package com.gameengine.example;

import com.gameengine.components.TransformComponent;
import com.gameengine.core.GameEngine;
import com.gameengine.core.GameObject;
import com.gameengine.graphics.IRenderer;
import com.gameengine.input.InputManager;
import com.gameengine.math.Vector2;
import com.gameengine.scene.Scene;
import com.gameengine.example.EntityFactory;

import java.io.File;
import java.util.*;

public class ReplayScene extends Scene {
    private final GameEngine engine;
    private String recordingPath;
    private IRenderer renderer;
    private InputManager input;
    private float time;
    private boolean DEBUG_REPLAY = false;
    private float debugAccumulator = 0f;

    private static class Keyframe {
        static class EntityInfo {
            Vector2 pos;
            String rt; // RECTANGLE/CIRCLE/LINE/CUSTOM/null
            float w, h;
            float r=0.9f,g=0.9f,b=0.2f,a=1.0f; // 默认颜色
            String id;
        }
        double t;
        java.util.List<EntityInfo> entities = new ArrayList<>();
        // Optimization: Map for fast lookup by ID
        Map<String, EntityInfo> entityMap = new HashMap<>();
    }

    private final List<Keyframe> keyframes = new ArrayList<>();
    private final Map<String, GameObject> replayObjects = new HashMap<>();
    
    // Recorded resolution
    private int recordedWidth = 0;
    private int recordedHeight = 0;
    
    // Visuals
    private ParticleSystem particleSystem;
    private int lastKeyframeIndex = -1;
    private Random random = new Random();

    // 如果 path 为 null，则先展示 recordings 目录下的文件列表，供用户选择
    public ReplayScene(GameEngine engine, String path) {
        super("Replay");
        this.engine = engine;
        this.recordingPath = path;
    }

    @Override
    public void initialize() {
        super.initialize();
        this.renderer = engine.getRenderer();
        this.input = engine.getInputManager();
        this.particleSystem = new ParticleSystem();
        // 重置状态，防止从列表进入后残留
        this.time = 0f;
        this.keyframes.clear();
        this.replayObjects.clear();
        this.lastKeyframeIndex = -1;
        clear();
        if (recordingPath != null) {
            loadRecording(recordingPath);
        } else {
            // 仅进入文件选择模式
            this.recordingFiles = null;
            this.selectedIndex = 0;
        }
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (input.isKeyJustPressed(27) || input.isKeyJustPressed(8)) { // ESC/BACK
            engine.setScene(new MenuScene(engine, "MainMenu"));
            return;
        }
        // 文件选择模式
        if (recordingPath == null) {
            handleFileSelection();
            return;
        }

        if (keyframes.size() < 1) return;
        time += deltaTime;
        // 限制在最后关键帧处停止（也可选择循环播放）
        double lastT = keyframes.get(keyframes.size() - 1).t;
        if (time > lastT) {
            time = (float)lastT;
        }

        // 查找区间
        int index = 0;
        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (time >= keyframes.get(i).t && time <= keyframes.get(i+1).t) {
                index = i;
                break;
            }
        }
        
        // Detect events (entities disappearing/appearing)
        // 检测事件：比较上一帧和当前帧的实体列表
        // 如果上一帧存在的实体在当前帧消失了，说明它被销毁了（死亡/爆炸）
        if (index != lastKeyframeIndex && lastKeyframeIndex != -1) {
            processEvents(keyframes.get(lastKeyframeIndex), keyframes.get(index));
        }
        lastKeyframeIndex = index;
        
        Keyframe a = keyframes.get(index);
        Keyframe b = keyframes.get(index + 1);
        // 获取前一帧用于外推计算速度
        Keyframe prev = index > 0 ? keyframes.get(index - 1) : null;

        // 计算插值系数 u (0.0 到 1.0)
        double span = Math.max(1e-6, b.t - a.t);
        double u = Math.min(1.0, Math.max(0.0, (time - a.t) / span));

        updateInterpolatedPositions(a, b, prev, (float)u, time);
        particleSystem.update(deltaTime);
    }

    @Override
    public void render() {
        renderer.drawRect(0, 0, renderer.getWidth(), renderer.getHeight(), 0.06f, 0.06f, 0.08f, 1.0f);
        if (recordingPath == null) {
            renderFileList();
            return;
        }
        
        // Draw a border indicating recorded area if available
        if (recordedWidth > 0 && recordedHeight > 0) {
            renderer.drawRect(0, 0, recordedWidth, recordedHeight, 0f, 0f, 0f, 0.2f); // Darker background for play area
            // Draw border lines
            renderer.drawLine(0, 0, recordedWidth, 0, 1, 1, 1, 0.5f);
            renderer.drawLine(0, recordedHeight, recordedWidth, recordedHeight, 1, 1, 1, 0.5f);
            renderer.drawLine(0, 0, 0, recordedHeight, 1, 1, 1, 0.5f);
            renderer.drawLine(recordedWidth, 0, recordedWidth, recordedHeight, 1, 1, 1, 0.5f);
        }

        // 基于 Transform 手动绘制（回放对象没有附带 RenderComponent）
        super.render();
        particleSystem.render();
        
        String hint = "REPLAY: ESC to return";
        float w = hint.length() * 12.0f;
        renderer.drawText(renderer.getWidth()/2.0f - w/2.0f, 30, hint, 0.8f, 0.8f, 0.8f, 1.0f);
        
        if (recordedWidth > 0) {
            String res = "REC: " + recordedWidth + "x" + recordedHeight;
            renderer.drawText(10, renderer.getHeight() - 20, res, 0.5f, 0.5f, 0.5f, 1f);
        }
    }
    
    private void processEvents(Keyframe oldFrame, Keyframe newFrame) {
        // Detect disappeared entities (Death/Explosion)
        // 遍历旧帧中的所有实体
        for (Keyframe.EntityInfo ei : oldFrame.entities) {
            // 如果新帧的Map中不包含该ID，说明该实体在这一帧之间被移除了
            if (!newFrame.entityMap.containsKey(ei.id)) {
                // Entity existed in old frame but not in new frame -> Died
                // 根据ID前缀判断实体类型，生成对应的死亡特效
                if (ei.id.startsWith("Enemy") && !ei.id.startsWith("EnemyBullet")) {
                    particleSystem.emitExplosion(ei.pos, 20, 1.0f, 0.3f, 0.3f);
                } else if (ei.id.startsWith("Bullet")) {
                    particleSystem.emitExplosion(ei.pos, 5, 1.0f, 1.0f, 0.0f);
                } else if (ei.id.startsWith("EnemyBullet")) {
                    particleSystem.emitExplosion(ei.pos, 5, 0.8f, 0.2f, 0.8f);
                } else if (ei.id.startsWith("PowerUp")) {
                    particleSystem.emitExplosion(ei.pos, 15, 0.3f, 1.0f, 1.0f);
                }
            }
        }
    }

    private void loadRecording(String path) {
        keyframes.clear();
        com.gameengine.recording.RecordingStorage storage = new com.gameengine.recording.FileRecordingStorage();
        try {
            for (String line : storage.readLines(path)) {
                if (line.contains("\"type\":\"header\"")) {
                    try {
                        recordedWidth = (int)com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(line, "width"));
                        recordedHeight = (int)com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(line, "height"));
                    } catch (Exception ignored) {}
                } else if (line.contains("\"type\":\"keyframe\"")) {
                    Keyframe kf = new Keyframe();
                    kf.t = com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(line, "t"));
                    // 解析 entities 列表中的若干 {"id":"name","x":num,"y":num}
                    int idx = line.indexOf("\"entities\":[");
                    if (idx >= 0) {
                        int bracket = line.indexOf('[', idx);
                        String arr = bracket >= 0 ? com.gameengine.recording.RecordingJson.extractArray(line, bracket) : "";
                        String[] parts = com.gameengine.recording.RecordingJson.splitTopLevel(arr);
                        for (String p : parts) {
                            Keyframe.EntityInfo ei = new Keyframe.EntityInfo();
                            ei.id = com.gameengine.recording.RecordingJson.stripQuotes(com.gameengine.recording.RecordingJson.field(p, "id"));
                            double x = com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(p, "x"));
                            double y = com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(p, "y"));
                            ei.pos = new Vector2((float)x, (float)y);
                            String rt = com.gameengine.recording.RecordingJson.stripQuotes(com.gameengine.recording.RecordingJson.field(p, "rt"));
                            ei.rt = rt;
                            ei.w = (float)com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(p, "w"));
                            ei.h = (float)com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(p, "h"));
                            String colorArr = com.gameengine.recording.RecordingJson.field(p, "color");
                            if (colorArr != null && colorArr.startsWith("[")) {
                                String c = colorArr.substring(1, Math.max(1, colorArr.indexOf(']', 1)));
                                String[] cs = c.split(",");
                                if (cs.length >= 3) {
                                    try {
                                        ei.r = Float.parseFloat(cs[0].trim());
                                        ei.g = Float.parseFloat(cs[1].trim());
                                        ei.b = Float.parseFloat(cs[2].trim());
                                        if (cs.length >= 4) ei.a = Float.parseFloat(cs[3].trim());
                                    } catch (Exception ignored) {}
                                }
                            }
                            kf.entities.add(ei);
                            kf.entityMap.put(ei.id, ei);
                        }
                    }
                    keyframes.add(kf);
                }
            }
        } catch (Exception e) {
            
        }
        keyframes.sort(Comparator.comparingDouble(k -> k.t));
    }

    private void buildObjectsFromFirstKeyframe() {
        // Deprecated
    }

    private void updateInterpolatedPositions(Keyframe a, Keyframe b, Keyframe prev, float u, double currentTime) {
        Set<String> currentIds = new HashSet<>();
        
        for (Keyframe.EntityInfo eiA : a.entities) {
            String id = eiA.id;
            currentIds.add(id);
            
            // Find corresponding entity in b using Map (O(1))
            // 尝试在下一帧(b)中找到同名实体
            Keyframe.EntityInfo eiB = b.entityMap.get(id);
            
            Vector2 pos;
            if (eiB != null) {
                // Standard interpolation
                // 情况1：实体在两帧都存在 -> 线性插值 (Lerp)
                // Pos = A * (1-u) + B * u
                float x = (float)((1.0 - u) * eiA.pos.x + u * eiB.pos.x);
                float y = (float)((1.0 - u) * eiA.pos.y + u * eiB.pos.y);
                pos = new Vector2(x, y);
            } else {
                // Extrapolation: Object exists in A but not in B (destroyed in this interval)
                // Try to calculate velocity from Prev -> A
                // 情况2：实体在下一帧消失了（例如子弹击中敌人）
                // 如果直接停止渲染，视觉上会觉得子弹突然消失。
                // 解决方案：外推 (Extrapolation)。
                // 利用前一帧(prev)和当前帧(a)计算速度，预测它在消失前的位置。
                Keyframe.EntityInfo eiPrev = (prev != null) ? prev.entityMap.get(id) : null;
                
                if (eiPrev != null) {
                    double dtPrev = a.t - prev.t;
                    if (dtPrev > 0.0001) {
                        // 计算速度 v = (PosA - PosPrev) / dt
                        float vx = (float)((eiA.pos.x - eiPrev.pos.x) / dtPrev);
                        float vy = (float)((eiA.pos.y - eiPrev.pos.y) / dtPrev);
                        
                        // 预测位置 = PosA + v * (currentTime - TimeA)
                        double dtCurrent = currentTime - a.t;
                        float x = eiA.pos.x + vx * (float)dtCurrent;
                        float y = eiA.pos.y + vy * (float)dtCurrent;
                        pos = new Vector2(x, y);
                    } else {
                        pos = eiA.pos;
                    }
                } else {
                    pos = eiA.pos;
                }
            }
            
            // 获取或创建回放用的GameObject
            GameObject obj = replayObjects.get(id);
            if (obj == null) {
                obj = buildObjectFromEntity(eiA, 0);
                addGameObject(obj);
                replayObjects.put(id, obj);
            }
            
            // 更新位置
            TransformComponent tc = obj.getComponent(TransformComponent.class);
            if (tc != null) tc.setPosition(pos);
            obj.setActive(true);
            
            // Emit trails for active bullets
            // 为移动中的子弹生成拖尾粒子
            if (id.startsWith("Bullet")) {
                particleSystem.emitTrail(pos, new Vector2(0, -1), 1.0f, 1.0f, 0.0f);
            } else if (id.startsWith("EnemyBullet")) {
                particleSystem.emitTrail(pos, new Vector2(0, 1), 0.8f, 0.2f, 0.8f);
            }
        }
        
        // Deactivate objects not in current frame
        // 隐藏那些在当前帧数据中不存在的对象（对象池复用逻辑的一部分）
        for (Map.Entry<String, GameObject> entry : replayObjects.entrySet()) {
            if (!currentIds.contains(entry.getKey())) {
                entry.getValue().setActive(false);
            }
        }
    }

    private GameObject buildObjectFromEntity(Keyframe.EntityInfo ei, int index) {
        GameObject obj;
        if ("Player".equalsIgnoreCase(ei.id)) {
            // Reconstruct Player visual
            obj = new GameObject("Player") {
                @Override
                public void render() {
                    TransformComponent tc = getComponent(TransformComponent.class);
                    if (tc == null) return;
                    Vector2 pos = tc.getPosition();
                    float bounce = (float) Math.sin(time * 3) * 2;
                    renderer.drawRect(pos.x - 10, pos.y - 12 + bounce, 20, 24, 0.9f, 0.1f, 0.1f, 1.0f);
                    renderer.drawCircle(pos.x, pos.y - 25 + bounce, 10, 16, 1.0f, 0.8f, 0.6f, 1.0f);
                    renderer.drawCircle(pos.x - 4, pos.y - 26 + bounce, 2, 8, 0.0f, 0.0f, 0.0f, 1.0f);
                    renderer.drawCircle(pos.x + 4, pos.y - 26 + bounce, 2, 8, 0.0f, 0.0f, 0.0f, 1.0f);
                    renderer.drawRect(pos.x - 18, pos.y - 8 + bounce, 8, 16, 1.0f, 0.9f, 0.0f, 1.0f);
                    renderer.drawRect(pos.x + 10, pos.y - 8 + bounce, 8, 16, 0.1f, 0.9f, 0.1f, 1.0f);
                    renderer.drawRect(pos.x - 8, pos.y + 12, 8, 14, 0.2f, 0.5f, 1.0f, 1.0f);
                    renderer.drawRect(pos.x + 2, pos.y + 12, 8, 14, 0.2f, 0.8f, 0.8f, 1.0f);
                }
            };
        } else if (ei.id.startsWith("Enemy") && !ei.id.startsWith("EnemyBullet")) {
            // Reconstruct Enemy visual
            obj = new GameObject(ei.id) {
                @Override
                public void render() {
                    TransformComponent tc = getComponent(TransformComponent.class);
                    if (tc == null) return;
                    Vector2 pos = tc.getPosition();
                    float pulse = (float) Math.sin(time * 5) * 0.2f + 1.0f;
                    renderer.drawCircle(pos.x + 12, pos.y + 12, 12 * pulse, 16, 0.8f, 0.2f, 0.8f, 0.9f);
                    renderer.drawCircle(pos.x + 8, pos.y + 8, 2, 8, 1.0f, 0.0f, 0.0f, 1.0f);
                    renderer.drawCircle(pos.x + 16, pos.y + 8, 2, 8, 1.0f, 0.0f, 0.0f, 1.0f);
                    renderer.drawLine(pos.x + 8, pos.y, pos.x + 5, pos.y - 8, 0.6f, 0.1f, 0.6f, 1.0f);
                    renderer.drawLine(pos.x + 16, pos.y, pos.x + 19, pos.y - 8, 0.6f, 0.1f, 0.6f, 1.0f);
                }
            };
        } else if (ei.id.startsWith("PowerUp")) {
            obj = new GameObject(ei.id) {
                @Override
                public void render() {
                    TransformComponent tc = getComponent(TransformComponent.class);
                    if (tc == null) return;
                    Vector2 pos = tc.getPosition();
                    float glow = (float) Math.sin(time * 6) * 0.3f + 0.7f;
                    renderer.drawCircle(pos.x + 12, pos.y + 12, 18, 16, 0.0f, 1.0f, 1.0f, 0.2f * glow);
                    renderer.drawCircle(pos.x + 12, pos.y + 12, 10, 16, 0.3f, 1.0f, 1.0f, glow);
                    renderer.drawCircle(pos.x + 12, pos.y + 12, 6, 16, 1.0f, 1.0f, 1.0f, 1.0f);
                }
            };
        } else if (ei.id.startsWith("BlackHole")) {
            obj = new GameObject(ei.id) {
                @Override
                public void render() {
                    TransformComponent tc = getComponent(TransformComponent.class);
                    if (tc == null) return;
                    Vector2 p = tc.getPosition();
                    float pulse = (float) Math.sin(time * 6) * 0.3f + 0.7f;
                    renderer.drawCircle(p.x, p.y, 80 * pulse, 32, 0.5f, 0.0f, 0.8f, 0.3f);
                    renderer.drawCircle(p.x, p.y, 50, 32, 0.3f, 0.0f, 0.5f, 0.7f);
                    renderer.drawCircle(p.x, p.y, 30, 32, 0.1f, 0.0f, 0.2f, 1.0f);
                }
            };
        } else if (ei.id.startsWith("Bullet")) {
            obj = new GameObject(ei.id) {
                @Override
                public void render() {
                    TransformComponent tc = getComponent(TransformComponent.class);
                    if (tc == null) return;
                    Vector2 p = tc.getPosition();
                    renderer.drawCircle(p.x, p.y, 8, 8, 1.0f, 1.0f, 0.0f, 1.0f);
                }
            };
        } else if (ei.id.startsWith("EnemyBullet")) {
            obj = new GameObject(ei.id) {
                @Override
                public void render() {
                    TransformComponent tc = getComponent(TransformComponent.class);
                    if (tc == null) return;
                    Vector2 p = tc.getPosition();
                    renderer.drawCircle(p.x, p.y, 6, 8, 0.8f, 0.2f, 0.8f, 1.0f);
                }
            };
        } else if (ei.id.startsWith("Star")) {
            obj = new GameObject(ei.id) {
                @Override
                public void render() {
                    TransformComponent tc = getComponent(TransformComponent.class);
                    if (tc == null) return;
                    Vector2 p = tc.getPosition();
                    renderer.drawCircle(p.x, p.y, 2, 4, 1.0f, 1.0f, 1.0f, 0.8f);
                }
            };
        } else if (ei.id.startsWith("SlashEffect")) {
            obj = new GameObject(ei.id) {
                float startTime = -1;
                @Override
                public void render() {
                    if (startTime < 0) startTime = time;
                    float lifetime = 0.25f;
                    float progress = (time - startTime) / lifetime;
                    if (progress > 1.0f) progress = 1.0f;
                    
                    TransformComponent tc = getComponent(TransformComponent.class);
                    if (tc == null) return;
                    Vector2 playerPos = tc.getPosition();
                    float slashRadius = 180f;
                    float angle = -150f + 120f * progress;
                    float rad = (float)Math.toRadians(angle);
                    float x = playerPos.x + (float)Math.cos(rad) * slashRadius;
                    float y = playerPos.y + (float)Math.sin(rad) * slashRadius;
                    renderer.drawLine(playerPos.x, playerPos.y, x, y, 1f, 0.9f, 0.2f, 1f);
                }
            };
        } else if ("AIPlayer".equalsIgnoreCase(ei.id)) {
            float w2 = (ei.w > 0 ? ei.w : 20);
            float h2 = (ei.h > 0 ? ei.h : 20);
            obj = com.gameengine.example.EntityFactory.createAIVisual(renderer, w2, h2, ei.r, ei.g, ei.b, ei.a);
        } else {
            if ("CIRCLE".equals(ei.rt)) {
                GameObject tmp = new GameObject(ei.id == null ? ("Obj#"+index) : ei.id);
                tmp.addComponent(new TransformComponent(new Vector2(0,0)));
                com.gameengine.components.RenderComponent rc = tmp.addComponent(
                    new com.gameengine.components.RenderComponent(
                        com.gameengine.components.RenderComponent.RenderType.CIRCLE,
                        new Vector2(Math.max(1, ei.w), Math.max(1, ei.h)),
                        new com.gameengine.components.RenderComponent.Color(ei.r, ei.g, ei.b, ei.a)
                    )
                );
                rc.setRenderer(renderer);
                obj = tmp;
            } else {
                obj = com.gameengine.example.EntityFactory.createAIVisual(renderer, Math.max(1, ei.w>0?ei.w:10), Math.max(1, ei.h>0?ei.h:10), ei.r, ei.g, ei.b, ei.a);
            }
            obj.setName(ei.id == null ? ("Obj#"+index) : ei.id);
        }
        TransformComponent tc = obj.getComponent(TransformComponent.class);
        if (tc == null) obj.addComponent(new TransformComponent(new Vector2(ei.pos)));
        else tc.setPosition(new Vector2(ei.pos));
        return obj;
    }

    // ========== 文件列表模式 ==========
    private List<File> recordingFiles;
    private int selectedIndex = 0;

    private void ensureFilesListed() {
        if (recordingFiles != null) return;
        com.gameengine.recording.RecordingStorage storage = new com.gameengine.recording.FileRecordingStorage();
        recordingFiles = storage.listRecordings();
    }

    private void handleFileSelection() {
        ensureFilesListed();
        if (input.isKeyJustPressed(38) || input.isKeyJustPressed(265)) { // up (AWT 38 / GLFW 265)
            selectedIndex = (selectedIndex - 1 + Math.max(1, recordingFiles.size())) % Math.max(1, recordingFiles.size());
        } else if (input.isKeyJustPressed(40) || input.isKeyJustPressed(264)) { // down (AWT 40 / GLFW 264)
            selectedIndex = (selectedIndex + 1) % Math.max(1, recordingFiles.size());
        } else if (input.isKeyJustPressed(10) || input.isKeyJustPressed(32) || input.isKeyJustPressed(257) || input.isKeyJustPressed(335)) { // enter/space (AWT 10/32, GLFW 257/335)
            if (recordingFiles.size() > 0) {
                String path = recordingFiles.get(selectedIndex).getAbsolutePath();
                this.recordingPath = path;
                clear();
                initialize();
            }
        } else if (input.isKeyJustPressed(27)) { // esc
            engine.setScene(new MenuScene(engine, "MainMenu"));
        }
    }

    private void renderFileList() {
        ensureFilesListed();
        int w = renderer.getWidth();
        int h = renderer.getHeight();
        String title = "SELECT RECORDING";
        float tw = title.length() * 16f;
        renderer.drawText(w/2f - tw/2f, 80, title, 1f,1f,1f,1f);

        if (recordingFiles.isEmpty()) {
            String none = "NO RECORDINGS FOUND";
            float nw = none.length() * 14f;
            renderer.drawText(w/2f - nw/2f, h/2f, none, 0.9f,0.8f,0.2f,1f);
            String back = "ESC TO RETURN";
            float bw = back.length() * 12f;
            renderer.drawText(w/2f - bw/2f, h - 60, back, 0.7f,0.7f,0.7f,1f);
            return;
        }

        float startY = 140f;
        float itemH = 28f;
        for (int i = 0; i < recordingFiles.size(); i++) {
            String name = recordingFiles.get(i).getName();
            float x = 100f;
            float y = startY + i * itemH;
            if (i == selectedIndex) {
                renderer.drawRect(x - 10, y - 6, 600, 24, 0.3f,0.3f,0.4f,0.8f);
            }
            renderer.drawText(x, y, name, 0.9f,0.9f,0.9f,1f);
        }

        String hint = "UP/DOWN SELECT, ENTER PLAY, ESC RETURN";
        float hw = hint.length() * 12f;
        renderer.drawText(w/2f - hw/2f, h - 60, hint, 0.7f,0.7f,0.7f,1f);
    }

    private class ParticleSystem {
        class Particle { Vector2 pos, vel; float life, maxLife; float r,g,b; }
        List<Particle> particles = new ArrayList<>();
        
        public void update(float dt) {
            Iterator<Particle> it = particles.iterator();
            while(it.hasNext()) {
                Particle p = it.next();
                p.life -= dt;
                if (p.life <= 0) it.remove();
                else {
                    p.pos = p.pos.add(p.vel.multiply(dt));
                }
            }
        }
        public void render() {
            for(Particle p : particles) {
                float a = p.life / p.maxLife;
                renderer.drawRect(p.pos.x, p.pos.y, 4, 4, p.r, p.g, p.b, a);
            }
        }
        public void emitTrail(Vector2 pos, Vector2 vel, float r, float g, float b) {
            Particle p = new Particle();
            p.pos = new Vector2(pos);
            p.vel = vel.multiply(-0.1f).add(new Vector2((random.nextFloat()-0.5f)*20, (random.nextFloat()-0.5f)*20));
            p.life = p.maxLife = 0.3f;
            p.r=r; p.g=g; p.b=b;
            particles.add(p);
        }
        public void emitExplosion(Vector2 pos, int count, float r, float g, float b) {
            for(int i=0; i<count; i++) {
                Particle p = new Particle();
                p.pos = new Vector2(pos);
                float angle = random.nextFloat() * 6.28f;
                float speed = random.nextFloat() * 100 + 50;
                p.vel = new Vector2((float)Math.cos(angle)*speed, (float)Math.sin(angle)*speed);
                p.life = p.maxLife = 0.5f + random.nextFloat()*0.5f;
                p.r=r; p.g=g; p.b=b;
                particles.add(p);
            }
        }
    }
}


