package top.gunmu.party.tools;

import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Roll;
import top.gunmu.party.core.TerrainType;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapRegistry;
import top.gunmu.party.physics.HeightField;
import top.gunmu.party.physics.PhysicsWorld;

/**
 * 起点屏障自检。
 *
 * <p>需求：「8 秒之前所有人都被困在起点区域内，8 秒后这层屏障消失，被困住的期间可以随便移动」。
 *
 * <p>这句话里其实有**两条互相拉扯**的约束，缺一条就算做错：
 *
 * <ol>
 *   <li><b>出不去</b>：拼了命往外冲（甚至一边冲一边跳）也不能越界；</li>
 *   <li><b>随便动</b>：在里面必须能正常跑，不能被"卡住"——
 *       只做第一条很容易写成"开局把所有人钉死原地"。</li>
 * </ol>
 *
 * <p>所以这个自检正反都测。前两条性质在真实地图的真实地形上跑，
 * 因为屏障半径是从出生点推出来的，只测合成平地证明不了它裹得住真出生点。
 */
public final class StartBarrierTest {

    /** 往外冲的时长（秒）。比屏障时长还长，确保是"屏障"挡住而不是"还没跑出去"。 */
    private static final float CHARGE_TIME = GameConfig.START_BARRIER_TIME + 4f;

    private StartBarrierTest() {
    }

    public static int run() {
        System.out.println("---- 起点屏障自检 ----");
        int bad = 0;
        bad += testSynthetic();
        bad += testRealMaps();
        System.out.println(bad == 0 ? "  起点屏障行为正确" : "  起点屏障自检失败 " + bad + " 项");
        return bad;
    }

    // ================================================================== 合成平地

    private static int testSynthetic() {
        int bad = 0;
        float radius = 12f;
        HeightField field = flatField();
        PhysicsWorld world = new PhysicsWorld(field);
        world.setStartBarrier(true, 0f, 0f, radius);

        // ---- 1) 里面必须能自由移动（"被困住的期间可以随便移动"）----
        Roll runner = newRoll(0, 0f, -5f);
        Array<Roll> rolls = new Array<>();
        rolls.add(runner);
        float worst = 0f;
        for (int i = 0; i < 90; i++) {
            runner.input.move.set(1f, 0f, 0f);
            world.step(rolls, GameConfig.FIXED_DT);
            worst = Math.max(worst, (float) Math.hypot(runner.pos.x, runner.pos.z));
        }
        float moved = (float) Math.hypot(runner.pos.x, runner.pos.z + 5f);
        if (moved < 3f) {
            System.out.println("  [FAIL] 屏障内 1.5 秒只移动了 " + fmt(moved)
                    + "m —— 人被卡住了，这不算可移动");
            bad++;
        } else {
            System.out.println("  [OK]   屏障内可自由移动：1.5 秒前进 " + fmt(moved) + "m");
        }
        if (worst > radius - GameConfig.LOG_COLLIDE_RADIUS + 0.05f) {
            System.out.println("  [FAIL] 移动过程中越界了：" + fmt(worst));
            bad++;
        }

        // ---- 2) 朝外拼命冲 + 跳，必须出不去 ----
        Roll charger = newRoll(1, 0f, 6f);
        rolls.clear();
        rolls.add(charger);
        float maxOut = 0f;
        int steps = Math.round(CHARGE_TIME / GameConfig.FIXED_DT);
        for (int i = 0; i < steps; i++) {
            float d = (float) Math.hypot(charger.pos.x, charger.pos.z);
            if (d > 1e-4f) {
                charger.input.move.set(charger.pos.x / d, 0f, charger.pos.z / d);
            } else {
                charger.input.move.set(0f, 0f, 1f);
            }
            if (i % 30 == 0) {
                charger.input.jump = true;       // 一边冲一边跳，不许靠跳跃越狱
            }
            world.step(rolls, GameConfig.FIXED_DT);
            maxOut = Math.max(maxOut, (float) Math.hypot(charger.pos.x, charger.pos.z));
        }
        float limit = radius - GameConfig.LOG_COLLIDE_RADIUS;
        if (maxOut > limit + 0.05f) {
            System.out.println("  [FAIL] 朝外冲 " + fmt(CHARGE_TIME) + " 秒最远到了 "
                    + fmt(maxOut) + "m，超过上限 " + fmt(limit) + "m");
            bad++;
        } else {
            System.out.println("  [OK]   朝外冲 + 跳 " + fmt(CHARGE_TIME) + " 秒"
                    + "（含 +4 秒余量）最远 " + fmt(maxOut) + "m ≤ 上限 " + fmt(limit) + "m");
        }
        if (maxOut < limit - 0.5f) {
            System.out.println("  [FAIL] 压根没贴到屏障上（最远 "
                    + fmt(maxOut) + "m），这条断言其实没验到东西");
            bad++;
        }

        // ---- 3) 屏障关掉之后必须出得去 ----
        world.setStartBarrier(false, 0f, 0f, radius);
        for (int i = 0; i < 180; i++) {
            float d = (float) Math.hypot(charger.pos.x, charger.pos.z);
            if (d > 1e-4f) {
                charger.input.move.set(charger.pos.x / d, 0f, charger.pos.z / d);
            }
            world.step(rolls, GameConfig.FIXED_DT);
        }
        float out = (float) Math.hypot(charger.pos.x, charger.pos.z);
        if (out <= radius + 2f) {
            System.out.println("  [FAIL] 屏障消失后 3 秒仍停在 " + fmt(out)
                    + "m，出不去（屏障没真正关掉？）");
            bad++;
        } else {
            System.out.println("  [OK]   屏障消失后 3 秒跑到 " + fmt(out) + "m，可以出去了");
        }

        return bad;
    }

    // ================================================================== 真实地图

    private static int testRealMaps() {
        int bad = 0;
        int checked = 0;
        for (String id : allIds()) {
            MapDef m = MapRegistry.create(id);
            if (m.spawns.size == 0 || m.startRadius <= 0f) {
                continue;
            }
            // 起点区域必须装得下所有出生点
            for (int i = 0; i < m.spawns.size; i++) {
                if (!m.insideStartZone(m.spawns.get(i).x, m.spawns.get(i).z)) {
                    System.out.println("  [FAIL] " + pad(m.name) + " 出生点 #" + i + " 在屏障外");
                    bad++;
                    break;
                }
            }

            // 抽 3 个出生点：朝外冲，全程不许出圈
            int[] picks = {0, m.spawns.size / 2, m.spawns.size - 1};
            float worst = 0f;
            for (int k = 0; k < picks.length; k++) {
                int idx = picks[k];
                if (idx < 0 || idx >= m.spawns.size) {
                    continue;
                }
                float sx = m.spawns.get(idx).x;
                float sy = m.spawns.get(idx).y;
                float sz = m.spawns.get(idx).z;
                PhysicsWorld w = new PhysicsWorld(m.field);
                w.statics.addAll(m.walls);
                w.zones.addAll(m.zones);
                w.mapFloorY = m.minHeight - 6f;
                w.waterLevel = -1e9f;
                w.poisonRadius = -1f;
                w.setStartBarrier(true, m.startCenter.x, m.startCenter.z, m.startRadius);

                Roll r = new Roll(0, "测试", true);
                r.pos.set(sx, sy, sz);
                Array<Roll> rolls = new Array<>();
                rolls.add(r);
                boolean escaped = false;
                for (int i = 0; i < 480 && !escaped; i++) {
                    float dx = r.pos.x - m.startCenter.x;
                    float dz = r.pos.z - m.startCenter.z;
                    float d = (float) Math.hypot(dx, dz);
                    if (d > 1e-4f) {
                        r.input.move.set(dx / d, 0f, dz / d);
                    }
                    if (i % 30 == 0) {
                        r.input.jump = true;
                    }
                    w.step(rolls, GameConfig.FIXED_DT);
                    float now = (float) Math.hypot(r.pos.x - m.startCenter.x,
                            r.pos.z - m.startCenter.z);
                    worst = Math.max(worst, now);
                    if (!m.insideStartZone(r.pos.x, r.pos.z)
                            && now > m.startRadius - GameConfig.LOG_COLLIDE_RADIUS + 0.05f) {
                        System.out.println("  [FAIL] " + pad(m.name) + " 出生点 #" + idx
                                + " 冲出了屏障：" + fmt(now) + "m > " + fmt(m.startRadius) + "m");
                        bad++;
                        escaped = true;
                    }
                }
                checked++;
            }
            System.out.println("  [OK]   " + pad(m.name) + " 半径 " + fmt(m.startRadius)
                    + "m，出生点 " + m.spawns.size + " 个全在内，"
                    + picks.length + " 个点朝外冲 8 秒最远 " + fmt(worst) + "m");
        }
        if (bad == 0) {
            System.out.println("  " + checked + " 个出生点全部冲不出起点区域");
        }
        return bad;
    }

    // ================================================================== 工具

    private static Array<String> allIds() {
        Array<String> ids = new Array<>();
        for (String s : MapRegistry.RACE_IDS) {
            ids.add(s);
        }
        for (String s : MapRegistry.SURVIVAL_IDS) {
            ids.add(s);
        }
        for (String s : MapRegistry.ARENA_IDS) {
            ids.add(s);
        }
        return ids;
    }

    private static HeightField flatField() {
        HeightField f = new HeightField(-40f, -40f, 80f, 80f, 1f);
        for (int iz = 0; iz < f.nz; iz++) {
            for (int ix = 0; ix < f.nx; ix++) {
                f.set(ix, iz, 0f, true, TerrainType.GRASS);
            }
        }
        return f;
    }

    private static Roll newRoll(int id, float x, float z) {
        Roll r = new Roll(id, "测试", true);
        r.pos.set(x, GameConfig.LOG_RADIUS, z);
        return r;
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 10) {
            b.append(' ');
        }
        return b.toString();
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
