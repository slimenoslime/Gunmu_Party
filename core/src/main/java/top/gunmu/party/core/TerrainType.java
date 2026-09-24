package top.gunmu.party.core;

import com.badlogic.gdx.graphics.Color;

/**
 * 地面材质。决定摩擦、渲染颜色与程序化贴图。
 * 对应文档：docs/01-CORE-RULES.md §2.3
 */
public enum TerrainType {
    GRASS("草地", 1.00f, new Color(0x4a7c3aff)),
    ROCK("岩面", 1.20f, new Color(0x6f6f78ff)),
    ICE("冰面", 0.35f, new Color(0xa8dcf0ff)),
    MUD("泥坑", 3.00f, new Color(0x5a4632ff)),
    SAND("沙地", 1.80f, new Color(0xd9c48aff)),
    LAVA("熔岩", 1.00f, new Color(0xd8451fff)),
    /** 深渊：不可行走，掉下去即碎。 */
    VOID("深渊", 1.00f, new Color(0x14141cff));

    public final String cn;
    public final float friction;
    public final Color color;

    TerrainType(String cn, float friction, Color color) {
        this.cn = cn;
        this.friction = friction;
        this.color = color;
    }

    public boolean walkable() {
        return this != VOID;
    }

    public static final TerrainType[] ALL = values();
}
