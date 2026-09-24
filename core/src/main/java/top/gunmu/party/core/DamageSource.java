package top.gunmu.party.core;

/** 伤害来源。用于结算顺序与统计（docs/03-ITEMS.md §6）。 */
public enum DamageSource {
    /** 高速撞击（被动）。 */
    IMPACT("撞击"),
    /** 普通攻击（决赛关的扇形挥击，键盘 J / 触屏「攻击」）。 */
    ATTACK("攻击"),
    /** 坠落伤害。 */
    FALL("坠落"),
    /** 地图机关。 */
    OBSTACLE("机关"),
    /** 道具。 */
    ITEM("道具"),
    /** 决战技。 */
    ULTIMATE("决战技"),
    /** 熔岩地面。 */
    LAVA("熔岩"),
    /** 毒雾。 */
    POISON("毒雾"),
    /** 溺水 / 涨水。 */
    DROWN("涨水"),
    /** 掉出地图。 */
    OUT_OF_MAP("坠落深渊");

    public final String cn;

    DamageSource(String cn) {
        this.cn = cn;
    }
}
