package top.gunmu.party;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Disposable;

import top.gunmu.party.match.LevelDirector;
import top.gunmu.party.render.Fonts;
import top.gunmu.party.render.Hud;
import top.gunmu.party.render.MeshFactory;
import top.gunmu.party.render.SceneRenderer;
import top.gunmu.party.render.TextureFactory;
import top.gunmu.party.screens.LobbyScreen;

/**
 * 《滚木派对》入口。桌面与安卓共用同一份实例，只由 {@link #mobile} 区分。
 *
 * <p>30 根滚木，五关淘汰赛。设计文档见 {@code docs/}。
 */
public final class GunmuPartyGame extends Game {

    public Fonts fonts;
    public TextureFactory textures;
    public MeshFactory meshes;
    public SceneRenderer renderer;
    public Hud hud;
    public LevelDirector director;

    /**
     * 是否运行在移动端。影响：
     * <ul>
     *   <li>HUD 默认打开触屏摇杆与按钮</li>
     *   <li>UI 按屏幕高度整体放大</li>
     *   <li>地形渲染网格抽稀（物理精度不变）</li>
     *   <li>接住系统返回键，避免误触直接退出</li>
     * </ul>
     */
    public final boolean mobile;

    /** 本局随机种子。可用 -Dgunmu.seed=12345 固定下来做回归。 */
    public long seed;

    /**
     * 跳过大厅直接开赛，并（可选）由 AI 接管玩家这根滚木。
     * <p>用法：{@code -Dgunmu.autostart=true -Dgunmu.autoplay=true}
     * <p>存在的意义：看一眼实际画面就能发现"相机把玩家框到屏幕外""W 键在往后退"
     * 这类只在真机上才暴露的问题，不用手工开局。
     */
    public boolean autostart;
    public boolean autoplay;

    /** 玩家在 L4 / L5 选的决战技序号。 */
    public int ultimatePick;

    public GunmuPartyGame() {
        this(false);
    }

    public GunmuPartyGame(boolean mobile) {
        this.mobile = mobile;
    }

    @Override
    public void create() {
        String seedProp = System.getProperty("gunmu.seed");
        seed = seedProp != null ? Long.parseLong(seedProp) : System.nanoTime();
        autostart = Boolean.getBoolean("gunmu.autostart");
        autoplay = Boolean.getBoolean("gunmu.autoplay");

        // 移动端先降低地形渲染密度（只影响画面，不影响物理）
        GameConfig.terrainRenderStep = mobile ? 2 : 1;

        fonts = new Fonts();
        fonts.load();
        textures = new TextureFactory();
        meshes = new MeshFactory(textures);
        renderer = new SceneRenderer(meshes);
        director = new LevelDirector();
        hud = new Hud(fonts);
        hud.touchControlsVisible = mobile;

        if (mobile) {
            // 接住返回键：交给界面自己处理，不要直接退出
            Gdx.input.setCatchKey(Input.Keys.BACK, true);
            Gdx.input.setCatchKey(Input.Keys.MENU, true);
        }

        Gdx.app.log("GunmuParty", "启动完成。平台=" + (mobile ? "Android" : "Desktop")
                + " 种子=" + seed + " libGDX " + com.badlogic.gdx.Version.VERSION);

        if (autostart) {
            Gdx.app.log("GunmuParty", "autostart 已开启，跳过大厅直接开赛"
                    + (autoplay ? "（玩家由 AI 接管）" : ""));
            ultimatePick = 0;
            director.newTournament(seed, ultimatePick);
            director.autoplayPlayer = autoplay;
            setScreen(new top.gunmu.party.screens.LevelScreen(this));
        } else {
            setScreen(new LobbyScreen(this));
        }
        top.gunmu.party.render.Screenshot.init();
    }

    @Override
    public void dispose() {
        if (getScreen() instanceof Disposable) {
            ((Disposable) getScreen()).dispose();
        }
        super.dispose();
        if (hud != null) {
            hud.dispose();
        }
        if (renderer != null) {
            renderer.dispose();
        }
        if (meshes != null) {
            meshes.dispose();
        }
        if (textures != null) {
            textures.dispose();
        }
        if (fonts != null) {
            fonts.dispose();
        }
    }

    /** 重开一局。 */
    public void restartTournament() {
        seed = System.nanoTime();
        ultimatePick = 0;
        director.newTournament(seed, ultimatePick);
    }
}
