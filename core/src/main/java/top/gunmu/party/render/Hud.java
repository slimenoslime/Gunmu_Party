package top.gunmu.party.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Roll;
import top.gunmu.party.items.ItemType;
import top.gunmu.party.match.LevelDirector;

/**
 * 平视 HUD：血条、名次、道具/决战技槽、触屏摇杆与按钮。
 *
 * <p>不使用 Scene2D —— 全部用 ShapeRenderer + SpriteBatch 直接画，
 * 既不需要 uiskin 资源，也便于把触屏与键盘映射到同一个 {@link Roll#input}。
 *
 * <p>绘制顺序固定：先全部 Shape，再全部文字，避免两个批处理交替打断。
 */
public final class Hud implements Disposable {

    private static final Color C_PANEL = new Color(0x12161cdd);
    private static final Color C_TEXT = new Color(0xe6edf3ff);
    private static final Color C_DIM = new Color(0x8b949eff);
    private static final Color C_HP = new Color(0x3fb950ff);
    private static final Color C_HP_LOW = new Color(0xf85149ff);
    private static final Color C_ACCENT = new Color(0xffb703ff);
    private static final Color C_TRACK = new Color(0x161b22ff);
    /** 自动演示横幅的底色，刻意做成醒目的橙，避免被当成"游戏坏了"。 */
    private static final Color AUTOPLAY_BG = new Color(0x7a4a00e0);
    /** 起点屏障倒计时的底色，与场景里的青色屏障同一色系。 */
    private static final Color BARRIER_BG = new Color(0x0b3d4dcc);
    /** 加速键的青色，和场景里的起点屏障同一色系。 */
    private static final Color C_BARRIER = new Color(0x62e8ffFF);

    private static final float STICK_MIN_DELTA = 8f;

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final Fonts fonts;
    private final GlyphLayout layout = new GlyphLayout();

    private int width;
    private int height;
    /** 真实屏幕像素尺寸（虚拟坐标 = 真实 / uiScale）。 */
    private int realW;
    private int realH;
    private float uiScale = 1f;

    /**
     * 全部 UI 分块的位置与尺寸，由 {@link HudLayout} 按锚点算出。
     *
     * <p>绘制代码**只许从这里读位置**，不许再出现 {@code width - 108} 这种算式 ——
     * 那正是手表上按键压住地图、面板互相叠的根因。
     */
    private HudLayout L = HudLayout.compute(1440, 810, 21f, 29f, 39f);

    private int stickPointer = -1;
    private final Vector2 stickOrigin = new Vector2();
    private final Vector2 stickKnob = new Vector2();
    private final Vector2 stickDelta = new Vector2();

    private int jumpPointer = -1;
    private int itemPointer = -1;
    private int ultPointer = -1;
    private int dashPointer = -1;
    /** 攻击键（只在决赛关显示，见 {@code GameConfig.ATTACK_MIN_LEVEL}）。 */
    private int attackPointer = -1;

    // ------------------------------------------------------------- 手动转视角
    /** 正在拖动视角的那个 pointer（桌面右键 / 触屏右半屏空白）。 */
    private int viewPointer = -1;
    /** 上一次拖动的横向位置（虚拟坐标），用来算增量。 */
    private float viewLastX;
    /** 待消费的横向拖动量（虚拟像素，向右为正）。被 {@link #takeViewDragX()} 取走即清零。 */
    private float viewDrag;

    private final Vector2 jumpBtn = new Vector2();
    private final Vector2 itemBtn = new Vector2();
    private final Vector2 ultBtn = new Vector2();
    private final Vector2 dashBtn = new Vector2();
    /** 攻击键。位置在右侧按钮列的**最上面**：它是决赛关才出现的键，
     *  固定摆在最上格，出现/消失时下面四键不会跟着挪，肌肉记忆不被打乱。 */
    private final Vector2 attackBtn = new Vector2();

    // ---------------------------------------------------------------- 道具卡排版
    // 卡片的宽度与"梗出处显示几行"由 HudLayout.measureCard 决定（属性永不截断，
    // 放不下时省的是梗出处），这里只负责把量好的行画出来。

    /** 道具卡内容（每帧先测量折行，再画面板，最后画字）。 */
    private final Array<String> cardAttrLines = new Array<>();
    private final Array<String> cardDesc = new Array<>();
    private final Array<String> cardMeme = new Array<>();
    private String cardTitle = "";
    private String cardAttrs = "";
    private float cardW;
    private float cardH;
    private boolean cardVisible;

    /** 供外部（不进入游戏时）隐藏触屏控件。 */
    public boolean touchControlsVisible = true;

    /**
     * 攻击键此刻是否在屏幕上。
     *
     * <p>每帧由 {@code render} 写入（取决于"本关是否开放普攻"），
     * 触摸判定读它 —— 否则非决赛关会存在一个**看不见但点得到**的按钮，
     * 玩家点到空白处就触发了一次挥击输入。
     */
    private boolean attackButtonVisible;

    public Hud(Fonts fonts) {
        this.fonts = fonts;
    }

    public void resize(int w, int h) {
        // 整个 HUD 按屏幕尺寸缩放：内部一律用"虚拟坐标"排版，字号也跟着 uiScale 走。
        // 缩放口径与档位判定都在 HudLayout 里（唯一口径，自检脚本共用同一份），
        // 这里只负责把结果搬到绘制代码用得到的地方。
        realW = w;
        realH = h;
        L = HudLayout.compute(w, h, fonts.lineHeight(fonts.small),
                fonts.lineHeight(fonts.medium), fonts.lineHeight(fonts.large));
        uiScale = L.uiScale;
        width = Math.round(L.width);
        height = Math.round(L.height);

        jumpBtn.set(L.btnBox[HudLayout.BTN_JUMP].cx(), L.btnBox[HudLayout.BTN_JUMP].cy());
        itemBtn.set(L.btnBox[HudLayout.BTN_ITEM].cx(), L.btnBox[HudLayout.BTN_ITEM].cy());
        ultBtn.set(L.btnBox[HudLayout.BTN_ULT].cx(), L.btnBox[HudLayout.BTN_ULT].cy());
        dashBtn.set(L.btnBox[HudLayout.BTN_DASH].cx(), L.btnBox[HudLayout.BTN_DASH].cy());
        attackBtn.set(L.btnBox[HudLayout.BTN_ATTACK].cx(), L.btnBox[HudLayout.BTN_ATTACK].cy());
        resizeProjection(width, height);
        fonts.setUiScale(uiScale);
    }

    /** 当前布局（自检与调试用；绘制代码内部也读它）。 */
    public HudLayout layout() {
        return L;
    }

    /** 现在是紧凑档（手表 / 极窄窗）吗。 */
    public boolean compactLayout() {
        return L.compact();
    }

    public float uiScale() {
        return uiScale;
    }

    /** 虚拟坐标系下的宽度（所有 HUD 排版都用这套坐标）。 */
    public int virtualWidth() {
        return width;
    }

    public int virtualHeight() {
        return height;
    }

    // ================================================================== 输入

    public boolean touchDown(int screenX, int screenY, int pointer) {
        if (!touchControlsVisible) {
            return false;
        }
        float x = screenX / uiScale;
        float y = height - screenY / uiScale;
        // 命中半径给到 1.22 倍半径（手指比像素粗，手表上尤其明显）
        float hit2 = L.btnRadius * L.btnRadius * 1.5f;
        if (attackButtonVisible && dist2(x, y, attackBtn) < hit2) {
            attackPointer = pointer;
            return true;
        }
        if (dist2(x, y, jumpBtn) < hit2) {
            jumpPointer = pointer;
            return true;
        }
        if (dist2(x, y, itemBtn) < hit2) {
            itemPointer = pointer;
            return true;
        }
        if (dist2(x, y, ultBtn) < hit2) {
            ultPointer = pointer;
            return true;
        }
        if (dist2(x, y, dashBtn) < hit2) {
            dashPointer = pointer;
            return true;
        }
        // 摇杆区由布局给出：左半屏，但避开右下键列那一列（手表上键列占了小半屏）
        if (L.stickZone.contains(x, y) && stickPointer < 0) {
            stickPointer = pointer;
            stickOrigin.set(x, y);
            stickKnob.set(x, y);
            stickDelta.setZero();
            return true;
        }
        // 右半屏空白处（四个按钮已经在上面被拦走了）= 转视角。
        // 手机上没有鼠标也没有 Q/E，右半屏滑动是唯一能转视角的手势。
        if (viewPointer < 0) {
            viewPointer = pointer;
            viewLastX = x;
            return true;
        }
        return false;
    }

    /**
     * 桌面端鼠标<b>右键</b>按下：开始转视角。
     *
     * <p>触屏那条路走 {@link #touchDown}（那里只被调用一次、没有 button 参数），
     * 桌面这条路要判 button，所以由 Screen 转进来。
     */
    public void viewTouchDown(int screenX, int pointer) {
        if (viewPointer >= 0) {
            return;
        }
        viewPointer = pointer;
        viewLastX = screenX / uiScale;
    }

    /** 取走本帧累积的视角拖动量（虚拟像素，向右为正）。**读到即清**（D43）。 */
    public float takeViewDragX() {
        float d = viewDrag;
        viewDrag = 0f;
        return d;
    }

    /** 玩家是否正在拖视角（用来把控制权从他 AI 手里抢回来）。 */
    public boolean viewDragging() {
        return viewPointer >= 0;
    }

    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (pointer == viewPointer) {
            float x = screenX / uiScale;
            viewDrag += x - viewLastX;
            viewLastX = x;
            return true;
        }
        if (pointer != stickPointer) {
            return false;
        }
        float x = screenX / uiScale;
        float y = height - screenY / uiScale;
        stickDelta.set(x - stickOrigin.x, y - stickOrigin.y);
        if (stickDelta.len() > L.stickRadius) {
            stickDelta.setLength(L.stickRadius);
        }
        stickKnob.set(stickOrigin.x + stickDelta.x, stickOrigin.y + stickDelta.y);
        return true;
    }

    public boolean touchUp(int pointer) {
        if (pointer == viewPointer) {
            viewPointer = -1;
            return true;
        }
        if (pointer == stickPointer) {
            stickPointer = -1;
            stickDelta.setZero();
            return true;
        }
        if (pointer == jumpPointer) {
            jumpPointer = -1;
            return true;
        }
        if (pointer == itemPointer) {
            itemPointer = -1;
            return true;
        }
        if (pointer == ultPointer) {
            ultPointer = -1;
            return true;
        }
        if (pointer == attackPointer) {
            attackPointer = -1;
            return true;
        }
        return false;
    }

    private float dist2(float x, float y, Vector2 c) {
        float dx = x - c.x;
        float dy = y - c.y;
        return dx * dx + dy * dy;
    }

    // ================================================================== 组装输入

    /**
     * 把键盘 / 触屏 / 按钮合成到玩家的 {@link Roll#input}。
     *
     * <p><b>输入是屏幕空间的，必须按相机朝向旋转到世界空间。</b>
     * 旧版直接把 W 映射成世界 {@code -Z}，而相机看向 {@code +Z} ——
     * 按 W 是朝屏幕下方滚，也就是往镜头方向退。这是"移动方向有问题"的根因。
     *
     * <p>屏幕上方在世界里是相机的水平朝向 {@code (sin yaw, cos yaw)}；
     * 屏幕右方是它顺时针转 90°，即 {@code (cos yaw, -sin yaw)}。
     *
     * @param camYaw 相机水平朝向（弧度，0 = +Z），取自 {@link CameraRig#yaw()}。
     */
    public void applyInput(Roll player, float camYaw) {
        // 先收集屏幕空间的摇杆量：sx 向右为正，sy 向上为正
        float sx = 0f;
        float sy = 0f;

        if (stickPointer >= 0 && stickDelta.len() > 8f) {
            sx += stickDelta.x / L.stickRadius;
            sy += stickDelta.y / L.stickRadius;
        }

        float kx = 0f;
        float ky = 0f;
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            kx -= 1f;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            kx += 1f;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP)) {
            ky += 1f;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            ky -= 1f;
        }
        float kl = (float) Math.sqrt(kx * kx + ky * ky);
        if (kl > 1e-4f) {
            sx += kx / kl;
            sy += ky / kl;
        }

        float len = (float) Math.sqrt(sx * sx + sy * sy);
        if (len > 1f) {
            sx /= len;
            sy /= len;
        }

        // 屏幕 -> 世界。符号约定见 InputMapping 的推导，别照着感觉改。
        top.gunmu.party.core.InputMapping.screenToWorld(camYaw, sx, sy, player.input.move);

        boolean jump = Gdx.input.isKeyJustPressed(Input.Keys.SPACE);
        if (jumpPointer >= 0) {
            jump = true;
            jumpPointer = -1;
        }
        if (jump) {
            player.input.jump = true;
        }

        boolean item = Gdx.input.isKeyJustPressed(Input.Keys.E);
        if (itemPointer >= 0) {
            item = true;
            itemPointer = -1;
        }
        if (item) {
            player.input.useItem = true;
        }

        boolean ult = Gdx.input.isKeyJustPressed(Input.Keys.Q);
        if (ultPointer >= 0) {
            ult = true;
            ultPointer = -1;
        }
        if (ult) {
            player.input.useUltimate = true;
        }

        // 加速：Shift（左右都认）
        boolean dash = Gdx.input.isKeyJustPressed(Input.Keys.SHIFT_LEFT)
                || Gdx.input.isKeyJustPressed(Input.Keys.SHIFT_RIGHT);
        if (dashPointer >= 0) {
            dash = true;
            dashPointer = -1;
        }
        if (dash) {
            player.input.dash = true;
        }

        // 普通攻击（决赛关）：键盘 J。
        // 与其余边沿键同一条规矩（D43）—— 只写输入，不清输入：
        // 消费点在 AttackSystem，它读到就清。
        boolean atk = Gdx.input.isKeyJustPressed(Input.Keys.J);
        if (attackPointer >= 0) {
            atk = true;
            attackPointer = -1;
        }
        if (atk) {
            player.input.attack = true;
        }
    }

    // ================================================================== 绘制

    /**
     * 玩家现在是否正在给方向输入（键盘或摇杆）。
     *
     * <p>用来做"一动就交还控制权"：调试用的自动演示（{@code -Dgunmu.autoplay}）
     * 绝不该把玩家锁死 —— 只要玩家碰了方向键/摇杆，控制权立刻还给他。
     */
    public boolean directionInputActive() {
        if (stickPointer >= 0 && stickDelta.len() > 8f) {
            return true;
        }
        return Gdx.input.isKeyPressed(Input.Keys.A)
                || Gdx.input.isKeyPressed(Input.Keys.D)
                || Gdx.input.isKeyPressed(Input.Keys.W)
                || Gdx.input.isKeyPressed(Input.Keys.S)
                || Gdx.input.isKeyPressed(Input.Keys.LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.RIGHT)
                || Gdx.input.isKeyPressed(Input.Keys.UP)
                || Gdx.input.isKeyPressed(Input.Keys.DOWN);
    }

    public void render(LevelDirector dir, float delta) {
        Roll p = dir.state.player;
        if (p == null) {
            return;
        }
        // 道具卡的折行是纯测量（不碰 GL），必须在 shapes.begin 之前算好，
        // 因为面板的高度取决于折出来有几行。
        prepareItemCard(p);
        attackButtonVisible = touchControlsVisible && dir.attacks.enabled;

        shapes.setProjectionMatrix(batch.getProjectionMatrix());

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // 面板位置全部来自锚点布局（HudLayout）——绘制代码里不许再出现坐标算式
        if (L.compact()) {
            panel(L.topStrip.x, L.topStrip.y, L.topStrip.w, L.topStrip.h);
        } else {
            panel(L.topLeft.x, L.topLeft.y, L.topLeft.w, L.topLeft.h);
            panel(L.topRight.x, L.topRight.y, L.topRight.w, L.topRight.h);
        }
        panel(L.status.x, L.status.y, L.status.w, L.status.h);
        if (cardVisible) {
            panel(L.card.x, L.card.y, L.card.w, L.card.h);
        }

        drawBottomLeft(p, dir);
        if (touchControlsVisible) {
            drawStick();
            drawButtons(p, dir.attacks.enabled);
        }
        // 起点屏障倒计时的底衬（标准档在顶部中间；紧凑档在顶栏第二行，不用底衬）
        if (dir.isStartBarrierActive() && !L.compact()) {
            fillRect(L.barrier.x, L.barrier.y, L.barrier.w, L.barrier.h, BARRIER_BG);
        }
        // 自动演示时必须让玩家一眼看出来，不然会被当成"游戏坏了"
        if (dir.autoplayPlayer) {
            fillRect(0f, height - 46f, width, 46f, AUTOPLAY_BG);
        }
        shapes.end();

        batch.begin();
        drawTopLeft(dir);
        drawTopRight(dir);
        drawBottomLeftText(p, dir);
        drawItemCard(p);
        drawBarrierCountdown(dir);
        if (touchControlsVisible) {
            drawButtonLabels(p, dir.attacks.enabled);
        }
        drawFeed(dir);
        drawBanner(dir);
        if (dir.autoplayPlayer) {
            drawAutoplayHint();
        }
        batch.end();
    }

    private void panel(float x, float y, float w, float h) {
        shapes.setColor(C_PANEL);
        shapes.rect(x, y, w, h);
    }

    private void drawStick() {
        if (stickPointer < 0) {
            return;
        }
        shapes.setColor(0.10f, 0.12f, 0.16f, 0.45f);
        shapes.circle(stickOrigin.x, stickOrigin.y, L.stickRadius, 40);
        shapes.setColor(C_ACCENT.r, C_ACCENT.g, C_ACCENT.b, 0.85f);
        shapes.circle(stickKnob.x, stickKnob.y, L.knobRadius, 28);
    }

    private void drawButtons(Roll p, boolean attackEnabled) {
        shapes.setColor(0.10f, 0.12f, 0.16f, 0.65f);
        shapes.circle(jumpBtn.x, jumpBtn.y, L.btnRadius, 28);
        shapes.circle(itemBtn.x, itemBtn.y, L.btnRadius, 28);
        shapes.circle(ultBtn.x, ultBtn.y, L.btnRadius, 28);
        shapes.circle(dashBtn.x, dashBtn.y, L.btnRadius, 28);

        // 攻击键（决赛关）：就绪时暖橙、冷却时暗 + 扇形遮罩，与加速键同一套读法
        if (attackEnabled) {
            shapes.circle(attackBtn.x, attackBtn.y, L.btnRadius, 28);
            boolean ready = p.attackCd <= 0f;
            if (p.attackFlash > 0f) {
                shapes.setColor(1f, 0.62f, 0.29f, 0.92f);
                shapes.circle(attackBtn.x, attackBtn.y, L.btnRadius * 0.74f, 24);
            } else if (ready) {
                shapes.setColor(0.96f, 0.48f, 0.22f, 0.82f);
                shapes.circle(attackBtn.x, attackBtn.y, L.btnRadius * 0.70f, 24);
            } else {
                shapes.setColor(0.96f, 0.48f, 0.22f, 0.22f);
                shapes.circle(attackBtn.x, attackBtn.y, L.btnRadius * 0.70f, 24);
                float cdr = MathUtils.clamp(p.attackCd / GameConfig.ATTACK_COOLDOWN, 0f, 1f);
                shapes.setColor(0f, 0f, 0f, 0.60f);
                shapes.arc(attackBtn.x, attackBtn.y, L.btnRadius * 0.72f, 90f,
                        cdr * 360f, 32);
            }
        }

        // 加速键：就绪时亮，冷却时暗 + 扇形遮罩，正在加速时用更亮的青
        {
            boolean ready = p.dashCd <= 0f;
            if (p.dashTimer > 0f) {
                shapes.setColor(C_BARRIER.r, C_BARRIER.g, C_BARRIER.b, 0.90f);
                shapes.circle(dashBtn.x, dashBtn.y, L.btnRadius * 0.74f, 24);
            } else if (ready) {
                shapes.setColor(0.35f, 0.82f, 0.92f, 0.80f);
                shapes.circle(dashBtn.x, dashBtn.y, L.btnRadius * 0.70f, 24);
            } else {
                shapes.setColor(0.35f, 0.82f, 0.92f, 0.22f);
                shapes.circle(dashBtn.x, dashBtn.y, L.btnRadius * 0.70f, 24);
                float cdr = MathUtils.clamp(p.dashCd / GameConfig.DASH_COOLDOWN, 0f, 1f);
                shapes.setColor(0f, 0f, 0f, 0.60f);
                shapes.arc(dashBtn.x, dashBtn.y, L.btnRadius * 0.72f, 90f,
                        cdr * 360f, 32);
            }
        }

        if (p.carried != null) {
            Color c = p.carried.rarity.color;
            shapes.setColor(c.r, c.g, c.b, 0.78f);
            shapes.circle(itemBtn.x, itemBtn.y, L.btnRadius * 0.72f, 24);
        }
        if (p.ultimate != null) {
            Color c = p.ultimate.color;
            shapes.setColor(c.r, c.g, c.b, p.ultimateCd <= 0f ? 0.85f : 0.28f);
            shapes.circle(ultBtn.x, ultBtn.y, L.btnRadius * 0.74f, 24);
            if (p.ultimateCd > 0f) {
                float cdr = MathUtils.clamp(p.ultimateCd / p.ultimate.cooldown, 0f, 1f);
                shapes.setColor(0f, 0f, 0f, 0.60f);
                shapes.arc(ultBtn.x, ultBtn.y, L.btnRadius * 0.78f, 90f, cdr * 360f, 32);
            }
        }
    }

    private void drawBottomLeft(Roll p, LevelDirector dir) {
        // HP 条与效果条的位置一律来自布局（标准档在左下状态面板里，紧凑档在顶栏底部）
        float ratio = MathUtils.clamp(p.hp / GameConfig.MAX_HP, 0f, 1f);
        shapes.setColor(C_TRACK);
        shapes.rect(L.hpTrack.x, L.hpTrack.y, L.hpTrack.w, L.hpTrack.h);
        shapes.setColor(ratio > 0.35f ? C_HP : C_HP_LOW);
        shapes.rect(L.hpTrack.x, L.hpTrack.y, L.hpTrack.w * ratio, L.hpTrack.h);

        // 道具冷却/持续状态的细条
        shapes.setColor(C_TRACK);
        shapes.rect(L.effTrack.x, L.effTrack.y, L.effTrack.w, L.effTrack.h);
        float eff = 0f;
        for (int i = 0; i < p.effects.size; i++) {
            eff = Math.max(eff, p.effects.get(i).ratio());
        }
        if (eff > 0f) {
            shapes.setColor(C_ACCENT.r, C_ACCENT.g, C_ACCENT.b, 0.85f);
            shapes.rect(L.effTrack.x, L.effTrack.y, L.effTrack.w * eff, L.effTrack.h);
        }
    }

    private void drawTopLeft(LevelDirector dir) {
        String title = dir.state.stageLabel
                + (dir.state.stageCount > 1
                ? "  (" + (dir.state.stageIndex + 1) + "/" + dir.state.stageCount + ")" : "");
        if (L.compact()) {
            // 顶栏第一行：左 关卡（挤不下就省略），右 倒计时 —— 位置见 drawTopRight
            float maxW = L.topStrip.w - 2f * L.pad - 90f;
            font(fonts.medium, C_TEXT,
                    fit(fonts.medium, title, maxW), L.topStrip.x + L.pad, L.stripRow1Top);
            // 第二行左侧：屏障期间显示倒计时，否则显示"模式 · 场上"。
            // 屏障是"看不见摸不着但就是出不去"的东西，倒计时必须一直在（见 drawBarrierCountdown）。
            String line2 = dir.isStartBarrierActive()
                    ? String.format(java.util.Locale.ROOT, "起点封闭 %.1fs", dir.startBarrierRemaining())
                    : dir.state.mode.cn + " · 场上 " + (int) dir.state.aliveCount() + " 根";
            font(fonts.small, dir.isStartBarrierActive() ? C_TEXT : C_DIM,
                    fit(fonts.small, line2, maxW), L.topStrip.x + L.pad, L.stripRow2Top);
            return;
        }
        font(fonts.medium, C_TEXT, title, L.topLeft.x + L.pad, L.topLeft.top() - 22f);
        StringBuilder sub = new StringBuilder("地图 ").append(dir.state.mapName)
                .append(" · 模式 ").append(dir.state.mode.cn)
                .append(" · 场上 ").append((int) dir.state.aliveCount()).append(" 根");
        font(fonts.small, C_DIM, fit(fonts.small, sub.toString(), L.topLeft.w - 2f * L.pad),
                L.topLeft.x + L.pad, L.topLeft.top() - 58f);
    }

    private void drawTopRight(LevelDirector dir) {
        float t = Math.max(0f, dir.state.timeLeft);
        String s = String.format(java.util.Locale.ROOT, "%d:%02d", (int) (t / 60f), (int) (t % 60f));
        Color tc = t < 20f ? C_HP_LOW : C_TEXT;
        if (L.compact()) {
            // 顶栏第一行右端：倒计时（右对齐）
            font(fonts.medium, tc, s,
                    L.topStrip.right() - L.pad - fonts.width(fonts.medium, s), L.stripRow1Top);
            // 第二行右端：名额
            float maxW = L.topStrip.w * 0.52f;
            String q = fit(fonts.small, quotaText(dir), maxW);
            font(fonts.small, C_DIM, q,
                    L.topStrip.right() - L.pad - fonts.width(fonts.small, q), L.stripRow2Top);
            return;
        }
        font(fonts.large, tc, s, L.topRight.x + L.pad, L.topRight.top() - 18f);
        font(fonts.small, C_DIM, quotaText(dir), L.topRight.x + L.pad, L.topRight.top() - 58f);
    }

    /**
     * 放不下就在尾部省略。
     *
     * <p>为什么需要它：手表的可用宽度只有宽屏的三分之一，而地图名 / 模式名 / 名额文案
     * 都是随关卡变的。宁可显示"崩落长阶…"，也不许把字画到面板外面 ——
     * 画出去就是"UI 严重错误"那个观感（文字被屏幕边缘切一半）。
     */
    private String fit(BitmapFont f, String text, float maxWidth) {
        if (maxWidth <= 0f || fonts.width(f, text) <= maxWidth) {
            return text;
        }
        float dots = fonts.width(f, "…");
        StringBuilder sb = new StringBuilder();
        float acc = 0f;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            float cw = fonts.width(f, new String(Character.toChars(cp)));
            if (acc + cw + dots > maxWidth) {
                break;
            }
            acc += cw;
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return sb.append("…").toString();
    }

    private void drawBottomLeftText(Roll p, LevelDirector dir) {
        if (L.compact()) {
            // 紧凑档：两行，第一行 HP + 名次，第二行 速度 + 道具槽。
            // 加速 / 决战 / 攻击的冷却已经写在各自按钮上（含扇形遮罩），这里不再重复。
            float row1 = L.status.top() - L.pad;
            float row2 = row1 - L.lineSmall;
            String l1 = (int) Math.ceil(p.hp) + " / 100   名次 " + p.rank + " / " + dir.rolls().size
                    + (p.isBlitz() ? "  狂暴" : "");
            font(fonts.small, C_TEXT, fit(fonts.small, l1, L.status.w - 2f * L.pad),
                    L.status.x + L.pad, row1);
            String l2 = "速度 " + String.format(java.util.Locale.ROOT, "%.1f", p.speed())
                    + " m/s" + (p.carried != null ? "   " + p.carried.cn : "   道具 无");
            font(fonts.small, C_DIM, fit(fonts.small, l2, L.status.w - 2f * L.pad),
                    L.status.x + L.pad, row2);
            return;
        }
        float y = 76f;
        font(fonts.small, C_TEXT, (int) Math.ceil(p.hp) + " / 100", 34f, y + 32f);
        String line = "名次 " + p.rank + " / " + dir.rolls().size
                + "   速度 " + String.format(java.util.Locale.ROOT, "%.1f", p.speed())
                + " m/s" + dashText(p)
                + (p.isBlitz() ? "   狂暴" : "");
        font(fonts.small, C_DIM, line, 350f, y + 32f);
        if (!cardVisible) {
            // 没拿道具时卡片不显示，这一行负责说明"道具槽是空的"
            font(fonts.small, C_DIM, "道具 无", 34f, y - 26f);
        }
        StringBuilder right = new StringBuilder();
        if (p.ultimate != null) {
            right.append("决战技 ").append(p.ultimate.cn).append("  ")
                    .append(p.ultimateCd > 0f
                            ? String.format(java.util.Locale.ROOT, "%.1fs", p.ultimateCd)
                            : "就绪");
        }
        // 普通攻击只在决赛关存在，所以提示也只在那时候出现 ——
        // 前四关挂一条"攻击 不可用"的灰字只会让人去按它。
        if (dir.attacks.enabled) {
            if (right.length() > 0) {
                right.append("     ");
            }
            right.append(attackText(p));
        }
        if (right.length() > 0) {
            font(fonts.small, C_DIM, right.toString(), 350f, y - 26f);
        }
    }

    /** 状态行里的普通攻击文字（只在决赛关被调用）。 */
    private String attackText(Roll p) {
        if (p.attackCd > 0f) {
            return String.format(java.util.Locale.ROOT, "攻击冷却 %.1fs", p.attackCd);
        }
        return "攻击就绪（J）";
    }

    /**
     * 把当前携带道具的**全部属性**排成道具卡：名称 / 稀有度 / 作用对象 / 持续 / 完整说明 / 梗出处。
     *
     * <p>以前只有一行 `道具 名字 — 说明`，而且不折行 —— 中文长句直接被屏幕右边缘切掉，
     * 玩家看到的属性是残缺的。现在说明按像素宽度折行，**一行都不许省**。
     *
     * <p>宽度与"梗出处显示几行"由 {@link HudLayout#planCard} 决定：
     * 手表的可用高度只有几行字，说明长的道具（三句话）和短的道具（一句话）差一倍，
     * 固定宽度必然要么浪费要么溢出。**属性一个字都不省**，省的是梗出处那几行。
     */
    private void prepareItemCard(Roll p) {
        cardVisible = false;
        cardDesc.clear();
        cardMeme.clear();
        if (p == null || p.carried == null) {
            return;
        }
        ItemType it = p.carried;
        cardTitle = it.cn;
        cardAttrs = "稀有度 " + it.rarity.cn
                + "    作用对象 " + targetingCn(it.targeting)
                + "    持续 " + (it.isInstant() ? "瞬时"
                        : String.format(java.util.Locale.ROOT, "%.1f 秒", it.duration))
                + (it.isDebuff() ? "    负面状态" : "");

        float lineH = fonts.lineHeight(fonts.small);
        HudLayout.CardPlan plan = L.measureCard(
                (text, maxWidth, out) -> {
                    Array<String> lines = fonts.wrap(fonts.small, text, maxWidth);
                    if (out != null) {
                        out.addAll(lines);
                    }
                    return lines.size;
                },
                cardAttrs, it.desc, "出处 " + it.meme, lineH);

        cardW = plan.width;
        float inner = cardW - 2f * L.pad;
        cardAttrLines.clear();
        cardAttrLines.addAll(fonts.wrap(fonts.small, cardAttrs, inner));
        cardDesc.clear();
        cardDesc.addAll(fonts.wrap(fonts.small, it.desc, inner));

        Array<String> meme = fonts.wrap(fonts.small, "出处 " + it.meme, inner);
        cardMeme.clear();
        int memeCap = plan.memeLines;
        for (int i = 0; i < meme.size && i < memeCap; i++) {
            String line = meme.get(i);
            if (i == memeCap - 1 && meme.size > memeCap) {
                line = line + "…";
            }
            cardMeme.add(line);
        }
        cardH = L.cardHeight(cardAttrLines.size, cardDesc.size, cardMeme.size, lineH);
        cardVisible = true;
    }

    private void drawItemCard(Roll p) {
        if (!cardVisible) {
            return;
        }
        ItemType it = p.carried;
        float titleH = fonts.lineHeight(fonts.medium);
        float lineH = fonts.lineHeight(fonts.small);
        float x = L.card.x + L.pad;
        float y = L.card.top() - L.pad;

        font(fonts.medium, C_DIM, "道具", x, y);
        float labelW = fonts.width(fonts.medium, "道具 ");
        font(fonts.medium, it.rarity.color, cardTitle, x + labelW, y);
        y -= titleH + 6f;

        for (int i = 0; i < cardAttrLines.size; i++) {
            font(fonts.small, C_DIM, cardAttrLines.get(i), x, y);
            y -= lineH;
        }
        y -= 8f;

        for (int i = 0; i < cardDesc.size; i++) {
            font(fonts.small, C_TEXT, cardDesc.get(i), x, y);
            y -= lineH;
        }
        if (cardMeme.size > 0) {
            y -= 8f;
            for (int i = 0; i < cardMeme.size; i++) {
                font(fonts.small, C_DIM, cardMeme.get(i), x, y);
                y -= lineH;
            }
        }
    }

    /**
     * 起点屏障倒计时。
     *
     * <p>屏障是"看不见摸不着但就是出不去"的东西，**必须给出倒计时**，
     * 否则玩家只会觉得"这游戏卡住了/我控制不了"（这正是上一轮踩过的坑）。
     */
    private void drawBarrierCountdown(LevelDirector dir) {
        if (!dir.isStartBarrierActive() || L.compact()) {
            // 紧凑档的倒计时写在顶栏第二行（见 drawTopLeft），不占画面中央
            return;
        }
        String s = String.format(java.util.Locale.ROOT, "起点封闭  %.1fs",
                dir.startBarrierRemaining());
        float tw = fonts.width(fonts.medium, s);
        font(fonts.medium, C_TEXT, s, (width - tw) * 0.5f, height - 18f);
    }

    /**
     * 触屏按钮上的文字标签。
     *
     * <p>原来四个按钮**只有颜色、没有字**，手机玩家根本分不出哪个是跳、
     * 哪个是道具 —— 只能靠试。这是安卓版必须补的一环。
     */
    private void drawButtonLabels(Roll p, boolean attackEnabled) {
        label(jumpBtn, "跳", C_TEXT);
        label(itemBtn, "道具", p.carried != null ? p.carried.rarity.color : C_DIM);
        label(ultBtn, "决战", p.ultimate != null ? p.ultimate.color : C_DIM);
        if (attackEnabled) {
            if (p.attackCd > 0f) {
                label(attackBtn, String.format(java.util.Locale.ROOT, "%.0f",
                        Math.ceil(p.attackCd)), C_DIM);
            } else {
                label(attackBtn, "攻击", C_TEXT);
            }
        }
        if (p.dashTimer > 0f) {
            label(dashBtn, "加速中", C_TEXT);
        } else if (p.dashCd > 0f) {
            label(dashBtn, String.format(java.util.Locale.ROOT, "%.0f", Math.ceil(p.dashCd)),
                    C_DIM);
        } else {
            label(dashBtn, "加速", C_TEXT);
        }
    }

    private void label(Vector2 btn, String text, Color c) {
        // 手表上按钮半径只有 27 上下，两字标签（宽 ~34）就已经贴边了 ——
        // 再小就退成单字（跳/道/决/加/攻），标签宁可少一个字，也不能糊到按钮外面。
        String t = text;
        if (L.compact() && L.btnRadius < 22f && text.length() > 1
                && fonts.width(fonts.small, text) > L.btnRadius * 1.7f) {
            t = text.substring(0, 1);
        }
        float w = fonts.width(fonts.small, t);
        float y = btn.y + fonts.lineHeight(fonts.small) * 0.34f;
        font(fonts.small, c, t, btn.x - w * 0.5f, y);
    }

    /** 状态行里的加速冷却文字。 */
    private String dashText(Roll p) {
        if (p.dashTimer > 0f) {
            return String.format(java.util.Locale.ROOT, "   加速 %.1fs", p.dashTimer);
        }
        if (p.dashCd > 0f) {
            return String.format(java.util.Locale.ROOT, "   加速冷却 %.1fs", p.dashCd);
        }
        return "   加速就绪";
    }

    private static String targetingCn(ItemType.Targeting t) {
        switch (t) {
            case SELF:
                return "自己";
            case NEAREST_AHEAD:
                return "前方最近的敌人";
            case AREA:
                return "以自己为中心的半径范围";
            default:
                return "未知";
        }
    }

    private String quotaText(LevelDirector dir) {
        int lvl = dir.state.levelIndex;
        switch (dir.state.mode) {
            case RACE: {
                // 名额的唯一来源是 LevelDirector.QUALIFIERS —— 别再在这里抄一份数字
                // （抄的那份在"第 3 关改成抽签单图"之后就已经过期了）。
                int q = lvl >= 1 && lvl <= LevelDirector.QUALIFIERS.length
                        ? LevelDirector.QUALIFIERS[lvl - 1] : 0;
                if (q <= 0) {
                    return "本关不淘汰";
                }
                int done = 0;
                Array<Roll> rolls = dir.rolls();
                for (int i = 0; i < rolls.size; i++) {
                    if (rolls.get(i).finished) {
                        done++;
                    }
                }
                return "到线 " + done + " / " + q;
            }
            case SURVIVAL:
                return "死亡 " + dir.state.stageDeaths + " / "
                        + GameConfig.L3_SURVIVAL_DEATH_THRESHOLD;
            default:
                return lvl >= 5 ? "只剩一人即胜" : "限时人头赛";
        }
    }

    private void drawFeed(LevelDirector dir) {
        Array<String> feed = dir.state.feed;
        if (feed.size == 0) {
            return;
        }
        if (L.compact()) {
            // 手表上播报与道具卡**二选一**：卡片优先（它说的是你手里那个东西的属性），
            // 否则两条文字必然在中间那点地方叠上（可用高度只有几行字）。
            if (cardVisible) {
                return;
            }
            float y = L.feed.top();
            int from = Math.max(0, feed.size - 2);
            for (int i = from; i < feed.size; i++) {
                float alpha = 0.45f + 0.55f * (i - from + 1) / (float) (feed.size - from);
                font(fonts.small, C_DIM.r, C_DIM.g, C_DIM.b, alpha,
                        fit(fonts.small, feed.get(i), L.feed.w), L.feed.x, y);
                y -= L.lineSmall;
            }
            return;
        }
        for (int i = 0; i < feed.size; i++) {
            float alpha = 0.35f + 0.65f * (i + 1) / (float) feed.size;
            font(fonts.small, C_DIM.r, C_DIM.g, C_DIM.b, alpha,
                    feed.get(i), L.feed.x, L.topLeft.y - 48f - (feed.size - 1 - i) * 26f);
        }
    }

    private void drawBanner(LevelDirector dir) {
        if (dir.state.bannerTimer <= 0f || dir.state.bannerText == null
                || dir.state.bannerText.isEmpty()) {
            return;
        }
        layout.setText(fonts.huge, dir.state.bannerText);
        float x = (width - layout.width) * 0.5f;
        // 紧凑档放在"道具卡顶"与"顶栏底"之间的空档里，别压住顶栏
        float y = L.compact() ? L.banner.y : height * 0.74f;
        font(fonts.huge, 0f, 0f, 0f, 0.55f, dir.state.bannerText, x + 3f, y - 3f);
        font(fonts.huge, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1f, dir.state.bannerText, x, y);
    }

    private void drawAutoplayHint() {
        String s = "自动演示中 · 按任意方向键或拖动摇杆即可立刻接管    F2 开关";
        layout.setText(fonts.medium, s);
        float x = (width - layout.width) * 0.5f;
        float y = height - 16f;
        font(fonts.medium, C_ACCENT.r, C_ACCENT.g, C_ACCENT.b, 1f, s, x, y);
    }

    // ================================================================== 工具

    private void font(BitmapFont f, Color c, String text, float x, float y) {
        font(f, c.r, c.g, c.b, 1f, text, x, y);
    }

    private void font(BitmapFont f, float r, float g, float b, float a,
                      String text, float x, float y) {
        f.setColor(r, g, b, a);
        f.draw(batch, text, x, y);
    }

    public void resizeProjection(int w, int h) {
        proj.setToOrtho2D(0f, 0f, w, h);
        batch.setProjectionMatrix(proj);
        shapes.setProjectionMatrix(proj);
    }

    private final Matrix4 proj = new Matrix4();

    public void beginText() {
        batch.begin();
    }

    public void endText() {
        batch.end();
    }

    public void centered(BitmapFont f, Color c, String text, float cx, float y) {
        layout.setText(f, text);
        font(f, c, text, cx - layout.width * 0.5f, y);
    }

    public BitmapFont font() {
        return fonts.medium;
    }

    public SpriteBatch batch() {
        return batch;
    }

    public void shapesBegin() {
        shapes.begin(ShapeRenderer.ShapeType.Filled);
    }

    public void shapesEnd() {
        shapes.end();
    }

    public void fillRect(float x, float y, float w, float h, Color c) {
        shapes.setColor(c);
        shapes.rect(x, y, w, h);
    }

    public void fillCircle(float x, float y, float r, Color c) {
        shapes.setColor(c);
        shapes.circle(x, y, r, 32);
    }

    /** 画一个带边框的卡片。 */
    public void card(float x, float y, float w, float h, Color fill, Color border,
                     boolean highlight) {
        shapes.setColor(fill);
        shapes.rect(x, y, w, h);
        shapes.setColor(border);
        float t = highlight ? 4f : 2f;
        shapes.rect(x, y, w, t);
        shapes.rect(x, y + h - t, w, t);
        shapes.rect(x, y, t, h);
        shapes.rect(x + w - t, y, t, h);
    }

    public void setColor(float r, float g, float b, float a) {
        shapes.setColor(r, g, b, a);
    }

    public Fonts fonts() {
        return fonts;
    }

    public GlyphLayout glyphLayout() {
        return layout;
    }

    @Override
    public void dispose() {
        batch.dispose();
        shapes.dispose();
    }
}
