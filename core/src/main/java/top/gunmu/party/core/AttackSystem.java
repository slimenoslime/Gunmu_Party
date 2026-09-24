package top.gunmu.party.core;

import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;
import top.gunmu.party.items.ItemSystem;

/**
 * 普通攻击系统（决赛关专属）。
 *
 * <p>需求来自用户口述：「添加攻击技能，5 秒冷却对扇形内的敌人造成 15 点伤害，
 * 只在决赛关可用」。
 *
 * <p>三条实现要点：
 *
 * <ol>
 *   <li><b>只在 L5 开放</b>：由 {@link #enabled} 把关，而它由 {@code LevelDirector}
 *       按 {@code levelIndex >= GameConfig.ATTACK_MIN_LEVEL} 每关设置。
 *       把"关卡属性"和"滚木状态"分开是有意的 —— {@code Roll.canAttack()} 只管冷却；</li>
 *   <li><b>唯一消费点</b>（D43）：{@code input.attack} 只在这里被读、被清。
 *       读到的任何一帧都必须清掉，否则按一次会连打；</li>
 *   <li><b>判定是可断言的纯函数</b>：{@link #inCone} 与 {@link #hasTargetInCone}
 *       不碰任何 GL / 随机数，所以 {@code tools/AttackTest} 能无头逐条验证
 *       "正前方命中、背后不命中、超出半径不命中、隔着一层地形不命中"。</li>
 * </ol>
 */
public final class AttackSystem {

    /**
     * 本关是否开放普攻。默认 false —— 只有 L5（决赛关）会打开。
     *
     * <p>默认关着是刻意的：任何"忘了设置"的路径都会退化成"没有普攻"（无害），
     * 而不是"所有关卡都能打人"（那是玩法事故）。
     */
    public boolean enabled;

    /** 本局累计挥击次数（含 AI）。自检靠它断言"决赛关的攻击链路真的通了"。 */
    public int attackCount;

    public void reset() {
        attackCount = 0;
    }

    /**
     * 每逻辑帧调用一次。**这是 {@code input.attack} 的唯一消费点**。
     *
     * <p>注意顺序：先清输入、再判定能不能放 —— 与 {@code ItemSystem.tickUses}
     * 完全同构（拿着道具但还没冷却完也不会把输入留到下一帧）。
     */
    public void tick(Array<Roll> rolls, Combat combat) {
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.input.attack) {
                continue;
            }
            r.input.attack = false;
            if (!enabled || !r.canAttack()) {
                continue;
            }
            if (swing(r, rolls, combat)) {
                attackCount++;
            } else {
                // 挥空也要吃冷却：否则空挥零成本，玩家会一直按着刷
                r.attackCd = GameConfig.ATTACK_COOLDOWN;
            }
        }
    }

    /**
     * 挥击一次。
     *
     * @return 是否命中了至少一个目标
     */
    public boolean swing(Roll self, Array<Roll> rolls, Combat combat) {
        self.attackCd = GameConfig.ATTACK_COOLDOWN;
        self.attackFlash = GameConfig.ATTACK_FLASH;

        float fx = ItemSystem.forwardX(self);
        float fz = ItemSystem.forwardZ(self);
        int hits = 0;
        // 实体遍历按 ID 升序（D21）：rolls 由 LevelDirector 保证有序
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == self || !o.onField()) {
                continue;
            }
            if (!inCone(self, o, fx, fz)) {
                continue;
            }
            hits++;
            combat.deal(o, self, GameConfig.ATTACK_DAMAGE, DamageSource.ATTACK, 0);
            if (GameConfig.ATTACK_KNOCKBACK > 0f) {
                float dx = o.pos.x - self.pos.x;
                float dz = o.pos.z - self.pos.z;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d > 1e-3f) {
                    float k = GameConfig.ATTACK_KNOCKBACK * o.knockbackMultiplier();
                    o.vel.x += dx / d * k;
                    o.vel.z += dz / d * k;
                }
            }
        }
        if (hits > 0) {
            // 挥击命中一样会暴露自己（与"主动攻击提前解除隐身"同一条设计口径）
            self.hiddenTimer = 0f;
        }
        return hits > 0;
    }

    // ================================================================== 判定

    /**
     * 目标是否落在以 {@code self} 为顶点、朝向 {@code (fx, fz)} 的扇形里。
     *
     * @param fx 朝向单位向量 x（由 {@code ItemSystem.forwardX} 给出）
     * @param fz 朝向单位向量 z
     */
    public static boolean inCone(Roll self, Roll other, float fx, float fz) {
        float dx = other.pos.x - self.pos.x;
        float dz = other.pos.z - self.pos.z;
        return inCone(dx, dz, other.pos.y - self.pos.y, fx, fz);
    }

    /**
     * 扇形判定的**纯函数**版本（无 Roll、无 GL，可直接断言）。
     *
     * <p>判据三条，缺一不可：
     * <ul>
     *   <li>水平距离 ≤ {@link GameConfig#ATTACK_RANGE}（0 距离视为命中 —— 重叠时也要打得着）；</li>
     *   <li>与朝向的夹角 ≤ {@link GameConfig#ATTACK_HALF_ANGLE}；</li>
     *   <li>高度差 ≤ {@link GameConfig#ATTACK_HEIGHT_TOLERANCE}，否则站在断崖上能把下面一层的人打死。</li>
     * </ul>
     *
     * <p>刻意**不检查可见性**：这是一次实打实的挥击，不是"锁定"，所以隐身不能免疫它 ——
     * 否则隐身就成了绝对无解的贴脸手段（而隐身本身已经有一次 1.8 倍破隐一击）。
     */
    public static boolean inCone(float dx, float dz, float dy, float fx, float fz) {
        if (Math.abs(dy) > GameConfig.ATTACK_HEIGHT_TOLERANCE) {
            return false;
        }
        float d2 = dx * dx + dz * dz;
        float range = GameConfig.ATTACK_RANGE;
        if (d2 > range * range) {
            return false;
        }
        if (d2 < 1e-6f) {
            return true;
        }
        float d = (float) Math.sqrt(d2);
        float cosLimit = (float) Math.cos(Math.toRadians(GameConfig.ATTACK_HALF_ANGLE));
        return (dx * fx + dz * fz) / d >= cosLimit;
    }

    /**
     * 扇形里是否有目标 —— 给 AI 用（AI 与真人共用同一个输入结构，D20）。
     *
     * <p>故意复用 {@link #inCone}：AI 判断的范围与真人打到的范围必须是同一个，
     * 否则就会出现"AI 隔着 8 米打我、我 5 米打不到他"。
     */
    public static boolean hasTargetInCone(Roll self, Array<Roll> rolls) {
        float fx = ItemSystem.forwardX(self);
        float fz = ItemSystem.forwardZ(self);
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == self || !o.onField()) {
                continue;
            }
            if (inCone(self, o, fx, fz)) {
                return true;
            }
        }
        return false;
    }

    /** 扇形内目标的最近距离（没有目标时返回 {@code -1}）。仅用于自检与调试。 */
    public static float nearestInCone(Roll self, Array<Roll> rolls) {
        float fx = ItemSystem.forwardX(self);
        float fz = ItemSystem.forwardZ(self);
        float best = -1f;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == self || !o.onField()) {
                continue;
            }
            if (!inCone(self, o, fx, fz)) {
                continue;
            }
            float dx = o.pos.x - self.pos.x;
            float dz = o.pos.z - self.pos.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (best < 0f || d < best) {
                best = d;
            }
        }
        return best;
    }
}
