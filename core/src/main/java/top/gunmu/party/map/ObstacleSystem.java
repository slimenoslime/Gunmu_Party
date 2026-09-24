package top.gunmu.party.map;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Combat;
import top.gunmu.party.core.DamageSource;
import top.gunmu.party.core.Roll;

/**
 * 机关推进与判定。行为表见 docs/02-LEVELS-AND-MAPS.md §7。
 *
 * <p>瞬时伤害用 {@link Roll#hazardCd} 做公共冷却，避免一帧内被同一台机关刷成碎片；
 * 持续伤害（锯盘）按 {@code dps * dt} 结算。
 */
public final class ObstacleSystem {

    private static final float HIT_COOLDOWN = 0.65f;

    private final Vector3 tmp = new Vector3();

    public void tick(MapDef map, Array<Roll> rolls, Combat combat, float dt) {
        for (int i = 0; i < map.obstacles.size; i++) {
            Obstacle o = map.obstacles.get(i);
            switch (o.kind) {
                case HAMMER:
                    tickHammer(o, rolls, combat, dt);
                    break;
                case SAW:
                    tickSaw(o, rolls, combat, dt);
                    break;
                case PISTON:
                    tickPiston(o, rolls, dt);
                    break;
                case CRUSHER:
                    tickCrusher(o, rolls, combat, dt);
                    break;
                case BOULDER:
                    tickBoulder(o, rolls, combat, dt);
                    break;
                case FAN:
                    tickFan(o, rolls, dt);
                    break;
                case SPINNER:
                    tickSpinner(o, rolls, dt);
                    break;
                default:
                    o.worldPos.set(o.pos);
                    break;
            }
        }
    }

    // ================================================================== 旋转横杆锤

    private void tickHammer(Obstacle o, Array<Roll> rolls, Combat combat, float dt) {
        o.t += dt;
        o.angle = o.phase + o.t * o.speed;
        o.worldPos.set(o.pos);

        float cx = (float) Math.cos(o.angle);
        float cz = (float) Math.sin(o.angle);
        float ax = o.pos.x - cx * o.radius;
        float az = o.pos.z - cz * o.radius;
        float bx = o.pos.x + cx * o.radius;
        float bz = o.pos.z + cz * o.radius;

        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.alive || r.eliminated || r.respawnTimer > 0f || r.finished) {
                continue;
            }
            if (Math.abs(r.pos.y - o.pos.y) > o.height + 1.2f) {
                continue;
            }
            float px = closestParam(r.pos.x, r.pos.z, ax, az, bx, bz);
            float qx = ax + (bx - ax) * px;
            float qz = az + (bz - az) * px;
            float dx = r.pos.x - qx;
            float dz = r.pos.z - qz;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist > o.thickness + GameConfig.LOG_COLLIDE_RADIUS) {
                continue;
            }
            if (r.hazardCd > 0f) {
                continue;
            }
            r.hazardCd = HIT_COOLDOWN;
            combat.deal(r, null, o.kind.damage, DamageSource.OBSTACLE, 0);
            if (dist > 1e-4f) {
                r.vel.x += dx / dist * 11f;
                r.vel.z += dz / dist * 11f;
            }
            r.vel.y = Math.max(r.vel.y, 5.5f);
            r.grounded = false;
        }
    }

    private static float closestParam(float px, float pz, float ax, float az,
                                      float bx, float bz) {
        float dx = bx - ax;
        float dz = bz - az;
        float len2 = dx * dx + dz * dz;
        if (len2 < 1e-5f) {
            return 0f;
        }
        return MathUtils.clamp(((px - ax) * dx + (pz - az) * dz) / len2, 0f, 1f);
    }

    // ================================================================== 锯盘

    private void tickSaw(Obstacle o, Array<Roll> rolls, Combat combat, float dt) {
        o.t += dt;
        float span = Math.max(1f, o.travel);
        float norm = (o.t * o.speed / span + o.phase) % 2f;
        float tri = Math.abs(norm - 1f);
        o.current = (tri - 0.5f) * span;
        o.angle += dt * 6f;
        o.worldPos.set(o.pos.x + o.pathDirX * o.current, o.pos.y,
                o.pos.z + o.pathDirZ * o.current);

        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.alive || r.eliminated || r.respawnTimer > 0f) {
                continue;
            }
            float dx = r.pos.x - o.worldPos.x;
            float dz = r.pos.z - o.worldPos.z;
            float rr = o.radius + GameConfig.LOG_COLLIDE_RADIUS;
            if (dx * dx + dz * dz > rr * rr) {
                continue;
            }
            if (Math.abs(r.pos.y - o.worldPos.y) > 2.2f) {
                continue;
            }
            combat.deal(r, null, o.kind.damage * dt, DamageSource.OBSTACLE, 0);
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > 1e-4f) {
                r.vel.x += dx / d * 6f * dt;
                r.vel.z += dz / d * 6f * dt;
            }
            r.addSlow(0.75f, 0.2f);
        }
    }

    // ================================================================== 推板

    private void tickPiston(Obstacle o, Array<Roll> rolls, float dt) {
        o.t += dt;
        float ph = (o.t / Math.max(0.3f, o.period) + o.phase) % 1f;
        // 0.15 快速伸出，0.55 保持，0.30 收回
        float reach;
        if (ph < 0.15f) {
            reach = ph / 0.15f;
        } else if (ph < 0.70f) {
            reach = 1f;
        } else {
            reach = 1f - (ph - 0.70f) / 0.30f;
        }
        o.current = reach * o.travel;
        o.worldPos.set(o.pos.x + o.dirX * o.current, o.pos.y, o.pos.z + o.dirZ * o.current);

        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.alive || r.eliminated || r.respawnTimer > 0f) {
                continue;
            }
            float dx = r.pos.x - o.worldPos.x;
            float dz = r.pos.z - o.worldPos.z;
            float rr = o.radius + GameConfig.LOG_COLLIDE_RADIUS;
            if (dx * dx + dz * dz > rr * rr) {
                continue;
            }
            if (Math.abs(r.pos.y - o.worldPos.y) > 1.8f) {
                continue;
            }
            // 推板只推不伤
            float push = 26f * dt * (0.4f + reach);
            r.vel.x += o.dirX * push;
            r.vel.z += o.dirZ * push;
        }
    }

    // ================================================================== 压板

    private void tickCrusher(Obstacle o, Array<Roll> rolls, Combat combat, float dt) {
        o.t += dt;
        float ph = (o.t / Math.max(0.3f, o.period) + o.phase) % 1f;
        float down;
        if (ph < 0.35f) {
            down = 0f;
        } else if (ph < 0.55f) {
            down = (ph - 0.35f) / 0.20f;
        } else if (ph < 0.75f) {
            down = 1f;
        } else {
            down = 1f - (ph - 0.75f) / 0.25f;
        }
        o.current = down;
        o.worldPos.set(o.pos.x, o.pos.y - down * o.travel, o.pos.z);

        if (down < 0.7f) {
            return;
        }
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.alive || r.eliminated || r.respawnTimer > 0f) {
                continue;
            }
            float dx = r.pos.x - o.pos.x;
            float dz = r.pos.z - o.pos.z;
            if (dx * dx + dz * dz > o.radius * o.radius) {
                continue;
            }
            if (r.pos.y > o.worldPos.y + 1.0f || r.pos.y < o.worldPos.y - 6f) {
                continue;
            }
            if (r.hazardCd > 0f) {
                continue;
            }
            r.hazardCd = HIT_COOLDOWN;
            combat.deal(r, null, o.kind.damage, DamageSource.OBSTACLE, 0);
            r.addRoot(0.5f);
            r.vel.y = Math.min(r.vel.y, 0f);
        }
    }

    // ================================================================== 落石

    private void tickBoulder(Obstacle o, Array<Roll> rolls, Combat combat, float dt) {
        o.t += dt;
        float ph = (o.t / Math.max(0.5f, o.period) + o.phase) % 1f;
        if (ph < 0.25f) {
            o.current = 0f;
        } else if (ph < 0.55f) {
            o.current = (ph - 0.25f) / 0.30f;
        } else {
            o.current = 1f;
        }
        float y = o.pos.y + o.travel - o.current * o.travel * 1.6f;
        o.worldPos.set(o.pos.x, y, o.pos.z);

        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.alive || r.eliminated || r.respawnTimer > 0f) {
                continue;
            }
            float dx = r.pos.x - o.worldPos.x;
            float dy = r.pos.y - o.worldPos.y;
            float dz = r.pos.z - o.worldPos.z;
            float rr = o.radius + GameConfig.LOG_COLLIDE_RADIUS;
            if (dx * dx + dy * dy + dz * dz > rr * rr) {
                continue;
            }
            if (r.hazardCd > 0f) {
                continue;
            }
            r.hazardCd = HIT_COOLDOWN;
            combat.deal(r, null, o.kind.damage, DamageSource.OBSTACLE, 0);
        }
    }

    // ================================================================== 风扇

    private void tickFan(Obstacle o, Array<Roll> rolls, float dt) {
        o.t += dt;
        o.worldPos.set(o.pos);
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.alive || r.eliminated || r.respawnTimer > 0f) {
                continue;
            }
            float dx = r.pos.x - o.pos.x;
            float dz = r.pos.z - o.pos.z;
            if (dx * dx + dz * dz > o.radius * o.radius) {
                continue;
            }
            if (Math.abs(r.pos.y - o.pos.y) > 4.5f) {
                continue;
            }
            r.vel.x += o.dirX * o.force * dt;
            r.vel.z += o.dirZ * o.force * dt;
        }
    }

    // ================================================================== 旋转圆盘

    private void tickSpinner(Obstacle o, Array<Roll> rolls, float dt) {
        o.t += dt;
        o.angle = o.phase + o.t * o.speed;
        o.worldPos.set(o.pos);
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.alive || r.eliminated || r.respawnTimer > 0f) {
                continue;
            }
            float dx = r.pos.x - o.pos.x;
            float dz = r.pos.z - o.pos.z;
            float d2 = dx * dx + dz * dz;
            if (d2 > o.radius * o.radius || d2 < 1e-4f) {
                continue;
            }
            if (Math.abs(r.pos.y - o.pos.y) > 2.5f) {
                continue;
            }
            float d = (float) Math.sqrt(d2);
            // 切向单位向量
            float tx = -dz / d;
            float tz = dx / d;
            float tangential = o.speed * d;
            float k = Math.min(1f, 8f * dt);
            r.vel.x += (tx * tangential - r.vel.x) * k * 0.5f;
            r.vel.z += (tz * tangential - r.vel.z) * k * 0.5f;
        }
    }

    /** 供渲染使用：机关当前的世界位置。 */
    public static void worldPositionOf(Obstacle o, Vector3 out) {
        out.set(o.worldPos);
    }
}
