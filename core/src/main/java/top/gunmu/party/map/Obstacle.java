package top.gunmu.party.map;

import com.badlogic.gdx.math.Vector3;

/**
 * 会动的机关。行为表见 docs/02-LEVELS-AND-MAPS.md §7。
 */
public final class Obstacle {

    public enum Kind {
        /** 绕 Y 匀速旋转的长杆，扫过造成伤害并击飞。 */
        HAMMER("旋转横杆锤", 18f),
        /** 沿两点往复移动的锯盘。 */
        SAW("锯盘", 12f),
        /** 周期伸出的推板，纯位移。 */
        PISTON("推板", 0f),
        /** 上下压的压板，伤害 + 短暂定身。 */
        CRUSHER("压板", 25f),
        /** 定时下落的滚石。 */
        BOULDER("落石", 22f),
        /** 区域持续推力。 */
        FAN("风扇", 0f),
        /** 站上被切向带动的旋转圆盘。 */
        SPINNER("旋转圆盘", 0f),
        /** 静态立柱，纯挡路。 */
        PILLAR("木桩", 0f);

        public final String cn;
        public final float damage;

        Kind(String cn, float damage) {
            this.cn = cn;
            this.damage = damage;
        }
    }

    public Kind kind;
    public final Vector3 pos = new Vector3();
    public float yaw;

    // ---- 通用尺寸 ----
    /** 作用半径 / 杆长 / 锯盘半径。 */
    public float radius = 3f;
    /** 宽度（柱体半径）。 */
    public float thickness = 0.35f;
    /** 高度。 */
    public float height = 1.2f;

    // ---- 运动参数 ----
    /** 角速度（rad/s，HAMMER / SPINNER）或线速度（m/s，SAW / PISTON）。 */
    public float speed = 1.5f;
    /** 周期（秒，PISTON / CRUSHER / BOULDER）。 */
    public float period = 2.5f;
    /** 行程（PISTON 伸出距离 / CRUSHER 下压距离 / BOULDER 起始高度）。 */
    public float travel = 2.5f;
    /** 往复路径方向（SAW）。 */
    public float pathDirX = 1f;
    public float pathDirZ = 0f;
    /** 推力方向（FAN）。 */
    public float dirX = 1f;
    public float dirZ = 0f;
    /** 力度（FAN 加速度）。 */
    public float force = 10f;
    /** 相位偏移，防止所有机关同步。 */
    public float phase;

    // ---- 运行时状态（由 ObstacleSystem 每帧更新） ----
    public float angle;
    public float t;
    public float current;      // PISTON 伸出量 / CRUSHER 下压量 / BOULDER 当前 Y
    /** 本帧的世界位置（会动的机关用这个，不动时等于 pos）。 */
    public final Vector3 worldPos = new Vector3();

    public Obstacle(Kind kind) {
        this.kind = kind;
    }

    public Obstacle at(float x, float y, float z) {
        pos.set(x, y, z);
        worldPos.set(x, y, z);
        return this;
    }

    public Obstacle yaw(float radians) {
        this.yaw = radians;
        return this;
    }

    public Obstacle radius(float r) {
        this.radius = r;
        return this;
    }

    public Obstacle speed(float s) {
        this.speed = s;
        return this;
    }

    public Obstacle period(float p) {
        this.period = p;
        return this;
    }

    public Obstacle travel(float t) {
        this.travel = t;
        return this;
    }

    public Obstacle thickness(float t) {
        this.thickness = t;
        return this;
    }

    public Obstacle height(float h) {
        this.height = h;
        return this;
    }

    public Obstacle dir(float x, float z) {
        float len = (float) Math.sqrt(x * x + z * z);
        if (len > 1e-5f) {
            dirX = x / len;
            dirZ = z / len;
            pathDirX = dirX;
            pathDirZ = dirZ;
        }
        return this;
    }

    public Obstacle force(float f) {
        this.force = f;
        return this;
    }

    public Obstacle phase(float p) {
        this.phase = p;
        return this;
    }

    /** 旋转类机关（HAMMER / SPINNER / SAW 的杆端位置）在世界空间的位置。 */
    public void tipPosition(Vector3 out) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        // 绕 Y 轴旋转
        out.set(pos.x + c * radius, pos.y, pos.z + s * radius);
    }

    public boolean rotating() {
        return kind == Kind.HAMMER || kind == Kind.SPINNER;
    }
}
