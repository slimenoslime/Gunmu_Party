package top.gunmu.party.tools;

import com.badlogic.gdx.utils.Array;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Roll;
import top.gunmu.party.core.TerrainType;
import top.gunmu.party.physics.HeightField;
import top.gunmu.party.physics.PhysicsWorld;

/**
 * 跳跃自检：<b>把"跳跃能不能用"变成一条会红的断言</b>。
 *
 * <p>为什么要有这个：跳跃曾经整条链路是断的 —— {@code Hud} 每帧把
 * {@code input.jump} 置位、{@code BotBrain} 也在置位，但 <b>物理层没有任何地方读它</b>
 * （对比 {@code useItem} → {@code ItemSystem}、{@code useUltimate} → {@code UltimateSystem}
 * 都是"读一次 + 清掉"）。结果是编译全绿、冒烟全绿、地图校验全绿，
 * 唯独玩家按空格完全没反应。这类"写进去没人读"的 bug 只有端到端的断言抓得住。
 *
 * <p>这里在**平地上**跑真实物理，验证：
 * <ol>
 *   <li>按一次能起跳，初速等于 {@link GameConfig#JUMP_VELOCITY}（扣掉当帧重力）；</li>
 *   <li>起跳当帧就脱离地面（不会被 {@code resolveGround} 按回去）；</li>
 *   <li>边沿信号被消费，不会变成"一直跳"；</li>
 *   <li>腾空高度与滞空时间符合抛体公式，不是只弹一下就贴回去；</li>
 *   <li>空中再按不能二段跳。</li>
 * </ol>
 */
public final class JumpTest {

    private JumpTest() {
    }

    public static int run() {
        System.out.println("---- 跳跃自检（平地上跑真实物理）----");
        int bad = 0;

        // 60×60 的平地，全部可行走
        HeightField field = new HeightField(-30f, -30f, 60f, 60f, 1f);
        for (int iz = 0; iz < field.nz; iz++) {
            for (int ix = 0; ix < field.nx; ix++) {
                field.set(ix, iz, 0f, true, TerrainType.GRASS);
            }
        }
        PhysicsWorld world = new PhysicsWorld(field);

        Roll r = new Roll(0, "测试滚木", true);
        r.pos.set(0f, GameConfig.LOG_RADIUS, 0f);
        Array<Roll> rolls = new Array<>();
        rolls.add(r);

        // ---- 先落地站稳 ----
        for (int i = 0; i < 30; i++) {
            world.step(rolls, GameConfig.FIXED_DT);
        }
        float baseY = r.pos.y;
        if (!r.grounded) {
            System.out.println("  [FAIL] 平地上站了 30 帧仍然没有 grounded（y=" + baseY + "）");
            return 1;
        }
        if (!r.canJump()) {
            System.out.println("  [FAIL] 站稳了却 canJump()=false（noJumpTimer=" + r.noJumpTimer + "）");
            bad++;
        }
        System.out.println("  [OK]   站立：y=" + fmt(baseY) + "  grounded=" + r.grounded
                + "  canJump=" + r.canJump());

        // ---- 按一次跳跃 ----
        float expectedV = GameConfig.JUMP_VELOCITY;
        r.input.jump = true;
        world.step(rolls, GameConfig.FIXED_DT);

        // 物理在跳跃之后还会扣一帧重力，所以允许这个误差
        float minV = expectedV - GameConfig.GRAVITY * GameConfig.FIXED_DT * 1.5f;
        if (r.vel.y < minV) {
            System.out.println("  [FAIL] 按了跳跃但没获得初速：vel.y=" + fmt(r.vel.y)
                    + "，期望 >= " + fmt(minV));
            bad++;
        } else {
            System.out.println("  [OK]   起跳初速 vel.y=" + fmt(r.vel.y)
                    + "（JUMP_VELOCITY=" + fmt(expectedV) + " 扣一帧重力后应约 "
                    + fmt(expectedV - GameConfig.GRAVITY * GameConfig.FIXED_DT) + "）");
        }

        if (r.grounded) {
            System.out.println("  [FAIL] 起跳当帧仍然是 grounded —— 会被 resolveGround 按回地面");
            bad++;
        } else {
            System.out.println("  [OK]   起跳当帧已离地 grounded=false");
        }

        if (r.input.jump) {
            System.out.println("  [FAIL] input.jump 没有被消费（边沿信号会变成\"一直跳\"）");
            bad++;
        } else {
            System.out.println("  [OK]   input.jump 已被消费，不会重复触发");
        }

        // ---- 空中再按，不许二段跳 ----
        float vBeforeAirPress = r.vel.y;
        r.input.jump = true;
        world.step(rolls, GameConfig.FIXED_DT);
        if (r.vel.y >= vBeforeAirPress) {
            System.out.println("  [FAIL] 空中再按跳跃获得了额外速度（二段跳）："
                    + fmt(vBeforeAirPress) + " -> " + fmt(r.vel.y));
            bad++;
        } else {
            System.out.println("  [OK]   空中再按无效（" + fmt(vBeforeAirPress)
                    + " -> " + fmt(r.vel.y) + "），无二段跳");
        }

        // ---- 飞到最高点再落回来 ----
        float maxY = r.pos.y;
        int airFrames = 1;
        for (int i = 0; i < 600; i++) {
            world.step(rolls, GameConfig.FIXED_DT);
            maxY = Math.max(maxY, r.pos.y);
            if (r.grounded) {
                break;
            }
            airFrames++;
        }

        if (!r.grounded) {
            System.out.println("  [FAIL] 跳起来之后 600 帧都没有落回地面，y=" + fmt(r.pos.y));
            bad++;
        }

        // 抛体公式：h = v²/(2g)，t = 2v/g
        float jumpH = expectedV * expectedV / (2f * GameConfig.GRAVITY);
        float actualH = maxY - baseY;
        int expectFrames = Math.round(2f * expectedV / GameConfig.GRAVITY / GameConfig.FIXED_DT);
        float hErr = Math.abs(actualH - jumpH) / jumpH;

        System.out.println("  [..]   腾空高度 " + fmt(actualH) + "m（抛体公式 " + fmt(jumpH)
                + "m，误差 " + fmt(hErr * 100f) + "%）"
                + "  滞空 " + airFrames + " 帧（理论 " + expectFrames + " 帧）");

        if (hErr > 0.15f) {
            System.out.println("  [FAIL] 腾空高度与抛体公式差太多，重力或初速有问题");
            bad++;
        }
        if (airFrames < 2) {
            System.out.println("  [FAIL] 只腾空了 " + airFrames + " 帧 —— 相当于没跳起来");
            bad++;
        }
        if (Math.abs(airFrames - expectFrames) > expectFrames * 0.35f) {
            System.out.println("  [FAIL] 滞空时间与理论差太多");
            bad++;
        }
        // 跳一次应该明显越过一个滚木半径，否则手感上"看不出跳了"
        if (actualH < GameConfig.LOG_RADIUS) {
            System.out.println("  [FAIL] 腾空高度 " + fmt(actualH)
                    + "m 不足一个滚木半径 " + fmt(GameConfig.LOG_RADIUS) + "m");
            bad++;
        }

        // ---- 落地后还能再跳（不是一次性的） ----
        r.input.jump = true;
        world.step(rolls, GameConfig.FIXED_DT);
        if (r.vel.y <= minV) {
            System.out.println("  [FAIL] 落地后第二次跳跃失败：vel.y=" + fmt(r.vel.y));
            bad++;
        } else {
            System.out.println("  [OK]   落地后可再次起跳 vel.y=" + fmt(r.vel.y));
        }

        System.out.println(bad == 0 ? "  跳跃链路正常" : "  跳跃自检失败 " + bad + " 项");
        return bad;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
