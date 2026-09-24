package top.gunmu.party.tools;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

import top.gunmu.party.core.InputMapping;

/**
 * 屏幕空间 → 世界空间 的输入映射自检。<b>不需要 OpenGL</b>。
 *
 * <p>判据不是"我算的公式等于我算的公式"，而是拿 {@link PerspectiveCamera}
 * <b>真实构造出来的视图矩阵</b>当参照：libGDX 把屏幕右写在视图矩阵第 0 行、
 * 屏幕上写在第 1 行。把这两个基向量在 XZ 平面上取水平分量，
 * 就是"按 D / 按 W 应该往世界哪个方向走"的权威答案。
 *
 * <p>这一版曾经栽在 {@code 按 A 往右、按 D 往左} 上 —— 横向两项符号写反，
 * 编译、地图校验、整局冒烟全绿。所以这条断言是必须的。
 */
public final class InputMappingTest {

    private static final float[] YAWS = {
            0f, 30f, 45f, 90f, 135f, 180f, -90f, -135f, 12.7f, 260f
    };

    private InputMappingTest() {
    }

    public static int run() {
        System.out.println("---- 输入映射自检（屏幕空间 -> 世界空间）----");
        // PerspectiveCamera.update() 会走 Frustum.update()，里面 Matrix4.prj 是 native 的。
        // 把 gdx 原生库载进来即可；这一步不需要窗口/GL。
        try {
            com.badlogic.gdx.utils.GdxNativesLoader.load();
        } catch (Throwable t) {
            System.out.println("  [跳过] 无法加载 gdx 原生库，" + t);
            return 0;
        }
        int bad = 0;

        Vector3 right = new Vector3();
        Vector3 up = new Vector3();
        Vector3 got = new Vector3();
        Matrix4 view = new Matrix4();

        for (float deg : YAWS) {
            float yaw = deg * com.badlogic.gdx.math.MathUtils.degRad;

            // 用一面真实相机的基向量作为参照
            PerspectiveCamera cam = new PerspectiveCamera(55f, 16f / 9f, 1f);
            float pitch = 50f * com.badlogic.gdx.math.MathUtils.degRad;
            cam.position.set(0f, 20f, 0f);
            cam.direction.set(
                    com.badlogic.gdx.math.MathUtils.sin(yaw) * com.badlogic.gdx.math.MathUtils.cos(pitch),
                    -com.badlogic.gdx.math.MathUtils.sin(pitch),
                    com.badlogic.gdx.math.MathUtils.cos(yaw) * com.badlogic.gdx.math.MathUtils.cos(pitch));
            cam.up.set(0f, 1f, 0f);
            cam.update();
            view.set(cam.view);

            // 视图矩阵第 0 行 = 相机右，第 1 行 = 相机上
            right.set(view.val[Matrix4.M00], 0f, view.val[Matrix4.M02]).nor();
            up.set(view.val[Matrix4.M10], 0f, view.val[Matrix4.M12]).nor();

            float dotRight = 0f;
            float dotUp = 0f;
            InputMapping.screenToWorld(yaw, 1f, 0f, got);   // 按 D
            dotRight = got.x * right.x + got.z * right.z;
            InputMapping.screenToWorld(yaw, 0f, 1f, got);   // 按 W
            dotUp = got.x * up.x + got.z * up.z;

            boolean ok = dotRight > 0.999f && dotUp > 0.999f;
            if (!ok) {
                bad++;
            }
            System.out.printf(java.util.Locale.ROOT,
                    "  %s yaw=%7.1f°  D→右 点积=%+.4f   W→上 点积=%+.4f%s%n",
                    ok ? "[OK]" : "[!!]", deg, dotRight, dotUp,
                    ok ? "" : "   << 方向反了");
        }

        System.out.println(bad == 0
                ? "  输入映射方向全部正确"
                : "  输入映射有 " + bad + " 个朝向是反的");
        return bad;
    }
}
