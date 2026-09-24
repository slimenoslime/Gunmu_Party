package top.gunmu.party.screens;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;

import top.gunmu.party.GunmuPartyGame;
import top.gunmu.party.ultimates.UltimateType;

/**
 * 决战技选择界面。进入 L4 前选，进入 L5 前可重选一次（docs/06 D19）。
 */
public final class UltimateSelectScreen extends UiScreen {

    private static final int COLS = 4;
    private static final int ROWS = 2;

    private final boolean reselect;
    private int picked;

    /** autostart 模式下的自动确认计时。 */
    private float autoDelay;

    public UltimateSelectScreen(GunmuPartyGame game, boolean reselect) {
        super(game);
        this.reselect = reselect;
        this.picked = game.ultimatePick;
        if (picked < 0 || picked >= UltimateType.ALL.length) {
            picked = 0;
        }
    }

    private float cardW() {
        return Math.min(300f, (width - 120f) / COLS - 18f);
    }

    private float cardH() {
        return Math.min(230f, (height - 300f) / ROWS - 18f);
    }

    private float gridLeft() {
        return (width - (cardW() * COLS + 18f * (COLS - 1))) * 0.5f;
    }

    private float gridBottom() {
        return height * 0.18f;
    }

    private void cardRect(int index, float[] out) {
        int c = index % COLS;
        int r = index / COLS;
        out[0] = gridLeft() + c * (cardW() + 18f);
        out[1] = gridBottom() + (ROWS - 1 - r) * (cardH() + 18f);
        out[2] = cardW();
        out[3] = cardH();
    }

    @Override
    public void render(float delta) {
        fillBackground();

        // autostart：无人值守时自动确认，避免卡在选技界面
        if (game.autostart) {
            autoDelay += delta;
            if (autoDelay > 1.2f) {
                autoDelay = 0f;
                confirm();
                return;
            }
        }

        float[] rect = new float[4];
        beginShapes();
        for (int i = 0; i < UltimateType.ALL.length; i++) {
            cardRect(i, rect);
            Color head = UltimateType.ALL[i].color;
            game.hud.card(rect[0], rect[1], rect[2], rect[3],
                    i == picked ? CARD_SEL : CARD, head, i == picked);
            // 顶部色带
            game.hud.fillRect(rect[0] + 2f, rect[1] + rect[3] - 8f, rect[2] - 4f, 6f,
                    new Color(head.r, head.g, head.b, i == picked ? 0.95f : 0.5f));
        }
        endShapes();

        beginText();
        center(game.fonts.large, TEXT, reselect ? "决赛前 · 重选决战技" : "选择你的决战技",
                height - 52f);
        center(game.fonts.small, DIM,
                "决战技只在第 4 关与第 5 关可用，与道具互不冲突。"
                        + "进入第 5 关前还能换一次。", height - 92f);

        for (int i = 0; i < UltimateType.ALL.length; i++) {
            UltimateType t = UltimateType.ALL[i];
            cardRect(i, rect);
            text(game.fonts.medium, i == picked ? ACCENT : TEXT, t.cn,
                    rect[0] + 16f, rect[1] + rect[3] - 34f);
            text(game.fonts.small, DIM, "CD " + (int) t.cooldown + "s",
                    rect[0] + 16f, rect[1] + rect[3] - 62f);
            centerWrapped2(game.fonts.small, TEXT, t.tagline,
                    rect[0] + rect[2] * 0.5f, rect[1] + rect[3] - 96f, rect[2] - 28f);
            centerWrapped2(game.fonts.small, DIM, t.detail,
                    rect[0] + rect[2] * 0.5f, rect[1] + rect[3] - 136f, rect[2] - 28f);
        }

        center(game.fonts.medium, ACCENT,
                "点击卡片选择，再按 空格 / 点击下方按钮 确认：" + UltimateType.ALL[picked].cn,
                64f);
        endText();
    }

    private void centerWrapped2(com.badlogic.gdx.graphics.g2d.BitmapFont f, Color c,
                                String s, float cx, float y, float maxWidth) {
        StringBuilder line = new StringBuilder();
        float yy = y;
        for (int i = 0; i < s.length(); i++) {
            line.append(s.charAt(i));
            if (textWidth(f, line.toString()) > maxWidth) {
                line.deleteCharAt(line.length() - 1);
                if (line.length() > 0) {
                    layout.setText(f, line.toString());
                    f.setColor(c);
                    f.draw(game.hud.batch(), line.toString(),
                            cx - layout.width * 0.5f, yy);
                }
                line.setLength(0);
                line.append(s.charAt(i));
                yy -= f.getLineHeight() * 0.92f;
            }
        }
        if (line.length() > 0) {
            layout.setText(f, line.toString());
            f.setColor(c);
            f.draw(game.hud.batch(), line.toString(), cx - layout.width * 0.5f, yy);
        }
    }

    @Override
    protected void onTap(float x, float y, int pointer) {
        float[] rect = new float[4];
        for (int i = 0; i < UltimateType.ALL.length; i++) {
            cardRect(i, rect);
            if (x >= rect[0] && x <= rect[0] + rect[2]
                    && y >= rect[1] && y <= rect[1] + rect[3]) {
                picked = i;
                return;
            }
        }
        if (y < height * 0.14f) {
            confirm();
        }
    }

    @Override
    protected boolean onKey(int keycode) {
        if (keycode == Input.Keys.SPACE || keycode == Input.Keys.ENTER
                || keycode == Input.Keys.NUMPAD_ENTER) {
            confirm();
            return true;
        }
        if (keycode == Input.Keys.LEFT || keycode == Input.Keys.A) {
            picked = (picked + UltimateType.ALL.length - 1) % UltimateType.ALL.length;
            return true;
        }
        if (keycode == Input.Keys.RIGHT || keycode == Input.Keys.D) {
            picked = (picked + 1) % UltimateType.ALL.length;
            return true;
        }
        return false;
    }

    private void confirm() {
        game.ultimatePick = picked;
        game.director.setPlayerUltimate(picked);
        game.director.advanceToNextLevel();
        game.setScreen(new LevelScreen(game));
    }
}
