package top.gunmu.party.tools;

import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;

import top.gunmu.party.GameConfig;
import top.gunmu.party.render.MeshFactory;

/**
 * 程序化几何的自检。**不需要 OpenGL**，直接审顶点与索引数组。
 *
 * <p>存在的理由：这一版出过两个"编译器不报、断言不抓、只有肉眼能看出"的渲染 bug：
 * <ul>
 *   <li>{@code box()} 的顶点位置漏了沿面法线的一项，六个面全落在过原点的平面上 ——
 *       得到的不是立方体而是一堆纸片；</li>
 *   <li>地形与球面的三角形绕序反了，几何法线朝下、顶点法线朝上，
 *       开启背面剔除后整片地面直接消失。</li>
 * </ul>
 * 这两个都能用同一个判据抓出来，对<b>凸的、以原点为中心</b>的网格：
 * <pre>
 *   每个三角形的几何法线（边的叉积）必须与「面心 − 形心」同向
 * </pre>
 * 顺带把包围盒也断言掉——退化几何的包围盒会扁在某个轴上。
 */
public final class PrimitiveTest {

    private static int failures;

    private PrimitiveTest() {
    }

    public static int run() {
        failures = 0;
        System.out.println("---- 程序化几何自检 ----");

        // 单位立方体：三个轴都必须张开 1.0
        check("box", build((v, ix) -> MeshFactory.box(v, ix)),
                1f, 1f, 1f);

        // 单位球
        check("sphere", build((v, ix) -> MeshFactory.sphere(v, ix, 1f, 16, 10)),
                2f, 2f, 2f);

        // 沿 Y 轴的圆柱 r=1 h=2
        check("cylinder", build((v, ix) -> MeshFactory.cylinder(v, ix, 1f, 2f, 16)),
                2f, 2f, 2f);

        // 八面体 r=1
        check("octahedron", build((v, ix) -> MeshFactory.octahedron(v, ix, 1f)),
                2f, 2f, 2f);

        // 碎木片：不要求尺寸，只要求"是个有体积的凸壳"
        check("tetraShard", build((v, ix) -> MeshFactory.tetraShard(v, ix)),
                -1f, -1f, -1f);

        // 贴地圆环：平的（Y 厚度为 0）、外径 2ro，且所有三角形法线都沿 ±Y
        checkRing("ring", build((v, ix) -> MeshFactory.ring(v, ix, 0.8f, 1f, 24)),
                2f, true);

        // 贴地扇形（决赛关普通攻击的范围指示）：
        // 必须水平、顶点法线与几何法线都**朝上** —— 绕序一反，这个扇形就会
        // 像地形那次一样被背面剔除（画面上什么都没有，但编译与冒烟全绿）。
        checkSector("sector", build((v, ix) -> MeshFactory.sector(v, ix,
                GameConfig.ATTACK_HALF_ANGLE, 16)));

        System.out.println(failures == 0
                ? "  几何自检全部通过"
                : "  几何自检失败 " + failures + " 项");
        return failures;
    }

    private interface Builder {
        void build(FloatArray v, ShortArray ix);
    }

    private static final class Mesh {
        float[] v;
        short[] ix;
        int verts;
    }

    private static Mesh build(Builder b) {
        FloatArray v = new FloatArray();
        ShortArray ix = new ShortArray();
        b.build(v, ix);
        Mesh m = new Mesh();
        m.v = new float[v.size];
        System.arraycopy(v.items, 0, m.v, 0, v.size);
        m.ix = ix.toArray();
        m.verts = v.size / MeshFactory.FLOATS_PER_VERTEX;
        return m;
    }

    /**
     * @param ex 期望的包围盒尺寸（x/y/z）。传负数表示不检查该轴。
     */
    private static void check(String name, Mesh m, float ex, float ey, float ez) {
        if (m.verts == 0 || m.ix.length == 0) {
            fail(name, "没有生成任何几何");
            return;
        }

        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        float z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = 0; i < m.verts; i++) {
            int o = i * MeshFactory.FLOATS_PER_VERTEX;
            float px = m.v[o], py = m.v[o + 1], pz = m.v[o + 2];
            x0 = Math.min(x0, px);
            x1 = Math.max(x1, px);
            y0 = Math.min(y0, py);
            y1 = Math.max(y1, py);
            z0 = Math.min(z0, pz);
            z1 = Math.max(z1, pz);
            cx += px;
            cy += py;
            cz += pz;
        }
        cx /= m.verts;
        cy /= m.verts;
        cz /= m.verts;

        boolean ok = true;
        StringBuilder why = new StringBuilder();

        float sx = x1 - x0, sy = y1 - y0, sz = z1 - z0;
        if (ex >= 0f && Math.abs(sx - ex) > 1e-3f) {
            ok = false;
            why.append(String.format(" X 跨度 %.3f≠%.3f（%s）", sx, ex,
                    sx < ex * 0.5f ? "该轴被压扁，位置算漏了" : ""));
        }
        if (ey >= 0f && Math.abs(sy - ey) > 1e-3f) {
            ok = false;
            why.append(String.format(" Y 跨度 %.3f≠%.3f", sy, ey));
        }
        if (ez >= 0f && Math.abs(sz - ez) > 1e-3f) {
            ok = false;
            why.append(String.format(" Z 跨度 %.3f≠%.3f", sz, ez));
        }

        // 逐三角形：几何法线必须朝外；同时和顶点法线同向
        int outside = 0;
        int agree = 0;
        int tris = m.ix.length / 3;
        for (int t = 0; t < tris; t++) {
            int ia = m.ix[t * 3] & 0xFFFF;
            int ib = m.ix[t * 3 + 1] & 0xFFFF;
            int ic = m.ix[t * 3 + 2] & 0xFFFF;
            if (ia >= m.verts || ib >= m.verts || ic >= m.verts) {
                ok = false;
                why.append(" 索引越界");
                break;
            }
            final int st = MeshFactory.FLOATS_PER_VERTEX;
            float ax = m.v[ia * st], ay = m.v[ia * st + 1], az = m.v[ia * st + 2];
            float bx = m.v[ib * st], by = m.v[ib * st + 1], bz = m.v[ib * st + 2];
            float dx = m.v[ic * st], dy = m.v[ic * st + 1], dz = m.v[ic * st + 2];
            float ux = bx - ax, uy = by - ay, uz = bz - az;
            float wx = dx - ax, wy = dy - ay, wz = dz - az;
            float gx = uy * wz - uz * wy;
            float gy = uz * wx - ux * wz;
            float gz = ux * wy - uy * wx;
            float gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            if (gl < 1e-6f) {
                continue; // 退化三角形（球的极点）不计
            }
            gx /= gl;
            gy /= gl;
            gz /= gl;

            float fx = (ax + bx + dx) / 3f - cx;
            float fy = (ay + by + dy) / 3f - cy;
            float fz = (az + bz + dz) / 3f - cz;
            if (gx * fx + gy * fy + gz * fz > 0f) {
                outside++;
            }
            // 与三角形第一个顶点的法线比较
            float vnx = m.v[ia * st + 3], vny = m.v[ia * st + 4], vnz = m.v[ia * st + 5];
            if (gx * vnx + gy * vny + gz * vnz > 0f) {
                agree++;
            }
        }
        if (tris > 0 && outside * 4 < tris * 3) {
            ok = false;
            why.append(String.format(" 绕序反了：只有 %d/%d 个三角形法线朝外", outside, tris));
        }
        if (tris > 0 && agree * 4 < tris * 3) {
            ok = false;
            why.append(String.format(" 顶点法线与几何法线相反（%d/%d）", agree, tris));
        }

        String box = String.format("x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f] %d顶点 %d面",
                x0, x1, y0, y1, z0, z1, m.verts, tris);
        if (ok) {
            System.out.println("  [OK] " + name + "  " + box);
        } else {
            failures++;
            System.out.println("  [!!] " + name + "  " + box + "\n        " + why);
        }
    }


    /**
     * 平环专用检查：包围盒必须是 2ro × 0 × 2ro，且每个三角形的几何法线都沿 ±Y。
     * 凸性判据在这里不适用（环既不凸也不以形心为"外侧"）。
     */
    private static void checkRing(String name, Mesh m, float expectedSpan, boolean flat) {
        if (m.verts == 0 || m.ix.length == 0) {
            fail(name, "没有生成任何几何");
            return;
        }
        final int st = MeshFactory.FLOATS_PER_VERTEX;
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        float z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int i = 0; i < m.verts; i++) {
            float px = m.v[i * st], py = m.v[i * st + 1], pz = m.v[i * st + 2];
            x0 = Math.min(x0, px); x1 = Math.max(x1, px);
            y0 = Math.min(y0, py); y1 = Math.max(y1, py);
            z0 = Math.min(z0, pz); z1 = Math.max(z1, pz);
        }
        boolean ok = true;
        StringBuilder why = new StringBuilder();
        if (Math.abs((x1 - x0) - expectedSpan) > 1e-3f) {
            ok = false;
            why.append(String.format(java.util.Locale.ROOT, " X 跨度 %.3f≠%.3f",
                    x1 - x0, expectedSpan));
        }
        if (Math.abs((z1 - z0) - expectedSpan) > 1e-3f) {
            ok = false;
            why.append(String.format(java.util.Locale.ROOT, " Z 跨度 %.3f≠%.3f",
                    z1 - z0, expectedSpan));
        }
        if (flat && Math.abs(y1 - y0) > 1e-4f) {
            ok = false;
            why.append(String.format(java.util.Locale.ROOT, " 不平（Y 厚度 %.4f）", y1 - y0));
        }
        // 环是空心的：所有顶点到中心的距离必须在 [ri, ro] 内
        float ri = 0.8f;
        for (int i = 0; i < m.verts; i++) {
            float r = (float) Math.hypot(m.v[i * st], m.v[i * st + 2]);
            if (r < ri - 1e-3f || r > 1f + 1e-3f) {
                ok = false;
                why.append(String.format(java.util.Locale.ROOT, " 半径 %.3f 越界", r));
                break;
            }
        }
        // 法线必须沿 ±Y
        int tris = m.ix.length / 3;
        int vertical = 0;
        for (int t = 0; t < tris; t++) {
            int ia = m.ix[t * 3] & 0xFFFF, ib = m.ix[t * 3 + 1] & 0xFFFF,
                    ic = m.ix[t * 3 + 2] & 0xFFFF;
            float ax = m.v[ia * st], ay = m.v[ia * st + 1], az = m.v[ia * st + 2];
            float bx = m.v[ib * st], by = m.v[ib * st + 1], bz = m.v[ib * st + 2];
            float dx = m.v[ic * st], dy = m.v[ic * st + 1], dz = m.v[ic * st + 2];
            float ux = bx - ax, uy = by - ay, uz = bz - az;
            float wx = dx - ax, wy = dy - ay, wz = dz - az;
            float gx = uy * wz - uz * wy;
            float gy = uz * wx - ux * wz;
            float gz = ux * wy - uy * wx;
            float len = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            if (len < 1e-6f) {
                continue;
            }
            if (Math.abs(gy) / len > 0.999f) {
                vertical++;
            }
        }
        if (tris > 0 && vertical < tris) {
            ok = false;
            why.append(" 有三角形不水平（" + vertical + "/" + tris + "）");
        }
        String box = String.format(java.util.Locale.ROOT,
                "x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f] %d顶点 %d面",
                x0, x1, y0, y1, z0, z1, m.verts, tris);
        if (ok) {
            System.out.println("  [OK] " + name + "  " + box);
        } else {
            failures++;
            System.out.println("  [!!] " + name + "  " + box + "\n        " + why);
        }
    }

    /**
     * 贴地扇形专用检查：水平（Y 厚度 0）、所有三角形法线朝 <b>+Y</b>、
     * 顶点法线也都是 +Y，且包围盒与"半径 1、半角 halfDeg"吻合。
     *
     * <p>凸性判据在这里同样不适用（扇形以顶点为中心，不是凸壳），所以单独写一条。
     * 真正要守的是**朝上**：几何法线朝下时，运行时背面剔除会让整个扇形消失，
     * 而编译、物理冒烟、地图校验全都不会报错。
     */
    private static void checkSector(String name, Mesh m) {
        if (m.verts == 0 || m.ix.length == 0) {
            fail(name, "没有生成任何几何");
            return;
        }
        final int st = MeshFactory.FLOATS_PER_VERTEX;
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        float z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        int vertUp = 0;
        for (int i = 0; i < m.verts; i++) {
            float px = m.v[i * st], py = m.v[i * st + 1], pz = m.v[i * st + 2];
            x0 = Math.min(x0, px); x1 = Math.max(x1, px);
            y0 = Math.min(y0, py); y1 = Math.max(y1, py);
            z0 = Math.min(z0, pz); z1 = Math.max(z1, pz);
            if (m.v[i * st + 4] > 0.99f) {
                vertUp++;
            }
        }
        boolean ok = true;
        StringBuilder why = new StringBuilder();
        if (Math.abs(y1 - y0) > 1e-4f) {
            ok = false;
            why.append(String.format(java.util.Locale.ROOT, " 不平（Y 厚度 %.4f）", y1 - y0));
        }
        if (vertUp < m.verts) {
            ok = false;
            why.append(" 顶点法线不是全朝上（" + vertUp + "/" + m.verts + "）");
        }
        float expectX = 2f * (float) Math.sin(Math.toRadians(GameConfig.ATTACK_HALF_ANGLE));
        if (Math.abs((x1 - x0) - expectX) > 1e-3f) {
            ok = false;
            why.append(String.format(java.util.Locale.ROOT, " X 跨度 %.3f≠%.3f（半角不对）",
                    x1 - x0, expectX));
        }
        int tris = m.ix.length / 3;
        int up = 0;
        for (int t = 0; t < tris; t++) {
            int ia = m.ix[t * 3] & 0xFFFF, ib = m.ix[t * 3 + 1] & 0xFFFF,
                    ic = m.ix[t * 3 + 2] & 0xFFFF;
            float ax = m.v[ia * st], ay = m.v[ia * st + 1], az = m.v[ia * st + 2];
            float bx = m.v[ib * st], by = m.v[ib * st + 1], bz = m.v[ib * st + 2];
            float dx = m.v[ic * st], dy = m.v[ic * st + 1], dz = m.v[ic * st + 2];
            float ux = bx - ax, uy = by - ay, uz = bz - az;
            float wx = dx - ax, wy = dy - ay, wz = dz - az;
            float gx = uy * wz - uz * wy;
            float gy = uz * wx - ux * wz;
            float gz = ux * wy - uy * wx;
            float len = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            if (len < 1e-6f) {
                continue;
            }
            if (gy / len > 0.99f) {
                up++;
            }
        }
        if (tris > 0 && up < tris) {
            ok = false;
            why.append(" 绕序反了：只有 " + up + "/" + tris + " 个三角形法线朝上"
                    + "（朝下会被背面剔除，画面上什么都没有）");
        }
        String box = String.format(java.util.Locale.ROOT,
                "x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f] %d顶点 %d面",
                x0, x1, y0, y1, z0, z1, m.verts, tris);
        if (ok) {
            System.out.println("  [OK] " + name + "  " + box);
        } else {
            failures++;
            System.out.println("  [!!] " + name + "  " + box + "\n        " + why);
        }
    }

    private static void fail(String name, String why) {
        failures++;
        System.out.println("  [!!] " + name + "  " + why);
    }
}