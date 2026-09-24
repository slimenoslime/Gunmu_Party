package top.gunmu.party.items;

import com.badlogic.gdx.graphics.Color;

/**
 * 道具稀有度。权重区间见 docs/03-ITEMS.md §3。
 */
public enum Rarity {
    COMMON("普通", new Color(0xefe6d2ff)),
    RARE("稀有", new Color(0x5fc2f0ff)),
    EPIC("史诗", new Color(0xb072ffFF));

    public final String cn;
    public final Color color;

    Rarity(String cn, Color color) {
        this.cn = cn;
        this.color = color;
    }
}
