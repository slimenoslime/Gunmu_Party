package top.gunmu.party.core;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;
import top.gunmu.party.items.Effect;
import top.gunmu.party.items.ItemType;
import top.gunmu.party.ultimates.UltimateType;

/**
 * 一根滚木的全部状态。
 *
 * <p>本类**不读取输入、不自己产生视觉效果**：输入由外部（真人或 {@code BotBrain}）
 * 写进 {@link #input}，物理由 {@code PhysicsWorld} 推进，渲染由 {@code SceneRenderer} 读取。
 * 这是联机化的前提（docs/05-TECH-ARCHITECTURE.md §7）。
 */
public final class Roll {

    // ------------------------------------------------------------ 身份
    public final int id;
    public final String name;
    public final boolean player;
    public final RollInput input = new RollInput();

    // ------------------------------------------------------------ 运动状态
    public final Vector3 pos = new Vector3();
    public final Vector3 vel = new Vector3();
    /** 长轴朝向（XZ 平面单位向量）。 */
    public final Vector3 longAxis = new Vector3(1f, 0f, 0f);
    /** 视觉滚动角（弧度），只增不减。 */
    public float rollAngle;
    public boolean grounded;
    public float airTime;
    public float groundTime;

    // ------------------------------------------------------------ 生命
    public float hp = GameConfig.MAX_HP;
    public boolean alive = true;
    public boolean eliminated;
    public boolean finished;
    /** 本子图已出局（生存/决战关碎掉后不再复活）。 */
    public boolean stageOut;
    /** 本关已锁定名次，不参与后续子图（L3 竞速段）。 */
    public boolean parked;
    public float finishTime = -1f;
    public float invulnTimer;
    public float respawnTimer;

    // ------------------------------------------------------------ 赛程
    public int checkpointIndex;
    public float progress;
    public int rank;
    public int deaths;
    public int kills;
    public float damageDealt;
    public float damageTaken;
    public float killedAt = -1f;

    // ------------------------------------------------------------ 控制状态
    public float stunTimer;
    public float rootTimer;
    public float invertTimer;
    public float noJumpTimer;
    public float slowTimer;
    public float slowMul = 1f;
    /** 做完你的做你的 结束后的疲劳。 */
    public float fatigueTimer;

    /**
     * 起点屏障保护：屏障生效期间、且人在起点区域内时**免伤**。
     *
     * <p>起点区域是个"准备区"，比赛还没开始。某些地图（熔岩环之类的竞技图）
     * 出生点半径范围内本来就压着伤害区域，如果那 8 秒里照常结算伤害，
     * 玩家会在起跑线上被活活烧死 —— 而这跟他们怎么操作毫无关系。
     *
     * <p>放在 {@link #invulnerable()} 里而不是逐个伤害来源去堵，是因为这一条是**唯一**的
     * 伤害汇总口径：地形伤害、机关、对撞、决战技全都走它，只改一处就不会漏。
     */
    public boolean startShield;

    /** 加速剩余时长（>0 表示正在加速）。 */
    public float dashTimer;
    /** 加速冷却剩余（>0 表示还不能加速）。重生只清正在生效的加速，不动冷却。 */
    public float dashCd;
    /**
     * 普通攻击的冷却剩余（秒）。只在决赛关（L5）会被消费，见 {@code AttackSystem}。
     *
     * <p>与 {@link #dashCd} 同一条规矩：**冷却是资源不是状态**，
     * 碎掉重生时不清（否则死一次就白赚一次挥击）。
     */
    public float attackCd;
    /** 挥击的视觉残留（>0 时渲染器画扇形）。 */
    public float attackFlash;
    public float checkpointBonusTimer;
    /** 正太扭腰：免疫伤害与控制，但不能动。 */
    public float zhengTaiTimer;
    /** 我的刀盾：攻防 +50%。 */
    public float daoDunTimer;
    /** 中国人能飞的前摇剩余。 */
    public float castTimer;
    /** 隐身剩余（忘情牛肉面）。 */
    public float hiddenTimer;
    /** 背手负鼠的免死是否已经用掉（本次装死内）。 */
    public boolean opossumUsed;
    /** 瞬时机关伤害的公共冷却，防止一帧多段。 */
    public float hazardCd;

    // ------------------------------------------------------------ 延迟结算（轻松绷住）
    public float delayedDamage;
    public float delayedTimer;

    // ------------------------------------------------------------ 道具
    public ItemType carried;
    public final Array<Effect> effects = new Array<>();

    // ------------------------------------------------------------ 决战技
    public UltimateType ultimate;
    /** 我的世界皓宸 偷来的技能。 */
    public UltimateType stolen;
    public int stolenCharges;
    public float ultimateCd;
    /** 坤坤第二段倒计时。 */
    public float kunKunDelay = -1f;

    // ------------------------------------------------------------ 春秋肠历史缓冲
    public final float[] histX = new float[GameConfig.CHUNQIU_FRAMES];
    public final float[] histY = new float[GameConfig.CHUNQIU_FRAMES];
    public final float[] histZ = new float[GameConfig.CHUNQIU_FRAMES];
    public final float[] histHp = new float[GameConfig.CHUNQIU_FRAMES];
    public int histHead;
    public int histCount;

    // ------------------------------------------------------------ AI 用
    /**
     * 上一帧命中的赛段号（-1 = 未知），给 {@code TrackProgress.arcAt} 做段滞后用。
     *
     * <p>弯道处两条边的距离几乎相等，纯全局最近点搜索会让弧长在两段之间来回跳
     * （实测一帧跳 15 米，而滚木一帧最多走 0.37 米）。优先沿用上一段就稳了。
     */
    public final int[] pathHint = new int[]{-1};

    public int aiDifficulty = 1;
    public float aiThinkTimer;
    public float aiJitterPhase;

    public Roll(int id, String name, boolean player) {
        this.id = id;
        this.name = name;
        this.player = player;
    }

    // ================================================================ 查询

    public float speed() {
        return (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
    }

    public float speed3() {
        return vel.len();
    }

    public boolean isBlitz() {
        return speed() >= GameConfig.BLITZ_SPEED * 0.97f;
    }

    public boolean invulnerable() {
        return invulnTimer > 0f || zhengTaiTimer > 0f || startShield;
    }

    /** 是否参与物理与战斗判定。 */
    public boolean onField() {
        return alive && !eliminated && !stageOut && !parked && respawnTimer <= 0f;
    }

    public boolean hidden() {
        return hiddenTimer > 0f;
    }

    public boolean canMove() {
        return alive && !eliminated && stunTimer <= 0f && rootTimer <= 0f
                && zhengTaiTimer <= 0f && !finished;
    }

    public boolean canJump() {
        return canMove() && noJumpTimer <= 0f && grounded;
    }

    /** 现在能不能加速：能动、不在冷却、也没在加速中。 */
    public boolean canDash() {
        return canMove() && !finished && dashCd <= 0f && dashTimer <= 0f;
    }

    /**
     * 现在能不能挥击。**这里不判断关卡**——"只在决赛关可用"由
     * {@code AttackSystem.enabled} 把关（那是关卡属性，不是滚木状态）。
     */
    public boolean canAttack() {
        return canMove() && !finished && attackCd <= 0f;
    }

    public boolean canUseItem() {
        return canMove() && invertTimer <= 0f;
    }

    public boolean canUseUltimate() {
        return canMove();
    }

    // ================================================================ 效果

    public Effect effect(ItemType type) {
        for (int i = 0; i < effects.size; i++) {
            if (effects.get(i).type == type) {
                return effects.get(i);
            }
        }
        return null;
    }

    public boolean hasEffect(ItemType type) {
        return effect(type) != null;
    }

    public void addEffect(ItemType type, float duration) {
        Effect e = effect(type);
        if (e != null) {
            e.remaining = Math.max(e.remaining, duration);
        } else {
            effects.add(new Effect(type, duration));
        }
    }

    public void removeEffect(ItemType type) {
        for (int i = effects.size - 1; i >= 0; i--) {
            if (effects.get(i).type == type) {
                effects.removeIndex(i);
            }
        }
    }

    /** 清除全部负面（爱你老己 / 带去刘文祥）。 */
    public void clearDebuffs() {
        for (int i = effects.size - 1; i >= 0; i--) {
            if (effects.get(i).type.isDebuff()) {
                effects.removeIndex(i);
            }
        }
        stunTimer = 0f;
        invertTimer = 0f;
        noJumpTimer = 0f;
        slowTimer = 0f;
    }

    /** 是否处于"免疫控制"状态（带去刘文祥）。 */
    public boolean controlImmune() {
        return hasEffect(ItemType.LIUWENXIANG);
    }

    public void addStun(float t) {
        if (controlImmune()) {
            return;
        }
        stunTimer = Math.max(stunTimer, t);
    }

    public void addRoot(float t) {
        if (controlImmune()) {
            return;
        }
        rootTimer = Math.max(rootTimer, t);
    }

    public void addSlow(float mul, float t) {
        if (controlImmune()) {
            return;
        }
        if (t >= slowTimer) {
            slowTimer = t;
            slowMul = mul;
        }
    }

    public void addInvert(float t) {
        if (controlImmune()) {
            return;
        }
        invertTimer = Math.max(invertTimer, t);
    }

    public void addNoJump(float t) {
        if (controlImmune()) {
            return;
        }
        noJumpTimer = Math.max(noJumpTimer, t);
    }

    // ================================================================ 倍率

    /** 移速总倍率。 */
    public float speedMultiplier() {
        float m = 1f;
        if (hasEffect(ItemType.CHAIN_OFF)) {
            m *= 0.5f;
        }
        if (hasEffect(ItemType.CRY_BABY)) {
            m *= 0.8f;
        }
        if (hasEffect(ItemType.CHAIN_GEARS)) {
            m *= 1.35f;
        }
        if (hasEffect(ItemType.ZUOWAN_NIDE)) {
            m *= 1.6f;
        }
        if (hasEffect(ItemType.YAOYAO_LINGXIAN)) {
            m *= GameConfig.YAOLING_SPEED_MUL;
        }
        if (fatigueTimer > 0f) {
            m *= 0.8f;
        }
        if (slowTimer > 0f) {
            m *= slowMul;
        }
        if (castTimer > 0f) {
            m *= GameConfig.ZHONGGUOREN_CAST_SLOW;
        }
        if (checkpointBonusTimer > 0f) {
            m *= 1f + GameConfig.CHECKPOINT_SPEED_BONUS;
        }
        if (dashTimer > 0f) {
            m *= GameConfig.DASH_SPEED_MULT;
        }
        return m;
    }

    /** 跳跃初速倍率。 */
    public float jumpMultiplier() {
        return hasEffect(ItemType.CHAIN_GEARS) ? 1.25f : 1f;
    }

    /** 本帧速度上限。 */
    public float maxSpeed() {
        if (hasEffect(ItemType.YAOYAO_LINGXIAN)) {
            return GameConfig.YAOLING_MAX_SPEED;
        }
        return hasEffect(ItemType.XIE_XIU)
                ? GameConfig.XIE_XIU_MAX_SPEED
                : GameConfig.DOWNHILL_MAX_SPEED;
    }

    /**
     * 加速度倍率（早八冲刺）。
     *
     * <p>作用点是 {@code PhysicsWorld} 里那两处 {@code MOVE_ACCEL} ——
     * 与加速（Shift）放大"上限 + 加速度"是同一个思路：
     * 要的是"这段时间里起步更快"，而不是弹一下。
     */
    public float accelMultiplier() {
        return hasEffect(ItemType.ZAO_BA) ? GameConfig.ZAOBA_ACCEL_MUL : 1f;
    }

    /** 是否无视地形摩擦与减速（邪修）。 */
    public boolean ignoreTerrainFriction() {
        return hasEffect(ItemType.XIE_XIU);
    }

    /** 出击伤害倍率。 */
    public float attackMultiplier() {
        float m = 1f;
        if (daoDunTimer > 0f) {
            m *= GameConfig.DAODUN_ATTACK_MUL;
        }
        if (isBlitz()) {
            m *= GameConfig.BLITZ_DAMAGE_BONUS;
        }
        if (hasEffect(ItemType.QIANXIN_WANKU)) {
            float lost = 1f - MathUtils.clamp(hp / GameConfig.MAX_HP, 0f, 1f);
            m *= 1f + lost * 0.6f;
        }
        if (hasEffect(ItemType.EBRUFEN)) {
            m = 0f;
        }
        // 忘情牛肉面：隐身期间的"破隐一击"。命中之后 Combat 会解除隐身（D16），
        // 所以这一下是**一次性**的，不是每撞一次都翻倍。
        if (hiddenTimer > 0f) {
            m *= GameConfig.NIUROUMIAN_AMBUSH_MUL;
        }
        return m;
    }

    /** 承伤倍率（<1 为减伤）。 */
    public float damageTakenMultiplier() {
        float m = 1f;
        if (daoDunTimer > 0f) {
            m /= GameConfig.DAODUN_DEFENCE_DIV;
        }
        if (hasEffect(ItemType.PO_FANG)) {
            m *= 1.35f;
        }
        if (hasEffect(ItemType.EGG_TART)) {
            m *= 1.30f;
        }
        return m;
    }

    /** 被撞击时的额外击退倍率。 */
    public float knockbackMultiplier() {
        return hasEffect(ItemType.EGG_TART) ? 1.5f : 1f;
    }

    // ================================================================ 计时器

    /**
     * 递减全部"纯计时"状态。由 LevelDirector 在每个逻辑帧开头统一调用一次，
     * 保证所有实体的计时推进顺序一致（确定性要求）。
     */
    public void tickTimers(float dt) {
        invulnTimer = dec(invulnTimer, dt);
        stunTimer = dec(stunTimer, dt);
        rootTimer = dec(rootTimer, dt);
        invertTimer = dec(invertTimer, dt);
        noJumpTimer = dec(noJumpTimer, dt);
        if (slowTimer > 0f) {
            slowTimer -= dt;
            if (slowTimer <= 0f) {
                slowMul = 1f;
            }
        }
        fatigueTimer = dec(fatigueTimer, dt);
        dashTimer = dec(dashTimer, dt);
        dashCd = dec(dashCd, dt);
        attackCd = dec(attackCd, dt);
        attackFlash = dec(attackFlash, dt);
        checkpointBonusTimer = dec(checkpointBonusTimer, dt);
        zhengTaiTimer = dec(zhengTaiTimer, dt);
        daoDunTimer = dec(daoDunTimer, dt);
        hiddenTimer = dec(hiddenTimer, dt);
        hazardCd = dec(hazardCd, dt);
    }

    private static float dec(float v, float dt) {
        return v > 0f ? Math.max(0f, v - dt) : 0f;
    }

    // ================================================================ 历史（春秋肠）

    public void pushHistory() {
        histX[histHead] = pos.x;
        histY[histHead] = pos.y;
        histZ[histHead] = pos.z;
        histHp[histHead] = hp;
        histHead = (histHead + 1) % GameConfig.CHUNQIU_FRAMES;
        if (histCount < GameConfig.CHUNQIU_FRAMES) {
            histCount++;
        }
    }

    public void resetHistory() {
        histHead = 0;
        histCount = 0;
        for (int i = 0; i < GameConfig.CHUNQIU_FRAMES; i++) {
            histX[i] = pos.x;
            histY[i] = pos.y;
            histZ[i] = pos.z;
            histHp[i] = hp;
        }
    }

    /**
     * 读取 {@code ageFrames} 帧之前的一份快照。
     *
     * @return true 表示读到了；false 表示历史不足（调用方应回退到出生点）
     */
    public boolean readHistory(int ageFrames, Vector3 outPos, float[] outHp) {
        if (histCount < GameConfig.CHUNQIU_FRAMES) {
            return false;
        }
        int idx = ((histHead - 1 - ageFrames) % GameConfig.CHUNQIU_FRAMES
                + GameConfig.CHUNQIU_FRAMES) % GameConfig.CHUNQIU_FRAMES;
        outPos.set(histX[idx], histY[idx], histZ[idx]);
        outHp[0] = histHp[idx];
        return true;
    }

    // ================================================================ 重置

    /** 碎掉之后清空瞬时状态。 */
    public void clearTransient() {
        effects.clear();
        stunTimer = 0f;
        rootTimer = 0f;
        invertTimer = 0f;
        noJumpTimer = 0f;
        slowTimer = 0f;
        fatigueTimer = 0f;
        zhengTaiTimer = 0f;
        daoDunTimer = 0f;
        castTimer = 0f;
        hiddenTimer = 0f;
        delayedDamage = 0f;
        delayedTimer = 0f;
        kunKunDelay = -1f;
        opossumUsed = false;
        checkpointBonusTimer = 0f;
        // 正在生效的加速要清掉；但**冷却是资源不是状态**，不在这里清，
        // 否则死一次就白赚一次加速（与 ultimateCd 的处理保持一致）
        dashTimer = 0f;
        attackFlash = 0f;
        carried = null;
    }

    public void teleport(Vector3 p) {
        pos.set(p);
        vel.setZero();
    }
}
