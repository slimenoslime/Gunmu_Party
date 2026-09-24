package top.gunmu.party.android;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import top.gunmu.party.GunmuPartyGame;

/**
 * 安卓入口。
 *
 * <p>与桌面入口（{@code Lwjgl3Launcher}）共享同一份 {@code core}，
 * 唯一区别是构造 {@link GunmuPartyGame} 时把 {@code mobile} 置为 true ——
 * 它会让 HUD 默认开启触屏摇杆、按屏幕高度放大 UI、并接住返回键。
 *
 * <p>本作是纯离线单机派对游戏，不申请任何权限。
 */
public final class AndroidLauncher extends AndroidApplication {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.useRotationVectorSensor = false;
        // GL20 覆盖面最广；游戏本身只用最基础的 3D 管线
        config.useGL30 = false;
        config.useWakelock = true;
        // 沉浸式全屏：隐藏状态栏/导航栏（libGDX 自己会处理 API 差异）
        config.useImmersiveMode = true;
        // 刘海屏/挖孔屏：让渲染铺满整个屏幕，不做黑边裁剪
        config.renderUnderCutout = true;
        // 关掉 MSAA：中低端机在 30 根滚木同屏时更需要稳定帧率
        config.numSamples = 0;
        // 本作暂无音频，省掉一路 OpenAL/AAudio 初始化
        config.disableAudio = true;
        config.maxSimultaneousSounds = 4;
        config.r = 8;
        config.g = 8;
        config.b = 8;
        config.a = 8;
        config.depth = 16;
        config.stencil = 0;

        // 保持屏幕常亮（横屏跑酷式操作，误触息屏体验很差）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initialize(new GunmuPartyGame(true), config);

        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    /** 尽力隐藏状态栏与导航栏。失败也不影响游戏（libGDX 自己也会隐藏一次）。 */
    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View decor = getWindow().getDecorView();
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable t) {
            android.util.Log.w("GunmuParty", "无法进入沉浸式全屏: " + t.getMessage());
        }
    }
}
