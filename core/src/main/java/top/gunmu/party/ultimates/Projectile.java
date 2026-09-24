package top.gunmu.party.ultimates;

import com.badlogic.gdx.math.Vector3;

/**
 * 决战技的飞行物。
 * <ul>
 *   <li>{@code WEB}（mj）：直线飞行、半径线性长大、命中定身。</li>
 *   <li>{@code BALL}（坤坤）：直线飞行、速度慢、命中伤害 + 击退。</li>
 * </ul>
 */
public final class Projectile {

    public enum Kind {
        WEB, BALL
    }

    public final Kind kind;
    public final int ownerId;
    public final Vector3 pos = new Vector3();
    public final Vector3 dir = new Vector3(1f, 0f, 0f);
    public float speed;
    public float radius;
    public float maxRadius;
    public float range;
    public float travelled;
    public float damage;
    public float knockback;
    /** 命中造成的定身时长（仅 WEB 有意义）。 */
    public float rootTime;
    public boolean dead;

    public Projectile(Kind kind, int ownerId) {
        this.kind = kind;
        this.ownerId = ownerId;
    }
}
