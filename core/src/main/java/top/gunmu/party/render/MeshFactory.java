package top.gunmu.party.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ShortArray;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.TerrainType;
import top.gunmu.party.physics.HeightField;

/**
 * 全部几何体都在代码里生成——不加载任何外部模型文件。
 *
 * <p>刻意**不使用 {@code Model} / {@code ModelInstance}**：在当前 libGDX 版本里
 * {@code Model} 只能由 {@code ModelData} 或 {@code ModelBuilder} 构造，
 * 而本工程需要「同一份几何、多种颜色」的实例级复用。
 * 直接产出 {@link MeshPart} + {@link Material}，由渲染器塞进 {@code Renderable}，
 * 既省内存也完全避开上游 API 的构造限制。
 */
public final class MeshFactory implements Disposable {

    /** 每顶点浮点数：position(3) + normal(3) + uv(2)。几何自检也要用，故公开。 */
    public static final int FLOATS_PER_VERTEX = 8;

    public static final String KIND_ROLL = "roll";
    public static final String KIND_BALL = "ball";
    public static final String KIND_BOX = "box";
    public static final String KIND_OCT = "oct";
    public static final String KIND_POLE = "pole";
    public static final String KIND_DISC = "disc";
    /** 空心圆环（贴地标记用）。实心圆盘看起来像"地上多了一块地板"。 */
    public static final String KIND_RING = "ring";
    /** 开口圆筒（双面）。用作起点屏障：里面外面都看得见。 */
    public static final String KIND_TUBE = "tube";
    /** 贴地扇形（决赛关普通攻击的范围指示）。 */
    public static final String KIND_SECTOR = "sector";
    public static final String KIND_SHARD = "shard";

    /** 一份可渲染几何：网格分段 + 材质。 */
    public static final class Geo {
        public final String key;
        public final Mesh mesh;
        public final MeshPart part;
        public final Material material;
        /** 由地形生成、归调用方所有，需要显式释放。 */
        public final boolean owned;

        Geo(String key, Mesh mesh, MeshPart part, Material material, boolean owned) {
            this.key = key;
            this.mesh = mesh;
            this.part = part;
            this.material = material;
            this.owned = owned;
        }
    }

    private final TextureFactory textures;
    private final ObjectMap<String, Geo> cache = new ObjectMap<>();
    private final Array<Geo> ownedGeos = new Array<>();

    public MeshFactory(TextureFactory textures) {
        this.textures = textures;
    }

    // ================================================================== 单件几何

    public Geo geo(String kind, int rgba) {
        String key = kind + '#' + Integer.toHexString(rgba);
        Geo g = cache.get(key);
        if (g != null) {
            return g;
        }
        g = build(kind, rgba, false, key);
        cache.put(key, g);
        ownedGeos.add(g);
        return g;
    }

    public Geo geo(String kind, Color c) {
        return geo(kind, Color.rgba8888(c));
    }

    /** 半透明变体（检查点、终点线、光环等不该遮视线的东西）。 */
    public Geo translucent(String kind, int rgba) {
        String key = kind + '@' + Integer.toHexString(rgba);
        Geo g = cache.get(key);
        if (g != null) {
            return g;
        }
        g = build(kind, rgba, true, key);
        cache.put(key, g);
        ownedGeos.add(g);
        return g;
    }

    private Geo build(String kind, int rgba, boolean blend, String key) {
        FloatArray v = new FloatArray();
        ShortArray ix = new ShortArray();
        Texture tex;

        switch (kind) {
            case KIND_ROLL:
                tex = textures.wood;
                cylinder(v, ix, 0.5f, 1.6f, 20);
                break;
            case KIND_BALL:
                tex = textures.item;
                sphere(v, ix, 1f, 14, 10);
                break;
            case KIND_BOX:
                tex = textures.terrainOf(TerrainType.ROCK);
                box(v, ix);
                break;
            case KIND_OCT:
                tex = textures.item;
                octahedron(v, ix, 1f);
                break;
            case KIND_POLE:
                tex = textures.wood;
                cylinder(v, ix, 0.16f, 2f, 10);
                break;
            case KIND_DISC:
                tex = textures.terrainOf(TerrainType.ROCK);
                cylinder(v, ix, 1f, 0.24f, 18);
                break;
            case KIND_RING:
                tex = textures.terrainOf(TerrainType.ROCK);
                ring(v, ix, 0.80f, 1f, 32);
                break;
            case KIND_TUBE:
                tex = textures.terrainOf(TerrainType.ICE);
                tube(v, ix, 1f, 1f, 40);
                break;
            case KIND_SECTOR:
                tex = textures.terrainOf(TerrainType.ROCK);
                sector(v, ix, GameConfig.ATTACK_HALF_ANGLE, 16);
                break;
            case KIND_SHARD:
            default:
                tex = textures.wood;
                tetraShard(v, ix);
                break;
        }

        Material mat = new Material(
                TextureAttribute.createDiffuse(tex),
                ColorAttribute.createDiffuse(new Color(rgba)));
        if (blend) {
            mat.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA));
        }
        return makeGeo(key, v, ix, mat, false);
    }

    private static Geo makeGeo(String key, FloatArray v, ShortArray ix,
                               Material mat, boolean owned) {
        Mesh mesh = new Mesh(true, Math.max(1, v.size / FLOATS_PER_VERTEX),
                Math.max(3, ix.size),
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
        float[] va = new float[v.size];
        System.arraycopy(v.items, 0, va, 0, v.size);
        mesh.setVertices(va);
        mesh.setIndices(ix.toArray());

        MeshPart part = new MeshPart(key, mesh, 0, ix.size, GL20.GL_TRIANGLES);
        part.update();
        return new Geo(key, mesh, part, mat, owned);
    }

    // ================================================================== 地形

    /**
     * 单块地形网格的边长（格数）。
     *
     * <p>为什么必须分块：{@code Mesh.setIndices} 只接受 {@code short[]}，
     * 也就是说单个网格的顶点索引上限是 65535。冰隙峡谷那张图的 ICE 材质组
     * 一度有 67960 个顶点 —— 索引回绕之后三角形连到了错误的顶点上，
     * 整片地形被撕成碎片。实测截图里"地面几乎看不见"就是这个原因。
     *
     * <p>96 格 → 单块最多 97×97 = 9409 个格子，按每格最多 4 个顶点算是 37636，
     * 留在 65535 以内且有余量。再大就只是减少 draw call，收益远小于风险。
     */
    private static final int TERRAIN_TILE = 96;

    /** 按地面材质分组，每种材质一份几何。返回值归调用方所有，需 {@link #disposeGeo}。 */
    public Array<Geo> terrain(HeightField f) {
        return terrain(f, 1);
    }

    /**
     * @param step 网格抽稀步长；2 表示隔一格画一次，顶点数降到约 1/4。
     *             <b>只影响画面</b>，物理仍然使用完整的 heightfield，
     *             因此桌面与安卓的模拟结果完全一致。
     */
    public Array<Geo> terrain(HeightField f, int step) {
        final int s = Math.max(1, step);
        final int tileSpan = TERRAIN_TILE * s;
        Array<Geo> out = new Array<>();
        FloatArray v = new FloatArray();
        ShortArray ix = new ShortArray();
        int maxVerts = 0;

        for (TerrainType type : TerrainType.ALL) {
            if (!type.walkable()) {
                continue;
            }
            for (int tz = 0; tz + s < f.nz; tz += tileSpan) {
                for (int tx = 0; tx + s < f.nx; tx += tileSpan) {
                    v.clear();
                    ix.clear();
                    if (!collectTerrainTile(f, s, type, tx, tz, v, ix)) {
                        continue;
                    }
                    Material mat = new Material(
                            TextureAttribute.createDiffuse(textures.terrainOf(type)),
                            ColorAttribute.createDiffuse(type.color));
                    Geo g = makeGeo("terrain_" + type.name() + "_" + tx + "_" + tz,
                            v, ix, mat, true);
                    maxVerts = Math.max(maxVerts, g.mesh.getNumVertices());
                    out.add(g);
                }
            }
        }
        // 真超了就是分块逻辑写错了，别让它悄悄撕图
        if (maxVerts > 65535) {
            throw new IllegalStateException("地形分块失败：单块顶点数 " + maxVerts
                    + " 超过 short 索引上限 65535。调小 MeshFactory.TERRAIN_TILE。");
        }
        if (out.size == 0) {
            FloatArray bv = new FloatArray();
            ShortArray bix = new ShortArray();
            box(bv, bix);
            out.add(makeGeo("terrain_empty", bv, bix,
                    new Material(ColorAttribute.createDiffuse(Color.RED)), true));
        }
        com.badlogic.gdx.Gdx.app.log("MeshFactory", "地形分块 " + out.size
                + " 块，单块最多 " + maxVerts + " 个顶点");
        return out;
    }

    /**
     * 生成一块（tile）指定材质的地形几何。
     *
     * <p><b>纯计算，不碰 GL</b> —— 无头自检（{@code TerrainAudit}）就是直接调它来
     * 断言"每个三角形的几何法线都朝上"的。绕序一旦再被改反，冒烟测试立刻会红，
     * 不用等到跑起来才发现地面整片消失。
     *
     * @return 是否至少产生了一个三角形
     */
    public static boolean collectTerrainTile(HeightField f, int s, TerrainType type,
                                             int tx, int tz, FloatArray v, ShortArray ix) {
        final int tileSpan = TERRAIN_TILE * s;
        int zLimit = Math.min(tz + tileSpan, f.nz - s);
        int xLimit = Math.min(tx + tileSpan, f.nx - s);
        int[] corner = new int[4];
        float[] cxs = new float[4];
        float[] czs = new float[4];
        short[] base = new short[4];
        Vector3 nor = new Vector3();
        final int vStart = ix.size;

        for (int iz = tz; iz < zLimit; iz += s) {
            for (int ixx = tx; ixx < xLimit; ixx += s) {
                int ix1 = ixx + s;
                int iz1 = iz + s;
                corner[0] = f.index(ixx, iz);
                corner[1] = f.index(ix1, iz);
                corner[2] = f.index(ix1, iz1);
                corner[3] = f.index(ixx, iz1);
                cxs[0] = f.xAt(ixx);
                cxs[1] = f.xAt(ix1);
                cxs[2] = cxs[1];
                cxs[3] = cxs[0];
                czs[0] = f.zAt(iz);
                czs[1] = czs[0];
                czs[2] = f.zAt(iz1);
                czs[3] = czs[2];

                boolean emitted = false;
                for (int tri = 0; tri < 2; tri++) {
                    // 格子四角：0=(x0,z0) 1=(x1,z0) 2=(x1,z1) 3=(x0,z1)
                    // 三角形按逆时针（俯视）绕：0→2→1 与 0→3→2。
                    // 原来的 0→1→2 / 0→2→3 算出的几何法线是 -Y，与顶点法线 +Y 相反，
                    // 开启背面剔除后整片地面直接消失 —— 就是"地面看不见"的真因。
                    int a = 0;
                    int b = tri == 0 ? 2 : 3;
                    int c = tri == 0 ? 1 : 2;
                    if (f.mat[corner[a]] != type.ordinal()
                            || f.mat[corner[b]] != type.ordinal()
                            || f.mat[corner[c]] != type.ordinal()) {
                        continue;
                    }
                    if (!f.walkable[corner[a]] || !f.walkable[corner[b]]
                            || !f.walkable[corner[c]]) {
                        continue;
                    }
                    if (!emitted) {
                        for (int k = 0; k < 4; k++) {
                            f.normalAt(cxs[k], czs[k], nor);
                            base[k] = (short) (v.size / FLOATS_PER_VERTEX);
                            addVertex(v, cxs[k], f.h[corner[k]], czs[k],
                                    nor.x, nor.y, nor.z,
                                    cxs[k] / 6f, czs[k] / 6f);
                        }
                        emitted = true;
                    }
                    ix.add(base[a]);
                    ix.add(base[b]);
                    ix.add(base[c]);
                }
            }
        }
        return ix.size > vStart;
    }

    public void disposeGeo(Geo g) {
        if (g == null) {
            return;
        }
        if (!g.owned) {
            ownedGeos.removeValue(g, true);
            cache.remove(g.key);
        }
        g.mesh.dispose();
    }

    // ================================================================== 顶点工具

    private static void addVertex(FloatArray v, float x, float y, float z,
                                  float nx, float ny, float nz, float u, float vv) {
        v.add(x);
        v.add(y);
        v.add(z);
        v.add(nx);
        v.add(ny);
        v.add(nz);
        v.add(u);
        v.add(vv);
    }

    // ================================================================== 几何生成

    /** 沿 Y 轴、中心在原点的圆柱。 */
    public static void cylinder(FloatArray v, ShortArray ix, float r, float h, int seg) {
        float half = h * 0.5f;
        int start = v.size / FLOATS_PER_VERTEX;
        for (int i = 0; i <= seg; i++) {
            float a = (float) (i / (double) seg * Math.PI * 2.0);
            float cx = (float) Math.cos(a);
            float cz = (float) Math.sin(a);
            float u = i / (float) seg;
            addVertex(v, cx * r, half, cz * r, cx, 0f, cz, u, 0f);
            addVertex(v, cx * r, -half, cz * r, cx, 0f, cz, u, 1f);
        }
        for (int i = 0; i < seg; i++) {
            int a = start + i * 2;
            int b = a + 1;
            int c = a + 2;
            int d = a + 3;
            ix.add((short) a);
            ix.add((short) c);
            ix.add((short) b);
            ix.add((short) b);
            ix.add((short) c);
            ix.add((short) d);
        }
        for (int side = 0; side < 2; side++) {
            float ny = side == 0 ? 1f : -1f;
            float y = side == 0 ? half : -half;
            int center = v.size / FLOATS_PER_VERTEX;
            addVertex(v, 0f, y, 0f, 0f, ny, 0f, 0.5f, 0.5f);
            int ringStart = v.size / FLOATS_PER_VERTEX;
            for (int i = 0; i <= seg; i++) {
                float a = (float) (i / (double) seg * Math.PI * 2.0);
                float cx = (float) Math.cos(a);
                float cz = (float) Math.sin(a);
                addVertex(v, cx * r, y, cz * r, 0f, ny, 0f,
                        0.5f + cx * 0.5f, 0.5f + cz * 0.5f);
            }
            for (int i = 0; i < seg; i++) {
                if (side == 0) {
                    ix.add((short) center);
                    ix.add((short) (ringStart + i + 1));
                    ix.add((short) (ringStart + i));
                } else {
                    ix.add((short) center);
                    ix.add((short) (ringStart + i));
                    ix.add((short) (ringStart + i + 1));
                }
            }
        }
    }

    public static void sphere(FloatArray v, ShortArray ix, float r, int lon, int lat) {
        int start = v.size / FLOATS_PER_VERTEX;
        for (int j = 0; j <= lat; j++) {
            float phi = (float) (j / (double) lat * Math.PI);
            float ny = (float) Math.cos(phi);
            float sr = (float) Math.sin(phi);
            for (int i = 0; i <= lon; i++) {
                float theta = (float) (i / (double) lon * Math.PI * 2.0);
                float nx = sr * (float) Math.cos(theta);
                float nz = sr * (float) Math.sin(theta);
                addVertex(v, nx * r, ny * r, nz * r, nx, ny, nz,
                        i / (float) lon, j / (float) lat);
            }
        }
        int row = lon + 1;
        for (int j = 0; j < lat; j++) {
            for (int i = 0; i < lon; i++) {
                short a = (short) (start + j * row + i);
                short b = (short) (a + 1);
                short c = (short) (a + row);
                short d = (short) (c + 1);
                // 四边形按 a → b → d → c 绕行；这个顺序才和外法线一致。
                // 原来写的 a,c,b + b,c,d 是反的，整颗球都被背面剔除掉。
                ix.add(a);
                ix.add(b);
                ix.add(d);
                ix.add(a);
                ix.add(d);
                ix.add(c);
            }
        }
    }

    /**
     * 贴在 XZ 平面上的扇形（半径 1，以 <b>+Z 为中线</b>，张角 ±{@code halfDeg}）。
     *
     * <p>用途：决赛关普通攻击的范围指示。扇形的朝向约定与 {@code Zone.yaw}、
     * {@code MapDef.finishDir} 一致（都用 {@code atan2(x, z)}），
     * 所以渲染时只要 {@code rotate(axisY, atan2(fx, fz) · radDeg)} 就能对准人。
     *
     * <p><b>绕序</b>：地从上方看逆时针才是朝上。所以三角形取
     * {@code (中心, 角度小的边点, 角度大的边点)} —— 叉积的 Y 分量是
     * {@code sin(a2 − a1) > 0}，法线朝上。反过来的话几何法线是 −Y，
     * 背面剔除一开，这个扇形就会像地形那次一样整片消失（D34 / D15 的同类坑）。
     */
    public static void sector(FloatArray v, ShortArray ix, float halfDeg, int seg) {
        float half = (float) Math.toRadians(halfDeg);
        addVertex(v, 0f, 0f, 0f, 0f, 1f, 0f, 0.5f, 0.5f);
        for (int i = 0; i <= seg; i++) {
            float a = -half + 2f * half * (i / (float) seg);
            float sx = (float) Math.sin(a);
            float cz = (float) Math.cos(a);
            addVertex(v, sx, 0f, cz, 0f, 1f, 0f,
                    0.5f + sx * 0.5f, 0.5f + cz * 0.5f);
        }
        for (int i = 0; i < seg; i++) {
            ix.add((short) 0);
            ix.add((short) (1 + i));      // 角度小的边点
            ix.add((short) (2 + i));      // 角度大的边点
        }
    }

    /**
     * 边长 1、中心在原点的立方体。六个面各给 4 个顶点，法线为面法线。
     *
     * <p>每行是 <b>{面法线, u 轴, v 轴}</b>，且 {@code 法线 = u × v}。
     *
     * <p><b>曾经的错误</b>：顶点位置只写成 {@code u*su + v*sv}，
     * 漏掉了沿面法线的那一项，于是六个面全部落在过原点的平面上 ——
     * 得到的不是立方体，而是六张互相穿插的"纸片"，从背面看还全被剔除。
     */
    /**
     * 开口圆筒（半径 r、高 h，无上下盖），<b>双面</b>。
     *
     * <p>用作起点屏障：玩家在筒<b>里面</b>，所以必须双面 —— 只画朝外的面会被
     * 背面剔除掉，从里面看就是一片空白，屏障等于没画。
     * 双面用两份顶点（法线一份朝外、一份朝内），而不是关掉剔除 ——
     * 半透明叠两层正好能读成"能量墙"。
     */
    public static void tube(FloatArray v, ShortArray ix, float r, float h, int seg) {
        float half = h * 0.5f;
        for (int side = 0; side < 2; side++) {
            int start = v.size / FLOATS_PER_VERTEX;
            for (int i = 0; i <= seg; i++) {
                float a = (float) (i / (double) seg * Math.PI * 2.0);
                float cx = (float) Math.cos(a);
                float cz = (float) Math.sin(a);
                float nx = side == 0 ? cx : -cx;
                float nz = side == 0 ? cz : -cz;
                float u = i / (float) seg;
                addVertex(v, cx * r, half, cz * r, nx, 0f, nz, u, 0f);
                addVertex(v, cx * r, -half, cz * r, nx, 0f, nz, u, 1f);
            }
            for (int i = 0; i < seg; i++) {
                int t0 = start + i * 2;
                int b0 = t0 + 1;
                int t1 = t0 + 2;
                int b1 = t0 + 3;
                if (side == 0) {
                    // 朝外：外法线方向观察为逆时针
                    ix.add((short) t0);
                    ix.add((short) t1);
                    ix.add((short) b0);
                    ix.add((short) b0);
                    ix.add((short) t1);
                    ix.add((short) b1);
                } else {
                    ix.add((short) t0);
                    ix.add((short) b0);
                    ix.add((short) t1);
                    ix.add((short) b0);
                    ix.add((short) b1);
                    ix.add((short) t1);
                }
            }
        }
    }

    /**
     * 贴在 XZ 平面上的空心圆环（内外半径 ri/ro），<b>双面</b>。
     *
     * <p>用途：地面标记。实心圆盘半透明叠在地形上会变成一块和地面同色系的"地板"，
     * 看起来像地图上莫名多出来的平台 —— 这正是玩家报过的"草的圆形地板"。
     * 圆环只画轮廓，一眼就知道是标记而不是可站立的平台。
     */
    public static void ring(FloatArray v, ShortArray ix, float ri, float ro, int seg) {
        for (int side = 0; side < 2; side++) {
            float ny = side == 0 ? 1f : -1f;
            int start = v.size / FLOATS_PER_VERTEX;
            for (int i = 0; i < seg; i++) {
                float a = (float) (i / (double) seg * Math.PI * 2.0);
                float cx = (float) Math.cos(a);
                float cz = (float) Math.sin(a);
                addVertex(v, cx * ri, 0f, cz * ri, 0f, ny, 0f, 0f, 0f);
                addVertex(v, cx * ro, 0f, cz * ro, 0f, ny, 0f, 1f, 1f);
            }
            for (int i = 0; i < seg; i++) {
                int a = start + i * 2;
                int b = a + 1;
                int c = start + ((i + 1) % seg) * 2;
                int d = c + 1;
                if (side == 0) {
                    ix.add((short) a);
                    ix.add((short) d);
                    ix.add((short) b);
                    ix.add((short) a);
                    ix.add((short) c);
                    ix.add((short) d);
                } else {
                    ix.add((short) a);
                    ix.add((short) b);
                    ix.add((short) d);
                    ix.add((short) a);
                    ix.add((short) d);
                    ix.add((short) c);
                }
            }
        }
    }

    public static void box(FloatArray v, ShortArray ix) {
        float h = 0.5f;
        float[][] axes = {
                {1, 0, 0, 0, 1, 0, 0, 0, 1},
                {-1, 0, 0, 0, 0, 1, 0, 1, 0},
                {0, 1, 0, 0, 0, 1, 1, 0, 0},
                {0, -1, 0, 1, 0, 0, 0, 0, 1},
                {0, 0, 1, 1, 0, 0, 0, 1, 0},
                {0, 0, -1, 0, 1, 0, 1, 0, 0}
        };
        float[][] uvs = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float[] a : axes) {
            int base = v.size / FLOATS_PER_VERTEX;
            for (int k = 0; k < 4; k++) {
                float su = (k == 1 || k == 2) ? 1f : -1f;
                float sv = (k == 2 || k == 3) ? 1f : -1f;
                float px = (a[0] + a[3] * su + a[6] * sv) * h;
                float py = (a[1] + a[4] * su + a[7] * sv) * h;
                float pz = (a[2] + a[5] * su + a[8] * sv) * h;
                addVertex(v, px, py, pz, a[0], a[1], a[2], uvs[k][0], uvs[k][1]);
            }
            ix.add((short) base);
            ix.add((short) (base + 1));
            ix.add((short) (base + 2));
            ix.add((short) base);
            ix.add((short) (base + 2));
            ix.add((short) (base + 3));
        }
    }

    /** 单位八面体，用作道具外壳。 */
    public static void octahedron(FloatArray v, ShortArray ix, float r) {
        float[][] vt = {
                {0, r, 0}, {0, 0, r}, {r, 0, 0}, {0, 0, -r}, {-r, 0, 0}, {0, -r, 0}
        };
        int[][] faces = {
                {0, 1, 2}, {0, 2, 3}, {0, 3, 4}, {0, 4, 1},
                {5, 2, 1}, {5, 3, 2}, {5, 4, 3}, {5, 1, 4}
        };
        for (int[] f : faces) {
            int base = v.size / FLOATS_PER_VERTEX;
            float nx = (vt[f[0]][0] + vt[f[1]][0] + vt[f[2]][0]) / 3f;
            float ny = (vt[f[0]][1] + vt[f[1]][1] + vt[f[2]][1]) / 3f;
            float nz = (vt[f[0]][2] + vt[f[1]][2] + vt[f[2]][2]) / 3f;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-5f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            for (int k = 0; k < 3; k++) {
                addVertex(v, vt[f[k]][0], vt[f[k]][1], vt[f[k]][2], nx, ny, nz,
                        0.5f + vt[f[k]][0] / (2f * r), 0.5f + vt[f[k]][2] / (2f * r));
            }
            ix.add((short) base);
            ix.add((short) (base + 1));
            ix.add((short) (base + 2));
        }
    }

    /** 碎木片：一个扁四面体，用于"滚木被撞碎"的效果。 */
    public static void tetraShard(FloatArray v, ShortArray ix) {
        float[][] p = {{0.35f, 0, 0}, {-0.15f, 0.2f, 0.2f}, {-0.15f, -0.2f, 0.2f},
                {-0.15f, 0, -0.25f}};
        int[][] faces = {{0, 1, 2}, {0, 2, 3}, {0, 3, 1}, {1, 3, 2}};
        for (int[] fc : faces) {
            int base = v.size / FLOATS_PER_VERTEX;
            float ax = p[fc[1]][0] - p[fc[0]][0];
            float ay = p[fc[1]][1] - p[fc[0]][1];
            float az = p[fc[1]][2] - p[fc[0]][2];
            float bx = p[fc[2]][0] - p[fc[0]][0];
            float by = p[fc[2]][1] - p[fc[0]][1];
            float bz = p[fc[2]][2] - p[fc[0]][2];
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-5f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            for (int k = 0; k < 3; k++) {
                addVertex(v, p[fc[k]][0], p[fc[k]][1], p[fc[k]][2], nx, ny, nz, 0f, 0f);
            }
            ix.add((short) base);
            ix.add((short) (base + 1));
            ix.add((short) (base + 2));
        }
    }

    @Override
    public void dispose() {
        for (Geo g : ownedGeos) {
            g.mesh.dispose();
        }
        ownedGeos.clear();
        cache.clear();
    }
}
