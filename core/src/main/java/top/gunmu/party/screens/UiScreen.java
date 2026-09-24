package top.gunmu.party.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;

import top.gunmu.party.GunmuPartyGame;

/** 所有非玩法界面的公共基类：统一投影、统一画法、统一输入转发。 */
public abstract class UiScreen extends ScreenAdapter {

    protected static final Color BG = new Color(0x0b0f14ff);
    protected static final Color CARD = new Color(0x171d26ff);
    protected static final Color CARD_SEL = new Color(0x24303fff);
    protected static final Color TEXT = new Color(0xe6edf3ff);
    protected static final Color DIM = new Color(0x8b949eff);
    protected static final Color ACCENT = new Color(0xffb703ff);
    protected static final Color GOOD = new Color(0x3fb950ff);
    protected static final Color BAD = new Color(0xf85149ff);

    protected final GunmuPartyGame game;
    /** 虚拟坐标系尺寸（= 真实像素 / uiScale），所有排版都用它。 */
    protected int width = 1440;
    protected int height = 810;
    protected float uiScale = 1f;
    protected final GlyphLayout layout = new GlyphLayout();

    private final InputAdapter input = new InputAdapter() {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            onTap(screenX / uiScale, height - screenY / uiScale, pointer);
            return true;
        }

        @Override
        public boolean keyDown(int keycode) {
            return onKey(keycode);
        }
    };

    protected UiScreen(GunmuPartyGame game) {
        this.game = game;
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(input);
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    @Override
    public void resize(int w, int h) {
        game.hud.resize(w, h);
        this.uiScale = game.hud.uiScale();
        this.width = game.hud.virtualWidth();
        this.height = game.hud.virtualHeight();
    }

    protected void onTap(float x, float y, int pointer) {
    }

    /**
     * 键盘 / 系统返回键。
     * 默认吞掉返回键，避免安卓上误触直接退出游戏。
     */
    protected boolean onKey(int keycode) {
        return keycode == com.badlogic.gdx.Input.Keys.BACK
                || keycode == com.badlogic.gdx.Input.Keys.MENU;
    }

    // ================================================================== 绘制

    protected void fillBackground() {
        Gdx.gl.glClearColor(BG.r, BG.g, BG.b, 1f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT
                | com.badlogic.gdx.graphics.GL20.GL_DEPTH_BUFFER_BIT);
    }

    protected void beginShapes() {
        game.hud.shapesBegin();
    }

    protected void endShapes() {
        game.hud.shapesEnd();
    }

    protected void beginText() {
        game.hud.beginText();
    }

    protected void endText() {
        game.hud.endText();
    }

    protected void text(BitmapFont f, Color c, String s, float x, float y) {
        f.setColor(c);
        f.draw(game.hud.batch(), s, x, y);
    }

    protected void center(BitmapFont f, Color c, String s, float y) {
        layout.setText(f, s);
        f.setColor(c);
        f.draw(game.hud.batch(), s, (width - layout.width) * 0.5f, y);
    }

    protected float textWidth(BitmapFont f, String s) {
        layout.setText(f, s);
        return layout.width;
    }

    protected void centerWrapped(BitmapFont f, Color c, String s, float y, float maxWidth) {
        if (textWidth(f, s) <= maxWidth) {
            center(f, c, s, y);
            return;
        }
        StringBuilder line = new StringBuilder();
        float yy = y;
        for (int i = 0; i < s.length(); i++) {
            line.append(s.charAt(i));
            if (textWidth(f, line.toString()) > maxWidth) {
                line.deleteCharAt(line.length() - 1);
                center(f, c, line.toString(), yy);
                line.setLength(0);
                line.append(s.charAt(i));
                yy -= f.getLineHeight() * 0.95f;
            }
        }
        if (line.length() > 0) {
            center(f, c, line.toString(), yy);
        }
    }
}
