package top.gunmu.party.map;

import com.badlogic.gdx.math.Vector3;

import top.gunmu.party.GameConfig;
import top.gunmu.party.physics.HeightField;

/**
 * 「这个位置压在赛道路面上吗？压在哪一段？」的唯一判定实现。
 *
 * <p>进度、存档点、终点全部靠它 —— 只有在<b>真压在路面上</b>时弧长才可信。
 *
 * <h3>为什么不能直接用 {@code route.nearest(x, z)}</h3>
 *
 * <p>玩家报的 bug：「之字断崖跳到虚空里时能直接读到下面的存档点和通关区域，
 * 因为销毁不是瞬间销毁的」。根因有两层：
 *
 * <ol>
 *   <li>{@code nearest(x, z)} <b>只看水平坐标、完全不看高度</b>。折返/叠层赛道
 *       在俯视图上两段路可能靠得很近，掉进虚空时最近点会跳到"更靠后"的那一段，
 *       弧长瞬间跳一大截；而坠落判定要等掉够几米才生效，这一小段时间足够把
 *       存档点与终点判定全部误触发。（方位最近点见 {@link Route#nearestTrack}。）</li>
 *   <li>判定完全没有"我到底在不在路上"这一步。地形其实是用同一个
 *       {@code nearest} 裁出来的，所以虚空格子的"最近段"也可能是别处那一段。</li>
 * </ol>
 *
 * <h3>修法</h3>
 *
 * <p>弧长只在同时满足下列条件时才成立：
 *
 * <ul>
 *   <li>脚下是可行走面（{@link HeightField#walkableAt}）；</li>
 *   <li>横向不出走廊：{@code |lateral| ≤ halfWidth + shoulder}；</li>
 *   <li>高度对得上该处的路面（含路肩每往外 1 米下降 0.42 的坡，
 *       见 {@code TerrainBuilder.fromRoute}）。</li>
 * </ul>
 *
 * <p>并且会分别用「水平最近段」和「含高度最近段」各试一次：前者与地形生成口径一致，
 * 后者用于折返/叠层赛道。谁能通过检查就用谁，都不通过就返回 {@link #OFF_TRACK}，
 * 调用方应当<b>保持上一次的弧长不变</b>（冻结），既不前进也不后退。
 *
 * <p>⚠️ 这个类必须被 {@code LevelDirector} 与无头审计 {@code RouteAudit} <b>共用</b>：
 * 审计要是另写一份验算，就会出现"验算写对了、运行时写错了"的老问题（决策 D36）。
 */
public final class TrackProgress {

    /** 不在路面上。调用方遇到它必须保持原有弧长不变。 */
    public static final float OFF_TRACK = -1f;

    /** 高度贴合容差（米）。路面凸凹噪声 ±0.28、路肩 ±0.45、双线性插值再留些余量。 */
    public static final float HEIGHT_SLOP = 3.0f;

    /** 路肩每超出路面 1 米下降的高度，与 {@code TerrainBuilder.fromRoute} 保持一致。 */
    private static final float SHOULDER_SLOPE = 0.42f;

    /**
     * 允许的段外余量（占段长的比例）。
     *
     * <p>有它是为了让"沿上一段"这件事平滑接管端点附近的位置；但**弧长必须按未钳制的
     * t 外推**，否则余量会变成"弧长卡住再一次性释放"。
     */
    private static final float T_OVER = 0.15f;

    private final MapDef map;
    private final float[] scratch = new float[4];
    private final Vector3 center = new Vector3();

    public TrackProgress(MapDef map) {
        this.map = map;
    }

    /** 不带滞后提示的版本（一次性查询用）。 */
    public float arcAt(float x, float y, float z) {
        return arcAt(x, y, z, null);
    }

    /**
     * 求该位置对应的赛程弧长。
     *
     * <p><b>带段滞后（hysteresis）</b>：{@code hint} 传入上一次命中的段号，函数会优先在它
     * 和它的邻居里找。弯道处两条边的距离几乎相等，纯全局搜索会在两者之间来回切，
     * 弧长跟着一帧跳十几米 —— 实测之字断崖的急弯就是这样（最大单帧增量 14.9m，
     * 而滚木一帧最多走 {@code 22/60 ≈ 0.37m}）。优先沿用上一段就把这种闪烁消掉了。
     *
     * @param x    世界坐标
     * @param y    滚木<b>中心</b>高度（内部会减去贴地半径）
     * @param hint 长度 ≥ 1 的数组，{@code hint[0]} 是上次命中的段号（-1 表示没有）；
     *             函数会就地更新它。可传 {@code null}
     * @return 弧长（米），或 {@link #OFF_TRACK}
     */
    public float arcAt(float x, float y, float z, int[] hint) {
        Route route = map.route;
        HeightField field = map.field;
        if (route == null || field == null || route.totalLength <= 0f) {
            return OFF_TRACK;
        }
        if (!field.walkableAt(x, z)) {
            return OFF_TRACK;
        }
        int segCount = route.segmentCount();
        // 候选零：上一帧的段及其左右邻居
        if (hint != null && hint[0] >= 0 && hint[0] < segCount) {
            int prev = hint[0];
            float a;
            if ((a = trySegment(route, x, y, z, prev, hint)) != OFF_TRACK) {
                return a;
            }
            if ((a = trySegment(route, x, y, z, prev - 1, hint)) != OFF_TRACK) {
                return a;
            }
            if ((a = trySegment(route, x, y, z, prev + 1, hint)) != OFF_TRACK) {
                return a;
            }
        }
        // 候选一：水平最近段（与地形生成口径一致）
        route.nearest(x, z, scratch);
        float arc = accept(route, x, y, z, scratch);
        if (arc != OFF_TRACK) {
            remember(hint, scratch);
            return arc;
        }
        // 候选二：含高度最近段（折返/叠层赛道）
        route.nearestTrack(x, y, z, scratch);
        arc = accept(route, x, y, z, scratch);
        if (arc != OFF_TRACK) {
            remember(hint, scratch);
        }
        return arc;
    }

    private static void remember(int[] hint, float[] near) {
        if (hint != null) {
            hint[0] = (int) near[0];
        }
    }

    /**
     * 试着用指定的段来解释这个位置。
     *
     * @return 弧长，或 {@link #OFF_TRACK}（不通过）。命中时把段号写回 {@code hint[0]}
     */
    private float trySegment(Route route, float x, float y, float z, int seg,
                             int[] hint) {
        if (seg < 0 || seg + 1 >= route.nodeCount()) {
            return OFF_TRACK;
        }
        Route.Node a = route.node(seg);
        Route.Node b = route.node(seg + 1);
        float dx = b.x - a.x;
        float dz = b.z - a.z;
        float len2 = dx * dx + dz * dz;
        if (len2 < 1e-6f) {
            return OFF_TRACK;
        }
        float t = ((x - a.x) * dx + (z - a.z) * dz) / len2;
        if (t < -T_OVER || t > 1f + T_OVER) {
            // 离这一段太远，不必考虑
            return OFF_TRACK;
        }
        // 投影点用钳制后的 t（算横向距离），**弧长用不钳制的 t**。
        //
        // ⚠️ 这里以前是 `t = clamp(t, 0, 1)` 之后拿它算弧长，于是玩家走到段末端之外
        // 时弧长被卡在"段末端"，一直卡到越界超过余量才切到下一段、一次性把
        // 0.25 × 段长（本项目段长约 36m → 9m）补回来 —— 表现为**单帧进度跳 10 米**。
        // 弧长外推之后就是连续的，跳变随之消失。
        float tProj = Math.max(0f, Math.min(1f, t));
        float px = a.x + dx * tProj;
        float pz = a.z + dz * tProj;
        float ex = x - px;
        float ez = z - pz;
        float nl = (float) Math.sqrt(len2);
        float lat = (ex * (-dz) + ez * dx) / nl;
        float arc = route.arcAt(seg, t);
        if (!passes(route, x, y, arc, lat)) {
            return OFF_TRACK;
        }
        if (hint != null) {
            hint[0] = seg;
        }
        return arc;
    }

    /** 检查某个候选最近点是否真的"压在路面上"，是则返回弧长。 */
    private float accept(Route route, float x, float y, float z, float[] near) {
        float arc = near[3];
        if (passes(route, x, y, arc, near[2])) {
            return arc;
        }
        return OFF_TRACK;
    }

    /**
     * 三条硬性判据（横向不出走廊 / 高度对得上路面）。
     *
     * <p>滚木的坐标里 {@code y} 是<b>中心</b>，判高度时要减掉贴地半径。
     */
    private boolean passes(Route route, float x, float y, float arc, float signedLat) {
        float lat = Math.abs(signedLat);
        float hw = route.halfWidthAt(arc);
        float sh = route.shoulderAt(arc);
        if (lat > hw + sh + 1f) {
            return false;
        }
        route.sampleAt(arc, center);
        // 路肩是斜的：越往外越低
        float over = Math.max(0f, lat - hw);
        float surfaceY = center.y - over * SHOULDER_SLOPE;
        float rollSurface = y - GameConfig.LOG_RADIUS;
        return Math.abs(rollSurface - surfaceY) <= HEIGHT_SLOP;
    }

    /** 滚木所在位置是否算作"在赛道上"（供渲染/HUD 显示用）。 */
    public boolean onTrack(float x, float y, float z) {
        return arcAt(x, y, z) != OFF_TRACK;
    }
}
