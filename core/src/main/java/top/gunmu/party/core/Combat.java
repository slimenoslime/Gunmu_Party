package top.gunmu.party.core;

import com.badlogic.gdx.math.MathUtils;

import top.gunmu.party.GameConfig;
import top.gunmu.party.items.ItemType;
import top.gunmu.party.physics.PhysicsWorld;
import top.gunmu.party.ultimates.Projectile;

/**
 * 伤害结算。所有伤害必须经过这里，以保证 docs/03-ITEMS.md §6 的固定优先级：
 *
 * <pre>
 * 1. 免疫检查（无敌帧 / 正太扭腰 / 背手负鼠免死 / 酱板鸭反转）
 * 2. 伤害修正（刀盾减伤 -> 破防 -> 蛋挞 -> 千薪万苦由攻击方结算）
 * 3. 延迟结算（轻松绷住）
 * 4. 反弹（外耗 50% / 酱板鸭 100%），递归深度上限 1
 * 5. 掉血 / 碎掉
 * 6. 事件派发
 * </pre>
 */
public final class Combat {

    /** 回调：碎掉时需要外部做的事（计死亡、淘汰、重生…）。 */
    public interface DestroyListener {
        void onDestroyed(Roll victim, Roll killer, DamageSource source);
    }

    private final DestroyListener listener;

    public Combat(DestroyListener listener) {
        this.listener = listener;
    }

    // ================================================================== 撞击

    /**
     * 由 {@link PhysicsWorld.CollisionEvent} 计算撞击伤害（单向判定）。
     *
     * <p>伤害只与相对速度有关，与被撞者自身速度无关；攻击方吃 15% 反伤。
     */
    public void resolveImpact(Roll a, Roll b, float relSpeed,
                              PhysicsWorld.CollisionEvent ev) {
        if (relSpeed < GameConfig.HIT_SPEED_THRESHOLD) {
            return;
        }
        if (a.invulnerable() || b.invulnerable() || a.finished || b.finished) {
            return;
        }

        float aToward = towardness(a, b);
        float bToward = towardness(b, a);
        Roll attacker;
        Roll victim;
        if (aToward >= bToward) {
            attacker = a;
            victim = b;
        } else {
            attacker = b;
            victim = a;
        }
        if (victim.invulnerable()) {
            return;
        }

        float dmg = MathUtils.clamp(
                (relSpeed - GameConfig.HIT_SPEED_THRESHOLD) * GameConfig.HIT_DAMAGE_SCALE,
                0f, GameConfig.HIT_DAMAGE_CAP);
        dmg *= attacker.attackMultiplier();
        if (dmg <= 0.05f) {
            // 电子布洛芬期间不能打人：仍推开，但不造成伤害
            return;
        }

        deal(victim, attacker, dmg, DamageSource.IMPACT, 0);
        deal(attacker, victim, dmg * GameConfig.ATTACKER_RECOIL, DamageSource.IMPACT, 1);

        // 忘情牛肉面的"破隐一击"：`Roll.attackMultiplier()` 在隐身期间给了 1.8 倍，
        // 而 D16 规定"主动攻击会提前解除隐身" —— 这里就是那句话第一次真的有落点。
        // 只有真的打到人（对方没在无敌帧里）才暴露，否则隐身会被"撞到无敌的对手"白送掉。
        if (attacker.hiddenTimer > 0f && !victim.invulnerable()) {
            attacker.hiddenTimer = 0f;
        }
    }

    /** 攻击方朝受害者方向的速度分量 / 自身速度，范围 -1..1。 */
    private float towardness(Roll from, Roll to) {
        float sp = from.speed();
        if (sp < 1e-3f) {
            return -2f;
        }
        float dx = to.pos.x - from.pos.x;
        float dz = to.pos.z - from.pos.z;
        float dl = (float) Math.sqrt(dx * dx + dz * dz);
        if (dl < 1e-4f) {
            return 0f;
        }
        return (from.vel.x * dx / dl + from.vel.z * dz / dl) / sp;
    }

    // ================================================================== 通用

    /**
     * 结算一次伤害。
     *
     * @param depth 反弹递归深度，超过 1 不再反弹
     */
    public void deal(Roll victim, Roll attacker, float amount,
                     DamageSource source, int depth) {
        if (amount <= 0f || !victim.alive || victim.eliminated) {
            return;
        }
        // 1) 免疫（无敌帧 / 正太扭腰）
        if (victim.invulnerable()) {
            return;
        }
        if (victim.hasEffect(ItemType.OPOSSUM) && !victim.opossumUsed
                && amount >= victim.hp) {
            victim.opossumUsed = true;
            victim.hp = 1f;
            victim.removeEffect(ItemType.OPOSSUM);
            return;
        }
        if (victim.hasEffect(ItemType.JIANGBAN_DUCK)) {
            victim.removeEffect(ItemType.JIANGBAN_DUCK);
            if (attacker != null && depth == 0) {
                deal(attacker, victim, amount, source, 1);
            }
            return;
        }

        // 2) 修正
        float dmg = amount * victim.damageTakenMultiplier();

        // 3) 延迟结算
        if (victim.hasEffect(ItemType.BENGHZU)) {
            victim.delayedDamage += dmg;
            victim.delayedTimer = 2f;
            return;
        }

        // 4) 反弹
        if (victim.hasEffect(ItemType.EXTERNAL_BURN) && attacker != null && depth == 0) {
            deal(attacker, victim, dmg * 0.5f, source, 1);
            dmg *= 0.5f;
        }

        // 5) 掉血
        victim.hp -= dmg;
        victim.damageTaken += dmg;
        if (attacker != null && attacker != victim) {
            attacker.damageDealt += dmg;
        }

        // 6) 碎掉
        if (victim.hp <= 0f) {
            victim.hp = 0f;
            destroy(victim, attacker, source);
        }
    }

    /**
     * 真伤：不吃任何增减伤，也不触发延迟结算与反弹。
     * 用于 {@code 中国人能飞} 的 25 点爆发（docs/06 D15）。
     */
    public void dealTrue(Roll victim, Roll attacker, float amount, DamageSource source) {
        if (amount <= 0f || !victim.alive || victim.eliminated) {
            return;
        }
        if (victim.invulnerable()) {
            return;
        }
        victim.hp -= amount;
        victim.damageTaken += amount;
        if (attacker != null && attacker != victim) {
            attacker.damageDealt += amount;
        }
        if (victim.hp <= 0f) {
            victim.hp = 0f;
            destroy(victim, attacker, source);
        }
    }

    /** 延迟伤害的结算（轻松绷住 的 2s 队列）。 */
    public void flushDelayed(Roll r, float dt) {
        if (r.delayedTimer > 0f) {
            r.delayedTimer -= dt;
            if (r.delayedTimer <= 0f && r.delayedDamage > 0f) {
                float d = r.delayedDamage;
                r.delayedDamage = 0f;
                r.hp -= d;
                r.damageTaken += d;
                if (r.hp <= 0f) {
                    r.hp = 0f;
                    destroy(r, null, DamageSource.ITEM);
                }
            }
        }
    }

    private void destroy(Roll victim, Roll killer, DamageSource source) {
        if (!victim.alive) {
            return;
        }
        victim.alive = false;
        victim.deaths++;
        victim.clearTransient();
        if (killer != null && killer != victim) {
            killer.kills++;
        }
        if (listener != null) {
            listener.onDestroyed(victim, killer, source);
        }
    }

    /** 子弹命中。 */
    public void resolveProjectile(Projectile p, Roll victim, Roll owner) {
        if (p.kind == Projectile.Kind.BALL) {
            deal(victim, owner, p.damage, DamageSource.ULTIMATE, 0);
            knockback(victim, p.dir, p.knockback);
        } else {
            // 蜘蛛网：定身之外再补 8 点伤害 —— 原来"网住"是纯控制，
            // 网住之后双方都只能干等（玩家报过"大多数技能都没有攻击能力"）。
            victim.addRoot(p.rootTime);
            if (p.damage > 0f) {
                deal(victim, owner, p.damage, DamageSource.ULTIMATE, 0);
            }
        }
    }

    public void knockback(Roll victim, com.badlogic.gdx.math.Vector3 dir, float power) {
        float d = (float) Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (d < 1e-5f) {
            return;
        }
        victim.vel.x += dir.x / d * power;
        victim.vel.z += dir.z / d * power;
    }
}
