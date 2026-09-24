package top.gunmu.party.ultimates;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import java.util.Random;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Combat;
import top.gunmu.party.core.DamageSource;
import top.gunmu.party.core.Roll;
import top.gunmu.party.items.ItemSystem;
import top.gunmu.party.map.MapDef;

/**
 * 决战技系统（仅 L4 / L5 可用）。
 * 对应文档：docs/04-ULTIMATE-SKILLS.md，数值以该文档为准。
 */
public final class UltimateSystem {

    public final Array<Projectile> projectiles = new Array<>();

    private final Vector3 tmp = new Vector3();
    private final float[] hpOut = new float[1];

    public void reset() {
        projectiles.clear();
    }

    // ================================================================== 主循环

    public void tick(MapDef map, Array<Roll> rolls, Combat combat, Random rng, float dt) {
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (r.ultimateCd > 0f) {
                r.ultimateCd = Math.max(0f, r.ultimateCd - dt);
            }
            if (r.castTimer > 0f) {
                r.castTimer -= dt;
                if (r.castTimer <= 0f) {
                    r.castTimer = 0f;
                    zhongguorenBurst(r, rolls, combat);
                }
            }
            if (r.kunKunDelay >= 0f) {
                r.kunKunDelay -= dt;
                if (r.kunKunDelay <= 0f) {
                    r.kunKunDelay = -1f;
                    fireBalls(r, rolls);
                }
            }
            if (r.input.useUltimate) {
                r.input.useUltimate = false;
                tryCast(r, map, rolls, combat);
            }
        }
        tickProjectiles(map, rolls, combat, dt);
    }

    /** 施法被碎掉：取消技能并返还一半 CD（docs/06 D24）。 */
    public void onCastInterrupted(Roll r) {
        if (r.castTimer > 0f) {
            r.castTimer = 0f;
            if (r.ultimate != null) {
                r.ultimateCd = Math.max(r.ultimateCd, r.ultimate.cooldown * 0.5f);
            }
        }
    }

    // ================================================================== 施放

    private void tryCast(Roll r, MapDef map, Array<Roll> rolls, Combat combat) {
        if (!r.canUseUltimate()) {
            return;
        }
        UltimateType type;
        if (r.stolenCharges > 0 && r.stolen != null) {
            type = r.stolen;
            r.stolen = null;
            r.stolenCharges = 0;
        } else if (r.ultimate != null && r.ultimateCd <= 0f) {
            type = r.ultimate;
            r.ultimateCd = type.cooldown;
        } else {
            return;
        }
        // 忘情牛肉面：主动施放会暴露自己
        r.hiddenTimer = 0f;
        cast(r, type, map, rolls, combat);
    }

    private void cast(Roll r, UltimateType type, MapDef map, Array<Roll> rolls,
                      Combat combat) {
        switch (type) {
            case SPRING_SAUSAGE:
                springSausage(r, map, rolls, combat);
                break;
            case DAO_DUN:
                r.daoDunTimer = GameConfig.DAODUN_DURATION;
                // 刀盾本来是"纯自身强化"。补一段施放瞬间的身周震击：
                // 否则在混战里放完这个技能，除了自己变硬之外什么都没发生。
                burst(r, rolls, combat, GameConfig.DAODUN_BURST_RADIUS,
                        GameConfig.DAODUN_BURST_DAMAGE, GameConfig.DAODUN_BURST_KNOCKBACK);
                break;
            case HAOCHEN:
                haochen(r, rolls, combat);
                break;
            case ZHENG_TAI:
                r.zhengTaiTimer = GameConfig.ZHENGTAI_DURATION;
                r.vel.x = 0f;
                r.vel.z = 0f;
                // 扭腰 = 把贴在身上的人甩开。伤害压低（10），主要靠 11 m/s 的径向击退。
                burst(r, rolls, combat, GameConfig.ZHENGTAI_BURST_RADIUS,
                        GameConfig.ZHENGTAI_BURST_DAMAGE, GameConfig.ZHENGTAI_BURST_KNOCKBACK);
                break;
            case ZHONGGUOREN_FEI:
                r.castTimer = GameConfig.ZHONGGUOREN_CAST;
                break;
            case NIURouMIAN:
                r.hiddenTimer = GameConfig.NIURoumian_DURATION;
                break;
            case MJ:
                launchWeb(r);
                break;
            case KUNKUN:
                kunKunRap(r, rolls);
                r.kunKunDelay = GameConfig.KUNKUN_SECOND_DELAY;
                break;
            default:
                break;
        }
    }

    /**
     * 近身震击：半径内所有敌人吃一次**普通伤害** + 径向击退。
     *
     * <p>这是给"没有攻击能力"的决战技补的那一段（玩家报过「大多数技能都没有攻击能力」）。
     * 走 {@link Combat#deal} 而不是 {@code dealTrue}：只有中国人能飞那条按 D15 是真伤，
     * 其余附加伤害都该吃刀盾 / 破防 / 蛋挞等增减伤，否则数值会失控。
     */
    private void burst(Roll self, Array<Roll> rolls, Combat combat, float radius,
                       float damage, float knockback) {
        float r2 = radius * radius;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == self || !o.onField()) {
                continue;
            }
            float dx = o.pos.x - self.pos.x;
            float dz = o.pos.z - self.pos.z;
            float d2 = dx * dx + dz * dz;
            if (d2 > r2) {
                continue;
            }
            float d = (float) Math.sqrt(d2);
            if (d > 1e-3f) {
                float k = knockback * o.knockbackMultiplier();
                o.vel.x += dx / d * k;
                o.vel.z += dz / d * k;
            }
            combat.deal(o, self, damage, DamageSource.ULTIMATE, 0);
        }
    }

    // ---- 春秋肠 ----

    private void springSausage(Roll r, MapDef map, Array<Roll> rolls, Combat combat) {
        // 冲击波留在**出发地**：回到 5 秒前本身是逃脱技，那就让"离开"这个动作有代价
        // —— 追着你打的人会吃一次 10 伤 + 击退。
        float fromX = r.pos.x;
        float fromZ = r.pos.z;
        float fromY = r.pos.y;
        boolean ok = r.readHistory(GameConfig.CHUNQIU_FRAMES - 1, tmp, hpOut);
        if (ok && map.field.walkableAt(tmp.x, tmp.z)) {
            r.pos.set(tmp.x, tmp.y, tmp.z);
            r.vel.setZero();
            r.hp = Math.max(1f, Math.min(hpOut[0], GameConfig.MAX_HP));
        } else {
            // 历史不足或落点悬空：回退到出生点
            if (map.spawns.size > 0) {
                r.pos.set(map.spawns.get(r.id % map.spawns.size));
            }
            r.vel.setZero();
            r.hp = Math.max(1f, r.hp);
        }
        r.grounded = false;
        r.invulnTimer = Math.max(r.invulnTimer, GameConfig.CHUNQIU_INVULN);
        shockwaveAt(rolls, combat, r, fromX, fromY, fromZ);
    }

    /** 以某个历史位置为中心放冲击波（施法者本人不受影响，他早就走了）。 */
    private void shockwaveAt(Array<Roll> rolls, Combat combat, Roll self,
                             float x, float y, float z) {
        float radius = GameConfig.CHUNQIU_BURST_RADIUS;
        float r2 = radius * radius;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == self || !o.onField()) {
                continue;
            }
            float dx = o.pos.x - x;
            float dy = o.pos.y - y;
            float dz = o.pos.z - z;
            if (dx * dx + dz * dz > r2 || Math.abs(dy) > 4f) {
                continue;
            }
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > 1e-3f) {
                float k = GameConfig.CHUNQIU_BURST_KNOCKBACK * o.knockbackMultiplier();
                o.vel.x += dx / d * k;
                o.vel.z += dz / d * k;
            }
            combat.deal(o, self, GameConfig.CHUNQIU_BURST_DAMAGE, DamageSource.ULTIMATE, 0);
        }
    }

    // ---- 我的世界皓宸 ----

    private void haochen(Roll r, Array<Roll> rolls, Combat combat) {
        Roll target = pickConeTarget(r, rolls,
                GameConfig.HAOCHEN_RANGE_MIN, GameConfig.HAOCHEN_RANGE_MAX,
                GameConfig.HAOCHEN_CONE_DEG);
        if (target == null) {
            target = ItemSystem.nearestEnemyAnywhere(r, rolls, GameConfig.HAOCHEN_RANGE_MAX);
        }
        boolean copied = false;
        if (target != null && target.ultimate != null
                && target.ultimate != UltimateType.HAOCHEN) {
            r.stolen = target.ultimate;
            r.stolenCharges = 1;
            copied = true;
        }
        // 「顺手牵羊」—— 偷不到也不至于白放：锁定到的那个人照样吃一下。
        // 这一下是**普通伤害**，且就算偷窃成功也会打（技能名字里本来就有"我的世界"）。
        if (target != null) {
            combat.deal(target, r, GameConfig.HAOCHEN_STRIKE_DAMAGE,
                    DamageSource.ULTIMATE, 0);
        }
        if (!copied && r.ultimate != null) {
            // 施放失败：CD 立即返还一半，不做空放惩罚
            r.ultimateCd = Math.max(0f, r.ultimateCd * GameConfig.CD_REFUND_ON_BREAK);
        }
    }

    private Roll pickConeTarget(Roll self, Array<Roll> rolls, float min, float max,
                                float halfAngleDeg) {
        float fx = ItemSystem.forwardX(self);
        float fz = ItemSystem.forwardZ(self);
        float cosLimit = (float) Math.cos(Math.toRadians(halfAngleDeg));
        Roll best = null;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == self || !o.alive || o.eliminated || o.respawnTimer > 0f) {
                continue;
            }
            if (!ItemSystem.visibleTo(self, o)) {
                continue;
            }
            float dx = o.pos.x - self.pos.x;
            float dz = o.pos.z - self.pos.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < min || d > max || d < 1e-4f) {
                continue;
            }
            if ((dx * fx + dz * fz) / d < cosLimit) {
                continue;
            }
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    // ---- 中国人能飞 ----

    private void zhongguorenBurst(Roll r, Array<Roll> rolls, Combat combat) {
        float r2 = GameConfig.ZHONGGUOREN_RADIUS * GameConfig.ZHONGGUOREN_RADIUS;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == r || !o.alive || o.eliminated || o.respawnTimer > 0f) {
                continue;
            }
            float dx = o.pos.x - r.pos.x;
            float dz = o.pos.z - r.pos.z;
            float d2 = dx * dx + dz * dz;
            if (d2 > r2) {
                continue;
            }
            float d = (float) Math.sqrt(d2);
            if (d > 1e-3f) {
                o.vel.x += dx / d * GameConfig.ZHONGGUOREN_LAUNCH_RADIAL;
                o.vel.z += dz / d * GameConfig.ZHONGGUOREN_LAUNCH_RADIAL;
            }
            o.vel.y = GameConfig.ZHONGGUOREN_LAUNCH_UP;
            o.grounded = false;
            combat.dealTrue(o, r, GameConfig.ZHONGGUOREN_DAMAGE, DamageSource.ULTIMATE);
        }
        // 施法者站稳
        r.invulnTimer = Math.max(r.invulnTimer, 0.3f);
    }

    // ---- mj 蜘蛛网 ----

    private void launchWeb(Roll r) {
        Projectile p = new Projectile(Projectile.Kind.WEB, r.id);
        float fx = ItemSystem.forwardX(r);
        float fz = ItemSystem.forwardZ(r);
        p.dir.set(fx, 0f, fz);
        p.pos.set(r.pos.x + fx * 1.0f, r.pos.y, r.pos.z + fz * 1.0f);
        p.speed = GameConfig.WEB_SPEED;
        p.radius = GameConfig.WEB_RADIUS_START;
        p.maxRadius = GameConfig.WEB_RADIUS_MAX;
        p.range = GameConfig.WEB_RANGE;
        p.damage = GameConfig.WEB_HIT_DAMAGE;
        projectiles.add(p);
    }

    // ---- 坤坤 ----

    private void kunKunRap(Roll r, Array<Roll> rolls) {
        float r2 = GameConfig.KUNKUN_RAP_RADIUS * GameConfig.KUNKUN_RAP_RADIUS;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == r || !o.alive || o.eliminated || o.respawnTimer > 0f) {
                continue;
            }
            float dx = o.pos.x - r.pos.x;
            float dz = o.pos.z - r.pos.z;
            if (dx * dx + dz * dz > r2) {
                continue;
            }
            o.addStun(GameConfig.KUNKUN_RAP_STUN);
        }
    }

    private void fireBalls(Roll r, Array<Roll> rolls) {
        // 取最近的 2 个敌人（隐身者不发）
        Roll first = null;
        Roll second = null;
        float d1 = Float.MAX_VALUE;
        float d2 = Float.MAX_VALUE;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == r || !o.alive || o.eliminated || o.respawnTimer > 0f) {
                continue;
            }
            if (!ItemSystem.visibleTo(r, o)) {
                continue;
            }
            float dx = o.pos.x - r.pos.x;
            float dz = o.pos.z - r.pos.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > GameConfig.BALL_RANGE) {
                continue;
            }
            if (d < d1) {
                d2 = d1;
                second = first;
                d1 = d;
                first = o;
            } else if (d < d2) {
                d2 = d;
                second = o;
            }
        }
        shootBallAt(r, first);
        shootBallAt(r, second);
    }

    private void shootBallAt(Roll r, Roll target) {
        if (target == null) {
            return;
        }
        float dx = target.pos.x - r.pos.x;
        float dz = target.pos.z - r.pos.z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-3f) {
            return;
        }
        Projectile p = new Projectile(Projectile.Kind.BALL, r.id);
        p.dir.set(dx / d, 0f, dz / d);
        p.pos.set(r.pos.x + p.dir.x * 0.9f, r.pos.y + 0.5f, r.pos.z + p.dir.z * 0.9f);
        p.speed = GameConfig.BALL_SPEED;
        p.radius = GameConfig.BALL_RADIUS;
        p.maxRadius = GameConfig.BALL_RADIUS;
        p.range = GameConfig.BALL_RANGE;
        p.damage = GameConfig.BALL_DAMAGE;
        p.knockback = GameConfig.BALL_KNOCKBACK;
        projectiles.add(p);
    }

    // ================================================================== 投射物

    private void tickProjectiles(MapDef map, Array<Roll> rolls, Combat combat, float dt) {
        for (int i = projectiles.size - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            float step = p.speed * dt;
            p.pos.x += p.dir.x * step;
            p.pos.z += p.dir.z * step;
            p.travelled += step;

            if (p.kind == Projectile.Kind.WEB) {
                float t = MathUtils.clamp(p.travelled / p.range, 0f, 1f);
                p.radius = GameConfig.WEB_RADIUS_START
                        + (GameConfig.WEB_RADIUS_MAX - GameConfig.WEB_RADIUS_START) * t;
                p.rootTime = p.radius / GameConfig.WEB_RADIUS_MAX * GameConfig.WEB_ROOT_MAX;
            }

            if (p.travelled >= p.range) {
                projectiles.removeIndex(i);
                continue;
            }
            if (!map.field.walkableAt(p.pos.x, p.pos.z)) {
                projectiles.removeIndex(i);
                continue;
            }
            float g = map.field.heightAt(p.pos.x, p.pos.z) + 0.4f;
            if (p.pos.y < g) {
                p.pos.y = g;
            }

            Roll owner = findById(rolls, p.ownerId);
            boolean hit = false;
            for (int j = 0; j < rolls.size; j++) {
                Roll o = rolls.get(j);
                if (o.id == p.ownerId || !o.alive || o.eliminated || o.respawnTimer > 0f) {
                    continue;
                }
                float dx = o.pos.x - p.pos.x;
                float dz = o.pos.z - p.pos.z;
                float dy = o.pos.y - p.pos.y;
                float rr = p.radius + GameConfig.LOG_COLLIDE_RADIUS;
                if (dx * dx + dz * dz > rr * rr || Math.abs(dy) > 2.2f) {
                    continue;
                }
                combat.resolveProjectile(p, o, owner);
                hit = true;
                break;
            }
            if (hit) {
                projectiles.removeIndex(i);
            }
        }
    }

    private Roll findById(Array<Roll> rolls, int id) {
        for (int i = 0; i < rolls.size; i++) {
            if (rolls.get(i).id == id) {
                return rolls.get(i);
            }
        }
        return null;
    }
}
