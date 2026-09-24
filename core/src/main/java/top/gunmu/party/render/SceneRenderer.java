package top.gunmu.party.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Mode;
import top.gunmu.party.core.Roll;
import top.gunmu.party.items.ItemSystem;
import top.gunmu.party.items.ItemType;
import top.gunmu.party.items.Rarity;
import top.gunmu.party.items.Summon;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.Obstacle;
import top.gunmu.party.map.Zone;
import top.gunmu.party.match.LevelDirector;
import top.gunmu.party.ultimates.Projectile;

/**
 * 世界渲染。全部物体由 {@link MeshFactory} 的程序化几何拼出来。
 *
 * <p>直接用 {@link Renderable} 而不是 {@code ModelInstance}：前者允许逐次替换
 * meshPart / material / transform，不必为每种颜色复制一份实例对象。
 */
public final class SceneRenderer implements Disposable {

    private static final Color C_PLAYER_RING = new Color(0xffb703ff);
    private static final Color C_CHECKPOINT = new Color(0x4cc9f0ff);
    private static final Color C_FINISH = new Color(0x80ed99ff);
    private static final Color C_BOUNCER = new Color(0x39ff7aff);
    private static final Color C_BOOST = new Color(0xffd60aff);
    private static final Color C_CONVEYOR = new Color(0x8d99aeFF);
    private static final Color C_LAVA = new Color(0xff5400ff);
    private static final Color C_ICE = new Color(0xa8dcf0ff);
    private static final Color C_METAL = new Color(0xb0b0b8ff);
    private static final Color C_HAZARD = new Color(0xe63946ff);
    private static final Color C_FAN = new Color(0x48cae4ff);
    private static final Color C_WOOD = new Color(0x8a6239ff);
    private static final Color C_GHOST = new Color(0x2b3a67ff);
    private static final Color C_DEAD = new Color(0x3a3a3aff);
    /** 起点屏障：偏青的能量色，和存档点的蓝、终点的绿都区分得开。 */
    private static final Color C_BARRIER = new Color(0x62e8ffFF);
    /** 起点屏障高度（米）。比跳跃高度高得多，确保跳不出去。 */
    private static final float BARRIER_HEIGHT = 9f;

    private final ModelBatch batch = new ModelBatch();
    private final Environment env = new Environment();
    private final MeshFactory meshes;

    private final Array<MeshFactory.Geo> terrainGeos = new Array<>();
    private final Array<Renderable> terrainRenderables = new Array<>();

    private final Array<Renderable> pool = new Array<>();
    private final Array<Renderable> frame = new Array<>();
    private int cursor;

    private final Matrix4 m4 = new Matrix4();
    private final Matrix4 identity = new Matrix4();
    private final Vector3 v3a = new Vector3();
    private final Vector3 v3b = new Vector3();
    private final Quaternion qa = new Quaternion();
    private final Quaternion qb = new Quaternion();
    private final Quaternion qc = new Quaternion();
    private final Vector3 axisY = new Vector3(0f, 1f, 0f);
    private final Vector3 axisX = new Vector3(1f, 0f, 0f);
    private final Vector3 axisZ = new Vector3(0f, 0f, 1f);
    private float time;

    public SceneRenderer(MeshFactory meshes) {
        this.meshes = meshes;
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.62f, 0.66f, 0.72f, 1f));
        DirectionalLight sun = new DirectionalLight();
        sun.set(0.72f, 0.85f, 0.62f, 0.55f, -1f, 0.35f);
        env.add(sun);
    }

    // ================================================================== 地图

    public void loadMap(MapDef map) {
        disposeTerrain();
        Array<MeshFactory.Geo> geos = meshes.terrain(map.field, GameConfig.terrainRenderStep);
        int maxVerts = 0;
        for (int i = 0; i < geos.size; i++) {
            MeshFactory.Geo g = geos.get(i);
            terrainGeos.add(g);
            Renderable r = new Renderable();
            r.meshPart.set(g.part);
            r.material = g.material;
            r.environment = env;
            r.worldTransform.set(identity);
            terrainRenderables.add(r);
            maxVerts = Math.max(maxVerts, g.mesh.getNumVertices());
        }
        // 正常情况下地形是分好块的，只有异常时才需要刷日志
        if (maxVerts > 65535) {
            com.badlogic.gdx.Gdx.app.error("Terrain", "单块顶点数 " + maxVerts
                    + " 超过 short 索引上限，画面会撕裂。调小 MeshFactory.TERRAIN_TILE。");
        }
        com.badlogic.gdx.Gdx.app.log("Terrain", "地图 " + map.name
                + " · 高度场 " + map.field.nx + "x" + map.field.nz
                + " · " + geos.size + " 块，单块最多 " + maxVerts + " 顶点");
    }

    private void disposeTerrain() {
        for (int i = 0; i < terrainGeos.size; i++) {
            meshes.disposeGeo(terrainGeos.get(i));
        }
        terrainGeos.clear();
        terrainRenderables.clear();
    }

    // ================================================================== 主渲染

    public void render(LevelDirector dir, PerspectiveCamera cam, float delta) {
        time += delta;
        MapDef map = dir.map();
        if (map == null) {
            return;
        }

        cursor = 0;
        frame.clear();

        Array<Roll> rolls = dir.rolls();
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (r.eliminated && !r.player) {
                continue;
            }
            addRoll(dir, r);
        }

        addStartBarrier(dir);

        for (int i = 0; i < map.zones.size; i++) {
            addZone(dir, map.zones.get(i));
        }
        Array<Zone> temp = dir.physics().tempZones;
        for (int i = 0; i < temp.size; i++) {
            addZone(dir, temp.get(i));
        }

        for (int i = 0; i < map.checkpoints.size; i++) {
            MapDef.Checkpoint cp = map.checkpoints.get(i);
            // 存档点画成**横跨赛道的带状区域**，而不是路中间一个小圆盘：
            // 判定本来就是"越过这一段就存档"，画面也该这么表达，玩家才不会以为
            // 必须精确踩到某个点。飘在上面的八面体保留，远处也能看见。
            // 存档带要横跨赛道，宽度与朝向都取自路径。非竞速图（生存/竞技）没有路径，
            // 也没有存档点，这里只是防御性分支。
            float cpHalf = 8f;
            float cpYaw = 0f;
            if (map.hasRoute()) {
                cpHalf = map.route.halfWidthAt(cp.arc);   // route-ok: 紧邻的 hasRoute 分支
                cpYaw = map.route.yawAt(cp.arc);          // route-ok: 同上
            }
            alignToSlope(dir, cp.pos.x, cp.pos.z);
            v3a.set(cp.pos.x, cp.pos.y + 0.07f, cp.pos.z);
            m4.setToTranslation(v3a).rotate(slopeQ)
                    .rotate(axisY, cpYaw * MathUtils.radDeg)
                    .scale(cpHalf, 0.03f, 0.9f);
            add(meshes.translucent(MeshFactory.KIND_DISC,
                    Color.rgba8888(C_CHECKPOINT)), m4);

            v3b.set(cp.pos.x, cp.pos.y + 2.2f, cp.pos.z);
            m4.setToTranslation(v3b).scale(0.6f, 0.6f, 0.6f);
            add(meshes.geo(MeshFactory.KIND_OCT, C_CHECKPOINT), m4);
        }

        if (map.mode == Mode.RACE) {
            float yaw = MathUtils.atan2(map.finishDir.x, map.finishDir.z) * MathUtils.radDeg;
            v3a.set(map.finishPos.x, map.finishPos.y + 0.10f, map.finishPos.z);
            m4.setToTranslation(v3a).rotate(axisY, yaw)
                    .scale(map.finishHalfWidth, 0.16f, 1.2f);
            add(meshes.translucent(MeshFactory.KIND_DISC,
                    Color.rgba8888(C_FINISH)), m4);
        }

        for (int i = 0; i < map.obstacles.size; i++) {
            addObstacle(dir, map.obstacles.get(i));
        }

        for (int i = 0; i < map.itemSpawns.size; i++) {
            MapDef.ItemSpawn s = map.itemSpawns.get(i);
            if (s.ready) {
                addItem(s.pos, s.current);
            }
        }
        Array<ItemSystem.Drop> drops = dir.items.drops();
        for (int i = 0; i < drops.size; i++) {
            addItem(drops.get(i).pos, drops.get(i).type);
        }

        Array<Summon> summons = dir.items.summons;
        for (int i = 0; i < summons.size; i++) {
            Summon s = summons.get(i);
            if (s.kind == Summon.Kind.LOBSTER) {
                m4.setToTranslation(s.pos).rotate(axisY, time * 220f)
                        .scale(1.1f, 0.7f, 1.6f);
                add(meshes.geo(MeshFactory.KIND_BOX, 0xff7b54ff), m4);
            } else {
                m4.setToTranslation(s.pos).scale(0.42f, 0.42f, 0.42f);
                add(meshes.geo(MeshFactory.KIND_BALL, 0xffe066ff), m4);
            }
        }

        for (int i = 0; i < dir.ultimates.projectiles.size; i++) {
            Projectile p = dir.ultimates.projectiles.get(i);
            if (p.kind == Projectile.Kind.WEB) {
                float s = p.radius;
                m4.setToTranslation(p.pos).rotate(axisY, time * 90f).scale(s, s, s);
                add(meshes.translucent(MeshFactory.KIND_BALL, 0xf8f8f2cc), m4);
            } else {
                m4.setToTranslation(p.pos).rotate(axisX, time * 480f)
                        .scale(p.radius, p.radius, p.radius);
                add(meshes.geo(MeshFactory.KIND_BALL, 0xff9f1cff), m4);
            }
        }

        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (r.castTimer > 0f) {
                float t = 1f - r.castTimer / GameConfig.ZHONGGUOREN_CAST;
                float rad = GameConfig.ZHONGGUOREN_RADIUS * (1.15f - t * 0.5f);
                v3a.set(r.pos.x, r.pos.y - GameConfig.LOG_RADIUS + 0.12f, r.pos.z);
                m4.setToTranslation(v3a).scale(rad, 0.14f, rad);
                add(meshes.translucent(MeshFactory.KIND_DISC, 0x00bbf9cc), m4);
            }
            if (r.zhengTaiTimer > 0f) {
                v3a.set(r.pos.x, r.pos.y, r.pos.z);
                m4.setToTranslation(v3a).scale(1.9f, 1.9f, 1.9f);
                add(meshes.translucent(MeshFactory.KIND_BALL, 0xf15bb588), m4);
            }
            // 普通攻击的扇形（决赛关）：贴地画一段朝前的扇区，随时间往外张。
            // 只靠按钮上的倒计时是看不出"这一下打向哪里、能打多远"的。
            if (r.attackFlash > 0f) {
                float t = 1f - r.attackFlash / GameConfig.ATTACK_FLASH;
                float reach = GameConfig.ATTACK_RANGE * (0.70f + 0.30f * Math.min(1f, t * 2.4f));
                float yaw = MathUtils.atan2(top.gunmu.party.items.ItemSystem.forwardX(r),
                        top.gunmu.party.items.ItemSystem.forwardZ(r)) * MathUtils.radDeg;
                alignToSlope(dir, r.pos.x, r.pos.z);
                v3a.set(r.pos.x, r.pos.y - GameConfig.LOG_RADIUS + 0.09f, r.pos.z);
                m4.setToTranslation(v3a).rotate(slopeQ).rotate(axisY, yaw)
                        .scale(reach, 0.06f, reach);
                add(meshes.translucent(MeshFactory.KIND_SECTOR,
                        r.player ? 0xffb70399 : 0xff6b6b99), m4);
            }
        }

        batch.begin(cam);
        for (int i = 0; i < terrainRenderables.size; i++) {
            batch.render(terrainRenderables.get(i));
        }
        for (int i = 0; i < frame.size; i++) {
            batch.render(frame.get(i));
        }
        batch.end();
    }

    // ================================================================== 单体

    private void addRoll(LevelDirector dir, Roll r) {
        if (r.eliminated && !r.player) {
            return;
        }
        int rgba;
        if (!r.alive) {
            if (r.respawnTimer > 0f || r.stageOut) {
                return;
            }
            rgba = Color.rgba8888(C_DEAD);
        } else if (r.invulnerable()) {
            rgba = ((int) (time * 8f) & 1) == 0 ? 0xffffffff : Color.rgba8888(C_WOOD);
        } else if (r.hidden()) {
            rgba = Color.rgba8888(C_GHOST);
        } else if (r.player) {
            rgba = 0xffb703ff;
        } else if (r.isBlitz()) {
            rgba = 0xff5400ff;
        } else if (r.daoDunTimer > 0f) {
            rgba = 0x8ecae6ff;
        } else {
            rgba = Color.rgba8888(C_WOOD);
        }

        v3a.set(r.longAxis.x, 0f, r.longAxis.z);
        if (v3a.len2() < 1e-6f) {
            v3a.set(1f, 0f, 0f);
        }
        v3a.nor();
        qa.setFromCross(axisY, v3a);
        qb.set(v3a, r.rollAngle * MathUtils.radDeg);
        qc.set(qb).mul(qa);

        m4.setToTranslation(r.pos).rotate(qc).scale(1f, 1f, 1f);
        add(meshes.geo(MeshFactory.KIND_ROLL, rgba), m4);

        if (r.player && r.alive) {
            // 用空心环而不是实心圆盘：实心盘半透明叠在地上会变成一块"草地/地板"，
            // 玩家会以为那是个能站的平台。环只画轮廓，读起来才是"你在这儿"。
            alignToSlope(dir, r.pos.x, r.pos.z);
            v3b.set(r.pos.x, r.pos.y - GameConfig.LOG_RADIUS + 0.06f, r.pos.z);
            m4.setToTranslation(v3b).rotate(slopeQ).scale(1.45f, 1f, 1.45f);
            add(meshes.translucent(MeshFactory.KIND_RING,
                    Color.rgba8888(C_PLAYER_RING)), m4);
        }
    }

    private void addItem(Vector3 pos, ItemType type) {
        if (type == null) {
            return;
        }
        float bob = (float) Math.sin(time * 2.2f + pos.x) * 0.18f;
        v3a.set(pos.x, pos.y + 0.35f + bob, pos.z);
        float s = GameConfig.ITEM_SIZE * (type.rarity == Rarity.EPIC ? 1.25f : 1f);
        m4.setToTranslation(v3a).rotate(axisY, time * GameConfig.ITEM_SPIN_RATE)
                .scale(s, s, s);
        add(meshes.geo(MeshFactory.KIND_OCT, type.rarity.color), m4);
    }

    /**
     * 起点屏障：一道半透明的圆柱能量墙，把所有人关在起点区域内。
     *
     * <p>两个可见件：地面上的**基环**（明确画出边界，不然玩家只看到一堵半透明的墙
     * 会以为是贴图错误）+ **筒身**（双面几何，人在里面也看得见，见
     * {@link MeshFactory#tube}）。剩余秒数由 HUD 负责，这里只管形状。
     */
    private void addStartBarrier(LevelDirector dir) {
        if (!dir.isStartBarrierActive()) {
            return;
        }
        MapDef map = dir.map();
        if (map == null || map.startRadius <= 0f) {
            return;
        }
        float cx = map.startCenter.x;
        float cz = map.startCenter.z;
        float baseY = map.field.walkableAt(cx, cz)
                ? map.field.heightAt(cx, cz) : map.startCenter.y;

        // 地面基环
        v3a.set(cx, baseY + 0.06f, cz);
        m4.setToTranslation(v3a).scale(map.startRadius, 1f, map.startRadius);
        add(meshes.translucent(MeshFactory.KIND_RING,
                Color.rgba8888(C_BARRIER)), m4);

        // 筒身：往下多探 1 米，免得地面起伏时底部漏缝
        v3b.set(cx, baseY + BARRIER_HEIGHT * 0.5f - 1f, cz);
        m4.setToTranslation(v3b).scale(map.startRadius, BARRIER_HEIGHT,
                map.startRadius);
        add(meshes.translucent(MeshFactory.KIND_TUBE,
                Color.rgba8888(C_BARRIER)), m4);
    }

    private void addZone(LevelDirector dir, Zone z) {
        float y = z.center.y + 0.05f;
        alignToSlope(dir, z.center.x, z.center.z);
        switch (z.kind) {
            case BOUNCER:
                v3a.set(z.center.x, y, z.center.z);
                m4.setToTranslation(v3a).rotate(slopeQ).scale(z.radius, 0.20f, z.radius);
                add(meshes.translucent(MeshFactory.KIND_DISC,
                        Color.rgba8888(C_BOUNCER)), m4);
                break;
            case BOOST:
                v3a.set(z.center.x, y, z.center.z);
                m4.setToTranslation(v3a).rotate(slopeQ).rotate(axisY,
                                MathUtils.atan2(z.dirX, z.dirZ) * MathUtils.radDeg)
                        .scale(z.halfX * 2f, 0.18f, z.halfZ * 2f);
                add(meshes.translucent(MeshFactory.KIND_DISC,
                        Color.rgba8888(C_BOOST)), m4);
                break;
            case CONVEYOR:
                v3a.set(z.center.x, y, z.center.z);
                m4.setToTranslation(v3a).rotate(slopeQ).rotate(axisY,
                                MathUtils.atan2(z.dirX, z.dirZ) * MathUtils.radDeg)
                        .scale(z.halfX * 2f, 0.16f, z.halfZ * 2f);
                add(meshes.translucent(MeshFactory.KIND_DISC,
                        Color.rgba8888(C_CONVEYOR)), m4);
                break;
            case ICE:
                v3a.set(z.center.x, y, z.center.z);
                m4.setToTranslation(v3a).rotate(slopeQ).scale(z.halfX * 2f, 0.14f, z.halfZ * 2f);
                add(meshes.translucent(MeshFactory.KIND_DISC,
                        Color.rgba8888(C_ICE)), m4);
                break;
            case LAVA:
                v3a.set(z.center.x, z.center.y + 0.9f, z.center.z);
                m4.setToTranslation(v3a).rotate(slopeQ).scale(z.radius, 0.08f, z.radius);
                add(meshes.translucent(MeshFactory.KIND_DISC,
                        Color.rgba8888(C_LAVA)), m4);
                break;
            default:
                break;
        }
    }

    private void addObstacle(LevelDirector dir, Obstacle o) {
        // 机关必须贴合坡度：底座按地形法线倾斜，自转轴跟着变成坡面法线。
        // 否则在斜坡上摆的机关一转就会一头扎进地里、一头悬空 —— 也就是"穿模"。
        alignToSlope(dir, o.pos.x, o.pos.z);
        switch (o.kind) {
            case HAMMER: {
                float deg = o.angle * MathUtils.radDeg;
                m4.setToTranslation(o.worldPos)
                        .rotate(slopeQ)
                        .rotate(axisY, -deg)
                        .rotate(axisZ, -90f)
                        .scale(o.thickness / 0.16f, o.radius, o.thickness / 0.16f);
                add(meshes.geo(MeshFactory.KIND_POLE, C_HAZARD), m4);
                m4.setToTranslation(o.pos).rotate(slopeQ).scale(0.8f, 1.4f, 0.8f);
                add(meshes.geo(MeshFactory.KIND_BOX, C_METAL), m4);
                break;
            }
            case SAW:
                m4.setToTranslation(o.worldPos).rotate(slopeQ)
                        .rotate(axisY, o.angle * MathUtils.radDeg)
                        .scale(o.radius, 0.5f, o.radius);
                add(meshes.geo(MeshFactory.KIND_DISC, C_HAZARD), m4);
                break;
            case PISTON:
                m4.setToTranslation(o.worldPos).rotate(slopeQ).rotate(axisY,
                                MathUtils.atan2(o.dirX, o.dirZ) * MathUtils.radDeg)
                        .scale(o.radius, 0.8f, o.radius);
                add(meshes.geo(MeshFactory.KIND_BOX, C_METAL), m4);
                break;
            case CRUSHER:
                m4.setToTranslation(o.worldPos).rotate(slopeQ)
                        .scale(o.radius, 0.9f, o.radius);
                add(meshes.geo(MeshFactory.KIND_BOX, C_HAZARD), m4);
                break;
            case BOULDER:
                m4.setToTranslation(o.worldPos).scale(o.radius, o.radius, o.radius);
                add(meshes.geo(MeshFactory.KIND_BALL, 0x9a8c98ff), m4);
                break;
            case FAN:
                m4.setToTranslation(o.pos).rotate(slopeQ).scale(1.6f, 3.2f, 1.6f);
                add(meshes.translucent(MeshFactory.KIND_BOX,
                        Color.rgba8888(C_FAN)), m4);
                break;
            case SPINNER:
                m4.setToTranslation(o.worldPos).rotate(slopeQ)
                        .rotate(axisY, o.angle * MathUtils.radDeg)
                        .scale(o.radius, 0.28f, o.radius);
                add(meshes.geo(MeshFactory.KIND_DISC, C_CONVEYOR), m4);
                break;
            default:
                m4.setToTranslation(o.pos).scale(o.radius * 2f, o.height, o.radius * 2f);
                add(meshes.geo(MeshFactory.KIND_BOX, C_WOOD), m4);
                break;
        }
    }

    // ================================================================== Renderable 池




    private final Quaternion slopeQ = new Quaternion();
    private final Vector3 slopeN = new Vector3();

    /**
     * 把物体的 +Y 轴对齐到该点的地形法线。
     *
     * <p>用法固定在 {@code setToTranslation(...).rotate(slopeQ).rotate(轴, 角度).scale(...)}：
     * Matrix4 的 rotate 是右乘，所以<b>最后写的 rotate 最先作用于顶点</b> ——
     * 这样"自转"发生在物体自己的局部坐标系里，再整体倾斜贴合坡度，
     * 旋转轴自动变成坡面法线。机关转起来就不会一头插进地里、一头悬空。
     */
    private void alignToSlope(LevelDirector dir, float x, float z) {
        MapDef m = dir.map();
        if (m == null || m.field == null) {
            slopeQ.idt();
            return;
        }
        m.field.normalAt(x, z, slopeN);
        if (slopeN.y < 0.2f) {
            slopeQ.idt();
            return;
        }
        slopeQ.setFromCross(axisY, slopeN);
        if (slopeQ.x == 0f && slopeQ.y == 0f && slopeQ.z == 0f && slopeQ.w == 0f) {
            slopeQ.idt();
        }
    }

    private void add(MeshFactory.Geo geo, Matrix4 transform) {
        Renderable r = obtain();
        r.meshPart.set(geo.part);
        r.material = geo.material;
        r.environment = env;
        r.worldTransform.set(transform);
        frame.add(r);
    }

    private Renderable obtain() {
        if (cursor >= pool.size) {
            pool.add(new Renderable());
        }
        return pool.get(cursor++);
    }

    /** 当前渲染了多少个实例（HUD/日志用）。 */
    public int instanceCount() {
        return frame.size;
    }

    @Override
    public void dispose() {
        batch.dispose();
        disposeTerrain();
    }
}
