package top.gunmu.party.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;

import top.gunmu.party.GameConfig;
import top.gunmu.party.GunmuPartyGame;
import top.gunmu.party.items.ItemType;
import top.gunmu.party.ultimates.UltimateType;

/** 标题界面：规则速览 + 开始。 */
public final class LobbyScreen extends UiScreen {

    private float backHint;

    private static final String[] RULES = {
            "30 根滚木从山坡滚下去。摇杆控向、能跳、下坡越滚越快。",
            "速度够快时撞到别人会把他撞碎；碎掉的重生到最近存档点。",
            "每根滚木 100 点血。地图上固定位置会刷道具，捡了就用来坑人。",
    };

    private static final String[] LEVELS = {
            "第 1 关   热身赛道   竞速           30 → 25",
            "第 2 关   山坡冲刺   竞速           25 → 20",
            "第 3 关   生存赛道   竞速 + 生存    20 → 15",
            "第 4 关   多重赛场   竞速+生存+竞技  15 → 10",
            "第 5 关   决战       竞技·禁复活     10 → 1",
    };

    public LobbyScreen(GunmuPartyGame game) {
        super(game);
    }

    @Override
    public void render(float delta) {
        fillBackground();

        beginShapes();
        game.hud.fillRect(0f, height - 190f, width, 190f, new Color(0x111720ff));
        endShapes();

        beginText();
        center(game.fonts.huge, TEXT, "滚 木 派 对", height - 84f);
        center(game.fonts.small, DIM,
                game.hud.fonts().cjk ? "30 根滚木 · 5 关淘汰赛 · 最后只剩 1 根"
                        : "30 LOGS / 5 STAGES / ONLY 1 SURVIVES",
                height - 132f);
        // 版本号必须显示出来：内测阶段玩家报 bug 时，"你玩的是哪一版"是第一个要问的问题
        center(game.fonts.small, ACCENT,
                (game.hud.fonts().cjk ? "版本 " : "build ") + GameConfig.versionText(),
                height - 166f);

        float y = height - 240f;
        for (String r : RULES) {
            center(game.fonts.small, DIM, r, y);
            y -= 30f;
        }

        y -= 22f;
        center(game.fonts.medium, ACCENT, "赛程", y);
        y -= 36f;
        for (String l : LEVELS) {
            center(game.fonts.small, TEXT, l, y);
            y -= 28f;
        }

        y -= 24f;
        center(game.fonts.small, DIM,
                "道具 " + ItemType.ALL.length + " 种（全部取材自 2026 热梗）"
                        + " · 决战技 " + UltimateType.ALL.length + " 种（第 4 关起可用）", y);
        y -= 28f;
        center(game.fonts.small, DIM,
                "普通攻击：第 5 关起解锁（J / 触屏「攻击」，扇形 15 伤，冷却 5 秒）", y);
        y -= 28f;
        center(game.fonts.small, DIM,
                "键盘 WASD 移动   空格 跳跃   E 道具   Q 决战技   Shift 加速   J 攻击", y);
        y -= 28f;
        center(game.fonts.small, DIM,
                "触屏：左半屏拖动 = 摇杆，右下按钮 = 跳 / 道具 / 决战 / 加速（第 5 关多一个「攻击」）", y);

        center(game.fonts.large, ACCENT,
                game.mobile ? "点击屏幕开始" : "按 空格 或 点击屏幕 开始", 78f);
        if (backHint > 0f) {
            center(game.fonts.small, BAD, "再按一次返回键退出游戏", 40f);
        }
        endText();

        if (backHint > 0f) {
            backHint -= delta;
        }
    }

    @Override
    protected void onTap(float x, float y, int pointer) {
        start();
    }

    @Override
    protected boolean onKey(int keycode) {
        if (keycode == Input.Keys.SPACE || keycode == Input.Keys.ENTER
                || keycode == Input.Keys.NUMPAD_ENTER) {
            start();
            return true;
        }
        if (keycode == Input.Keys.BACK) {
            // 安卓：双击返回键退出，避免误触
            if (backHint > 0f) {
                Gdx.app.exit();
            } else {
                backHint = GameConfig.BACK_EXIT_WINDOW;
            }
            return true;
        }
        return super.onKey(keycode);
    }

    private void start() {
        game.ultimatePick = 0;
        game.director.newTournament(game.seed, game.ultimatePick);
        game.director.autoplayPlayer = game.autoplay;
        game.setScreen(new LevelScreen(game));
    }
}
