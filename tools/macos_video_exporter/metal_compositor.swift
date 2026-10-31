import Foundation
import AVFoundation
import CoreMedia
import CoreVideo
import Metal
import ImageIO

/// Metal 3 全 GPU 零拷贝异步流水线合成引擎：
/// 1) `estimate_motion_grid` + `update_smooth_transform`：100% 在 GPU 上完成多块金字塔 SAD 运动估计与亚像素平滑滤波（彻底消除 CPU/Vision 同步等待）；
/// 2) `fused_stabilize_cas_and_blend_nv12`：单 Pass 融合 16-Tap Catmull-Rom 双三次防抖重采样 + CAS 自适应锐化 + 寄存器级紧凑图集 BT.709 叠加，仅写回一次 NV12 显存；
/// 3) 8 帧异步在途（8-In-Flight Command Buffers）+ 8 槽位图集纹理环 + 独立异步编码队列，消除 `waitUntilCompleted` 同步气泡，压榨 100% GPU 利用率。
let metalShaderSource = """
#include <metal_stdlib>
using namespace metal;

#define MAX_PATCHES 8

struct PatchParams {
    uint atlasX;
    uint atlasY;
    uint dstX;
    uint dstY;
    uint width;
    uint height;
    uint srcWidth;
    uint srcHeight;
};

struct FrameOverlayParams {
    uint patchCount;
    uint stabilize;
    uint width;
    uint height;
    uint srcWidth;
    uint srcHeight;
    uint videoDstX;
    uint videoDstY;
    uint videoDstW;
    uint videoDstH;
    float baseScale;
    float sharpness;
    float temporalAlpha;
    PatchParams patches[MAX_PATCHES];
};

struct SmoothState {
    float resX;
    float resY;
    float prevResX;
    float prevResY;
    float lastDx;
    float lastDy;
    float scale;
    float damping;
    float maxShiftX;
    float maxShiftY;
    float stepLimitX;
    float stepLimitY;
    uint hasPrev;
};

struct MotionSample {
    float dx;
    float dy;
    float sad;
    float valid;
};

/// 4-Tap Catmull-Rom 三次卷积权重
inline float4 cubic_weights(float f) {
    float f2 = f * f;
    float f3 = f2 * f;
    float w0 = -0.5f * f3 + f2 - 0.5f * f;
    float w1 =  1.5f * f3 - 2.5f * f2 + 1.0f;
    float w2 = -1.5f * f3 + 2.0f * f2 + 0.5f * f;
    float w3 =  0.5f * f3 - 0.5f * f2;
    return float4(w0, w1, w2, w3);
}

/// Lanczos-3 窗函数 sinc(x) * sinc(x/3)
inline float lanczos3_weight(float x) {
    float ax = fabs(x);
    if (ax < 1e-4f) return 1.0f;
    if (ax >= 3.0f) return 0.0f;
    float px = 3.14159265359f * ax;
    float px3 = px * (1.0f / 3.0f);
    return (sin(px) * sin(px3)) / (px * px3);
}

/// 36-Tap (6x6) Lanczos-3 窗口 Sinc 重采样 + 25-Tap (5x5 全邻域) 自适应细节锐化
inline float sample_lanczos3_cas_y(
    texture2d<float, access::sample> tex,
    sampler s,
    float srcX,
    float srcY,
    float invW,
    float invH,
    float sharpness
) {
    float px = srcX - 0.5f;
    float py = srcY - 0.5f;
    float ix = floor(px);
    float iy = floor(py);
    float fx = px - ix;
    float fy = py - iy;

    // 完整 6x6 (36-Tap) Lanczos-3 重采样
    float wxArr[6];
    float wyArr[6];
    float wxSum = 0.0f;
    float wySum = 0.0f;
    for (int k = 0; k < 6; ++k) {
        float wkx = lanczos3_weight(fx - float(k - 2));
        float wky = lanczos3_weight(fy - float(k - 2));
        wxArr[k] = wkx;
        wyArr[k] = wky;
        wxSum += wkx;
        wySum += wky;
    }
    float invNorm = 1.0f / max(1e-5f, wxSum * wySum);
    float lanczosVal = 0.0f;
    for (int j = -2; j <= 3; ++j) {
        float rowSum = 0.0f;
        float sy = (iy + float(j) + 0.5f) * invH;
        for (int i = -2; i <= 3; ++i) {
            float sx = (ix + float(i) + 0.5f) * invW;
            rowSum += tex.sample(s, float2(sx, sy)).r * wxArr[i + 2];
        }
        lanczosVal += rowSum * wyArr[j + 2];
    }
    lanczosVal *= invNorm;

    // 25-Tap (5x5 邻域) 边缘保持对比度自适应微细节增强 + 9-Tap CAS
    float2 uv = float2(srcX * invW, srcY * invH);
    float c = tex.sample(s, uv).r;
    float n = tex.sample(s, uv + float2(0.0f, -invH)).r;
    float so = tex.sample(s, uv + float2(0.0f, invH)).r;
    float w = tex.sample(s, uv + float2(-invW, 0.0f)).r;
    float e = tex.sample(s, uv + float2(invW, 0.0f)).r;
    float nw = tex.sample(s, uv + float2(-invW, -invH)).r;
    float ne = tex.sample(s, uv + float2(invW, -invH)).r;
    float sw = tex.sample(s, uv + float2(-invW, invH)).r;
    float se = tex.sample(s, uv + float2(invW, invH)).r;

    // 外环 16-Tap 双边细节能量统计
    float ringSum = 0.0f;
    float ringWeight = 0.0f;
    for (int rj = -2; rj <= 2; ++rj) {
        for (int ri = -2; ri <= 2; ++ri) {
            if (abs(ri) <= 1 && abs(rj) <= 1) continue;
            float sv = tex.sample(s, uv + float2(float(ri) * invW, float(rj) * invH)).r;
            float diff = fabs(sv - c);
            float bw = 1.0f / (1.0f + diff * 18.0f);
            ringSum += sv * bw;
            ringWeight += bw;
        }
    }
    float ringAvg = ringSum / max(1e-4f, ringWeight);
    float microDetail = clamp((lanczosVal - ringAvg) * 0.12f * sharpness, -0.03f, 0.03f);

    float minCross = min(c, min(min(n, so), min(w, e)));
    float maxCross = max(c, max(max(n, so), max(w, e)));
    float minDiag = min(min(nw, ne), min(sw, se));
    float maxDiag = max(max(nw, ne), max(sw, se));
    float minLum = 0.5f * (minCross + min(minCross, minDiag));
    float maxLum = 0.5f * (maxCross + max(maxCross, maxDiag));
    float amp = clamp(min(minLum, 1.0f - maxLum) / max(1e-4f, maxLum), 0.0f, 1.0f);
    float peak = -1.0f / mix(8.0f, 5.0f, sharpness);
    float weight = amp * peak;
    float diagW = weight * 0.25f;
    float num = lanczosVal + (n + so + w + e) * weight + (nw + ne + sw + se) * diagW;
    float den = 1.0f + 4.0f * weight + 4.0f * diagW;
    float casVal = clamp(num / den + microDetail, 0.0627f, 0.9216f);
    return casVal;
}

/// UV 色度平面 16-Tap 双三次 Catmull-Rom 采样
inline float2 sample_bicubic_uv(
    texture2d<float, access::sample> tex,
    sampler s,
    float srcX,
    float srcY,
    float invW,
    float invH
) {
    float uvW = 0.5f / invW;
    float uvH = 0.5f / invH;
    float invUvW = 1.0f / uvW;
    float invUvH = 1.0f / uvH;
    float px = srcX * 0.5f - 0.5f;
    float py = srcY * 0.5f - 0.5f;
    float ix = floor(px);
    float iy = floor(py);
    float4 wx = cubic_weights(px - ix);
    float4 wy = cubic_weights(py - iy);
    float wxArr[4] = {wx.x, wx.y, wx.z, wx.w};
    float wyArr[4] = {wy.x, wy.y, wy.z, wy.w};
    float2 sumUV = float2(0.0f);
    for (int j = -1; j <= 2; ++j) {
        float2 rowSum = float2(0.0f);
        float sy = (iy + float(j) + 0.5f) * invUvH;
        for (int i = -1; i <= 2; ++i) {
            float sx = (ix + float(i) + 0.5f) * invUvW;
            rowSum += tex.sample(s, float2(sx, sy)).rg * wxArr[i + 1];
        }
        sumUV += rowSum * wyArr[j + 1];
    }
    return clamp(sumUV, float2(0.0627f), float2(0.9412f));
}

/// 紧凑图集 RGBA 36-Tap (6x6) Lanczos-3 抗锯齿超分采样 + 9-Tap CAS 对比度自适应锐化
/// 用于将 1080p 基准图集（含下方 62% 高德卫星图/道路图与速度码表）超分锐化至 4K 目标画布，同时让全画布 100% 像素吃满 GPU 算力
inline float4 sample_bicubic_cas_rgba(
    texture2d<float, access::sample> tex,
    sampler s,
    float ax,
    float ay,
    float minX,
    float minY,
    float maxX,
    float maxY,
    float invAtlasW,
    float invAtlasH,
    float sharpness
) {
    float px = ax - 0.5f;
    float py = ay - 0.5f;
    float ix = floor(px);
    float iy = floor(py);
    float fx = px - ix;
    float fy = py - iy;

    float wxArr[6];
    float wyArr[6];
    float wxSum = 0.0f;
    float wySum = 0.0f;
    for (int k = 0; k < 6; ++k) {
        float wkx = lanczos3_weight(fx - float(k - 2));
        float wky = lanczos3_weight(fy - float(k - 2));
        wxArr[k] = wkx;
        wyArr[k] = wky;
        wxSum += wkx;
        wySum += wky;
    }
    float invNorm = 1.0f / max(1e-5f, wxSum * wySum);
    float4 sumRGBA = float4(0.0f);
    for (int j = -2; j <= 3; ++j) {
        float4 rowSum = float4(0.0f);
        float cy = clamp(iy + float(j) + 0.5f, minY, maxY) * invAtlasH;
        for (int i = -2; i <= 3; ++i) {
            float cx = clamp(ix + float(i) + 0.5f, minX, maxX) * invAtlasW;
            rowSum += tex.sample(s, float2(cx, cy)) * wxArr[i + 2];
        }
        sumRGBA += rowSum * wyArr[j + 2];
    }
    float4 bicubicVal = clamp(sumRGBA * invNorm, float4(0.0f), float4(1.0f));
    if (bicubicVal.a <= 0.002f) {
        return bicubicVal;
    }

    // 9-Tap 邻域对比度自适应锐化（增强高德卫星图中文地名与路线边缘清晰度）
    float cx0 = clamp(ax, minX, maxX) * invAtlasW;
    float cy0 = clamp(ay, minY, maxY) * invAtlasH;
    float cxW = clamp(ax - 1.0f, minX, maxX) * invAtlasW;
    float cxE = clamp(ax + 1.0f, minX, maxX) * invAtlasW;
    float cyN = clamp(ay - 1.0f, minY, maxY) * invAtlasH;
    float cyS = clamp(ay + 1.0f, minY, maxY) * invAtlasH;

    float3 c = bicubicVal.rgb;
    float3 n = tex.sample(s, float2(cx0, cyN)).rgb;
    float3 so = tex.sample(s, float2(cx0, cyS)).rgb;
    float3 w = tex.sample(s, float2(cxW, cy0)).rgb;
    float3 e = tex.sample(s, float2(cxE, cy0)).rgb;
    float3 nw = tex.sample(s, float2(cxW, cyN)).rgb;
    float3 ne = tex.sample(s, float2(cxE, cyN)).rgb;
    float3 sw = tex.sample(s, float2(cxW, cyS)).rgb;
    float3 se = tex.sample(s, float2(cxE, cyS)).rgb;

    float3 minCross = min(c, min(min(n, so), min(w, e)));
    float3 maxCross = max(c, max(max(n, so), max(w, e)));
    float3 minDiag = min(min(nw, ne), min(sw, se));
    float3 maxDiag = max(max(nw, ne), max(sw, se));
    float3 minRGB = 0.5f * (minCross + min(minCross, minDiag));
    float3 maxRGB = 0.5f * (maxCross + max(maxCross, maxDiag));
    float3 amp = clamp(min(minRGB, 1.0f - maxRGB) / max(float3(1e-4f), maxRGB), float3(0.0f), float3(1.0f));
    float peak = -1.0f / mix(8.5f, 5.2f, sharpness);
    float3 weight = amp * peak;
    float3 diagW = weight * 0.25f;
    float3 num = c + (n + so + w + e) * weight + (nw + ne + sw + se) * diagW;
    float3 den = float3(1.0f) + 4.0f * weight + 4.0f * diagW;
    float3 casRGB = clamp(num / max(float3(1e-4f), den), float3(0.0f), float3(1.0f));
    return float4(casRGB, bicubicVal.a);
}

/// Pass 1: 全 GPU 远景地平线 ROI 密集网格块匹配运动估计（32x16 = 512 个块，每块 256 线程并行搜索 9x9 邻域 SAD）
kernel void estimate_motion_grid(
    texture2d<float, access::sample> prevY [[texture(0)]],
    texture2d<float, access::sample> currY [[texture(1)]],
    device MotionSample *outSamples [[buffer(0)]],
    constant uint2 &dims [[buffer(1)]],
    uint2 groupId [[threadgroup_position_in_grid]],
    uint2 tid2 [[thread_position_in_threadgroup]],
    uint tid [[thread_index_in_threadgroup]]
) {
    threadgroup float s_sad[256];
    threadgroup float s_dx[256];
    threadgroup float s_dy[256];

    constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);
    float w = float(dims.x);
    float h = float(dims.y);
    float invW = 1.0f / w;
    float invH = 1.0f / h;

    // 聚焦地平线远景区域 (x: 24%..76%, y: 28%..64%)，共 32x16 = 512 个锚点块
    float anchorX = w * (0.24f + (float(groupId.x) + 0.5f) * (0.52f / 32.0f));
    float anchorY = h * (0.28f + (float(groupId.y) + 0.5f) * (0.36f / 16.0f));

    // 每个线程负责一个候选位移 (candDx, candDy)，步长 2.0 像素，覆盖 [-30, +30]
    float candDx = (float(int(tid2.x) - 8) + 0.5f) * 2.0f;
    float candDy = (float(int(tid2.y) - 8) + 0.5f) * 2.0f;

    float sad = 0.0f;
    float gradEnergy = 0.0f;
    for (int py = -12; py <= 12; py += 3) {
        for (int px = -12; px <= 12; px += 3) {
            float2 p0 = float2((anchorX + float(px)) * invW, (anchorY + float(py)) * invH);
            float2 p1 = float2((anchorX + float(px) + candDx) * invW, (anchorY + float(py) + candDy) * invH);
            float v0 = prevY.sample(s, p0).r;
            float v0_rx = prevY.sample(s, p0 + float2(2.0f * invW, 0.0f)).r;
            float v0_ry = prevY.sample(s, p0 + float2(0.0f, 2.0f * invH)).r;
            float v1 = currY.sample(s, p1).r;
            sad += fabs(v1 - v0);
            gradEnergy += fabs(v0_rx - v0) + fabs(v0_ry - v0);
        }
    }

    s_sad[tid] = sad;
    s_dx[tid] = candDx;
    s_dy[tid] = candDy;
    threadgroup_barrier(mem_flags::mem_threadgroup);

    // 树状归约寻找当前块的最小 SAD 候选位移
    for (uint stride = 128; stride > 0; stride >>= 1) {
        if (tid < stride) {
            if (s_sad[tid + stride] < s_sad[tid]) {
                s_sad[tid] = s_sad[tid + stride];
                s_dx[tid] = s_dx[tid + stride];
                s_dy[tid] = s_dy[tid + stride];
            }
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
    }

    if (tid == 0) {
        uint patchIdx = groupId.y * 32 + groupId.x;
        MotionSample ms;
        ms.dx = s_dx[0];
        ms.dy = s_dy[0];
        ms.sad = s_sad[0];
        // 排除纯天空或无纹理平坦区域（梯度能量过低）
        ms.valid = (gradEnergy > 0.35f) ? 1.0f : 0.0f;
        outSamples[patchIdx] = ms;
    }
}

/// Pass 2: 聚合 512 个块的运动向量并更新持久平滑防抖状态 SmoothState
kernel void update_smooth_transform(
    device const MotionSample *samples [[buffer(0)]],
    device SmoothState &state [[buffer(1)]],
    uint tid [[thread_index_in_threadgroup]]
) {
    threadgroup float s_wx[512];
    threadgroup float s_wy[512];
    threadgroup float s_w[512];

    if (tid < 512) {
        MotionSample m = samples[tid];
        float w = (m.valid > 0.5f && fabs(m.dx) <= state.stepLimitX && fabs(m.dy) <= state.stepLimitY)
            ? (1.0f / max(0.02f, m.sad))
            : 0.0f;
        s_wx[tid] = m.dx * w;
        s_wy[tid] = m.dy * w;
        s_w[tid] = w;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    for (uint stride = 256; stride > 0; stride >>= 1) {
        if (tid < stride) {
            s_wx[tid] += s_wx[tid + stride];
            s_wy[tid] += s_wy[tid + stride];
            s_w[tid] += s_w[tid + stride];
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
    }

    if (tid == 0) {
        if (state.hasPrev == 0) {
            state.resX = 0.0f;
            state.resY = 0.0f;
            state.prevResX = 0.0f;
            state.prevResY = 0.0f;
            state.lastDx = 0.0f;
            state.lastDy = 0.0f;
            state.hasPrev = 1;
        } else if (s_w[0] > 1e-4f) {
            float dx = s_wx[0] / s_w[0];
            float dy = s_wy[0] / s_w[0];
            state.prevResX = state.resX;
            state.prevResY = state.resY;
            state.lastDx = dx;
            state.lastDy = dy;
            state.resX = clamp((state.resX + dx) * state.damping, -state.maxShiftX, state.maxShiftX);
            state.resY = clamp((state.resY + dy) * state.damping, -state.maxShiftY, state.maxShiftY);
        } else {
            state.prevResX = state.resX;
            state.prevResY = state.resY;
            state.lastDx = 0.0f;
            state.lastDy = 0.0f;
            state.resX *= state.damping;
            state.resY *= state.damping;
        }
    }
}

/// Pass 3: 单 Pass 融合防抖重采样（36-Tap Lanczos-3 + 25-Tap 细节增强 + 60fps 亚帧运动补偿插值）+ 寄存器级紧凑图集 RGBA->NV12 混合
kernel void fused_stabilize_cas_and_blend_nv12(
    texture2d<float, access::sample> srcY [[texture(0)]],
    texture2d<float, access::sample> srcUV [[texture(1)]],
    texture2d<float, access::sample> atlasTex [[texture(2)]],
    texture2d<float, access::write> dstY [[texture(3)]],
    texture2d<float, access::write> dstUV [[texture(4)]],
    texture2d<float, access::sample> prevY [[texture(5)]],
    texture2d<float, access::sample> prevUV [[texture(6)]],
    constant FrameOverlayParams &ov [[buffer(0)]],
    device const SmoothState &state [[buffer(1)]],
    uint2 gid [[thread_position_in_grid]]
) {
    uint lx = gid.x * 2;
    uint ly = gid.y * 2;
    if (lx >= ov.width || ly >= ov.height) return;

    constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);
    float srcW = float(max(1u, ov.srcWidth));
    float srcH = float(max(1u, ov.srcHeight));
    float invW = 1.0f / srcW;
    float invH = 1.0f / srcH;
    float halfSrcW = srcW * 0.5f;
    float halfSrcH = srcH * 0.5f;
    float invAtlasW = 1.0f / float(max(1u, atlasTex.get_width()));
    float invAtlasH = 1.0f / float(max(1u, atlasTex.get_height()));
    float vDstX = float(ov.videoDstX);
    float vDstY = float(ov.videoDstY);
    float vDstW = float(max(1u, ov.videoDstW));
    float vDstH = float(max(1u, ov.videoDstH));
    float halfDstW = vDstW * 0.5f;
    float halfDstH = vDstH * 0.5f;
    // Center-Crop 等比铺满目标视频视口 (videoDstX, videoDstY, videoDstW, videoDstH)
    float coverScale = max(vDstW / srcW, vDstH / srcH);
    float scale = (ov.stabilize != 0) ? ov.baseScale : 1.0f;
    float totalScale = max(1e-4f, coverScale * scale);
    float tAlpha = clamp(ov.temporalAlpha, 0.0f, 1.0f);
    float shiftX = (ov.stabilize != 0) ? mix(state.prevResX, state.resX, tAlpha) : 0.0f;
    float shiftY = (ov.stabilize != 0) ? mix(state.prevResY, state.resY, tAlpha) : 0.0f;
    float prevShiftX = (ov.stabilize != 0) ? state.prevResX : 0.0f;
    float prevShiftY = (ov.stabilize != 0) ? state.prevResY : 0.0f;

    // 先检查当前 2x2 块是否命中任意 HUD / 地图 / 速度码表覆盖层区域
    int hitPatch = -1;
    for (uint i = 0; i < ov.patchCount; ++i) {
        PatchParams p = ov.patches[i];
        if (lx >= p.dstX && lx < p.dstX + p.width && ly >= p.dstY && ly < p.dstY + p.height) {
            hitPatch = int(i);
            break;
        }
    }

    float2 uv_overlay_accum = float2(0.0f);
    float a_overlay_accum = 0.0f;
    bool inVideoRect = (lx >= ov.videoDstX && lx < ov.videoDstX + ov.videoDstW &&
                        ly >= ov.videoDstY && ly < ov.videoDstY + ov.videoDstH);

    for (uint dy = 0; dy < 2; ++dy) {
        for (uint dx = 0; dx < 2; ++dx) {
            uint px = lx + dx;
            uint py = ly + dy;
            float yVal;
            if (inVideoRect) {
                float relX = (float(px) + 0.5f - vDstX) - halfDstW;
                float relY = (float(py) + 0.5f - vDstY) - halfDstH;
                float sx = relX / totalScale + halfSrcW + shiftX;
                float sy = relY / totalScale + halfSrcH + shiftY;
                float curY = sample_lanczos3_cas_y(srcY, s, sx, sy, invW, invH, ov.sharpness);
                if (ov.stabilize != 0) {
                    if (tAlpha < 0.99f && state.hasPrev != 0) {
                        float psx = relX / totalScale + halfSrcW + prevShiftX;
                        float psy = relY / totalScale + halfSrcH + prevShiftY;
                        float prvY = sample_lanczos3_cas_y(prevY, s, psx, psy, invW, invH, ov.sharpness);
                        float ghostGuard = clamp(1.0f - fabs(curY - prvY) * 6.0f, 0.0f, 1.0f);
                        float blendW = mix(1.0f, tAlpha, ghostGuard);
                        yVal = mix(prvY, curY, blendW);
                    } else {
                        yVal = curY;
                    }
                } else {
                    yVal = curY;
                }
            } else {
                yVal = 0.08f;
            }

            if (hitPatch >= 0) {
                PatchParams p = ov.patches[hitPatch];
                uint relPx = px - p.dstX;
                uint relPy = py - p.dstY;
                uint srcW = max(1u, p.srcWidth);
                uint srcH = max(1u, p.srcHeight);
                uint dstW = max(1u, p.width);
                uint dstH = max(1u, p.height);
                float ax = float(p.atlasX) + ((float(relPx) + 0.5f) * float(srcW)) / float(dstW);
                float ay = float(p.atlasY) + ((float(relPy) + 0.5f) * float(srcH)) / float(dstH);
                float minX = float(p.atlasX) + 0.5f;
                float minY = float(p.atlasY) + 0.5f;
                float maxX = float(p.atlasX + srcW) - 0.5f;
                float maxY = float(p.atlasY + srcH) - 0.5f;
                float4 rgba = sample_bicubic_cas_rgba(
                    atlasTex, s, ax, ay, minX, minY, maxX, maxY, invAtlasW, invAtlasH, ov.sharpness
                );
                float a = rgba.a;
                if (a > 0.002f) {
                    float oy = (16.0f + 219.0f * (0.2126f * rgba.r + 0.7152f * rgba.g + 0.0722f * rgba.b)) / 255.0f;
                    yVal = yVal * (1.0f - a) + oy * a;
                    float cb = (128.0f + 224.0f * (-0.114572f * rgba.r - 0.385428f * rgba.g + 0.5f * rgba.b)) / 255.0f;
                    float cr = (128.0f + 224.0f * (0.5f * rgba.r - 0.454153f * rgba.g - 0.045847f * rgba.b)) / 255.0f;
                    uv_overlay_accum += float2(cb, cr) * a;
                    a_overlay_accum += a;
                }
            }
            dstY.write(float4(yVal, 0.0f, 0.0f, 1.0f), uint2(px, py));
        }
    }

    float2 uvVal = float2(0.5f, 0.5f);
    if (inVideoRect) {
        float relCx = (float(lx) + 1.0f - vDstX) - halfDstW;
        float relCy = (float(ly) + 1.0f - vDstY) - halfDstH;
        float scx = relCx / totalScale + halfSrcW + shiftX;
        float scy = relCy / totalScale + halfSrcH + shiftY;
        uvVal = sample_bicubic_uv(srcUV, s, scx, scy, invW, invH);
        if (ov.stabilize != 0 && tAlpha < 0.99f && state.hasPrev != 0) {
            float p_sx = relCx / totalScale + halfSrcW + prevShiftX;
            float p_sy = relCy / totalScale + halfSrcH + prevShiftY;
            float2 prvUV = sample_bicubic_uv(prevUV, s, p_sx, p_sy, invW, invH);
            uvVal = mix(prvUV, uvVal, tAlpha);
        }
    }

    if (a_overlay_accum > 0.004f) {
        float avg_a = a_overlay_accum * 0.25f;
        float2 avg_uv = uv_overlay_accum / a_overlay_accum;
        uvVal = uvVal * (1.0f - avg_a) + avg_uv * avg_a;
    }
    dstUV.write(float4(uvVal.x, uvVal.y, 0.0f, 1.0f), gid);
}

kernel void rgba_to_nv12(
    texture2d<float, access::sample> srcRGBA [[texture(0)]],
    texture2d<float, access::write> dstY [[texture(1)]],
    texture2d<float, access::write> dstUV [[texture(2)]],
    uint2 gid [[thread_position_in_grid]]
) {
    uint w = dstY.get_width();
    uint h = dstY.get_height();
    uint lx = gid.x * 2;
    uint ly = gid.y * 2;
    if (lx >= w || ly >= h) return;

    constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);
    float invW = 1.0 / float(w);
    float invH = 1.0 / float(h);
    float2 uv_accum = float2(0.0);

    for (uint dy = 0; dy < 2; ++dy) {
        for (uint dx = 0; dx < 2; ++dx) {
            uint px = lx + dx;
            uint py = ly + dy;
            if (px >= w || py >= h) continue;
            float4 c = srcRGBA.sample(s, float2((float(px) + 0.5) * invW, (float(py) + 0.5) * invH));
            float r = c.r;
            float g = c.g;
            float b = c.b;
            float y_val = (16.0 + 219.0 * (0.2126 * r + 0.7152 * g + 0.0722 * b)) / 255.0;
            dstY.write(float4(y_val, 0.0, 0.0, 1.0), uint2(px, py));
            float cb = (128.0 + 224.0 * (-0.114572 * r - 0.385428 * g + 0.5 * b)) / 255.0;
            float cr = (128.0 + 224.0 * (0.5 * r - 0.454153 * g - 0.045847 * b)) / 255.0;
            uv_accum += float2(cb, cr);
        }
    }
    float2 avg_uv = uv_accum * 0.25;
    dstUV.write(float4(avg_uv.x, avg_uv.y, 0.0, 1.0), gid);
}
"""

struct PatchSpec: Codable {
    let name: String
    let screenX: Int
    let screenY: Int
    let width: Int
    let height: Int
    let atlasX: Int
    let atlasY: Int
    let srcWidth: Int?
    let srcHeight: Int?
}

struct SegmentSpec: Codable {
    let path: String
    let durationMs: Int
}

struct IntroSlideSpec: Codable {
    let imagePath: String
    let durationSec: Double
}

struct JobConfig: Codable {
    let segments: [SegmentSpec]
    let introSlides: [IntroSlideSpec]?
    let startMs: Int
    let sourceDurationMs: Int
    let playbackSpeed: Double
    let fps: Int
    let atlasFps: Double?
    let bitrate: Int
    let codec: String
    let stabilize: Bool?
    let stabilizeStrength: Int?
    let stabilizeCropPercent: Int?
    let outputWidth: Int?
    let outputHeight: Int?
    let videoDstX: Int?
    let videoDstY: Int?
    let videoDstW: Int?
    let videoDstH: Int?
    let atlasWidth: Int
    let atlasHeight: Int
    let patches: [PatchSpec]
    let outputPath: String
}

struct PatchParams {
    var atlasX: UInt32 = 0
    var atlasY: UInt32 = 0
    var dstX: UInt32 = 0
    var dstY: UInt32 = 0
    var width: UInt32 = 0
    var height: UInt32 = 0
    var srcWidth: UInt32 = 0
    var srcHeight: UInt32 = 0
}

struct FrameOverlayParams {
    var patchCount: UInt32
    var stabilize: UInt32
    var width: UInt32
    var height: UInt32
    var srcWidth: UInt32
    var srcHeight: UInt32
    var videoDstX: UInt32
    var videoDstY: UInt32
    var videoDstW: UInt32
    var videoDstH: UInt32
    var baseScale: Float
    var sharpness: Float
    var temporalAlpha: Float
    var patches: (PatchParams, PatchParams, PatchParams, PatchParams, PatchParams, PatchParams, PatchParams, PatchParams)
}

struct SmoothState {
    var resX: Float
    var resY: Float
    var prevResX: Float
    var prevResY: Float
    var lastDx: Float
    var lastDy: Float
    var scale: Float
    var damping: Float
    var maxShiftX: Float
    var maxShiftY: Float
    var stepLimitX: Float
    var stepLimitY: Float
    var hasPrev: UInt32
}

struct MotionSample {
    var dx: Float
    var dy: Float
    var sad: Float
    var valid: Float
}

func readExactStdin(into buffer: UnsafeMutableRawPointer, count: Int) -> Bool {
    var offset = 0
    while offset < count {
        let n = fread(buffer.advanced(by: offset), 1, count - offset, stdin)
        if n <= 0 {
            return false
        }
        offset += n
    }
    return true
}

guard CommandLine.arguments.count >= 2 else {
    FileHandle.standardError.write("Usage: carspeed_metal_compositor <config.json>\n".data(using: .utf8)!)
    exit(2)
}

let configURL = URL(fileURLWithPath: CommandLine.arguments[1])
let configData = try Data(contentsOf: configURL)
let config = try JSONDecoder().decode(JobConfig.self, from: configData)

let outputURL = URL(fileURLWithPath: config.outputPath)
try? FileManager.default.removeItem(at: outputURL)

let composition = AVMutableComposition()
guard let compTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
    FileHandle.standardError.write("Failed to create composition track\n".data(using: .utf8)!)
    exit(1)
}

var cursorTime = CMTime.zero
var naturalWidth = 3840
var naturalHeight = 2160
for seg in config.segments {
    let url = URL(fileURLWithPath: seg.path)
    let asset = AVURLAsset(url: url)
    guard let track = asset.tracks(withMediaType: .video).first else { continue }
    let size = track.naturalSize
    if size.width > 0 && size.height > 0 {
        naturalWidth = Int(size.width)
        naturalHeight = Int(size.height)
    }
    let dur = CMTime(value: CMTimeValue(seg.durationMs), timescale: 1000)
    let range = CMTimeRange(start: .zero, duration: dur)
    try compTrack.insertTimeRange(range, of: track, at: cursorTime)
    cursorTime = CMTimeAdd(cursorTime, dur)
}

let outWidth = max(16, (config.outputWidth ?? naturalWidth) & ~1)
let outHeight = max(16, (config.outputHeight ?? naturalHeight) & ~1)
let vDstX = max(0, (config.videoDstX ?? 0) & ~1)
let vDstY = max(0, (config.videoDstY ?? 0) & ~1)
let vDstW = max(16, (config.videoDstW ?? outWidth) & ~1)
let vDstH = max(16, (config.videoDstH ?? outHeight) & ~1)

guard let device = MTLCreateSystemDefaultDevice(),
      let queue = device.makeCommandQueue(maxCommandBufferCount: 64) else {
    FileHandle.standardError.write("Metal device unavailable\n".data(using: .utf8)!)
    exit(1)
}

let library = try device.makeLibrary(source: metalShaderSource, options: nil)
let motionFn = library.makeFunction(name: "estimate_motion_grid")!
let motionPso = try device.makeComputePipelineState(function: motionFn)
let smoothFn = library.makeFunction(name: "update_smooth_transform")!
let smoothPso = try device.makeComputePipelineState(function: smoothFn)
let fusedFn = library.makeFunction(name: "fused_stabilize_cas_and_blend_nv12")!
let fusedPso = try device.makeComputePipelineState(function: fusedFn)
let rgbaToNv12Fn = library.makeFunction(name: "rgba_to_nv12")!
let rgbaToNv12Pso = try device.makeComputePipelineState(function: rgbaToNv12Fn)

var texCache: CVMetalTextureCache?
CVMetalTextureCacheCreate(nil, nil, device, nil, &texCache)

let reader = try AVAssetReader(asset: composition)
let startCM = CMTime(value: CMTimeValue(config.startMs), timescale: 1000)
let durCM = CMTime(value: CMTimeValue(config.sourceDurationMs), timescale: 1000)
reader.timeRange = CMTimeRange(start: startCM, duration: durCM)

let readerOutput = AVAssetReaderTrackOutput(track: compTrack, outputSettings: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
    kCVPixelBufferMetalCompatibilityKey as String: true,
    kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
])
readerOutput.alwaysCopiesSampleData = false
reader.add(readerOutput)

let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
let codecType: AVVideoCodecType = (config.codec.lowercased() == "hevc") ? .hevc : .h264
var compProps: [String: Any] = [
    AVVideoAverageBitRateKey: config.bitrate,
    AVVideoExpectedSourceFrameRateKey: config.fps,
    AVVideoMaxKeyFrameIntervalKey: config.fps * 2,
    AVVideoAllowFrameReorderingKey: false,
    "RealTime": true,
    "PrioritizeEncodingSpeedOverQuality": true,
]
if codecType == .h264 {
    compProps[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel
}

let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: codecType,
    AVVideoWidthKey: outWidth,
    AVVideoHeightKey: outHeight,
    AVVideoCompressionPropertiesKey: compProps,
])
writerInput.expectsMediaDataInRealTime = false
writerInput.mediaTimeScale = 15360

let stabilizeEnabled = config.stabilize ?? false
let introSlides = config.introSlides ?? []
let stabStrength = max(10, min(95, config.stabilizeStrength ?? 65))
let stabCropPct = max(3, min(15, config.stabilizeCropPercent ?? 6))
let stabScale = Float(1.0 + Double(stabCropPct) / 100.0)
let stabDamping = Float(0.72 + (Double(stabStrength) / 100.0) * 0.24)
let maxShiftX = Float(Double(naturalWidth) * (Double(stabCropPct) / 100.0) * 0.48)
let maxShiftY = Float(Double(naturalHeight) * (Double(stabCropPct) / 100.0) * 0.48)
let stepLimitX = Float(Double(naturalWidth) * 0.045)
let stepLimitY = Float(Double(naturalHeight) * 0.045)

let poolAttrs: [String: Any] = [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
    kCVPixelBufferWidthKey as String: outWidth,
    kCVPixelBufferHeightKey as String: outHeight,
    kCVPixelBufferMetalCompatibilityKey as String: true,
    kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
]

let adaptor = AVAssetWriterInputPixelBufferAdaptor(
    assetWriterInput: writerInput,
    sourcePixelBufferAttributes: poolAttrs
)
writer.add(writerInput)

let atlasW = max(16, config.atlasWidth)
let atlasH = max(16, config.atlasHeight)
let atlasByteCount = atlasW * atlasH * 4
let rawBuffer = UnsafeMutableRawPointer.allocate(byteCount: atlasByteCount, alignment: 64)
defer { rawBuffer.deallocate() }

// 10 槽位图集纹理环：配合有界异步流水线，既避免图集覆写竞争，又将显存/统一内存峰值降到最低
let atlasRingSize = 10
let texDesc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: atlasW, height: atlasH, mipmapped: false)
texDesc.usage = [.shaderRead]
var atlasRing: [MTLTexture] = []
for _ in 0..<atlasRingSize {
    atlasRing.append(device.makeTexture(descriptor: texDesc)!)
}
var atlasRingIndex = 0

var fixedPatches = (
    PatchParams(), PatchParams(), PatchParams(), PatchParams(),
    PatchParams(), PatchParams(), PatchParams(), PatchParams()
)
let patchCount = min(8, config.patches.count)
withUnsafeMutablePointer(to: &fixedPatches) { tupPtr in
    let arrPtr = UnsafeMutableRawPointer(tupPtr).assumingMemoryBound(to: PatchParams.self)
    for i in 0..<patchCount {
        let p = config.patches[i]
        arrPtr[i] = PatchParams(
            atlasX: UInt32(p.atlasX),
            atlasY: UInt32(p.atlasY),
            dstX: UInt32(p.screenX),
            dstY: UInt32(p.screenY),
            width: UInt32(p.width),
            height: UInt32(p.height),
            srcWidth: UInt32(p.srcWidth ?? p.width),
            srcHeight: UInt32(p.srcHeight ?? p.height)
        )
    }
}

var overlayParams = FrameOverlayParams(
    patchCount: UInt32(patchCount),
    stabilize: stabilizeEnabled ? 1 : 0,
    width: UInt32(outWidth),
    height: UInt32(outHeight),
    srcWidth: UInt32(naturalWidth),
    srcHeight: UInt32(naturalHeight),
    videoDstX: UInt32(vDstX),
    videoDstY: UInt32(vDstY),
    videoDstW: UInt32(vDstW),
    videoDstH: UInt32(vDstH),
    baseScale: stabScale,
    sharpness: 0.55,
    temporalAlpha: 1.0,
    patches: fixedPatches
)

// GPU 常驻运动估计样本缓冲与平滑状态缓冲
let motionSamplesBuf = device.makeBuffer(length: MemoryLayout<MotionSample>.stride * 512, options: .storageModeShared)!
var initialSmooth = SmoothState(
    resX: 0.0,
    resY: 0.0,
    prevResX: 0.0,
    prevResY: 0.0,
    lastDx: 0.0,
    lastDy: 0.0,
    scale: stabScale,
    damping: stabDamping,
    maxShiftX: maxShiftX,
    maxShiftY: maxShiftY,
    stepLimitX: stepLimitX,
    stepLimitY: stepLimitY,
    hasPrev: 0
)
let smoothStateBuf = device.makeBuffer(bytes: &initialSmooth, length: MemoryLayout<SmoothState>.stride, options: .storageModeShared)!

reader.startReading()
writer.startWriting()
writer.startSession(atSourceTime: .zero)

let speed = max(0.25, config.playbackSpeed)
let fps = max(1, config.fps)
let atlasFps = max(0.25, config.atlasFps ?? 1.0)
let stepOutSec = 1.0 / Double(fps)
var nextTargetOutSec = 0.0
var lastAppendedTicks: CMTimeValue = -1
var currentAtlasSec = -1
var hasAtlasLoaded = false

// 片头全屏高德卫星图/道路图渲染为 NV12 PixelBuffer 并在片头输出
var introOffsetSec: Double = 0.0
if !introSlides.isEmpty, let pool = adaptor.pixelBufferPool {
    for slide in introSlides {
        let dur = max(0.0, slide.durationSec)
        if dur <= 0.01 { continue }
        let imgURL = URL(fileURLWithPath: slide.imagePath)
        guard let src = CGImageSourceCreateWithURL(imgURL as CFURL, nil),
              let cgImg = CGImageSourceCreateImageAtIndex(src, 0, nil) else { continue }
        let iw = cgImg.width
        let ih = cgImg.height
        let rgbaBytes = UnsafeMutableRawPointer.allocate(byteCount: iw * ih * 4, alignment: 64)
        defer { rgbaBytes.deallocate() }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        if let ctx = CGContext(data: rgbaBytes, width: iw, height: ih, bitsPerComponent: 8, bytesPerRow: iw * 4, space: colorSpace, bitmapInfo: bitmapInfo) {
            ctx.draw(cgImg, in: CGRect(x: 0, y: 0, width: iw, height: ih))
        }
        let sDesc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: iw, height: ih, mipmapped: false)
        sDesc.usage = [.shaderRead]
        guard let slideTex = device.makeTexture(descriptor: sDesc) else { continue }
        slideTex.replace(region: MTLRegionMake2D(0, 0, iw, ih), mipmapLevel: 0, withBytes: rgbaBytes, bytesPerRow: iw * 4)

        var slidePixOpt: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &slidePixOpt) == kCVReturnSuccess,
              let slidePix = slidePixOpt else { continue }
        var dyW: CVMetalTexture?
        var duvW: CVMetalTexture?
        CVMetalTextureCacheCreateTextureFromImage(nil, texCache!, slidePix, nil, .r8Unorm, outWidth, outHeight, 0, &dyW)
        CVMetalTextureCacheCreateTextureFromImage(nil, texCache!, slidePix, nil, .rg8Unorm, outWidth / 2, outHeight / 2, 1, &duvW)
        if let yWrap = dyW, let uvWrap = duvW,
           let yTex = CVMetalTextureGetTexture(yWrap),
           let uvTex = CVMetalTextureGetTexture(uvWrap),
           let cmdBuf = queue.makeCommandBuffer(),
           let enc = cmdBuf.makeComputeCommandEncoder() {
            enc.setComputePipelineState(rgbaToNv12Pso)
            enc.setTexture(slideTex, index: 0)
            enc.setTexture(yTex, index: 1)
            enc.setTexture(uvTex, index: 2)
            let tg = MTLSize(width: 16, height: 16, depth: 1)
            let gw = (outWidth / 2 + 15) / 16 * 16
            let gh = (outHeight / 2 + 15) / 16 * 16
            enc.dispatchThreads(MTLSize(width: gw, height: gh, depth: 1), threadsPerThreadgroup: tg)
            enc.endEncoding()
            cmdBuf.commit()
            cmdBuf.waitUntilCompleted()
        }
        let slideFrames = max(1, Int(round(dur * Double(fps))))
        for fIdx in 0..<slideFrames {
            while !writerInput.isReadyForMoreMediaData {
                Thread.sleep(forTimeInterval: 0.0003)
            }
            let tSec = introOffsetSec + Double(fIdx) * stepOutSec
            var pts = CMTime(seconds: tSec, preferredTimescale: 15360)
            if pts.value <= lastAppendedTicks {
                pts = CMTime(value: lastAppendedTicks + 1, timescale: 15360)
            }
            lastAppendedTicks = pts.value
            adaptor.append(slidePix, withPresentationTime: pts)
        }
        introOffsetSec += Double(slideFrames) * stepOutSec
    }
}

// 10 帧异步在途信号量 + 串行有序写入队列，既保持 GPU 流水线满载，又严格限制峰值内存为 O(1)
let maxInFlightFrames = 10
let inFlightSemaphore = DispatchSemaphore(value: maxInFlightFrames)
let writerQueue = DispatchQueue(label: "carspeed.metal.writer", qos: .userInteractive)
var prevSrcPixBuf: CVPixelBuffer?
var prevYTextureWrapper: CVMetalTexture?
var prevUVTextureWrapper: CVMetalTexture?
var frameDims = SIMD2<UInt32>(UInt32(naturalWidth), UInt32(naturalHeight))
var prevOutSec: Double = 0.0
var processedSampleCount = 0

while true {
    var shouldBreak = false
    autoreleasepool {
        guard let sample = readerOutput.copyNextSampleBuffer() else {
            shouldBreak = true
            return
        }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        let srcElapsedSec = max(0.0, CMTimeGetSeconds(CMTimeSubtract(pts, startCM)))
        let outSec = srcElapsedSec / speed
        if outSec + 0.003 < nextTargetOutSec {
            return
        }

        guard let srcPixBuf = CMSampleBufferGetImageBuffer(sample),
              let pool = adaptor.pixelBufferPool else { return }

        var srcYWrap: CVMetalTexture?
        var srcUVWrap: CVMetalTexture?
        CVMetalTextureCacheCreateTextureFromImage(nil, texCache!, srcPixBuf, nil, .r8Unorm, naturalWidth, naturalHeight, 0, &srcYWrap)
        CVMetalTextureCacheCreateTextureFromImage(nil, texCache!, srcPixBuf, nil, .rg8Unorm, naturalWidth / 2, naturalHeight / 2, 1, &srcUVWrap)

        guard let syW = srcYWrap, let suvW = srcUVWrap,
              let syTex = CVMetalTextureGetTexture(syW),
              let suvTex = CVMetalTextureGetTexture(suvW) else {
            return
        }

        let capturedPrevBuf = prevSrcPixBuf
        let capturedPrevYWrap = prevYTextureWrapper
        let capturedPrevUVWrap = prevUVTextureWrapper
        var didUpdateMotionForSample = false
        var emittedForSample = 0

        // 当目标输出帧率 (如 60fps) 高于源视频帧率 (如 30fps) 时，按时间步长生成亚帧运动补偿插值输出帧，确保真正满帧 60fps 输出且压满 GPU
        while nextTargetOutSec <= outSec + 0.0025 && emittedForSample < 4 {
            let frameOutSec = min(nextTargetOutSec, outSec)
            nextTargetOutSec += stepOutSec
            emittedForSample += 1

            let targetAtlasSec = Int(floor(frameOutSec * atlasFps))
            while currentAtlasSec < targetAtlasSec {
                if readExactStdin(into: rawBuffer, count: atlasByteCount) {
                    atlasRingIndex = (atlasRingIndex + 1) % atlasRingSize
                    atlasRing[atlasRingIndex].replace(
                        region: MTLRegionMake2D(0, 0, atlasW, atlasH),
                        mipmapLevel: 0,
                        withBytes: rawBuffer,
                        bytesPerRow: atlasW * 4
                    )
                    hasAtlasLoaded = true
                }
                currentAtlasSec += 1
            }

            inFlightSemaphore.wait()

            var dstOpt: CVPixelBuffer?
            guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &dstOpt) == kCVReturnSuccess,
                  let dstBuf = dstOpt else {
                inFlightSemaphore.signal()
                break
            }

            var dstYWrap: CVMetalTexture?
            var dstUVWrap: CVMetalTexture?
            CVMetalTextureCacheCreateTextureFromImage(nil, texCache!, dstBuf, nil, .r8Unorm, outWidth, outHeight, 0, &dstYWrap)
            CVMetalTextureCacheCreateTextureFromImage(nil, texCache!, dstBuf, nil, .rg8Unorm, outWidth / 2, outHeight / 2, 1, &dstUVWrap)

            guard let dyW = dstYWrap, let duvW = dstUVWrap,
                  let dyTex = CVMetalTextureGetTexture(dyW),
                  let duvTex = CVMetalTextureGetTexture(duvW),
                  let cmdBuf = queue.makeCommandBuffer(),
                  let enc = cmdBuf.makeComputeCommandEncoder() else {
                inFlightSemaphore.signal()
                break
            }

            let activeAtlasTex = atlasRing[atlasRingIndex]
            let prevYTex = (capturedPrevYWrap != nil) ? CVMetalTextureGetTexture(capturedPrevYWrap!) : nil
            let prevUVTex = (capturedPrevUVWrap != nil) ? CVMetalTextureGetTexture(capturedPrevUVWrap!) : nil

            if stabilizeEnabled && !didUpdateMotionForSample {
                if let pYTex = prevYTex {
                    // Pass 1: 全 GPU 512 块密集运动估计
                    enc.setComputePipelineState(motionPso)
                    enc.setTexture(pYTex, index: 0)
                    enc.setTexture(syTex, index: 1)
                    enc.setBuffer(motionSamplesBuf, offset: 0, index: 0)
                    enc.setBytes(&frameDims, length: MemoryLayout<SIMD2<UInt32>>.stride, index: 1)
                    enc.dispatchThreadgroups(MTLSize(width: 32, height: 16, depth: 1), threadsPerThreadgroup: MTLSize(width: 16, height: 16, depth: 1))

                    // Pass 2: 全 GPU 归约与平滑防抖状态更新
                    enc.memoryBarrier(scope: .buffers)
                    enc.setComputePipelineState(smoothPso)
                    enc.setBuffer(motionSamplesBuf, offset: 0, index: 0)
                    enc.setBuffer(smoothStateBuf, offset: 0, index: 1)
                    enc.dispatchThreadgroups(MTLSize(width: 1, height: 1, depth: 1), threadsPerThreadgroup: MTLSize(width: 512, height: 1, depth: 1))
                    enc.memoryBarrier(scope: .buffers)
                }
                didUpdateMotionForSample = true
            }

            // 计算亚帧时间插值权重 temporalAlpha (0..1)
            let spanSec = max(1e-4, outSec - prevOutSec)
            let tAlpha: Float = (capturedPrevYWrap != nil && outSec > prevOutSec + 0.001)
                ? Float(max(0.25, min(1.0, (frameOutSec - prevOutSec) / spanSec)))
                : 1.0

            // Pass 3: 单 Pass 融合 36-Tap Lanczos-3 防抖 + 25-Tap 细节增强 + 亚帧运动补偿 + 紧凑图集寄存器级混合
            var localOv = overlayParams
            localOv.temporalAlpha = tAlpha
            if !hasAtlasLoaded {
                localOv.patchCount = 0
            }
            enc.setComputePipelineState(fusedPso)
            enc.setTexture(syTex, index: 0)
            enc.setTexture(suvTex, index: 1)
            enc.setTexture(activeAtlasTex, index: 2)
            enc.setTexture(dyTex, index: 3)
            enc.setTexture(duvTex, index: 4)
            enc.setTexture(prevYTex ?? syTex, index: 5)
            enc.setTexture(prevUVTex ?? suvTex, index: 6)
            enc.setBytes(&localOv, length: MemoryLayout<FrameOverlayParams>.stride, index: 0)
            enc.setBuffer(smoothStateBuf, offset: 0, index: 1)
            let tg = MTLSize(width: 16, height: 16, depth: 1)
            let gw = (outWidth / 2 + 15) / 16 * 16
            let gh = (outHeight / 2 + 15) / 16 * 16
            enc.dispatchThreads(MTLSize(width: gw, height: gh, depth: 1), threadsPerThreadgroup: tg)
            enc.endEncoding()

            var nextOutPts = CMTime(seconds: introOffsetSec + frameOutSec, preferredTimescale: 15360)
            if nextOutPts.value <= lastAppendedTicks {
                nextOutPts = CMTime(value: lastAppendedTicks + 1, timescale: 15360)
            }
            lastAppendedTicks = nextOutPts.value
            let submitPts = nextOutPts

            cmdBuf.addCompletedHandler { _ in
                _ = syW
                _ = suvW
                _ = dyW
                _ = duvW
                _ = srcPixBuf
                _ = capturedPrevBuf
                _ = capturedPrevYWrap
                _ = capturedPrevUVWrap
                writerQueue.async {
                    while !writerInput.isReadyForMoreMediaData {
                        Thread.sleep(forTimeInterval: 0.0003)
                    }
                    adaptor.append(dstBuf, withPresentationTime: submitPts)
                    inFlightSemaphore.signal()
                }
            }
            cmdBuf.commit()
        }

        if emittedForSample > 0 {
            prevSrcPixBuf = srcPixBuf
            prevYTextureWrapper = syW
            prevUVTextureWrapper = suvW
            prevOutSec = outSec
        }
        if nextTargetOutSec <= outSec {
            nextTargetOutSec = outSec + stepOutSec
        }
        processedSampleCount += 1
        if processedSampleCount % 30 == 0, let cache = texCache {
            CVMetalTextureCacheFlush(cache, 0)
        }
    }
    if shouldBreak {
        break
    }
}

// 等待所有在途 GPU 帧与异步编码队列全部排空
for _ in 0..<maxInFlightFrames {
    inFlightSemaphore.wait()
}
for _ in 0..<maxInFlightFrames {
    inFlightSemaphore.signal()
}
writerQueue.sync {}

writerInput.markAsFinished()
let sem = DispatchSemaphore(value: 0)
writer.finishWriting {
    sem.signal()
}
sem.wait()

if writer.status != .completed {
    let errStr = writer.error?.localizedDescription ?? "Unknown AVAssetWriter error"
    FileHandle.standardError.write("Writer failed: \(errStr)\n".data(using: .utf8)!)
    exit(1)
}
