package top.gunmu.party.map;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Mode;
import top.gunmu.party.core.TerrainType;
import top.gunmu.party.items.ItemType;
import top.gunmu.party.physics.StaticBody;

/**
 * 10 张地图的注册表（RACE 4 / SURVIVAL 3 / ARENA 3）。
 * 对应文档：docs/02-LEVELS-AND-MAPS.md §6
 *
 * <p>每次调用 {@link #create(String)} 都**重新构建**一张全新的地图，
 * 包含新的高度场、机关相位与刷新点状态。
 */
public final class MapRegistry {

    /** 同一模式内必须一致的数量基线（docs/02 §6）。 */
    public static final int RACE_CHECKPOINTS = 4;
    public static final int RACE_ITEM_SPAWNS = 10;
    public static final int RACE_SPAWNS = 30;
    public static final int SURVIVAL_ITEM_SPAWNS = 12;
    public static final int SURVIVAL_SPAWNS = 20;
    public static final int ARENA_ITEM_SPAWNS = 14;
    public static final int ARENA_SPAWNS = 10;

    /** 同一模式内的伤害预算（Σ 机关伤害），归一化机关难度的代理指标。 */
    public static final float RACE_DAMAGE_BUDGET = 60f;
    public static final float SURVIVAL_DAMAGE_BUDGET = 85f;
    public static final float ARENA_DAMAGE_BUDGET = 43f;

    public static final float RACE_TARGET_LENGTH = 620f;
    public static final float SURVIVAL_RADIUS = 45f;
    public static final float ARENA_RADIUS = 35f;

    public static final String[] RACE_IDS = {
            "race_green_hill", "race_switchback", "race_ice_canyon", "race_collapse"
    };

    /**
     * 所有 RACE 图的路面宽度统一倍率。
     * 走廊太窄时高速滚木几乎必然冲下深渊（实测 94% 的死亡都来自坠落深渊），
     * 因此把路面与路肩一起放宽，让"下坡加速"和"掉下去"之间留出容错。
     */
    public static final float ROUTE_WIDTH_SCALE = 1.4f;
    public static final float ROUTE_SHOULDER_BONUS = 2.5f;
    public static final String[] SURVIVAL_IDS = {
            "surv_water_rise", "surv_shrink_ring", "surv_saw_pit"
    };
    public static final String[] ARENA_IDS = {
            "arena_duel_pit", "arena_pinball", "arena_lava_ring"
    };

    private MapRegistry() {
    }

    public static String[] idsFor(Mode mode) {
        switch (mode) {
            case RACE:
                return RACE_IDS;
            case SURVIVAL:
                return SURVIVAL_IDS;
            default:
                return ARENA_IDS;
        }
    }

    // ================================================================== 工厂

    /**
     * 建图。**所有地图的唯一出口** —— 起点区域在这里统一推导，
     * 免得每张图的建图函数各写一遍（写漏一张就等于那张图没有起点屏障）。
     */
    public static MapDef create(String id) {
        MapDef m = build(id);
        m.computeStartZone(GameConfig.START_BARRIER_MARGIN,
                GameConfig.START_BARRIER_MIN_RADIUS);
        MapValidator.validateStartZone(m);
        return m;
    }

    private static MapDef build(String id) {
        switch (id) {
            case "race_green_hill":
                return greenHill();
            case "race_switchback":
                return switchback();
            case "race_ice_canyon":
                return iceCanyon();
            case "race_collapse":
                return collapse();
            case "surv_water_rise":
                return waterRise();
            case "surv_shrink_ring":
                return shrinkRing();
            case "surv_saw_pit":
                return sawPit();
            case "arena_duel_pit":
                return duelPit();
            case "arena_pinball":
                return pinball();
            case "arena_lava_ring":
                return lavaRing();
            default:
                throw new IllegalArgumentException("未知地图 id: " + id);
        }
    }

    // ================================================================== RACE

    private static MapDef greenHill() {
        MapDef m = new MapDef("race_green_hill", "青丘缓坡", Mode.RACE);
        m.timeLimit = GameConfig.L1_TIME_LIMIT;
        Route r = new Route();
        // 宽阔 S 形缓坡，全程最宽，教学图
        r.add(0, 170, -290, 10, 5, TerrainType.GRASS);
        r.add(8, 168, -250, 10, 5, TerrainType.GRASS);
        r.add(20, 160, -210, 10, 5, TerrainType.GRASS);
        r.add(32, 145, -170, 10, 5, TerrainType.GRASS);
        r.add(38, 120, -130, 10.5f, 5, TerrainType.GRASS);
        r.add(34, 100, -90, 10.5f, 5, TerrainType.GRASS);
        r.add(20, 88, -50, 10.5f, 5, TerrainType.GRASS);
        r.add(0, 80, -10, 10.5f, 5, TerrainType.MUD);
        r.add(-22, 60, 30, 10, 5, TerrainType.GRASS);
        r.add(-34, 45, 70, 10, 5, TerrainType.GRASS);
        r.add(-32, 30, 110, 10, 5, TerrainType.GRASS);
        r.add(-16, 18, 150, 10, 5, TerrainType.MUD);
        r.add(6, 8, 190, 10, 5, TerrainType.GRASS);
        r.add(24, 3, 230, 11, 6, TerrainType.GRASS);
        r.add(34, 1, 265, 12, 6, TerrainType.GRASS);
        r.add(38, 0, 300, 14, 8, TerrainType.GRASS);
        finishRace(m, r);
        // 机关：弹跳台 ×2、推板 ×1（非伤害） + 伤害预算 60 = 2 锤 + 2 锯
        piston(m, 0.20f, 7f, 0f, 1f);
        bouncer(m, 0.34f, -7f, 12f);
        bouncer(m, 0.62f, 7f, 12f);
        piston(m, 0.78f, -7f, 0f, -1f);
        hammer(m, 0.28f, 0f, 6.5f, 1.1f, 0f);
        hammer(m, 0.71f, 0f, 6.5f, -1.1f, 1.6f);
        saw(m, 0.50f, 8f, 10f, 3.2f, 1f);
        saw(m, 0.88f, -8f, 10f, 3.2f, -1f);
        // 教学图：新手上路最缺的是"起步"，早八给它一个立刻能跑起来的推力
        m.bias(ItemType.CHAIN_GEARS, 1.5f).bias(ItemType.LOVE_SELF, 1.5f)
                .bias(ItemType.ZAO_BA, 2f);
        validate(m);
        return m;
    }

    private static MapDef switchback() {
        MapDef m = new MapDef("race_switchback", "之字断崖", Mode.RACE);
        m.timeLimit = GameConfig.L2_TIME_LIMIT;
        Route r = new Route();
        // 连续 Z 字急弯，中段两处无路肩窄桥。
        //
        // ⚠️ 这些坐标不是随便摆的，改之前先读这一段。
        //
        // 地形是"按离最近的路段逐格定高"生成的，而<b>一个格子只能存一个高度</b>。
        // 两条路在水平面上靠得比「半宽之和」还近时，高的那条会盖住低的那条：
        // 玩家走在低的那条上，脚下读到高的那条的高度，`resolveGround` 就会把他
        // <b>瞬移到上面去</b>；判定也会在两条路的弧长之间来回跳（偷鸡）。
        //
        // 旧坐标正踩这个坑：终点 (82, 6, -288) 与中段 (76, 112, -292) 水平距离只有 7 米、
        // 高度差 106 米，玩家一到终点就被抬到 y≈120；起点 (0, 150, -280) 也压在中段
        // (8, 100, -296) 头上，出生点直接落进中段的地形里（实测甚至是"水平距 0.0m"）。
        //
        // 现在按「每条横向带子之间留 ≥ 90 米 z 间距」重排，保证相隔 2 段以上的路都不重叠。
        // 这条约束由 tools/RouteOverlapTest 守着 —— 再改坐标请先跑它。
        r.add(0, 150, -40, 9, 4, TerrainType.ROCK);
        r.add(0, 148, -70, 9, 4, TerrainType.ROCK);
        r.add(6, 145, -104, 9, 4, TerrainType.ROCK);
        r.add(44, 140, -110, 8, 3, TerrainType.ROCK);
        r.add(78, 135, -116, 8, 3, TerrainType.ROCK);
        r.add(82, 130, -180, 5, 0, TerrainType.ROCK);
        r.add(82, 124, -246, 5, 0, TerrainType.ROCK);
        r.add(44, 118, -252, 8, 3, TerrainType.ROCK);
        r.add(6, 112, -246, 8, 3, TerrainType.ROCK);
        r.add(2, 106, -312, 8, 3, TerrainType.SAND);
        r.add(2, 100, -378, 6, 1, TerrainType.SAND);
        r.add(44, 94, -384, 6, 1, TerrainType.SAND);
        r.add(78, 88, -378, 8, 3, TerrainType.GRASS);
        r.add(82, 80, -444, 8, 3, TerrainType.GRASS);
        r.add(82, 72, -510, 9, 4, TerrainType.GRASS);
        r.add(44, 64, -516, 10, 5, TerrainType.GRASS);
        r.add(6, 56, -510, 11, 6, TerrainType.GRASS);
        r.add(2, 48, -576, 14, 8, TerrainType.GRASS);
        // 窄桥段两侧加护栏
        wallRange(r, 7, 9, true, true);
        finishRace(m, r);
        boost(m, 0.16f, 0f, 0f, 1f, 15f);
        hammer(m, 0.10f, 0f, 6f, 1.3f, 0f);
        saw(m, 0.30f, 6f, 9f, 3.5f, 1f);
        hammer(m, 0.50f, 0f, 6f, -1.2f, 2.2f);
        saw(m, 0.70f, -6f, 9f, 3.5f, -1f);
        // 之字折返：一层一层往下甩，速度上限比什么都值钱
        m.bias(ItemType.HAKIMI, 1.6f).bias(ItemType.CRY_BABY, 1.6f)
                .bias(ItemType.YAOYAO_LINGXIAN, 2f);
        validate(m);
        return m;
    }

    private static MapDef iceCanyon() {
        MapDef m = new MapDef("race_ice_canyon", "冰隙峡谷", Mode.RACE);
        m.timeLimit = GameConfig.L2_TIME_LIMIT;
        Route r = new Route();
        // 一条大冰面直冲到底，两侧是风扇气流
        r.add(0, 160, -280, 11, 5, TerrainType.ICE);
        r.add(0, 152, -240, 11, 5, TerrainType.ICE);
        r.add(0, 138, -200, 11, 5, TerrainType.ICE);
        r.add(0, 118, -160, 11, 5, TerrainType.ICE);
        r.add(0, 96, -120, 11, 5, TerrainType.ICE);
        r.add(4, 76, -80, 11, 5, TerrainType.ICE);
        r.add(14, 62, -40, 10, 5, TerrainType.ICE);
        r.add(18, 54, 0, 10, 5, TerrainType.SAND);
        r.add(14, 46, 40, 10, 5, TerrainType.ICE);
        r.add(2, 34, 80, 10, 5, TerrainType.ICE);
        r.add(-14, 22, 120, 10, 5, TerrainType.ICE);
        r.add(-18, 12, 160, 11, 5, TerrainType.ICE);
        r.add(-12, 5, 200, 11, 6, TerrainType.GRASS);
        r.add(0, 2, 240, 12, 6, TerrainType.GRASS);
        r.add(10, 0, 280, 14, 8, TerrainType.GRASS);
        finishRace(m, r);
        fan(m, 0.18f, 10f, 3f, 9f);
        fan(m, 0.34f, -10f, 3f, 9f);
        fan(m, 0.52f, 10f, 3f, 9f);
        fan(m, 0.68f, -10f, 3f, 9f);
        boost(m, 0.26f, 0f, 0f, 1f, 16f);
        boost(m, 0.60f, 0f, 0f, 1f, 16f);
        hammer(m, 0.33f, 0f, 7f, 1.2f, 0f);
        hammer(m, 0.72f, 0f, 7f, -1.2f, 0f);
        saw(m, 0.10f, 7f, 10f, 3.4f, 1f);
        saw(m, 0.90f, -7f, 10f, 3.4f, -1f);
        // 冰隙峡谷：摩擦力只有 0.35，靠脚底打滑甩出去是常态 —— 闪现能救回来
        m.bias(ItemType.DONG_MEN, 3f).bias(ItemType.XIE_XIU, 2f)
                .bias(ItemType.SHAN_XIAN, 2f);
        validate(m);
        return m;
    }

    private static MapDef collapse() {
        MapDef m = new MapDef("race_collapse", "崩落长阶", Mode.RACE);
        m.timeLimit = GameConfig.L2_TIME_LIMIT;
        Route r = new Route();
        // 长台阶下降，中段靠传送带横移换线
        r.add(0, 150, -290, 10, 4, TerrainType.ROCK);
        r.add(0, 145, -252, 10, 4, TerrainType.ROCK);
        r.add(0, 136, -214, 10, 4, TerrainType.SAND);
        r.add(10, 124, -176, 9, 4, TerrainType.SAND);
        r.add(30, 112, -142, 9, 4, TerrainType.SAND);
        r.add(52, 100, -110, 9, 4, TerrainType.SAND);
        r.add(58, 88, -72, 9, 4, TerrainType.ROCK);
        r.add(50, 76, -34, 9, 4, TerrainType.ROCK);
        r.add(30, 64, 0, 9, 4, TerrainType.ROCK);
        r.add(10, 54, 36, 9, 4, TerrainType.SAND);
        r.add(4, 44, 74, 9, 4, TerrainType.SAND);
        r.add(10, 32, 112, 10, 5, TerrainType.SAND);
        r.add(24, 20, 150, 10, 5, TerrainType.GRASS);
        r.add(34, 8, 188, 11, 6, TerrainType.GRASS);
        r.add(34, 2, 226, 12, 6, TerrainType.GRASS);
        r.add(30, 0, 264, 14, 8, TerrainType.GRASS);
        finishRace(m, r);
        conveyorOnRoute(m, 0.22f, 0f, 1f, 0f, 8f);
        conveyorOnRoute(m, 0.40f, 0f, -1f, 0f, 8f);
        conveyorOnRoute(m, 0.58f, 0f, 1f, 0f, 8f);
        bouncer(m, 0.30f, -7f, 12f);
        bouncer(m, 0.50f, 7f, 12f);
        bouncer(m, 0.70f, -7f, 12f);
        hammer(m, 0.16f, 0f, 6.5f, 1.1f, 0.4f);
        saw(m, 0.32f, 7f, 10f, 3.3f, 1f);
        saw(m, 0.70f, -7f, 10f, 3.3f, -1f);
        hammer(m, 0.90f, 0f, 6.5f, -1.1f, 2.0f);
        // 崩落长阶：落石一路砸下来，给自己铺一条跑得更快的带子
        m.bias(ItemType.HAKIMI, 2f).bias(ItemType.CRY_BABY, 2f)
                .bias(ItemType.YI_LU_LU, 2f);
        validate(m);
        return m;
    }

    // ================================================================== SURVIVAL

    private static MapDef waterRise() {
        MapDef m = new MapDef("surv_water_rise", "涨水盆地", Mode.SURVIVAL);
        m.timeLimit = GameConfig.L3_SURVIVAL_TIME_LIMIT;
        m.arenaRadius = SURVIVAL_RADIUS;
        m.field = TerrainBuilder.fromArea(0, 0, SURVIVAL_RADIUS, TerrainBuilder.Shape.BOWL,
                10f, 9f, TerrainType.SAND, 0.35f);
        m.waterRise = 0.42f;
        areaSpawns(m, SURVIVAL_SPAWNS, 0.72f);
        areaItems(m, SURVIVAL_ITEM_SPAWNS, 0.55f, GameConfig.ITEM_DEFAULT_RESPAWN);
        bouncer(m, -14f, -8f, 12f);
        bouncer(m, 16f, -6f, 12f);
        bouncer(m, 0f, 20f, 12f);
        conveyor(m, -20f, 10f, 1f, 0f, 7f);
        conveyor(m, 20f, -10f, -1f, 0f, 7f);
        sawAt(m, -18f, 18f, 9f, 3.0f, 1f, 0f);
        sawAt(m, 18f, 18f, 9f, 3.0f, -1f, 0f);
        hammerAt(m, 0f, -22f, 8f, 0.9f, 0f);
        hammerAt(m, 0f, 22f, 8f, 0.9f, 1.5f);
        crusher(m, 0f, 0f, 2.4f);
        m.bias(ItemType.CHAIN_GEARS, 2f).bias(ItemType.ZUOWAN_NIDE, 2f);
        validate(m);
        return m;
    }

    private static MapDef shrinkRing() {
        MapDef m = new MapDef("surv_shrink_ring", "收缩木桩林", Mode.SURVIVAL);
        m.timeLimit = GameConfig.L3_SURVIVAL_TIME_LIMIT;
        m.arenaRadius = SURVIVAL_RADIUS;
        m.field = TerrainBuilder.fromArea(0, 0, SURVIVAL_RADIUS, TerrainBuilder.Shape.FLAT,
                6f, 0f, TerrainType.GRASS, 0.5f);
        m.poisonStartRadius = SURVIVAL_RADIUS;
        m.poisonShrink = 0.20f;
        areaSpawns(m, SURVIVAL_SPAWNS, 0.72f);
        areaItems(m, SURVIVAL_ITEM_SPAWNS, 0.55f, GameConfig.ITEM_DEFAULT_RESPAWN);
        // 18 根木桩（静态挡路，不造成伤害）
        for (int i = 0; i < 18; i++) {
            double a = i / 18.0 * Math.PI * 2.0;
            float rr = 14f + (i % 3) * 7f;
            float px = (float) Math.cos(a) * rr;
            float pz = (float) Math.sin(a) * rr;
            pillar(m, px, pz, 0.55f, 2.2f);
        }
        spinner(m, -12f, -12f, 5f, 1.3f);
        spinner(m, 12f, 12f, 5f, -1.3f);
        sawAt(m, -22f, 0f, 9f, 3.2f, 0f, 1f);
        sawAt(m, 22f, 0f, 9f, 3.2f, 0f, -1f);
        hammerAt(m, 0f, -20f, 8f, 1.0f, 0f);
        hammerAt(m, 0f, 20f, 8f, 1.0f, 1.6f);
        crusher(m, 0f, 0f, 2.2f);
        m.bias(ItemType.DONG_MEN, 2f).bias(ItemType.PIGEON_POP, 2f);
        validate(m);
        return m;
    }

    private static MapDef sawPit() {
        MapDef m = new MapDef("surv_saw_pit", "锯盘深井", Mode.SURVIVAL);
        m.timeLimit = GameConfig.L3_SURVIVAL_TIME_LIMIT;
        m.arenaRadius = SURVIVAL_RADIUS;
        m.field = TerrainBuilder.fromArea(0, 0, SURVIVAL_RADIUS, TerrainBuilder.Shape.FUNNEL,
                12f, 8f, TerrainType.ROCK, 0.3f);
        areaSpawns(m, SURVIVAL_SPAWNS, 0.80f);
        areaItems(m, SURVIVAL_ITEM_SPAWNS, 0.58f, GameConfig.ITEM_DEFAULT_RESPAWN);
        sawAt(m, -8f, -8f, 8f, 3.6f, 1f, 1f);
        sawAt(m, 8f, -8f, 8f, 3.6f, -1f, 1f);
        sawAt(m, -8f, 8f, 8f, 3.6f, -1f, -1f);
        sawAt(m, 8f, 8f, 8f, 3.6f, 1f, -1f);
        sawAt(m, 0f, 0f, 12f, 2.4f, 1f, 0f);
        crusher(m, 0f, 0f, 1.8f);
        m.bias(ItemType.XIE_XIU, 2f).bias(ItemType.LIUWENXIANG, 2f);
        validate(m);
        return m;
    }

    // ================================================================== ARENA

    private static MapDef duelPit() {
        MapDef m = new MapDef("arena_duel_pit", "决斗坑", Mode.ARENA);
        m.timeLimit = GameConfig.L4_ARENA_TIME_LIMIT;
        m.arenaRadius = ARENA_RADIUS;
        m.field = TerrainBuilder.fromArea(0, 0, ARENA_RADIUS, TerrainBuilder.Shape.BOWL,
                6f, 7f, TerrainType.SAND, 0.28f);
        areaSpawns(m, ARENA_SPAWNS, 0.78f);
        areaItems(m, ARENA_ITEM_SPAWNS, 0.50f, GameConfig.ITEM_DEFAULT_RESPAWN);
        hammerAt(m, 0f, 0f, 10f, 0.85f, 0f);
        crusher(m, 0f, 0f, 2.6f);
        m.bias(ItemType.PO_FANG, 2f).bias(ItemType.QIANXIN_WANKU, 2f);
        validate(m);
        return m;
    }

    private static MapDef pinball() {
        MapDef m = new MapDef("arena_pinball", "弹球台", Mode.ARENA);
        m.timeLimit = GameConfig.L4_ARENA_TIME_LIMIT;
        m.arenaRadius = ARENA_RADIUS;
        m.field = TerrainBuilder.fromArea(0, 0, ARENA_RADIUS, TerrainBuilder.Shape.FLAT,
                4f, 0f, TerrainType.ROCK, 0.6f);
        areaSpawns(m, ARENA_SPAWNS, 0.78f);
        areaItems(m, ARENA_ITEM_SPAWNS, 0.50f, GameConfig.ITEM_DEFAULT_RESPAWN);
        for (int i = 0; i < 8; i++) {
            double a = i / 8.0 * Math.PI * 2.0;
            float rr = 12f + (i % 2) * 9f;
            bouncer(m, (float) Math.cos(a) * rr, (float) Math.sin(a) * rr, 13f);
        }
        for (int i = 0; i < 4; i++) {
            double a = i / 4.0 * Math.PI * 2.0 + 0.4;
            boostAt(m, (float) Math.cos(a) * 26f, (float) Math.sin(a) * 26f,
                    -(float) Math.cos(a), -(float) Math.sin(a), 15f);
        }
        hammerAt(m, -18f, 0f, 8f, 1.1f, 0f);
        crusher(m, 0f, 0f, 2.4f);
        m.bias(ItemType.HAKIMI, 2.5f).bias(ItemType.ZUOWAN_NIDE, 2f);
        validate(m);
        return m;
    }

    private static MapDef lavaRing() {
        MapDef m = new MapDef("arena_lava_ring", "熔岩环", Mode.ARENA);
        m.timeLimit = GameConfig.L4_ARENA_TIME_LIMIT;
        m.arenaRadius = ARENA_RADIUS;
        m.field = TerrainBuilder.fromArea(0, 0, ARENA_RADIUS, TerrainBuilder.Shape.MESA,
                4f, 5f, TerrainType.ROCK, 0.35f);
        areaSpawns(m, ARENA_SPAWNS, 0.60f);
        areaItems(m, ARENA_ITEM_SPAWNS, 0.42f, GameConfig.ITEM_DEFAULT_RESPAWN);
        Zone lava = new Zone(Zone.Kind.LAVA);
        lava.ring(ARENA_RADIUS - 9f, ARENA_RADIUS + 4f);
        lava.at(0f, 10f, 0f);
        m.zones.add(lava);
        conveyor(m, -22f, -22f, 1f, 1f, 8f);
        conveyor(m, 22f, 22f, -1f, -1f, 8f);
        hammerAt(m, 0f, -16f, 8f, 1.0f, 0f);
        crusher(m, 0f, 0f, 2.4f);
        m.bias(ItemType.XIE_XIU, 2f).bias(ItemType.LIUWENXIANG, 2f);
        validate(m);
        return m;
    }

    // ================================================================== 构建辅助

    /** 完成一张 RACE 图：加起终点平台、生成地形、护栏、存档点、刷新点、出生点。 */
    private static void finishRace(MapDef m, Route route) {
        extendPads(route);
        for (int i = 0; i < route.nodeCount(); i++) {
            Route.Node n = route.node(i);
            n.halfWidth *= ROUTE_WIDTH_SCALE;
            n.shoulder += ROUTE_SHOULDER_BONUS;
        }
        route.build();
        route.scaleToLength(RACE_TARGET_LENGTH);
        m.route = route;
        m.field = TerrainBuilder.fromRoute(route);
        TerrainBuilder.buildWalls(route, m.walls);

        Vector3 p = new Vector3();
        // 终点：最后一个节点处，垂直于路径方向
        float yaw = route.yawAt(route.totalLength - 0.01f);
        route.sampleAt(route.totalLength - 0.01f, p);
        m.finishPos.set(p.x, p.y, p.z);
        m.finishDir.set(MathUtils.sin(yaw), 0f, MathUtils.cos(yaw));
        m.finishHalfWidth = route.node(route.nodeCount() - 1).halfWidth;

        // 存档点：按弧长均分
        for (int i = 0; i < RACE_CHECKPOINTS; i++) {
            float s = route.totalLength * (i + 1f) / (RACE_CHECKPOINTS + 1f);
            route.pointAt(s, 0f, p);
            float y = m.field.walkableAt(p.x, p.z) ? m.field.heightAt(p.x, p.z) : p.y;
            MapDef.Checkpoint cp = new MapDef.Checkpoint(p.x, y, p.z, i + 1);
            cp.arc = s;
            m.checkpoints.add(cp);
        }

        // 道具刷新点：均分，左右交替
        for (int i = 0; i < RACE_ITEM_SPAWNS; i++) {
            float frac = (i + 0.5f) / RACE_ITEM_SPAWNS;
            float s = route.totalLength * frac;
            float lat = (i % 2 == 0 ? 1f : -1f) * route.halfWidthAt(s) * 0.5f;
            route.pointAt(s, lat, p);
            float y = m.field.walkableAt(p.x, p.z) ? m.field.heightAt(p.x, p.z) : p.y;
            m.itemSpawns.add(new MapDef.ItemSpawn(p.x, y + 0.6f, p.z));
        }

        raceSpawns(m, route);
        m.minHeight = m.field.minHeight();
        m.maxHeight = m.field.maxHeight();
    }

    /** 起点前加 3 段宽阔平台，终点后加 1 段缓冲平台（避免出生/冲线落空）。 */
    private static void extendPads(Route route) {
        if (route.nodeCount() < 2) {
            return;
        }
        route.build();
        Route.Node first = route.node(0);
        Route.Node second = route.node(1);
        float dx = second.x - first.x;
        float dz = second.z - first.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        float ux = dx / len;
        float uz = dz / len;
        float y = first.y;
        float hw = first.halfWidth;
        float sh = first.shoulder;
        int type0 = first.type.ordinal();

        // 插到最前面的 3 段，最终顺序必须是 −36 / −24 / −12 / first（一路延伸出去）。
        //
        // ⚠️ 这里原来写的是 `for (int i = 3; i >= 1; i--) insert(0, ...)`，结果顺序变成
        // −12 / −24 / −36 / first —— 路径先往回走 36 米，再**猛地跳回**起点，凭空折返 32 米。
        // 后果不是"多走一点路"这么轻：
        //   · 折返段与后面的路在空间上完全重叠，`nearest` 会在两个弧长之间乱跳 → 偷鸡；
        //   · 出生点被摆到折返段上，脚下一会儿是起点高度、一会儿是中段高度 → 冻结/瞬移。
        // 实测（绿丘）：段长本该 12/12/12，实际是 10.4/10.4/**31.2**。
        // 生成顺序必须是"从远到近"依次**插到最前面**，这样后来插入的更近的节点才会排在后面。
        for (int i = 1; i <= 3; i++) {
            Route.Node pad = new Route.Node();
            pad.x = first.x - ux * 12f * i;
            pad.y = y;
            pad.z = first.z - uz * 12f * i;
            pad.halfWidth = 18f;
            pad.shoulder = 7f;
            pad.type = TerrainType.ALL[type0];
            route.nodes().insert(0, pad);
        }

        // 终点缓冲
        Route.Node last = route.node(route.nodeCount() - 1);
        Route.Node prev = route.node(route.nodeCount() - 2);
        float ex = last.x - prev.x;
        float ez = last.z - prev.z;
        float el = (float) Math.sqrt(ex * ex + ez * ez);
        ex /= el;
        ez /= el;
        for (int i = 1; i <= 2; i++) {
            Route.Node pad = new Route.Node();
            pad.x = last.x + ex * 15f * i;
            pad.y = last.y;
            pad.z = last.z + ez * 15f * i;
            pad.halfWidth = 18f;
            pad.shoulder = 7f;
            pad.type = last.type;
            route.nodes().add(pad);
        }
    }

    private static void raceSpawns(MapDef m, Route route) {
        Vector3 p0 = new Vector3();
        route.sampleAt(0.5f, p0);
        float yaw = route.yawAt(0.5f);
        float fx = MathUtils.sin(yaw);
        float fz = MathUtils.cos(yaw);
        float rx = MathUtils.cos(yaw);
        float rz = -MathUtils.sin(yaw);
        int cols = 6;
        float colGap = 2.8f;
        float rowGap = 3.4f;
        for (int i = 0; i < RACE_SPAWNS; i++) {
            int c = i % cols;
            int row = i / cols;
            float lox = (c - (cols - 1) * 0.5f) * colGap;
            float back = (row + 1) * rowGap;
            float px = p0.x + rx * lox - fx * back;
            float pz = p0.z + rz * lox - fz * back;
            float py = m.field.walkableAt(px, pz)
                    ? m.field.heightAt(px, pz) + GameConfig.LOG_RADIUS
                    : p0.y + GameConfig.LOG_RADIUS;
            m.spawns.add(new Vector3(px, py, pz));
        }
    }

    private static void areaSpawns(MapDef m, int count, float radiusRatio) {
        m.spawns.clear();
        float r = m.arenaRadius * radiusRatio;
        for (int i = 0; i < count; i++) {
            double a = i / (double) count * Math.PI * 2.0;
            float px = (float) Math.cos(a) * r;
            float pz = (float) Math.sin(a) * r;
            float py = m.field.walkableAt(px, pz)
                    ? m.field.heightAt(px, pz) + GameConfig.LOG_RADIUS
                    : GameConfig.LOG_RADIUS;
            m.spawns.add(new Vector3(px, py, pz));
        }
    }

    private static void areaItems(MapDef m, int count, float radiusRatio, float respawn) {
        m.itemSpawns.clear();
        float r = m.arenaRadius * radiusRatio;
        for (int i = 0; i < count; i++) {
            double a = i / (double) count * Math.PI * 2.0 + 0.31;
            float rr = r * (i % 2 == 0 ? 1f : 0.62f);
            float px = (float) Math.cos(a) * rr;
            float pz = (float) Math.sin(a) * rr;
            float py = m.field.walkableAt(px, pz) ? m.field.heightAt(px, pz) : 0f;
            MapDef.ItemSpawn s = new MapDef.ItemSpawn(px, py + 0.6f, pz);
            s.respawnTime = respawn;
            m.itemSpawns.add(s);
        }
    }

    // ---- RACE 机关放置（按弧长分数 + 横向偏移） ----

    private static final Vector3 TMP = new Vector3();

    private static float gy(MapDef m, float x, float z, float fallback) {
        return m.field.walkableAt(x, z) ? m.field.heightAt(x, z) : fallback;
    }

    private static void hammer(MapDef m, float frac, float lat, float len, float speed, float phase) {
        m.route.pointAt(m.route.totalLength * frac, lat, TMP);
        hammerAt(m, TMP.x, TMP.z, len, speed, phase);
    }

    private static void hammerAt(MapDef m, float x, float z, float len, float speed, float phase) {
        Obstacle o = new Obstacle(Obstacle.Kind.HAMMER)
                .at(x, gy(m, x, z, 0f) + 0.9f, z)
                .radius(len).speed(speed).thickness(0.34f).height(1.0f).phase(phase);
        m.obstacles.add(o);
    }

    private static void saw(MapDef m, float frac, float lat, float travel, float speed, float dirSign) {
        m.route.pointAt(m.route.totalLength * frac, lat, TMP);
        float yaw = m.route.yawAt(m.route.totalLength * frac);
        sawAt(m, TMP.x, TMP.z, travel, speed,
                MathUtils.cos(yaw) * dirSign, -MathUtils.sin(yaw) * dirSign);
    }

    private static void sawAt(MapDef m, float x, float z, float travel, float speed,
                              float dirX, float dirZ) {
        Obstacle o = new Obstacle(Obstacle.Kind.SAW)
                .at(x, gy(m, x, z, 0f) + 0.5f, z)
                .radius(1.1f).travel(travel).speed(speed);
        o.dir(dirX, dirZ);
        m.obstacles.add(o);
    }

    private static void piston(MapDef m, float frac, float lat, float dirX, float dirZ) {
        m.route.pointAt(m.route.totalLength * frac, lat, TMP);
        pistonAt(m, TMP.x, TMP.z, frac, dirX, dirZ);
    }

    private static void pistonAt(MapDef m, float x, float z, float frac,
                                 float dirX, float dirZ) {
        Obstacle o = new Obstacle(Obstacle.Kind.PISTON)
                .at(x, gy(m, x, z, 0f) + 1.0f, z)
                .radius(2.4f).travel(3.2f).period(2.6f);
        if (dirX != 0f || dirZ != 0f) {
            o.dir(dirX, dirZ);
        } else if (m.route != null) {
            float yaw = m.route.yawAt(m.route.totalLength * frac);
            o.dir(MathUtils.cos(yaw), -MathUtils.sin(yaw));
        }
        m.obstacles.add(o);
    }

    private static void crusher(MapDef m, float x, float z, float period) {
        Obstacle o = new Obstacle(Obstacle.Kind.CRUSHER)
                .at(x, gy(m, x, z, 0f) + 3.5f, z)
                .radius(2.6f).period(period).travel(3.6f);
        m.obstacles.add(o);
    }

    private static void pillar(MapDef m, float x, float z, float radius, float height) {
        float y = gy(m, x, z, 0f);
        Obstacle o = new Obstacle(Obstacle.Kind.PILLAR)
                .at(x, y + height * 0.5f, z).radius(radius).height(height);
        m.obstacles.add(o);
        m.walls.add(new StaticBody(x, y + height * 0.5f, z, radius, height * 0.5f, radius, 0f));
    }

    private static void spinner(MapDef m, float x, float z, float radius, float speed) {
        Obstacle o = new Obstacle(Obstacle.Kind.SPINNER)
                .at(x, gy(m, x, z, 0f) + 0.25f, z).radius(radius).speed(speed);
        m.obstacles.add(o);
    }

    private static void fan(MapDef m, float frac, float lat, float dirX, float dirZ) {
        m.route.pointAt(m.route.totalLength * frac, lat, TMP);
        Obstacle o = new Obstacle(Obstacle.Kind.FAN)
                .at(TMP.x, gy(m, TMP.x, TMP.z, TMP.y) + 2.2f, TMP.z)
                .radius(7f).dir(dirX, dirZ).force(11f);
        m.obstacles.add(o);
    }

    // ---- 区域类（按世界坐标） ----

    private static void bouncer(MapDef m, float x, float z, float power) {
        Zone z2 = new Zone(Zone.Kind.BOUNCER);
        z2.at(x, gy(m, x, z, 0f), z).circle(2.6f).dir(0f, 1f, power);
        m.zones.add(z2);
    }

    private static void conveyor(MapDef m, float x, float z, float dx, float dz, float speed) {
        Zone z2 = new Zone(Zone.Kind.CONVEYOR);
        z2.at(x, gy(m, x, z, 0f), z).size(4.5f, 4.5f).dir(dx, dz, speed);
        m.zones.add(z2);
    }

    private static void conveyorOnRoute(MapDef m, float frac, float lat, float dx, float dz, float speed) {
        m.route.pointAt(m.route.totalLength * frac, lat, TMP);
        conveyor(m, TMP.x, TMP.z, dx, dz, speed);
    }

    /** 按弧长分数放置的加速带。 */
    private static void boost(MapDef m, float frac, float lat, float dx, float dz, float force) {
        m.route.pointAt(m.route.totalLength * frac, lat, TMP);
        boostAt(m, TMP.x, TMP.z, dx, dz, force);
    }

    /** 按世界坐标放置的加速带。 */
    private static void boostAt(MapDef m, float x, float z, float dx, float dz, float force) {
        Zone z2 = new Zone(Zone.Kind.BOOST);
        z2.at(x, gy(m, x, z, 0f), z).size(4f, 4f).dir(dx, dz, force);
        m.zones.add(z2);
    }

    private static void wallRange(Route r, int fromSeg, int toSeg, boolean left, boolean right) {
        for (int i = fromSeg; i <= toSeg && i < r.segmentCount(); i++) {
            r.node(i).wallLeft = left;
            r.node(i).wallRight = right;
            r.node(i + 1).wallLeft = left;
            r.node(i + 1).wallRight = right;
        }
    }

    /** 注册时的硬校验（docs/02 §8）。任何一项不通过都直接抛异常，禁止静默降级。 */
    private static void validate(MapDef m) {
        MapValidator.validate(m);
    }
}
