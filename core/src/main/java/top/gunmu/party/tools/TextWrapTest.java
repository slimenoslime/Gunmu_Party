package top.gunmu.party.tools;

import com.badlogic.gdx.utils.Array;

import top.gunmu.party.items.ItemType;
import top.gunmu.party.render.Fonts;

/**
 * 文本折行自检：<b>道具属性必须一行不丢地显示完整</b>。
 *
 * <p>玩家报的「道具属性的显示范围太小了，应该要显示完整属性」。原来的实现是一行
 * {@code 道具 名字 — 说明}，而且**完全不折行** —— 中文长句被屏幕右边缘直接切掉。
 *
 * <p>中英文混排的折行不能交给 {@code GlyphLayout}：它按<b>空格</b>断词，
 * 中文整句没有空格，会被当成一个超长单词原样溢出。所以自己按字符折，
 * 并且把算法抽成纯函数（{@link Fonts#wrapText}）以便在这里无头验证。
 *
 * <p>断言的是三个**可证伪的性质**，而不是"看起来差不多"：
 *
 * <ol>
 *   <li><b>无损</b>：把折出来的各行首尾相接，必须一字不差等于原文；</li>
 *   <li><b>不溢出</b>：每行的像素宽度必须 ≤ 给定宽度（除非单个字符本身就比一行还宽）；</li>
 *   <li><b>真的折了</b>：明显超长的中文句子必须折成多行，且每行都接近满宽
 *       （不然就是"每行一个字"，同样是错的）。</li>
 * </ol>
 */
public final class TextWrapTest {

    private TextWrapTest() {
    }

    public static int run() {
        System.out.println("---- 道具属性折行自检 ----");
        int bad = 0;

        // 模拟中文字体：全角 17px、半角 9px。真实宽度由 Fonts.width 提供，
        // 这里只需要一个"字越宽越容易溢出"的单调模型。
        Fonts.Advance mono = cp -> cp < 0x2E80 ? 9f : 17f;

        // ---- 1) 23 个道具的说明全部无损、不溢出 ----
        float inner = 700f;
        int longestLines = 0;
        String longestName = "";
        for (ItemType it : ItemType.values()) {
            String text = it.desc;
            Array<String> lines = Fonts.wrapText(text, inner, mono);
            StringBuilder joined = new StringBuilder();
            float widest = 0f;
            for (int i = 0; i < lines.size; i++) {
                joined.append(lines.get(i));
                widest = Math.max(widest, Fonts.measure(lines.get(i), mono));
            }
            if (!joined.toString().equals(text)) {
                System.out.println("  [FAIL] 「" + it.cn + "」折行丢了字：\n"
                        + "         原文 " + text + "\n"
                        + "         拼回 " + joined);
                bad++;
            }
            if (widest > inner + 1e-3f) {
                System.out.println("  [FAIL] 「" + it.cn + "」有行溢出："
                        + String.format(java.util.Locale.ROOT, "%.1f > %.1f", widest, inner));
                bad++;
            }
            if (lines.size > longestLines) {
                longestLines = lines.size;
                longestName = it.cn;
            }
        }
        System.out.println("  [OK]   " + ItemType.values().length + " 个道具的说明全部无损、不溢出"
                + "（最多 " + longestLines + " 行：" + longestName + "）");

        // ---- 2) 长中文句必须真的折成多行 ----
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append('测');
        }
        Array<String> longLines = Fonts.wrapText(sb.toString(), inner, mono);
        int perLine = (int) (inner / 17f);
        if (longLines.size < 2) {
            System.out.println("  [FAIL] 120 个字挤在 " + longLines.size + " 行里，等于没折行");
            bad++;
        } else {
            int minLen = Integer.MAX_VALUE;
            int maxLen = 0;
            for (int i = 0; i < longLines.size; i++) {
                maxLen = Math.max(maxLen, longLines.get(i).length());
                // 最后一行本来就短，不参与"每行必须接近满宽"的判定
                if (i < longLines.size - 1) {
                    minLen = Math.min(minLen, longLines.get(i).length());
                }
            }
            if (minLen == Integer.MAX_VALUE) {
                minLen = maxLen;
            }
            System.out.println("  [OK]   120 字折成 " + longLines.size + " 行"
                    + "（每行 " + minLen + "~" + maxLen + " 字，理论上限 " + perLine + " 字）");
            if (maxLen > perLine) {
                System.out.println("  [FAIL] 有行超过理论上限，宽度算错了");
                bad++;
            }
            // 除最后一行外都应接近满宽，否则就是"每行一个字"式的错误折行
            if (minLen < perLine - 2) {
                System.out.println("  [FAIL] 有非末行只有 " + minLen + " 个字，折行太早");
                bad++;
            }
        }

        // ---- 3) 中文标点不该跑到行首 ----
        String punct = "一二三四五六七八九，十";  // ，正好会被挤到下一行开头
        Array<String> pl = Fonts.wrapText(punct, 9 * 17f, mono);   // 只能放下 9 个字
        boolean punctHead = pl.size > 1 && !pl.get(1).isEmpty()
                && pl.get(1).charAt(0) == '，';
        if (punctHead) {
            System.out.println("  [FAIL] 逗号被折到了行首（避头处理没生效）");
            bad++;
        } else {
            System.out.println("  [OK]   收尾标点不会被折到行首");
        }
        StringBuilder pj = new StringBuilder();
        for (int i = 0; i < pl.size; i++) {
            pj.append(pl.get(i));
        }
        if (!pj.toString().equals(punct)) {
            System.out.println("  [FAIL] 含标点的句子折行后丢了字");
            bad++;
        }

        // ---- 4) 宽度为 0 / 空串的边界 ----
        Array<String> empty = Fonts.wrapText("", inner, mono);
        if (empty.size != 1 || !empty.get(0).isEmpty()) {
            System.out.println("  [FAIL] 空串折行应返回恰好一个空行");
            bad++;
        }
        Array<String> zero = Fonts.wrapText("中文", 0f, mono);
        if (zero.size != 1 || !zero.get(0).equals("中文")) {
            System.out.println("  [FAIL] 宽度为 0 时应原样返回（不能把字吃掉）");
            bad++;
        }
        if (bad == 0) {
            System.out.println("  [OK]   空串 / 零宽度边界正常");
        }

        System.out.println(bad == 0 ? "  折行自检通过" : "  折行自检失败 " + bad + " 项");
        return bad;
    }
}
