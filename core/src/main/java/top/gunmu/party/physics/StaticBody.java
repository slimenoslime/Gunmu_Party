package top.gunmu.party.physics;

import com.badlogic.gdx.math.Vector3;

/**
 * 静态碰撞体：可绕 Y 旋转的长方体（OBB）。
 * 护栏、机关底座、场地围墙都由它构成。
 */
public final class StaticBody {

    public final Vector3 center = new Vector3();
    public final Vector3 half = new Vector3();
    public float yaw;
    public final Vector3 axisX = new Vector3(1f, 0f, 0f);
    public final Vector3 axisZ = new Vector3(0f, 0f, 1f);

    public StaticBody() {
    }

    public StaticBody(float cx, float cy, float cz, float hx, float hy, float hz, float yaw) {
        center.set(cx, cy, cz);
        half.set(hx, hy, hz);
        setYaw(yaw);
    }

    public void setYaw(float yawRad) {
        this.yaw = yawRad;
        float c = (float) Math.cos(yawRad);
        float s = (float) Math.sin(yawRad);
        axisX.set(c, 0f, -s);
        axisZ.set(s, 0f, c);
    }

    /** 世界坐标 → 局部坐标。 */
    public void toLocal(Vector3 world, Vector3 out) {
        float dx = world.x - center.x;
        float dy = world.y - center.y;
        float dz = world.z - center.z;
        out.x = dx * axisX.x + dz * axisX.z;
        out.y = dy;
        out.z = dx * axisZ.x + dz * axisZ.z;
    }

    /** 局部坐标 → 世界坐标。 */
    public void toWorld(Vector3 local, Vector3 out) {
        out.x = center.x + local.x * axisX.x + local.z * axisZ.x;
        out.y = center.y + local.y;
        out.z = center.z + local.x * axisX.z + local.z * axisZ.z;
    }
}
