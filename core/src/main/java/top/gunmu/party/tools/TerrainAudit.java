package top.gunmu.party.tools;

import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;

import top.gunmu.party.core.TerrainType;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapRegistry;
import top.gunmu.party.physics.HeightField;
import top.gunmu.party.render.MeshFactory;

/**
 * 地形网格绕序审计。<b>不需要 OpenGL</b>：直接复用 {@link MeshFactory#collectTerrainTile}
 * 把三角形生成出来，再逐面算几何法线。
 *
 * <p>为什么必须有这个检查：地形是个高度场，所有面的几何法线<b>必须朝上</b>。
 * 一旦绕序写反，法线变成朝下，开启背面剔除后整片地面会在画面上彻底消失 ——
 * 而这一点在编译期、在物理冒烟测试里<b>全都看不出来</b>。
 * 上一版就是带着这个 bug 交付的。
 */
public final class TerrainAudit {

    /** 允许的例外比例（贴着走廊边缘的退化三角形）。 */
    private static final float TOLERANCE = 0.02f;

    private TerrainAudit() {
    }

    public static int run() {
        System.out.println("---- 地形网格绕序审计 ----");
        int bad = 0;
        bad += audit(MapRegistry.RACE_IDS);
        bad += audit(MapRegistry.SURVIVAL_IDS);
        bad += audit(MapRegistry.ARENA_IDS);
        System.out.println(bad == 0
                ? "  地形绕序全部朝上"
                : "  有 " + bad + " 张地图的地形绕序有问题");
        return bad;
    }

    private static int audit(String[] ids) {
        int bad = 0;
        for (String id : ids) {
            MapDef m = MapRegistry.create(id);
            HeightField f = m.field;
            final int s = 1;
            final int span = 96 * s;

            FloatArray v = new FloatArray();
            ShortArray ix = new ShortArray();
            int tris = 0;
            int up = 0;
            int degenerate = 0;
            int maxTileVerts = 0;

            for (TerrainType type : TerrainType.ALL) {
                if (!type.walkable()) {
                    continue;
                }
                for (int tz = 0; tz + s < f.nz; tz += span) {
                    for (int tx = 0; tx + s < f.nx; tx += span) {
                        v.clear();
                        ix.clear();
                        if (!MeshFactory.collectTerrainTile(f, s, type, tx, tz, v, ix)) {
                            continue;
                        }
                        maxTileVerts = Math.max(maxTileVerts,
                                v.size / MeshFactory.FLOATS_PER_VERTEX);
                        short[] idx = ix.toArray();
                        int n = idx.length / 3;
                        for (int t = 0; t < n; t++) {
                            int ia = idx[t * 3] & 0xFFFF;
                            int ib = idx[t * 3 + 1] & 0xFFFF;
                            int ic = idx[t * 3 + 2] & 0xFFFF;
                            float ax = v.items[ia * 8], ay = v.items[ia * 8 + 1];
                            float az = v.items[ia * 8 + 2];
                            float bx = v.items[ib * 8], by = v.items[ib * 8 + 1];
                            float bz = v.items[ib * 8 + 2];
                            float dx = v.items[ic * 8], dy = v.items[ic * 8 + 1];
                            float dz = v.items[ic * 8 + 2];
                            float ux = bx - ax, uy = by - ay, uz = bz - az;
                            float wx = dx - ax, wy = dy - ay, wz = dz - az;
                            // 几何法线 = u × w
                            float gx = uy * wz - uz * wy;
                            float gy = uz * wx - ux * wz;
                            float gz = ux * wy - uy * wx;
                            float len2 = gx * gx + gy * gy + gz * gz;
                            tris++;
                            if (len2 < 1e-9f) {
                                degenerate++;
                            } else if (gy > 0f) {
                                up++;
                            }
                        }
                    }
                }
            }

            float ratio = tris == 0 ? 0f : up / (float) tris;
            boolean ok = tris > 0 && ratio >= 1f - TOLERANCE && maxTileVerts <= 65535;
            String line = String.format(
                    "  %-18s 三角 %7d  朝上 %6.2f%%  退化 %d  单块最多 %d 顶点",
                    id, tris, ratio * 100f, degenerate, maxTileVerts);
            if (ok) {
                System.out.println("  [OK] " + line.trim());
            } else {
                bad++;
                System.out.println("  [!!] " + line.trim()
                        + (ratio < 1f - TOLERANCE ? "  << 绕序反了，地面会被背面剔除" : "")
                        + (maxTileVerts > 65535 ? "  << 单块顶点超 short 上限" : ""));
            }
        }
        return bad;
    }
}
