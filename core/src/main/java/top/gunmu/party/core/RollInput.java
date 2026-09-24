package top.gunmu.party.core;

import com.badlogic.gdx.math.Vector3;

/**
 * 抽象输入。真人（键盘/触屏摇杆）与 AI 走同一套结构，
 * 因此 AI 不可能作弊，也便于后续接联机由服务端接管。
 * 对应文档：docs/05-TECH-ARCHITECTURE.md §6、docs/06-DECISIONS.md D20
 */
public final class RollInput {

    /** 摇杆方向（世界空间 XZ 平面），模长 0..1。 */
    public final Vector3 move = new Vector3();
    /** 本帧是否按下跳跃（边沿）。 */
    public boolean jump;
    /** 本帧是否按下道具（边沿）。 */
    public boolean useItem;
    /** 本帧是否按下决战技（边沿）。 */
    public boolean useUltimate;
    /** 本帧是否按下加速（边沿）。键盘 Shift / 触屏「加速」键。 */
    public boolean dash;
    /**
     * 本帧是否按下普通攻击（边沿）。键盘 {@code J} / 触屏「攻击」键。
     *
     * <p>唯一的消费点是 {@code core.AttackSystem#tick}（读到就清，D43）。
     */
    public boolean attack;

    public void clear() {
        move.setZero();
        jump = false;
        useItem = false;
        useUltimate = false;
        dash = false;
        attack = false;
    }

    /** 清空边沿型按键，保留摇杆（边沿键在消费后调用）。 */
    public void clearEdges() {
        jump = false;
        useItem = false;
        useUltimate = false;
        dash = false;
        attack = false;
    }

    public float strength() {
        float m = (float) Math.sqrt(move.x * move.x + move.z * move.z);
        return Math.min(1f, m);
    }
}
