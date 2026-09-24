package top.gunmu.party.tools;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectSet;

import top.gunmu.party.match.LevelDirector;

/**
 * 选图自检：<b>不同的一局必须抽到不同的图</b>，同一局之内也不该重复。
 *
 * <p>玩家反馈过"每个关卡的地图都是固定的"。原因有两个可能：
 * 一是启动时把随机种子写死（用的是 {@code -Dgunmu.seed}，正常玩不该带），
 * 二是选图逻辑真的退化了。这个自检覆盖第二种。
 */
public final class MapPickTest {

    private static final int SEEDS = 48;

    private MapPickTest() {
    }

    public static int run() {
        System.out.println("---- 选图自检 ----");
        int bad = 0;

        ObjectSet<String> firstLevelMaps = new ObjectSet<>();
        ObjectSet<String> allFirstStageMaps = new ObjectSet<>();
        String example = null;

        for (long seed = 1; seed <= SEEDS; seed++) {
            LevelDirector d = new LevelDirector();
            try {
                d.newTournament(seed, 0);
            } catch (Throwable t) {
                System.out.println("  [FAIL] 种子 " + seed + " 建局失败: " + t);
                bad++;
                continue;
            }
            Array<String> ids = d.plannedMapIds();
            if (ids.size == 0) {
                System.out.println("  [FAIL] 种子 " + seed + " 没有排定任何子图");
                bad++;
                continue;
            }
            String first = ids.get(0);
            firstLevelMaps.add(first);
            for (int i = 0; i < ids.size; i++) {
                allFirstStageMaps.add(ids.get(i));
            }
            if (example == null) {
                example = "种子 1 的第 1 关 = " + first;
            }
        }

        System.out.println("  " + SEEDS + " 个种子共抽到 " + firstLevelMaps.size
                + " 张不同的第 1 关地图（竞速池共 "
                + top.gunmu.party.map.MapRegistry.RACE_IDS.length + " 张）");
        System.out.println("  出现过 " + allFirstStageMaps.size + " 张不同的图；" + example);

        if (firstLevelMaps.size < 2) {
            System.out.println("  [FAIL] 所有种子的第 1 关都是同一张图 —— 选图是固定的");
            bad++;
        }
        if (allFirstStageMaps.size < 3) {
            System.out.println("  [FAIL] " + SEEDS + " 局下来只用到 "
                    + allFirstStageMaps.size + " 张图，多样性不足");
            bad++;
        }

        System.out.println(bad == 0 ? "  选图多样性正常" : "  选图自检失败 " + bad + " 项");
        return bad;
    }
}
