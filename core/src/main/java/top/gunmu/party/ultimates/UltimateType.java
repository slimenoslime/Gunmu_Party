package top.gunmu.party.ultimates;

import com.badlogic.gdx.graphics.Color;

/**
 * 8 个决战技。只在 L4 / L5 可用。
 * 对应文档：docs/04-ULTIMATE-SKILLS.md（数值以该文档为准）
 */
public enum UltimateType {

    SPRING_SAUSAGE("春秋肠",
            "回到 5 秒前的位置和血量",
            40f, new Color(0xffd166ff),
            "每逻辑帧写入 300 帧环形缓冲；施放时回滚位置与血量，并给 1s 无敌。"),

    DAO_DUN("我的刀盾",
            "攻击力与防御力 +50%，持续 5 秒",
            30f, new Color(0x8ecae6ff),
            "撞击伤害 x1.5；受到伤害 x0.6667。"),

    HAOCHEN("我的世界皓宸",
            "复制对面的技能，复制到的技能用一次就消失",
            25f, new Color(0x9b5de5ff),
            "锁定 3~12m 内 60 度锥形中最接近的敌人，复制其决战技，可用 1 次。"),

    ZHENG_TAI("正太扭腰",
            "使用后不能动但免疫其他人攻击，持续 3 秒",
            25f, new Color(0xf15bb5ff),
            "移速归零、免疫伤害与控制，但仍会被物理推挤。"),

    ZHONGGUOREN_FEI("中国人能飞",
            "施法 2 秒，结束后范围内所有敌人被击飞并造成伤害",
            35f, new Color(0x00bbf9ff),
            "前摇 2s（移速 -75%，地面出现收缩光圈），爆发半径 9m：击飞 + 25 真伤。"),

    NIURouMIAN("忘情牛肉面",
            "使用后别人都会忘记你，隐身 5 秒",
            30f, new Color(0xfee440ff),
            "免锁定 5s；受到伤害或主动攻击会提前解除。"),

    MJ("mj",
            "朝施法方向发射逐渐变大的蜘蛛网，最大 2.5 倍，网住则无法移动",
            30f, new Color(0xffffffFF),
            "直线飞行 16 m/s，半径 0.8 -> 2.0m，命中定身 1.2~3.0s。"),

    KUNKUN("坤坤",
            "先 rap 眩晕周围敌人 0.5 秒，0.5 秒后朝最近的 2 个敌人发射慢速篮球",
            30f, new Color(0xff9f1cff),
            "半径 6m 眩晕 0.5s；随后 2 个篮球（8 m/s，20 伤 + 击退）。");

    public final String cn;
    public final String tagline;
    public final float cooldown;
    public final Color color;
    public final String detail;

    UltimateType(String cn, String tagline, float cooldown, Color color, String detail) {
        this.cn = cn;
        this.tagline = tagline;
        this.cooldown = cooldown;
        this.color = color;
        this.detail = detail;
    }

    public static final UltimateType[] ALL = values();

    /** 计数校验：8 个（docs/04 §2）。 */
    public static final int EXPECTED_COUNT = 8;

    public static UltimateType byId(String id) {
        for (UltimateType t : ALL) {
            if (t.name().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }
}
