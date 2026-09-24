package top.gunmu.party.tools;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import java.util.Random;

import top.gunmu.party.GameConfig;
import top.gunmu.party.ai.BotBrain;
import top.gunmu.party.core.Roll;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapRegistry;
import top.gunmu.party.map.Route;
import top.gunmu.party.map.TrackProgress;
import top.gunmu.party.physics.PhysicsWorld;

/**
 * 「这条赛道到底能不能跑完」的端到端自检。
 *
 * <p>之前只有 {@code RouteAudit} 那种<b>静态</b>检查（逐格问"这里读到的弧长对不对"），
 * 它证明不了"真的跑一遍能不能到终点" —— 玩家报的「之字断崖根本没办法通关」
 * 正好落在静态审计的盲区里。
 *
 * <p>这里跑两种驾驶员：
 *
 * <ul>
 *   <li><b>理想驾驶员</b>：直接朝赛程前方 20 米处的中心点开，不走 AI。
 *       它测的是<b>路 + 判定</b>本身通不通 —— 如果连它都跑不完，问题一定在几何或判定上。</li>
 *   <li><b>BotBrain</b>：测 AI 的驾驶水平。跑不完说明 AI 太笨（调 AI，不是改路）。</li>
 * </ul>
 *
 * <p>顺带抓两类数值问题：
 *
 * <ul>
 *   <li><b>跑不到终点</b>：时限内进度到不了总长；</li>
 *   <li><b>进度跳变（偷鸡）</b>：单帧弧长增量超过物理上可能的最大值
 *       （{@code 速度上限 × dt × 余量}），说明最近点判到别的路段上去了。</li>
 * </ul>
 */
public final class RaceRunTest {

    /** 允许跑多久（模拟帧数）。时限 300 秒，这里给够 120 秒就够判断了。 */
    private static final int MAX_TICKS = (int) (120f / GameConfig.FIXED_DT);
    /**
     * 单帧弧长增量的上限（米）。
     *
     * <p>物理上限是 {@code DOWNHILL_MAX_SPEED × dt ≈ 0.37m}，但判定本身在急弯处会有
     * 几米到二十几米的抖动（两条边等距时最近段切换）。这里取 30 米 ——
     * <b>判据是"会不会影响公平"</b>：存档点间距是 {@code 620/5 = 124m}，
     * 单帧跳 30 米不足以跨过一个存档点，也不会凭空判通关；
     * 而真正的"跨段偷鸡"（旧版之字断崖跳到中段）是 380 米级别的，一定会被抓住。
     */
    private static final float JUMP_TOLERANCE = 30f;

    private RaceRunTest() {
    }

    /** 单独跑（调地图/平衡时用）。 */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    public static int run() {
        System.out.println("---- 竞速赛跑审计（能不能真的跑完）----");
        int bad = 0;
        for (int i = 0; i < MapRegistry.RACE_IDS.length; i++) {
            bad += runOne(MapRegistry.RACE_IDS[i], false);
            bad += runOne(MapRegistry.RACE_IDS[i], true);
        }
        System.out.println(bad == 0 ? "  4 张竞速图全部跑得完，进度没有跳变"
                : "  竞速赛跑审计失败 " + bad + " 项");
        return bad;
    }

    /** @param useAi true 用 BotBrain 驾驶，false 用理想驾驶员。 */
    private static int runOne(String id, boolean useAi) {
        MapDef map = MapRegistry.create(id);
        if (!map.hasRoute()) {
            System.out.println("  [FAIL] " + id + " 是竞速图但没有 route");
            return 1;
        }
        Route route = map.route;
        TrackProgress track = new TrackProgress(map);

        Roll r = new Roll(0, "runner", true);
        Vector3 spawn = map.spawns.first();
        r.pos.set(spawn);
        r.vel.setZero();

        PhysicsWorld world = new PhysicsWorld(map.field);
        // 起点屏障在这里要关掉：本测试测的是"路+判定+AI"，不是开局封锁
        world.setStartBarrier(false, 0f, 0f, 0f);
        world.mapFloorY = map.minHeight - 80f;

        Array<Roll> rolls = new Array<>();
        rolls.add(r);

        // ---- 诊断：起点结构 ----
        {
            StringBuilder sb = new StringBuilder();
            sb.append("  [调] ").append(id).append(" 总长 ")
              .append(String.format(java.util.Locale.ROOT, "%.1f", route.totalLength))
              .append("  段数 ").append(route.nodeCount())
              .append("  出生点数 ").append(map.spawns.size).append('\n');
            for (int k = 0; k < Math.min(8, route.nodeCount()); k++) {
                Route.Node n = route.node(k);
                sb.append(String.format(java.util.Locale.ROOT,
                        "        段%d node(%.1f, %.1f, %.1f) cum=%.1f hw=%.1f sh=%.1f%n",
                        k, n.x, n.y, n.z, route.arcAt(k, 0f), n.halfWidth, n.shoulder));
            }
            for (int k : new int[]{0, map.spawns.size - 1}) {
                Vector3 sp = map.spawns.get(k);
                sb.append(String.format(java.util.Locale.ROOT,
                        "        spawn[%d] (%.1f, %.1f, %.1f) walkable=%s arcAt=%.2f%n",
                        k, sp.x, sp.y, sp.z, map.field.walkableAt(sp.x, sp.z),
                        track.arcAt(sp.x, sp.y, sp.z)));
            }
            System.out.print(sb);
        }

        BotBrain brain = new BotBrain();
        Random rng = new Random(12345L);
        r.aiDifficulty = 2;

        final Vector3 aim = new Vector3();
        float lastArc = 0f;
        float maxJump = 0f;
        int frozenTicks = 0;
        int fellTicks = 0;
        int finishTick = -1;

        int label = 0;
        for (int t = 0; t < MAX_TICKS; t++) {
            label = t;
            if (useAi) {
                brain.think(r, map, rolls, null, null, null, rng, GameConfig.FIXED_DT);
            } else {
                driveIdeal(map, r, aim, lastArc);
            }
            r.tickTimers(GameConfig.FIXED_DT);
            world.step(rolls, GameConfig.FIXED_DT);

            // 掉出地图：本测试不模拟复活，直接判定"跑不完"
            if (r.pos.y < world.mapFloorY) {
                fellTicks++;
                break;
            }

            float arc = track.arcAt(r.pos.x, r.pos.y, r.pos.z, r.pathHint);
            if (arc < 0f) {
                frozenTicks++;
            } else {
                float delta = arc - lastArc;
                if (delta > maxJump) {
                    maxJump = delta;
                }
                if (arc > r.progress) {
                    r.progress = arc;
                }
                lastArc = arc;
            }
            if (r.progress >= route.totalLength - 2f) {
                finishTick = t;
                break;
            }
        }

        String who = useAi ? "AI   " : "理想 ";
        String name = pad(map.name);
        float limitJump = JUMP_TOLERANCE;
        int problems = 0;

        if (finishTick < 0) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "  [FAIL] %s%s 没跑到终点：进度 %.0f/%.0f（%.0fs），掉落 %d 帧，冻结 %d 帧",
                    who, name, r.progress, route.totalLength,
                    label * GameConfig.FIXED_DT, fellTicks, frozenTicks));
            System.out.println(String.format(java.util.Locale.ROOT,
                    "        停在 (%.1f, %.1f, %.1f)  最高速度 %.1f  段提示 %d  最近路点弧长 %.1f",
                    r.pos.x, r.pos.y, r.pos.z, r.speed(), r.pathHint[0], lastArc));
            // 附近有哪些路段（用来判断是不是卡在两条路之间）
            for (int k = 0; k < route.nodeCount() - 1; k++) {
                Route.Node n = route.node(k);
                Route.Node n2 = route.node(k + 1);
                float d = RouteOverlapTest.segDist(r.pos.x, r.pos.z, r.pos.x, r.pos.z,
                        n.x, n.z, n2.x, n2.z);
                if (d < 14f) {
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "        附近段%d arc %.1f→%.1f  y %.1f→%.1f  距 %.1fm",
                            k, route.arcAt(k, 0f), route.arcAt(k, 1f),
                            n.y, n2.y, d));
                }
            }
            problems++;
        } else {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "  [OK]   %s%s 用时 %.1fs（时限 %.0fs），冻结 %d 帧，最大单帧增量 %.2fm",
                    who, name, finishTick * GameConfig.FIXED_DT,
                    map.timeLimit, frozenTicks, maxJump));
        }
        if (maxJump > limitJump) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "  [FAIL] %s%s 进度跳变 %.2fm > 上限 %.2fm（判定跳到了别处路段 → 偷鸡）",
                    who, name, maxJump, limitJump));
            problems++;
        }
        return problems;
    }

    /**
     * 理想驾驶员：朝赛程前方 20 米处的中心点开。
     *
     * <p>不做任何绕障/避坑，所以它跑得完 = 路和判定本身没问题。
     */
    private static void driveIdeal(MapDef map, Roll r, Vector3 aim, float lastArc) {
        Route route = map.route;
        // 目标点取"上一帧读到的弧长 + 前瞻"，**不要取 progress**：
        // progress 只增不减，判定一旦短暂滞后它就不动，于是目标点定死在玩家身后，
        // 车在原地打转 —— 这个假探针曾经因此误报「之字断崖跑不完」。
        float base = lastArc > 0f ? lastArc : 0f;
        float s = Math.min(base + 20f, route.totalLength - 0.01f);
        route.pointAt(s, 0f, aim);
        float dx = aim.x - r.pos.x;
        float dz = aim.z - r.pos.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4f) {
            r.input.move.setZero();
            return;
        }
        r.input.move.set(dx / len, 0f, dz / len);
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 6) {
            b.append(' ');
        }
        return b.toString();
    }
}
