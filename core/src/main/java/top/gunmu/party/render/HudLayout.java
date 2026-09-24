package top.gunmu.party.render;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;

/**
 * HUD 的**分块布局**：把"哪一块放哪儿、多大"从绘制代码里拿出来，变成可断言的数据。
 *
 * <p>两个档：
 *
 * <table border="1">
 *   <caption>档位</caption>
 *   <tr><th>档</th><th>判定</th><th>长什么样</th></tr>
 *   <tr>
 *     <td>{@link Profile#STANDARD}</td>
 *     <td>虚拟短边 ≥ {@link #COMPACT_MAX_SHORT}</td>
 *     <td><b>沿用旧的视觉基线</b>（1440×810 上看起来与 a0.0.2 完全一样）：
 *         左上信息面板、右上倒计时、左下状态面板、右下 2×3 键列、道具卡在左下往上长</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Profile#COMPACT}</td>
 *     <td>虚拟短边 &lt; 该阈值（手表 / 极窄窗）</td>
 *     <td>顶部整条（关卡 + 倒计时 / 屏障 + 名额，下面压一条全宽 HP）+ 左下两行状态 +
 *         右下 2×3 紧凑键列 + 道具卡自适应（放不下就收窄、再放不下就省掉梗出处行）</td>
 *   </tr>
 * </table>
 *
 * <p>为什么需要 COMPACT：旧 HUD 是**对着 810 高的参考屏硬编码**的。
 * 表盘 396×396 时 {@code uiScale} 被钳到下限 0.85，虚拟屏只剩 466×466，
 * 于是「480 + 236 = 716 &gt; 466」两块面板直接叠上、状态行被右边缘切掉、
 * 五个直径 116 的按钮压住半张地图。**不是缩放没做好，是排版压根没有第二档。**
 *
 * <p>本类**纯几何**：不碰 GL，不吃 {@code BitmapFont}（只吃行高这些数字），
 * 所以能在 {@code top.gunmu.party.tools.UiLayoutTest} 里对着一堆屏幕尺寸跑断言。
 */
public final class HudLayout {

    /** 屏幕档位。 */
    public enum Profile {
        /** 手机 / 桌面：沿用旧基线。 */
        STANDARD,
        /** 手表 / 极窄：顶部整条 + 紧凑键列。 */
        COMPACT
    }

    /** 虚拟短边小于它就切紧凑档 —— 810（16:9 基准）与 1080+（手机）都在标准档。 */
    public static final float COMPACT_MAX_SHORT = GameConfig.UI_COMPACT_MAX_SHORT;

    // ---------------------------------------------------------------- 按钮槽位
    public static final int BTN_ATTACK = 0;
    public static final int BTN_ULT = 1;
    public static final int BTN_JUMP = 2;
    public static final int BTN_DASH = 3;
    public static final int BTN_ITEM = 4;
    public static final int BTN_COUNT = 5;

    // ---------------------------------------------------------------- 结果
    public Profile profile = Profile.STANDARD;
    public float uiScale = 1f;
    public float width;
    public float height;
    public float margin;
    public float pad;

    /** 标准档：左上信息面板。紧凑档：{@link #topStrip} 的第一行用它算位置（本矩形为 0）。 */
    public final UiLayout.Rect topLeft = new UiLayout.Rect();
    /** 标准档：右上倒计时面板。紧凑档：{@link #topStrip} 的第二行右端。 */
    public final UiLayout.Rect topRight = new UiLayout.Rect();
    /** 紧凑档：整条顶栏（两行 + 底部 HP 条）。标准档为空矩形。 */
    public final UiLayout.Rect topStrip = new UiLayout.Rect();
    /** 状态区（HP 条 + 状态行 + 道具槽）。 */
    public final UiLayout.Rect status = new UiLayout.Rect();
    /** HP 条与道具效果条（都在状态区里）。 */
    public final UiLayout.Rect hpTrack = new UiLayout.Rect();
    public final UiLayout.Rect effTrack = new UiLayout.Rect();
    /** 按钮簇（2 列 × 3 行）。 */
    public final UiLayout.Rect cluster = new UiLayout.Rect();
    /** 五个按钮的中心（虚拟坐标）。 */
    public final UiLayout.Rect[] btnBox = new UiLayout.Rect[HudLayout.BTN_COUNT];
    public float btnRadius = 58f;
    /** 摇杆。 */
    public float stickRadius = 115f;
    public float knobRadius = 46f;
    /** 摇杆的接管区域（左半屏，避开按钮簇那一列）。 */
    public final UiLayout.Rect stickZone = new UiLayout.Rect();
    /** 道具卡（底边固定，向上长；高度由 {@link #setCardHeight} 定）。 */
    public final UiLayout.Rect card = new UiLayout.Rect();
    /** 击杀播报（紧凑档只在没有道具卡时才画）。 */
    public final UiLayout.Rect feed = new UiLayout.Rect();
    /** 大字横幅的中心点区域。 */
    public final UiLayout.Rect banner = new UiLayout.Rect();
    /** 起点屏障倒计时条。 */
    public final UiLayout.Rect barrier = new UiLayout.Rect();
    /** 自动演示横幅。 */
    public final UiLayout.Rect autoplay = new UiLayout.Rect();

    /** 行高（虚拟像素，由 Fonts 实测传入）。 */
    public float lineSmall = 21f;
    public float lineMedium = 29f;
    public float lineLarge = 39f;

    /** 紧凑档顶栏两行文字的**顶边 y**（虚拟坐标，文字向下排）。 */
    public float stripRow1Top;
    public float stripRow2Top;

    /** 道具卡的可用高度上限（超出说明这块放不下了，自检会红）。 */
    public float cardBandHeight;

    private HudLayout() {
        for (int i = 0; i < btnBox.length; i++) {
            btnBox[i] = new UiLayout.Rect();
        }
    }

    public boolean compact() {
        return profile == Profile.COMPACT;
    }

    // ================================================================== 缩放与档位

    /**
     * HUD 的缩放系数 —— **唯一口径**（Hud 与 UiLayoutTest 共用，免得自检的尺子和真机不一样）。
     *
     * <p>沿用旧公式：以屏幕高度对 810 求比，再用宽度兜一个上限（超宽屏别把界面拉太大）。
     * 下限 0.85 是刻意的：再往下字号就低于可读线了 ——
     * 表盘上的正确做法是**换排版**（COMPACT 档），不是把字缩小。
     */
    public static float uiScaleFor(int realW, int realH) {
        float scale = realH / GameConfig.UI_REFERENCE_HEIGHT;
        float byWidth = realW / GameConfig.UI_REFERENCE_WIDTH;
        scale = Math.min(scale, Math.max(1f, byWidth));
        return MathUtils.clamp(scale, GameConfig.UI_SCALE_MIN, GameConfig.UI_SCALE_MAX);
    }

    /** 虚拟尺寸下的短边决定档位。 */
    public static Profile profileFor(float virtualW, float virtualH) {
        return Math.min(virtualW, virtualH) < COMPACT_MAX_SHORT
                ? Profile.COMPACT : Profile.STANDARD;
    }

    /**
     * 算出一整套 HUD 布局。
     *
     * @param realW / realH 真实屏幕像素
     * @param lineSmall / lineMedium / lineLarge 三种字号的行高（虚拟像素，来自 {@code Fonts}）
     */
    public static HudLayout compute(int realW, int realH,
                                    float lineSmall, float lineMedium, float lineLarge) {
        HudLayout l = new HudLayout();
        l.uiScale = uiScaleFor(realW, realH);
        l.width = realW / l.uiScale;
        l.height = realH / l.uiScale;
        l.lineSmall = lineSmall > 0f ? lineSmall : l.lineSmall;
        l.lineMedium = lineMedium > 0f ? lineMedium : l.lineMedium;
        l.lineLarge = lineLarge > 0f ? lineLarge : l.lineLarge;

        UiLayout ui = new UiLayout(l.width, l.height);
        l.margin = ui.margin;
        l.pad = ui.pad;
        l.profile = profileFor(l.width, l.height);
        if (l.profile == Profile.COMPACT) {
            l.layoutCompact(ui);
        } else {
            l.layoutStandard(ui);
        }
        return l;
    }

    // ---------------------------------------------------------------- 标准档（旧基线）

    /**
     * 标准档 = **a0.0.2 的视觉基线原样保留**。
     *
     * <p>那套在手机（1080p 横屏）和桌面上是对的，这次是为了手表才动的排版，
     * 没有理由顺手把它们挪一遍 —— 挪了就得重新验证一遍所有平台的观感。
     * 唯一的变化是：这里的每个数字都从"屏幕左上角往下数"改成了**按边锚定**，
     * 所以虚拟宽度一旦不足（旧代码正是死在这里），锚点会把它夹住而不是让两块面板叠上。
     */
    private void layoutStandard(UiLayout ui) {
        float m = ui.margin;
        ui.place(topLeft, UiLayout.Anchor.TOP_LEFT, 480f, 94f, m, m);
        ui.place(topRight, UiLayout.Anchor.TOP_RIGHT, 236f, 94f, m, m);

        // 左下状态面板：底边离屏幕 34（旧值 CARD_BOTTOM = 34 + 100 + 8 = 142 依赖它）
        ui.place(status, UiLayout.Anchor.BOTTOM_LEFT, 468f, 100f, m, 34f);
        status.inner(hpTrack, 0f);
        hpTrack.set(status.x + ui.pad, status.y + 42f, 300f, 20f);
        effTrack.set(status.x + ui.pad, status.y + 26f, 300f, 8f);

        // 右下 2×3 键列：右列中心离右边 108、行距 128（旧值），折算成"簇 + 半径"
        btnRadius = 58f;
        float gapX = 8f;
        float gapY = 12f;
        float cw = 4f * btnRadius + gapX;
        float ch = 6f * btnRadius + 2f * gapY;
        float rowGap = 2f * btnRadius + gapY;
        float colGap = 2f * btnRadius + gapX;
        float rightCol = width - 108f;
        float leftCol = width - 232f;
        float topRow = 424f;
        cluster.set(leftCol - btnRadius, 168f - btnRadius, cw, ch);
        btnAt(BTN_ATTACK, rightCol, topRow);
        btnAt(BTN_ULT, rightCol, topRow - rowGap);
        btnAt(BTN_JUMP, rightCol, topRow - 2f * rowGap);
        btnAt(BTN_DASH, leftCol, topRow - rowGap);
        btnAt(BTN_ITEM, leftCol, topRow - 2f * rowGap);
        cluster.set(btnBox[BTN_ITEM].x - btnRadius, btnBox[BTN_JUMP].y - btnRadius, cw, ch);

        stickRadius = 115f;
        knobRadius = 46f;
        stickZone.set(0f, 0f, width * 0.55f, height);

        // 道具卡：底边贴状态面板上沿 + 8，右边不许压到键列（旧值是 keyCol 左侧留一个半径）
        float cardW = MathUtils.clamp(cluster.x - m - btnRadius, 320f, 760f);
        card.set(m, status.top() + 8f, cardW, 0f);

        // 击杀播报：左上信息面板下面往下排
        feed.set(m + 12f, topLeft.y - 48f - 3f * lineSmall, 480f, 4f * lineSmall);
        banner.set(0f, height * 0.62f, width, lineLarge * 1.6f);
        barrier.set(0f, height - 44f, width, 34f);
        autoplay.set(0f, height - 46f, width, 46f);
    }

    // ---------------------------------------------------------------- 紧凑档（手表）

    /**
     * 紧凑档：一切尺寸**按屏幕短边推**，不写死像素。
     *
     * <p>结构（表盘 466×466 为例）：
     * <pre>
     * ┌────────────────────────┐  ← 顶栏：左 关卡 / 右 倒计时
     * │ 第1关 · 热身赛道   4:15 │     第二行：左 屏障倒计时（或 模式·场上）/ 右 名额
     * │ 竞速 · 场上 30 根 到线 3/25 │
     * │ ▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░ │  ← 全宽 HP 条（+ 效果条）
     * │        （画面）         │
     * │ 100/100  名次 30/30     │  ← 左下状态两行
     * │ 道具 无                 │
     * │              ◯ ◯       │  ← 右下键列（攻击/决战/跳 · 道具/加速）
     * └────────────────────────┘
     * </pre>
     */
    private void layoutCompact(UiLayout ui) {
        float s = Math.min(width, height);
        float m = ui.margin;
        float p = ui.pad;

        // 按钮：半径跟着短边走，但不小于 13（再小就点不中了）
        btnRadius = UiLayout.clamp(s * 0.058f, 13f, 46f);
        float gapX = Math.max(4f, btnRadius * 0.34f);
        float gapY = Math.max(5f, btnRadius * 0.50f);
        float cw = 4f * btnRadius + gapX;
        float ch = 6f * btnRadius + 2f * gapY;
        float colGap = 2f * btnRadius + gapX;
        float rowGap = 2f * btnRadius + gapY;
        ui.place(cluster, UiLayout.Anchor.BOTTOM_RIGHT, cw, ch, m, m);
        float rightCol = cluster.right() - btnRadius;
        float leftCol = rightCol - colGap;
        float topRow = cluster.top() - btnRadius;
        btnAt(BTN_ATTACK, rightCol, topRow);
        btnAt(BTN_ULT, rightCol, topRow - rowGap);
        btnAt(BTN_JUMP, rightCol, topRow - 2f * rowGap);
        btnAt(BTN_DASH, leftCol, topRow - rowGap);
        btnAt(BTN_ITEM, leftCol, topRow - 2f * rowGap);

        // 左下状态两行
        float statusH = 2f * lineSmall + 2f * p;
        float statusW = Math.max(100f, width - 2f * m - cluster.w - m - p);
        ui.place(status, UiLayout.Anchor.BOTTOM_LEFT, statusW, statusH, m, m);

        // 顶栏：两行（关卡+倒计时 / 屏障或模式+名额）+ 底部两条细条（HP + 道具效果）
        float stripH = 2f * p + lineMedium + lineSmall + 15f;
        ui.place(topStrip, UiLayout.Anchor.TOP_CENTER, width - 2f * m, stripH, m, m);
        stripRow1Top = topStrip.top() - p;
        stripRow2Top = stripRow1Top - lineMedium;
        hpTrack.set(topStrip.x + p, topStrip.y + p, topStrip.w - 2f * p, 8f);
        effTrack.set(topStrip.x + p, hpTrack.y + 11f, topStrip.w - 2f * p, 4f);
        topLeft.set(0f, 0f, 0f, 0f);
        topRight.set(0f, 0f, 0f, 0f);

        // 摇杆：左半屏（避开键列），上下让开顶栏与键列
        stickZone.set(0f, 0f, Math.max(60f, cluster.x - m), Math.max(60f, topStrip.y - p));
        stickRadius = UiLayout.clamp(Math.min(stickZone.w, stickZone.h) * 0.30f, 40f, 115f);
        knobRadius = stickRadius * 0.40f;

        // 道具卡：底边取"状态面板顶 / 键列顶"里更高的那个 + 间距，向上长
        float cardBottom = Math.max(status.top(), cluster.y + cluster.h) + p;
        card.set(m, cardBottom, width - 2f * m, 0f);
        cardBandHeight = Math.max(0f, (topStrip.y - p) - cardBottom);

        feed.set(m + 4f, topStrip.y - p - lineSmall, Math.max(80f, statusW), lineSmall * 2f);
        banner.set(0f, cardBottom + cardBandHeight * 0.35f, width, lineLarge * 1.6f);
        barrier.set(topStrip.x, topStrip.y - 2f * p - 2f * lineSmall,
                topStrip.w, lineSmall + 2f * p);
        autoplay.set(0f, height - 46f, width, 46f);
    }

    private void btnAt(int slot, float cx, float cy) {
        btnBox[slot].set(cx - btnRadius, cy - btnRadius, 2f * btnRadius, 2f * btnRadius);
    }

    // ================================================================== 道具卡自适应

    /** 折行测量器：真机用 {@code Fonts::wrap}，自检用近似宽度（见 UiLayoutTest）。 */
    public interface Wrap {
        /**
         * 按像素宽度折行。
         *
         * @param out 非 null 时把折好的行写进去
         * @return 行数
         */
        int lines(String text, float maxWidth, Array<String> out);
    }

    /** 道具卡最终选定的排版。 */
    public static final class CardPlan {
        /** 卡片宽度（虚拟像素）。 */
        public float width;
        /** 梗出处最多显示几行（紧凑档放不下就降到 0）。 */
        public int memeLines;
        /** 折行结果（属性 / 说明 / 梗各占几行）。 */
        public int attrLines;
        public int descLines;
        /** 是否放得下：false 表示所有候选都超出可用高度（自检会对它报红）。 */
        public boolean fits;
    }

    /**
     * **量一遍并就定下卡高** —— Hud 与自检共用同一条路径。
     *
     * <p>为什么要共用：折行宽度 ↔ 行数 ↔ 卡高 ↔ 放不放得下 ↔ 换不换排版，是个环。
     * 两边各写一遍必然漂移，而漂移的后果是"自检说放得下、真机把属性切了"。
     */
    public CardPlan measureCard(Wrap wrap, String attrs, String desc, String meme,
                                float lineSmallH) {
        CardPlan plan = planCard(wrap, attrs, desc, meme, lineSmallH);
        float inner = plan.width - 2f * pad;
        plan.attrLines = Math.max(1, wrap.lines(attrs, inner, null));
        plan.descLines = wrap.lines(desc, inner, null);
        int mem = plan.memeLines;
        if (mem > 0) {
            mem = Math.min(mem, Math.max(1, wrap.lines(meme, inner, null)));
        }
        plan.memeLines = mem;
        setCardHeight(cardHeight(plan.attrLines, plan.descLines, mem, lineSmallH));
        return plan;
    }

    /**
     * 给道具卡挑一种排法：**先要全宽，放不下再省梗行**。
     *
     * <p>为什么不是"一个固定宽度"：手表的可用高度是几行字的事，
     * 而不同道具的说明长度差一倍（「遥遥领先」一句 vs「我的世界皓宸」三句）。
     * 固定宽度必然要么浪费、要么溢出，而溢出在手表上就是"属性被切掉" ——
     * 这条正是被用户报过的 bug（见 {@code Fonts.wrap} 的注释）。
     *
     * <p>候选顺序（取第一个放得下的）：
     * <ol>
     *   <li>标准档：旧宽度 + 梗 2 行；</li>
     *   <li>紧凑档：全宽 + 梗 1 行；</li>
     *   <li>紧凑档：全宽 + 不显示梗（**属性一个字都不省**，省的只是梗出处）。</li>
     * </ol>
     */
    private CardPlan planCard(Wrap wrap, String attrs, String desc, String meme,
                              float lineSmallH) {
        CardPlan plan = new CardPlan();
        float[][] cand;                       // {宽度, 梗行数}
        if (compact()) {
            float full = width - 2f * margin;
            cand = new float[][]{{full, 1f}, {full, 0f}};
        } else {
            cand = new float[][]{{card.w, 2f}};
        }
        for (float[] c : cand) {
            float w = Math.min(c[0], width - 2f * margin);
            float inner = w - 2f * pad;
            int attrLines = wrap.lines(attrs, inner, null);
            int descLines = wrap.lines(desc, inner, null);
            int memLines = (int) c[1];
            if (memLines > 0) {
                memLines = Math.min(memLines, Math.max(1, wrap.lines(meme, inner, null)));
            }
            float h = cardHeight(attrLines, descLines, memLines, lineSmallH);
            plan.width = w;
            plan.memeLines = memLines;
            plan.fits = h <= cardBandHeight + 0.5f;
            if (plan.fits) {
                return plan;
            }
        }
        return plan;                          // 全放不下：返回最后一个（自检会报红）
    }

    /**
     * 道具卡高度：内边距 + 标题 + **属性行（可折行）** + 说明行 + 梗出处行。
     *
     * <p>属性也折行、不省略 —— 用户明确要求"道具卡要显示完整属性"，
     * 而手表的宽度只有宽屏的三分之一，固定一行的属性串必然溢出。
     */
    public float cardHeight(int attrLines, int descLines, int memeLines, float lineSmallH) {
        float titleH = lineMedium;
        float lineH = lineSmallH > 0f ? lineSmallH : lineSmall;
        return 2f * pad + titleH + 6f + Math.max(1, attrLines) * lineH + 8f
                + descLines * lineH
                + (memeLines > 0 ? 8f + memeLines * lineH : 0f);
    }

    /** 折行之后把卡高定下来（底边不动，向上长）。 */
    public void setCardHeight(float h) {
        card.h = h;
    }

    /** 卡片顶边是否顶到了不该顶的地方（自检用）。 */
    public boolean cardWithinBand() {
        return card.top() <= (compact() ? topStrip.y - pad : height) + 0.5f;
    }
}
