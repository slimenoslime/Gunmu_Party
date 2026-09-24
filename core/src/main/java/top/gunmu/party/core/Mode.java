package top.gunmu.party.core;

/**
 * 三种模式决定"这一张图里，碎掉会发生什么"。
 * 对应文档：docs/00-OVERVIEW.md §3、docs/06-DECISIONS.md D5/D6
 */
public enum Mode {
    /** 竞速：有终点线，碎掉后重生到最近存档点。 */
    RACE("竞速"),
    /** 生存：无终点，碎掉 = 计一次死亡且不重生，死亡满阈值触发结算。 */
    SURVIVAL("生存"),
    /** 竞技：场地混战，按人头/贡献排序，复活策略由关卡决定。 */
    ARENA("竞技");

    public final String cn;

    Mode(String cn) {
        this.cn = cn;
    }
}
