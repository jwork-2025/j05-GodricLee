# 游戏回放系统架构文档 (Save & Replay System Architecture)

本文档详细解释了本游戏引擎中实现的录制与回放系统的架构、工作原理及各组件之间的协作方式。

## 1. 核心概念

回放系统的核心思想是**确定性重现**或**状态快照插值**。本系统采用**状态快照（State Snapshots / Keyframes）**的方式。

*   **录制 (Recording)**: 在游戏运行时，周期性地（例如每帧）捕获所有游戏对象的状态（位置、外观等），并序列化为文本格式（JSONL）。
*   **回放 (Replay)**: 读取录制文件，解析出时间轴上的关键帧。在回放循环中，根据当前播放时间，在两个关键帧之间进行插值（Interpolation），从而平滑地还原游戏画面。

## 2. 架构组件

### 2.1 录制端 (Recording Side)

*   **`RecordingService`**: 核心服务类。
    *   **职责**: 负责收集游戏状态并写入存储。
    *   **工作流**:
        1.  `start()`: 开启独立写线程，写入文件头（分辨率等）。
        2.  `update()`: 每帧被 `GameEngine` 调用。
        3.  `writeKeyframe()`: 遍历当前 Scene 中的所有 `GameObject`。
            *   提取 `TransformComponent` (位置)。
            *   提取 `RenderComponent` (外观信息：颜色、形状)。
            *   对于特殊对象（如 Player），标记为 `CUSTOM` 类型。
            *   生成 JSON 字符串。
        4.  `enqueue()`: 将 JSON 字符串放入线程安全的队列 `BlockingQueue`。
        5.  **写线程**: 从队列取出字符串写入磁盘（避免IO阻塞主游戏循环）。

*   **`RecordingConfig`**: 配置类，存储输出路径、采样率等。

### 2.2 回放端 (Replay Side)

*   **`ReplayScene`**: 继承自 `Scene`，专门用于回放。
    *   **职责**: 加载录像文件，重建游戏世界，渲染回放画面。
    *   **数据结构**:
        *   `Keyframe`: 包含时间戳 `t` 和该时刻所有实体的列表 `entities`。
        *   `entityMap`: 为了优化性能，每个关键帧内部维护一个 `ID -> EntityInfo` 的映射表，实现 O(1) 查找。
    *   **工作流**:
        1.  `loadRecording()`: 解析 JSONL 文件，构建关键帧列表。
        2.  `update()`: 增加播放时间 `time`。
            *   **查找区间**: 找到 `time` 所在的两个关键帧 `Keyframe A` 和 `Keyframe B`。
            *   **计算插值**: 计算进度 `u` (0~1)。
            *   **位置更新 (`updateInterpolatedPositions`)**:
                *   如果实体在 A 和 B 都存在 -> **线性插值 (Lerp)**。
                *   如果实体在 A 存在但 B 不存在（说明在 A->B 之间死亡） -> **外推 (Extrapolation)**。利用 A 和 A的前一帧计算速度，预测其轨迹，防止物体突然消失。
            *   **事件处理 (`processEvents`)**: 对比 A 和 B 的实体列表，检测消失的实体，触发爆炸粒子特效。
        3.  `render()`: 遍历所有重建的 `GameObject` 进行绘制。
            *   对于 `CUSTOM` 类型的实体（如 Player），使用硬编码的绘图逻辑还原其特殊外观。
            *   绘制粒子系统（爆炸、拖尾）。

## 3. 关键技术点

### 3.1 为什么需要外推 (Extrapolation)?
在高速运动物体（如子弹）击中敌人时，物体会在某一帧突然销毁。
*   如果不做处理：子弹会在击中前一帧的位置突然消失，视觉上感觉“没打中就没了”。
*   使用外推：系统计算子弹的速度，在它消失的那一瞬间，画出它“应该到达”的位置（即敌人体内），从而产生击中的视觉连贯性。

### 3.2 ID 系统的重要性
`GameScene` 中引入了 `static objectIdCounter`。
*   **目的**: 确保每个生成的物体（子弹、敌人）都有全局唯一的 ID（如 `Enemy_1`, `Bullet_5`）。
*   **作用**: 回放系统完全依赖 ID 来匹配前后两帧中的同一个物体。如果 ID 混乱，插值就会出错（例如把子弹插值变成敌人）。

### 3.3 粒子系统 (Particle System)
录像文件通常不记录粒子（数据量太大）。
*   **策略**: 仅记录实体。
*   **重建**: 在回放时，根据实体的**状态变化**（创建/销毁/移动）实时生成粒子。
    *   **拖尾**: 每一帧检测到子弹移动，就在其位置生成拖尾粒子。
    *   **爆炸**: 检测到实体销毁事件，就在其最后位置生成爆炸粒子。

## 4. 协作流程图

```text
[Game Loop]
    |
    v
[GameScene Update] --> [Entities Move/Die]
    |
    v
[RecordingService]
    |-- Extract Transform & Render Info
    |-- Serialize to JSON
    |-- Push to Queue
    |
    v
[Writer Thread] --> [File.jsonl]

------------------------------------------------

[Replay Loop]
    |
    v
[ReplayScene Update]
    |-- Load JSONL --> [Keyframes List]
    |-- Find Keyframe A and B based on Time
    |
    |-- [Interpolation Logic]
    |     |-- Match Entity ID in A and B
    |     |-- Lerp Position
    |     |-- If missing in B -> Extrapolate Velocity
    |
    |-- [Event Logic]
    |     |-- Detect missing IDs -> Spawn Explosion Particles
    |
    v
[Renderer] --> Draw Reconstructed Objects & Particles
```
