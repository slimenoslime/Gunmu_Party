package top.gunmu.party.map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectFloatMap;

import top.gunmu.party.core.Mode;
import top.gunmu.party.items.ItemType;
import top.gunmu.party.physics.HeightField;
import top.gunmu.party.physics.StaticBody;

/**
 * 一张地图的完整定义 + 运行时状态。
 *
 * <p>每关开始时**重新构建**（{@code MapRegistry.create(id)}），因此刷新点、机关相位、
 * 地形都是全新的——这保证了同一份随机种子下每关结果可复现。
 */
public final class MapDef {

    /** 存档点。个人进度单调前进（docs/01 §4）。 */
    public static final class Checkpoint {
        public final Vector3 pos = new Vector3();
        public int index;
        public float radius = 2.5f;
        /**
         * 该存档点对应的**赛程弧长**（米）。
         *
         * <p>判定用的是它，不是 {@link #pos} 的距离：存档点是"过了这一段就存"，
         * 不让玩家有机会从旁边擦过去、没碰到圆盘就没存上。
         * {@code pos} 只用于复活的落点与画面标记。
         */
        public float arc;

        public Checkpoint(float x, float y, float z, int index) {
            pos.set(x, y, z);
            this.index = index;
        }
    }

    /** 道具刷新点。位置固定，刷出什么是随机的（docs/03 §2）。 */
    public static final class ItemSpawn {
        public final Vector3 pos = new Vector3();
        public float respawnTime = 12f;
        public ItemType current;
        public boolean ready = true;
        public float timer;
        public int pity;

        public ItemSpawn(float x, float y, float z) {
            pos.set(x, y, z);
        }
    }

    public String id;
    public String name;
    public Mode mode = Mode.RACE;
    public float timeLimit = 300f;

    public Route route;
    public HeightField field;

    public final Array<StaticBody> walls = new Array<>();
    public final Array<Zone> zones = new Array<>();
    public final Array<Obstacle> obstacles = new Array<>();
    public final Array<ItemSpawn> itemSpawns = new Array<>();
    public final Array<Checkpoint> checkpoints = new Array<>();
    public final Array<Vector3> spawns = new Array<>();

    /** 起点区域中心（所有出生点的形心）。开局屏障以此为圆心。 */
    public final Vector3 startCenter = new Vector3();
    /** 起点区域半径：覆盖全部出生点后再加上余量。0 表示没有起点屏障。 */
    public float startRadius;

    // ---- RACE 终点 ----
    public final Vector3 finishPos = new Vector3();
    public final Vector3 finishDir = new Vector3(0f, 0f, 1f);
    public float finishHalfWidth = 8f;

    // ---- 环境危害 ----
    /** 涨水速度（m/s），0 表示无。 */
    public float waterRise;
    /** 毒雾环收缩速度（m/s），0 表示无。 */
    public float poisonShrink;
    public float poisonStartRadius;
    public final Vector3 poisonCenter = new Vector3();

    public float arenaRadius;
    public float minHeight = -20f;
    public float maxHeight = 200f;

    /** 该图的道具权重偏置（相对全局权重的倍率）。 */
    public final ObjectFloatMap<ItemType> weightBias = new ObjectFloatMap<>();

    public MapDef(String id, String name, Mode mode) {
        this.id = id;
        this.name = name;
        this.mode = mode;
    }

    public MapDef bias(ItemType t, float mul) {
        weightBias.put(t, mul);
        return this;
    }

    /**
     * 道具在本图中的刷新权重。**权重为 0 表示不会出现**。
     *
     * <p>竞速图会剔除"战斗专用"道具（{@link ItemType#combatOnly()}）：免伤/反伤/纯输出/
     * 反隐这些东西在竞速图里没有适用对象，刷出来等于空道具
     * （玩家报过「竞速关会刷出防御类的道具，这些只在生存图有用」）。
     *
     * <p>过滤放在这里而不是各调用点，是因为 {@code rollItem} / {@code rollRarePlus} /
     * 保底逻辑都走它 —— 只改一处就不会漏。
     */
    public float weightOf(ItemType t) {
        if (mode == Mode.RACE && t.combatOnly()) {
            return 0f;
        }
        float w = t.weight;
        if (weightBias.containsKey(t)) {
            w *= weightBias.get(t, 1f);
        }
        return w;
    }

    /** 全部刷新点复位并各刷 1 个道具（关卡开始时调用）。 */
    /**
     * 由出生点推导起点区域：中心取形心，半径取"最远出生点到形心"再加 {@code margin}。
     *
     * <p>用推导而不是手写坐标，是因为三种模式的出生点布局完全不同
     * （竞速是 6 列 × 5 排的方阵、生存/竞技是环形），手写必然漂移。
     * 推导出来天然满足"所有出生点都在屏障内"，这一点由
     * {@code MapValidator.validateStartZone} 硬校验兜住。
     */
    public void computeStartZone(float margin, float minRadius) {
        if (spawns.size == 0) {
            startCenter.setZero();
            startRadius = 0f;
            return;
        }
        float cx = 0f;
        float cy = 0f;
        float cz = 0f;
        for (int i = 0; i < spawns.size; i++) {
            Vector3 v = spawns.get(i);
            cx += v.x;
            cy += v.y;
            cz += v.z;
        }
        cx /= spawns.size;
        cy /= spawns.size;
        cz /= spawns.size;
        startCenter.set(cx, cy, cz);

        float maxD = 0f;
        for (int i = 0; i < spawns.size; i++) {
            Vector3 v = spawns.get(i);
            float dx = v.x - cx;
            float dz = v.z - cz;
            maxD = Math.max(maxD, (float) Math.sqrt(dx * dx + dz * dz));
        }
        startRadius = Math.max(minRadius, maxD + margin);
    }

    /**
     * 是否有可用的赛道路径。
     *
     * <p>⚠️ <b>只有 RACE 图有 {@code route}</b>；生存图与竞技图是找路/环形场地，
     * 根本没有折线路径。全仓任何要用 route 的地方都必须先过这里 ——
     * 漏一处的后果是**进程直接崩**（曾经在生存图上崩过一次：
     * 「Cannot read field "totalLength" because route is null」）。
     */
    public boolean hasRoute() {
        return route != null && route.totalLength > 0f;
    }

    /** 赛程总长（米）。没有路径时返回 0，绝不会 NPE。 */
    public float routeLength() {
        return route == null ? 0f : route.totalLength;
    }

    /** 某点是否在起点区域内（水平投影）。 */
    public boolean insideStartZone(float x, float z) {
        if (startRadius <= 0f) {
            return true;
        }
        float dx = x - startCenter.x;
        float dz = z - startCenter.z;
        return dx * dx + dz * dz <= startRadius * startRadius;
    }

    public void resetItemSpawns(java.util.Random rng) {
        for (int i = 0; i < itemSpawns.size; i++) {
            ItemSpawn s = itemSpawns.get(i);
            s.ready = true;
            s.timer = 0f;
            s.pity = 0;
            s.current = rollItem(rng, s);
        }
    }

    /** 单个刷新点重刷。 */
    public ItemType refreshSpawn(ItemSpawn spawn, java.util.Random rng) {
        spawn.current = rollItem(rng, spawn);
        spawn.ready = true;
        spawn.timer = 0f;
        return spawn.current;
    }

    private ItemType rollItem(java.util.Random rng, ItemSpawn spawn) {
        float total = 0f;
        for (int i = 0; i < ItemType.ALL.length; i++) {
            float w = weightOf(ItemType.ALL[i]);
            if (w > 0f) {
                total += w;
            }
        }
        if (total <= 0f) {
            return firstAllowedItem();
        }
        float pick = rng.nextFloat() * total;
        ItemType chosen = null;
        for (int i = 0; i < ItemType.ALL.length; i++) {
            ItemType t = ItemType.ALL[i];
            float w = weightOf(t);
            if (w <= 0f) {
                // 权重为 0 的道具（例如竞速图里的战斗专用道具）直接跳过，
                // 否则 pick 恰好归零时会被抽到
                continue;
            }
            pick -= w;
            if (pick <= 0f) {
                chosen = t;
                break;
            }
        }
        if (chosen == null) {
            // 兜底：池子非空时不该走到这里
            chosen = firstAllowedItem();
        }
        // 保底：连续 3 次普通 -> 强制稀有以上（docs/03 §2）
        if (chosen.rarity == top.gunmu.party.items.Rarity.COMMON) {
            spawn.pity++;
            if (spawn.pity >= 3) {
                chosen = rollRarePlus(rng);
                spawn.pity = 0;
            }
        } else {
            spawn.pity = 0;
        }
        return chosen;
    }

    private ItemType rollRarePlus(java.util.Random rng) {
        float total = 0f;
        for (int i = 0; i < ItemType.ALL.length; i++) {
            if (ItemType.ALL[i].rarity != top.gunmu.party.items.Rarity.COMMON) {
                float w = weightOf(ItemType.ALL[i]);
                if (w > 0f) {
                    total += w;
                }
            }
        }
        if (total <= 0f) {
            return firstAllowedItem();
        }
        float pick = rng.nextFloat() * total;
        for (int i = 0; i < ItemType.ALL.length; i++) {
            ItemType t = ItemType.ALL[i];
            if (t.rarity == top.gunmu.party.items.Rarity.COMMON) {
                continue;
            }
            float w = weightOf(t);
            if (w <= 0f) {
                continue;
            }
            pick -= w;
            if (pick <= 0f) {
                return t;
            }
        }
        // 兜底也必须是**本模式允许**的道具
        return firstAllowedItem();
    }

    /** 本模式下第一个权重 > 0 的道具，用作一切兜底。 */
    private ItemType firstAllowedItem() {
        for (int i = 0; i < ItemType.ALL.length; i++) {
            if (weightOf(ItemType.ALL[i]) > 0f) {
                return ItemType.ALL[i];
            }
        }
        return ItemType.ALL[0];
    }
}
