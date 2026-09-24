package top.gunmu.party.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ObjectMap;

import java.util.Random;

import top.gunmu.party.core.TerrainType;

/**
 * 程序化纹理。全部由固定种子的 {@link Random} 在代码里画出来，
 * 不依赖任何外部图片文件（docs/05-TECH-ARCHITECTURE.md §5.2）。
 *
 * <p>接入正式美术时：把同名 PNG 丢进 {@code assets/textures/}，
 * 本类会优先读取外部文件，找不到才回退到程序化生成。
 */
public final class TextureFactory implements Disposable {

    private static final int SIZE = 64;

    public final Texture wood;
    public final Texture woodEnd;
    public final Texture item;
    private final ObjectMap<String, Texture> terrain = new ObjectMap<>();

    public TextureFactory() {
        wood = load("roll_wood", () -> genWood(1337L, false));
        woodEnd = load("roll_wood_end", () -> genWood(1337L, true));
        item = load("item_shell", () -> genItem(99L));

        String[] names = {"terrain_grass", "terrain_rock", "terrain_ice",
                "terrain_mud", "terrain_sand", "terrain_lava", "terrain_void"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            terrain.put(names[i], load(names[i], () -> genTerrain(idx)));
        }
    }

    /** 先找外部 PNG，找不到再程序化生成。 */
    private Texture load(String name, java.util.function.Supplier<Pixmap> fallback) {
        String path = "textures/" + name + ".png";
        if (Gdx.files.internal(path).exists()) {
            Texture t = new Texture(Gdx.files.internal(path));
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            return t;
        }
        Texture t = new Texture(fallback.get());
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        return t;
    }

    public Texture terrainOf(TerrainType type) {
        switch (type) {
            case GRASS:
                return terrain.get("terrain_grass");
            case ROCK:
                return terrain.get("terrain_rock");
            case ICE:
                return terrain.get("terrain_ice");
            case MUD:
                return terrain.get("terrain_mud");
            case SAND:
                return terrain.get("terrain_sand");
            case LAVA:
                return terrain.get("terrain_lava");
            default:
                return terrain.get("terrain_void");
        }
    }

    // ================================================================== 生成

    private static Pixmap base(int rgb) {
        Pixmap p = new Pixmap(SIZE, SIZE, Pixmap.Format.RGBA8888);
        p.setColor(new Color(rgb));
        p.fill();
        return p;
    }

    private static void noise(Pixmap p, Random r, int baseRgb, float amount) {
        Color c = new Color();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                c.set(baseRgb);
                float k = 1f + (r.nextFloat() - 0.5f) * 2f * amount;
                c.mul(Math.max(0.1f, k), Math.max(0.1f, k), Math.max(0.1f, k), 1f);
                c.a = 1f;
                p.setColor(c);
                p.drawPixel(x, y);
            }
        }
    }

    private static Pixmap genWood(long seed, boolean end) {
        Random r = new Random(seed + (end ? 7 : 0));
        Pixmap p = base(0x8a6239);
        noise(p, r, 0x8a6239, 0.16f);
        if (end) {
            // 端面：年轮
            int cx = SIZE / 2;
            int cy = SIZE / 2;
            for (int ring = 4; ring < 34; ring += 5) {
                p.setColor(new Color(0x5d3f22ff));
                p.drawCircle(cx, cy, ring);
                p.setColor(new Color(0xa07a4bff));
                p.drawCircle(cx, cy, ring + 1);
            }
        } else {
            // 侧面：纵向纹理
            for (int i = 0; i < 26; i++) {
                int x = r.nextInt(SIZE);
                int w = 1 + r.nextInt(2);
                Color c = new Color(0x5d3f22ff);
                c.mul(0.75f + r.nextFloat() * 0.5f, 0.75f + r.nextFloat() * 0.5f,
                        0.75f + r.nextFloat() * 0.5f, 1f);
                c.a = 1f;
                p.setColor(c);
                int y0 = r.nextInt(16);
                p.fillRectangle(x, y0, w, SIZE - y0 * 2);
            }
        }
        return p;
    }

    private static Pixmap genTerrain(int index) {
        Random r = new Random(4000L + index * 131L);
        switch (index) {
            case 0:
                return speckled(r, 0x4a7c3a, 0x3c6730, 0x5e9448, 240);
            case 1:
                return speckled(r, 0x6f6f78, 0x5c5c64, 0x86868f, 200);
            case 2: {
                Pixmap p = base(0xa8dcf0);
                noise(p, r, 0xa8dcf0, 0.06f);
                p.setColor(new Color(0xe8ffffff));
                for (int i = 0; i < 14; i++) {
                    int x = r.nextInt(SIZE);
                    int y = r.nextInt(SIZE);
                    p.drawLine(x, y, x + 6 + r.nextInt(10), y + 4 + r.nextInt(10));
                }
                return p;
            }
            case 3:
                return speckled(r, 0x5a4632, 0x413322, 0x6d573f, 300);
            case 4:
                return speckled(r, 0xd9c48a, 0xc4ac6e, 0xecdca8, 260);
            case 5: {
                Pixmap p = base(0x2b1208);
                noise(p, r, 0x2b1208, 0.12f);
                p.setColor(new Color(0xff7a2aff));
                for (int i = 0; i < 18; i++) {
                    int x = r.nextInt(SIZE);
                    int y = r.nextInt(SIZE);
                    p.drawLine(x, y, x + r.nextInt(18) - 9, y + r.nextInt(18) - 9);
                }
                p.setColor(new Color(0xffe08aff));
                for (int i = 0; i < 8; i++) {
                    p.drawPixel(r.nextInt(SIZE), r.nextInt(SIZE));
                }
                return p;
            }
            default: {
                Pixmap p = base(0x14141c);
                noise(p, r, 0x14141c, 0.22f);
                return p;
            }
        }
    }

    private static Pixmap speckled(Random r, int baseRgb, int darkRgb, int lightRgb,
                                   int count) {
        Pixmap p = base(baseRgb);
        noise(p, r, baseRgb, 0.14f);
        for (int i = 0; i < count; i++) {
            Color c = new Color(r.nextBoolean() ? darkRgb : lightRgb);
            c.mul(0.85f + r.nextFloat() * 0.3f, 0.85f + r.nextFloat() * 0.3f,
                    0.85f + r.nextFloat() * 0.3f, 1f);
            c.a = 1f;
            p.setColor(c);
            int x = r.nextInt(SIZE);
            int y = r.nextInt(SIZE);
            int w = 1 + r.nextInt(3);
            p.fillRectangle(x, y, w, w);
        }
        return p;
    }

    private static Pixmap genItem(long seed) {
        Random r = new Random(seed);
        Pixmap p = new Pixmap(SIZE, SIZE, Pixmap.Format.RGBA8888);
        p.setColor(new Color(0xffffff00));
        p.fill();
        // 中心亮、边缘透明的圆形光斑，用作道具底座
        int cx = SIZE / 2;
        int cy = SIZE / 2;
        for (int rad = SIZE / 2; rad > 0; rad--) {
            float a = 1f - rad / (SIZE / 2f);
            p.setColor(1f, 1f, 1f, a * a);
            p.fillCircle(cx, cy, rad);
        }
        return p;
    }

    @Override
    public void dispose() {
        wood.dispose();
        woodEnd.dispose();
        item.dispose();
        for (Texture t : terrain.values()) {
            t.dispose();
        }
    }
}
