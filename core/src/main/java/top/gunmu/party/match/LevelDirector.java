package top.gunmu.party.match;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import java.util.Random;

import top.gunmu.party.GameConfig;
import top.gunmu.party.ai.BotBrain;
import top.gunmu.party.core.Combat;
import top.gunmu.party.core.DamageSource;
import top.gunmu.party.core.Mode;
import top.gunmu.party.core.Roll;
import top.gunmu.party.items.ItemSystem;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapRegistry;
import top.gunmu.party.map.ObstacleSystem;
import top.gunmu.party.physics.PhysicsWorld;
import top.gunmu.party.ultimates.UltimateSystem;
import top.gunmu.party.ultimates.UltimateType;

/**
 * 单局淘汰赛的导演。负责：关卡/子图流程、固定步长推进、晋级结算。
 *
 * <p>关卡结构见 docs/02-LEVELS-AND-MAPS.md；
 * L3 两阶段与 L4 三子图的处理见 docs/06-DECISIONS.md D7 / D8。
 */
public final class LevelDirector {

    /** 本局所处的阶段。 */
    public enum Phase {
        /** 正在比赛。 */
        PLAYING,
        /** 子图结束的短暂停顿（显示横幅）。 */
        STAGE_BREAK,
        /** 本关结束，等界面调用 {@link #advanceToNextLevel()}。 */
        LEVEL_DONE,
        /** 整局结束（冠军或被淘汰）。 */
        TOURNAMENT_DONE
    }

    /** 每关进场人数 / 晋级名额（docs/00 §2.1）。 */
    public static final int[] ENTRANTS = {30, 25, 20, 15, 10};
    public static final int[] QUALIFIERS = {25, 20, 15, 10, 1};

    /** 子图定义。 */
    private static final class Stage {
        Mode mode;
        String mapId;
        String label;
        float timeLimit;

        Stage(Mode mode, String mapId, String label, float timeLimit) {
            this.mode = mode;
            this.mapId = mapId;
            this.label = label;
            this.timeLimit = timeLimit;
        }
    }

    public final MatchState state = new MatchState();

    private final Array<Stage> stages = new Array<>();
    private final Array<BotBrain> brains = new Array<>();
    private final Array<Roll> entrants = new Array<>();

    private PhysicsWorld physics;
    private Combat combat;
    public final ItemSystem items = new ItemSystem();
    public final UltimateSystem ultimates = new UltimateSystem();
    public final ObstacleSystem obstacles = new ObstacleSystem();
    /**
     * 普通攻击系统（决赛关专属，5 s 冷却 / 70° 扇形 / 15 伤）。
     *
     * <p>它**不属于决战技**：决战技是 L4/L5 的 25~40 秒大招，普攻是决赛关才开放的基础输出。
     * 开放与否由 {@link #startStage} 按关卡设置，见 {@code GameConfig.ATTACK_MIN_LEVEL}。
     */
    public final top.gunmu.party.core.AttackSystem attacks
            = new top.gunmu.party.core.AttackSystem();

    private MapDef map;

    /** 「压在路上吗、压在哪一段」的唯一判定（与无头审计 RouteAudit 共用同一份实现）。 */
    private top.gunmu.party.map.TrackProgress track;
    private Random rng;
    private long seed;

    private float accumulator;
    private float breakTimer;
    private int stageDeaths;
    private int finishedCount;
    /** 某根滚木在本关已锁定名额（L3 竞速段）。 */
    /** 按来源统计的死亡次数，用于平衡调参。 */
    public final int[] deathsByCause = new int[DamageSource.values().length];
    /** L4 三子图累计积分。 */
    private final float[] levelPoints = new float[64];
    /** 本关已锁定的名额数。 */

    private final Vector3 tmp = new Vector3();
    private final float[] near = new float[4];

    public Phase phase = Phase.PLAYING;
    private int playerUltimatePick = -1;

    /**
     * 让 AI 接管玩家这根滚木（调试/录屏/看平衡用）。
     * 打开后界面不再写玩家输入，改由 BotBrain 驱动，与其它滚木完全同源。
     */
    public boolean autoplayPlayer;

    /** 本局已经用过的地图 id，保证同一场比赛不会连着抽到同一张图。 */
    private final Array<String> usedMaps = new Array<>();

    // ================================================================== 建局

    /** 开一局新比赛。 */
    public void newTournament(long seed, int playerUltimateOrdinal) {
        this.seed = seed;
        this.rng = new Random(mixSeed(seed));
        this.playerUltimatePick = playerUltimateOrdinal;

        state.rolls.clear();
        entrants.clear();
        brains.clear();
        usedMaps.clear();
        for (int i = 0; i < GameConfig.LOG_COUNT; i++) {
            Roll r = new Roll(i, rollName(i), i == 0);
            entrants.add(r);
            state.rolls.add(r);
            brains.add(new BotBrain());
        }
        state.player = state.rolls.first();

        state.playerChampion = false;
        state.playerEliminated = false;
        state.tournamentOver = false;
        state.feed.clear();
        for (int i = 0; i < levelPoints.length; i++) {
            levelPoints[i] = 0f;
        }
        // 累计量随新的一局归零（不放在 startStage 里：那是"换关"，不是"新局"）
        dashTotal = 0;
        jumpTotal = 0;
        attackTotal = 0;
        attacks.reset();
        attacks.enabled = false;
        startLevel(1);
    }

    private static String rollName(int i) {
        if (i == 0) {
            return "你";
        }
        String[] pool = {"木", "墩", "桩", "柱", "干", "梁", "柁", "柯", "杈", "棒",
                "梃", "梧", "桐", "杉", "桦", "榆", "榕", "檀", "楠", "椴",
                "榉", "枫", "榛", "橡", "桑", "柳", "杨", "槐", "柏"};
        return pool[(i - 1) % pool.length];
    }

    // ================================================================== 关卡流程

    private void startLevel(int level) {
        state.levelIndex = level;
        state.stageIndex = 0;
        state.rolls.clear();
        for (int i = 0; i < entrants.size; i++) {
            state.rolls.add(entrants.get(i));
        }
        state.resultLines.clear();
        state.qualified.clear();
        state.feed.clear();
        state.playerEliminated = false;
        state.tournamentOver = false;


        // 本关参赛者的难度阶梯
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            r.aiDifficulty = MathUtils.clamp(level / 2, 0, 2);
            r.eliminated = false;
            r.parked = false;
            r.stageOut = false;
        }

        buildStages(level);
        startStage(0);
        phase = Phase.PLAYING;
    }

    /**
     * 从给定的模式池里抽一个，作为这一关的**唯一一张图**。
     *
     * <p>第 3 关抽 2 选 1（竞速 / 生存），第 4 关抽 3 选 1（竞速 / 生存 / 竞技）。
     * 这两关**只跑一张图**，不是"一张跑完接着跑下一张"。
     */
    private void pickLevelMode(int level, Mode... pool) {
        Mode mode = pool[rng.nextInt(pool.length)];
        stages.add(new Stage(mode, pickMap(mode, null),
                "第 " + level + " 关 · " + mode.cn, timeLimitFor(level, mode)));
    }

    /** 第 3 / 4 关的时限要按**抽到的模式**取，不能一张表套死。 */
    private float timeLimitFor(int level, Mode mode) {
        if (level == 3) {
            return mode == Mode.RACE
                    ? GameConfig.L3_RACE_TIME_LIMIT : GameConfig.L3_SURVIVAL_TIME_LIMIT;
        }
        switch (mode) {
            case RACE:
                return GameConfig.L4_RACE_TIME_LIMIT;
            case SURVIVAL:
                return GameConfig.L4_SURVIVAL_TIME_LIMIT;
            default:
                return GameConfig.L4_ARENA_TIME_LIMIT;
        }
    }

    /**
     * 本关已排定的子图地图 id（只读快照）。
     * 自检要靠它断言"同一局里不重复、不同局之间会变"。
     */
    /**
     * 起点屏障剩余时间（秒）。{@code <= 0} 表示屏障已消失。
     * 界面靠它显示倒计时，自检靠它断言"8 秒内出不去、8 秒后出得去"。
     */
    public float startBarrierRemaining() {
        return Math.max(0f, GameConfig.START_BARRIER_TIME - state.stageClock);
    }

    /**
     * 起点屏障是否仍在生效。
     *
     * <p>⚠️ 必须带上 {@code phase == PLAYING}：结算 / 选技 / 冠军界面也会
     * {@code renderScene()}，而换关时 {@code stageClock} 刚被清零 —— 于是那三个界面上
     * 会立着一个巨大的青色圆筒正对镜头，**把整个结算卡挡住**
     * （玩家报的「晋级图上面有一个大大的东西遮挡住视野」就是这个）。
     * 屏障是"比赛开始前 8 秒"的东西，比赛已结束就不该出现。
     */
    public boolean isStartBarrierActive() {
        return phase == Phase.PLAYING
                && state.stageClock < GameConfig.START_BARRIER_TIME;
    }

    /**
     * 本局累计成功加速次数（含 AI）。
     *
     * <p>⚠️ 必须把**已经结束的子图**的计数累加进来：{@code PhysicsWorld} 是每个子图
     * 新建一个的，只读当前实例的话，拿到的就只是最后一个子图的数字 ——
     * 曾被这个口径坑过：一局下来看着只有 6 次加速，其实是"最后一关 6 次"。
     */
    public int dashCount() {
        return dashTotal + (physics == null ? 0 : physics.dashCount);
    }

    /** 本局累计成功起跳次数（含 AI）。口径同 {@link #dashCount()}。 */
    public int jumpCount() {
        return jumpTotal + (physics == null ? 0 : physics.jumpCount);
    }

    /**
     * 本局累计挥击次数（含 AI）。
     *
     * <p>与 {@link #dashCount()} 同一条口径：{@code AttackSystem.attackCount} 是**本关**计数，
     * 每关换图时收口到 {@link #attackTotal}。直接读实例字段只会得到最后一关的数字
     * （这个坑在加速上已经踩过一次，见 D48 的附注）。
     */
    public int attackCount() {
        return attackTotal + attacks.attackCount;
    }

    private int dashTotal;
    private int jumpTotal;
    private int attackTotal;

    /** 换子图前把上一张图的累计量收进来（见 {@link #dashCount()}）。 */
    private void harvestCounters() {
        if (physics == null) {
            return;
        }
        dashTotal += physics.dashCount;
        jumpTotal += physics.jumpCount;
        attackTotal += attacks.attackCount;
        attacks.attackCount = 0;
    }

    public Array<String> plannedMapIds() {
        Array<String> out = new Array<>();
        for (int i = 0; i < stages.size; i++) {
            out.add(stages.get(i).mapId);
        }
        return out;
    }


    /**
     * 把种子做一次雪崩混合再交给 {@link Random}。
     *
     * <p><b>为什么不直接 new Random(seed)：</b>{@code java.util.Random} 只是
     * {@code seed = (seed ^ 0x5DEECE66D)} 再线性同余，相邻种子的<b>高位几乎一样</b>，
     * 而 {@code nextInt(4)} 取的正是高位 —— 实测种子 1..6 的第一次
     * {@code nextInt(4)} 全都是 2，于是"每局的第 1 关都是同一张图"。
     *
     * <p>这里用 murmur3 的收尾混合（乘 + 异或移位）把相邻种子彻底打散，
     * 再用时间戳之类的相邻值时也能得到无关的随机序列。
     */
    private static long mixSeed(long seed) {
        long h = seed;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h == 0L ? 0x9E3779B97F4A7C15L : h;
    }

    /**
     * 编排本关的图。
     *
     * <p>🔒 <b>第 3 / 4 关是"抽签"，不是"连战"</b>（玩家口径：「第三关有竞速也有生存，
     * 第四关有竞速也有生存也有竞技图，是三选一，而不是子图」）：
     *
     * <ul>
     *   <li>第 1 / 2 关：竞速图各一张；</li>
     *   <li>第 3 关：<b>竞速 / 生存 二选一</b>，整关只跑这一张；</li>
     *   <li>第 4 关：<b>竞速 / 生存 / 竞技 三选一</b>；</li>
     *   <li>第 5 关：竞技图（决战）。</li>
     * </ul>
     *
     * <p>所以 {@code stages} 永远只有一张图。早期那套「第 3 关竞速段 + 生存段连跑、
     * 第 4 关三子图连战、按子图名次累计积分」是理解错设计留下的，已按玩家口径改掉。
     */
    private void buildStages(int level) {
        stages.clear();
        switch (level) {
            case 1:
                stages.add(new Stage(Mode.RACE, pickMap(Mode.RACE, null),
                        "第 1 关 · 热身赛道", GameConfig.L1_TIME_LIMIT));
                break;
            case 2:
                stages.add(new Stage(Mode.RACE, pickMap(Mode.RACE, null),
                        "第 2 关 · 山坡冲刺", GameConfig.L2_TIME_LIMIT));
                break;
            case 3:
                pickLevelMode(3, Mode.RACE, Mode.SURVIVAL);
                break;
            case 4:
                pickLevelMode(4, Mode.RACE, Mode.SURVIVAL, Mode.ARENA);
                break;
            default:
                stages.add(new Stage(Mode.ARENA, pickMap(Mode.ARENA, null),
                        "第 5 关 · 决战", GameConfig.L5_TIME_LIMIT));
                break;
        }
    }

    /**
     * 选图：一场比赛内**不重复**。
     *
     * <p>同一局里 L1/L2/L3/L4 都可能抽到竞速图，纯随机的话很容易连着两关是同一张 ——
     * 看起来就像"每关地图都是固定的"。这里记住本局已经用过的图，优先抽没玩过的；
     * 只有该模式的所有图都用过时才允许重复。
     */
    private String pickMap(Mode mode, String exclude) {
        String[] pool = MapRegistry.idsFor(mode);
        if (pool.length <= 1) {
            return pool[0];
        }
        String fresh = pickFrom(pool, true, exclude);
        if (fresh != null) {
            return fresh;
        }
        String reused = pickFrom(pool, false, exclude);
        return reused != null ? reused : pool[0];
    }

    /** @param avoidUsed true 表示只在"本局没用过"的图里挑。 */
    private String pickFrom(String[] pool, boolean avoidUsed, String exclude) {
        int tries = Math.max(8, pool.length * 3);
        for (int i = 0; i < tries; i++) {
            String id = pool[rng.nextInt(pool.length)];
            if (exclude != null && id.equals(exclude)) {
                continue;
            }
            if (avoidUsed && usedMaps.contains(id, false)) {
                continue;
            }
            usedMaps.add(id);
            return id;
        }
        return null;
    }

    private void startStage(int index) {
        state.stageIndex = index;
        Stage s = stages.get(index);
        harvestCounters();
        map = MapRegistry.create(s.mapId);
        track = new top.gunmu.party.map.TrackProgress(map);

        physics = new PhysicsWorld(map.field);
        physics.statics.addAll(map.walls);
        physics.zones.addAll(map.zones);
        physics.mapFloorY = map.minHeight - 6f;
        physics.waterLevel = -1e9f;
        physics.poisonRadius = -1f;
        if (map.mode == Mode.SURVIVAL && map.poisonStartRadius > 0f) {
            physics.poisonRadius = map.poisonStartRadius;
            physics.poisonCenter.set(map.poisonCenter);
        }

        combat = new Combat(this::onDestroyed);
        items.reset(map, rng);
        ultimates.reset();
        // 普通攻击只在决赛关开放（用户口述「只在决赛关可用」）。
        // 每关重新设置而不是设一次，是因为"关卡属性"必须跟着换关走 ——
        // 漏掉这一步的话，进第 5 关时攻击是关着的（看起来像"加了没生效"）。
        attacks.enabled = state.levelIndex >= GameConfig.ATTACK_MIN_LEVEL;

        state.mode = s.mode;
        state.mapName = map.name;
        state.stageLabel = s.label;
        state.stageCount = stages.size;
        state.timeLeft = s.timeLimit;
        state.stageClock = 0f;
        physics.setStartBarrier(true, map.startCenter.x, map.startCenter.z,
                map.startRadius);
        state.stageElapsed = 0f;
        state.stageOver = false;
        stageDeaths = 0;
        state.stageDeaths = 0;
        finishedCount = 0;

        int spawnIdx = 0;
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            if (r.eliminated || r.parked) {
                continue;
            }
            placeAtSpawn(r, spawnIdx++);
            if (state.levelIndex >= 4) {
                r.ultimate = ultimateFor(r);
            }
        }
        if (s.mode == Mode.SURVIVAL && map.waterRise > 0f) {
            physics.waterLevel = map.minHeight + 4f;
        }
        state.showBanner(s.label + "   " + map.name, 3.0f);
    }

    private UltimateType ultimateFor(Roll r) {
        if (r.player) {
            if (playerUltimatePick >= 0 && playerUltimatePick < UltimateType.ALL.length) {
                return UltimateType.ALL[playerUltimatePick];
            }
            return UltimateType.ALL[0];
        }
        return UltimateType.ALL[rng.nextInt(UltimateType.ALL.length)];
    }

    private void placeAtSpawn(Roll r, int spawnIdx) {
        Vector3 p = map.spawns.get(spawnIdx % map.spawns.size);
        r.pos.set(p);
        r.vel.setZero();
        r.rollAngle = 0f;
        r.longAxis.set(1f, 0f, 0f);
        r.hp = GameConfig.MAX_HP;
        r.alive = true;
        r.stageOut = false;
        r.finished = false;
        r.finishTime = -1f;
        r.checkpointIndex = 0;
        r.progress = 0f;
        r.rank = r.id + 1;
        r.deaths = 0;
        r.kills = 0;
        r.damageDealt = 0f;
        r.damageTaken = 0f;
        r.invulnTimer = GameConfig.RESPAWN_INVULN;
        r.respawnTimer = 0f;
        r.clearTransient();
        r.stolen = null;
        r.stolenCharges = 0;
        r.ultimateCd = 0f;
        r.dashCd = 0f;
        r.dashTimer = 0f;
        r.aiThinkTimer = 0f;
        r.input.clear();
        r.resetHistory();
    }

    // ================================================================== 推进

    /**
     * 用真实帧间隔推进逻辑。
     *
     * @return 本帧是否推进过物理（用于渲染插值）
     */
    public void update(float delta) {
        float d = Math.min(delta, GameConfig.MAX_FRAME_DELTA);

        if (state.bannerTimer > 0f) {
            state.bannerTimer -= d;
        }

        if (phase == Phase.STAGE_BREAK) {
            breakTimer -= d;
            if (breakTimer <= 0f) {
                if (state.stageIndex + 1 < stages.size) {
                    startStage(state.stageIndex + 1);
                    phase = Phase.PLAYING;
                } else {
                    finishLevel();
                }
            }
            return;
        }
        if (phase != Phase.PLAYING) {
            return;
        }

        accumulator += d;
        int steps = 0;
        while (accumulator >= GameConfig.FIXED_DT) {
            stepOne(GameConfig.FIXED_DT);
            accumulator -= GameConfig.FIXED_DT;
            if (++steps >= GameConfig.MAX_STEPS_PER_FRAME) {
                accumulator = 0f;
                break;
            }
            if (phase != Phase.PLAYING) {
                break;
            }
        }
    }

    private void stepOne(float dt) {
        state.elapsed += dt;
        state.stageElapsed += dt;

        // 起点屏障：开局的一段时间里所有人被关在起点区域内（可以自由移动，出不去）。
        //
        // ⚠️ 屏障期间**比赛时钟与生存关的危险区都不推进**。
        // 否则等于白扣 8 秒：限时 60 秒的关卡实际只剩 52 秒可用，
        // 而折返图本来就跑不完（见 RouteAudit 的记录），等于雪上加霜。
        boolean barrier = state.stageClock < GameConfig.START_BARRIER_TIME;
        physics.startBarrierActive = barrier;
        refreshStartShield(barrier);
        if (barrier) {
            state.stageClock = Math.min(GameConfig.START_BARRIER_TIME,
                    state.stageClock + dt);
            if (state.stageClock >= GameConfig.START_BARRIER_TIME) {
                if (state.bannerTimer <= 0f) {
                    state.showBanner("出发！", 1.6f);
                }
            }
        } else {
            state.timeLeft -= dt;
            updateEnvironment(dt);
        }

        // 1) 输入
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            if (r.player && !autoplayPlayer) {
                continue; // 真人的输入由界面每帧写入
            }
            brains.get(r.id).think(r, map, state.rolls, items, ultimates, attacks, rng, dt);
        }

        // 2) 计时器
        for (int i = 0; i < state.rolls.size; i++) {
            state.rolls.get(i).tickTimers(dt);
        }

        // 3) 物理
        physics.step(state.rolls, dt);

        // 4) 伤害事件
        for (int i = 0; i < physics.damageEvents.size; i++) {
            PhysicsWorld.DamageEvent e = physics.damageEvents.get(i);
            if (e.source == DamageSource.OUT_OF_MAP) {
                combat.dealTrue(e.victim, null, GameConfig.MAX_HP, e.source);
            } else {
                combat.deal(e.victim, e.attacker, e.amount, e.source, 0);
            }
        }
        for (int i = 0; i < physics.collisions.size; i++) {
            PhysicsWorld.CollisionEvent c = physics.collisions.get(i);
            combat.resolveImpact(c.a, c.b, c.relSpeed, c);
        }
        for (int i = 0; i < state.rolls.size; i++) {
            combat.flushDelayed(state.rolls.get(i), dt);
        }

        // 5) 存档点 / 进度 / 终点
        updateProgress(dt);

        // 6) 复活
        updateRespawns(dt);

        // 7) 道具与决战技
        items.tick(physics, state.rolls, combat, rng, dt);
        ultimates.tick(map, state.rolls, combat, rng, dt);
        // 普通攻击：input.attack 的**唯一消费点**（D43）。放在物理之后、机关之前，
        // 这样本帧"挥击 → 击退"产生的速度差会被下一帧的互撞判定看见。
        attacks.tick(state.rolls, combat);

        // 8) 机关
        obstacles.tick(map, state.rolls, combat, dt);

        // 9) 环境（涨水 / 毒圈）→ 已移到上面的屏障判定里：屏障期间不推进

        // 10) 名次 / 结束判定
        updateRanks();
        checkStageEnd(dt);
    }

    // ================================================================== 进度

    private void updateProgress(float dt) {
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            if (!r.onField() || r.finished) {
                continue;
            }
            if (map.mode == Mode.RACE && map.hasRoute()) {
                // 只有真压在路面上，弧长才可信。
                //
                // ⚠️ 这里以前是无条件 `r.progress = route.progressAt(x, z)`，
                // 而 nearest 只看水平坐标 —— 之字断崖那种折返图，掉进虚空时最近点会
                // 跳到更靠后的那一段，弧长瞬间跳几百米，存档点与终点判定被全部误触发
                // （玩家报的「跳到虚空里能直接读到下面的存档点和通关区域」）。
                // 现在读不到就**冻结上一次的弧长**：既不前进也不后退。
                float arc = track != null
                        ? track.arcAt(r.pos.x, r.pos.y, r.pos.z, r.pathHint)
                        : map.route.progressAt(r.pos.x, r.pos.z, near);   // route-ok: 上面 if 已过 map.hasRoute()
                if (arc >= 0f) {
                    r.progress = arc;
                }
                checkCheckpoints(r);
                if (r.progress >= map.routeLength() - 2f) {
                    r.finished = true;
                    r.finishTime = state.stageElapsed;
                    finishedCount++;
                    if (r.player) {
                        state.showBanner("到达终点！名次 " + finishedCount, 2.5f);
                    }
                }
            } else if (map.mode == Mode.SURVIVAL) {
                r.progress = state.stageElapsed * 10f;
            } else {
                r.progress = r.kills * 100f + r.damageDealt * 0.1f;
            }
        }
    }

    /**
     * 存档点判定：<b>越过该存档点对应的赛程弧长就立刻存档</b>，而不是"必须碰到那个圆盘"。
     *
     * <p>旧做法是在固定位置放一个半径 2.5 米的触发圆盘，玩家完全可能从旁边擦过去 ——
     * 尤其是被撞飞、走边线、或者高速掠过的时候，结果是"我明明过了这一段却没存上"。
     * 现在改成按弧长判定：只要你在这条赛道上越过了那一段，就一定存上，跑不掉。
     */
    /**
     * 刷新"起点屏障保护"：屏障生效期间、人在起点区域内 → 免伤。
     *
     * <p>这份保护必须在**每帧**重算，而不是开屏障时一次性给上：
     * 一旦屏障消失（或人出了圈）就要立刻收回，否则会白送一段无敌。
     */
    private void refreshStartShield(boolean barrier) {
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            r.startShield = barrier && map != null
                    && map.insideStartZone(r.pos.x, r.pos.z);
        }
    }

    private void checkCheckpoints(Roll r) {
        if (map.mode != Mode.RACE || map.checkpoints.size == 0) {
            return;
        }
        for (int i = 0; i < map.checkpoints.size; i++) {
            MapDef.Checkpoint cp = map.checkpoints.get(i);
            if (cp.index <= r.checkpointIndex) {
                continue;
            }
            if (r.progress < cp.arc) {
                continue;
            }
            r.checkpointIndex = cp.index;
            r.checkpointBonusTimer = GameConfig.CHECKPOINT_BONUS_TIME;
            if (r.player) {
                state.showBanner("存档点 " + cp.index + " / " + map.checkpoints.size, 1.2f);
            }
        }
    }

    // ================================================================== 复活

    private void updateRespawns(float dt) {
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            if (r.alive || r.respawnTimer <= 0f) {
                continue;
            }
            r.respawnTimer -= dt;
            if (r.respawnTimer > 0f) {
                continue;
            }
            r.respawnTimer = 0f;
            r.alive = true;
            r.hp = GameConfig.MAX_HP * GameConfig.RESPAWN_HP_RATIO;
            r.invulnTimer = GameConfig.RESPAWN_INVULN;
            r.clearTransient();
            if (map.mode == Mode.RACE) {
                respawnAtCheckpoint(r);
            } else {
                int idx = rng.nextInt(map.spawns.size);
                r.pos.set(map.spawns.get(idx));
                r.vel.setZero();
            }
        }
    }

    private void respawnAtCheckpoint(Roll r) {
        if (r.checkpointIndex <= 0 || map.checkpoints.size == 0) {
            int idx = Math.floorMod(r.id, map.spawns.size);
            r.pos.set(map.spawns.get(idx));
        } else {
            MapDef.Checkpoint cp = map.checkpoints.get(
                    MathUtils.clamp(r.checkpointIndex - 1, 0, map.checkpoints.size - 1));
            float a = rng.nextFloat() * MathUtils.PI2;
            float rr = rng.nextFloat() * GameConfig.RESPAWN_JITTER;
            r.pos.set(cp.pos.x + MathUtils.cos(a) * rr, cp.pos.y + GameConfig.LOG_RADIUS,
                    cp.pos.z + MathUtils.sin(a) * rr);
        }
        r.vel.setZero();
    }

    private void onDestroyed(Roll victim, Roll killer, DamageSource source) {
        stageDeaths++;
        state.stageDeaths = stageDeaths;
        deathsByCause[source.ordinal()]++;
        victim.killedAt = state.stageElapsed;
        if (killer != null && killer != victim) {
            state.pushFeed(killer.name + " 撞碎了 " + victim.name);
        } else {
            state.pushFeed(victim.name + " 被" + source.cn + "干碎了");
        }
        ultimates.onCastInterrupted(victim);

        if (map.mode == Mode.RACE) {
            victim.respawnTimer = 1.2f;
        } else if (map.mode == Mode.SURVIVAL) {
            // 生存图：碎掉即出局本子图，但不影响整关（子图之间会满血重置）
            victim.stageOut = true;
        } else if (state.levelIndex >= 5) {
            // 决战：永久淘汰
            victim.stageOut = true;
            victim.eliminated = true;
        } else {
            victim.respawnTimer = GameConfig.ARENA_RESPAWN_DELAY;
        }

        if (victim.player) {
            if (map.mode == Mode.RACE) {
                state.showBanner("碎了！重生到存档点 " + victim.checkpointIndex, 2f);
            } else {
                state.showBanner("你被淘汰了", 3f);
            }
        }
    }

    // ================================================================== 环境

    private void updateEnvironment(float dt) {
        if (map.waterRise > 0f) {
            physics.waterLevel += map.waterRise * dt;
        }
        if (map.poisonStartRadius > 0f && physics.poisonRadius >= 0f) {
            physics.poisonRadius -= map.poisonShrink * dt;
            if (physics.poisonRadius < 3f) {
                physics.poisonRadius = 3f;
            }
        }
    }

    // ================================================================== 名次

    private void updateRanks() {
        Array<Roll> list = new Array<>();
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            if (r.eliminated || r.parked) {
                continue;
            }
            list.add(r);
        }
        list.sort((a, b) -> compareForRank(a, b, map.mode));
        for (int i = 0; i < list.size; i++) {
            list.get(i).rank = i + 1;
        }
    }

    private int compareForRank(Roll a, Roll b, Mode mode) {
        switch (mode) {
            case SURVIVAL: {
                if (a.stageOut != b.stageOut) {
                    return a.stageOut ? 1 : -1;
                }
                if (a.alive != b.alive) {
                    return a.alive ? -1 : 1;
                }
                if (Math.abs(b.hp - a.hp) > 0.01f) {
                    return Float.compare(b.hp, a.hp);
                }
                return Integer.compare(a.id, b.id);
            }
            case ARENA: {
                if (a.kills != b.kills) {
                    return Integer.compare(b.kills, a.kills);
                }
                if (Math.abs(b.damageDealt - a.damageDealt) > 0.01f) {
                    return Float.compare(b.damageDealt, a.damageDealt);
                }
                if (a.alive != b.alive) {
                    return a.alive ? -1 : 1;
                }
                return Integer.compare(a.id, b.id);
            }
            default: {
                if (a.finished != b.finished) {
                    return a.finished ? -1 : 1;
                }
                if (a.finished) {
                    return Float.compare(a.finishTime, b.finishTime);
                }
                if (a.alive != b.alive) {
                    return a.alive ? -1 : 1;
                }
                if (Math.abs(b.progress - a.progress) > 0.01f) {
                    return Float.compare(b.progress, a.progress);
                }
                return Integer.compare(a.id, b.id);
            }
        }
    }

    // ================================================================== 结束判定

    private void checkStageEnd(float dt) {
        boolean end = false;
        String why = null;

        switch (map.mode) {
            case RACE: {
                int quota = raceQuota();
                if (finishedCount >= quota) {
                    end = true;
                    why = "名额已满";
                } else if (quota == Integer.MAX_VALUE) {
                    int onField = 0;
                    for (int i = 0; i < state.rolls.size; i++) {
                        Roll r = state.rolls.get(i);
                        if (!r.eliminated && !r.parked) {
                            onField++;
                        }
                    }
                    if (finishedCount >= onField - 1) {
                        end = true;
                        why = "全部到线";
                    }
                }
                break;
            }
            case SURVIVAL: {
                if (stageDeaths >= GameConfig.L3_SURVIVAL_DEATH_THRESHOLD) {
                    end = true;
                    why = "死亡人数达到 " + GameConfig.L3_SURVIVAL_DEATH_THRESHOLD;
                } else if (countOnField() <= 1) {
                    end = true;
                    why = "只剩最后一人";
                }
                break;
            }
            default: {
                if (state.levelIndex >= 5 && countOnField() <= 1) {
                    end = true;
                    why = "决战结束";
                }
                break;
            }
        }

        if (!end && state.timeLeft <= 0f) {
            end = true;
            why = "时间到";
        }
        if (!end) {
            return;
        }

        state.stageOver = true;
        state.showBanner(why, 2.5f);
        breakTimer = 2.5f;
        phase = Phase.STAGE_BREAK;
        recordStageResult();
    }

    /**
     * 竞速图的名额：本关**跑够多少人到线就结束**。
     *
     * <p>名额是硬上限（三个触发源之一：名额填满 / 时限到 / 模式条件）。
     * 改成"每关只抽一张图"之后不再需要按子图分段取数，直接用本关的晋级人数 ——
     * 第 1 关 25、第 2 关 20、第 3 关 15、第 4 关 10。
     */
    private int raceQuota() {
        int level = MathUtils.clamp(state.levelIndex, 1, QUALIFIERS.length);
        return QUALIFIERS[level - 1];
    }

    private int countOnField() {
        int n = 0;
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            if (!r.eliminated && !r.parked && !r.stageOut) {
                n++;
            }
        }
        return n;
    }

    // ================================================================== 子图结算

    /**
     * 记本关名次分（晋级名单在 {@code breakStage} 里按它排序）。
     *
     * <p>⚠️ 这里原来有一段特殊分支：「第 3 关第 1 个子图 = 竞速段，锁定头部 10 个名额」，
     * 那是"第 3 关 = 竞速段 + 生存段连跑"时代的产物。按玩家口径改成"每关只抽一张图"
     * 之后它已经无从触发（{@code stageIndex} 恒为 0、L3 也可能抽到生存图），
     * 留着只会让人以为还有子图连战 —— 已删除。
     */
    private void recordStageResult() {
        Array<Roll> list = rankedList();
        int max = Math.min(list.size, GameConfig.LOG_COUNT);

        for (int i = 0; i < max; i++) {
            Roll r = list.get(i);
            int place = i + 1;
            float pts = Math.max(0f, GameConfig.SUBMAP_PLACE_POINTS - place + 1f);
            levelPoints[r.id] += pts;
            if (map.mode == Mode.ARENA) {
                levelPoints[r.id] += r.kills * GameConfig.ARENA_KILL_POINTS;
            }
        }
    }

    /**
     * 决战关最终名次：存活者第一；已淘汰者按"被碎掉的先后"倒序
     * （越晚出局名次越高，和大逃杀惯例一致）。
     */
    private int compareArenaPlacement(Roll a, Roll b) {
        boolean aOut = a.stageOut || a.eliminated;
        boolean bOut = b.stageOut || b.eliminated;
        if (aOut != bOut) {
            return aOut ? 1 : -1;
        }
        if (aOut) {
            int c = Float.compare(b.killedAt, a.killedAt);
            if (c != 0) {
                return c;
            }
        }
        return compareForRank(a, b, Mode.ARENA);
    }

    private Array<Roll> rankedList() {        Array<Roll> list = new Array<>();
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            if (r.eliminated || r.parked) {
                continue;
            }
            list.add(r);
        }
        list.sort((a, b) -> compareForRank(a, b, map.mode));
        return list;
    }

    // ================================================================== 本关结算

    private void finishLevel() {
        int slots = QUALIFIERS[state.levelIndex - 1];
        state.qualified.clear();
        state.resultLines.clear();

        if (state.levelIndex < 5) {
            Array<Roll> order;
            if (state.levelIndex == 4) {
                // 三子图积分
                Array<Roll> list = new Array<>();
                for (int i = 0; i < state.rolls.size; i++) {
                    Roll r = state.rolls.get(i);
                    if (!r.eliminated) {
                        list.add(r);
                    }
                }
                list.sort((a, b) -> {
                    if (Math.abs(levelPoints[b.id] - levelPoints[a.id]) > 0.01f) {
                        return Float.compare(levelPoints[b.id], levelPoints[a.id]);
                    }
                    if (b.kills != a.kills) {
                        return Integer.compare(b.kills, a.kills);
                    }
                    return Integer.compare(a.id, b.id);
                });
                order = list;
            } else if (state.levelIndex == 3) {
                // 锁定者优先，其余按生存段名次补足
                Array<Roll> list = new Array<>();
                for (int i = 0; i < state.rolls.size; i++) {
                    Roll r = state.rolls.get(i);
                    if (r.eliminated && !r.parked) {
                        continue;
                    }
                    list.add(r);
                }
                list.sort((a, b) -> {
                    if (a.parked != b.parked) {
                        return a.parked ? -1 : 1;
                    }
                    return compareForRank(a, b, Mode.SURVIVAL);
                });
                order = list;
            } else {
                order = rankedList();
            }

            for (int i = 0; i < order.size && i < slots; i++) {
                state.qualified.add(order.get(i).id);
            }
            // 剩余的人淘汰
            for (int i = 0; i < state.rolls.size; i++) {
                Roll r = state.rolls.get(i);
                if (!state.qualified.contains(r.id, false)) {
                    r.eliminated = true;
                }
            }
            boolean playerIn = state.qualified.contains(state.player.id, false);
            state.playerEliminated = !playerIn;
            state.resultTitle = "第 " + state.levelIndex + " 关结果";
            state.resultLines.add("晋级名额：" + slots);
            state.resultLines.add("你的名次：" + rankOfPlayer(order));
            state.resultLines.add(playerIn ? "晋级下一关" : "止步于此");
        } else {
            // 决战关：存活者第一，已淘汰者按"死得越晚越靠前"排（大逃杀惯例）
            Array<Roll> all = new Array<>();
            for (int i = 0; i < state.rolls.size; i++) {
                all.add(state.rolls.get(i));
            }
            all.sort(this::compareArenaPlacement);
            Roll winner = all.first();
            int playerPlace = 1;
            for (int i = 0; i < all.size; i++) {
                if (all.get(i).player) {
                    playerPlace = i + 1;
                    break;
                }
            }
            state.playerChampion = winner.player;
            state.resultTitle = "决战结果";
            state.resultLines.add("冠军：" + winner.name + "（击杀 " + winner.kills + "）");
            state.resultLines.add("存活到最后的 " + (state.rolls.size - 1) + " 人依次被淘汰");
            state.resultLines.add(state.playerChampion
                    ? "你就是滚木之王 —— 全场唯一没被撞碎的滚木"
                    : "你的名次：" + playerPlace + " / " + state.rolls.size);
        }

        // 把人淘汰后同步物理世界的活跃集合
        entrants.clear();
        for (int i = 0; i < state.rolls.size; i++) {
            Roll r = state.rolls.get(i);
            if (!r.eliminated) {
                entrants.add(r);
            }
        }

        boolean more = !state.playerEliminated && state.levelIndex < 5
                && !state.qualified.isEmpty();
        if (state.levelIndex >= 5 || state.playerEliminated || entrants.size <= 1) {
            state.tournamentOver = true;
        } else {
            state.tournamentOver = false;
        }
        if (!more) {
            state.tournamentOver = true;
        }
        phase = Phase.LEVEL_DONE;
    }

    private int rankOfPlayer(Array<Roll> order) {
        for (int i = 0; i < order.size; i++) {
            if (order.get(i).player) {
                return i + 1;
            }
        }
        return order.size;
    }

    /** 界面确认后进入下一关。 */
    public void advanceToNextLevel() {
        if (state.tournamentOver) {
            phase = Phase.TOURNAMENT_DONE;
            return;
        }
        int next = state.levelIndex + 1;
        if (next > 5) {
            state.tournamentOver = true;
            phase = Phase.TOURNAMENT_DONE;
            return;
        }
        startLevel(next);
    }

    // ================================================================== 访问器

    public MapDef map() {
        return map;
    }

    public PhysicsWorld physics() {
        return physics;
    }

    public Array<Roll> rolls() {
        return state.rolls;
    }

    public boolean ultimateUnlocked() {
        return state.levelIndex >= 4;
    }

    public void setPlayerUltimate(int ordinal) {
        playerUltimatePick = ordinal;
        if (state.player != null && ultimateUnlocked()) {
            state.player.ultimate = UltimateType.ALL[
                    MathUtils.clamp(ordinal, 0, UltimateType.ALL.length - 1)];
            state.player.ultimateCd = 0f;
        }
    }
}
