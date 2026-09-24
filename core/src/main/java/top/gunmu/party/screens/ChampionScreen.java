package top.gunmu.party.screens;

import com.badlogic.gdx.Input;

import top.gunmu.party.GunmuPartyGame;
import top.gunmu.party.match.LevelDirector;

/** 终局界面：冠军或被淘汰。 */
public final class ChampionScreen extends UiScreen {

    public ChampionScreen(GunmuPartyGame game) {
        super(game);
    }

    @Override
    public void render(float delta) {
        fillBackground();
        LevelDirector dir = game.director;

        float w = Math.min(width - 200f, 760f);
        float h = 380f;
        float x = (width - w) * 0.5f;
        float y = (height - h) * 0.5f;

        beginShapes();
        game.hud.card(x, y, w, h, CARD, dir.state.playerChampion ? ACCENT : BAD, true);
        endShapes();

        beginText();
        String title = dir.state.playerChampion ? "滚 木 之 王" : "止 步 于 此";
        center(game.fonts.huge, dir.state.playerChampion ? ACCENT : BAD,
                title, y + h - 74f);

        float ty = y + h - 150f;
        for (String line : dir.state.resultLines) {
            center(game.fonts.medium, TEXT, line, ty);
            ty -= 40f;
        }

        center(game.fonts.small, DIM,
                "本局走了 " + dir.state.levelIndex + " 关 · 累计击杀 " + dir.state.player.kills
                        + " · 累计伤害 " + (int) dir.state.player.damageDealt,
                ty - 10f);

        center(game.fonts.medium, ACCENT, "按 空格 或 点击屏幕 重开一局", y + 44f);
        endText();
    }

    @Override
    protected void onTap(float x, float y, int pointer) {
        restart();
    }

    @Override
    protected boolean onKey(int keycode) {
        if (keycode == Input.Keys.SPACE || keycode == Input.Keys.ENTER
                || keycode == Input.Keys.NUMPAD_ENTER) {
            restart();
            return true;
        }
        return false;
    }

    private void restart() {
        game.restartTournament();
        game.setScreen(new LobbyScreen(game));
    }
}
