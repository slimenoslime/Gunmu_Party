package top.gunmu.party.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

import top.gunmu.party.core.Mode;
import top.gunmu.party.core.TerrainType;
import top.gunmu.party.items.ItemType;
import top.gunmu.party.map.MapRegistry;
import top.gunmu.party.ultimates.UltimateType;

/**
 * 中文字体。<b>只生成 UI 真正用到的那批字形</b>——否则 CJK 全量字形会把图集撑爆。
 *
 * <p>字体来源按优先级回退（docs/05 §5.3）：
 * <ol>
 *   <li>{@code assets/fonts/game.ttf}（用户自备）</li>
 *   <li>系统自带中文字体（Windows / macOS / Linux 各一条）</li>
 *   <li>全部失败 → 退回 libGDX 内置 ASCII 字体，并在日志里明确告知</li>
 * </ol>
 */
public final class Fonts implements Disposable {

    private static final String[] CANDIDATES = {
            "fonts/game.ttf",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/STHeiti Medium.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            // Android：系统自带的中日韩字体，绝大多数机型可读
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/NotoSansCJKsc-Regular.otf",
            "/system/fonts/DroidSansFallbackFull.ttf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/DroidSansChinese.ttf",
            "/system/fonts/NotoSansHans-Regular.otf"
    };

    /**
     * 字形按基准尺寸放大这个倍数再烘焙，运行时再用 {@code setScale} 缩回去。
     * 好处：同一份字体既能服务 810p 的窗口，也能服务 4K 屏和手机，
     * 缩放是"降采样"而不是"放大"，文字不会糊。
     */
    public static final float GENERATION_SCALE = 1.25f;

    /** 静态 UI 文案里出现的全部字符（含标点）。 */
    private static final String UI_CHARS =
            "！？。，、：；·—…“”‘’（）《》「」【】/+×-%"
                    + "0123456789"
                    + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
                    + " :#.()[]<>|-_,!?'\"*="
                    + "你我了的是在有个不和人大这中"
                    + "第关关卡秒名次晋级淘汰死亡击杀伤害重生存档点道具决战技难度"
                    + "竞速生存竞技子图决战结果冠军名额剩最后一人时间到全部到线出局"
                    + "血量速度攻击防御隐身眩晕定身击退免伤反弹护盾道具栏操作说明"
                    + "开始选择确认返回继续重新机键盘摇杆空格移动跳跃使用方向键"
                    + "撞碎了被干碎到达终点名次记录滚动消息排行"
                    + "木墩桩柱干梁柁柯杈棒梃梧桐杉桦榆榕檀楠椴榉枫榛橡桑柳杨槐柏"
                    + "青丘缓坡之字断崖冰隙峡谷崩落长阶涨水盆地收缩木桩林锯盘深井"
                    + "决斗坑弹球台熔岩环竞速段生存段竞技子图"
                    + "伤害预算机关泥坑冰面加速带弹跳台传送带熔岩深渊旋转横杆锤锯盘推板压板落石风扇旋转圆盘";

    public BitmapFont small;
    public BitmapFont medium;
    public BitmapFont large;
    public BitmapFont huge;
    /** 是否成功加载到中文字体。false 时界面会自动切换到 ASCII 文案。 */
    public boolean cjk;

    /** 基准字号（未乘 GENERATION_SCALE），供界面换算行距。 */
    public int baseSmall = 17;
    public int baseMedium = 23;
    public int baseLarge = 31;
    public int baseHuge = 48;

    private float uiScale = 1f;
    private FreeTypeFontGenerator generator;

    public void load() {
        String found = null;
        for (String path : CANDIDATES) {
            try {
                if (path.startsWith("fonts/")) {
                    if (!Gdx.files.internal(path).exists()) {
                        continue;
                    }
                    generator = new FreeTypeFontGenerator(Gdx.files.internal(path));
                } else {
                    if (!Gdx.files.absolute(path).exists()) {
                        continue;
                    }
                    generator = new FreeTypeFontGenerator(Gdx.files.absolute(path));
                }
                found = path;
                break;
            } catch (Throwable t) {
                Gdx.app.error("Fonts", "字体不可用: " + path + " -> " + t.getMessage());
                generator = null;
            }
        }

        if (generator != null) {
            try {
                String chars = charset();
                baseSmall = 17;
                baseMedium = 23;
                baseLarge = 31;
                baseHuge = 48;
                small = make(chars, baseSmall);
                medium = make(chars, baseMedium);
                large = make(chars, baseLarge);
                huge = make(chars, baseHuge);
                cjk = true;
                Gdx.app.log("Fonts", "已加载中文字体: " + found + "，字形 " + chars.length() + " 个");
                generator.dispose();
                generator = null;
                return;
            } catch (Throwable t) {
                Gdx.app.error("Fonts", "生成字体失败，回退 ASCII: " + t.getMessage());
                if (small != null) {
                    small.dispose();
                }
                if (medium != null) {
                    medium.dispose();
                }
                if (large != null) {
                    large.dispose();
                }
                if (huge != null) {
                    huge.dispose();
                }
                small = null;
                medium = null;
                large = null;
                huge = null;
                if (generator != null) {
                    generator.dispose();
                    generator = null;
                }
            }
        }

        // 兜底：内置 ASCII 字体
        cjk = false;
        baseSmall = 15;
        baseMedium = 18;
        baseLarge = 26;
        baseHuge = 40;
        small = new BitmapFont();
        medium = new BitmapFont();
        large = new BitmapFont();
        huge = new BitmapFont();
        setUiScale(uiScale);
        Gdx.app.error("Fonts", "未找到中文字体，界面已切换到 ASCII 模式。"
                + "把任意中文 TTF 放到 assets/fonts/game.ttf 即可恢复。");
    }

    private BitmapFont make(String chars, int baseSize) {
        FreeTypeFontGenerator.FreeTypeFontParameter para =
                new FreeTypeFontGenerator.FreeTypeFontParameter();
        // 按放大后的尺寸烘焙，运行时再缩回去，缩放时是降采样所以不会糊
        para.size = Math.round(baseSize * GENERATION_SCALE);
        para.characters = chars;
        para.color = Color.WHITE;
        para.minFilter = Texture.TextureFilter.Linear;
        para.magFilter = Texture.TextureFilter.Linear;
        para.genMipMaps = false;
        para.packer = new PixmapPacker(2048, 2048, Pixmap.Format.RGBA8888, 2, false);
        BitmapFont f = generator.generateFont(para);
        f.setUseIntegerPositions(false);
        f.getData().setScale(1f / GENERATION_SCALE);
        return f;
    }

    /**
     * 按屏幕高度整体缩放 UI 字号。
     * 手机会传入 1.3~2.4，桌面一般是 1.0。
     */
    public void setUiScale(float scale) {
        uiScale = scale;
        float k = scale / GENERATION_SCALE;
        if (small != null) {
            small.getData().setScale(k);
        }
        if (medium != null) {
            medium.getData().setScale(k);
        }
        if (large != null) {
            large.getData().setScale(k);
        }
        if (huge != null) {
            huge.getData().setScale(k);
        }
    }

    public float uiScale() {
        return uiScale;
    }

    /** 文本像素宽度（已含当前缩放）。 */
    public float width(BitmapFont f, String text) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        BitmapFont.BitmapFontData d = f.getData();
        float scale = d.scaleX;
        float w = 0f;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            w += advance(d, cp) * scale;
            i += Character.charCount(cp);
        }
        return w;
    }

    /**
     * 按像素宽度折行（逐字符贪心）。
     *
     * <p><b>不要用 {@code GlyphLayout} 的自动折行</b>：它按<b>空格</b>断词，
     * 而中文整句没有空格，会被当成一个超长单词直接溢出屏幕 ——
     * 这就是"道具属性显示不全"的原因。中文得按字断。
     *
     * <p>标点尽量不留在行首（基本的避头处理）：行尾放不下标点时，
     * 宁可把标点挤在行尾也不另起一行。
     */
    public Array<String> wrap(BitmapFont f, String text, float maxWidth) {
        BitmapFont.BitmapFontData d = f.getData();
        final float scale = d.scaleX;
        return wrapText(text, maxWidth, cp -> advance(d, cp) * scale);
    }

    /** 单个码点的推进宽度（像素）。 */
    public interface Advance {
        float of(int codePoint);
    }

    /**
     * 折行算法本体：<b>与具体字体无关</b>，只依赖"每个码点多宽"这一个函数。
     *
     * <p>抽成纯函数是为了能**无头自检** —— 真字体要 FreeType + 纹理，命令行跑不起来，
     * 而折行是否丢字、是否溢出是纯算法性质，跟字形无关。
     * 断言见 {@code tools/TextWrapTest}。
     *
     * <p>不变量：把返回的各行首尾相接必须**一字不差**等于原文（折行不许吃掉任何字符）。
     */
    public static Array<String> wrapText(String text, float maxWidth, Advance adv) {
        Array<String> out = new Array<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        if (maxWidth <= 1f) {
            out.add(text);
            return out;
        }
        StringBuilder line = new StringBuilder();
        float w = 0f;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            float a = adv.of(cp);
            boolean breakBefore = line.length() > 0 && w + a > maxWidth
                    && !isClosingPunctuation(cp);
            if (breakBefore) {
                out.add(line.toString());
                line.setLength(0);
                w = 0f;
            }
            line.appendCodePoint(cp);
            w += a;
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    /** 一行文本按给定宽度函数算出来的像素宽度。 */
    public static float measure(String s, Advance adv) {
        if (s == null) {
            return 0f;
        }
        float w = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += adv.of(cp);
        }
        return w;
    }

    private static float advance(BitmapFont.BitmapFontData d, int cp) {
        BitmapFont.Glyph g = cp <= 0xFFFF ? d.getGlyph((char) cp) : null;
        if (g != null) {
            return g.xadvance;
        }
        return d.missingGlyph == null ? 0f : d.missingGlyph.xadvance;
    }

    /** 不该出现在行首的收尾标点。 */
    private static boolean isClosingPunctuation(int cp) {
        switch (cp) {
            case '，': case '。': case '、': case '；': case '：':
            case '？': case '！': case '）': case '】': case '》':
            case '」': case '』': case '…': case '·':
            case ',': case '.': case ';': case ':':
            case '?': case '!': case ')': case ']':
                return true;
            default:
                return false;
        }
    }

    /** 基准字号下的行高，供界面排版使用（已含当前缩放）。 */
    public float lineHeight(BitmapFont f) {
        return f.getLineHeight();
    }

    /** 收集当前代码里所有会被渲染的中文，组装成字符集。 */
    public static String charset() {
        // 首选 tools/make_font.py 产出的清单：它和 assets/fonts/game.ttf 是同一份扫描结果，
        // 保证"烘焙的字"与"字体里有的字"严格一致。找不到才退回手写集合。
        if (Gdx.files != null) {
            try {
                if (Gdx.files.internal("fonts/charset.txt").exists()) {
                    String s = Gdx.files.internal("fonts/charset.txt").readString("UTF-8");
                    if (s.length() > 0) {
                        return dedupe(s);
                    }
                }
            } catch (Throwable ignored) {
                // 落到下面的手写集合
            }
        }
        StringBuilder sb = new StringBuilder(2048);
        sb.append(UI_CHARS);
        for (ItemType t : ItemType.ALL) {
            sb.append(t.cn).append(t.desc).append(t.rarity.cn).append(t.meme);
        }
        for (UltimateType u : UltimateType.ALL) {
            sb.append(u.cn).append(u.tagline).append(u.detail);
        }
        for (Mode m : Mode.values()) {
            sb.append(m.cn);
        }
        for (TerrainType t : TerrainType.ALL) {
            sb.append(t.cn);
        }
        for (String id : MapRegistry.RACE_IDS) {
            sb.append(mapName(id));
        }
        for (String id : MapRegistry.SURVIVAL_IDS) {
            sb.append(mapName(id));
        }
        for (String id : MapRegistry.ARENA_IDS) {
            sb.append(mapName(id));
        }
        return dedupe(sb.toString());
    }

    private static String dedupe(String s) {
        boolean[] seen = new boolean[0x10000];
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                continue;
            }
            if (!seen[c]) {
                seen[c] = true;
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String mapName(String id) {
        // 与 MapRegistry 里的中文名保持一致；只用于字符集收集
        switch (id) {
            case "race_green_hill":
                return "青丘缓坡";
            case "race_switchback":
                return "之字断崖";
            case "race_ice_canyon":
                return "冰隙峡谷";
            case "race_collapse":
                return "崩落长阶";
            case "surv_water_rise":
                return "涨水盆地";
            case "surv_shrink_ring":
                return "收缩木桩林";
            case "surv_saw_pit":
                return "锯盘深井";
            case "arena_duel_pit":
                return "决斗坑";
            case "arena_pinball":
                return "弹球台";
            case "arena_lava_ring":
                return "熔岩环";
            default:
                return "";
        }
    }

    @Override
    public void dispose() {
        if (generator != null) {
            generator.dispose();
        }
        if (small != null) {
            small.dispose();
        }
        if (medium != null) {
            medium.dispose();
        }
        if (large != null) {
            large.dispose();
        }
        if (huge != null) {
            huge.dispose();
        }
    }
}
