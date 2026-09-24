package top.gunmu.party.map;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;

import top.gunmu.party.core.TerrainType;

/**
 * 赛道路径：一条带宽度剖面与高度剖面的折线，是 RACE 地图地形的唯一来源。
 *
 * <p>同时充当"引导折线（Guide Path）"：已推进度按弧长投影计算，
 * 而不是按直线距离——否则弯道图会算错名次（docs/00 §4.4）。
 */
public final class Route {

    public static final class Node {
        public float x;
        public float y;
        public float z;
        /** 路面半宽。 */
        public float halfWidth;
        /** 路肩宽度：路面之外还能站多久，再往外就是深渊。 */
        public float shoulder;
        public TerrainType type = TerrainType.GRASS;
        /** 横向倾斜：正值表示 +lateral 一侧更高。 */
        public float bank;
        public boolean wallLeft;
        public boolean wallRight;
    }

    private final Array<Node> nodes = new Array<>();
    private final FloatArray cum = new FloatArray();
    public float totalLength;

    public Route add(float x, float y, float z, float halfWidth, float shoulder,
                     TerrainType type) {
        Node n = new Node();
        n.x = x;
        n.y = y;
        n.z = z;
        n.halfWidth = halfWidth;
        n.shoulder = shoulder;
        n.type = type;
        nodes.add(n);
        return this;
    }

    public Node last() {
        return nodes.peek();
    }

    public Node node(int i) {
        return nodes.get(i);
    }

    /** 直接访问节点表（构建期使用，例如插入起终点平台）。 */
    public Array<Node> nodes() {
        return nodes;
    }

    /**
     * 等比缩放路径使总弧长等于 {@code target}。
     * 用于保证同一模式内各图路程长度一致（docs/02 §6 公平性基线）。
     */
    public void scaleToLength(float target) {
        build();
        if (totalLength < 1e-3f) {
            return;
        }
        float k = target / totalLength;
        float cx = 0f;
        float cz = 0f;
        for (int i = 0; i < nodes.size; i++) {
            cx += nodes.get(i).x;
            cz += nodes.get(i).z;
        }
        cx /= nodes.size;
        cz /= nodes.size;
        for (int i = 0; i < nodes.size; i++) {
            Node n = nodes.get(i);
            n.x = cx + (n.x - cx) * k;
            n.z = cz + (n.z - cz) * k;
        }
        build();
    }

    public int nodeCount() {
        return nodes.size;
    }

    public int segmentCount() {
        return Math.max(0, nodes.size - 1);
    }

    public void build() {
        cum.clear();
        cum.add(0f);
        float total = 0f;
        for (int i = 1; i < nodes.size; i++) {
            Node a = nodes.get(i - 1);
            Node b = nodes.get(i);
            float dx = b.x - a.x;
            float dz = b.z - a.z;
            total += (float) Math.sqrt(dx * dx + dz * dz);
            cum.add(total);
        }
        totalLength = total;
    }

    public float arcAt(int segmentIndex, float t) {
        float a = cum.get(segmentIndex);
        float b = cum.get(segmentIndex + 1);
        return a + (b - a) * t;
    }

    /** 弧长 s 处的中心线位置。 */
    public void sampleAt(float s, Vector3 out) {
        if (nodes.size == 0) {
            out.setZero();
            return;
        }
        if (nodes.size == 1 || s <= 0f) {
            Node n = nodes.first();
            out.set(n.x, n.y, n.z);
            return;
        }
        if (s >= totalLength) {
            Node n = nodes.peek();
            out.set(n.x, n.y, n.z);
            return;
        }
        int seg = segmentIndexForArc(s);
        float a = cum.get(seg);
        float b = cum.get(seg + 1);
        float t = (b - a) > 1e-5f ? (s - a) / (b - a) : 0f;
        Node n0 = nodes.get(seg);
        Node n1 = nodes.get(seg + 1);
        out.set(MathUtils.lerp(n0.x, n1.x, t), MathUtils.lerp(n0.y, n1.y, t),
                MathUtils.lerp(n0.z, n1.z, t));
    }

    public int segmentIndexForArc(float s) {
        for (int i = 0; i < cum.size - 1; i++) {
            if (s <= cum.get(i + 1)) {
                return i;
            }
        }
        return cum.size - 2;
    }

    /**
     * 最近点查询。
     *
     * @param out 输出 [0]=段索引 [1]=段内 t [2]=带符号横向距离 [3]=弧长
     */
    public void nearest(float x, float z, float[] out) {
        int best = 0;
        float bestT = 0f;
        float bestLat = 0f;
        float bestD2 = Float.MAX_VALUE;

        for (int i = 0; i < nodes.size - 1; i++) {
            Node a = nodes.get(i);
            Node b = nodes.get(i + 1);
            float ax = a.x;
            float az = a.z;
            float bx = b.x;
            float bz = b.z;
            float dx = bx - ax;
            float dz = bz - az;
            float len2 = dx * dx + dz * dz;
            float t = 0f;
            if (len2 > 1e-6f) {
                t = ((x - ax) * dx + (z - az) * dz) / len2;
                t = MathUtils.clamp(t, 0f, 1f);
            }
            float px = ax + dx * t;
            float pz = az + dz * t;
            float ex = x - px;
            float ez = z - pz;
            float d2 = ex * ex + ez * ez;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
                bestT = t;
                // 带符号横向：路径方向的右手侧为正
                float nx = -dz;
                float nz = dx;
                float nl = (float) Math.sqrt(nx * nx + nz * nz);
                if (nl > 1e-5f) {
                    bestLat = (ex * nx + ez * nz) / nl;
                } else {
                    bestLat = 0f;
                }
            }
        }
        out[0] = best;
        out[1] = bestT;
        out[2] = bestLat;
        out[3] = arcAt(best, bestT);
    }

    /** 已推进度：弧长（米）。 */
    public float progressAt(float x, float z, float[] scratch) {
        nearest(x, z, scratch);
        return scratch[3];
    }

    /** 弧长 s 处的路径朝向（yaw，弧度）。 */
    public float yawAt(float s) {
        int seg = segmentIndexForArc(s);
        Node a = nodes.get(seg);
        Node b = nodes.get(Math.min(seg + 1, nodes.size - 1));
        return MathUtils.atan2(b.x - a.x, b.z - a.z);
    }

    /** 弧长 s、横向偏移 lateral（右侧为正）处的位置。 */
    public void pointAt(float s, float lateral, Vector3 out) {
        sampleAt(s, out);
        int seg = segmentIndexForArc(s);
        Node a = nodes.get(seg);
        Node b = nodes.get(Math.min(seg + 1, nodes.size - 1));
        float dx = b.x - a.x;
        float dz = b.z - a.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-5f) {
            return;
        }
        out.x += (-dz / len) * lateral;
        out.z += (dx / len) * lateral;
    }

    /** 弧长 s 处的路肩宽度。 */
    public float shoulderAt(float s) {
        int seg = segmentIndexForArc(s);
        Node a = nodes.get(seg);
        Node b = nodes.get(Math.min(seg + 1, nodes.size - 1));
        float sa = cum.get(seg);
        float sb = cum.get(seg + 1);
        float t = (sb - sa) > 1e-5f ? (s - sa) / (sb - sa) : 0f;
        return a.shoulder + (b.shoulder - a.shoulder) * t;
    }

    /**
     * <b>带高度的</b>最近点查询。与 {@link #nearest} 的唯一区别是把 y 也算进距离。
     *
     * <p>为什么需要它：{@link #nearest} 只看 xz，折返/叠层赛道（之字断崖）在俯视图上
     * 会有两段路靠得很近，只按水平距离找最近点会跳到"上一层/下一层"那一段，
     * 弧长瞬间跳一大截 —— 玩家报的「跳到虚空里能直接读到下面的存档点和通关区域」就是这个。
     *
     * <p>地形生成**继续用 xz 版本**（改它等于改所有地图的几何）。
     *
     * @param out 输出 [0]=段索引 [1]=段内 t [2]=带符号横向距离 [3]=弧长
     */
    public void nearestTrack(float x, float y, float z, float[] out) {
        int best = 0;
        float bestT = 0f;
        float bestLat = 0f;
        float bestD2 = Float.MAX_VALUE;

        for (int i = 0; i < nodes.size - 1; i++) {
            Node a = nodes.get(i);
            Node b = nodes.get(i + 1);
            float ax = a.x;
            float az = a.z;
            float bx = b.x;
            float bz = b.z;
            float dx = bx - ax;
            float dz = bz - az;
            float len2 = dx * dx + dz * dz;
            float t = 0f;
            if (len2 > 1e-6f) {
                t = ((x - ax) * dx + (z - az) * dz) / len2;
                t = MathUtils.clamp(t, 0f, 1f);
            }
            float px = ax + dx * t;
            float pz = az + dz * t;
            float py = a.y + (b.y - a.y) * t;
            float ex = x - px;
            float ey = y - py;
            float ez = z - pz;
            float d2 = ex * ex + ey * ey + ez * ez;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
                bestT = t;
                float nx = -dz;
                float nz = dx;
                float nl = (float) Math.sqrt(nx * nx + nz * nz);
                if (nl > 1e-5f) {
                    bestLat = (ex * nx + ez * nz) / nl;
                } else {
                    bestLat = 0f;
                }
            }
        }
        out[0] = best;
        out[1] = bestT;
        out[2] = bestLat;
        out[3] = arcAt(best, bestT);
    }

    /** 弧长 s 处的路面半宽。 */
    public float halfWidthAt(float s) {
        int seg = segmentIndexForArc(s);
        Node a = nodes.get(seg);
        Node b = nodes.get(Math.min(seg + 1, nodes.size - 1));
        float sa = cum.get(seg);
        float sb = cum.get(seg + 1);
        float t = (sb - sa) > 1e-5f ? (s - sa) / (sb - sa) : 0f;
        return a.halfWidth + (b.halfWidth - a.halfWidth) * t;
    }
}
