package top.gunmu.party.map;

import com.badlogic.gdx.math.Vector3;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Mode;

/**
 * 地图构建期的硬校验。任何一项不通过都**直接抛异常**，禁止静默降级。
 * 对应文档：docs/02-LEVELS-AND-MAPS.md §8
 */
public final class MapValidator {

    /** 存档点前后这段距离内不允许有伤害机关。 */
    private static final float CHECKPOINT_SAFE_BACK = 10f;
    private static final float CHECKPOINT_SAFE_FORWARD = 30f;
    /** RACE 图下坡占比下限。 */
    private static final float MIN_DOWNHILL_RATIO = 0.35f;
    /** 判定为"下坡"的坡度阈值。 */
    private static final float DOWNHILL_GRADE = 0.15f;
    /** 同模式地图伤害预算的相对容差。 */
    private static final float BUDGET_TOLERANCE = 0.10f;

    private MapValidator() {
    }

    public static void validate(MapDef m) {
        switch (m.mode) {
            case RACE:
                expect(m.checkpoints.size, MapRegistry.RACE_CHECKPOINTS, m, "存档点数");
                expect(m.itemSpawns.size, MapRegistry.RACE_ITEM_SPAWNS, m, "道具刷新点数");
                expect(m.spawns.size, MapRegistry.RACE_SPAWNS, m, "出生点数");
                checkDownhill(m);
                checkCheckpointSafety(m);
                budget(m, MapRegistry.RACE_DAMAGE_BUDGET);
                break;
            case SURVIVAL:
                expect(m.checkpoints.size, 0, m, "存档点数");
                expect(m.itemSpawns.size, MapRegistry.SURVIVAL_ITEM_SPAWNS, m, "道具刷新点数");
                expect(m.spawns.size, MapRegistry.SURVIVAL_SPAWNS, m, "出生点数");
                budget(m, MapRegistry.SURVIVAL_DAMAGE_BUDGET);
                break;
            case ARENA:
                expect(m.checkpoints.size, 0, m, "存档点数");
                expect(m.itemSpawns.size, MapRegistry.ARENA_ITEM_SPAWNS, m, "道具刷新点数");
                expect(m.spawns.size, MapRegistry.ARENA_SPAWNS, m, "出生点数");
                budget(m, MapRegistry.ARENA_DAMAGE_BUDGET);
                break;
            default:
                break;
        }
        checkItemSpawnsWalkable(m);
        checkSpawnSpacing(m);
    }

    /**
     * 起点屏障硬校验。<b>失败直接抛异常，不许静默降级。</b>
     *
     * <p>管三件事：
     * <ol>
     *   <li>所有出生点都必须在屏障内（含贴地半径的余量），否则有人在屏障外出生；</li>
     *   <li>屏障中心脚下必须是可行走面，否则开局所有人直接掉进虚空；</li>
     *   <li>屏障半径要能容下活动空间，不能缩成一个点。</li>
     * </ol>
     */
    public static void validateStartZone(MapDef m) {
        if (m.spawns.size == 0) {
            return;
        }
        if (m.startRadius <= 0f) {
            throw new IllegalStateException(
                    "[起点屏障校验失败] " + m.id + " 没有推导出起点区域半径");
        }
        for (int i = 0; i < m.spawns.size; i++) {
            Vector3 v = m.spawns.get(i);
            float dx = v.x - m.startCenter.x;
            float dz = v.z - m.startCenter.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            // 半径本身是"最远出生点 + margin"，所以这里必然通过；
            // 留着是为了防止将来有人把半径改成手写常量。
            if (d + GameConfig.LOG_RADIUS > m.startRadius) {
                throw new IllegalStateException(String.format(
                        "[起点屏障校验失败] %s 的出生点 #%d 在屏障外（距中心 %.2fm > 半径 %.2fm）",
                        m.id, i, d, m.startRadius));
            }
        }
        if (!m.field.walkableAt(m.startCenter.x, m.startCenter.z)) {
            throw new IllegalStateException(String.format(
                    "[起点屏障校验失败] %s 的起点区域中心 (%.1f, %.1f) 不是可行走面",
                    m.id, m.startCenter.x, m.startCenter.z));
        }
        if (m.startRadius < GameConfig.START_BARRIER_MIN_RADIUS - 1e-3f) {
            throw new IllegalStateException(String.format(
                    "[起点屏障校验失败] %s 的起点区域半径 %.2fm 小于下限 %.2fm",
                    m.id, m.startRadius, GameConfig.START_BARRIER_MIN_RADIUS));
        }
    }

    private static void expect(int actual, int wanted, MapDef m, String what) {
        if (actual != wanted) {
            throw new IllegalStateException(
                    "[地图校验失败] " + m.id + " 的" + what + " 应为 " + wanted + "，实际 " + actual);
        }
    }

    private static void checkDownhill(MapDef m) {
        Route r = m.route;
        float total = 0f;
        float downhill = 0f;
        Vector3 a = new Vector3();
        Vector3 b = new Vector3();
        int steps = 400;
        for (int i = 0; i < steps; i++) {
            float s0 = r.totalLength * i / steps;
            float s1 = r.totalLength * (i + 1) / steps;
            r.sampleAt(s0, a);
            r.sampleAt(s1, b);
            float dx = b.x - a.x;
            float dz = b.z - a.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < 1e-4f) {
                continue;
            }
            total += d;
            float grade = (a.y - b.y) / d;
            if (grade >= DOWNHILL_GRADE) {
                downhill += d;
            }
        }
        float ratio = total > 0f ? downhill / total : 0f;
        if (ratio < MIN_DOWNHILL_RATIO) {
            throw new IllegalStateException(String.format(
                    "[地图校验失败] %s 下坡占比 %.1f%% < %.0f%%",
                    m.id, ratio * 100f, MIN_DOWNHILL_RATIO * 100f));
        }
    }

    private static void checkCheckpointSafety(MapDef m) {
        if (m.checkpoints.size == 0) {
            return;
        }
        float[] near = new float[4];
        for (int i = 0; i < m.checkpoints.size; i++) {
            float cpArc = m.route.progressAt(
                    m.checkpoints.get(i).pos.x, m.checkpoints.get(i).pos.z, near);
            for (int j = 0; j < m.obstacles.size; j++) {
                Obstacle o = m.obstacles.get(j);
                if (o.kind.damage <= 0f) {
                    continue;
                }
                float oArc = m.route.progressAt(o.pos.x, o.pos.z, near);
                float d = oArc - cpArc;
                if (d >= -CHECKPOINT_SAFE_BACK && d <= CHECKPOINT_SAFE_FORWARD) {
                    throw new IllegalStateException(String.format(
                            "[地图校验失败] %s 存档点 #%d 旁边 %.1fm 处有 %s",
                            m.id, i + 1, d, o.kind.cn));
                }
            }
        }
    }

    private static void budget(MapDef m, float expected) {
        float sum = 0f;
        for (int i = 0; i < m.obstacles.size; i++) {
            sum += m.obstacles.get(i).kind.damage;
        }
        float diff = Math.abs(sum - expected) / expected;
        if (diff > BUDGET_TOLERANCE) {
            throw new IllegalStateException(String.format(
                    "[地图校验失败] %s 机关伤害预算 %.0f，应为 %.0f（偏差 %.1f%%）",
                    m.id, sum, expected, diff * 100f));
        }
    }

    private static void checkItemSpawnsWalkable(MapDef m) {
        for (int i = 0; i < m.itemSpawns.size; i++) {
            MapDef.ItemSpawn s = m.itemSpawns.get(i);
            if (!m.field.walkableAt(s.pos.x, s.pos.z)) {
                throw new IllegalStateException(String.format(
                        "[地图校验失败] %s 道具刷新点 #%d (%.1f, %.1f) 不在可行走面上",
                        m.id, i, s.pos.x, s.pos.z));
            }
        }
    }

    private static void checkSpawnSpacing(MapDef m) {
        float minGap = GameConfig.LOG_RADIUS * 2f + 0.2f;
        float min2 = minGap * minGap;
        for (int i = 0; i < m.spawns.size; i++) {
            Vector3 a = m.spawns.get(i);
            if (!m.field.walkableAt(a.x, a.z)) {
                throw new IllegalStateException(String.format(
                        "[地图校验失败] %s 出生点 #%d 落在深渊上", m.id, i));
            }
            for (int j = i + 1; j < m.spawns.size; j++) {
                Vector3 b = m.spawns.get(j);
                float dx = a.x - b.x;
                float dz = a.z - b.z;
                if (dx * dx + dz * dz < min2) {
                    throw new IllegalStateException(String.format(
                            "[地图校验失败] %s 出生点 #%d 与 #%d 间距过小", m.id, i, j));
                }
            }
        }
    }
}
