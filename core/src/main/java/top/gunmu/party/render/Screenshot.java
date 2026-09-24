package top.gunmu.party.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;

/**
 * 把帧缓冲按固定间隔存成 PNG，用于"实际看一眼画面"。
 *
 * <p>存在的理由：相机把玩家框到屏幕外、W 键在往后退、地形只剩一条缝——
 * 这类问题**断言抓不到**，只有看图才知道。之前就是因为只跑无头断言，
 * 交付了一个视角完全错的东西。
 *
 * <p>用法：
 * <pre>
 *   -Dgunmu.shotdir=D:/tmp/shots -Dgunmu.shotcount=8 -Dgunmu.shotinterval=1.5
 * </pre>
 * 第 0 张在开局后 {interval} 秒落盘，之后每隔 {interval} 秒一张。
 */
public final class Screenshot {

    private static String dir;
    private static int count;
    private static float interval = 1.5f;
    private static float timer;
    private static int index;

    private Screenshot() {
    }

    public static void init() {
        dir = System.getProperty("gunmu.shotdir");
        if (dir == null || dir.isEmpty()) {
            return;
        }
        count = Integer.getInteger("gunmu.shotcount", 6);
        interval = Float.parseFloat(System.getProperty("gunmu.shotinterval", "1.5"));
        Gdx.app.log("Shot", "抓帧已开启 -> " + dir + "，共 " + count + " 张，间隔 "
                + interval + "s");
    }

    public static boolean active() {
        return dir != null && index < count;
    }

    /** 每帧渲染结束后调用（此时后台缓冲里才是完整画面）。 */
    public static void maybeCapture(float delta) {
        if (!active()) {
            return;
        }
        timer += delta;
        if (timer < interval) {
            return;
        }
        timer = 0f;
        capture();
    }

    private static void capture() {
        int w = Gdx.graphics.getBackBufferWidth();
        int h = Gdx.graphics.getBackBufferHeight();
        Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, w, h);
        // glReadPixels 的原点在左下角，直接存出来是上下颠倒的，这里翻回来
        Pixmap flipped = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        for (int y = 0; y < h; y++) {
            flipped.drawPixmap(raw, 0, h - 1 - y, 0, y, w, 1);
        }
        String path = dir + "/shot-" + index + ".png";
        try {
            PixmapIO.writePNG(Gdx.files.absolute(path), flipped);
            Gdx.app.log("Shot", "已保存 " + path);
        } catch (Throwable t) {
            Gdx.app.error("Shot", "保存失败 " + path + " -> " + t.getMessage());
        }
        flipped.dispose();
        raw.dispose();
        index++;
    }
}
