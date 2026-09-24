package top.gunmu.party.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GunmuPartyGame;
import top.gunmu.party.core.Roll;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.match.LevelDirector;
import top.gunmu.party.render.CameraRig;

/** 主玩法界面：物理推进 + 场景渲染 + HUD。 */
public final class LevelScreen extends ScreenAdapter {

    private final GunmuPartyGame game;
    private final CameraRig rig = new CameraRig();
    private MapDef loaded;
    private int lastStageKey = -1;

    private final Vector3 focus = new Vector3();

    private final InputAdapter input = new InputAdapter() {
        @Override
        public boolean keyDown(int keycode) {
            // 安卓返回键在比赛中被吞掉：误触返回不该丢掉这一局
            return keycode == com.badlogic.gdx.Input.Keys.BACK
                    || keycode == com.badlogic.gdx.Input.Keys.MENU;
        }

        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            return game.hud.touchDown(screenX, screenY, pointer);
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            return game.hud.touchUp(pointer);
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            return game.hud.touchDragged(screenX, screenY, pointer);
        }
    };

    public LevelScreen(GunmuPartyGame game) {
        this.game = game;
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(input);
        loaded = null;
        lastStageKey = -1;
        // 触屏控件只跟平台走，不能在关卡里无脑打开——桌面端会冒出三个灰色按钮
        game.hud.touchControlsVisible = game.mobile;
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    @Override
    public void resize(int w, int h) {
        game.hud.resize(w, h);
        rig.resize(w, h);
    }

    @Override
    public void render(float delta) {
        LevelDirector dir = game.director;

        // 1) 输入。
        //    autoplay 只是个调试开关，**绝不能把玩家锁死**：
        //    · 按 F2 随时开关；
        //    · 玩家一动方向键/摇杆就立刻把控制权还给他。
        Roll player = dir.state.player;
        if (player != null) {
            if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.F2)) {
                dir.autoplayPlayer = !dir.autoplayPlayer;
                Gdx.app.log("GunmuParty", dir.autoplayPlayer
                        ? "已切换到自动演示（按 F2 或动一下方向键可接管）"
                        : "已交还控制权，由你操作");
            }
            if (dir.autoplayPlayer && game.hud.directionInputActive()) {
                dir.autoplayPlayer = false;
                Gdx.app.log("GunmuParty", "检测到你按了方向键，已交还控制权");
            }
            if (!dir.autoplayPlayer) {
                game.hud.applyInput(player, rig.yaw());
            }
        }

        // 2) 逻辑推进
        dir.update(delta);

        // 3) 换图检测
        MapDef map = dir.map();
        if (map != loaded) {
            loaded = map;
            game.renderer.loadMap(map);
            rig.snapTo(focusPos(dir));
            // 控制方式必须能从日志里看出来，否则"为什么我控制不了"根本没法定位
            Gdx.app.log("GunmuParty", "进入第 " + dir.state.levelIndex + " 关 · "
                    + map.name + " · 控制方式："
                    + (dir.autoplayPlayer ? "AI 自动演示（-Dgunmu.autoplay=true）"
                            : "玩家"));
        }

        // 4) 相机：位置跟玩家，朝向跟赛道切线
        rig.update(focusPos(dir), routeYawFor(dir), delta);
        logDiagnostics(delta, dir);

        // 5) 清屏 + 世界
        Gdx.gl.glClearColor(0.36f, 0.44f, 0.56f, 1f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT
                | com.badlogic.gdx.graphics.GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_DEPTH_TEST);
        game.renderer.render(dir, rig.camera, delta);
        Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_DEPTH_TEST);

        // 6) HUD
        game.hud.render(dir, delta);

        // 7) 流程跳转
        if (dir.phase == LevelDirector.Phase.LEVEL_DONE) {
            game.setScreen(new LevelResultScreen(game));
        } else if (dir.phase == LevelDirector.Phase.TOURNAMENT_DONE) {
            game.setScreen(new ChampionScreen(game));
        }

        // 8) 抓帧（仅 -Dgunmu.shotdir 开启时）
        top.gunmu.party.render.Screenshot.maybeCapture(delta);
    }

    /**
     * 相机该朝哪边：玩家所在处的赛道切线朝向（实现见 {@link top.gunmu.party.map.MapNav}）。
     * 非竞速图返回 NaN，表示"不要按切线看"。
     *
     * <p>这段逻辑搬进 MapNav 是刻意的：它按模式有条件成立（只有竞速图有 route），
     * 留在 Screen 里就没法无头验证，而 Screen 里的漏判是会**崩进程**的。
     */
    private float routeYawFor(LevelDirector dir) {
        return top.gunmu.party.map.MapNav.cameraYaw(dir.map(), dir.state.player,
                focus.x, focus.z, near);
    }


    private final float[] near = new float[4];

    private float diagTimer = 1f;

    /**
     * 每 2 秒打一行现场数据。存在的意义：相机把玩家框到屏幕外、地形没画在脚下
     * 这类问题，光看断言永远发现不了。
     */
    private void logDiagnostics(float delta, LevelDirector dir) {
        diagTimer -= delta;
        if (diagTimer > 0f) {
            return;
        }
        diagTimer = 2f;
        MapDef m = dir.map();
        Roll p = dir.state.player;
        com.badlogic.gdx.math.Vector3 c = rig.camera.position;
        com.badlogic.gdx.math.Vector3 d = rig.camera.direction;
        // 整行都交给 MapNav 拼（原来这里直接读 route 的 totalLength，
        // 生存图没有 route → 一进「涨水盆地」就 NPE 把进程干掉）。
        // 收成纯函数之后能被无头自检逐张图跑一遍。
        Gdx.app.log("Diag", top.gunmu.party.map.MapNav.diagLine(
                m, p, dir.state.stageClock,
                c.x, c.y, c.z, d.x, d.y, d.z, focus.x, focus.y, focus.z));
    }

    private Vector3 focusPos(LevelDirector dir) {
        Roll player = dir.state.player;
        if (player != null && player.onField()) {
            focus.set(player.pos);
            return focus;
        }
        // 玩家出局：跟当前的领头羊
        Roll best = null;
        Array<Roll> rolls = dir.rolls();
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (r.eliminated || r.parked) {
                continue;
            }
            if (best == null || r.rank < best.rank) {
                best = r;
            }
        }
        if (best != null) {
            focus.set(best.pos);
        } else if (player != null) {
            focus.set(player.pos);
        }
        return focus;
    }

    @Override
    public void dispose() {
        Gdx.input.setInputProcessor(null);
    }
}
