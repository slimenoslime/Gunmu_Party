package top.gunmu.party.map;

import java.util.Locale;

import top.gunmu.party.core.Mode;
import top.gunmu.party.core.Roll;

/**
 * 界面与相机需要的"赛道相关查询"的<b>唯一实现</b>。
 *
 * <p>为什么单独抽出来：这些查询是**按模式有条件成立**的 —— 只有 RACE 图才有
 * {@code route} 折线路径，生存图/竞技图没有。以前这类判断散在 {@code LevelScreen} 里，
 * 结果漏了一处，在「涨水盆地」（生存图）上直接
 * {@code NullPointerException: Cannot read field "totalLength" because route is null}
 * 把整个进程干掉了。
 *
 * <p>抽成**纯函数**（不碰 GL、不依赖 Screen）之后，就能在无头自检里对 10 张图逐一验证，
 * 这类崩溃不会再漏到玩家面前。断言见 {@code tools/MapNavTest}。
 */
public final class MapNav {

    private MapNav() {
    }

    /**
     * 相机该朝哪边：玩家所在处的赛道切线朝向。
     *
     * @return 弧度；**非竞速图返回 {@code NaN}**（表示"不要按切线看，用默认视角"）
     */
    public static float cameraYaw(MapDef map, Roll player, float focusX, float focusZ,
                                  float[] scratch) {
        if (map == null || !map.hasRoute()) {
            return Float.NaN;
        }
        Route route = map.route;
        float arc = 0f;
        if (player != null && !player.eliminated) {
            arc = player.progress;
        } else {
            // 玩家出局（或还没出生）就跟镜头盯的位置走
            route.nearest(focusX, focusZ, scratch);
            arc = scratch[3];
        }
        return route.yawAt(arc);
    }

    /**
     * 那行现场诊断的完整文本。
     *
     * <p><b>抽出来的原因</b>：崩就崩在这一行上 ——
     * {@code LevelScreen.logDiagnostics} 里直接读了 route 的总长，
     * 一进生存图（涨水盆地）就是
     * {@code NullPointerException: Cannot read field "totalLength" because route is null}，
     * 整个进程当场死掉。它在 Screen 里，**要开窗口才跑得到**，
     * 单元测试够不着；收成纯函数之后就能对 10 张图逐一验。
     */
    public static String diagLine(MapDef map, Roll player, float stageClock,
                                  float cx, float cy, float cz,
                                  float dx, float dy, float dz,
                                  float fx, float fy, float fz) {
        if (map == null) {
            return "地图未装载";
        }
        float px = player == null ? 0f : player.pos.x;
        float py = player == null ? 0f : player.pos.y;
        float pz = player == null ? 0f : player.pos.z;
        float ground = player == null ? 0f : map.field.heightAt(px, pz);
        return String.format(Locale.ROOT,
                "cam(%.1f,%.1f,%.1f) dir(%.2f,%.2f,%.2f) focus(%.1f,%.1f,%.1f) "
                        + "player(%.1f,%.1f,%.1f) 地面h=%.1f 地图=%s %s",
                cx, cy, cz, dx, dy, dz, fx, fy, fz, px, py, pz, ground,
                map.name, progressText(map, player, stageClock));
    }

    /**
     * 诊断行里的"进度"字段。按模式给不同的量，**任何模式都不会 NPE**。
     *
     * <p>竞速看赛程米数，生存看已经活了多久，竞技看人头 —— 硬把非竞速图也说成
     * "进度 x/y" 只会误导人。
     */
    public static String progressText(MapDef map, Roll player, float stageClock) {
        if (map == null) {
            return "—";
        }
        if (map.hasRoute()) {
            float p = player == null ? 0f : player.progress;
            return String.format(Locale.ROOT, "进度 %.0f/%.0f", p, map.routeLength());
        }
        if (map.mode == Mode.SURVIVAL) {
            return String.format(Locale.ROOT, "生存 %.0fs", Math.max(0f, stageClock));
        }
        int kills = player == null ? 0 : player.kills;
        return "击杀 " + kills;
    }
}
