package top.gunmu.party.map;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;

import top.gunmu.party.core.TerrainType;
import top.gunmu.party.physics.HeightField;
import top.gunmu.party.physics.StaticBody;

/**
 * 由地图数据生成高度场。
 *
 * <p>地形完全由代码生成、固定种子，因此不依赖任何美术资源（docs/05 §5.3）。
 */
public final class TerrainBuilder {

    /** 竞技/生存场的形状。 */
    public enum Shape {
        FLAT("平地"),
        BOWL("碗形"),
        FUNNEL("阶梯漏斗"),
        MESA("中央高台"),
        RIDGE("双坡脊");

        public final String cn;

        Shape(String cn) {
            this.cn = cn;
        }
    }

    public static final float CELL = 1.0f;
    /** 路面外多少米开始是深渊。 */
    public static final float CHASM_DROP = 5.5f;

    private TerrainBuilder() {
    }

    // ================================================================== 赛道路径

    public static HeightField fromRoute(Route route) {
        route.build();
        float maxHalf = 6f;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < route.nodeCount(); i++) {
            Route.Node n = route.node(i);
            float w = n.halfWidth + n.shoulder;
            maxHalf = Math.max(maxHalf, w);
            minX = Math.min(minX, n.x - w);
            maxX = Math.max(maxX, n.x + w);
            minZ = Math.min(minZ, n.z - w);
            maxZ = Math.max(maxZ, n.z + w);
        }
        float margin = maxHalf + 16f;
        minX -= margin;
        minZ -= margin;
        maxX += margin;
        maxZ += margin;

        HeightField f = new HeightField(minX, minZ, maxX - minX, maxZ - minZ, CELL);
        float[] near = new float[4];

        for (int iz = 0; iz < f.nz; iz++) {
            float z = f.zAt(iz);
            for (int ix = 0; ix < f.nx; ix++) {
                float x = f.xAt(ix);
                route.nearest(x, z, near);
                int seg = (int) near[0];
                float t = near[1];
                float lat = near[2];
                Route.Node n0 = route.node(seg);
                Route.Node n1 = route.node(seg == route.segmentCount() ? seg : seg + 1);
                if (seg + 1 >= route.nodeCount()) {
                    n1 = n0;
                }
                float hw = MathUtils.lerp(n0.halfWidth, n1.halfWidth, t);
                float sh = MathUtils.lerp(n0.shoulder, n1.shoulder, t);
                float y = MathUtils.lerp(n0.y, n1.y, t);
                float bank = MathUtils.lerp(n0.bank, n1.bank, t);
                TerrainType type = t < 0.5f ? n0.type : n1.type;

                float ad = Math.abs(lat);
                float sign = lat >= 0f ? 1f : -1f;
                float n = noise(x, z);

                if (ad <= hw) {
                    float h = y + bank * (lat / Math.max(0.001f, hw)) + n * 0.28f;
                    f.set(ix, iz, h, true, type);
                } else if (ad <= hw + sh) {
                    float h = y + bank * sign - (ad - hw) * 0.42f + n * 0.45f;
                    TerrainType shoulderType = type == TerrainType.ICE
                            ? TerrainType.ROCK
                            : (type == TerrainType.GRASS ? TerrainType.SAND : type);
                    f.set(ix, iz, h, true, shoulderType);
                } else {
                    float h = y - CHASM_DROP - (ad - hw - sh) * 0.7f + n * 0.6f;
                    f.set(ix, iz, h, false, TerrainType.VOID);
                }
            }
        }
        return f;
    }

    /** 沿走廊两侧生成护栏（只在该节点标记了 wall 的地方）。 */
    public static void buildWalls(Route route, Array<StaticBody> out) {
        for (int i = 0; i < route.segmentCount(); i++) {
            Route.Node a = route.node(i);
            Route.Node b = route.node(i + 1);
            float dx = b.x - a.x;
            float dz = b.z - a.z;
            float len = (float) Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-3f) {
                continue;
            }
            float ux = dx / len;
            float uz = dz / len;
            float yaw = MathUtils.atan2(ux, uz);
            int steps = Math.max(1, (int) (len / 4f));
            for (int s = 0; s < steps; s++) {
                float t = (s + 0.5f) / steps;
                float cx = a.x + dx * t;
                float cz = a.z + dz * t;
                float cy = a.y + (b.y - a.y) * t;
                float hw = a.halfWidth + (b.halfWidth - a.halfWidth) * t;
                float sh = a.shoulder + (b.shoulder - a.shoulder) * t;
                float off = hw + sh;
                // 横向单位向量
                float nx = -uz;
                float nz = ux;
                if (a.wallLeft || b.wallLeft) {
                    out.add(new StaticBody(cx + nx * off, cy + 1.1f, cz + nz * off,
                            2.2f, 1.1f, 0.35f, yaw));
                }
                if (a.wallRight || b.wallRight) {
                    out.add(new StaticBody(cx - nx * off, cy + 1.1f, cz - nz * off,
                            2.2f, 1.1f, 0.35f, yaw));
                }
            }
        }
    }

    // ================================================================== 竞技场

    public static HeightField fromArea(float cx, float cz, float radius, Shape shape,
                                       float base, float param, TerrainType type,
                                       float roughness) {
        float extent = radius + 14f;
        HeightField f = new HeightField(cx - extent, cz - extent, extent * 2f, extent * 2f, CELL);
        for (int iz = 0; iz < f.nz; iz++) {
            float z = f.zAt(iz);
            for (int ix = 0; ix < f.nx; ix++) {
                float x = f.xAt(ix);
                float dx = x - cx;
                float dz = z - cz;
                float r = (float) Math.sqrt(dx * dx + dz * dz);
                float u = MathUtils.clamp(r / radius, 0f, 1.5f);
                float n = noise(x, z);
                float h;
                switch (shape) {
                    case BOWL:
                        h = base - param * (1f - u * u);
                        break;
                    case FUNNEL:
                        float step = (float) Math.floor(u * 3f) / 3f;
                        h = base - param * step;
                        break;
                    case MESA:
                        float k = u < 0.55f ? 1f
                                : MathUtils.clamp(1f - (u - 0.55f) / 0.45f, 0f, 1f);
                        h = base + param * k * k * (3f - 2f * k);
                        break;
                    case RIDGE:
                        h = base + param * (float) Math.cos(u * MathUtils.PI) * 0.8f;
                        break;
                    case FLAT:
                    default:
                        h = base;
                        break;
                }
                h += n * roughness;
                if (r <= radius) {
                    f.set(ix, iz, h, true, type);
                } else if (r <= radius + 5f) {
                    f.set(ix, iz, h - (r - radius) * 0.9f, true, TerrainType.ROCK);
                } else {
                    f.set(ix, iz, h - 6f - (r - radius) * 0.7f, false, TerrainType.VOID);
                }
            }
        }
        return f;
    }

    // ================================================================== 噪声

    /** 确定性解析噪声：不依赖任何随机数状态，保证跨机器一致。 */
    public static float noise(float x, float z) {
        float a = (float) Math.sin(x * 0.137f) * (float) Math.cos(z * 0.113f);
        float b = (float) Math.sin((x + z) * 0.311f) * 0.5f;
        float c = (float) Math.cos((x - z * 0.7f) * 0.053f) * 0.35f;
        return a * 0.7f + b + c;
    }
}
