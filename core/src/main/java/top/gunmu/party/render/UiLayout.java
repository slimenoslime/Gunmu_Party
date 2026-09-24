package top.gunmu.party.render;

/**
 * UI 锚点系统：把"贴哪条边、留多少边距、多大"变成**纯几何**，因此可以在无窗口自检里断言。
 *
 * <p>为什么要有这套东西：旧 HUD 是**对着 810 高的参考屏硬编码**的 ——
 * 左上信息面板固定 480 宽、右上 236 宽、按钮写死在 {@code width - 108}、
 * HP 条 300 宽摆在 (34, 76)。在 1440×810 的虚拟屏上正好，但屏幕一小就全挤在一起：
 *
 * <pre>
 * 表盘 396×396 → uiScale 被钳到下限 0.85 → 虚拟屏只剩 466×466
 *   · 480 + 236 = 716 &gt; 466          → 左右两块面板直接叠上
 *   · 右下状态行从 x=350 开始          → 文字被右边缘切掉（"名次 30 / 30  速…"）
 *   · 按钮直径 116、间距 124            → 五个键占掉半屏，压住地图
 * </pre>
 *
 * <p>这套系统只做三件事：
 * <ol>
 *   <li>{@link #place} —— 按 {@link Anchor} 把一块 (w, h) 放进"屏幕 − 安全边距"的区域；</li>
 *   <li>{@link #margin} / {@link #pad} —— 边距与内边距**按屏幕短边推**，不写死数字；</li>
 *   <li>{@link #textWidth} / {@link #ellipsize} —— 中英混排的**保守**字宽估算，
 *       让"这段字放不放得下"变成可断言的（真机上再拿 {@code Fonts.width} 复核）。</li>
 * </ol>
 *
 * <p>坐标约定与 HUD 一致：**虚拟坐标，y 向上**，原点在左下角。
 * 虚拟尺寸 = 真实像素 / uiScale，字体也按同一个 uiScale 缩放，所以两边口径一致。
 *
 * <p>本类**不引用任何 GL 对象**（{@code BitmapFont} 要 GL 才能造），
 * 只吃行高、字号这些数字 —— 这是它能在 {@code tools/UiLayoutTest} 里跑的前提。
 */
public final class UiLayout {

    /** 贴哪条边（或哪个角）。 */
    public enum Anchor {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        MID_LEFT, CENTER, MID_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    /** 一块矩形（虚拟坐标，y 向上）。可变对象，放在成员里复用，避免每帧 new。 */
    public static final class Rect {

        public float x;
        public float y;
        public float w;
        public float h;

        public Rect set(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        public Rect set(Rect o) {
            return set(o.x, o.y, o.w, o.h);
        }

        public float right() {
            return x + w;
        }

        public float top() {
            return y + h;
        }

        public float cx() {
            return x + w * 0.5f;
        }

        public float cy() {
            return y + h * 0.5f;
        }

        public boolean contains(float px, float py) {
            return px >= x && px <= right() && py >= y && py <= top();
        }

        /** 向内缩进一圈。 */
        public Rect inset(float p) {
            x += p;
            y += p;
            w -= 2f * p;
            h -= 2f * p;
            return this;
        }

        /** 四周内缩一圈，但把结果写进 {@code out}，不改自己。 */
        public void inner(Rect out, float p) {
            out.set(x + p, y + p, w - 2f * p, h - 2f * p);
        }

        /** 是否与另一块相交（边贴边不算）。 */
        public boolean overlaps(Rect o) {
            return x < o.right() && o.x < right() && y < o.top() && o.y < top();
        }

        /** 是否与另一块相交，允许留出 {@code tol} 的间隙。 */
        public boolean overlaps(Rect o, float tol) {
            return x < o.right() - tol && o.x < right() - tol
                    && y < o.top() - tol && o.y < top() - tol;
        }

        public boolean inside(Rect o) {
            return x >= o.x && y >= o.y && right() <= o.right() && top() <= o.top();
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "[%.1f, %.1f, %.1f×%.1f]", x, y, w, h);
        }
    }

    /** 虚拟屏幕尺寸。 */
    public final float width;
    public final float height;
    /** 统一外边距（按短边推）。 */
    public final float margin;
    /** 面板内边距（按短边推）。 */
    public final float pad;
    /** 屏幕本身的可放置矩形。 */
    public final Rect screen = new Rect();
    /** 扣掉安全区（刘海 / 圆角 / 表盘）之后的可放置矩形。 */
    public final Rect safe = new Rect();

    public UiLayout(float width, float height) {
        this(width, height, 0f, 0f, 0f, 0f);
    }

    /**
     * @param insetTop / insetBottom / insetLeft / insetRight 安全区（真实像素换算到虚拟像素后传入）
     */
    public UiLayout(float width, float height,
                    float insetLeft, float insetTop, float insetRight, float insetBottom) {
        this.width = width;
        this.height = height;
        float s = Math.min(width, height);
        // 0.0222 是"1440×810 时正好 18"反推出来的系数 —— 宽屏上看起来与旧版完全一样
        this.margin = clamp(s * 0.0222f, 8f, 26f);
        // 0.0198 → 1440×810 时正好 16，等于旧的 CARD_PAD
        this.pad = clamp(s * 0.0198f, 5f, 16f);
        screen.set(0f, 0f, width, height);
        safe.set(insetLeft, insetBottom,
                Math.max(1f, width - insetLeft - insetRight),
                Math.max(1f, height - insetTop - insetBottom));
    }

    /**
     * 按锚点把一块 (w, h) 放进可放置区域。
     *
     * <p>边距 = {@link #margin}。宽度会被**自动夹到放得下**为止
     * （宁可比想要的小，也不许溢出屏幕 —— 溢出就是文字被切，正是要修的毛病）。
     */
    public Rect place(Anchor a, float w, float h) {
        return place(null, a, w, h, margin);
    }

    public Rect place(Anchor a, float w, float h, float m) {
        return place(null, a, w, h, m);
    }

    public Rect place(Rect out, Anchor a, float w, float h) {
        return place(out, a, w, h, margin);
    }

    /** 横竖边距不同（标准档要沿用旧的视觉基线：右上角键列离边 50、底部键列离边 110）。 */
    public Rect place(Rect out, Anchor a, float w, float h, float mx, float my) {
        Rect r = out == null ? new Rect() : out;
        float availW = Math.max(1f, safe.w - 2f * mx);
        float availH = Math.max(1f, safe.h - 2f * my);
        float ww = Math.min(w, availW);
        float hh = Math.min(h, availH);
        r.set(xFor(a, safe.x + mx, safe.right() - mx, ww),
                yFor(a, safe.y + my, safe.top() - my, hh), ww, hh);
        return r;
    }

    /** 供复用的版本：结果写进 {@code out}（{@code null} 则新建）。 */
    public Rect place(Rect out, Anchor a, float w, float h, float m) {
        Rect r = out == null ? new Rect() : out;
        float availW = Math.max(1f, safe.w - 2f * m);
        float availH = Math.max(1f, safe.h - 2f * m);
        float ww = Math.min(w, availW);
        float hh = Math.min(h, availH);
        float x0 = safe.x + m;
        float x1 = safe.right() - m;
        float y0 = safe.y + m;
        float y1 = safe.top() - m;
        r.set(xFor(a, x0, x1, ww), yFor(a, y0, y1, hh), ww, hh);
        return r;
    }

    /** 在**给定容器**里按锚点放一块（用于"贴着某块的上沿/右侧"）。 */
    public static Rect placeIn(Rect box, Anchor a, float w, float h) {
        float ww = Math.min(w, box.w);
        float hh = Math.min(h, box.h);
        return new Rect().set(xFor(a, box.x, box.right(), ww),
                yFor(a, box.y, box.top(), hh), ww, hh);
    }

    private static float xFor(Anchor a, float x0, float x1, float w) {
        switch (a) {
            case TOP_RIGHT:
            case MID_RIGHT:
            case BOTTOM_RIGHT:
                return x1 - w;
            case TOP_CENTER:
            case CENTER:
            case BOTTOM_CENTER:
                return x0 + (x1 - x0 - w) * 0.5f;
            default:
                return x0;
        }
    }

    private static float yFor(Anchor a, float y0, float y1, float h) {
        switch (a) {
            case TOP_LEFT:
            case TOP_CENTER:
            case TOP_RIGHT:
                return y1 - h;
            case MID_LEFT:
            case CENTER:
            case MID_RIGHT:
                return y0 + (y1 - y0 - h) * 0.5f;
            default:
                return y0;
        }
    }

    // ================================================================== 文字放得下吗

    /**
     * 中英混排的**保守**字宽估算（虚拟像素）。
     *
     * <p>刻意往大了估：CJK / 全角按 1.0 个字身（这类字体里 CJK 就是 1 em 宽），
     * ASCII 按 0.62（等宽字体常见 0.5~0.6）。**宁可估宽**——
     * 自检断言用的是它，估宽意味着"断言过了，真机一定过得去"；
     * 估窄就等于放了一堆假绿灯过去。真机排版仍然用 {@code Fonts.width} 实测。
     */
    public static float textWidth(float fontSize, String s) {
        if (s == null || s.isEmpty()) {
            return 0f;
        }
        float w = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += isWide(cp) ? fontSize : fontSize * 0.62f;
            i += Character.charCount(cp);
        }
        return w;
    }

    /** CJK 与全角标点算"一个字身宽"。 */
    public static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)          // 韩文字母
                || (cp >= 0x2E80 && cp <= 0xA4CF)      // CJK 部首 / 假名 / 汉字
                || (cp >= 0xAC00 && cp <= 0xD7A3)      // 韩文音节
                || (cp >= 0xF900 && cp <= 0xFAFF)      // CJK 兼容汉字
                || (cp >= 0xFE30 && cp <= 0xFE6F)      // CJK 兼容形式
                || (cp >= 0xFF00 && cp <= 0xFF60)      // 全角
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x20000 && cp <= 0x3FFFD);   // 扩展区
    }

    /**
     * 放不下就在**尾部**省略（逐字符，与 {@code Fonts.wrap} 同一口径：中文不看空格断词）。
     */
    public static String ellipsize(float fontSize, String s, float maxWidth) {
        if (s == null || s.isEmpty() || textWidth(fontSize, s) <= maxWidth) {
            return s == null ? "" : s;
        }
        float dots = textWidth(fontSize, "…");
        float acc = 0f;
        int end = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            float cw = isWide(cp) ? fontSize : fontSize * 0.62f;
            if (acc + cw + dots > maxWidth) {
                break;
            }
            acc += cw;
            i += Character.charCount(cp);
            end = i;
        }
        return s.substring(0, end) + "…";
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
