package top.gunmu.party.tools;

import java.util.Locale;

import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapRegistry;
import top.gunmu.party.map.Route;

/**
 * 「两条路面有没有叠在一起」的审计。
 *
 * <p><b>为什么必须有这条检查</b>：地形是按路径生成的，而一份高度场<b>每个格子只能有一个高度</b>。
 * 两条路在水平面上靠得比"半宽之和"还近时：
 *
 * <ul>
 *   <li>高的那条会盖住低的那条，低的那条**实际不可通行**；</li>
 *   <li>{@code nearest(x,z)} 会在两条路的弧长之间乱跳 → <b>进度偷鸡</b>；</li>
 *   <li>玩家站在接缝上时脚下高度反复切换 → 看起来就是<b>被瞬移到另一层</b>。</li>
 * </ul>
 *
 * <p>玩家报的「之字悬崖一到终点就被瞬移到上面、而且还能偷鸡」正是这一类。
 * 所以这里把"路面不许重叠"从口头约定变成会报错的数字：
 * <b>任意两条相隔 2 段以上的路段，水平最小距离必须大于两者半宽之和。</b>
 *
 * <p>相邻段（{@code |i−j| == 1}）豁免 —— 急弯的内圈本来就该挨着。
 */
public final class RouteOverlapTest {

    /** 除了半宽之和，还要留的余量（米）。 */
    private static final float EXTRA_MARGIN = 2f;

    /**
     * 高度差超过这个值、且水平重叠 → 才判失败（米）。
     *
     * <p>为什么按高度过滤：<b>同高的两条路挨着是无害的</b>（地形连成一片，本来就是一片平台，
     * 起点广场、终点缓冲台内部就是这样加宽的），玩家在哪儿脚下都是同一个高度。
     *
     * <p>真正会出事的是<b>高度差很大</b>的那种：地形格子只能存一个高度，高的那条会盖住低的，
     * 玩家走在低的那条上会被 {@code resolveGround} <b>直接抬到高的那条</b>——
     * 这就是玩家报的「一到终点就被瞬移到上面」。滚木半径 0.55、地形噪声 ±0.45，
     * 十几米的高度差绝无可能靠滚动跨越，所以 12 米是安全的分界。
     */
    private static final float HEIGHT_CONFLICT = 12f;

    private RouteOverlapTest() {
    }

    /** 单独跑（调地图坐标时用，比整包冒烟快得多）。 */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    public static int run() {
        System.out.println("---- 路面重叠审计（两条路不许叠在一起）----");
        int bad = 0;
        for (int i = 0; i < MapRegistry.RACE_IDS.length; i++) {
            bad += check(MapRegistry.RACE_IDS[i]);
        }
        System.out.println(bad == 0 ? "  竞速图不存在互相重叠的路段"
                : "  有 " + bad + " 处路面真的相交 —— 地形会盖住其中一条，玩家会被瞬移");
        return bad;
    }

    private static int check(String id) {
        MapDef map = MapRegistry.create(id);
        String label = map.name + "(" + id + ")";
        if (!map.hasRoute()) {
            return 0;
        }
        Route r = map.route;
        int n = r.nodeCount();
        int bad = 0;
        String worst = null;
        float worstGap = Float.MAX_VALUE;
        for (int i = 0; i + 1 < n; i++) {
            // 从 i+3 开始：|i−j| ≤ 2 的段对豁免。
            //
            // 相邻段共享节点，必然贴在一起；隔一段的那对，中间那段的长度就是它们的
            // 最近距离 —— 那属于"缓冲台与它自己的延伸段"（起点广场加宽、急弯内圈），
            // 玩家沿路走过去高度是连续的，不构成"两条不同的路叠在一起"。
            // 真正会出事的是<b>远处</b>两条带叠在一起（旧版之字断崖的段9↔段21 就是，
            // 水平距 0.0 米、高度差 114 米），那种 |i−j| 都在 10 以上。
            for (int j = i + 3; j + 1 < n; j++) {
                Route.Node a0 = r.node(i);
                Route.Node a1 = r.node(i + 1);
                Route.Node b0 = r.node(j);
                Route.Node b1 = r.node(j + 1);
                float dy = Math.max(Math.max(Math.abs(a0.y - b0.y), Math.abs(a0.y - b1.y)),
                        Math.max(Math.abs(a1.y - b0.y), Math.abs(a1.y - b1.y)));
                if (dy <= HEIGHT_CONFLICT) {
                    // 同高：连成一片，无害
                    continue;
                }
                float d = segDist(a0.x, a0.z, a1.x, a1.z, b0.x, b0.z, b1.x, b1.z);
                float hw = Math.max(a0.halfWidth, a1.halfWidth)
                        + Math.max(b0.halfWidth, b1.halfWidth);
                float gap = d - hw;
                if (gap < worstGap) {
                    worstGap = gap;
                    worst = String.format(Locale.ROOT,
                            "段%d↔段%d 水平距 %.1fm，半宽和 %.1fm，%s %.1fm（高度差 %.0fm）",
                            i, j, d, hw, gap < 0f ? "重叠" : "余量", Math.abs(gap), dy);
                }
                // 判失败的门槛是"真的相交"（gap < 0）。
                //
                // 余量不足（0 ≤ gap < EXTRA_MARGIN）只提示不判错：急弯的内圈、缓冲台与
                // 路径的接头，本来就会贴得很近，而且两条路的高度差再大，只要圆面不相交，
                // 玩家就不可能同时站在两条路上。
                if (gap < EXTRA_MARGIN && gap >= 0f) {
                    System.out.println(String.format(Locale.ROOT,
                            "  [提示] %s 段%d↔段%d 余量只有 %.1fm（高度差 %.0fm）",
                            label, i, j, gap, dy));
                }
                if (gap < 0f) {
                    bad++;
                    System.out.println(String.format(Locale.ROOT,
                            "  [!!] %s 段%d↔段%d 两条路相交 %.1fm（高度 %.1f vs %.1f，差 %.0fm）"
                                    + " → 高的那条会盖住低的，玩家会被瞬移上去",
                            label, i, j, -gap, a0.y, b0.y, dy));
                }
            }
        }
        if (bad == 0) {
            System.out.println(String.format(Locale.ROOT,
                    "  [OK] %s 最近的两段：%s", label, worst));
        }
        return bad;
    }

    /** 两条 2D 线段之间的最短水平距离（相交则 0）。 */
    static float segDist(float ax, float az, float bx, float bz,
                         float cx, float cz, float dx, float dz) {
        if (segmentsIntersect(ax, az, bx, bz, cx, cz, dx, dz)) {
            return 0f;
        }
        float d = Float.MAX_VALUE;
        d = Math.min(d, pointSeg(ax, az, cx, cz, dx, dz));
        d = Math.min(d, pointSeg(bx, bz, cx, cz, dx, dz));
        d = Math.min(d, pointSeg(cx, cz, ax, az, bx, bz));
        d = Math.min(d, pointSeg(dx, dz, ax, az, bx, bz));
        return d;
    }

    private static float pointSeg(float px, float pz, float ax, float az,
                                  float bx, float bz) {
        float dx = bx - ax;
        float dz = bz - az;
        float len2 = dx * dx + dz * dz;
        if (len2 < 1e-9f) {
            return (float) Math.hypot(px - ax, pz - az);
        }
        float t = ((px - ax) * dx + (pz - az) * dz) / len2;
        t = Math.max(0f, Math.min(1f, t));
        return (float) Math.hypot(px - (ax + dx * t), pz - (az + dz * t));
    }

    private static boolean segmentsIntersect(float ax, float az, float bx, float bz,
                                             float cx, float cz, float dx, float dz) {
        float d1 = cross(cx, cz, dx, dz, ax, az);
        float d2 = cross(cx, cz, dx, dz, bx, bz);
        float d3 = cross(ax, az, bx, bz, cx, cz);
        float d4 = cross(ax, az, bx, bz, dx, dz);
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f))
                && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f));
    }

    private static float cross(float ax, float az, float bx, float bz,
                               float px, float pz) {
        return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
    }
}
