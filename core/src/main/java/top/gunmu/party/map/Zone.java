package top.gunmu.party.map;

import com.badlogic.gdx.math.Vector3;

/**
 * 地面效果区域。位置固定在地图数据里，一局内不变。
 * 与"机关"（{@link Obstacle}，会动）区分开。
 */
public final class Zone {

    public enum Kind {
        MUD("泥坑", 0f),
        ICE("冰面", 0f),
        BOOST("加速带", 0f),
        BOUNCER("弹跳台", 0f),
        CONVEYOR("传送带", 0f),
        LAVA("熔岩", 8f),
        PIT("深渊", 999f);

        public final String cn;
        /** 站在上面每秒掉多少血。 */
        public final float dps;

        Kind(String cn, float dps) {
            this.cn = cn;
            this.dps = dps;
        }
    }

    public Kind kind;
    public final Vector3 center = new Vector3();
    public float halfX = 3f;
    public float halfZ = 3f;
    public float yaw;
    public boolean round;
    /** 圆形区域半径（round == true 时有效）。 */
    public float radius = 3f;
    /** 圆环内半径（> 0 时构成环带）。 */
    public float innerRadius = 0f;
    /** 推力方向（BOOST / CONVEYOR）。 */
    public float dirX = 1f;
    public float dirZ = 0f;
    /** 强度：加速度 / 切线速度 / 上抛初速。 */
    public float power = 14f;
    /** 以此为中心的局部地形类型覆盖（用于视觉与摩擦）。 */
    public boolean overridesTerrain;

    public float cosYaw = 1f;
    public float sinYaw = 0f;

    public Zone(Kind kind) {
        this.kind = kind;
    }

    public Zone at(float x, float y, float z) {
        center.set(x, y, z);
        return this;
    }

    public Zone size(float hx, float hz) {
        halfX = hx;
        halfZ = hz;
        round = false;
        return this;
    }

    public Zone circle(float r) {
        radius = r;
        round = true;
        innerRadius = 0f;
        return this;
    }

    /** 圆环带：内半径 inner、外半径 outer。 */
    public Zone ring(float inner, float outer) {
        innerRadius = inner;
        radius = outer;
        round = true;
        return this;
    }

    public Zone dir(float x, float z, float power) {
        float len = (float) Math.sqrt(x * x + z * z);
        if (len > 1e-5f) {
            dirX = x / len;
            dirZ = z / len;
        }
        this.power = power;
        return this;
    }

    public Zone yaw(float radians) {
        this.yaw = radians;
        cosYaw = (float) Math.cos(radians);
        sinYaw = (float) Math.sin(radians);
        return this;
    }

    /** 是否包含该 XZ 点（忽略 Y）。 */
    public boolean contains(float x, float z) {
        float dx = x - center.x;
        float dz = z - center.z;
        if (round) {
            float d2 = dx * dx + dz * dz;
            if (d2 > radius * radius) {
                return false;
            }
            return innerRadius <= 0f || d2 >= innerRadius * innerRadius;
        }
        float lx = dx * cosYaw - dz * sinYaw;
        float lz = dx * sinYaw + dz * cosYaw;
        return lx >= -halfX && lx <= halfX && lz >= -halfZ && lz <= halfZ;
    }
}
