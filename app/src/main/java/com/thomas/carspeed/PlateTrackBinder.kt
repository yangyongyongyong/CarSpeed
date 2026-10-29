package com.thomas.carspeed

import kotlin.math.sqrt

/**
 * 同一辆车在画面里移动时，ML Kit 每秒给出的车牌号经常不一样（漏一个字符、认错一个字符最多）。
 * 这里把一趟里的所有读数按「位置 + 时间」串成轨迹，再用整条轨迹的加权投票定下一个号，
 * 整条轨迹统一显示这个号——而不是每帧各显示各的，让车在路上"换牌"。
 *
 * 置信度不是 ML Kit 给的（它不提供），而是候选来源的强弱：正则严格命中记 1.0，
 * 只在疑似车牌兜底里命中记 0.55。所以投票实质上是"严格命中的次数"占主导，
 * 兜底读数只能在没有严格读数时说话。
 */
internal object PlateTrackBinder {
    /** 相邻两次读数允许的固定位移（按画面对角线归一化）。 */
    private const val BASE_JUMP = 0.18f
    /** 车在动，间隔越长允许跑得越远。 */
    private const val JUMP_PER_SECOND = 0.20f
    /** 容差封顶，否则隔几秒的位置也能被接成同一条轨迹，两辆不同的车会合并。 */
    private const val MAX_JUMP = 0.55f
    /** 轨迹消失超过这么久就关掉，避免把几分钟后的另一辆车接上来。 */
    private const val TRACK_MEMORY_MS = 5_000L
    /** 实时显示时，至少要有这么多次一致读数才敢覆盖当帧识别结果。 */
    private const val MIN_CONFIRMATIONS = 2
    /** 严格正则命中的置信度就是 1.0，兜底命中是 0.55，以这个为界区分来源强弱。 */
    private const val STRICT_CONFIDENCE = 0.9f

    /**
     * 批量绑定：拿整趟的读数算出每条轨迹的最终号牌，写回每个采样点。
     * 回放和导出都走这里，所以老录像的 sidecar 不用改格式也能享受到。
     */
    fun bindAll(samples: List<PlateRecognitionSample>): List<PlateRecognitionSample> {
        if (samples.size < 2) return samples
        val tracks = mutableListOf<Track>()
        // 采样点下标 -> 每个车牌对应的轨迹 id
        val assigned = Array(samples.size) { mutableListOf<Int>() }
        val order = samples.indices.sortedBy { samples[it].elapsedMs }

        for (index in order) {
            val sample = samples[index]
            val diagonal = diagonalOf(sample)
            sample.plates.forEach { plate ->
                val nx = centerX(plate) / diagonal
                val ny = centerY(plate) / diagonal
                val track = matchTrack(tracks, sample.elapsedMs, nx, ny)?.also {
                    it.add(plate, nx, ny, sample.elapsedMs)
                } ?: Track(tracks.size).also {
                    it.add(plate, nx, ny, sample.elapsedMs)
                    tracks += it
                }
                assigned[index] += track.id
            }
        }

        val winners = tracks.associate { it.id to it.winner() }
        return samples.mapIndexed { index, sample ->
            if (sample.plates.isEmpty()) return@mapIndexed sample
            val ids = assigned[index]
            val rebound = sample.plates.mapIndexed { plateIndex, plate ->
                val code = ids.getOrNull(plateIndex)?.let { winners[it] } ?: plate.code
                val confidence = ids.getOrNull(plateIndex)
                    ?.let { tracks.getOrNull(it)?.confidenceOf(code) }
                    ?: plate.confidence
                plate.copy(code = code, confidence = confidence)
            }
            sample.copy(plates = rebound.distinctBy { it.code })
        }
    }

    private fun matchTrack(tracks: List<Track>, elapsedMs: Long, nx: Float, ny: Float): Track? {
        var best: Track? = null
        var bestDistance = Float.MAX_VALUE
        tracks.forEach { track ->
            val gapMs = elapsedMs - track.lastMs
            if (gapMs < 0L || gapMs > TRACK_MEMORY_MS) return@forEach
            val allowed = allowedJump(gapMs)
            val dx = nx - track.cx
            val dy = ny - track.cy
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= allowed && distance < bestDistance) {
                bestDistance = distance
                best = track
            }
        }
        return best
    }

    private fun allowedJump(gapMs: Long): Float {
        val seconds = gapMs / 1000f
        return (BASE_JUMP + JUMP_PER_SECOND * seconds).coerceAtMost(MAX_JUMP)
    }

    private fun diagonalOf(sample: PlateRecognitionSample): Float {
        val w = sample.imageWidth.coerceAtLeast(1).toFloat()
        val h = sample.imageHeight.coerceAtLeast(1).toFloat()
        return sqrt(w * w + h * h)
    }

    private fun centerX(plate: PlateRecognitionResult): Float = (plate.x1 + plate.x2) / 2f

    private fun centerY(plate: PlateRecognitionResult): Float = (plate.y1 + plate.y2) / 2f

    internal class Track(val id: Int) {
        var cx = 0f
        var cy = 0f
        var lastMs = 0L
        private val votes = HashMap<String, Vote>()

        fun add(plate: PlateRecognitionResult, nx: Float, ny: Float, elapsedMs: Long) {
            cx = nx
            cy = ny
            lastMs = elapsedMs
            votes.getOrPut(plate.code) { Vote() }.apply {
                weight += plate.confidence.coerceAtLeast(0f)
                if (plate.confidence >= STRICT_CONFIDENCE) strictWeight += plate.confidence
                count += 1
                if (plate.confidence > bestConfidence) bestConfidence = plate.confidence
                if (firstMs < 0L) firstMs = elapsedMs
            }
        }

        /**
         * 整条轨迹的最终号牌。严格命中优先：只要有过严格读数，就只在严格读数之间选，
         * 兜底读数绝不覆盖它；一次严格命中胜过多少次兜底都不改变这个次序。
         * 只有在整条轨迹一次严格命中都没有时，才让兜底读数投票。
         */
        fun winner(): String? {
            val strict = votes.filterValues { it.strictWeight > 0f }
            val pool = if (strict.isNotEmpty()) strict else votes
            return pool.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Vote>> { it.value.strictWeight }
                        .thenByDescending { it.value.weight }
                        .thenByDescending { it.value.bestConfidence }
                        .thenBy { it.value.firstMs }
                )
                .firstOrNull()?.key
        }

        fun confidenceOf(code: String): Float? = votes[code]?.bestConfidence

        fun confirmations(code: String): Int = votes[code]?.count ?: 0
    }

    private class Vote {
        var weight = 0f
        /** 只累计严格命中的权重；兜底命中的次数再多也不会超过它。 */
        var strictWeight = 0f
        var count = 0
        var bestConfidence = 0f
        var firstMs = -1L
    }

    /**
     * 实时用：逐帧喂进来，返回把当帧号牌替换成「这条轨迹目前最可信的号」后的采样点。
     * 读数还不稳定（只有一次）时不改动，避免把一次误识别又当成新的结论。
     */
    fun stabilize(tracks: MutableList<Track>, sample: PlateRecognitionSample): PlateRecognitionSample {
        if (sample.plates.isEmpty()) return sample
        val diagonal = diagonalOf(sample)
        val rebound = sample.plates.map { plate ->
            val nx = centerX(plate) / diagonal
            val ny = centerY(plate) / diagonal
            val track = matchTrack(tracks, sample.elapsedMs, nx, ny)?.also {
                it.add(plate, nx, ny, sample.elapsedMs)
            } ?: Track(tracks.size).also {
                it.add(plate, nx, ny, sample.elapsedMs)
                tracks += it
            }
            val winner = track.winner()
            if (winner != null && winner != plate.code && track.confirmations(winner) >= MIN_CONFIRMATIONS) {
                plate.copy(code = winner, confidence = track.confidenceOf(winner) ?: plate.confidence)
            } else {
                plate
            }
        }
        return sample.copy(plates = rebound.distinctBy { it.code })
    }
}
