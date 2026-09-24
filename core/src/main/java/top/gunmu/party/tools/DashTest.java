package top.gunmu.party.tools;

import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Roll;
import top.gunmu.party.core.TerrainType;
import top.gunmu.party.physics.HeightField;
import top.gunmu.party.physics.PhysicsWorld;

/**
 * 加速（Shift）自检。
 *
 * <p>需求：「添加加速，shift，冷却 15 秒，加速后移速提升 50%」。三件事都要能被证伪：
 *
 * <ol>
 *   <li><b>真的快了 50%</b> —— 不是只改了一个没人读的倍率，而是实跑出来的速度确实高一半；</li>
 *   <li><b>冷却真的是 15 秒</b> —— 冷却期间连按不会生效，冷却结束后才能再按；</li>
 *   <li><b>边沿信号被消费</b> —— 同 D43：按住不放不会变成"加速常开"。</li>
 * </ol>
 */
public final class DashTest {

    private DashTest() {
    }

    public static int run() {
        System.out.println("---- 加速自检（Shift / 触屏「加速」）----");
        int bad = 0;
        bad += testSpeedAndCooldown();
        bad += testEdgeConsumed();
        System.out.println(bad == 0 ? "  加速行为正确" : "  加速自检失败 " + bad + " 项");
        return bad;
    }

    /** 平地上直线冲：加速组应当比对照组快约 50%。 */
    private static int testSpeedAndCooldown() {
        int bad = 0;
        float normalPeak = peakSpeed(false);
        float dashPeak = peakSpeed(true);

        float ratio = normalPeak > 1e-3f ? dashPeak / normalPeak : 0f;
        System.out.println(String.format(java.util.Locale.ROOT,
                "  [..]   平地峰值速度：常规 %.2f m/s，加速 %.2f m/s，比值 %.3f",
                normalPeak, dashPeak, ratio));

        if (Math.abs(ratio - GameConfig.DASH_SPEED_MULT) > 0.08f) {
            System.out.println("  [FAIL] 加速倍率不是 "
                    + GameConfig.DASH_SPEED_MULT + "（实测比值 " + fmt(ratio) + "）");
            bad++;
        } else {
            System.out.println("  [OK]   移速提升 " + fmt((ratio - 1f) * 100f)
                    + "%（目标 " + fmt((GameConfig.DASH_SPEED_MULT - 1f) * 100f) + "%）");
        }

        // ---- 冷却与持续 ----
        PhysicsWorld world = new PhysicsWorld(flatField());
        Roll r = newRoll();
        Array<Roll> rolls = new Array<>();
        rolls.add(r);

        r.input.dash = true;
        step(world, rolls, r);
        if (world.dashCount != 1) {
            System.out.println("  [FAIL] 第一次按加速没有生效（dashCount=" + world.dashCount + "）");
            return bad + 1;
        }
        // 触发那一帧的末尾就会扣掉一个 dt（tickTimers 在同一步里跑），所以容差给一帧
        float frame = GameConfig.FIXED_DT * 1.5f;
        if (Math.abs(r.dashTimer - GameConfig.DASH_TIME) > frame) {
            System.out.println("  [FAIL] 加速时长应为 " + GameConfig.DASH_TIME
                    + "s，实际 " + fmt(r.dashTimer));
            bad++;
        }
        if (Math.abs(r.dashCd - GameConfig.DASH_COOLDOWN) > frame) {
            System.out.println("  [FAIL] 冷却应为 " + GameConfig.DASH_COOLDOWN
                    + "s，实际 " + fmt(r.dashCd));
            bad++;
        }
        System.out.println("  [OK]   按下后：加速 " + fmt(r.dashTimer) + "s、冷却 "
                + fmt(r.dashCd) + "s");

        // 冷却期间狂按：只应该成功 1 次
        int spamSeconds = (int) GameConfig.DASH_COOLDOWN + 1;
        int steps = Math.round(spamSeconds / GameConfig.FIXED_DT);
        for (int i = 0; i < steps; i++) {
            r.input.dash = true;
            if (rolls.size > 0) {
                r.input.move.set(0f, 0f, 1f);
            }
            step(world, rolls, r);
        }
        if (world.dashCount != 2) {
            System.out.println("  [FAIL] 冷却 " + spamSeconds + " 秒内共触发 "
                    + world.dashCount + " 次，期望 2 次（初始 1 次 + 冷却结束后 1 次）");
            bad++;
        } else {
            System.out.println("  [OK]   " + spamSeconds + " 秒种连按只触发 2 次"
                    + "（冷却卡住，冷却结束后可再按）");
        }

        // 冷却中 speedMultiplier 必须回到 1
        if (r.dashTimer <= 0f && Math.abs(r.speedMultiplier() - 1f) > 1e-4f) {
            System.out.println("  [FAIL] 加速结束后速度倍率仍为 " + fmt(r.speedMultiplier()));
            bad++;
        } else {
            System.out.println("  [OK]   加速结束后倍率回到 1.0");
        }
        return bad;
    }

    /** 按住不放（每帧都置位）不能变成常开。 */
    private static int testEdgeConsumed() {
        PhysicsWorld world = new PhysicsWorld(flatField());
        Roll r = newRoll();
        Array<Roll> rolls = new Array<>();
        rolls.add(r);

        r.input.dash = true;
        step(world, rolls, r);
        if (r.input.dash) {
            System.out.println("  [FAIL] input.dash 没有被消费（边沿信号会变成常开）");
            return 1;
        }
        // 紧接着（冷却中）再连续置位 3 秒，不该有任何一次成功
        int before = world.dashCount;
        for (int i = 0; i < 180; i++) {
            r.input.dash = true;
            step(world, rolls, r);
        }
        if (world.dashCount != before) {
            System.out.println("  [FAIL] 冷却中仍然触发了 " + (world.dashCount - before) + " 次");
            return 1;
        }
        System.out.println("  [OK]   input.dash 被消费；冷却中按了 3 秒无效");
        return 0;
    }

    /**
     * 推进一步。
     *
     * <p>⚠️ 真实游戏里计时器是 {@code LevelDirector} 每帧调 {@code Roll.tickTimers} 的，
     * {@code PhysicsWorld.step} 自己不管。直接调 step 而不调 tickTimers，
     * 冷却就永远不会走完 —— 第一版自检就是这么写错的。
     */
    private static void step(PhysicsWorld world, Array<Roll> rolls, Roll r) {
        world.step(rolls, GameConfig.FIXED_DT);
        r.tickTimers(GameConfig.FIXED_DT);
    }

    /** 平地上直线猛冲 3 秒，返回后 1 秒内的峰值速度。 */
    private static float peakSpeed(boolean dash) {
        PhysicsWorld world = new PhysicsWorld(flatField());
        Roll r = newRoll();
        if (dash) {
            r.input.dash = true;
        }
        Array<Roll> rolls = new Array<>();
        rolls.add(r);
        int steps = Math.round(3f / GameConfig.FIXED_DT);
        float peak = 0f;
        for (int i = 0; i < steps; i++) {
            r.input.move.set(0f, 0f, 1f);
            step(world, rolls, r);
            if (i > steps - 60) {
                peak = Math.max(peak, r.speed());
            }
        }
        return peak;
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

    private static Roll newRoll() {
        Roll r = new Roll(0, "测试", true);
        r.pos.set(0f, GameConfig.LOG_RADIUS, -20f);
        return r;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
