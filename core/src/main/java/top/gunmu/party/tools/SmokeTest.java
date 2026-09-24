package top.gunmu.party.tools;

import java.util.Locale;

import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;
import top.gunmu.party.console.ConsoleProbe;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapRegistry;

/**
 * 无窗口冒烟测试：验证地图校验器 + 整局淘汰赛流程能跑通且不抛异常。
 * 完全不触碰 OpenGL / 资源加载，因此可以在纯命令行里跑。
 *
 * <p>运行：{@code java -cp build/classes:libs/* top.gunmu.party.tools.SmokeTest}
 */
public final class SmokeTest {

    private SmokeTest() {
    }

    public static void main(String[] args) {
        int failures = 0;
        // 几何自检放最前：绕序反了 / 退化几何会让后面所有视觉相关的结论都不可信
        failures += PrimitiveTest.run();
        failures += InputMappingTest.run();
        failures += JumpTest.run();
        failures += StartBarrierTest.run();
        failures += TextWrapTest.run();
        failures += UiLayoutTest.run();
        failures += DashTest.run();
        failures += AttackTest.run();
        failures += ItemPoolTest.run();
        failures += MapNavTest.run();
        failures += MoveControlTest.run();
        failures += RaceRunTest.run();
        failures += RouteOverlapTest.run();
        failures += MapPickTest.run();
        failures += RouteAudit.run();
        failures += validateAllMaps();
        failures += TerrainAudit.run();
        failures += runTournament(args.length > 0 ? Long.parseLong(args[0]) : 20260921L);
        System.out.println();
        System.out.println(failures == 0
                ? "== 冒烟测试全部通过 =="
                : "== 冒烟测试有 " + failures + " 项失败 ==");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ================================================================== 地图

    private static int validateAllMaps() {
        System.out.println("---- 地图构建与校验 ----");
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

        int bad = 0;
        for (int i = 0; i < ids.size; i++) {
            String id = ids.get(i);
            long t0 = System.nanoTime();
            try {
                MapDef m = MapRegistry.create(id);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                System.out.printf(Locale.ROOT,
                        "  [OK] %-18s %-8s %-8s 存档%d 刷新点%d 出生%d 机关%d 区域%d"
                                + "  高[%.1f..%.1f]  %dms%n",
                        m.id, m.mode.cn, m.name,
                        m.checkpoints.size, m.itemSpawns.size, m.spawns.size,
                        m.obstacles.size, m.zones.size,
                        m.minHeight, m.maxHeight, ms);
                System.out.println("        " + m.field.stats());
            } catch (Throwable t) {
                bad++;
                System.out.println("  [FAIL] " + id + " -> " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }
        int expect = MapRegistry.RACE_IDS.length + MapRegistry.SURVIVAL_IDS.length
                + MapRegistry.ARENA_IDS.length;
        System.out.println("  地图数 " + ids.size + " / 期望 " + expect
                + (ids.size == expect ? "  ✓" : "  ✗"));
        return bad + (ids.size == expect ? 0 : 1);
    }

    // ================================================================== 整局


    /** 某一关用的是哪个模式（同一关只应有一张图）。 */
    private static top.gunmu.party.core.Mode modeOfLevel(
            top.gunmu.party.console.ConsoleProbe probe, int level) {
        // stageModes 是按进入顺序记的；关卡与图的对应关系由 levelVisits 推出来
        int idx = 0;
        for (int lv = 1; lv < level; lv++) {
            idx += probe.levelVisits[lv];
        }
        if (idx < 0 || idx >= probe.stageModes.size) {
            return null;
        }
        return probe.stageModes.get(idx);
    }

    private static int runTournament(long seed) {
        System.out.println();
        System.out.println("---- 整局淘汰赛（种子 " + seed + "）----");
        ConsoleProbe probe = new ConsoleProbe();
        try {
            probe.run(seed, 3600, 60_000);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            return 1;
        }
        System.out.println("  总逻辑帧 " + probe.ticks);
        System.out.println("  走完关卡 " + probe.levelsSeen);
        System.out.println("  触发道具使用 " + probe.itemUses + " 次，决战技 " + probe.ultUses + " 次");
        System.out.println("  观察到死亡 " + probe.deathsObserved + " 次");
        System.out.println("  观察到存档点激活 " + probe.checkpointHits + " 次");
        System.out.println("  观察到道具拾取 " + probe.pickups + " 次");
        System.out.println("  跳跃 " + probe.jumpCount + " 次");
        System.out.println("  最终阶段 " + probe.finalPhase + "，最后关卡 " + probe.finalLevel);
        if (probe.finalPhase == null) {
            System.out.println("  [FAIL] 流程没有走到任何结算");
            return 1;
        }
        // 关键指标断言
        int bad = 0;
        if (probe.deathsObserved == 0) {
            System.out.println("  [FAIL] 整局一次死亡都没有，物理/伤害链路可能没跑起来");
            bad++;
        }
        if (probe.itemUses == 0) {
            System.out.println("  [FAIL] 一次道具都没被用出去");
            bad++;
        }
        if (probe.pickups == 0) {
            System.out.println("  [FAIL] 一次道具都没被捡起");
            bad++;
        }
        if (probe.jumpCount == 0) {
            System.out.println("  [FAIL] 整局一次跳跃都没有 —— 跳跃链路可能又断了");
            bad++;
        }
        System.out.println("  加速 " + probe.dashCount + " 次");
        if (probe.dashCount == 0) {
            System.out.println("  [FAIL] 整局一次加速都没有 —— 加速链路可能又断了");
            bad++;
        }
        System.out.println("  普通攻击 " + probe.attackCount + " 次（只在决赛关会发生）");
        if (probe.levelVisits[5] > 0 && probe.attackCount == 0) {
            System.out.println("  [FAIL] 打到决赛关却一次挥击都没有 —— 攻击链路可能又断了");
            bad++;
        }
        if (probe.levelVisits[5] == 0 && probe.attackCount > 0) {
            System.out.println("  [FAIL] 没进决赛关却挥击了 " + probe.attackCount
                    + " 次 —— 普攻应当在第 5 关之前完全关闭");
            bad++;
        }
        System.out.println("  起点屏障生效 " + probe.barrierTicks + " 帧，期间越界 "
                + probe.barrierEscapes + " 次、死亡 " + probe.barrierDeaths + " 人");
        if (probe.barrierTicks < 400) {
            System.out.println("  [FAIL] 起点屏障总共只生效 " + probe.barrierTicks
                    + " 帧（8 秒应该是 480 帧）");
            bad++;
        }
        if (probe.barrierEscapes > 0) {
            System.out.println("  [FAIL] 屏障生效期间有人跑到了起点区域外 "
                    + probe.barrierEscapes + " 次");
            bad++;
        }
        if (probe.barrierDeaths > 0) {
            System.out.println("  [FAIL] 屏障生效期间死了 " + probe.barrierDeaths
                    + " 人 —— 起点区域里不该有深渊或致命机关");
            bad++;
        }
        if (probe.finalLevel < 2) {
            System.out.println("  [FAIL] 流程没有推进到第 2 关之后");
            bad++;
        }
        // 一局之内同模式的赛道图不能重复 —— 否则看起来就是"每关地图都一样"
        int dupRace = 0;
        java.util.HashSet<String> raceSeen = new java.util.HashSet<>();
        for (int i = 0; i < probe.stageMaps.size; i++) {
            if (probe.stageModes.get(i) != top.gunmu.party.core.Mode.RACE) {
                continue;
            }
            if (!raceSeen.add(probe.stageMaps.get(i))) {
                dupRace++;
                System.out.println("  [!!] 竞速图重复出现：" + probe.stageMaps.get(i));
            }
        }
        System.out.println("  本局竞速图 " + raceSeen.size() + " 张，重复 " + dupRace + " 次");

        // 关卡结构：每一关**只有一张图**（第 3 / 4 关是"抽签选模式"，不是子图连战）
        StringBuilder visits = new StringBuilder();
        for (int lv = 1; lv <= 5; lv++) {
            if (probe.levelVisits[lv] > 0) {
                visits.append("第").append(lv).append("关×")
                        .append(probe.levelVisits[lv]).append(" ");
            }
        }
        System.out.println("  关卡进入次数：" + visits);
        for (int lv = 1; lv <= 5; lv++) {
            if (probe.levelVisits[lv] > 1) {
                System.out.println("  [FAIL] 第 " + lv + " 关被进入了 "
                        + probe.levelVisits[lv] + " 次 —— 一关只能有一张图"
                        + "（第 3/4 关是二选一/三选一，不是子图连战）");
                bad++;
            }
        }
        // 模式合法性：第 3 关只能是竞速/生存，第 4 关只能是竞速/生存/竞技
        for (int lv = 1; lv <= 5; lv++) {
            if (probe.levelVisits[lv] == 0) {
                continue;
            }
            top.gunmu.party.core.Mode got = modeOfLevel(probe, lv);
            if (got == null) {
                continue;
            }
            boolean ok;
            switch (lv) {
                case 1: case 2:
                    ok = got == top.gunmu.party.core.Mode.RACE;
                    break;
                case 3:
                    ok = got == top.gunmu.party.core.Mode.RACE
                            || got == top.gunmu.party.core.Mode.SURVIVAL;
                    break;
                case 4:
                    ok = got == top.gunmu.party.core.Mode.RACE
                            || got == top.gunmu.party.core.Mode.SURVIVAL
                            || got == top.gunmu.party.core.Mode.ARENA;
                    break;
                default:
                    ok = got == top.gunmu.party.core.Mode.ARENA;
                    break;
            }
            if (!ok) {
                System.out.println("  [FAIL] 第 " + lv + " 关抽到 " + got.cn
                        + " —— 不在这一关的模式池里");
                bad++;
            }
        }
        if (dupRace > 0) {
            System.out.println("  [FAIL] 竞速图在一局内重复");
            bad++;
        }
        if (probe.stageMaps.size >= 3 && raceSeen.size() < 2) {
            System.out.println("  [!!] 跑了 " + probe.stageMaps.size
                    + " 个子图却只用过 " + raceSeen.size() + " 张图，选图逻辑可疑");
            System.out.println("  [FAIL] 选图多样性不足");
            bad++;
        }

        if (probe.enemiesMoving == 0) {
            System.out.println("  [FAIL] AI 完全没动");
            bad++;
        }
        System.out.println("  断言失败项 " + bad);
        return bad;
    }

    static {
        // 保持与 GameConfig 的引用关系，避免被优化掉
        if (GameConfig.LOG_COUNT != 30) {
            throw new IllegalStateException("LOG_COUNT 应为 30");
        }
    }
}
