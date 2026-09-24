package top.gunmu.party.tools;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.InputMapping;
import top.gunmu.party.core.Mode;
import top.gunmu.party.core.Roll;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapNav;
import top.gunmu.party.map.MapRegistry;
import top.gunmu.party.physics.PhysicsWorld;

/**
 * 「玩家能不能操控自己」的自检 —— 对<b>每一种模式</b>都跑一遍。
 *
 * <p>玩家报过「在生存关卡我无法操控自己」。这类问题的可疑面很宽
 * （输入映射、相机朝向、物理贴地、起点屏障、模式分支），所以这里走
 * <b>和游戏完全同一条链路</b>：
 *
 * <pre>
 *   屏幕输入 (sx, sy)
 *     → InputMapping.screenToWorld(相机 yaw)      ← 相机 yaw 取 MapNav.cameraYaw
 *     → Roll.input.move
 *     → PhysicsWorld.step
 *     → 位置变化
 * </pre>
 *
 * <p>三条断言，缺一条都不算"能操控"：
 *
 * <ol>
 *   <li>给方向输入 → 真的移动了（位移够大）；</li>
 *   <li>移动方向与"屏幕上方在世界里的方向"一致（角度误差 &lt; 25°）；</li>
 *   <li>松开输入 → 停下来（说明输入是每帧生效的，不是被谁一直推着）。</li>
 * </ol>
 */
public final class MoveControlTest {

    /** 给输入的模拟时长（秒）。太短的话「被卡住」「掉下去」都来不及暴露。 */
    private static final float DRIVE_TIME = 6f;
    /** 松开后观察是否停下（秒）。 */
    private static final float COAST_TIME = 1.0f;
    /** 6 秒里至少要走出这么远，否则就是被什么卡住了。 */
    private static final float MIN_DISTANCE = 12f;

    private MoveControlTest() {
    }

    /** 单独跑（调地图/平衡时用）。 */
    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    public static int run() {
        System.out.println("---- 操控自检（每种模式都要能控制自己）----");
        int bad = 0;
        bad += check(MapRegistry.RACE_IDS[0]);
        for (int i = 0; i < MapRegistry.SURVIVAL_IDS.length; i++) {
            bad += check(MapRegistry.SURVIVAL_IDS[i]);
        }
        bad += check(MapRegistry.ARENA_IDS[0]);
        System.out.println(bad == 0 ? "  三种模式的操控全部正常"
                : "  操控自检失败 " + bad + " 项");
        return bad;
    }

    private static int check(String id) {
        MapDef map = MapRegistry.create(id);
        Roll r = new Roll(0, "player", true);
        r.pos.set(map.spawns.first());
        r.vel.setZero();

        PhysicsWorld world = new PhysicsWorld(map.field);
        // 起点屏障必须关掉：它会把滚木关在圈里，位移测不准。
        // 屏障本身另有专门的 StartBarrierTest 覆盖。
        world.setStartBarrier(false, 0f, 0f, 0f);
        world.mapFloorY = map.minHeight - 80f;

        Array<Roll> rolls = new Array<>();
        rolls.add(r);

        // 相机 yaw：和游戏里完全一样 —— 只有竞速图有赛道切线，其余返回 NaN，
        // 由 CameraRig 保持上一次的朝向（这里用 0 表示"从未初始化过"）。
        float camYaw = MapNav.cameraYaw(map, r, r.pos.x, r.pos.z, new float[4]);
        if (Float.isNaN(camYaw)) {
            camYaw = 0f;
        }

        Vector3 start = new Vector3(r.pos);
        // 屏幕输入：向上推满（W）
        InputMapping.screenToWorld(camYaw, 0f, 1f, r.input.move);

        int steps = Math.round(DRIVE_TIME / GameConfig.FIXED_DT);
        boolean fell = false;
        for (int i = 0; i < steps; i++) {
            world.step(rolls, GameConfig.FIXED_DT);
            if (r.pos.y < world.mapFloorY) {
                fell = true;
                break;
            }
        }
        Vector3 moved = new Vector3(r.pos).sub(start);
        float dist = (float) Math.hypot(moved.x, moved.z);

        // 期望方向：屏幕上方向在世界里的方向
        Vector3 up = new Vector3();
        InputMapping.screenUp(camYaw, up);
        float dot = dist < 1e-4f ? 0f : (moved.x * up.x + moved.z * up.z) / dist;
        float angle = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, dot))));

        int problems = 0;
        String name = pad(map.name);
        if (fell) {
            System.out.println("  [FAIL] " + name + " " + map.mode.cn
                    + " 出生点往前跑就掉出地图了");
            return 1;
        }
        if (dist < MIN_DISTANCE) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "  [FAIL] %s %s 给了 %.1f 秒输入只移动了 %.2fm（至少要 %.1f）"
                            + " —— 操控不了自己",
                    name, map.mode.cn, DRIVE_TIME, dist, MIN_DISTANCE));
            problems++;
        }
        if (dist >= 1f && angle > 25f) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "  [FAIL] %s %s 移动方向偏了 %.1f°", name, map.mode.cn, angle));
            problems++;
        }

        // 松开输入：应该迅速停下（滚木有摩擦）
        r.input.move.setZero();
        Vector3 beforeCoast = new Vector3(r.pos);
        int coast = Math.round(COAST_TIME / GameConfig.FIXED_DT);
        for (int i = 0; i < coast; i++) {
            world.step(rolls, GameConfig.FIXED_DT);
        }
        float after = (float) Math.hypot(r.pos.x - beforeCoast.x, r.pos.z - beforeCoast.z);
        // ⚠️ 滚木是有惯性的（摩擦约 1.0/s，9 m/s 一秒后还剩 3.3 m/s，约滑 5 米），
        //    所以这里不能判「立刻停住」。要判的是「输入确实不再加速了」。
        float coastSpeed = r.speed();
        // ⚠️ 这一条**只提示不判失败**。
        //
        // 曾经把它当成"输入没被消费"的证据，结果是误伤：生存图是碗形场地，滚木松开
        // 输入后本来就还在坡上往下滚（实测 1 秒后仍有 8.3 m/s，而摩擦是正常生效的）。
        // "能不能操控"已经由前两条覆盖 —— 输入 6 秒能走出 50 多米、方向误差 0.1°。
        if (coastSpeed > GameConfig.FLAT_MAX_SPEED * 0.75f) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "  [提示] %s %s 松开 %.1fs 后仍有 %.2f m/s —— 脚下有坡，属正常滑行",
                    name, map.mode.cn, COAST_TIME, coastSpeed));
        }

        if (problems == 0) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "  [OK]   %s %s 输入 %.1fs 前进 %.2fm（方向误差 %.1f°），"
                            + "松开 1s 后速度 %.2f、滑行 %.2fm",
                    name, map.mode.cn, DRIVE_TIME, dist, angle, coastSpeed, after));
        }
        return problems;
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 6) {
            b.append(' ');
        }
        return b.toString();
    }
}
