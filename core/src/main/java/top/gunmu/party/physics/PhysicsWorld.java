package top.gunmu.party.physics;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.DamageSource;
import top.gunmu.party.core.Roll;
import top.gunmu.party.core.TerrainType;
import top.gunmu.party.map.Zone;

/**
 * 确定性固定步长物理世界（docs/06-DECISIONS.md D2）。
 *
 * <p>硬性约束：
 * <ul>
 *   <li>只接受固定 dt = {@link GameConfig#FIXED_DT}。</li>
 *   <li>实体遍历顺序 = 数组顺序（外部保证为 ID 升序），不依赖任何哈希迭代。</li>
 *   <li>本类**只产生事件，不结算伤害**——伤害由 Combat 按统一优先级处理。</li>
 * </ul>
 */
public final class PhysicsWorld implements top.gunmu.party.items.ItemSystem.PhysicsWorldView {

    /** 滚木之间的碰撞事件。 */
    public static final class CollisionEvent {
        public Roll a;
        public Roll b;
        public final Vector3 normal = new Vector3();
        /** 沿法线的接近速度（> 0 表示正在靠近）。 */
        public float closingSpeed;
        /** 双方速度差的大小，用于伤害判定。 */
        public float relSpeed;
    }

    /** 需要结算的伤害事件。 */
    public static final class DamageEvent {
        public Roll victim;
        public Roll attacker;
        public float amount;
        public DamageSource source;
    }

    public final HeightField field;
    public final Array<StaticBody> statics = new Array<>();
    /** 地图固有区域（一局内不变）。 */
    public final Array<Zone> zones = new Array<>();
    /** 运行时临时区域（道具放置的冰面等），由 ItemSystem 维护。 */
    public final Array<Zone> tempZones = new Array<>();

    public final Array<CollisionEvent> collisions = new Array<>();
    public final Array<DamageEvent> damageEvents = new Array<>();

    /** 掉出地图的判定高度。 */
    public float mapFloorY = -60f;

    /** 累计成功起跳次数。自检要靠它断言"跳跃在整局里真的发生过"。 */
    public int jumpCount;

    /** 累计成功加速次数。自检靠它断言加速在整局里真的发生过。 */
    public int dashCount;

    // ---------------------------------------------------------------- 起点屏障

    /** 起点屏障是否生效（开局的一段时间内把所有人关在起点区域里）。 */
    public boolean startBarrierActive;
    /** 起点屏障中心（水平投影；屏障视为无限高的圆柱，跳不过去）。 */
    public final Vector3 startBarrierCenter = new Vector3();
    /** 起点屏障半径（米）。 */
    public float startBarrierRadius;

    /** 配置本次子图的起点屏障。 */
    public void setStartBarrier(boolean active, float cx, float cz, float radius) {
        startBarrierActive = active;
        startBarrierCenter.set(cx, 0f, cz);
        startBarrierRadius = radius;
    }

    /**
     * 起点屏障：把滚木夹在圆柱内。
     *
     * <p>实现成"位置钳制 + 抹掉朝外的速度分量"，而不是弹开 ——
     * 弹开会把人反复推来推去；只抹掉朝外的分量，则贴着屏障走的手感和贴着墙一样，
     * 而沿切向的速度完全保留，所以在里面想怎么绕就怎么绕（这正是"可以随便移动"）。
     *
     * <p>屏障**不分高度**：跳起来也出不去。否则会有人靠跳/被弹飞越狱。
     */
    private void resolveStartBarrier(Roll r) {
        if (!startBarrierActive || startBarrierRadius <= 0f) {
            return;
        }
        float dx = r.pos.x - startBarrierCenter.x;
        float dz = r.pos.z - startBarrierCenter.z;
        float limit = startBarrierRadius - GameConfig.LOG_COLLIDE_RADIUS;
        if (limit <= 0.1f) {
            return;
        }
        float d2 = dx * dx + dz * dz;
        if (d2 <= limit * limit) {
            return;
        }
        float d = (float) Math.sqrt(d2);
        if (d < 1e-4f) {
            return;
        }
        float nx = dx / d;
        float nz = dz / d;
        r.pos.x = startBarrierCenter.x + nx * limit;
        r.pos.z = startBarrierCenter.z + nz * limit;
        float vn = r.vel.x * nx + r.vel.z * nz;
        if (vn > 0f) {
            r.vel.x -= nx * vn;
            r.vel.z -= nz * vn;
        }
    }
    /** 涨水水位（{@code -1e8} 以下表示无水）。 */
    public float waterLevel = -1e9f;
    /** 收缩毒雾环半径（< 0 表示无环）。 */
    public float poisonRadius = -1f;
    public final Vector3 poisonCenter = new Vector3();

    private final Vector3 tmpDir = new Vector3();
    private final Vector3 tmpNormal = new Vector3();
    private final Vector3 tmpLocal = new Vector3();
    private final Vector3 tmpDelta = new Vector3();
    private final Vector3 tmpAxis = new Vector3();

    private final Array<CollisionEvent> collisionPool = new Array<>();
    private final Array<DamageEvent> damagePool = new Array<>();
    private int collisionCursor;
    private int damageCursor;

    /** 本帧该滚木所在区域覆盖的摩擦（< 0 表示用地面材质）。 */
    private float zoneFriction;
    private boolean groundSuppressed;

    public PhysicsWorld(HeightField field) {
        this.field = field;
    }

    // ================================================================== ItemSystem 视图

    @Override
    public void setTempZones(Array<Zone> zones) {
        tempZones.clear();
        tempZones.addAll(zones);
    }

    @Override
    public float groundHeight(float x, float z) {
        return field.walkableAt(x, z) ? field.heightAt(x, z) : -999f;
    }

    @Override
    public boolean walkable(float x, float z) {
        return field.walkableAt(x, z);
    }

    // ================================================================== 主步进

    public void step(Array<Roll> rolls, float dt) {
        collisions.clear();
        damageEvents.clear();
        collisionCursor = 0;
        damageCursor = 0;

        for (int i = 0; i < rolls.size; i++) {
            stepOne(rolls.get(i), dt);
        }
        for (int i = 0; i < rolls.size; i++) {
            Roll a = rolls.get(i);
            if (!active(a)) {
                continue;
            }
            for (int j = i + 1; j < rolls.size; j++) {
                Roll b = rolls.get(j);
                if (!active(b)) {
                    continue;
                }
                collidePair(a, b);
            }
        }
    }

    private boolean active(Roll r) {
        return r.onField();
    }

    // ================================================================== 单体

    private void stepOne(Roll r, float dt) {
        if (!r.onField()) {
            r.vel.setZero();
            return;
        }
        if (r.finished) {
            // 到线后缓慢减速，避免冲出去撞人
            float damp = MathUtils.clamp(1f - 4f * dt, 0f, 1f);
            r.vel.x *= damp;
            r.vel.z *= damp;
        }

        sampleZones(r);

        // ---- 输入方向（世界空间） ----
        tmpDir.set(r.input.move.x, 0f, r.input.move.z);
        if (r.invertTimer > 0f) {
            tmpDir.x = -tmpDir.x;
        }
        float len = tmpDir.len();
        float strength = Math.min(1f, len);
        if (len > 1e-5f) {
            tmpDir.scl(1f / len);
        } else {
            tmpDir.setZero();
            strength = 0f;
        }
        if (!r.canMove() || r.finished) {
            strength = 0f;
            tmpDir.setZero();
        }

        // ---- 长轴对齐 + 滚动惩罚 ----
        float alignPenalty = 1f;
        if (r.grounded) {
            if (strength > 0.01f) {
                alignAxis(r, tmpDir, dt);
                alignPenalty = 1f - GameConfig.LONG_AXIS_PENALTY
                        * Math.abs(r.longAxis.dot(tmpDir));
            } else {
                float sx = r.vel.x;
                float sz = r.vel.z;
                float l2 = sx * sx + sz * sz;
                if (l2 > 0.25f) {
                    tmpAxis.set(sx, 0f, sz).scl(1f / (float) Math.sqrt(l2));
                    alignAxis(r, tmpAxis, dt);
                }
            }
        }

        // ---- 跳跃 ----
        // `input.jump` 是**边沿**信号（Hud / BotBrain 只在按下的那一帧置位），
        // 所以这里读到就立刻消费掉，否则一次按键会变成"每次都跳"。
        //
        // ⚠️ 这一段以前**整段缺失**：输入被写进来，却没有任何地方读它
        // （对比 `useItem` → ItemSystem、`useUltimate` → UltimateSystem 都是读+清）。
        // 症状就是"按空格完全没反应，跳跃压根没接线"，而编译与冒烟测试全绿。
        if (r.input.jump) {
            r.input.jump = false;
            if (r.canJump()) {
                jumpCount++;
                r.vel.y = GameConfig.JUMP_VELOCITY * r.jumpMultiplier();
                // 起跳当帧就脱离地面。不置 false 的话，本帧末尾的 resolveGround
                // 可能又把它按回地面，表现为"跳不起来"。
                r.grounded = false;
            }
        }

        // ---- 加速（Shift）----
        // 与跳跃同一条规矩（D43）：边沿信号必须有唯一消费点，而且读到就清。
        if (r.input.dash) {
            r.input.dash = false;
            if (r.canDash()) {
                dashCount++;
                r.dashTimer = GameConfig.DASH_TIME;
                r.dashCd = GameConfig.DASH_COOLDOWN;
            }
        }

        // ---- 重力 ----
        r.vel.y -= GameConfig.GRAVITY * dt;

        float slopeGain = 0f;
        if (r.grounded && !groundSuppressed) {
            field.normalAt(r.pos.x, r.pos.z, tmpNormal);
            float gDotN = -GameConfig.GRAVITY * tmpNormal.y;
            float gTanX = -tmpNormal.x * gDotN;
            float gTanZ = -tmpNormal.z * gDotN;
            r.vel.x += gTanX * dt;
            r.vel.z += gTanZ * dt;

            float hx;
            float hz;
            if (strength > 0.01f) {
                hx = tmpDir.x;
                hz = tmpDir.z;
            } else {
                float hs = (float) Math.sqrt(r.vel.x * r.vel.x + r.vel.z * r.vel.z);
                if (hs > 0.4f) {
                    hx = r.vel.x / hs;
                    hz = r.vel.z / hs;
                } else {
                    hx = 0f;
                    hz = 0f;
                }
            }
            slopeGain = MathUtils.clamp(
                    (gTanX * hx + gTanZ * hz) / GameConfig.GRAVITY, 0f, 1.2f);

            if (strength > 0f) {
                float accel = GameConfig.MOVE_ACCEL * r.accelMultiplier();
                r.vel.x += tmpDir.x * accel * strength * dt;
                r.vel.z += tmpDir.z * accel * strength * dt;
            }

            float friction = zoneFriction >= 0f
                    ? zoneFriction
                    : field.materialAt(r.pos.x, r.pos.z).friction;
            if (zoneFriction < 0f && r.ignoreTerrainFriction() && friction > 0.3f) {
                friction = TerrainType.GRASS.friction;
            }
            float damp = MathUtils.clamp(1f - friction * dt, 0f, 1f);
            r.vel.x *= damp;
            r.vel.z *= damp;
        } else if (strength > 0f) {
            float accel = GameConfig.MOVE_ACCEL * GameConfig.AIR_CONTROL * r.accelMultiplier();
            r.vel.x += tmpDir.x * accel * strength * dt;
            r.vel.z += tmpDir.z * accel * strength * dt;
        }

        // ---- 速度上限 ----
        float capLo = GameConfig.FLAT_MAX_SPEED;
        float capHi = r.maxSpeed();
        float slopeT = MathUtils.clamp(slopeGain / GameConfig.SLOPE_NORM, 0f, 1f);
        float cap = (capLo + (capHi - capLo) * slopeT) * r.speedMultiplier() * alignPenalty;
        if (cap < 0.01f) {
            cap = 0.01f;
        }
        float hSpeed = (float) Math.sqrt(r.vel.x * r.vel.x + r.vel.z * r.vel.z);
        if (hSpeed > cap) {
            float s = cap / hSpeed;
            r.vel.x *= s;
            r.vel.z *= s;
        }

        // ---- 狂暴态转向变钝 ----
        if (r.grounded && r.isBlitz() && strength > 0.01f) {
            float sp = (float) Math.sqrt(r.vel.x * r.vel.x + r.vel.z * r.vel.z);
            if (sp > 1f) {
                float vx = r.vel.x / sp;
                float vz = r.vel.z / sp;
                float k = 1f - GameConfig.BLITZ_TURN_PENALTY;
                float nx = vx * k + tmpDir.x * (1f - k);
                float nz = vz * k + tmpDir.z * (1f - k);
                float nl = (float) Math.sqrt(nx * nx + nz * nz);
                if (nl > 1e-4f) {
                    r.vel.x = nx / nl * sp;
                    r.vel.z = nz / nl * sp;
                }
            }
        }

        // ---- 积分 ----
        r.pos.x += r.vel.x * dt;
        r.pos.y += r.vel.y * dt;
        r.pos.z += r.vel.z * dt;

        resolveGround(r);

        for (int i = 0; i < statics.size; i++) {
            resolveStatic(r, statics.get(i));
        }

        applyZoneEffects(r, dt);
        environmentHazards(r, dt);

        // 起点屏障放最后：不管前面被谁推、被什么弹，最终都被夹在起点区域内
        resolveStartBarrier(r);

        // ---- 滚动视觉角：dθ/dt = v⊥ / r，⊥ = ŷ × longAxis ----
        float perpX = -r.longAxis.z;
        float perpZ = r.longAxis.x;
        float vPerp = r.vel.x * perpX + r.vel.z * perpZ;
        r.rollAngle += vPerp / GameConfig.LOG_RADIUS * dt;

        r.airTime = r.grounded ? 0f : r.airTime + dt;
        r.groundTime = r.grounded ? r.groundTime + dt : 0f;
    }

    private void alignAxis(Roll r, Vector3 dir, float dt) {
        float tx = dir.z;
        float tz = -dir.x;
        if (r.longAxis.x * tx + r.longAxis.z * tz < 0f) {
            tx = -tx;
            tz = -tz;
        }
        float ang = MathUtils.atan2(r.longAxis.z, r.longAxis.x);
        float tgt = MathUtils.atan2(tz, tx);
        float d = MathUtils.atan2(MathUtils.sin(tgt - ang), MathUtils.cos(tgt - ang));
        float maxStep = GameConfig.LONG_AXIS_ALIGN_RATE * dt;
        ang += MathUtils.clamp(d, -maxStep, maxStep);
        r.longAxis.set(MathUtils.cos(ang), 0f, MathUtils.sin(ang));
    }

    // ================================================================== 地面

    private void resolveGround(Roll r) {
        boolean walk = !groundSuppressed && field.walkableAt(r.pos.x, r.pos.z);
        if (!walk) {
            r.grounded = false;
            // 离开可行走面后不用真的掉到底，坠过深渊可视面几米就判死，避免长时间"卡在空中"
            float voidSurface = field.heightAt(r.pos.x, r.pos.z);
            if (r.pos.y < voidSurface - 4f || r.pos.y < mapFloorY) {
                emitDamage(r, null, GameConfig.MAX_HP, DamageSource.OUT_OF_MAP);
                r.pos.y = mapFloorY;
            }
            return;
        }
        float floor = field.heightAt(r.pos.x, r.pos.z) + GameConfig.LOG_RADIUS;
        // 上升中（刚起跳）绝不贴地。否则当这一帧的上升位移小于 0.06 的贴合余量时，
        // 起跳会被同一帧的这一步按回地面 —— 表现为"按了空格没反应"。
        // 用 `vel.y > 0` 而不是"离开地面多久"来判定，是因为原地站立、上下坡、贴地滚动
        // 时 vel.y 恒为 0 或负（见上面重力与贴地处理），不会误判。
        boolean rising = r.vel.y > 0.01f;
        if (!rising && r.pos.y <= floor + 0.06f) {
            if (!r.grounded && r.vel.y < -GameConfig.FALL_SAFE_SPEED) {
                float dmg = (-r.vel.y - GameConfig.FALL_SAFE_SPEED) * GameConfig.FALL_DAMAGE_SCALE;
                if (dmg > 0.5f) {
                    emitDamage(r, null, dmg, DamageSource.FALL);
                }
            }
            r.pos.y = floor;
            if (r.vel.y < 0f) {
                r.vel.y = 0f;
            }
            r.grounded = true;
        } else {
            r.grounded = false;
        }
    }

    // ================================================================== 静态体

    private void resolveStatic(Roll r, StaticBody b) {
        float radius = GameConfig.LOG_COLLIDE_RADIUS;
        b.toLocal(r.pos, tmpLocal);
        float hx = b.half.x;
        float hy = b.half.y;
        float hz = b.half.z;

        float cx = MathUtils.clamp(tmpLocal.x, -hx, hx);
        float cy = MathUtils.clamp(tmpLocal.y, -hy, hy);
        float cz = MathUtils.clamp(tmpLocal.z, -hz, hz);

        tmpDelta.set(tmpLocal.x - cx, tmpLocal.y - cy, tmpLocal.z - cz);
        float d2 = tmpDelta.len2();
        if (d2 > radius * radius) {
            return;
        }

        float nx;
        float ny;
        float nz;
        float push;
        if (d2 > 1e-8f) {
            float d = (float) Math.sqrt(d2);
            nx = tmpDelta.x / d;
            ny = tmpDelta.y / d;
            nz = tmpDelta.z / d;
            push = radius - d;
        } else {
            float px = hx - Math.abs(tmpLocal.x);
            float py = hy - Math.abs(tmpLocal.y);
            float pz = hz - Math.abs(tmpLocal.z);
            if (px <= py && px <= pz) {
                nx = tmpLocal.x >= 0f ? 1f : -1f;
                ny = 0f;
                nz = 0f;
                push = px + radius;
            } else if (py <= pz) {
                nx = 0f;
                ny = tmpLocal.y >= 0f ? 1f : -1f;
                nz = 0f;
                push = py + radius;
            } else {
                nx = 0f;
                ny = 0f;
                nz = tmpLocal.z >= 0f ? 1f : -1f;
                push = pz + radius;
            }
        }

        float wnx = nx * b.axisX.x + nz * b.axisZ.x;
        float wny = ny;
        float wnz = nx * b.axisX.z + nz * b.axisZ.z;

        r.pos.x += wnx * push * GameConfig.PENETRATION_SLOP;
        r.pos.y += wny * push * GameConfig.PENETRATION_SLOP;
        r.pos.z += wnz * push * GameConfig.PENETRATION_SLOP;

        float vn = r.vel.x * wnx + r.vel.y * wny + r.vel.z * wnz;
        if (vn < 0f) {
            float j = -(1f + GameConfig.STATIC_RESTITUTION) * vn;
            r.vel.x += wnx * j;
            r.vel.y += wny * j;
            r.vel.z += wnz * j;
            if (wny > 0.4f) {
                r.grounded = true;
            }
        }
    }

    // ================================================================== 互撞

    private void collidePair(Roll a, Roll b) {
        float radius = GameConfig.LOG_COLLIDE_RADIUS;
        float dx = b.pos.x - a.pos.x;
        float dy = b.pos.y - a.pos.y;
        float dz = b.pos.z - a.pos.z;
        float d2 = dx * dx + dy * dy + dz * dz;
        float rr = radius * 2f;
        if (d2 > rr * rr || d2 < 1e-9f) {
            return;
        }

        float d = (float) Math.sqrt(d2);
        float nx = dx / d;
        float ny = dy / d;
        float nz = dz / d;
        float push = (rr - d) * 0.5f * GameConfig.PENETRATION_SLOP;

        a.pos.x -= nx * push;
        a.pos.y -= ny * push;
        a.pos.z -= nz * push;
        b.pos.x += nx * push;
        b.pos.y += ny * push;
        b.pos.z += nz * push;

        float rvx = b.vel.x - a.vel.x;
        float rvy = b.vel.y - a.vel.y;
        float rvz = b.vel.z - a.vel.z;
        float vn = rvx * nx + rvy * ny + rvz * nz;

        CollisionEvent e = obtainCollision();
        e.a = a;
        e.b = b;
        e.normal.set(nx, ny, nz);
        e.closingSpeed = -vn;
        e.relSpeed = (float) Math.sqrt(rvx * rvx + rvy * rvy + rvz * rvz);

        if (vn > 0f) {
            return;
        }
        float j = -(1f + GameConfig.RESTITUTION) * vn * 0.5f;
        a.vel.x -= nx * j * a.knockbackMultiplier();
        a.vel.y -= ny * j * a.knockbackMultiplier();
        a.vel.z -= nz * j * a.knockbackMultiplier();
        b.vel.x += nx * j * b.knockbackMultiplier();
        b.vel.y += ny * j * b.knockbackMultiplier();
        b.vel.z += nz * j * b.knockbackMultiplier();
    }

    // ================================================================== 区域

    private void sampleZones(Roll r) {
        zoneFriction = -1f;
        groundSuppressed = false;
        for (int pass = 0; pass < 2; pass++) {
            Array<Zone> src = pass == 0 ? zones : tempZones;
            for (int i = 0; i < src.size; i++) {
                Zone z = src.get(i);
                if (!z.contains(r.pos.x, r.pos.z)) {
                    continue;
                }
                if (Math.abs(r.pos.y - z.center.y) > 8f) {
                    continue;
                }
                switch (z.kind) {
                    case MUD:
                        zoneFriction = zoneFriction < 0f ? 3.0f : Math.max(zoneFriction, 3.0f);
                        break;
                    case ICE:
                        zoneFriction = zoneFriction < 0f ? 0.35f : Math.min(zoneFriction, 0.35f);
                        break;
                    case PIT:
                        groundSuppressed = true;
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void applyZoneEffects(Roll r, float dt) {
        for (int pass = 0; pass < 2; pass++) {
            Array<Zone> src = pass == 0 ? zones : tempZones;
            for (int i = 0; i < src.size; i++) {
                Zone z = src.get(i);
                if (!z.contains(r.pos.x, r.pos.z)) {
                    continue;
                }
                if (Math.abs(r.pos.y - z.center.y) > 8f) {
                    continue;
                }
                switch (z.kind) {
                    case BOOST:
                        r.vel.x += z.dirX * z.power * dt;
                        r.vel.z += z.dirZ * z.power * dt;
                        break;
                    case CONVEYOR:
                        float k = Math.min(1f, 6f * dt);
                        r.vel.x += (z.dirX * z.power - r.vel.x) * k;
                        r.vel.z += (z.dirZ * z.power - r.vel.z) * k;
                        break;
                    case BOUNCER:
                        if (field.walkableAt(r.pos.x, r.pos.z)
                                && r.pos.y - GameConfig.LOG_RADIUS - field.heightAt(r.pos.x, r.pos.z) < 0.4f
                                && r.vel.y < z.power * 0.5f) {
                            r.vel.y = z.power;
                            r.grounded = false;
                            r.pos.y += 0.08f;
                        }
                        break;
                    case LAVA:
                        emitDamage(r, null, z.kind.dps * dt, DamageSource.LAVA);
                        break;
                    case PIT:
                        if (r.pos.y < z.center.y - GameConfig.OUT_OF_MAP_DEPTH) {
                            emitDamage(r, null, GameConfig.MAX_HP, DamageSource.OUT_OF_MAP);
                            r.pos.y = z.center.y - 6f;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void environmentHazards(Roll r, float dt) {
        if (r.pos.y < mapFloorY) {
            emitDamage(r, null, GameConfig.MAX_HP, DamageSource.OUT_OF_MAP);
            r.pos.y = mapFloorY + 1f;
        }
        if (waterLevel > -1e8f && field.walkableAt(r.pos.x, r.pos.z)) {
            if (field.heightAt(r.pos.x, r.pos.z) < waterLevel) {
                emitDamage(r, null, 6f * dt, DamageSource.DROWN);
                r.addSlow(0.55f, 0.3f);
            }
        }
        if (poisonRadius >= 0f) {
            float dx = r.pos.x - poisonCenter.x;
            float dz = r.pos.z - poisonCenter.z;
            if (dx * dx + dz * dz > poisonRadius * poisonRadius) {
                emitDamage(r, null, 6f * dt, DamageSource.POISON);
            }
        }
    }

    // ================================================================== 事件池

    private void emitDamage(Roll victim, Roll attacker, float amount, DamageSource src) {
        if (amount <= 0f) {
            return;
        }
        DamageEvent e = obtainDamage();
        e.victim = victim;
        e.attacker = attacker;
        e.amount = amount;
        e.source = src;
        damageEvents.add(e);
    }

    private DamageEvent obtainDamage() {
        if (damageCursor < damagePool.size) {
            return damagePool.get(damageCursor++);
        }
        DamageEvent e = new DamageEvent();
        damagePool.add(e);
        damageCursor++;
        return e;
    }

    private CollisionEvent obtainCollision() {
        if (collisionCursor < collisionPool.size) {
            return collisionPool.get(collisionCursor++);
        }
        CollisionEvent e = new CollisionEvent();
        collisionPool.add(e);
        collisionCursor++;
        return e;
    }
}
