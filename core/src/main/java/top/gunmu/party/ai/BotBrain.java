package top.gunmu.party.ai;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import java.util.Random;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Mode;
import top.gunmu.party.core.Roll;
import top.gunmu.party.items.ItemSystem;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.Obstacle;
import top.gunmu.party.ultimates.UltimateSystem;

/**
 * AI 大脑。只往 {@link Roll#input} 里写，与真人走完全同一套输入接口——
 * 不做任何直接改状态的操作（docs/06 D20）。
 */
public final class BotBrain {

    private static final float LOOKAHEAD = 26f;
    private static final float ARENA_ENGAGE_RANGE = 26f;

    private final Vector3 target = new Vector3();
    private final Vector3 scratch = new Vector3();
    private final float[] near = new float[4];
    /** 本帧的推力系数：偏航越厉害越松油门，让摩擦把速度带下来。 */
    private float steerStrength = 1f;

    /** 每帧调用，可能什么都不做（保持上一帧输入）。 */
    public void think(Roll r, MapDef map, Array<Roll> rolls, ItemSystem items,
                      UltimateSystem ult, top.gunmu.party.core.AttackSystem attacks,
                      Random rng, float dt) {
        if (!r.onField()) {
            r.input.clear();
            return;
        }
        r.aiThinkTimer -= dt;
        if (r.aiThinkTimer > 0f) {
            return;
        }
        int diff = MathUtils.clamp(r.aiDifficulty, 0, 2);
        r.aiThinkTimer = GameConfig.AI_REACTION[diff];
        float jitter = GameConfig.AI_JITTER[diff];
        steerStrength = 1f;

        switch (map.mode) {
            case RACE:
                thinkRace(r, map, rolls, items, jitter);
                break;
            case SURVIVAL:
                thinkSurvival(r, map, rolls, items, jitter);
                break;
            default:
                thinkArena(r, map, rolls, items, jitter);
                break;
        }

        // 机关躲避：前方有锤子/锯盘就跳
        if (shouldJump(r, map)) {
            r.input.jump = true;
        }

        // 加速：直道 / 下坡上把它当资源用掉，不留着
        if (shouldDash(r, map, rng, diff)) {
            r.input.dash = true;
        }

        // 普通攻击（决赛关）：**只在真的有人落在扇形里时才按**。
        // 判据与真人共用同一个纯函数 AttackSystem.inCone，所以不会出现
        // "AI 隔着 8 米打到我、我 5 米打不到它"这种不公平。
        // 命中率随难度走：低难度会错过一部分机会。
        if (attacks != null && attacks.enabled && r.canAttack()
                && top.gunmu.party.core.AttackSystem.hasTargetInCone(r, rolls)
                && rng.nextFloat() < 0.45f + 0.25f * diff) {
            r.input.attack = true;
        }

        // 道具 / 决战技
        if (r.carried != null && rng.nextFloat() < 0.25f * (1f + diff)) {
            r.input.useItem = true;
        }
        if ((r.ultimate != null || r.stolenCharges > 0) && r.ultimateCd <= 0f
                && rng.nextFloat() < GameConfig.AI_SKILL_CHANCE[diff] * 0.35f
                && ult != null && shouldUseUltimate(r, rolls, map)) {
            r.input.useUltimate = true;
        }
    }

    // ================================================================== 竞速

    private void thinkRace(Roll r, MapDef map, Array<Roll> rolls, ItemSystem items,
                           float jitter) {
        steerStrength = 1f;
        float speed = r.speed();
        float hw = map.route.halfWidthAt(Math.max(0f, r.progress));   // route-ok: 仅 thinkRace 链路可达，竞速图必有 route
        float look = 12f + speed * 1.4f;
        float s = Math.min(r.progress + look, map.route.totalLength);   // route-ok: 仅 thinkRace 链路可达，竞速图必有 route
        map.route.pointAt(s, 0f, target);   // route-ok: 仅 thinkRace 链路可达，竞速图必有 route

        // 走廊保持：横向偏得越多，目标点越收回当前中心线，同时松油门
        map.route.nearest(r.pos.x, r.pos.z, near);   // route-ok: 仅 thinkRace 链路可达，竞速图必有 route
        float off = Math.abs(near[2]) / Math.max(1f, hw);
        if (off > 0.30f) {
            float k = Math.min(1f, (off - 0.30f) / 0.55f);
            map.route.pointAt(Math.min(r.progress + look * 0.25f, map.route.totalLength),   // route-ok: 仅 thinkRace 链路可达，竞速图必有 route
                    0f, scratch);
            target.lerp(scratch, k * 0.85f);
            steerStrength = 1f - 0.5f * k;
            jitter *= (1f - k);
        }

        // 顺路捡道具：只有确实在路径前方、且横向偏移不大时才绕
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < map.itemSpawns.size; i++) {
            MapDef.ItemSpawn sp = map.itemSpawns.get(i);
            if (!sp.ready || r.carried != null) {
                continue;
            }
            float ds = arcOf(map, sp.pos.x, sp.pos.z) - r.progress;
            if (ds < 4f || ds > 45f) {
                continue;
            }
            float lateral = lateralOf(map, sp.pos.x, sp.pos.z);
            float score = Math.abs(lateral);
            if (score < bestScore) {
                bestScore = score;
                target.set(sp.pos.x, sp.pos.y, sp.pos.z);
                steerStrength = 1f;
            }
        }

        steer(r, target, jitter);
    }

    // ================================================================== 生存

    private void thinkSurvival(Roll r, MapDef map, Array<Roll> rolls, ItemSystem items,
                               float jitter) {
        // 往中心靠，同时躲开最近的非目标威胁
        target.set(map.poisonCenter.x, 0f, map.poisonCenter.z);
        if (map.waterRise > 0f) {
            // 涨水：往高处走 = 往边缘走
            float dx = r.pos.x - map.poisonCenter.x;
            float dz = r.pos.z - map.poisonCenter.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < 1e-3f) {
                d = 1f;
            }
            float want = map.arenaRadius * 0.75f;
            target.set(map.poisonCenter.x + dx / d * want, 0f,
                    map.poisonCenter.z + dz / d * want);
        }
        steer(r, target, jitter * 0.7f);
    }

    // ================================================================== 竞技

    private void thinkArena(Roll r, MapDef map, Array<Roll> rolls, ItemSystem items,
                            float jitter) {
        Roll enemy = ItemSystem.nearestEnemyAnywhere(r, rolls, ARENA_ENGAGE_RANGE);
        if (enemy != null) {
            target.set(enemy.pos.x, 0f, enemy.pos.z);
        } else {
            float a = (r.aiJitterPhase += 0.7f);
            target.set(map.poisonCenter.x + MathUtils.cos(a) * 12f, 0f,
                    map.poisonCenter.z + MathUtils.sin(a) * 12f);
        }
        steer(r, target, jitter);
    }

    // ================================================================== 工具

    private void steer(Roll r, Vector3 t, float jitter) {
        float dx = t.x - r.pos.x;
        float dz = t.z - r.pos.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4f) {
            r.input.move.setZero();
            return;
        }
        dx /= len;
        dz /= len;
        if (jitter > 0f) {
            float a = (float) (Math.sin((r.id * 7.13f + r.aiJitterPhase)) * Math.PI) * jitter;
            r.aiJitterPhase += 0.03f;
            float c = MathUtils.cos(a);
            float s = MathUtils.sin(a);
            float nx = dx * c - dz * s;
            float nz = dx * s + dz * c;
            dx = nx;
            dz = nz;
        }
        // 接近目标时要松油门：滚木有惯性（摩擦约 1.0/s，9 m/s 刹车要 9 米），
        // 一直全油门冲过去的话到了目标根本停不下来 —— 冲过头、目标落到身后、
        // 再掉头冲回来，就是玩家看到的「所有 AI 都在前后乱动」。
        float v = r.speed();
        float brake = v * v / Math.max(1f, GameConfig.MOVE_ACCEL);
        float slowRadius = Math.max(3f, brake * 1.4f);
        float scale = len >= slowRadius ? 1f : Math.max(0.05f, len / slowRadius);
        r.input.move.set(dx * steerStrength * scale, 0f, dz * steerStrength * scale);
    }

    /**
     * 要不要按加速。
     *
     * <p>规则很简单：**别攒着**。冷却 15 秒，一局里能用的次数有限，
     * 留着不用就是白扔。所以只要跑起来了、不在冷却、前方 12 米内没有伤害机关
     * （那地方该踩刹车而不是加速），就按。
     *
     * <p>加一点随机化，免得 30 根滚木在同一帧齐刷刷开加速。
     */
    private boolean shouldDash(Roll r, MapDef map, Random rng, int diff) {
        if (!r.canDash() || r.speed() < 5f) {
            return false;
        }
        for (int i = 0; i < map.obstacles.size; i++) {
            Obstacle o = map.obstacles.get(i);
            if (o.kind.damage <= 0f) {
                continue;
            }
            float dx = o.pos.x - r.pos.x;
            float dz = o.pos.z - r.pos.z;
            if (dx * dx + dz * dz < 144f) {
                return false;   // 12 米内有害机关，先躲再说
            }
        }
        return rng.nextFloat() < 0.5f + 0.2f * diff;
    }

    private boolean shouldJump(Roll r, MapDef map) {
        for (int i = 0; i < map.obstacles.size; i++) {
            Obstacle o = map.obstacles.get(i);
            if (o.kind.damage <= 0f) {
                continue;
            }
            float dx = o.pos.x - r.pos.x;
            float dz = o.pos.z - r.pos.z;
            float d2 = dx * dx + dz * dz;
            float near = o.radius + 6f;
            if (d2 > near * near) {
                continue;
            }
            float sp = r.speed();
            if (sp < 3f) {
                continue;
            }
            float dot = (dx * r.vel.x + dz * r.vel.z)
                    / (sp * Math.max(0.01f, (float) Math.sqrt(d2)));
            if (dot > 0.5f && r.canJump()) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldUseUltimate(Roll r, Array<Roll> rolls, MapDef map) {
        if (map.mode == Mode.ARENA) {
            Roll e = ItemSystem.nearestEnemyAnywhere(r, rolls, 12f);
            return e != null;
        }
        return r.speed() > 6f;
    }

    /** 仅由 thinkRace 调用 —— 竞速图必然有 route（生存/竞技图走的是另一条 think 分支）。 */
    private float arcOf(MapDef map, float x, float z) {
        map.route.nearest(x, z, near);   // route-ok: 仅 thinkRace 链路可达，竞速图必有 route
        return near[3];
    }

    /** 同 {@link #arcOf}，仅竞速图可达。 */
    private float lateralOf(MapDef map, float x, float z) {
        map.route.nearest(x, z, near);   // route-ok: 仅 thinkRace 链路可达，竞速图必有 route
        return near[2];
    }
}
