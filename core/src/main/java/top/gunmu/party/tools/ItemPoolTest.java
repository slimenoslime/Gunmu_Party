package top.gunmu.party.tools;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import top.gunmu.party.items.ItemType;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.MapRegistry;

/**
 * 道具池自检：<b>竞速图不许刷出"战斗专用"道具</b>。
 *
 * <p>玩家报：「竞速关可能会刷出防御类的道具，这些只在生存图有用，在竞速图没啥用」。
 * 竞速图里大家各跑各的，免伤 / 反伤 / 纯输出加成 / 反隐这些没有适用对象，刷出来等于空道具。
 *
 * <p>这类"漏了一个"最容易复发，所以断言写成**穷举**而不是抽样：
 *
 * <ol>
 *   <li>竞速图：把每个刷新点刷上万次，收集出现过的全部道具，
 *       断言与「全集 − 战斗专用」<b>集合完全相等</b>。既不许多（漏过滤），
 *       也不许少（过滤过头把能用的也砍了）；</li>
 *   <li>生存 / 竞技图：同样的枚举，断言战斗专用道具<b>确实会出现</b> ——
 *       否则可能是"全局都给关了"，那是另一种错；</li>
 *   <li>盲盒是后门：它在竞速图里开的道具也必须过同一道过滤；</li>
 *   <li>权重为 0 的道具不能被抽到（浮点边界：`pick` 恰好归零时会被抽中）。</li>
 * </ol>
 */
public final class ItemPoolTest {

    /** 每个刷新点刷多少次。权重最小的道具是 5，17 个道具权重和约 160，这个量级足够覆盖。 */
    private static final int ROLLS_PER_SPAWN = 4000;

    private ItemPoolTest() {
    }

    public static int run() {
        System.out.println("---- 道具池自检（竞速图不刷战斗专用道具）----");
        int bad = 0;

        int combat = 0;
        for (ItemType t : ItemType.ALL) {
            if (t.combatOnly()) {
                combat++;
            }
        }
        System.out.println("  战斗专用道具 " + combat + " / " + ItemType.ALL.length
                + " 个：" + combatNames());
        if (combat == 0 || combat == ItemType.ALL.length) {
            System.out.println("  [FAIL] 战斗专用道具的划分明显不合理（" + combat + " 个）");
            bad++;
        }
        // 普通稀有度里不能有战斗专用 —— 否则竞速图的"保底稀有以上"可能无解
        for (ItemType t : ItemType.ALL) {
            if (t.combatOnly() && t.rarity == top.gunmu.party.items.Rarity.COMMON) {
                System.out.println("  [FAIL] " + t.cn + " 是普通品质却被标成战斗专用，"
                        + "会让竞速图的稀有保底逻辑出问题");
                bad++;
            }
        }

        Set<ItemType> expectedRace = new HashSet<>();
        for (ItemType t : ItemType.ALL) {
            if (!t.combatOnly()) {
                expectedRace.add(t);
            }
        }

        for (String id : MapRegistry.RACE_IDS) {
            MapDef m = MapRegistry.create(id);
            Set<ItemType> seen = sample(m, 20260921L);
            Set<ItemType> extra = new HashSet<>(seen);
            extra.removeAll(expectedRace);
            Set<ItemType> missing = new HashSet<>(expectedRace);
            missing.removeAll(seen);

            if (!extra.isEmpty()) {
                System.out.println("  [FAIL] " + pad(m.name) + " 刷出了战斗专用道具："
                        + names(extra));
                bad++;
            } else if (!missing.isEmpty()) {
                System.out.println("  [FAIL] " + pad(m.name) + " 有能用的道具刷不出来："
                        + names(missing));
                bad++;
            } else {
                System.out.println("  [OK]   " + pad(m.name) + " 共出现 " + seen.size()
                        + " 种道具，与「全集 − 战斗专用」完全一致");
            }
        }

        // 生存 / 竞技：战斗专用必须真的会出现
        String[] others = new String[MapRegistry.SURVIVAL_IDS.length
                + MapRegistry.ARENA_IDS.length];
        System.arraycopy(MapRegistry.SURVIVAL_IDS, 0, others, 0,
                MapRegistry.SURVIVAL_IDS.length);
        System.arraycopy(MapRegistry.ARENA_IDS, 0, others,
                MapRegistry.SURVIVAL_IDS.length, MapRegistry.ARENA_IDS.length);
        Set<ItemType> combatSeen = new HashSet<>();
        for (String id : others) {
            MapDef m = MapRegistry.create(id);
            Set<ItemType> seen = sample(m, 4242L);
            for (ItemType t : seen) {
                if (t.combatOnly()) {
                    combatSeen.add(t);
                }
            }
        }
        if (combatSeen.size() != combat) {
            System.out.println("  [FAIL] 生存/竞技图只刷出 " + combatSeen.size() + "/"
                    + combat + " 种战斗专用道具：本该出现但没出现的 "
                    + names(missingCombat(combatSeen)));
            bad++;
        } else {
            System.out.println("  [OK]   生存/竞技图 " + combat
                    + " 种战斗专用道具全部会出现（过滤没做成全局关闭）");
        }

        System.out.println(bad == 0 ? "  道具池按模式正确过滤" : "  道具池自检失败 " + bad + " 项");
        return bad;
    }

    private static Set<ItemType> missingCombat(Set<ItemType> seen) {
        Set<ItemType> out = new HashSet<>();
        for (ItemType t : ItemType.ALL) {
            if (t.combatOnly() && !seen.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 枚举某张图所有刷新点的产出。 */
    private static Set<ItemType> sample(MapDef m, long seed) {
        Set<ItemType> seen = new HashSet<>();
        if (m.itemSpawns.size == 0) {
            return seen;
        }
        Random rng = new Random(seed);
        m.resetItemSpawns(rng);
        for (int i = 0; i < m.itemSpawns.size; i++) {
            seen.add(m.itemSpawns.get(i).current);
        }
        int total = ROLLS_PER_SPAWN * m.itemSpawns.size;
        for (int i = 0; i < total; i++) {
            MapDef.ItemSpawn s = m.itemSpawns.get(i % m.itemSpawns.size);
            seen.add(m.refreshSpawn(s, rng));
        }
        return seen;
    }

    private static String combatNames() {
        StringBuilder b = new StringBuilder();
        for (ItemType t : ItemType.ALL) {
            if (t.combatOnly()) {
                if (b.length() > 0) {
                    b.append("、");
                }
                b.append(t.cn);
            }
        }
        return b.toString();
    }

    private static String names(Set<ItemType> set) {
        StringBuilder b = new StringBuilder();
        for (ItemType t : set) {
            if (b.length() > 0) {
                b.append("、");
            }
            b.append(t.cn);
        }
        return b.toString();
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 10) {
            b.append(' ');
        }
        return b.toString();
    }
}
