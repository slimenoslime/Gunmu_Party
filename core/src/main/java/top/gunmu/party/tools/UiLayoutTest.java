package top.gunmu.party.tools;

import java.util.Locale;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.Array;

import top.gunmu.party.items.ItemType;
import top.gunmu.party.map.MapRegistry;
import top.gunmu.party.render.HudLayout;
import top.gunmu.party.render.UiLayout;

/**
 * HUD 排版自检：**对着真实屏幕尺寸断言"每一块都放得下、互不重叠、字不越界"**。
 *
 * <p>存在的理由是真的踩过：手表（396×396）上 HUD 全叠在一起 ——
 * 左上 480 宽的面板与右上 236 宽的面板在 466 宽的虚拟屏上直接摞起来、
 * 右下状态行被屏幕右缘切掉、五个直径 116 的按钮压住半张地图。
 * 而**当时的自检全绿**：那些断言只跑逻辑，从不问"这块东西放得下吗"。
 *
 * <p>这一份检查分四类，全部不需要 GL / 真机：
 * <ol>
 *   <li>**不越界**：每个分块都必须在屏幕内，且留够边距；</li>
 *   <li>**不重叠**：按钮之间、按钮与状态面板 / 道具卡 / 顶栏之间不许相交
 *       （手表上按键压住地图就是这条没守住）；</li>
 *   <li>**点得中**：按钮的物理直径不得小于 {@link #MIN_TAP_PX}；</li>
 *   <li>**字放得下**：所有会变长的文案（关卡名 / 地图名 / 模式 / 名额 / 状态行）
 *       在最长的取值下都必须塞得进它那块矩形（用 {@link UiLayout#textWidth} 保守估算）。</li>
 * </ol>
 *
 * <p>另外把每种屏幕的排版画成一张示意图（{@code build/ui-mock/ui-<宽>x<高>.png}）：
 * 文字用等宽小方块占位，位置是真实排版位置。断言只能证明"没越界"，
 * 这张图才能让人一眼看出"手表上到底长什么样"。
 */
public final class UiLayoutTest {

    /** 要验的屏幕（真实像素）—— 手表排在最前，这个 bug 就是手表报的。 */
    private static final int[][] SCREENS = {
            {396, 396},    // 手表（用户报错的那台）
            {450, 450},    // 手表（国产表常见）
            {466, 466},    // 手表
            {320, 320},    // 极小表（支持的极限，见 MIN_SUPPORTED_SHORT）
            {800, 480},    // 老安卓 / 小窗
            {1280, 720},   // 桌面窗口
            {1920, 1080},  // 桌面
            {2340, 1080},  // 手机横屏
            {1080, 2340},  // 手机竖屏（本作锁横屏，但仍要排得开）
    };

    /** 虚拟短边低于这个值就不再保证"道具卡放得下"（更小的表盘排不下完整属性，只能退让）。 */
    public static final float MIN_SUPPORTED_SHORT = 360f;

    /** 按钮的最小物理直径（低于这个数手指点不中）。 */
    public static final float MIN_TAP_PX = 28f;

    /** 行高：与真机同量级（Fonts 的行高 ≈ 基准字号 × 1.25）。 */
    private static final float LINE_SMALL = 17f * 1.25f;
    private static final float LINE_MEDIUM = 23f * 1.25f;
    private static final float LINE_LARGE = 31f * 1.25f;

    private static int failures;

    private UiLayoutTest() {
    }

    public static int run() {
        failures = 0;
        System.out.println("---- HUD 排版自检（锚点系统 / 手表档）----");
        for (int[] s : SCREENS) {
            checkScreen(s[0], s[1]);
        }
        writeMocks();
        System.out.println(failures == 0
                ? "  HUD 排版自检全部通过（%d 种屏幕）".formatted(SCREENS.length)
                : "  HUD 排版自检失败 %d 项".formatted(failures));
        return failures;
    }

    // ================================================================== 逐屏检查

    private static void checkScreen(int w, int h) {
        HudLayout l = HudLayout.compute(w, h, LINE_SMALL, LINE_MEDIUM, LINE_LARGE);
        String tag = "%dx%d(%s)".formatted(w, h, l.compact() ? "紧凑" : "标准");
        float m = l.margin;
        UiLayout.Rect scr = new UiLayout.Rect().set(0f, 0f, l.width, l.height);

        // ⓪ 小屏必须切紧凑档 —— 这条是本次"手表 UI 全叠在一起"的直接回归守卫：
        //    表盘 396/450/466 若被判成标准档，标准排版至少要 800 虚拟宽，必然叠上。
        if ((w <= 500 && h <= 500) && !l.compact()) {
            fail(tag + " 小方屏没有切到紧凑档（虚拟 " + fmt(l.width) + "×" + fmt(l.height)
                    + "，短边阈值 " + HudLayout.COMPACT_MAX_SHORT + "）");
        }

        // ① 不越界：每块都在屏幕内（留 ≥ 边距 − 0.5 的容差）
        for (Object[] pair : blocks(l)) {
            String name = (String) pair[0];
            UiLayout.Rect r = (UiLayout.Rect) pair[1];
            if (r.w <= 0f && r.h <= 0f) {
                continue;                       // 该档不用这块（例如紧凑档没有 topLeft）
            }
            if (!inside(r, scr, 0.5f)) {
                fail(tag + " " + name + " 越出屏幕: " + r + " 屏幕 " + scr);
            }
            if (r.w > 0f && (r.x < m - 0.5f || r.y < m - 0.5f
                    || r.right() > scr.right() - m + 0.5f
                    || r.top() > scr.top() - m + 0.5f)) {
                fail(tag + " " + name + " 没留够边距（应 ≥ " + fmt(m) + "）: " + r);
            }
        }

        // ② 按钮：位置正确、不互相重叠、不压别的块
        UiLayout.Rect cluster = l.cluster;
        if (!inside(cluster, scr, 0.5f)) {
            fail(tag + " 按钮簇越出屏幕: " + cluster);
        }
        for (int i = 0; i < HudLayout.BTN_COUNT; i++) {
            UiLayout.Rect b = l.btnBox[i];
            if (!inside(b, cluster, 0.5f)) {
                fail(tag + " 按钮 " + btnName(i) + " 不在键列里: " + b + " 键列 " + cluster);
            }
            for (int j = i + 1; j < HudLayout.BTN_COUNT; j++) {
                if (b.overlaps(l.btnBox[j], 0f)) {
                    fail(tag + " 按钮 " + btnName(i) + " 与 " + btnName(j)
                            + " 重叠（会点错键）: " + b + " / " + l.btnBox[j]);
                }
            }
            if (b.overlaps(l.status, 1f) || b.overlaps(l.card, 1f)
                    || b.overlaps(l.topStrip, 1f) || b.overlaps(l.topLeft, 1f)
                    || b.overlaps(l.topRight, 1f)) {
                fail(tag + " 按钮 " + btnName(i) + " 压在别的面板上: " + b);
            }
        }
        // ③ 点得中
        float tapPx = 2f * l.btnRadius * l.uiScale;
        if (tapPx < MIN_TAP_PX) {
            fail(tag + " 按钮物理直径只有 " + fmt(tapPx) + " px（下限 " + MIN_TAP_PX + "）");
        }
        // 摇杆区不许和键列抢
        if (l.stickZone.overlaps(cluster, 1f)) {
            fail(tag + " 摇杆区与键列重叠: " + l.stickZone + " / " + cluster);
        }
        if (l.stickRadius * l.uiScale < 12f) {
            fail(tag + " 摇杆半径太小: " + fmt(l.stickRadius * l.uiScale) + " px");
        }

        // ④ 文案放得下（最长的取值）
        checkTexts(l, tag);

        // ⑤ 道具卡：每个道具都要放得下、且不压键列
        checkCards(l, tag);
    }

    /** 会变长的文案 —— 全部按"最长可能出现"的取值检查。 */
    private static void checkTexts(HudLayout l, String tag) {
        String[] titles = {
                "第 1 关 · 热身赛道  (1/2)", "第 2 关 · 山坡冲刺", "第 3 关 · 竞速",
                "第 4 关 · 竞技", "第 5 关 · 决战  (2/2)",
        };
        String[] quotas = {"到线 25 / 25", "死亡 5 / 5", "只剩一人即胜", "限时人头赛"};
        String[] statusRows = {
                "100 / 100   名次 30 / 30", "速度 99.9 m/s   遥遥领先", "道具 无",
                "速度 99.9 m/s   道具 无   狂暴",
        };
        if (l.compact()) {
            // 顶栏第一行：左 关卡 + 右 倒计时
            float leftW = l.topStrip.w - 2f * l.pad - 90f;
            for (String t : titles) {
                float need = UiLayout.textWidth(23f, t);
                if (need > leftW) {
                    fail(tag + " 顶栏标题放不下：「" + t + "」需要 " + fmt(need)
                            + "，只有 " + fmt(leftW));
                }
            }
            float timeW = UiLayout.textWidth(23f, "12:34");
            if (UiLayout.textWidth(23f, "第 1 关 · 热身赛道  (1/2)") + timeW + l.pad > l.topStrip.w) {
                fail(tag + " 顶栏第一行：标题 + 倒计时 会撞上");
            }
            float quotaW = l.topStrip.w * 0.52f;
            for (String q : quotas) {
                if (UiLayout.textWidth(17f, q) > quotaW) {
                    fail(tag + " 顶栏名额放不下：「" + q + "」需要 "
                            + fmt(UiLayout.textWidth(17f, q)) + "，只有 " + fmt(quotaW));
                }
            }
            for (String r : statusRows) {
                if (UiLayout.textWidth(17f, r) > l.status.w - 2f * l.pad) {
                    fail(tag + " 状态行放不下：「" + r + "」需要 "
                            + fmt(UiLayout.textWidth(17f, r))
                            + "，只有 " + fmt(l.status.w - 2f * l.pad));
                }
            }
            return;
        }
        // 标准档
        for (String t : titles) {
            if (UiLayout.textWidth(23f, t) > l.topLeft.w - 2f * l.pad) {
                fail(tag + " 左上标题放不下：「" + t + "」");
            }
        }
        for (String q : quotas) {
            if (UiLayout.textWidth(17f, q) > l.topRight.w - 2f * l.pad) {
                fail(tag + " 右上名额放不下：「" + q + "」");
            }
        }
        // 左上第二行 = 地图 + 模式 + 人数（地图名来自注册表，是真实取值）
        for (String mapName : mapNames()) {
            for (String mode : new String[]{"竞速", "生存", "竞技"}) {
                String line = "地图 " + mapName + " · 模式 " + mode + " · 场上 30 根";
                if (UiLayout.textWidth(17f, line) > l.topLeft.w - 2f * l.pad) {
                    fail(tag + " 左上信息行放不下：「" + line + "」需要 "
                            + fmt(UiLayout.textWidth(17f, line)));
                }
            }
        }
        // 左下状态行（旧版就是在这一行被右缘切掉的：从 x=350 起排到屏幕外）
        // 标准档的这行字本来就允许伸出面板（面板只是 HP 那一块的底衬），
        // 但**不许伸出屏幕、也不许钻进右下键列**。
        String statusLine = "名次 30 / 30   速度 99.9 m/s   加速冷却 15.0s   狂暴";
        float room = clusterLeftRoom(l) - 350f;
        if (UiLayout.textWidth(17f, statusLine) > room) {
            fail(tag + " 左下状态行放不下：「" + statusLine + "」需要 "
                    + fmt(UiLayout.textWidth(17f, statusLine)) + "，只有 " + fmt(room));
        }
    }

    /** 标准档里"从 x=350 起那行字"能够用到的右边界。 */
    private static float clusterLeftRoom(HudLayout l) {
        return l.cluster.x - l.pad;
    }

    /** 每个道具都在这个屏幕上量一遍卡（与 Hud.prepareItemCard 走同一条路径）。 */
    private static void checkCards(HudLayout l, String tag) {
        boolean mustFit = Math.min(l.width, l.height) >= MIN_SUPPORTED_SHORT;
        for (ItemType it : ItemType.ALL) {
            String attrs = "稀有度 " + it.rarity.cn + "    作用对象 前方最近的敌人"
                    + "    持续 10.0 秒    负面状态";
            HudLayout.CardPlan plan = l.measureCard(approxWrap(), attrs, it.desc,
                    "出处 " + it.meme, LINE_SMALL);
            if (!plan.fits && mustFit) {
                fail(tag + " 道具卡放不下：「" + it.cn + "」（虚拟短边 "
                        + fmt(Math.min(l.width, l.height)) + " ≥ " + MIN_SUPPORTED_SHORT + "）");
            }
            if (l.card.overlaps(l.cluster, 1f)) {
                fail(tag + " 道具卡压住键列：「" + it.cn + "」卡 " + l.card + " 键列 " + l.cluster);
            }
            if (l.compact() && l.card.top() > l.topStrip.y + 0.5f) {
                fail(tag + " 道具卡压住顶栏：「" + it.cn + "」卡顶 " + fmt(l.card.top())
                        + " > 顶栏底 " + fmt(l.topStrip.y));
            }
            if (!l.card.inside(new UiLayout.Rect().set(0f, 0f, l.width, l.height), 0.5f)) {
                fail(tag + " 道具卡越出屏幕：「" + it.cn + "」" + l.card);
            }
        }
    }

    // ================================================================== 示意图

    /** 把每种屏幕的排版画成 PNG（文字用方块占位，位置是真实排版位置）。 */
    private static void writeMocks() {
        String dir = "build/ui-mock";
        try {
            new FileHandle(dir).mkdirs();
        } catch (Throwable t) {
            System.out.println("  （跳过示意图：建不出目录 " + dir + "）");
            return;
        }
        int ok = 0;
        for (int[] s : SCREENS) {
            if (drawMock(s[0], s[1], dir)) {
                ok++;
            }
        }
        System.out.println("  排版示意图 " + ok + " 张 -> " + dir + "/");
    }

    private static boolean drawMock(int w, int h, String dir) {
        Pixmap pm = null;
        try {
            pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
            HudLayout l = HudLayout.compute(w, h, LINE_SMALL, LINE_MEDIUM, LINE_LARGE);
            // 量出最长道具的卡，按它的高度画（图上要看到最坏情况）
            HudLayout.CardPlan plan = l.measureCard(approxWrap(),
                    "稀有度 传说    作用对象 前方最近的敌人    持续 10.0 秒",
                    longestDesc(), "出处 " + longestMeme(), LINE_SMALL);

            pm.setColor(Color.rgba8888(0.10f, 0.12f, 0.15f, 1f));
            pm.fill();
            // 安全区提示
            pm.setColor(Color.rgba8888(0.18f, 0.22f, 0.28f, 1f));
            pm.drawRectangle(0, 0, w, h);

            UiLayout.Rect scr = new UiLayout.Rect().set(0f, 0f, l.width, l.height);
            float k = l.uiScale;
            // 面板
            for (Object[] pair : blocks(l)) {
                UiLayout.Rect r = (UiLayout.Rect) pair[1];
                if (r.w <= 0f && r.h <= 0f) {
                    continue;
                }
                fill(pm, r, scr, k, Color.rgba8888(0.07f, 0.09f, 0.12f, 0.92f));
                rect(pm, r, scr, k, Color.rgba8888(0.25f, 0.35f, 0.45f, 1f));
            }
            // HP 条 / 效果条
            fill(pm, l.hpTrack, scr, k, Color.rgba8888(0.25f, 0.72f, 0.31f, 1f));
            fill(pm, l.effTrack, scr, k, Color.rgba8888(1f, 0.72f, 0.01f, 0.85f));
            // 按钮（圆）+ 标签占位
            for (int i = 0; i < HudLayout.BTN_COUNT; i++) {
                UiLayout.Rect b = l.btnBox[i];
                float cx = px(b.cx(), scr, k, true);
                float cy = px(b.cy(), scr, k, false);
                pm.setColor(Color.rgba8888(0.16f, 0.20f, 0.26f, 0.95f));
                pm.fillCircle((int) cx, (int) cy, (int) (l.btnRadius * k));
                pm.setColor(Color.rgba8888(0.75f, 0.82f, 0.90f, 1f));
                pm.drawCircle((int) cx, (int) cy, (int) (l.btnRadius * k));
            }
            // 摇杆区
            rect(pm, l.stickZone, scr, k, Color.rgba8888(0.35f, 0.45f, 0.55f, 1f));

            // 文字占位（方块宽度 = 真实字宽）
            Color tx = Color.rgba8888(0.90f, 0.94f, 0.98f, 1f);
            Color dim = Color.rgba8888(0.55f, 0.62f, 0.70f, 1f);
            if (l.compact()) {
                boxText(pm, scr, k, l.topStrip.x + l.pad, l.stripRow1Top, 23f,
                        "第 1 关 · 热身赛道", tx);
                String time = "12:34";
                boxText(pm, scr, k, l.topStrip.right() - l.pad - UiLayout.textWidth(23f, time),
                        l.stripRow1Top, 23f, time, tx);
                boxText(pm, scr, k, l.topStrip.x + l.pad, l.stripRow2Top, 17f,
                        "竞速 · 场上 30 根", dim);
                String q = "到线 25 / 25";
                boxText(pm, scr, k, l.topStrip.right() - l.pad - UiLayout.textWidth(17f, q),
                        l.stripRow2Top, 17f, q, dim);
                boxText(pm, scr, k, l.status.x + l.pad, l.status.top() - l.pad, 17f,
                        "100 / 100   名次 30 / 30", tx);
                boxText(pm, scr, k, l.status.x + l.pad, l.status.top() - l.pad - LINE_SMALL,
                        17f, "速度 12.3 m/s   道具 无", dim);
            } else {
                boxText(pm, scr, k, l.topLeft.x + l.pad, l.topLeft.top() - 22f, 23f,
                        "第 1 关 · 热身赛道  (1/2)", tx);
                boxText(pm, scr, k, l.topLeft.x + l.pad, l.topLeft.top() - 58f, 17f,
                        "地图 崩落长阶 · 模式 竞速 · 场上 30 根", dim);
                boxText(pm, scr, k, l.topRight.x + l.pad, l.topRight.top() - 18f, 31f, "12:34", tx);
                boxText(pm, scr, k, l.topRight.x + l.pad, l.topRight.top() - 58f, 17f,
                        "到线 25 / 25", dim);
                boxText(pm, scr, k, l.status.x + l.pad, l.status.y + 108f, 17f, "100 / 100", tx);
                boxText(pm, scr, k, 350f, l.status.y + 108f, 17f,
                        "名次 30 / 30   速度 12.3 m/s   加速就绪", dim);
                boxText(pm, scr, k, l.status.x + l.pad, l.status.y + 50f, 17f, "道具 无", dim);
            }
            // 道具卡内容
            if (l.card.h > 0f) {
                fill(pm, l.card, scr, k, Color.rgba8888(0.09f, 0.11f, 0.15f, 0.95f));
                rect(pm, l.card, scr, k, Color.rgba8888(0.45f, 0.35f, 0.20f, 1f));
                float y = l.card.top() - l.pad;
                boxText(pm, scr, k, l.card.x + l.pad, y, 23f, "道具 " + longestName(), tx);
                y -= 23f * 1.25f + 6f;
                for (String line : wrap("稀有度 传说  作用对象 前方最近的敌人", l.card.w - 2f * l.pad)) {
                    boxText(pm, scr, k, l.card.x + l.pad, y, 17f, line, dim);
                    y -= LINE_SMALL;
                }
                y -= 8f;
                for (String line : wrap(longestDesc(), l.card.w - 2f * l.pad)) {
                    boxText(pm, scr, k, l.card.x + l.pad, y, 17f, line, tx);
                    y -= LINE_SMALL;
                }
                if (plan.memeLines > 0) {
                    y -= 8f;
                    for (String line : wrap("出处 " + longestMeme(), l.card.w - 2f * l.pad)) {
                        boxText(pm, scr, k, l.card.x + l.pad, y, 17f, line, dim);
                        y -= LINE_SMALL;
                    }
                }
            }
            String out = dir + "/ui-" + w + "x" + h + ".png";
            PixmapIO.writePNG(new FileHandle(out), pm);
            return true;
        } catch (Throwable t) {
            System.out.println("  （跳过 " + w + "x" + h + " 示意图：" + t + "）");
            return false;
        } finally {
            if (pm != null) {
                pm.dispose();
            }
        }
    }

    private static void boxText(Pixmap pm, UiLayout.Rect scr, float k,
                               float x, float yTop, float fontSize, String text, Color c) {
        pm.setColor(c);
        float gx = x;
        float gh = fontSize * 0.72f;
        float gy = px(yTop, scr, k, false) - gh * k;      // 文字顶边 → 占位块
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            float cw = UiLayout.isWide(cp) ? fontSize : fontSize * 0.55f;
            pm.fillRectangle((int) px(gx, scr, k, true), (int) gy,
                    Math.max(1, (int) (cw * k) - 1), Math.max(1, (int) (gh * k)));
            gx += cw;
            i += Character.charCount(cp);
        }
    }

    // ================================================================== 小工具

    private static void fill(Pixmap pm, UiLayout.Rect r, UiLayout.Rect scr, float k, Color c) {
        if (r.w <= 0f || r.h <= 0f) {
            return;
        }
        pm.setColor(c);
        int x = (int) px(r.x, scr, k, true);
        int y = (int) px(r.top(), scr, k, false);
        pm.fillRectangle(x, y, Math.max(1, (int) (r.w * k)), Math.max(1, (int) (r.h * k)));
    }

    private static void rect(Pixmap pm, UiLayout.Rect r, UiLayout.Rect scr, float k, Color c) {
        if (r.w <= 0f || r.h <= 0f) {
            return;
        }
        pm.setColor(c);
        int x = (int) px(r.x, scr, k, true);
        int y = (int) px(r.top(), scr, k, false);
        pm.drawRectangle(x, y, Math.max(1, (int) (r.w * k)), Math.max(1, (int) (r.h * k)));
    }

    /** 虚拟坐标 → 像素（y 轴翻转：Pixmap 原点在左上）。 */
    private static float px(float v, UiLayout.Rect scr, float k, boolean horizontal) {
        return horizontal ? v * k : (scr.h - v) * k;
    }

    private static Array<Object[]> blocks(HudLayout l) {
        Array<Object[]> out = new Array<>();
        out.add(new Object[]{"左上信息面板", l.topLeft});
        out.add(new Object[]{"右上倒计时面板", l.topRight});
        out.add(new Object[]{"顶栏", l.topStrip});
        out.add(new Object[]{"状态面板", l.status});
        out.add(new Object[]{"键列", l.cluster});
        out.add(new Object[]{"摇杆区", l.stickZone});
        return out;
    }

    private static boolean inside(UiLayout.Rect r, UiLayout.Rect box, float tol) {
        return r.x >= box.x - tol && r.y >= box.y - tol
                && r.right() <= box.right() + tol && r.top() <= box.top() + tol;
    }

    private static String btnName(int i) {
        switch (i) {
            case HudLayout.BTN_ATTACK:
                return "攻击";
            case HudLayout.BTN_ULT:
                return "决战";
            case HudLayout.BTN_JUMP:
                return "跳";
            case HudLayout.BTN_DASH:
                return "加速";
            default:
                return "道具";
        }
    }

    /** 全部地图名（HUD 左上那一行显示的就是它）。 */
    private static String[] mapNames() {
        String[] ids = new String[MapRegistry.RACE_IDS.length + MapRegistry.SURVIVAL_IDS.length
                + MapRegistry.ARENA_IDS.length];
        int n = 0;
        for (String s : MapRegistry.RACE_IDS) {
            ids[n++] = s;
        }
        for (String s : MapRegistry.SURVIVAL_IDS) {
            ids[n++] = s;
        }
        for (String s : MapRegistry.ARENA_IDS) {
            ids[n++] = s;
        }
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = MapRegistry.create(ids[i]).name;
        }
        return names;
    }

    private static String longestName() {
        String best = "";
        for (ItemType it : ItemType.ALL) {
            if (it.cn.length() > best.length()) {
                best = it.cn;
            }
        }
        return best;
    }

    private static String longestDesc() {
        String best = "";
        for (ItemType it : ItemType.ALL) {
            if (it.desc.length() > best.length()) {
                best = it.desc;
            }
        }
        return best;
    }

    private static String longestMeme() {
        String best = "";
        for (ItemType it : ItemType.ALL) {
            if (it.meme.length() > best.length()) {
                best = it.meme;
            }
        }
        return best;
    }

    /** 自检里的折行：与 {@link UiLayout#textWidth} 同口径的贪心逐字符折行。 */
    private static Array<String> wrap(String text, float maxWidth) {
        Array<String> out = new Array<>();
        StringBuilder line = new StringBuilder();
        float acc = 0f;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            float cw = UiLayout.isWide(cp) ? LINE_SMALL : LINE_SMALL * 0.62f;
            String ch = new String(Character.toChars(cp));
            if (acc + cw > maxWidth && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                acc = 0f;
            }
            line.append(ch);
            acc += cw;
            i += Character.charCount(cp);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private static HudLayout.Wrap approxWrap() {
        return (text, maxWidth, out) -> {
            Array<String> lines = wrap(text, maxWidth);
            if (out != null) {
                out.addAll(lines);
            }
            return lines.size;
        };
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static void fail(String msg) {
        failures++;
        System.out.println("  [!!] " + msg);
    }

    /** 直接跑（不进整包冒烟时用）。 */
    public static void main(String[] args) {
        if (Gdx.files == null) {
            // 无窗口模式：Pixmap 走 g2d 原生库，不需要 GL，但要显式加载一次原生库
            try {
                com.badlogic.gdx.utils.GdxNativesLoader.load();
            } catch (Throwable t) {
                System.out.println("  （原生库未加载，示意图会跳过：" + t + "）");
            }
        }
        System.exit(run());
    }
}
