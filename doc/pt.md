# 背景
```
制作安卓app
只需要适配安卓16即可
```
# 开发目标
制作一个能够突然通过悬浮窗显示
    gps速度
    东西南北方向角度
    等待红绿灯时间 时长=驾驶过程中(首次速度>10km/h则认为开始驾驶),速度=0km/h的时长
的安卓app


# 部分功能需求
- 悬浮框字体颜色支持自定义
- 支持查看历史驾驶记录
- 


# 测试用例 每次修改或者新增功能后,自动化执行测试用例
- gps给低速 当前速度>0,等待4s(期间当前速度=0),gps给高速 当前速度>0.
- gps信号差的时候 当前速度=0km/h  
- gps发送低速度 等待,再发送高速度,检查最高速度是否变化,对应时间戳是否变化
- 最高速度不得超过9999km/h,超过则认为等于9999
- 悬浮框透明度0% 到 100% 分别对应完全不透明很完全透明.
- 悬浮框有且仅有贴到右侧右侧上半部分的1/3(判定采用悬浮框中心点)范围的侧边栏才会缩小为图标,其他位置禁止缩小. 
- 历史记录支持根据字段排序

# 测试用例自动化映射
以下是可自动化的测试用例与落地方式：

1. `gps给低速 -> 当前速度>0, 等待4s(期间速度=0), gps给高速 -> 当前速度>0`
   - **自动化方式**：单元测试（纯逻辑）
   - **用例**：`SpeedRulesTest.low_then_zero_wait_then_high_speed_is_non_zero_and_wait_kept`

2. `gps信号差的时候 当前速度=0km/h`
   - **自动化方式**：单元测试（速度选择逻辑）
   - **用例**：`SpeedRulesTest.poor_gps_results_zero_speed`
   - **补充用例**：`SpeedRulesTest.computed_speed_should_win_when_gps_is_zero`（gps=0但计算速度有效时，当前速度不得锁0）

3. `低速->等待->高速, 检查最高速度是否变化, 对应时间戳是否变化`
   - **自动化方式**：单元测试（最高速度与时间戳更新逻辑）
   - **用例**：`SpeedRulesTest.max_speed_and_timestamp_update_when_new_peak`

4. `最高速度不得超过9999km/h`
   - **自动化方式**：单元测试
   - **用例**：`SpeedRulesTest.max_speed_is_capped_to_9999`

5. `透明度0%~100% 映射`
   - **当前状态**：建议做 Robolectric/Instrumentation UI 断言（可后续补）

6. `仅贴到右侧右侧上半部分的1/3(判定采用悬浮框中心点)区域才缩小`
   - **自动化方式**：单元测试（规则函数）
   - **用例**：`OverlayRulesTest.collapse_only_when_right_top_corner_is_near_right_edge_and_in_upper_half`

7. `历史记录支持根据字段排序`
   - **自动化方式**：单元测试（排序规则）
   - **用例**：`HistorySortTest`

8. `高速过程中短时掉0不应导致当前速度立刻卡死为0`
   - **自动化方式**：单元测试（速度稳定化）
   - **用例**：`SpeedRulesTest.stabilize_speed_should_not_freeze_to_zero_immediately_after_short_drop`

## 执行方式
- 本地：`/Users/thomas990p/iqooAPP/iqooAPP/scripts/ci_local.sh`
- 单测：`./gradlew :app:testDebugUnitTest`
- 构建：`./gradlew :app:assembleDebug -x lint`

9. `隧道无GPS更新时当前速度应归零；重获GPS后不应瞬时飙升`
   - **自动化方式**：单元测试（stale/重获逻辑）
   - **用例**：`SpeedRulesTest.stale_gap_should_force_zero`, `SpeedRulesTest.reacquire_after_long_gap_should_enter_warmup`
