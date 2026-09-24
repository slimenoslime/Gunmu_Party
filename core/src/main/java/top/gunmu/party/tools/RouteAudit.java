package top.gunmu.party.tools;

import top.gunmu.party.GameConfig;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapRegistry;
import top.gunmu.party.map.TrackProgress;

/**
 * 进度判定审计：<b>掉进虚空不许读到"前沿"的进度</b>。
 *
 * <p>玩家报的 bug：「之字断崖跳到虚空里时能直接读到下面的存档点和通关区域，
 * 因为销毁不是瞬间销毁的」。
 *
 * <p>根因：进度原来是 {@code route.nearest(x, z)} 算的 —— <b>只看水平坐标，完全不看高度</b>。
 * 折返型赛道在俯视图上两段路会靠得很近，脚下一空掉进虚空时最近点会跳到"更靠后"的那一段，
 * 弧长瞬间跳几百米；而坠落判定要等掉够几米才生效，这一小段时间足够把存档点与终点
 * 全部误触发。（完整推导见 {@link TrackProgress} 的类注释。）
 *
 * <p>审计直接复用运行时那份 {@link TrackProgress}（不是另写一份验算，见决策 D36），
 * 对每张竞速图做两件事：
 *
 * <ol>
 *   <li><b>模拟坠落</b>：取每个虚空格子，从"它旁边的正常路面高度"起往下落 20 米，
 *       逐步询问弧长；只要中途读到的弧长比旁边路面的弧长超出容差，就算"能偷到进度"。</li>
 *   <li><b>反向检查</b>：每张图所有可行走格子在正常站姿（贴地）下都必须能读到弧长，
 *       否则闸门收得太紧，会把人卡在半路推不动进度。</li>
 * </ol>
 */
public final class RouteAudit {

    /** 允许的"虚空比旁边高"的进度差（米）。 */
    private static final float TOLERANCE = 8f;
    /** 邻居搜索半径（米）。 */
    private static final float NEIGHBOR_RADIUS = 15f;
    /** 模拟坠落的高度跨度（米）与步长（米）。 */
    private static final float FALL_SPAN = 20f;
    private static final float FALL_STEP = 2f;

    private RouteAudit() {
    }

    public static int run() {
        System.out.println("---- 进度判定审计（掉进虚空不许偷进度）----");
        int bad = 0;
        for (String id : MapRegistry.RACE_IDS) {
            MapDef m = MapRegistry.create(id);
            if (m.route == null || m.route.totalLength <= 0f) {
                System.out.println("  [FAIL] " + id + " 没有路径");
                bad++;
                continue;
            }
            bad += auditMap(m);
        }
        System.out.println(bad == 0
                ? "  全部竞速图：虚空不会偷到进度，路面全部可读"
                : "  进度判定审计失败 " + bad + " 项");
        return bad;
    }

    private static int auditMap(MapDef m) {
        TrackProgress track = new TrackProgress(m);
        int nx = m.field.nx;
        int nz = m.field.nz;
        float cell = m.field.cell;
        int cells = nx * nz;

        // 可行走格子的自报弧长（贴地站姿，即滚木中心 = 地面 + LOG_RADIUS）
        float[] walkArc = new float[cells];
        int walkCount = 0;
        int walkUnreadable = 0;
        for (int iz = 0; iz < nz; iz++) {
            for (int ix = 0; ix < nx; ix++) {
                int i = m.field.index(ix, iz);
                if (!m.field.walkable[i]) {
                    walkArc[i] = Float.NaN;
                    continue;
                }
                walkCount++;
                float arc = track.arcAt(m.field.xAt(ix),
                        m.field.heightAt(m.field.xAt(ix), m.field.zAt(iz)) + GameConfig.LOG_RADIUS,
                        m.field.zAt(iz));
                walkArc[i] = arc;
                if (arc < 0f) {
                    walkUnreadable++;
                }
            }
        }

        int bad = 0;
        if (walkCount > 0 && walkUnreadable > 0) {
            float rate = walkUnreadable * 100f / walkCount;
            System.out.println("  [FAIL] " + pad(m.name) + " 可行走格里有 "
                    + walkUnreadable + "/" + walkCount + " 格（" + fmt(rate)
                    + "%）读不到弧长 —— 闸门太紧，会把人卡在半路");
            bad++;
        } else {
            System.out.println("  [OK]   " + pad(m.name) + " 可行走 " + walkCount
                    + " 格全部可读弧长");
        }

        // 模拟坠落
        int radiusCells = Math.max(1, (int) Math.ceil(NEIGHBOR_RADIUS / cell));
        int offenders = 0;
        float worstGain = 0f;
        float worstX = 0f;
        float worstZ = 0f;
        float worstNear = 0f;
        float worstRead = 0f;
        for (int iz = 0; iz < nz; iz++) {
            for (int ix = 0; ix < nx; ix++) {
                int i = m.field.index(ix, iz);
                if (m.field.walkable[i]) {
                    continue;
                }
                float x = m.field.xAt(ix);
                float z = m.field.zAt(iz);

                // 旁边正常路面的最大弧长（用哪一段的弧长当参照都取最大的那个）
                float nearArc = -1f;
                for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                    int jz = iz + dz;
                    if (jz < 0 || jz >= nz) {
                        continue;
                    }
                    for (int dx = -radiusCells; dx <= radiusCells; dx++) {
                        int jx = ix + dx;
                        if (jx < 0 || jx >= nx) {
                            continue;
                        }
                        float a = walkArc[m.field.index(jx, jz)];
                        if (!Float.isNaN(a) && a >= 0f && a > nearArc) {
                            nearArc = a;
                        }
                    }
                }
                if (nearArc < 0f) {
                    continue; // 四周没有正常路面，没有参照
                }

                // 从旁边路面的高度开始往下落
                float startSurface = m.field.heightAt(x, z);
                if (startSurface < -1e4f) {
                    continue;
                }
                float top = startSurface + GameConfig.LOG_RADIUS + 2f;
                for (float y = top; y > top - FALL_SPAN; y -= FALL_STEP) {
                    float arc = track.arcAt(x, y, z);
                    if (arc < 0f) {
                        continue;
                    }
                    float gain = arc - nearArc;
                    if (gain > worstGain) {
                        worstGain = gain;
                        worstX = x;
                        worstZ = z;
                        worstNear = nearArc;
                        worstRead = arc;
                    }
                    if (gain > TOLERANCE) {
                        offenders++;
                        break; // 这一格已经算不合格，不必继续往下落
                    }
                }
            }
        }

        if (offenders > 0) {
            System.out.println("  [FAIL] " + pad(m.name) + " 有 " + offenders
                    + " 个虚空格子掉下去能偷到进度，最多偷 " + fmt(worstGain) + "m");
            System.out.println("         最差位置 (" + fmt(worstX) + ", " + fmt(worstZ) + ")"
                    + " 旁边路面 " + fmt(worstNear) + "m，掉下去读到 "
                    + fmt(worstRead) + "m，全图 " + fmt(m.route.totalLength) + "m");
            bad++;
        } else {
            System.out.println("  [OK]   " + pad(m.name) + " 虚空格子全部读不到进度"
                    + "（最大可疑差 " + fmt(worstGain) + "m，容差 " + fmt(TOLERANCE) + "m）");
        }
        return bad;
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 10) {
            b.append(' ');
        }
        return b.toString();
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
