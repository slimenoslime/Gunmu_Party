package top.gunmu.party.console;

import com.badlogic.gdx.utils.Array;

import top.gunmu.party.ai.BotBrain;
import top.gunmu.party.core.Roll;
import top.gunmu.party.GameConfig;
import top.gunmu.party.match.LevelDirector;

/**
 * 无渲染地跑一整局比赛，并统计关键事件——用来验证"规则真的在发生"，
 * 而不是只验证"没有抛异常"。
 */
public final class ConsoleProbe {

    public int ticks;
    public int levelsSeen = 1;
    public int itemUses;
    public int ultUses;
    public int deathsObserved;
    public int checkpointHits;
    public int pickups;
    public int enemiesMoving;
    public int jumpCount;
    public int dashCount;
    /** 本局累计挥击次数（只在决赛关会发生）。 */
    public int attackCount;
    /** 起点屏障生效的帧数（应当约等于 8 秒 × 60）。 */
    public int barrierTicks;
    /** 屏障生效期间有滚木跑到起点区域外的次数（必须为 0）。 */
    /** 每个关卡被进入的次数。每关只有一张图，所以应当恒为 1。 */
    public final int[] levelVisits = new int[6];

    public int barrierEscapes;
    /** 屏障生效期间有人死亡的次数（必须为 0：起点区域内不该有深渊/机关）。 */
    public int barrierDeaths;
    public String finalPhase;
    public int finalLevel;

    /** 每个子图的模式与地图名（按进入顺序）。 */
    public final com.badlogic.gdx.utils.Array<top.gunmu.party.core.Mode> stageModes
            = new com.badlogic.gdx.utils.Array<>();
    public final com.badlogic.gdx.utils.Array<String> stageMaps
            = new com.badlogic.gdx.utils.Array<>();

    private boolean[] wasAlive;
    private int[] lastCheckpoint;
    private boolean[] hadItem;
    private float[] lastUltCd;
    private boolean[] wasParked;

    private final BotBrain playerBrain = new BotBrain();
    private final java.util.Random aiRng = new java.util.Random(1234L);

    private int lastStageIndex = -1;
    private int lastStageLevel = -1;

    public void run(long seed, int ticksPerStage, int maxTicks) {
        // 每关的帧预算 = 正常回合时长 + 起点屏障时长。
        //
        // ⚠️ 起点屏障期间**比赛时钟不推进**，所以屏障吃掉的那 8 秒不属于回合时间。
        // 如果这里不补回来，探针就是在"少 8 秒"的前提下测平衡：
        // 曾经因此把平均进度从 611m 误判成 554m、死亡从 0 误判成 9，
        // 看起来像玩法被改坏了，其实只是测量口径变了。
        final int budget = ticksPerStage
                + Math.round(GameConfig.START_BARRIER_TIME / GameConfig.FIXED_DT);
        LevelDirector dir = new LevelDirector();
        dir.newTournament(seed, 0);

        int n = dir.rolls().size;
        wasAlive = new boolean[n];
        lastCheckpoint = new int[n];
        hadItem = new boolean[n];
        lastUltCd = new float[n];
        wasParked = new boolean[n];

        int sinceStageStart = 0;
        int lastLevel = dir.state.levelIndex;
        // 用字段而不是局部变量：换关卡时 stageIndex 会回到 0，
        // 只盯 stageIndex 是发现不了"换关了"的（必须同时比 levelIndex）。
        lastStageIndex = -1;
        lastStageLevel = -1;
        boolean forced = false;

        while (ticks < maxTicks) {
            if (dir.phase == LevelDirector.Phase.TOURNAMENT_DONE) {
                break;
            }
            if (dir.phase == LevelDirector.Phase.LEVEL_DONE) {
                dumpStage(dir);
                System.out.println("  [关卡结算] 第 " + dir.state.levelIndex + " 关 · "
                        + dir.state.resultTitle + " · 晋级 "
                        + dir.state.qualified.size + " 人 · 玩家"
                        + (dir.state.playerEliminated ? "被淘汰" : "晋级"));
                for (String line : dir.state.resultLines) {
                    System.out.println("             " + line);
                }
                dir.advanceToNextLevel();
                sinceStageStart = 0;
                forced = false;
                continue;
            }

            if (dir.state.stageIndex != lastStageIndex
                    || dir.state.levelIndex != lastStageLevel) {
                lastStageIndex = dir.state.stageIndex;
                lastStageLevel = dir.state.levelIndex;
                forced = false;
                // 换图必须重新计时，否则新图会被上一张攒下的帧数立刻强制超时
                sinceStageStart = 0;
                // 记录每张图用的是哪个模式/地图，供关卡结构断言使用
                stageModes.add(dir.state.mode);
                stageMaps.add(dir.map().name);
                if (dir.state.levelIndex >= 1 && dir.state.levelIndex < levelVisits.length) {
                    levelVisits[dir.state.levelIndex]++;
                }
                // 一关只有一张图了，不要再打印 "1/1" 那种子图编号
                System.out.println("  [进入关卡] 第 " + dir.state.levelIndex + " 关  "
                        + dir.state.mode.cn + " · " + dir.state.mapName
                        + " · 场上 " + (int) dir.state.aliveCount() + " 根");
            }

            // 让流程不要卡在超时里：到达预设帧数就把时限一次性压到快到期
            if (sinceStageStart >= budget && !forced
                    && dir.phase == LevelDirector.Phase.PLAYING) {
                forced = true;
                dir.state.timeLeft = 0.35f;
            }

            // 无头模式下用同一套 AI 驱动"玩家"，否则玩家原地不动必然被淘汰，
            // 也就测不到后面的关卡
            Roll p = dir.state.player;
            if (p != null && dir.map() != null) {
                // 这个"玩家"是替身，目的是把 5 关流程都跑到。
                // 用最高难度打，才不会因为运气差在第 1 关就被淘汰 ——
                // 那样后面的关卡与地图就一次都没被测到（而且是间歇性复现，最难查的一类）。
                p.aiDifficulty = 2;
                playerBrain.think(p, dir.map(), dir.rolls(), dir.items,
                        dir.ultimates, dir.attacks, aiRng, 1f / 60f);
            }

            dir.update(1f / 60f);
            ticks++;
            sinceStageStart++;
            observe(dir);

            if (dir.state.levelIndex > lastLevel) {
                lastLevel = dir.state.levelIndex;
                levelsSeen = Math.max(levelsSeen, lastLevel);
            }
        }

        finalPhase = dir.phase.name();
        finalLevel = dir.state.levelIndex;
        System.out.println("  [结束] 阶段 " + finalPhase + " · 第 " + finalLevel + " 关 · "
                + "冠军 " + (dir.state.playerChampion ? "玩家" : "AI")
                + " · 累计帧 " + ticks);
    }

    /** 打印这一关的分布，用来判断 AI 是不是真的在跑。 */
    private void dumpStage(LevelDirector dir) {
        Array<Roll> rolls = dir.rolls();
        int moved50 = 0;
        int moved200 = 0;
        int zero = 0;
        int parked = 0;
        int out = 0;
        float best = 0f;
        float sum = 0f;
        int counted = 0;
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (r.parked) {
                parked++;
                continue;
            }
            if (r.stageOut || r.eliminated) {
                out++;
                continue;
            }
            counted++;
            sum += r.progress;
            best = Math.max(best, r.progress);
            if (r.progress > 50f) {
                moved50++;
            }
            if (r.progress > 200f) {
                moved200++;
            }
            if (r.progress < 1f) {
                zero++;
            }
            if (i < 6) {
                sample.append(String.format(java.util.Locale.ROOT, "%s:%.0f(%d死) ",
                        r.name, r.progress, r.deaths));
            }
        }
        System.out.printf(java.util.Locale.ROOT,
                "  [诊断] %s · 场上%2d 停摆%2d 进度>50:%2d >200:%2d 均%.0fm 最好%.0fm"
                        + "（本关死亡%d · 历时%.1fs · 结束原因：%s）%n",
                dir.state.mapName, counted, zero, moved50, moved200,
                counted > 0 ? sum / counted : 0f, best, dir.state.stageDeaths,
                dir.state.stageElapsed,
                dir.state.bannerText == null || dir.state.bannerText.isEmpty()
                        ? "?" : dir.state.bannerText);
        System.out.println("         样本 " + sample);
        StringBuilder causes = new StringBuilder();
        for (int i = 0; i < dir.deathsByCause.length; i++) {
            if (dir.deathsByCause[i] > 0) {
                causes.append(top.gunmu.party.core.DamageSource.values()[i].cn)
                        .append(':').append(dir.deathsByCause[i]).append("  ");
            }
        }
        if (causes.length() > 0) {
            System.out.println("         死因累计 " + causes);
        }
        if (parked > 0 || out > 0) {
            System.out.println("         锁定 " + parked + " 人，出局 " + out + " 人");
        }
    }

    private void observe(LevelDirector dir) {
        // 跳跃是累计量，直接取快照
        jumpCount = dir.jumpCount();
        dashCount = dir.dashCount();
        attackCount = dir.attackCount();

        // 起点屏障：生效帧数 + 越界次数。越界只要发生一次就说明屏障没拦住。
        if (dir.isStartBarrierActive()) {
            barrierTicks++;
            top.gunmu.party.map.MapDef bm = dir.map();
            if (bm != null) {
                Array<Roll> all = dir.rolls();
                for (int i = 0; i < all.size; i++) {
                    Roll r = all.get(i);
                    if (!r.onField()) {
                        continue;
                    }
                    if (!bm.insideStartZone(r.pos.x, r.pos.z)) {
                        barrierEscapes++;
                    }
                }
            }
        }
        Array<Roll> rolls = dir.rolls();
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            int id = r.id;
            if (id >= wasAlive.length) {
                continue;
            }
            boolean alive = r.alive;
            if (wasAlive[id] && !alive) {
                deathsObserved++;
                if (dir.isStartBarrierActive()) {
                    // 起点区域内不该有深渊、也不该有人被挤死
                    barrierDeaths++;
                }
            }
            wasAlive[id] = alive;

            if (r.checkpointIndex > lastCheckpoint[id]) {
                checkpointHits++;
                lastCheckpoint[id] = r.checkpointIndex;
            }

            boolean hasItem = r.carried != null;
            if (hasItem && !hadItem[id]) {
                pickups++;
                hadItem[id] = true;
            } else if (!hasItem && hadItem[id]) {
                itemUses++;
                hadItem[id] = false;
            }

            if (r.ultimateCd > lastUltCd[id] + 0.5f) {
                ultUses++;
            }
            lastUltCd[id] = r.ultimateCd;

            if (!r.player && !r.parked && !r.eliminated && r.speed() > 3f) {
                enemiesMoving++;
            }
        }
    }
}
