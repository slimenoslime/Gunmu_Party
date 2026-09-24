package top.gunmu.party.items;

import com.badlogic.gdx.math.Vector3;

/**
 * 召唤物。两种行为：
 * <ul>
 *   <li>{@code LOBSTER}（养龙虾）：跟随主人，自动追撞最近的敌人。</li>
 *   <li>{@code HAKIMI}（哈基米）：沿固定方向直线冲 12m，碰到敌人造成伤害并击退。</li>
 * </ul>
 */
public final class Summon {

    public enum Kind {
        LOBSTER, HAKIMI
    }

    public static final float LOBSTER_RADIUS = 0.55f;
    public static final float LOBSTER_SPEED = 11f;
    public static final float LOBSTER_DAMAGE = 10f;
    public static final float LOBSTER_LIFE = 15f;

    public static final float HAKIMI_RADIUS = 0.42f;
    public static final float HAKIMI_SPEED = 15f;
    public static final float HAKIMI_DAMAGE = 8f;
    public static final float HAKIMI_KNOCKBACK = 5f;
    public static final float HAKIMI_RANGE = 12f;

    public final Kind kind;
    public final int ownerId;
    public final Vector3 pos = new Vector3();
    public final Vector3 dir = new Vector3(1f, 0f, 0f);
    public float life;
    public float maxLife;
    public float travelled;
    /** 连续撞击的冷却（龙虾可以反复撞）。 */
    public float hitCd;
    /** 伤害是否已经打出去过（哈基米一次即消失，龙虾可反复）。 */
    public boolean dead;

    public Summon(Kind kind, int ownerId) {
        this.kind = kind;
        this.ownerId = ownerId;
        this.maxLife = kind == Kind.LOBSTER ? LOBSTER_LIFE : HAKIMI_RANGE / HAKIMI_SPEED;
        this.life = maxLife;
    }

    public float radius() {
        return kind == Kind.LOBSTER ? LOBSTER_RADIUS : HAKIMI_RADIUS;
    }
}
