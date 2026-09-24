package top.gunmu.party.render;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.ViewControl;

/**
 * 第三人称跟随相机：绕玩家做固定俯角的环绕，朝向 = <b>基础朝向 + 手动偏移</b>。
 *
 * <p>基础朝向跟赛道切线（竞速图），手动偏移由玩家拖出来（{@link ViewControl}）。
 *
 * <p><b>旧版的四个错误</b>（前三个都是"看着就不对"但断言抓不到的）：
 * <ol>
 *   <li>把 {@code CAMERA_HEIGHT_ABOVE} 和 {@code CAMERA_DISTANCE} 叠加使用，
 *       等于给相机加了两个高度，屏幕中心落在玩家头顶上方约 9 米处 ——
 *       玩家被顶到画面最下沿，看到的大半是天空。</li>
 *   <li>朝向写死 {@code (0, sin, cos)}，和相机实际位置不匹配，
 *       <b>相机根本没有看向玩家</b>。</li>
 *   <li>yaw 恒定，之字断崖那种来回折的赛道有一半时间是横着看的。</li>
 *   <li><b>玩家根本没法转视角</b>（没有输入通道），而且生存 / 竞技图没有 route、
 *       基础朝向一直是 {@code NaN}，连自动转都被跳过 → 视角彻底定死。
 *       见 {@link ViewControl} 的类注释。</li>
 * </ol>
 *
 * <p>现在只保留三个量：俯角、距离、朝向。相机位置由
 * {@code 看点 - 朝向 * 水平距离 + 上方高度} 算出，
 * {@code direction} 由 {@code 看点 - 相机位置} 反算，
 * 所以<b>看点永远在屏幕正中</b>，构图不会因为改参数而漂。
 *
 * <h3>唯一真值</h3>
 * 朝向的合成只走 {@link #yaw()}（= {@code baseYaw + 手动偏移}），{@link #apply()}
 * 与输入映射（{@code Hud.applyInput(player, rig.yaw())}）都只读它。
 * <b>不许再出现第二个"当前朝向"字段</b> —— 一旦画面用的角度和输入映射用的角度不是
 * 同一个数，就会出现"按 A 往右、按 D 往左"那类只有上手玩才发现的错位。
 */
public final class CameraRig {

    public final PerspectiveCamera camera = new PerspectiveCamera(
            GameConfig.CAMERA_FOV, 16f / 9f, 1f);

    /** 从相机指向看点的单位向量（相机朝向）。 */
    private final Vector3 dir = new Vector3();
    /** 相机盯着的点（已含前瞻与抬高），永远在屏幕正中。 */
    private final Vector3 look = new Vector3();
    /** 玩家实际位置，平滑跟随。 */
    private final Vector3 focus = new Vector3();
    /** 玩家当前位置（未平滑），仅用于计算朝向。 */
    private final Vector3 rawFocus = new Vector3();

    private boolean initialized;

    /** 平滑后的基础朝向（弧度，0 = 世界 +Z）。 */
    private float baseYaw;
    /** 基础朝向的目标值，来自赛道切线。换图瞬间要能直接跳过去。 */
    private float baseTargetYaw;
    private boolean baseInit;

    /** 玩家手动拖出来的视角偏移。 */
    private final ViewControl view = new ViewControl();

    public CameraRig() {
        camera.near = 0.3f;
        camera.far = 1500f;
        camera.up.set(0f, 1f, 0f);
        camera.update();
    }

    public void resize(int width, int height) {
        camera.viewportWidth = Math.max(1, width);
        camera.viewportHeight = Math.max(1, height);
        camera.update();
    }

    /** 累积一次拖动（屏幕像素，向右为正）。HUD 那边取走后原样送进来。 */
    public void addViewDrag(float dxPx) {
        view.addDrag(dxPx);
    }

    /** 当前手动视角偏移（弧度）。 */
    public float viewOffset() {
        return view.offset();
    }

    /** 是否正在自动回中（界面要提示"松手后会自动转回赛道方向"）。 */
    public boolean viewRecentering() {
        return view.recentering();
    }

    /** 立即跳到目标点与目标朝向（换关卡时用，避免第一帧从原点飞过来）。 */
    public void snapTo(Vector3 target) {
        focus.set(target);
        rawFocus.set(target);
        initialized = true;
        if (baseInit) {
            baseYaw = baseTargetYaw;
        }
        // 换图必须清掉手动偏移：新赛道不该继承上一张图转过去的角度
        view.reset();
        apply();
    }

    /**
     * @param target   玩家位置
     * @param routeYaw 赛道在该处的切线朝向（弧度）。传 {@link Float#NaN} 表示
     *                 该图没有赛道（生存 / 竞技图），基础朝向保持不变。
     */
    public void update(Vector3 target, float routeYaw, float delta) {
        rawFocus.set(target);
        if (!initialized) {
            snapTo(target);
            return;
        }

        float k = 1f - (float) Math.exp(-GameConfig.CAMERA_LERP * delta);
        focus.x += (target.x - focus.x) * k;
        focus.y += (target.y - focus.y) * k;
        focus.z += (target.z - focus.z) * k;

        boolean hasTrack = !Float.isNaN(routeYaw);
        if (hasTrack) {
            // 先按最短弧把目标朝向拉回 -PI..PI，再平滑
            if (!baseInit) {
                baseYaw = routeYaw;
                baseTargetYaw = routeYaw;
                baseInit = true;
            } else {
                baseTargetYaw = routeYaw;
                float d = ViewControl.wrapPi(routeYaw - baseYaw);
                // 朝向转得比位置慢一点，转弯时不会被甩得头晕
                baseYaw = ViewControl.wrapPi(
                        baseYaw + d * (1f - (float) Math.exp(-GameConfig.CAMERA_YAW_LERP * delta)));
            }
        } else if (!baseInit) {
            // 没有赛道的图：基础朝向固定为 +Z，玩家用手动偏移去看任意方向
            baseInit = true;
            baseTargetYaw = 0f;
        }

        // 手动偏移：有赛道的图会自动回中，没有赛道的图不回中（见 ViewControl）
        view.update(delta, hasTrack);

        apply();
    }

    private void apply() {
        float pitch = GameConfig.CAMERA_PITCH * MathUtils.degRad;
        float dist = GameConfig.CAMERA_DISTANCE;

        float y = yaw();
        float fx = MathUtils.sin(y);
        float fz = MathUtils.cos(y);

        // 看点：玩家前方一点、再抬高一点，让玩家落在画面下三分之一
        look.set(
                focus.x + fx * GameConfig.CAMERA_LOOKAHEAD,
                focus.y + GameConfig.CAMERA_LOOK_LIFT,
                focus.z + fz * GameConfig.CAMERA_LOOKAHEAD);

        // 相机在看的反方向、按俯角抬起
        float back = dist * MathUtils.cos(pitch);
        float up = dist * MathUtils.sin(pitch);
        camera.position.set(look.x - fx * back, look.y + up, look.z - fz * back);

        // 朝向由位置反算，保证看点落在屏幕正中
        dir.set(look).sub(camera.position).nor();
        camera.direction.set(dir);
        camera.up.set(0f, 1f, 0f);
        camera.update();
    }

    /**
     * 相机实际水平朝向（弧度，0 = +Z）= 基础朝向 + 手动偏移。
     *
     * <p>输入映射要用它把屏幕方向转成世界方向，因此它必须与画面用的朝向<b>完全一致</b>。
     */
    public float yaw() {
        return ViewControl.compose(baseYaw, view.offset());
    }

    public Vector3 focus() {
        return focus;
    }
}
