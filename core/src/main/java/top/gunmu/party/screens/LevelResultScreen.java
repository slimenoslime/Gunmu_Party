package top.gunmu.party.screens;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;

import top.gunmu.party.GunmuPartyGame;
import top.gunmu.party.core.Roll;
import top.gunmu.party.match.LevelDirector;
import top.gunmu.party.ultimates.UltimateType;

/** 每关结束后的结算面板。 */
public final class LevelResultScreen extends UiScreen {

    private final LevelDirector dir;

    /** autostart 模式下的自动推进计时。 */
    private float autoDelay;

    public LevelResultScreen(GunmuPartyGame game) {
        super(game);
        this.dir = game.director;
    }

    @Override
    public void render(float delta) {
        fillBackground();
        renderScene(delta);

        // autostart：无人值守时自动推进，便于连续截图/看完整局
        if (game.autostart) {
            autoDelay += delta;
            if (autoDelay > 1.2f) {
                autoDelay = 0f;
                proceed();
                return;
            }
        }

        int level = dir.state.levelIndex;
        float w = Math.min(width - 160f, 860f);
        float h = 470f;
        float x = (width - w) * 0.5f;
        float y = (height - h) * 0.5f;

        beginShapes();
        game.hud.card(x, y, w, h, CARD, dir.state.playerEliminated ? BAD : GOOD,
                true);
        endShapes();

        beginText();
        center(game.fonts.large, dir.state.playerEliminated ? BAD : GOOD,
                dir.state.resultTitle + " · 第 " + level + " 关", y + h - 42f);

        float ty = y + h - 106f;
        for (String line : dir.state.resultLines) {
            center(game.fonts.medium, TEXT, line, ty);
            ty -= 36f;
        }

        ty -= 12f;
        center(game.fonts.medium, ACCENT,
                "本关晋级 " + dir.state.qualified.size + " 人 / "
                        + LevelDirector.QUALIFIERS[level - 1] + " 个名额", ty);
        ty -= 40f;

        // 晋级名单
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < dir.state.qualified.size && shown < 20; i++) {
            int id = dir.state.qualified.get(i);
            if (id >= 0 && id < dir.rolls().size) {
                Roll r = dir.rolls().get(id);
                sb.append(r.name).append("  ");
                shown++;
            }
        }
        centerWrapped(game.fonts.small, DIM, "晋级名单：" + sb, ty, w - 60f);

        ty -= 56f;
        center(game.fonts.small, DIM, "你的击杀 " + dir.state.player.kills
                + " · 承受伤害 " + (int) dir.state.player.damageTaken
                + " · 造成伤害 " + (int) dir.state.player.damageDealt, ty);

        boolean nextUsesUltimate = !dir.state.tournamentOver
                && dir.state.levelIndex + 1 >= 4;
        String hint = dir.state.tournamentOver
                ? "按 空格 或 点击屏幕 查看结果"
                : nextUsesUltimate
                ? "按 空格 或 点击屏幕 选择决战技"
                : "按 空格 或 点击屏幕 进入第 " + (dir.state.levelIndex + 1) + " 关";
        center(game.fonts.medium, ACCENT, hint, y + 44f);
        endText();
    }

    /** 背景里仍然把 3D 场景画出来，让结算面板不悬在纯色上。 */
    private void renderScene(float delta) {
        if (dir.map() == null) {
            return;
        }
        com.badlogic.gdx.Gdx.gl.glClearColor(0.36f, 0.44f, 0.56f, 1f);
        com.badlogic.gdx.Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT
                | com.badlogic.gdx.graphics.GL20.GL_DEPTH_BUFFER_BIT);
    }

    @Override
    protected void onTap(float x, float y, int pointer) {
        proceed();
    }

    @Override
    protected boolean onKey(int keycode) {
        if (keycode == Input.Keys.SPACE || keycode == Input.Keys.ENTER
                || keycode == Input.Keys.NUMPAD_ENTER) {
            proceed();
            return true;
        }
        return false;
    }

    private void proceed() {
        if (dir.state.tournamentOver) {
            game.setScreen(new ChampionScreen(game));
            return;
        }
        int next = dir.state.levelIndex + 1;
        if (next >= 4) {
            game.setScreen(new UltimateSelectScreen(game, next == 5));
            return;
        }
        dir.advanceToNextLevel();
        game.setScreen(new LevelScreen(game));
    }

    /** 供决战技界面使用：直接从结算跳到选技能。 */
    static boolean needsUltimatePick(GunmuPartyGame game) {
        LevelDirector d = game.director;
        return !d.state.tournamentOver && d.state.levelIndex + 1 >= 4;
    }

    static UltimateType currentPick(GunmuPartyGame game) {
        int i = game.ultimatePick;
        if (i < 0 || i >= UltimateType.ALL.length) {
            return UltimateType.ALL[0];
        }
        return UltimateType.ALL[i];
    }

    static Color colorOf(UltimateType t) {
        return t.color;
    }
}
