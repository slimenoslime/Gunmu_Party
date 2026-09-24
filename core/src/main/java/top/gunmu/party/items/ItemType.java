package top.gunmu.party.items;

/**
 * 27 个道具，全部取自 2026 年正在流行的网络热梗。
 * 对应文档：docs/03-ITEMS.md（数值与文案以该文档为准）
 *
 * <p>枚举顺序即图鉴顺序：干扰类 → 增益类 → 竞速向速度道具 → 反击类 → 场地召唤类 → 特殊。
 */
public enum ItemType {

    // ---------------------------------------------------------- 干扰类（6）
    PIGEON_POP("疯狂的鸽子",
            "《Pigeon Pop》结算页魔性摇摆舞 + BGM《UwU Funk》",
            Rarity.COMMON, 12f, Targeting.AREA, 2.5f,
            "半径 8m 内的敌人被强制摇摆：左右反转、无法跳跃、无法使用道具"),

    CHAIN_OFF("链子先掉",
            "命运的齿轮还没转，链子先掉了",
            Rarity.RARE, 8f, Targeting.NEAREST_AHEAD, 3f,
            "目标最高速度减半且无法跳跃"),

    MONEY_FREE("财富自由",
            "财富已经自由了，它想来就来想走就走",
            Rarity.COMMON, 10f, Targeting.NEAREST_AHEAD, 0f,
            "目标随机丢弃手上的道具；没道具则随机清除一个增益"),

    CRY_BABY("你已急哭",
            "调侃对方情绪上头、破防",
            Rarity.COMMON, 11f, Targeting.NEAREST_AHEAD, 3f,
            "目标视野被泪滴遮挡，移速 -20%"),

    PO_FANG("破防",
            "情绪瞬间崩溃，感动生气难过都能用",
            Rarity.COMMON, 10f, Targeting.NEAREST_AHEAD, 6f,
            "目标防御归零，受到伤害 x1.35"),

    EGG_TART("被确诊为蛋挞",
            "被确诊为 xx 梗：蛋挞 = 脆皮打工人",
            Rarity.RARE, 9f, Targeting.NEAREST_AHEAD, 8f,
            "目标受伤 x1.30，被撞时额外击退 +50%"),

    // ---------------------------------------------------------- 增益类（7）
    WILD_DOG_MILK("野生狗奶",
            "人类畏惧时间，时间畏惧野生狗奶",
            Rarity.RARE, 6f, Targeting.SELF, 10f,
            "立刻回满血，之后每秒 +4 缓回"),

    LOVE_SELF("爱你老己",
            "把「自己」当老朋友温柔对待（年度社交关键词）",
            Rarity.COMMON, 10f, Targeting.SELF, 0f,
            "回血 30 并解除全部负面状态"),

    CHAIN_GEARS("齿轮转动",
            "命运的齿轮开始转动",
            Rarity.RARE, 9f, Targeting.SELF, 5f,
            "移速 +35%、跳跃初速 +25%"),

    ZUOWAN_NIDE("做完你的做你的",
            "奶茶店主被顾客疯狂催单的魔性台词",
            Rarity.RARE, 9f, Targeting.SELF, 3f,
            "移速 +60%；结束后 2s 移速 -20%"),

    XIE_XIU("邪修",
            "不走正统路线，用野路子低成本巧劲完成目标",
            Rarity.RARE, 6f, Targeting.SELF, 8f,
            "无视泥坑与减速，下坡速度上限 22 -> 33 m/s"),

    QIANXIN_WANKU("千薪万苦",
            "拿着几千块工资，承受上万份委屈",
            Rarity.RARE, 7f, Targeting.SELF, 8f,
            "伤害加成 = (1 - 血量%) x 60%，血越少打得越狠"),

    OPOSSUM("背手负鼠",
            "我累了但不会倒下",
            Rarity.RARE, 6f, Targeting.SELF, 1.5f,
            "装死：首次致命伤无效化，期间不可移动"),

    // ---------------------------------------------------------- 竞速向速度道具（4）
    //
    // 玩家报过：「给竞速图多加点实用的速度道具」。
    // 竞速图在按模式过滤掉 6 个战斗专用道具之后只剩 17 个可用，而其中真正**加速自己**
    // 的只有齿轮转动 / 做完你的做你的 / 邪修三个 —— 三个图轮着抽，很快就腻了。
    // 这四个都是"跑得快"的正统解法：抬上限、抬起步、直接位移、给自己铺加速带。
    YAOYAO_LINGXIAN("遥遥领先",
            "发布会式夸口的通用化：断崖式领先、把自己甩开一整个身位",
            Rarity.RARE, 8f, Targeting.SELF, 8f,
            "移速 +35%，下坡速度上限 22 -> 28 m/s"),

    ZAO_BA("早八",
            "大学生早八生死时速：八点的第一节课，七点五十九还在宿舍楼下",
            Rarity.COMMON, 11f, Targeting.SELF, 6f,
            "起步冲量（速度立刻抬到 14 m/s）+ 加速度 ×1.5"),

    SHAN_XIAN("闪现",
            "MOBA 里的保命键，冷却一好手就痒",
            Rarity.EPIC, 5f, Targeting.SELF, 0f,
            "朝前方瞬移最多 12 m（落点必须在路面上），并获得 0.6 s 无敌"),

    YI_LU_LU("一路绿灯",
            "打工人通勤的最高礼遇：从家门口到公司一路没红灯",
            Rarity.RARE, 8f, Targeting.SELF, 8f,
            "在身前铺一条 3.4 × 10 m 的加速带，进入者被推着往前跑"),

    // ---------------------------------------------------------- 反击/反制类（5）
    EXTERNAL_BURN("外耗",
            "拒绝精神内耗，与其内耗自己，不如外耗别人",
            Rarity.RARE, 7f, Targeting.SELF, 12f,
            "受到伤害的 50% 反弹给来源，自己仍承受 50%"),

    JIANGBAN_DUCK("酱板鸭反转",
            "雪山救狐狸反转梗：我是当年那只酱板鸭",
            Rarity.EPIC, 5f, Targeting.SELF, 10f,
            "下一次受击 100% 免伤并全额反弹给来源"),

    BENGHZU("轻松绷住",
            "反话：表面淡定，内心濒临崩溃",
            Rarity.RARE, 6f, Targeting.SELF, 8f,
            "受到的伤害延迟 2s 结算，期间回血可抵消"),

    LIUWENXIANG("带去刘文祥",
            "如果全世界都指责你，我带你去吃刘文祥",
            Rarity.RARE, 8f, Targeting.SELF, 4f,
            "清除全部负面并免疫控制"),

    CHANGSHA_HOT("长沙挺热",
            "孙颖莎采访「实话文学」：别人要漂亮话，你只讲大实话",
            Rarity.RARE, 8f, Targeting.SELF, 12f,
            "看破隐身与伪装，标记 15m 内敌人轮廓"),

    // ---------------------------------------------------------- 场地/召唤类（3）
    DONG_MEN("冻门",
            "务实防御型生活方式（冻门）",
            Rarity.RARE, 7f, Targeting.SELF, 8f,
            "身前铺开 6x6m 冰面，进入者打滑失控"),

    LOBSTER_FARM("养龙虾",
            "职场玩梗：调教 AI 智能体替你干活，自己摸鱼",
            Rarity.RARE, 6f, Targeting.SELF, 15f,
            "召唤 1 只龙虾傀儡跟随，自动追撞最近敌人"),

    HAKIMI("哈基米",
            "泛指可爱小动物，萌物代名词",
            Rarity.COMMON, 11f, Targeting.SELF, 0f,
            "召唤 3 只哈基米向前冲 12m，命中 8 伤并击退"),

    // ---------------------------------------------------------- 特殊（2）
    EBRUFEN("电子布洛芬",
            "治愈短视频 / AI 陪伴成日常消费",
            Rarity.RARE, 8f, Targeting.SELF, 10f,
            "每秒回 6 血，但期间攻击力归零"),

    BLIND_BOX("谷子盲盒",
            "谷子 / 盲盒消费",
            Rarity.EPIC, 5f, Targeting.SELF, 0f,
            "立即随机触发另一个道具的效果");

    /** 作用对象。 */
    public enum Targeting {
        SELF,
        /** 前方最近的 1 名敌人。 */
        NEAREST_AHEAD,
        /** 以自己为中心的半径范围。 */
        AREA
    }

    public final String cn;
    public final String meme;
    public final Rarity rarity;
    public final float weight;
    public final Targeting targeting;
    public final float duration;
    public final String desc;

    ItemType(String cn, String meme, Rarity rarity, float weight,
             Targeting targeting, float duration, String desc) {
        this.cn = cn;
        this.meme = meme;
        this.rarity = rarity;
        this.weight = weight;
        this.targeting = targeting;
        this.duration = duration;
        this.desc = desc;
    }

    public boolean isInstant() {
        return duration <= 0f;
    }

    /** 是否属于"负面状态"，可被 爱你老己 / 带去刘文祥 清除。 */
    /**
     * 是否只对"会持续互相打斗"的模式有意义（生存 / 竞技），**竞速图不该刷**。
     *
     * <p>竞速图里大家各跑各的，被打断也只是摔一跤然后接着跑，所以：
     * <ul>
     *   <li>纯防御类（免死、反伤、免伤反弹、伤害延迟）没有攻击来源可以防；</li>
     *   <li>纯输出类（血越少打得越狠）没有目标可以打；</li>
     *   <li>反隐类（看破隐身与伪装）看不到需要看破的人。</li>
     * </ul>
     * 玩家报过「竞速关会刷出防御类的道具，这些只在生存图有用，在竞速图没啥用」。
     *
     * <p>判定是**显式枚举**而不是按稀有度/权重猜：漏掉一个就是一个坑，
     * 而且 {@code tools/ItemPoolTest} 会逐张竞速图枚举刷新池来兜底。
     *
     * <p>目前 6 / 27 个（可刷池 21）。新增的 4 个竞速向速度道具**都不是**战斗专用：
     * 抬上限、抬起步、瞬移、给自己铺加速带，在任何模式下都成立。
     */
    public boolean combatOnly() {
        switch (this) {
            // 纯防御
            case OPOSSUM:        // 背手负鼠：首次致命伤无效化
            case EXTERNAL_BURN:  // 外耗：受伤 50% 反弹
            case JIANGBAN_DUCK:  // 酱板鸭反转：下次受击 100% 免伤并全额反弹
            case BENGHZU:        // 轻松绷住：伤害延迟 2s 结算
            // 纯输出
            case QIANXIN_WANKU:  // 千薪万苦：血越少伤害越高
            // 反隐
            case CHANGSHA_HOT:   // 长沙挺热：看破隐身与伪装
                return true;
            default:
                return false;
        }
    }

    public boolean isDebuff() {
        switch (this) {
            case PIGEON_POP:
            case CHAIN_OFF:
            case CRY_BABY:
            case PO_FANG:
            case EGG_TART:
                return true;
            default:
                return false;
        }
    }

    /** 是否为"可被清除增益"（财富自由 会随机打掉一个）。 */
    public boolean isBuff() {
        return !isDebuff() && !isInstant() && targeting == Targeting.SELF;
    }

    /** 全部道具（图鉴顺序）。 */
    public static final ItemType[] ALL = values();

    /** 计数校验：6 + 7 + 4 + 5 + 3 + 2 = 27（docs/03-ITEMS.md §4）。 */
    public static final int EXPECTED_COUNT = 27;

    public static ItemType byId(String id) {
        for (ItemType t : ALL) {
            if (t.name().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }
}
