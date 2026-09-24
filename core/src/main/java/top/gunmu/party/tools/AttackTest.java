package top.gunmu.party.tools;

import com.badlogic.gdx.math.Vector3;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.AttackSystem;
import top.gunmu.party.core.Roll;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.match.LevelDirector;

/**
 * 普通攻击的端到端自检（决赛关专属：5 s 冷却 / 70° 扇形 / 15 伤）。
 *
 * <p>这一条需求是口述进来的（「添加攻击技能，5 秒冷却对扇形内的敌人造成 15 点伤害，
 * 只在决赛关可用」），而"口述需求 + 新输入字段"正是最容易出问题的地方：
 *
 * <ol>
 *   <li><b>扇形判定的几何</b>：正前方命中、背后不命中、侧后方不命中、超距不命中、
 *       隔着落差层不命中。全部用纯函数 {@link AttackSystem#inCone} 断言；</li>
 *   <li><b>关卡限定</b>：前四关 {@code attacks.enabled == false}，第五关为 true ——
 *       这一点必须**跟着换关走**，否则会出现"第 5 关攻击是关着的"（看起来像加了没生效）；</li>
 *   <li><b>输入不悬空</b>（D43）：{@code input.attack} 只有 {@code AttackSystem.tick}
 *       一个消费点，而且**无论能不能施放都会被清掉**。曾经 {@code input.jump} 全仓零处读取，
 *       整条跳跃链路是断的而所有测试全绿；</li>
 *   <li><b>冷却是一段真的时间</b>：施放后立刻再按无效，走完 5 秒（300 帧）之后才能再打。
 *       这条会驱动与游戏完全相同的更新路径（{@code LevelDirector.update}），
 *       而不是只调物理 —— 冷却是在 {@code Roll.tickTimers} 里递减的，只调 step 永远走不完。</li>
 * </ol>
 */
public final class AttackTest {

    private static int failures;

    private AttackTest() {
    }

    public static int run() {
        failures = 0;
        System.out.println("---- 普通攻击自检（5 s 冷却 / 70° 扇形 / 15 伤 / 仅决赛关）----");
        coneMath();
        endToEnd();
        System.out.println(failures == 0
                ? "  普通攻击自检全部通过"
                : "  普通攻击自检失败 " + failures + " 项");
        return failures;
    }

    // ================================================================== 判定几何

    private static void coneMath() {
        float r = GameConfig.ATTACK_RANGE;
        check("正前方 3 m 命中", AttackSystem.inCone(0f, 3f, 0f, 0f, 1f));
        check("正前方 " + (r - 0.1f) + " m（刚在范围内）命中",
                AttackSystem.inCone(0f, r - 0.1f, 0f, 0f, 1f));
        check("超出半径 " + r + " m 不命中", !AttackSystem.inCone(0f, r + 0.5f, 0f, 0f, 1f));
        check("正后方不命中", !AttackSystem.inCone(0f, -3f, 0f, 0f, 1f));
        check("正侧方（90°）不命中", !AttackSystem.inCone(3f, 0f, 0f, 0f, 1f));

        float a30x = (float) Math.sin(Math.toRadians(30));
        float a30z = (float) Math.cos(Math.toRadians(30));
        check("偏 30° 命中", AttackSystem.inCone(a30x * 3f, a30z * 3f, 0f, 0f, 1f));
        float over = GameConfig.ATTACK_HALF_ANGLE + 5f;
        float aox = (float) Math.sin(Math.toRadians(over));
        float aoz = (float) Math.cos(Math.toRadians(over));
        check("超出半角 " + over + "° 不命中", !AttackSystem.inCone(aox * 3f, aoz * 3f, 0f, 0f, 1f));

        check("贴身重叠也算命中", AttackSystem.inCone(0f, 0f, 0f, 0f, 1f));
        check("隔着一层地形（落差超容差）不命中", !AttackSystem.inCone(0f, 3f,
                GameConfig.ATTACK_HEIGHT_TOLERANCE + 0.5f, 0f, 1f));

        check("冷却就是 5 秒", Math.abs(GameConfig.ATTACK_COOLDOWN - 5f) < 1e-4f);
        check("单次伤害就是 15", Math.abs(GameConfig.ATTACK_DAMAGE - 15f) < 1e-4f);
        check("半角在 (0, 90) 之间（当前 " + GameConfig.ATTACK_HALF_ANGLE + "°）",
                GameConfig.ATTACK_HALF_ANGLE > 0f && GameConfig.ATTACK_HALF_ANGLE < 90f);
        check("只在第 5 关开放", GameConfig.ATTACK_MIN_LEVEL == 5);
    }

    // ================================================================== 端到端

    private static void endToEnd() {
        LevelDirector dir = new LevelDirector();
        dir.newTournament(20260921L, 0);

        for (int lv = 1; lv <= 4; lv++) {
            check("第 " + lv + " 关不开放普攻", !dir.attacks.enabled);
            if (lv == 4) {
                rejectBeforeFinal(dir);
            }
            dir.advanceToNextLevel();
        }
        check("第 5 关（决赛）开放普攻", dir.attacks.enabled);
        finalHitAndCooldown(dir);
    }

    /** 决赛之前按攻击：既不该造成伤害，也不该吃掉冷却 —— 但输入必须被清掉。 */
    private static void rejectBeforeFinal(LevelDirector dir) {
        Roll self = dir.state.player;
        Roll other = firstEnemy(dir, self);
        if (self == null || other == null) {
            fail("拿不到玩家或对手（第 4 关）");
            return;
        }
        other.invulnTimer = 0f;
        float hp = other.hp;
        self.input.attack = true;
        dir.update(GameConfig.FIXED_DT);
        check("第 4 关按攻击不造成伤害", Math.abs(other.hp - hp) < 1e-3f);
        check("第 4 关按攻击不进入冷却", self.attackCd <= 0f);
        check("第 4 关的攻击输入照样被清掉（读到即清，D43）", !self.input.attack);
        check("第 4 关挥击次数为 0", dir.attacks.attackCount == 0);
    }

    /** 决赛关：扇形内 15 伤 → 冷却 → 冷却期间无效 → 冷却走完能再打。 */
    private static void finalHitAndCooldown(LevelDirector dir) {
        MapDef map = dir.map();
        Roll self = dir.state.player;
        Roll other = firstEnemy(dir, self);
        if (self == null || other == null) {
            fail("拿不到玩家或对手（第 5 关）");
            return;
        }
        // 其余滚木退场：L5 的 AI 也会挥击，留着它们会把"打成没打"的断言搅乱。
        // 留两个人不会触发"只剩一人即胜"的结束条件。
        for (int i = 0; i < dir.rolls().size; i++) {
            Roll r = dir.rolls().get(i);
            if (r != self && r != other) {
                r.stageOut = true;
            }
        }

        Vector3 base = findFlatSpot(map);
        pin(self, base.x, base.z, map);
        // 朝向 +Z：静止时 forward 取长轴的垂直方向（-z, x），所以 longAxis=(1,0,0) → 面朝 +Z
        self.longAxis.set(1f, 0f, 0f);
        self.invulnTimer = 0f;
        self.attackCd = 0f;
        pin(other, base.x, base.z + 3f, map);
        other.hp = GameConfig.MAX_HP;
        other.alive = true;
        other.eliminated = false;
        other.stageOut = false;
        other.invulnTimer = 0f;

        // 先把开局那 8 秒起点屏障走完。
        //
        // ⚠️ 这一步不是可选的：屏障生效期间**圈内免伤**（D50），而两个人的位置就摆在
        // 出生点附近 —— 不推过去的话，挥击明明打中了（attackCount 会 +1），
        // 但 Combat.deal 在"免疫检查"那一步就返回了，血一滴不掉。
        // 这正是"断言看起来莫名其妙地红"的典型来源：真正的机制没错，是测试站错了时间。
        advancePastBarrier(dir, self, other, base, map);

        check("扇形里有目标（AI 与真人共用同一个判据）",
                AttackSystem.hasTargetInCone(self, dir.rolls()));

        self.input.attack = true;
        dir.update(GameConfig.FIXED_DT);
        float expect = GameConfig.MAX_HP - GameConfig.ATTACK_DAMAGE;
        check("扇形内的敌人掉 " + GameConfig.ATTACK_DAMAGE + " 点血（现在 "
                        + String.format(java.util.Locale.ROOT, "%.1f", other.hp) + "）",
                Math.abs(other.hp - expect) < 0.05f);
        check("挥击进入 5 秒冷却（现在 "
                        + String.format(java.util.Locale.ROOT, "%.2f", self.attackCd) + "s）",
                Math.abs(self.attackCd - GameConfig.ATTACK_COOLDOWN) < 0.05f);
        check("挥击后输入被消费掉", !self.input.attack);
        check("挥击被计入累计量", dir.attacks.attackCount == 1);

        // 冷却期间再按：无效，但输入同样要被清掉
        other.hp = GameConfig.MAX_HP;
        self.input.attack = true;
        dir.update(GameConfig.FIXED_DT);
        check("冷却期间再按不造成伤害", Math.abs(other.hp - GameConfig.MAX_HP) < 1e-3f);
        check("冷却期间的输入仍被清掉（否则会攒到冷却结束那一帧连放）",
                !self.input.attack);

        // 把冷却走完（走的是与游戏相同的更新路径：tickTimers 由 LevelDirector 驱动）
        int frames = (int) Math.ceil(GameConfig.ATTACK_COOLDOWN / GameConfig.FIXED_DT) + 4;
        for (int i = 0; i < frames; i++) {
            pin(self, base.x, base.z, map);
            pin(other, base.x, base.z + 3f, map);
            dir.update(GameConfig.FIXED_DT);
        }
        check("5 秒之后冷却走完（现在 "
                        + String.format(java.util.Locale.ROOT, "%.2f", self.attackCd) + "s）",
                self.attackCd <= 0f);

        other.hp = GameConfig.MAX_HP;
        self.input.attack = true;
        dir.update(GameConfig.FIXED_DT);
        check("冷却结束后可以再挥一次（" + GameConfig.ATTACK_DAMAGE + " 伤）",
                Math.abs(other.hp - expect) < 0.05f);
        check("两关累计挥击 2 次（跨关卡收口口径正确）",
                dir.attacks.attackCount == 2);
    }

    // ================================================================== 工具

    /**
     * 把开局 8 秒起点屏障推完（D46 / D50）。
     *
     * <p>屏障生效期间圈内所有人 {@code startShield = true} → {@code invulnerable()}
     * → 任何伤害都不结算。测试要在那之后才开始打人。
     */
    private static void advancePastBarrier(LevelDirector dir, Roll self, Roll other,
                                           Vector3 base, MapDef map) {
        int frames = (int) Math.ceil(GameConfig.START_BARRIER_TIME / GameConfig.FIXED_DT) + 30;
        for (int i = 0; i < frames; i++) {
            pin(self, base.x, base.z, map);
            pin(other, base.x, base.z + 3f, map);
            dir.update(GameConfig.FIXED_DT);
        }
        check("起点屏障已走完，圈内免伤收回（否则伤害会被免疫检查吃掉）",
                !dir.isStartBarrierActive() && !self.startShield && !other.startShield);
    }

    /** 把一根滚木钉在给定水平位置（贴地、静止），避免它在冷却期间滚走。 */
    private static void pin(Roll r, float x, float z, MapDef map) {
        r.pos.set(x, map.field.heightAt(x, z) + GameConfig.LOG_RADIUS, z);
        r.vel.setZero();
        r.grounded = true;
        r.respawnTimer = 0f;
    }

    /** 找一个"脚下与前方 1 m / 3 m 都可走"的出生点，保证贴地摆位稳定。 */
    private static Vector3 findFlatSpot(MapDef map) {
        float[] dz = {0f, 1f, 3f};
        for (int i = 0; i < map.spawns.size; i++) {
            Vector3 s = map.spawns.get(i);
            boolean ok = true;
            for (float d : dz) {
                if (!map.field.walkableAt(s.x, s.z + d)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return s;
            }
        }
        return map.spawns.first();
    }

    private static Roll firstEnemy(LevelDirector dir, Roll self) {
        for (int i = 0; i < dir.rolls().size; i++) {
            Roll r = dir.rolls().get(i);
            if (r != self) {
                return r;
            }
        }
        return null;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("  [OK] " + name);
        } else {
            failures++;
            System.out.println("  [!!] " + name);
        }
    }

    private static void fail(String why) {
        failures++;
        System.out.println("  [!!] " + why);
    }
}
