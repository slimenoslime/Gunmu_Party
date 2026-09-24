package top.gunmu.party.match;

import com.badlogic.gdx.utils.Array;

import top.gunmu.party.core.Mode;
import top.gunmu.party.core.Roll;

/**
 * 一局比赛的可变状态。渲染层只从这里读取，不反向写入。
 */
public final class MatchState {

    /** 当前第几关（1..5）。 */
    public int levelIndex = 1;
    /** 本关第几个子图（从 0 开始）。 */
    public int stageIndex;
    public int stageCount = 1;
    public String stageLabel = "";
    public String mapName = "";
    public Mode mode = Mode.RACE;

    public float elapsed;
    public float timeLeft;
    public float stageElapsed;

    /**
     * 本次子图的"比赛时钟"：从**起点屏障消失那一刻**开始计时（见
     * {@link top.gunmu.party.GameConfig#START_BARRIER_TIME}）。
     * 屏障期间的 dt 不计入这里，正在跑的回合时长才看它。
     */
    public float stageClock;

    /** 屏幕中央的大字提示。 */
    public String bannerText = "";
    public float bannerTimer;

    /** 左下角的滚动消息（击杀 / 事件）。 */
    public final Array<String> feed = new Array<>();

    /** 本关是否已结束。 */
    public boolean stageOver;
    /** 整局是否已结束。 */
    public boolean tournamentOver;
    /** 玩家是否夺冠。 */
    public boolean playerChampion;
    /** 玩家是否被淘汰。 */
    public boolean playerEliminated;

    /** 结算面板内容。 */
    public String resultTitle = "";
    public final Array<String> resultLines = new Array<>();
    /** 本关晋级名单（roll id）。 */
    public final Array<Integer> qualified = new Array<>();

    /** 生存关的死亡计数。 */
    public int stageDeaths;

    public final Array<Roll> rolls = new Array<>();
    public Roll player;

    public float aliveCount() {
        int n = 0;
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.eliminated) {
                n++;
            }
        }
        return n;
    }

    public void pushFeed(String s) {
        feed.add(s);
        while (feed.size > 6) {
            feed.removeIndex(0);
        }
    }

    public void clearFeed() {
        feed.clear();
    }

    public void showBanner(String text, float time) {
        bannerText = text;
        bannerTimer = time;
    }
}
