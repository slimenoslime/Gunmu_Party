package top.gunmu.party.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import top.gunmu.party.GunmuPartyGame;

/**
 * 桌面入口。
 *
 * <p>用法：
 * <pre>
 *   java -jar gunmu-party.jar
 *   java -Dgunmu.seed=12345 -jar gunmu-party.jar   // 固定种子做回归
 * </pre>
 */
public final class Lwjgl3Launcher {

    private Lwjgl3Launcher() {
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("滚木派对 Gunmu Party");
        config.setWindowedMode(1440, 810);
        config.setForegroundFPS(60);
        config.useVsync(true);
        config.setResizable(true);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);
        new Lwjgl3Application(new GunmuPartyGame(), config);
    }
}
