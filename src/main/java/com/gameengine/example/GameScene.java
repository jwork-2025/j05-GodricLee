package com.gameengine.example;

import com.gameengine.components.PhysicsComponent;
import com.gameengine.components.RenderComponent;
import com.gameengine.components.TransformComponent;
import com.gameengine.core.GameEngine;
import com.gameengine.core.GameLogic;
import com.gameengine.core.GameObject;
import com.gameengine.core.ParticleSystem;
import com.gameengine.graphics.IRenderer;
import com.gameengine.input.InputManager;
import com.gameengine.math.Vector2;
import com.gameengine.scene.Scene;

import java.util.*;

public class GameScene extends Scene {
    private GameEngine engine;
    private IRenderer renderer;
    private InputManager inputManager;
    private Random random;
    // Make ID counter static to avoid ID collisions across restarts in the same recording session
    private static long objectIdCounter = 0;

    // Game State
    private int score = 0;
    private int lives = 3;
    private int level = 1;
    private boolean paused = false;
    private boolean gameOver = false;

    // Timers
    private float enemySpawnTime;
    private float powerUpSpawnTime;
    private float shootCooldown;
    private float slashCooldown;
    private float blackHoleCooldown;
    private float enemyShootCooldown;

    // References
    private GameObject player;
    private GameObject activeBlackHole;
    private ParticleSystem particleSystem;

    public GameScene(GameEngine engine) {
        super("GameScene");
        this.engine = engine;
    }

    private String nextId(String prefix) {
        return prefix + "_" + (objectIdCounter++);
    }

    @Override
    public void initialize() {
        super.initialize();
        this.renderer = engine.getRenderer();
        this.inputManager = engine.getInputManager();
        this.random = new Random();
        this.particleSystem = new ParticleSystem();

        createPlayer();
        createInitialEnemies();
        createStars();
    }

    @Override
    public void update(float deltaTime) {
        // ESC to Menu (Check both AWT 27 and GLFW 256)
        if (inputManager.isKeyJustPressed(27) || inputManager.isKeyJustPressed(256)) {
            engine.setScene(new MenuScene(engine, "MainMenu"));
            return;
        }

        if (gameOver) {
            if (inputManager.isKeyJustPressed(82)) { // R
                engine.setScene(new GameScene(engine));
            }
            return;
        }

        if (inputManager.isKeyJustPressed(80)) { // P
            paused = !paused;
        }

        if (paused) return;

        super.update(deltaTime);
        particleSystem.update(deltaTime);

        if (shootCooldown > 0) shootCooldown -= deltaTime;
        if (slashCooldown > 0) slashCooldown -= deltaTime;
        if (blackHoleCooldown > 0) blackHoleCooldown -= deltaTime;

        handlePlayerInput(deltaTime);
        updatePhysics();

        enemyShootCooldown += deltaTime;
        if (enemyShootCooldown > 2.0f) {
            makeEnemiesShoot();
            enemyShootCooldown = 0;
        }

        if (activeBlackHole != null) updateBlackHole(deltaTime);

        checkCollisions();

        enemySpawnTime += deltaTime;
        float spawnInterval = Math.max(0.5f, 1.5f - level * 0.15f);
        if (enemySpawnTime > spawnInterval) {
            createEnemy();
            if (level >= 3 && random.nextFloat() < 0.5f) createEnemy();
            if (level >= 5 && random.nextFloat() < 0.3f) createEnemy();
            enemySpawnTime = 0;
        }

        powerUpSpawnTime += deltaTime;
        if (powerUpSpawnTime > 10.0f) {
            createPowerUp();
            powerUpSpawnTime = 0;
        }

        cleanupOffscreenObjects();
        checkLevelUp();
    }

    @Override
    public void render() {
        int w = renderer.getWidth();
        int h = renderer.getHeight();
        
        // Background
        for (int i = 0; i < h; i += 20) {
            float brightness = 0.05f + (i / (float)h) * 0.1f;
            renderer.drawRect(0, i, w, 20, brightness * 0.5f, brightness * 0.3f, brightness, 1.0f);
        }

        super.render();
        particleSystem.render();
        renderUI();
    }

    private void createPlayer() {
        player = new GameObject("Player") {
            private float animationTime = 0;
            @Override
            public void update(float deltaTime) {
                super.update(deltaTime);
                animationTime += deltaTime * 3;
            }
            @Override
            public void render() {
                TransformComponent tc = getComponent(TransformComponent.class);
                if (tc == null) return;
                Vector2 pos = tc.getPosition();
                float bounce = (float) Math.sin(animationTime) * 2;
                // Body
                renderer.drawRect(pos.x - 10, pos.y - 12 + bounce, 20, 24, 0.9f, 0.1f, 0.1f, 1.0f);
                // Head
                renderer.drawCircle(pos.x, pos.y - 25 + bounce, 10, 16, 1.0f, 0.8f, 0.6f, 1.0f);
                // Eyes
                renderer.drawCircle(pos.x - 4, pos.y - 26 + bounce, 2, 8, 0.0f, 0.0f, 0.0f, 1.0f);
                renderer.drawCircle(pos.x + 4, pos.y - 26 + bounce, 2, 8, 0.0f, 0.0f, 0.0f, 1.0f);
                // Arms
                renderer.drawRect(pos.x - 18, pos.y - 8 + bounce, 8, 16, 1.0f, 0.9f, 0.0f, 1.0f);
                renderer.drawRect(pos.x + 10, pos.y - 8 + bounce, 8, 16, 0.1f, 0.9f, 0.1f, 1.0f);
                // Legs
                renderer.drawRect(pos.x - 8, pos.y + 12, 8, 14, 0.2f, 0.5f, 1.0f, 1.0f);
                renderer.drawRect(pos.x + 2, pos.y + 12, 8, 14, 0.2f, 0.8f, 0.8f, 1.0f);
            }
        };
        player.addComponent(new TransformComponent(new Vector2(renderer.getWidth()/2f, renderer.getHeight() - 100)));
        PhysicsComponent physics = player.addComponent(new PhysicsComponent(1.0f));
        physics.setFriction(0.85f);
        // Add dummy render component for recorder
        player.addComponent(new RenderComponent(RenderComponent.RenderType.RECTANGLE, new Vector2(20, 40), new RenderComponent.Color(0,0,0,0)));
        addGameObject(player);
    }

    private void createInitialEnemies() {
        for (int i = 0; i < 5; i++) createEnemy();
    }

    private void createEnemy() {
        GameObject enemy = new GameObject(nextId("Enemy")) {
            private float animTime = 0;
            @Override
            public void update(float deltaTime) {
                super.update(deltaTime);
                animTime += deltaTime * 5;
            }
            @Override
            public void render() {
                TransformComponent tc = getComponent(TransformComponent.class);
                if (tc == null) return;
                Vector2 pos = tc.getPosition();
                float pulse = (float) Math.sin(animTime) * 0.2f + 1.0f;
                renderer.drawCircle(pos.x + 12, pos.y + 12, 12 * pulse, 16, 0.8f, 0.2f, 0.8f, 0.9f);
                renderer.drawCircle(pos.x + 8, pos.y + 8, 2, 8, 1.0f, 0.0f, 0.0f, 1.0f);
                renderer.drawCircle(pos.x + 16, pos.y + 8, 2, 8, 1.0f, 0.0f, 0.0f, 1.0f);
                renderer.drawLine(pos.x + 8, pos.y, pos.x + 5, pos.y - 8, 0.6f, 0.1f, 0.6f, 1.0f);
                renderer.drawLine(pos.x + 16, pos.y, pos.x + 19, pos.y - 8, 0.6f, 0.1f, 0.6f, 1.0f);
            }
        };
        Vector2 position = new Vector2(random.nextFloat() * renderer.getWidth(), -30);
        enemy.addComponent(new TransformComponent(position));
        PhysicsComponent physics = enemy.addComponent(new PhysicsComponent(0.5f));
        float speed = 50 + level * 10;
        physics.setVelocity(new Vector2((random.nextFloat() - 0.5f) * 50, speed + random.nextFloat() * 30));
        physics.setFriction(0.99f);
        enemy.addComponent(new RenderComponent(RenderComponent.RenderType.CIRCLE, new Vector2(24, 24), new RenderComponent.Color(0,0,0,0)));
        addGameObject(enemy);
    }

    private void createBullet(Vector2 position) {
        GameObject bullet = new GameObject(nextId("Bullet")) {
            @Override
            public void update(float deltaTime) {
                super.update(deltaTime);
                TransformComponent tc = getComponent(TransformComponent.class);
                PhysicsComponent pc = getComponent(PhysicsComponent.class);
                if (tc != null && pc != null) {
                    particleSystem.emitTrail(tc.getPosition(), pc.getVelocity(), 1.0f, 1.0f, 0.0f);
                }
            }
        };
        bullet.addComponent(new TransformComponent(new Vector2(position)));
        RenderComponent rc = bullet.addComponent(new RenderComponent(RenderComponent.RenderType.CIRCLE, new Vector2(8, 8), new RenderComponent.Color(1.0f, 1.0f, 0.0f, 1.0f)));
        rc.setRenderer(renderer);
        PhysicsComponent physics = bullet.addComponent(new PhysicsComponent(0.1f));
        physics.setVelocity(new Vector2(0, -400));
        physics.setFriction(1.0f);
        addGameObject(bullet);
    }

    private void createPowerUp() {
        GameObject powerUp = new GameObject(nextId("PowerUp")) {
            private float rotateTime = 0;
            @Override
            public void update(float deltaTime) {
                super.update(deltaTime);
                rotateTime += deltaTime * 3;
            }
            @Override
            public void render() {
                TransformComponent tc = getComponent(TransformComponent.class);
                if (tc == null) return;
                Vector2 pos = tc.getPosition();
                float glow = (float) Math.sin(rotateTime * 2) * 0.3f + 0.7f;
                renderer.drawCircle(pos.x + 12, pos.y + 12, 18, 16, 0.0f, 1.0f, 1.0f, 0.2f * glow);
                renderer.drawCircle(pos.x + 12, pos.y + 12, 10, 16, 0.3f, 1.0f, 1.0f, glow);
                renderer.drawCircle(pos.x + 12, pos.y + 12, 6, 16, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        };
        powerUp.addComponent(new TransformComponent(new Vector2(random.nextFloat() * renderer.getWidth(), -20)));
        PhysicsComponent physics = powerUp.addComponent(new PhysicsComponent(0.3f));
        physics.setVelocity(new Vector2(0, 100));
        physics.setFriction(1.0f);
        powerUp.addComponent(new RenderComponent(RenderComponent.RenderType.CIRCLE, new Vector2(24, 24), new RenderComponent.Color(0,0,0,0)));
        addGameObject(powerUp);
    }

    private void createStars() {
        for (int i = 0; i < 50; i++) {
            GameObject star = new GameObject(nextId("Star"));
            star.addComponent(new TransformComponent(new Vector2(random.nextFloat() * renderer.getWidth(), random.nextFloat() * renderer.getHeight())));
            float b = random.nextFloat() * 0.5f + 0.5f;
            RenderComponent rc = star.addComponent(new RenderComponent(RenderComponent.RenderType.CIRCLE, new Vector2(2, 2), new RenderComponent.Color(b, b, b, 0.8f)));
            rc.setRenderer(renderer);
            addGameObject(star);
        }
    }

    private void handlePlayerInput(float deltaTime) {
        if (player == null) return;
        TransformComponent tc = player.getComponent(TransformComponent.class);
        PhysicsComponent pc = player.getComponent(PhysicsComponent.class);
        if (tc == null || pc == null) return;

        Vector2 movement = new Vector2();
        if (inputManager.isKeyPressed(87) || inputManager.isKeyPressed(38)) movement.y -= 1; // W/Up
        if (inputManager.isKeyPressed(83) || inputManager.isKeyPressed(40)) movement.y += 1; // S/Down
        if (inputManager.isKeyPressed(65) || inputManager.isKeyPressed(37)) movement.x -= 1; // A/Left
        if (inputManager.isKeyPressed(68) || inputManager.isKeyPressed(39)) movement.x += 1; // D/Right

        if (movement.magnitude() > 0) pc.setVelocity(movement.normalize().multiply(300));
        else pc.setVelocity(new Vector2(0, 0));

        if (inputManager.isKeyPressed(32) && shootCooldown <= 0) {
            createBullet(new Vector2(tc.getPosition().x, tc.getPosition().y - 30));
            shootCooldown = 0.3f;
        }
        if (inputManager.isKeyJustPressed(74) && slashCooldown <= 0) { // J
            performSlashAttack();
            slashCooldown = 3.0f;
        }
        if (inputManager.isKeyJustPressed(75) && blackHoleCooldown <= 0) { // K
            createBlackHole(tc.getPosition());
            blackHoleCooldown = 7.0f;
        }

        Vector2 pos = tc.getPosition();
        pos.x = Math.max(20, Math.min(renderer.getWidth() - 20, pos.x));
        pos.y = Math.max(30, Math.min(renderer.getHeight() - 40, pos.y));
        tc.setPosition(pos);
    }

    private void updatePhysics() {
        for (GameObject obj : getGameObjects()) {
            PhysicsComponent pc = obj.getComponent(PhysicsComponent.class);
            TransformComponent tc = obj.getComponent(TransformComponent.class);
            if (pc != null && tc != null && obj.getName().startsWith("Enemy")) {
                Vector2 pos = tc.getPosition();
                Vector2 vel = pc.getVelocity();
                if (pos.x <= 0 || pos.x >= renderer.getWidth() - 25) {
                    vel.x = -vel.x;
                    pc.setVelocity(vel);
                    pos.x = Math.max(0, Math.min(renderer.getWidth() - 25, pos.x));
                    tc.setPosition(pos);
                }
            }
        }
    }

    private void checkCollisions() {
        if (player == null) return;
        TransformComponent ptc = player.getComponent(TransformComponent.class);
        if (ptc == null) return;

        List<GameObject> toDestroy = new ArrayList<>();
        
        for (GameObject obj : getGameObjects()) {
            if (!obj.isActive()) continue;
            String name = obj.getName();
            
            if (name.startsWith("Enemy") && !name.startsWith("EnemyBullet")) {
                TransformComponent etc = obj.getComponent(TransformComponent.class);
                if (etc != null && ptc.getPosition().distance(etc.getPosition()) < 30) {
                    loseLife();
                    toDestroy.add(obj);
                    particleSystem.emitExplosion(etc.getPosition(), 20, 1.0f, 0.3f, 0.3f);
                }
            } else if (name.startsWith("Bullet")) {
                TransformComponent btc = obj.getComponent(TransformComponent.class);
                if (btc == null) continue;
                for (GameObject enemy : getGameObjects()) {
                    if (!enemy.isActive() || !enemy.getName().startsWith("Enemy") || enemy.getName().startsWith("EnemyBullet")) continue;
                    TransformComponent etc = enemy.getComponent(TransformComponent.class);
                    if (etc != null && btc.getPosition().distance(etc.getPosition()) < 20) {
                        addScore(10);
                        toDestroy.add(obj);
                        toDestroy.add(enemy);
                        particleSystem.emitExplosion(etc.getPosition(), 15, 0.8f, 0.2f, 0.8f);
                        break;
                    }
                }
            } else if (name.startsWith("PowerUp")) {
                TransformComponent putc = obj.getComponent(TransformComponent.class);
                if (putc != null && ptc.getPosition().distance(putc.getPosition()) < 35) {
                    addScore(50);
                    toDestroy.add(obj);
                    particleSystem.emitExplosion(putc.getPosition(), 25, 0.3f, 1.0f, 1.0f);
                }
            } else if (name.startsWith("EnemyBullet")) {
                TransformComponent btc = obj.getComponent(TransformComponent.class);
                if (btc != null && ptc.getPosition().distance(btc.getPosition()) < 25) {
                    loseLife();
                    toDestroy.add(obj);
                    particleSystem.emitExplosion(btc.getPosition(), 15, 1.0f, 0.3f, 0.3f);
                }
            }
        }
        for(GameObject o : toDestroy) o.destroy();
    }

    private void makeEnemiesShoot() {
        if (player == null) return;
        TransformComponent ptc = player.getComponent(TransformComponent.class);
        List<GameObject> enemies = new ArrayList<>();
        for (GameObject obj : getGameObjects()) if (obj.getName().startsWith("Enemy") && !obj.getName().startsWith("EnemyBullet")) enemies.add(obj);
        if (!enemies.isEmpty()) {
            GameObject shooter = enemies.get(random.nextInt(enemies.size()));
            TransformComponent etc = shooter.getComponent(TransformComponent.class);
            if (etc != null) createEnemyBullet(etc.getPosition(), ptc.getPosition());
        }
    }

    private void createEnemyBullet(Vector2 from, Vector2 target) {
        GameObject bullet = new GameObject(nextId("EnemyBullet")) {
            @Override
            public void update(float deltaTime) {
                super.update(deltaTime);
                TransformComponent tc = getComponent(TransformComponent.class);
                PhysicsComponent pc = getComponent(PhysicsComponent.class);
                if (tc != null && pc != null) particleSystem.emitTrail(tc.getPosition(), pc.getVelocity(), 0.8f, 0.2f, 0.8f);
            }
        };
        bullet.addComponent(new TransformComponent(new Vector2(from)));
        RenderComponent rc = bullet.addComponent(new RenderComponent(RenderComponent.RenderType.CIRCLE, new Vector2(6, 6), new RenderComponent.Color(0.8f, 0.2f, 0.8f, 1.0f)));
        rc.setRenderer(renderer);
        PhysicsComponent pc = bullet.addComponent(new PhysicsComponent(0.1f));
        pc.setVelocity(target.subtract(from).normalize().multiply(250));
        pc.setFriction(1.0f);
        addGameObject(bullet);
    }

    private void performSlashAttack() {
        if (player == null) return;
        TransformComponent ptc = player.getComponent(TransformComponent.class);
        Vector2 playerPos = ptc.getPosition();
        float slashRadius = 180f;
        
        GameObject slashEffect = new GameObject(nextId("SlashEffect")) {
            float lifetime = 0.25f, currentTime = 0;
            @Override
            public void update(float deltaTime) {
                super.update(deltaTime);
                currentTime += deltaTime;
                if (currentTime >= lifetime) destroy();
            }
            @Override
            public void render() {
                float progress = currentTime / lifetime;
                float angle = -150f + 120f * progress;
                float rad = (float)Math.toRadians(angle);
                float x = playerPos.x + (float)Math.cos(rad) * slashRadius;
                float y = playerPos.y + (float)Math.sin(rad) * slashRadius;
                renderer.drawLine(playerPos.x, playerPos.y, x, y, 1f, 0.9f, 0.2f, 1f);
            }
        };
        // Add dummy render for recorder
        slashEffect.addComponent(new RenderComponent(RenderComponent.RenderType.RECTANGLE, new Vector2(1,1), new RenderComponent.Color(0,0,0,0)));
        slashEffect.addComponent(new TransformComponent(playerPos));
        addGameObject(slashEffect);

        List<GameObject> enemies = new ArrayList<>();
        for (GameObject obj : getGameObjects()) {
            if (obj.getName().startsWith("Enemy") && !obj.getName().startsWith("EnemyBullet")) {
                TransformComponent etc = obj.getComponent(TransformComponent.class);
                if (etc != null && etc.getPosition().distance(playerPos) <= slashRadius) {
                    enemies.add(obj);
                }
            }
        }
        for (GameObject e : enemies) {
            addScore(10);
            e.destroy();
            TransformComponent etc = e.getComponent(TransformComponent.class);
            if (etc != null) particleSystem.emitExplosion(etc.getPosition(), 25, 1.0f, 0.9f, 0.3f);
        }
    }

    private void createBlackHole(Vector2 pos) {
        if (activeBlackHole != null) activeBlackHole.destroy();
        activeBlackHole = new GameObject(nextId("BlackHole")) {
            float lifetime = 3.0f, rotation = 0;
            @Override
            public void update(float deltaTime) {
                super.update(deltaTime);
                lifetime -= deltaTime;
                rotation += deltaTime * 3;
                if (lifetime <= 0) { destroy(); activeBlackHole = null; }
            }
            @Override
            public void render() {
                TransformComponent tc = getComponent(TransformComponent.class);
                if (tc == null) return;
                Vector2 p = tc.getPosition();
                float pulse = (float) Math.sin(rotation * 2) * 0.3f + 0.7f;
                renderer.drawCircle(p.x, p.y, 80 * pulse, 32, 0.5f, 0.0f, 0.8f, 0.3f);
                renderer.drawCircle(p.x, p.y, 50, 32, 0.3f, 0.0f, 0.5f, 0.7f);
                renderer.drawCircle(p.x, p.y, 30, 32, 0.1f, 0.0f, 0.2f, 1.0f);
            }
        };
        activeBlackHole.addComponent(new TransformComponent(new Vector2(pos)));
        activeBlackHole.addComponent(new RenderComponent(RenderComponent.RenderType.CIRCLE, new Vector2(160, 160), new RenderComponent.Color(0,0,0,0)));
        addGameObject(activeBlackHole);
    }

    private void updateBlackHole(float deltaTime) {
        if (activeBlackHole == null) return;
        TransformComponent btc = activeBlackHole.getComponent(TransformComponent.class);
        Vector2 bPos = btc.getPosition();
        for (GameObject obj : getGameObjects()) {
            if (obj.getName().startsWith("Enemy") && !obj.getName().startsWith("EnemyBullet")) {
                TransformComponent etc = obj.getComponent(TransformComponent.class);
                PhysicsComponent epc = obj.getComponent(PhysicsComponent.class);
                if (etc != null && epc != null) {
                    Vector2 toBH = bPos.subtract(etc.getPosition());
                    float dist = toBH.magnitude();
                    if (dist <= 200f) {
                        epc.addVelocity(toBH.normalize().multiply(300f * (1 - dist/200f) * deltaTime));
                        if (dist < 40) {
                            addScore(10);
                            obj.destroy();
                            particleSystem.emitExplosion(etc.getPosition(), 15, 0.5f, 0.0f, 0.8f);
                        }
                    }
                }
            }
        }
    }

    private void cleanupOffscreenObjects() {
        for (GameObject obj : getGameObjects()) {
            String n = obj.getName();
            if (n.startsWith("Enemy") || n.startsWith("Bullet") || n.startsWith("EnemyBullet") || n.startsWith("PowerUp")) {
                TransformComponent tc = obj.getComponent(TransformComponent.class);
                if (tc != null) {
                    Vector2 p = tc.getPosition();
                    if (p.y > renderer.getHeight() + 50 || p.y < -50 || p.x < -50 || p.x > renderer.getWidth() + 50) obj.destroy();
                }
            }
        }
    }

    private void checkLevelUp() {
        int newLevel = (score / 100) + 1;
        if (newLevel > level) level = newLevel;
    }

    private void addScore(int v) { score += v; }
    private void loseLife() {
        lives--;
        if (lives <= 0) gameOver = true;
    }

    private void renderUI() {
        renderer.drawText(20, 30, "SCORE: " + score, 1f, 1f, 1f, 1f);
        renderer.drawText(20, 60, "LIVES: " + lives, 1f, 0.2f, 0.2f, 1f);
        renderer.drawText(20, 90, "LEVEL: " + level, 0.2f, 1f, 0.2f, 1f);
        renderer.drawText(20, 120, "SLASH: " + (slashCooldown > 0 ? String.format("%.1f", slashCooldown) : "READY"), 1f, 1f, 0f, 1f);
        renderer.drawText(20, 150, "BLACKHOLE: " + (blackHoleCooldown > 0 ? String.format("%.1f", blackHoleCooldown) : "READY"), 0.5f, 0f, 1f, 1f);
        if (gameOver) renderer.drawText(300, 300, "GAME OVER - PRESS R", 1f, 0f, 0f, 1f);
        if (paused) renderer.drawText(350, 300, "PAUSED", 1f, 1f, 0f, 1f);
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


