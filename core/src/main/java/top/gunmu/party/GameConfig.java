package top.gunmu.party;

/**
 * 全部数值常量的唯一来源。
 * 对应文档：docs/01-CORE-RULES.md
 *
 * <p>本类不含任何逻辑，便于调参时一处改全局生效。
 */
public final class GameConfig {

    private GameConfig() {
    }

    // ---------------------------------------------------------------- 版本
    /**
     * **版本号的唯一来源。**
     *
     * <p>命名规范（用户定的）：
     * <ul>
     *   <li>{@code a} 前缀 = **alpha / 内测版**（功能还在加，可能崩、可能改数值）；</li>
     *   <li>{@code b} 前缀 = **beta / 测试版**（功能定型，主要在修 bug 与调平衡）；</li>
     *   <li>{@code v} 前缀 = **release 正式版**。</li>
     * </ul>
     *
     * <p>为什么放在 Java 源码里而不是某个构建配置里：本工程的构建**不经过 Gradle**
     * （受限环境里 Gradle 起不来，见 D31），所以没有"一处配置两边生效"的机制。
     * 放在源码里，安卓清单的 {@code versionName}（由 {@code tools/build_apk.py} 读）、
     * 桌面 jar 的文件名（{@code tools/build_jar.py} 读）、以及**大厅界面显示的版本号**
     * 都取同一份，改一处就够，不会出现"APK 里写着 1.0.0、游戏里写着 a0.0.1"。
     */
    public static final String VERSION = "a0.0.2";
    /** 与 {@link #VERSION} 配套的整数版本号（安卓 versionCode），只增不减。 */
    public static final int VERSION_CODE = 2;
    /** 版本的阶段说明，用于界面上补一句人话。 */
    public static final String VERSION_STAGE = "内测版";

    /** 界面上显示的完整版本串，例如 {@code a0.0.1 内测版}。 */
    public static String versionText() {
        return VERSION + " " + VERSION_STAGE;
    }

    // ---------------------------------------------------------------- 对局规模
    /** 一局固定 30 根滚木（docs/00 §2）。 */
    public static final int LOG_COUNT = 30;

    // ---------------------------------------------------------------- 滚木几何
    public static final float LOG_RADIUS = 0.5f;
    public static final float LOG_LENGTH = 1.6f;
    /** 碰撞用等效球半径（docs/01 §3.1）。 */
    public static final float LOG_COLLIDE_RADIUS = 0.55f;
    /** 长轴与前进方向平行时的速度惩罚系数（docs/01 §2.2）。 */
    public static final float LONG_AXIS_PENALTY = 0.45f;
    /** 长轴对齐转速（弧度/秒）。 */
    public static final float LONG_AXIS_ALIGN_RATE = 7.0f;

    // ---------------------------------------------------------------- 生命
    public static final float MAX_HP = 100f;
    public static final float HP_BAR_HEIGHT = 0.28f;

    // ---------------------------------------------------------------- 运动
    public static final float GRAVITY = 16f;
    public static final float JUMP_VELOCITY = 5.4f;

    // ---------------------------------------------------------------- 加速（Shift）
    /**
     * 加速期间的移速倍率：+50%。
     *
     * <p>作用点是 {@code Roll.speedMultiplier()}，也就是<b>速度上限与加速度一起放大</b>，
     * 不是给一次瞬时冲量 —— 加速应该是一段时间内真的更快，而不是弹一下。
     */
    public static final float DASH_SPEED_MULT = 1.5f;
    /** 加速持续时长（秒）。 */
    public static final float DASH_TIME = 3f;
    /** 加速冷却（秒）。从按下那一刻开始算，冷却期间按了也不生效。 */
    public static final float DASH_COOLDOWN = 15f;
    public static final float MOVE_ACCEL = 22f;
    public static final float AIR_CONTROL = 0.25f;
    public static final float FLAT_MAX_SPEED = 9f;
    public static final float DOWNHILL_MAX_SPEED = 22f;
    /** 邪修状态下坡上限（docs/03 §4.2）。 */
    public static final float XIE_XIU_MAX_SPEED = 33f;
    /** 达到该速度进入狂暴态（docs/01 §2.4）。 */
    public static final float BLITZ_SPEED = 22f;
    public static final float BLITZ_TURN_PENALTY = 0.40f;
    public static final float BLITZ_DAMAGE_BONUS = 1.3f;
    public static final float DEFAULT_FRICTION = 1.0f;
    /** 下坡加速归一化系数：slopeGain 达到该值即吃满下坡速度上限。 */
    public static final float SLOPE_NORM = 0.75f;
    /** 落地缓冲：|vy| 超过该值开始吃坠落伤害。 */
    public static final float FALL_SAFE_SPEED = 12f;
    public static final float FALL_DAMAGE_SCALE = 4f;
    /** 掉出地图判定的相对高度。 */
    public static final float OUT_OF_MAP_DEPTH = 25f;

    // ---------------------------------------------------------------- 碰撞伤害
    /** 相对速度低于该值只弹开、不扣血（docs/00 §4.1）。 */
    public static final float HIT_SPEED_THRESHOLD = 8f;
    public static final float HIT_DAMAGE_SCALE = 2.0f;
    public static final float HIT_DAMAGE_CAP = 45f;
    /** 攻击方吃到的反伤比例。 */
    public static final float ATTACKER_RECOIL = 0.15f;
    /** 恢复系数：木头互撞手感。 */
    public static final float RESTITUTION = 0.35f;
    public static final float STATIC_RESTITUTION = 0.25f;
    /** 穿透消解比例（每次最多修正的比例）。 */
    public static final float PENETRATION_SLOP = 0.8f;

    // ---------------------------------------------------------------- 重生
    public static final float RESPAWN_INVULN = 2.0f;
    public static final float CHECKPOINT_RADIUS = 2.5f;
    public static final float CHECKPOINT_HEIGHT_TOLERANCE = 3.0f;
    public static final float CHECKPOINT_SPEED_BONUS = 0.20f;
    public static final float CHECKPOINT_BONUS_TIME = 1.5f;
    public static final float RESPAWN_JITTER = 1.2f;
    /** ARENA 无限复活的复活延迟。 */
    public static final float ARENA_RESPAWN_DELAY = 3.0f;
    public static final float RESPAWN_HP_RATIO = 1.0f;

    // ---------------------------------------------------------------- 时间轴
    public static final float FIXED_DT = 1f / 60f;
    public static final int MAX_STEPS_PER_FRAME = 5;
    public static final float MAX_FRAME_DELTA = 0.25f;
    /** 名额填满或时限到之后的结算缓冲。 */
    public static final float RESULT_BUFFER = 10f;

    // ---------------------------------------------------------------- 起点屏障
    /**
     * 开局起点屏障持续时间（秒）。
     *
     * <p>这段时间里所有人被一道圆柱屏障关在起点区域内：**可以自由移动，但出不去**。
     * 屏障消失后比赛才真正开始，比赛时钟与生存关的危险区推进**都不在屏障期间走**
     * （否则等于白扣 8 秒，折返图本来就不够跑）。
     */
    public static final float START_BARRIER_TIME = 8f;
    /** 起点屏障半径在"最远出生点到中心"之外再多留的余量（米）。 */
    public static final float START_BARRIER_MARGIN = 3.5f;
    /** 起点屏障半径下限（米）。出生点过于集中时也要留出活动空间。 */
    public static final float START_BARRIER_MIN_RADIUS = 11f;

    // ---------------------------------------------------------------- 关卡
    public static final float L1_TIME_LIMIT = 300f;
    public static final float L2_TIME_LIMIT = 300f;
    public static final float L3_RACE_TIME_LIMIT = 240f;
    public static final float L3_SURVIVAL_TIME_LIMIT = 180f;
    public static final float L4_RACE_TIME_LIMIT = 240f;
    public static final float L4_SURVIVAL_TIME_LIMIT = 180f;
    public static final float L4_ARENA_TIME_LIMIT = 90f;
    public static final float L5_TIME_LIMIT = 240f;
    /** L3 生存段死亡阈值（docs/02 §3）。 */
    public static final int L3_SURVIVAL_DEATH_THRESHOLD = 5;
    /** L3 阶段 A 最多锁定多少名额。 */
    // （原 `L3_RACE_LOCK_CAP` 已删除：它是"第 3 关 = 竞速段 + 生存段连跑"时代的产物。
    //   现在第 3 / 4 关是**二选一 / 三选一各跑一张图**，竞速图直接按本关晋级人数收尾。）
    /** L4 每张子图给第 1 名的名次分。 */
    public static final int SUBMAP_PLACE_POINTS = 15;
    /** L4 竞技子图人头分倍率。 */
    public static final int ARENA_KILL_POINTS = 2;

    // ---------------------------------------------------------------- 普通攻击
    /**
     * 普通攻击（决赛关专属）。
     *
     * <p>需求来自用户口述：「添加攻击技能，5 秒冷却，对扇形内的敌人造成 15 点伤害，
     * 只在决赛关可用」。它不是决战技 —— 决战技是 25~40 秒档的大招，而这是一个
     * **基础输出手段**：冷却短、伤害低、按键独立（键盘 {@code J} / 触屏「攻击」）。
     *
     * <p>只在第 5 关（决战关）开放：那是唯一"所有人都在同一个场地里互撞"的关卡，
     * 前四关要么在赛道上各跑各的、要么靠地形分胜负，加普攻只会让手感变杂。
     */
    public static final float ATTACK_COOLDOWN = 5f;
    /** 扇形半径（米）。 */
    public static final float ATTACK_RANGE = 5.0f;
    /** 扇形半角（度）。总张角 = 2×该值 = 70°。 */
    public static final float ATTACK_HALF_ANGLE = 35f;
    /** 单次伤害。 */
    public static final float ATTACK_DAMAGE = 15f;
    /** 命中击退（m/s）。 */
    public static final float ATTACK_KNOCKBACK = 8f;
    /** 高度容差（米）：隔着一层落差打不到，避免"隔空打人"。 */
    public static final float ATTACK_HEIGHT_TOLERANCE = 2.5f;
    /** 只有 {@code levelIndex >= 该值} 才开放普攻。 */
    public static final int ATTACK_MIN_LEVEL = 5;
    /** 挥击的视觉残留时长（秒）。 */
    public static final float ATTACK_FLASH = 0.22f;

    // ---------------------------------------------------------------- 道具
    public static final float ITEM_PICKUP_RADIUS = 1.6f;
    public static final float ITEM_PICKUP_HEIGHT = 2.0f;
    public static final float ITEM_DEFAULT_RESPAWN = 12f;
    /** L5 决战关道具重刷更快（docs/02 §5）。 */
    public static final float ITEM_FINAL_RESPAWN = 9f;
    public static final float ITEM_SPIN_RATE = 70f;
    /** 同一个刷新点连续 N 次普通后保底（docs/03 §2）。 */
    public static final int ITEM_PITY_COUNT = 3;
    /** 道具外观尺寸。 */
    public static final float ITEM_SIZE = 0.62f;

    // ------------------------------------------------- 竞速向速度道具（docs/03 §4.6）
    /** 遥遥领先：移速倍率。 */
    public static final float YAOLING_SPEED_MUL = 1.35f;
    /** 遥遥领先：下坡速度上限（22 → 28）。 */
    public static final float YAOLING_MAX_SPEED = 28f;
    /** 早八冲刺：加速度倍率。 */
    public static final float ZAOBA_ACCEL_MUL = 1.5f;
    /** 早八冲刺：起步冲量把速度至少抬到该值（受本帧速度上限约束）。 */
    public static final float ZAOBA_MIN_SPEED = 14f;
    /** 闪现：最大瞬移距离（米）。 */
    public static final float SHANXIAN_RANGE = 12f;
    /** 闪现：落点搜索步长（米）。落在不可行走处会往回退。 */
    public static final float SHANXIAN_STEP = 0.5f;
    /** 闪现后的短无敌（秒）。 */
    public static final float SHANXIAN_INVULN = 0.6f;
    /** 一路绿灯：加速带铺在身前多远（米）。 */
    public static final float GREEN_LIGHT_AHEAD = 5f;
    /** 一路绿灯：加速带的半宽 / 半长（米）。 */
    public static final float GREEN_LIGHT_HALF_W = 1.7f;
    public static final float GREEN_LIGHT_HALF_L = 5.0f;
    /** 一路绿灯：带的推力（m/s²）。 */
    public static final float GREEN_LIGHT_POWER = 26f;

    // ---------------------------------------------------------------- 决战技
    public static final float CHUNQIU_WINDOW = 5f;
    public static final int CHUNQIU_FRAMES = (int) (CHUNQIU_WINDOW / FIXED_DT);
    public static final float CHUNQIU_INVULN = 1.0f;

    public static final float DAODUN_DURATION = 5f;
    public static final float DAODUN_ATTACK_MUL = 1.5f;
    public static final float DAODUN_DEFENCE_DIV = 1.5f;

    /**
     * 皓宸的「顺手牵羊」判定范围。
     *
     * <p>玩家报过「皓宸在人机局偷不到任何技能」。原来是最前方 30° 锥形、3~12 米 ——
     * 人机局里 AI 会绕着你走、且竞技场里大家各打各的，要恰好把对手罩进这么窄的锥形
     * 基本靠运气。放宽到前方 60° 锥形、2~20 米，并且**兜底扩大到同一个范围**
     * （兜底原来也是 12 米，等于没有兜底）。
     *
     * <p>判定仍然要求目标**可见**（隐身 / 伪装要靠`长沙挺热`才看得见）—— 这条是设计。
     */
    public static final float HAOCHEN_RANGE_MIN = 2f;
    public static final float HAOCHEN_RANGE_MAX = 20f;
    public static final float HAOCHEN_CONE_DEG = 60f;
    public static final float CD_REFUND_ON_BREAK = 0.5f;

    public static final float ZHENGTAI_DURATION = 3f;

    public static final float ZHONGGUOREN_CAST = 2f;
    public static final float ZHONGGUOREN_CAST_SLOW = 0.25f;
    public static final float ZHONGGUOREN_RADIUS = 9f;
    public static final float ZHONGGUOREN_LAUNCH_RADIAL = 12f;
    public static final float ZHONGGUOREN_LAUNCH_UP = 7f;
    public static final float ZHONGGUOREN_DAMAGE = 25f;

    public static final float NIURoumian_DURATION = 5f;
    public static final float NIURoumian_FADE = 0.5f;

    public static final float WEB_SPEED = 16f;
    public static final float WEB_RADIUS_START = 0.8f;
    public static final float WEB_RADIUS_MAX = 2.0f;
    public static final float WEB_RANGE = 30f;
    public static final float WEB_ROOT_MAX = 3.0f;

    public static final float KUNKUN_RAP_RADIUS = 6f;
    public static final float KUNKUN_RAP_STUN = 0.5f;
    public static final float KUNKUN_SECOND_DELAY = 0.5f;
    public static final float BALL_SPEED = 8f;
    public static final float BALL_RANGE = 25f;
    public static final float BALL_RADIUS = 0.38f;
    public static final float BALL_DAMAGE = 20f;
    public static final float BALL_KNOCKBACK = 6f;

    // ------------------------------------------------- 决战技的附加攻击（docs/04 §3）
    //
    // 玩家报过「大多数技能都没有攻击能力」。逐条看，8 个决战技里只有
    // 中国人能飞（25 真伤）与坤坤（20 伤篮球）能打人，其余 6 个是纯位移/防御/控制/偷窃。
    // 这一批常量给那 6 个各补一段**小剂量**伤害：主伤害仍然来自撞击，
    // 附加伤害只负责让"放了技能却什么也没发生"变成"放完确实推开了周围的人"。
    //
    // 全部走 Combat.deal（普通伤害，吃刀盾/破防/蛋挞等增减伤），
    // 不用真伤 —— 只有中国人能飞那条是按 D15 定的真伤。

    /** 我的刀盾：施放瞬间的身周震击（半径 / 伤害 / 击退）。 */
    public static final float DAODUN_BURST_RADIUS = 4.5f;
    public static final float DAODUN_BURST_DAMAGE = 12f;
    public static final float DAODUN_BURST_KNOCKBACK = 8f;

    /** 正太扭腰：扭腰甩开周围的人（半径 / 伤害 / 击退）。 */
    public static final float ZHENGTAI_BURST_RADIUS = 4.0f;
    public static final float ZHENGTAI_BURST_DAMAGE = 10f;
    public static final float ZHENGTAI_BURST_KNOCKBACK = 11f;

    /** 春秋肠：回到过去时在原地留下的冲击波（半径 / 伤害 / 击退）。 */
    public static final float CHUNQIU_BURST_RADIUS = 5.0f;
    public static final float CHUNQIU_BURST_DAMAGE = 10f;
    public static final float CHUNQIU_BURST_KNOCKBACK = 6f;

    /** 我的世界皓宸：施放时顺手的锥形一击（伤害；范围复用 HAOCHEN_*）。 */
    public static final float HAOCHEN_STRIKE_DAMAGE = 12f;

    /**
     * 忘情牛肉面：隐身期间的撞击伤害倍率（"破隐一击"）。
     *
     * <p>隐身本来是一个纯逃脱技，加这一条之后它有了攻击性：
     * 贴着人撞过去打一下特别疼，但**命中即破隐**（D16 的"主动攻击提前解除隐身"
     * 在这里第一次真的有了作用对象）。
     */
    public static final float NIUROUMIAN_AMBUSH_MUL = 1.8f;

    /** mj 蜘蛛网：命中瞬间的附加伤害（定身之外的"网住就撕"）。 */
    public static final float WEB_HIT_DAMAGE = 8f;

    // ---------------------------------------------------------------- 视角
    public static final float CAMERA_FOV = 55f;
    /**
     * 相机俯角（度，正数表示往下看）。
     * 35° 太平，赛道在屏幕上被压成一条线；50° 左右才能同时看清脚下与前方几十米。
     */
    public static final float CAMERA_PITCH = 50f;
    /** 相机到**看点**的距离（米）。 */
    public static final float CAMERA_DISTANCE = 22f;
    /** 位置平滑速度；越大跟随越硬。 */
    public static final float CAMERA_LERP = 6.5f;
    /** 朝向平滑速度；刻意比位置慢，转弯时不会被甩。 */
    public static final float CAMERA_YAW_LERP = 3.2f;
    /** 看点沿赛道方向前移，用来多露出前方的路。 */
    public static final float CAMERA_LOOKAHEAD = 7f;
    /** 看点抬高，把玩家压到画面下三分之一而不是正中。 */
    public static final float CAMERA_LOOK_LIFT = 1.6f;

    // ------------------------------------------------------------ 手动转视角
    /** 拖动灵敏度（弧度 / 屏幕像素，虚拟坐标）。拖满 1600 px ≈ 200°。 */
    public static final float VIEW_RAD_PER_PX = 0.0022f;
    /** 拖动死区（像素）：手指按下时的抖动不该让视角自己飘。 */
    public static final float VIEW_DEADZONE_PX = 2f;
    /** 停手多久之后开始回中（秒）。这段时间里玩家可以自由看侧面。 */
    public static final float VIEW_RECENTER_DELAY = 2.5f;
    /**
     * 回中速度（1/秒，指数衰减）。
     *
     * <p><b>只有竞速图回中</b>：那里有赛道切线当参照物，转歪了必须自己转回来，
     * 否则车头方向与画面方向永久错位。生存 / 竞技图没有参照物，
     * 手动偏移就是绝对朝向，回中反而会让玩家"刚转过去就被掰回来"。
     */
    public static final float VIEW_RECENTER_RATE = 1.6f;

    // ---------------------------------------------------------------- 杂项
    /** 已推进度的存档点权重（docs/00 §4.4）。 */
    public static final float PROGRESS_CHECKPOINT_SCALE = 1000f;
    /** AI 反应延迟（秒），按难度。 */
    public static final float[] AI_REACTION = {0.45f, 0.28f, 0.14f};
    public static final float[] AI_JITTER = {0.55f, 0.32f, 0.15f};
    public static final float[] AI_SKILL_CHANCE = {0.35f, 0.6f, 0.85f};

    // ---------------------------------------------------------------- 平台差异
    /**
     * 地形渲染网格抽稀步长：1 = 每个格子都画，2 = 隔一个画。
     * <b>只影响画面，不影响物理</b>——物理永远用完整的 heightfield，
     * 因此桌面与安卓的模拟结果完全一致（移动端定为 2，顶点数降到约 1/4）。
     */
    public static int terrainRenderStep = 1;

    /** HUD 的参考屏幕高度：以此为 1.0 倍基准，按实际高度缩放整个界面。 */
    public static final float UI_REFERENCE_HEIGHT = 810f;
    /** HUD 的参考屏幕宽度（超宽屏用它兜住缩放下限，别把界面拉得过大）。 */
    public static final float UI_REFERENCE_WIDTH = 1600f;
    public static final float UI_SCALE_MIN = 0.85f;
    public static final float UI_SCALE_MAX = 2.4f;

    /**
     * 虚拟短边低于它就切 {@code HudLayout.Profile.COMPACT}（手表 / 极窄窗）。
     *
     * <p>手表（396×396）在 0.85 的缩放下限下虚拟屏只剩 466×466，
     * 而标准排版至少要 800 宽才排得开 —— 所以必须**换排版**，而不是继续缩小字号
     * （字号再小就低于可读线了）。见 {@code docs/07-UI-LAYOUT.md}。
     */
    public static final float UI_COMPACT_MAX_SHORT = 700f;

    /** 安卓返回键双击退出的时间窗口（秒）。 */
    public static final float BACK_EXIT_WINDOW = 2f;
}
