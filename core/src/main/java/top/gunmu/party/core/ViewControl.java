package top.gunmu.party.core;

import top.gunmu.party.GameConfig;

/**
 * 手动转视角：叠加在「赛道自动朝向」之上的那一层玩家偏移。
 *
 * <h3>为什么要有这个类（玩家原话：「为啥没办法转视角」）</h3>
 * 原来 <b>全平台没有任何一条转视角的输入通道</b>：键盘上 WASD/方向键是移动、
 * 空格跳、E 道具、Q 决战、Shift 加速，鼠标压根没接进游戏；
 * 安卓端只有左半屏摇杆 + 四个按钮。相机的 yaw 只有一个来源 ——
 * {@code MapNav.cameraYaw()} 给的赛道切线。所以：
 * <ul>
 *   <li>竞速图里视角是"绑死在车头"的，玩家想看一眼侧面都做不到；</li>
 *   <li>生存图 / 竞技图 <b>没有 route</b>，{@code cameraYaw} 返回 {@code NaN}，
 *       {@code CameraRig.update} 里整段朝向更新被跳过 —— 视角<b>连自动转都没有</b>，
 *       永远定死在进图那一刻的角度。这就是"完全没办法转视角"最直接的来源。</li>
 * </ul>
 *
 * <h3>语义</h3>
 * <pre>
 *   最终朝向 = 基础朝向（竞速图 = 赛道切线，自动跟随）+ 手动偏移（本类）
 * </pre>
 * 手动偏移是<b>边沿累积</b>而不是"按住才生效"：拖一下松手，视角就停在转过去的地方。
 *
 * <p><b>回中</b>：竞速图有赛道可跟，停手 {@code VIEW_RECENTER_DELAY} 秒后偏移按指数
 * 衰减回 0 —— 否则玩家转歪 90° 之后，车头方向与画面方向就永久错位了。
 * 生存 / 竞技图没有"赛道方向"这个参照物，偏移就是<b>绝对朝向</b>，<b>不回中</b>。
 *
 * <p>本类是纯逻辑（不碰 GL、不碰 Gdx.input），所以 {@code tools/ViewTest}
 * 能直接对"转了多少度 / 什么时候回中"下断言 —— 而这类"只有开窗口才知道对不对"
 * 的东西，正是这个项目已经栽过好几次的地方。
 */
public final class ViewControl {

    /** 当前手动偏移（弧度）。0 = 完全跟着基础朝向。 */
    private float offset;
    /** 距上一次手动拖动过去了多少秒；从未拖过时是最大值（等于"早该回中了"）。 */
    private float idle = Float.MAX_VALUE;
    /** 本帧待换算的拖动像素，由 HUD 一侧取走后塞进来。 */
    private float pendingPx;

    /** 换关 / 换图时清干净：新赛道不该继承上一张图的视角偏移。 */
    public void reset() {
        offset = 0f;
        idle = Float.MAX_VALUE;
        pendingPx = 0f;
    }

    /**
     * 累积拖动像素（屏幕横向，向右为正）。
     *
     * <p>小于死区的抖动直接丢掉 —— 触屏上手指按下时几乎必然有几像素的抖动，
     * 收下来会让视角有"自己会飘"的感觉。
     */
    public void addDrag(float dxPx) {
        if (Math.abs(dxPx) < GameConfig.VIEW_DEADZONE_PX) {
            return;
        }
        pendingPx += dxPx;
    }

    /**
     * 每帧推进一次。
     *
     * @param dt           帧时长（秒）
     * @param autoRecenter 是否允许自动回中（只有竞速图那种"有赛道可跟"的图才 true）
     * @return 偏移本帧是否发生了变化（供自检与诊断用）
     */
    public boolean update(float dt, boolean autoRecenter) {
        boolean changed = false;

        if (pendingPx != 0f) {
            // 向右拖 = 视角向右转，与主流第三人称手游/端游一致。
            // yaw 增加的方向是 +Z → +X，也就是屏幕里的"向右看"。
            offset = wrapPi(offset + pendingPx * GameConfig.VIEW_RAD_PER_PX);
            pendingPx = 0f;
            idle = 0f;
            changed = true;
        } else if (idle < Float.MAX_VALUE) {
            idle = Math.min(idle + dt, Float.MAX_VALUE);
        }

        if (autoRecenter && idle > GameConfig.VIEW_RECENTER_DELAY && offset != 0f) {
            offset = wrapPi(offset * (float) Math.exp(-GameConfig.VIEW_RECENTER_RATE * dt));
            if (Math.abs(offset) < 1e-4f) {
                offset = 0f;
            }
            changed = true;
        }
        return changed;
    }

    /** 当前手动偏移（弧度）。 */
    public float offset() {
        return offset;
    }

    /** 停止拖动之后过了多久（秒）。 */
    public float idle() {
        return idle;
    }

    /** 是否已经进入"正在回中"的状态（界面画提示要用）。 */
    public boolean recentering() {
        return idle > GameConfig.VIEW_RECENTER_DELAY && offset != 0f;
    }

    /** 合成最终相机朝向。全仓唯一的合成口径。 */
    public static float compose(float baseYaw, float offset) {
        return wrapPi(baseYaw + offset);
    }

    /** 把角度收进 −π..π。 */
    public static float wrapPi(float a) {
        while (a > (float) Math.PI) {
            a -= (float) (Math.PI * 2);
        }
        while (a < -(float) Math.PI) {
            a += (float) (Math.PI * 2);
        }
        return a;
    }
}
