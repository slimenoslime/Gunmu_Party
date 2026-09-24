package top.gunmu.party.tools;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Mode;
import top.gunmu.party.core.Roll;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapNav;
import top.gunmu.party.map.MapRegistry;

/**
 * 赛道相关查询自检：<b>生存图 / 竞技图上不许因为"没有 route"而崩</b>。
 *
 * <p>真实崩溃：进「涨水盆地」（生存图）时进程直接死掉 ——
 *
 * <pre>
 * Exception in thread "main" java.lang.NullPointerException:
 *     Cannot read field "totalLength" because "&lt;local3&gt;.route" is null
 *   at LevelScreen.logDiagnostics(LevelScreen.java:181)
 * </pre>
 *
 * <p>根因：<b>只有竞速图有折线路径 {@code route}</b>，生存/竞技图没有；
 * 而诊断行里写了一句裸的 {@code m.route.totalLength}。这类"按模式有条件成立"的解引用
 * 以前散在 {@code LevelScreen} 里，光看断言永远发现不了 —— Screen 要开窗口才能跑。
 *
 * <p>修法是把它们收进纯函数 {@link MapNav}，于是**能在这里对全部 10 张图逐一验证**。
 * 断言覆盖三种玩家状态（正常 / 出局 / 还没出生），因为这三种都会走到不同的分支。
 */
public final class MapNavTest {

    private MapNavTest() {
    }

    public static int run() {
        System.out.println("---- 赛道查询自检（生存/竞技图没有 route）----");
        int bad = 0;
        float[] scratch = new float[4];

        for (String id : allIds()) {
            MapDef m = MapRegistry.create(id);
            boolean race = m.mode == Mode.RACE;

            // ---- 1) hasRoute / routeLength 必须与模式严格一致 ----
            if (m.hasRoute() != race) {
                System.out.println("  [FAIL] " + pad(m.name) + " hasRoute=" + m.hasRoute()
                        + "，但模式是 " + m.mode);
                bad++;
            }
            if (race && m.routeLength() <= 0f) {
                System.out.println("  [FAIL] " + pad(m.name) + " 是竞速图却没有路径长度");
                bad++;
            }
            if (!race && m.routeLength() != 0f) {
                System.out.println("  [FAIL] " + pad(m.name) + " 非竞速图却报出路径长度 "
                        + m.routeLength());
                bad++;
            }

            // ---- 2) 相机朝向：三种玩家状态都不许抛 ----
            float yawAlive = tryYaw(m, aliveRoll(), scratch);
            float yawDead = tryYaw(m, deadRoll(), scratch);
            float yawNull = tryYaw(m, null, scratch);
            if (Float.isNaN(yawAlive) && Float.isNaN(yawDead) && Float.isNaN(yawNull)) {
                // 非竞速图：三次都该是 NaN
            } else if (!race) {
                System.out.println("  [FAIL] " + pad(m.name) + " 非竞速图却给出了赛道切线朝向");
                bad++;
            } else if (Float.isNaN(yawAlive) || Float.isNaN(yawDead) || Float.isNaN(yawNull)) {
                System.out.println("  [FAIL] " + pad(m.name) + " 竞速图有分支返回了 NaN"
                        + "（alive=" + yawAlive + " dead=" + yawDead + " null=" + yawNull + "）");
                bad++;
            }

            // ---- 3) 进度文案：非空、不抛、且不该给非竞速图报"进度 x/y" ----
            String t1 = tryText(m, aliveRoll(), 12.5f);
            String t2 = tryText(m, deadRoll(), 12.5f);
            String t3 = tryText(m, null, 0f);
            if (t1 == null || t2 == null || t3 == null) {
                System.out.println("  [FAIL] " + pad(m.name) + " 进度文案抛异常");
                bad++;
            } else {
                if (t1.isEmpty()) {
                    System.out.println("  [FAIL] " + pad(m.name) + " 进度文案是空串");
                    bad++;
                }
                if (!race && t1.contains("进度")) {
                    System.out.println("  [FAIL] " + pad(m.name) + " 非竞速图却报"
                            + "「进度 x/y」（route 为空，这个数字没有意义）");
                    bad++;
                }
                System.out.println("  [OK]   " + pad(m.name) + " " + m.mode.cn
                        + " → 相机 " + (Float.isNaN(yawAlive) ? "默认视角" : "跟赛道切线")
                        + "，文案「" + t1 + "」");
            }

            // ---- 4) 崩的就是这一整行：必须能原样跑出来 ----
            String diag = tryDiag(m, aliveRoll(), 12.5f);
            String diagDead = tryDiag(m, deadRoll(), 0f);
            String diagNull = tryDiag(m, null, 0f);
            if (diag == null || diagDead == null || diagNull == null) {
                System.out.println("  [FAIL] " + pad(m.name)
                        + " 诊断行抛异常 —— 这就是那个崩进程的 bug");
                bad++;
            } else if (!diag.contains(m.name)) {
                System.out.println("  [FAIL] " + pad(m.name) + " 诊断行里没有地图名");
                bad++;
            }
        }

        System.out.println(bad == 0 ? "  10 张图的赛道查询全部安全" : "  赛道查询自检失败 " + bad + " 项");
        return bad;
    }

    private static float tryYaw(MapDef m, Roll p, float[] scratch) {
        try {
            return MapNav.cameraYaw(m, p, 0f, 0f, scratch);
        } catch (Throwable t) {
            System.out.println("  [FAIL] " + m.id + " cameraYaw 抛了 " + t);
            return Float.NaN;
        }
    }

    /** 崩就崩在这一行上，所以要原样跑一遍。 */
    private static String tryDiag(MapDef m, Roll p, float clock) {
        try {
            return MapNav.diagLine(m, p, clock,
                    -12.3f, 7.5f, 40.1f, 0.12f, -0.77f, 0.63f, 5f, 6f, 33f);
        } catch (Throwable t) {
            System.out.println("  [FAIL] " + m.id + " diagLine 抛了 " + t);
            return null;
        }
    }

    private static String tryText(MapDef m, Roll p, float clock) {
        try {
            return MapNav.progressText(m, p, clock);
        } catch (Throwable t) {
            System.out.println("  [FAIL] " + m.id + " progressText 抛了 " + t);
            return null;
        }
    }

    private static Roll aliveRoll() {
        Roll r = new Roll(0, "测试", true);
        r.pos.set(0f, GameConfig.LOG_RADIUS, 0f);
        r.progress = 137f;
        return r;
    }

    private static Roll deadRoll() {
        Roll r = aliveRoll();
        r.eliminated = true;
        return r;
    }

    private static com.badlogic.gdx.utils.Array<String> allIds() {
        com.badlogic.gdx.utils.Array<String> ids = new com.badlogic.gdx.utils.Array<>();
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

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 10) {
            b.append(' ');
        }
        return b.toString();
    }
}
