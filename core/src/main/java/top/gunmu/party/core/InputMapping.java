package top.gunmu.party.core;

import com.badlogic.gdx.math.Vector3;

/**
 * 把「屏幕空间输入」转成「世界空间移动方向」。
 *
 * <p><b>为什么必须单独成一个函数</b>：这台机器上出过一次很贵的错 ——
 * 横向两项的符号写反，导致<b>按 A 往右、按 D 往左</b>。
 * 这种错编译器不报、物理冒烟测试也抓不到，只有人上手玩才会发现。
 * 抽成纯函数之后，{@code InputMappingTest} 可以直接拿
 * {@link com.badlogic.gdx.graphics.PerspectiveCamera} 的<b>真实基向量</b>来验它。
 *
 * <h3>推导（不要凭感觉改符号）</h3>
 * libGDX 的 {@code Matrix4.setToLookAt(eye, target, up)} 内部是：
 * <pre>
 *   z_view = normalize(eye − target)      // 相机看向 −z_view
 *   x_view = normalize(up × z_view)       // 屏幕右
 *   y_view = z_view × x_view              // 屏幕上
 * </pre>
 * 记相机水平朝向 {@code f = (sin yaw, cos yaw)}（{@link top.gunmu.party.map.Route#yawAt} 的约定）。
 * 由于 {@code z_view = −f}，代入 {@code up = (0,1,0)}：
 * <pre>
 *   x_view = up × (−f) = (−f.z, 0, +f.x)
 * </pre>
 * 即 <b>屏幕右 = (−cos yaw, 0, sin yaw)</b>，而不是 {@code (cos yaw, 0, −sin yaw)}。
 * 两者差一个负号 —— 就是"左右反了"的全部原因。
 *
 * <p>屏幕上（W）= {@code f}；于是
 * <pre>
 *   world = f·sy + (−f.z, 0, f.x)·sx
 * </pre>
 */
public final class InputMapping {

    private InputMapping() {
    }

    /**
     * @param camYaw 相机水平朝向（弧度，0 = 世界 +Z）
     * @param sx     屏幕横向输入，向右为正（D / →
     * @param sy     屏幕纵向输入，向上为正（W / ↑
     * @param out    写入世界空间方向（XZ 平面，长度 = 输入长度）
     */
    public static void screenToWorld(float camYaw, float sx, float sy, Vector3 out) {
        float fx = (float) Math.sin(camYaw);
        float fz = (float) Math.cos(camYaw);
        // f·sy 是"屏幕上方"，(−fz, fx)·sx 是"屏幕右方"
        out.x = fx * sy - fz * sx;
        out.z = fz * sy + fx * sx;
        out.y = 0f;
    }

    /** 屏幕右方向在世界里的单位向量（供自检直接对照相机基向量用）。 */
    public static void screenRight(float camYaw, Vector3 out) {
        float fx = (float) Math.sin(camYaw);
        float fz = (float) Math.cos(camYaw);
        out.set(-fz, 0f, fx);
    }

    /** 屏幕上方向在世界里的单位向量（水平分量）。 */
    public static void screenUp(float camYaw, Vector3 out) {
        out.set((float) Math.sin(camYaw), 0f, (float) Math.cos(camYaw));
    }
}
