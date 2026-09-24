package top.gunmu.party.physics;

import com.badlogic.gdx.math.Vector3;

import top.gunmu.party.core.TerrainType;

/**
 * 高度场。地形采样、法线与材质的唯一来源。
 *
 * <p>高度用双线性插值（连续），材质取最近采样点（离散）。
 * {@code walkable == false} 的格子是深渊：物理上无地面，渲染上仍给出一个可视高度。
 */
public final class HeightField {

    public final float minX;
    public final float minZ;
    public final float cell;
    public final int nx;
    public final int nz;

    /** 渲染/采样高度（恒为有限值）。 */
    public final float[] h;
    /** 是否可行走。 */
    public final boolean[] walkable;
    /** {@link TerrainType} 的 ordinal。 */
    public final byte[] mat;

    public final float maxX;
    public final float maxZ;

    public HeightField(float minX, float minZ, float sizeX, float sizeZ, float cell) {
        this.minX = minX;
        this.minZ = minZ;
        this.cell = cell;
        this.nx = Math.max(2, (int) Math.ceil(sizeX / cell) + 1);
        this.nz = Math.max(2, (int) Math.ceil(sizeZ / cell) + 1);
        this.h = new float[nx * nz];
        this.walkable = new boolean[nx * nz];
        this.mat = new byte[nx * nz];
        this.maxX = minX + (nx - 1) * cell;
        this.maxZ = minZ + (nz - 1) * cell;
    }

    public int index(int ix, int iz) {
        return iz * nx + ix;
    }

    public float xAt(int ix) {
        return minX + ix * cell;
    }

    public float zAt(int iz) {
        return minZ + iz * cell;
    }

    public int cx(float x) {
        int i = (int) ((x - minX) / cell);
        return i < 0 ? 0 : (i > nx - 1 ? nx - 1 : i);
    }

    public int cz(float z) {
        int i = (int) ((z - minZ) / cell);
        return i < 0 ? 0 : (i > nz - 1 ? nz - 1 : i);
    }

    public boolean inBounds(float x, float z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public void set(int ix, int iz, float height, boolean walk, TerrainType type) {
        int i = index(ix, iz);
        h[i] = height;
        walkable[i] = walk;
        mat[i] = (byte) type.ordinal();
    }

    /** 双线性插值高度。 */
    public float heightAt(float x, float z) {
        float fx = (x - minX) / cell;
        float fz = (z - minZ) / cell;
        int ix = (int) Math.floor(fx);
        int iz = (int) Math.floor(fz);
        if (ix < 0) {
            ix = 0;
        }
        if (iz < 0) {
            iz = 0;
        }
        if (ix > nx - 2) {
            ix = nx - 2;
        }
        if (iz > nz - 2) {
            iz = nz - 2;
        }
        float tx = fx - ix;
        float tz = fz - iz;
        tx = tx < 0f ? 0f : (tx > 1f ? 1f : tx);
        tz = tz < 0f ? 0f : (tz > 1f ? 1f : tz);

        int i00 = index(ix, iz);
        int i10 = i00 + 1;
        int i01 = i00 + nx;
        int i11 = i01 + 1;

        float h00 = h[i00];
        float h10 = h[i10];
        float h01 = h[i01];
        float h11 = h[i11];

        float a = h00 + (h10 - h00) * tx;
        float b = h01 + (h11 - h01) * tx;
        return a + (b - a) * tz;
    }

    /** 该点是否有地面（四舍五入到最近采样点判定，避免边界抖动）。 */
    public boolean walkableAt(float x, float z) {
        if (!inBounds(x, z)) {
            return true; // 越界交给"掉出地图"逻辑处理，这里不制造假深渊
        }
        return walkable[index(cx(x), cz(z))];
    }

    public TerrainType materialAt(float x, float z) {
        if (!inBounds(x, z)) {
            return TerrainType.GRASS;
        }
        return TerrainType.ALL[mat[index(cx(x), cz(z))] & 0xFF];
    }

    /** 用中心差分求地面法线（单位向量，y 恒为正）。 */
    public void normalAt(float x, float z, Vector3 out) {
        float e = cell * 0.5f;
        float hl = heightAt(x - e, z);
        float hr = heightAt(x + e, z);
        float hd = heightAt(x, z - e);
        float hu = heightAt(x, z + e);
        float dx = (hr - hl) / (2f * e);
        float dz = (hu - hd) / (2f * e);
        out.set(-dx, 1f, -dz).nor();
    }

    public float minHeight() {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < h.length; i++) {
            m = Math.min(m, h[i]);
        }
        return m;
    }

    public float maxHeight() {
        float m = -Float.MAX_VALUE;
        for (int i = 0; i < h.length; i++) {
            m = Math.max(m, h[i]);
        }
        return m;
    }

    /** 冒烟测试用：整片高度场的统计信息。 */
    public String stats() {
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        int voids = 0;
        for (int i = 0; i < h.length; i++) {
            lo = Math.min(lo, h[i]);
            hi = Math.max(hi, h[i]);
            if (!walkable[i]) {
                voids++;
            }
        }
        return String.format("grid=%dx%d cell=%.2f h=[%.2f..%.2f] void=%d/%d",
                nx, nz, cell, lo, hi, voids, h.length);
    }
}
