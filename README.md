[![Review Assignment Due Date](https://classroom.github.com/assets/deadline-readme-button-22041afd0340ce965d47ae6ef1cefeee28c7c493a6346c4f15d667ab976d596c.svg)](https://classroom.github.com/a/iHSjCEgj)




# Java 游戏引擎与回放系统

一个使用 Java 编写的自定义 2D 游戏引擎，包含完整的游戏循环和强大的**存档与回放**系统。



## B站录屏[链接](https://www.bilibili.com/video/BV1tcU1BFEA2/)


## 功能特性

*   **核心引擎**: 场景管理、实体组件系统 (ECS)、物理 (AABB 碰撞)、输入处理。
*   **游戏玩法**: "葫芦娃大战哥布林" 风格的射击游戏。
    *   玩家移动、射击、斩击攻击 (Slash) 和黑洞技能 (Black Hole)。
    *   敌人 AI、生成机制和弹幕战斗。
    *   粒子效果（爆炸、拖尾）。
*   **录制系统**:
    *   以高频率 (100 FPS) 将游戏过程录制为 JSONL 格式。
    *   捕获实体位置、视觉状态和唯一 ID。
    *   异步 I/O 设计，防止游戏过程中产生卡顿。
*   **回放系统**:
    *   **插值 (Interpolation)**: 实现录制帧之间的平滑移动。
    *   **外推 (Extrapolation)**: 预测被销毁对象（例如击中敌人的子弹）的轨迹，确保视觉上的连续性，避免物体突然消失。
    *   **事件重建 (Event Reconstruction)**: 基于实体生命周期事件（如销毁）重建粒子效果（爆炸）。
    *   **视觉高保真**: 从录制数据中精确重建复杂的玩家和敌人视觉外观。

## 如何运行

1.  **编译**:
    在根目录下运行 `compile.bat`。
    ```bat
    ./compile.bat
    ```

2.  **运行**:
    运行 `run.bat` 启动主菜单。
    ```bat
    ./run.bat
    ```

## 操作说明

### 菜单
*   **方向键 / 鼠标**: 导航选项。
*   **回车 / 点击**: 选择选项。

### 游戏中
*   **W / A / S / D**: 移动玩家。
*   **空格键 (Space)**: 射击。
*   **J**: 斩击 (近战 AOE)。
*   **K**: 黑洞 (吸附敌人)。
*   **P**: 暂停游戏。
*   **ESC**: 返回主菜单。

### 回放模式
*   使用方向键和回车选择录像文件。
*   观看回放（自动播放）。
*   **ESC**: 返回主菜单。

## 文档

关于回放系统架构的详细解释，请参阅：
`docs/ReplaySystemArchitecture.md`


# J05

本版本采用 LWJGL + OpenGL 实现纯 GPU 渲染，窗口与输入基于 GLFW，文本渲染通过 AWT 字体离屏生成纹理后在 OpenGL 中批量绘制。


## 核心类型与概念

- **Scene（场景）**：一组 `GameObject` 的容器，负责生命周期（`initialize/update/render/clear`）与场景间切换。示例：`MenuScene`, `GameScene`, `ReplayScene`。
- **GameObject（游戏对象）**：由多个 `Component` 组成的实体，管理自身更新与渲染委托。支持自定义 `render()`（如玩家外观组合）。
- **Component（组件）**：面向数据/单体行为的可组合单元，例如：
  - `TransformComponent`：位置/旋转/缩放（本项目主要使用位置与尺寸）
  - `PhysicsComponent`：速度/摩擦/运动学数据（行为由 `PhysicsSystem` 统一处理）
  - `RenderComponent`：基础形状绘制（矩形/圆等，颜色与尺寸）
- **System（系统）**：面向“过程”的批处理逻辑，跨对象统一执行。例如 `PhysicsSystem` 负责所有带 `PhysicsComponent` 的对象物理更新。并行物理计算通过 `ExecutorService` 线程池实现，按批处理提升多核利用。
- **IRenderer/GPURenderer**：渲染后端抽象与 LWJGL 实现，负责窗口/上下文/绘制 API 封装，文本纹理缓存与绘制。
- **EntityFactory**：常用外观/组合的建造器（如 Player、AI 外观），便于游戏与回放共享同一套“预制”。


## 游戏录制/回放机制

- **存储抽象**：`RecordingStorage` 定义录制的读/写/列举接口，默认实现 `FileRecordingStorage`（JSONL 文件）。
- **录制服务**：`RecordingService` 在运行时异步写 JSONL 行：
  - header：窗口大小/版本
  - input：关键输入事件（just pressed）
  - keyframe：周期关键帧（对象位置与可选渲染外观 `rt/w/h/color`）
  - 采用“暖机 + 周期写入 + 结束强制写入”的策略，避免空关键帧
- **回放场景**：`ReplayScene` 读取 JSONL，解析为 keyframe 列表，按时间在相邻关键帧间做线性插值，使用 `EntityFactory`/`RenderComponent` 恢复外观并渲染。


## 编译与运行

1) 下载 LWJGL 依赖与原生库（按平台自动处理）

```bash
./download_lwjgl.bat
```

2) 编译并启动（脚本会自动编译 src/main/java 下所有源码并运行）

```bash
./run.bat
```


## 作业要求

- 参考本仓库代码，完善你自己的游戏：
 
- 为你的游戏设计并实现“存档与回放”功能：
  - 存档：定义存储抽象（文件/网络/内存均可），录制关键帧 + 输入/事件
  - 回放：读取存档，恢复对象状态并插值渲染，保证外观与行为可见且稳定

提示：请尽量保持模块解耦（渲染/输入/逻辑/存储）。

**重要提醒：尽量手写代码，不依赖自动生成，考试会考！**