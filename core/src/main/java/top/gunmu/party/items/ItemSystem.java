package top.gunmu.party.items;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import java.util.Random;

import top.gunmu.party.GameConfig;
import top.gunmu.party.core.Combat;
import top.gunmu.party.core.DamageSource;
import top.gunmu.party.core.Roll;
import top.gunmu.party.map.MapDef;
import top.gunmu.party.map.Zone;

/**
 * 道具系统：固定刷新点、拾取、效果、召唤物、场地道具。
 * 对应文档：docs/03-ITEMS.md
 */
public final class ItemSystem {

    /** 被打掉的道具落在地上，谁都能捡。 */
    public static final class Drop {
        public final Vector3 pos = new Vector3();
        public ItemType type;
        public float life = 20f;
        public boolean taken;
    }

    private static final class Placed {
        final Zone zone;
        float life;

        Placed(Zone.Kind kind) {
            zone = new Zone(kind);
        }
    }

    public final Array<Summon> summons = new Array<>();

    /** 渲染层读取用：被打落在地上的道具。 */
    public Array<Drop> drops() {
        return drops;
    }

    private final Array<Drop> drops = new Array<>();
    private final Array<Placed> placed = new Array<>();
    private final Vector3 tmp = new Vector3();

    private MapDef map;

    public void reset(MapDef map, Random rng) {
        this.map = map;
        summons.clear();
        drops.clear();
        placed.clear();
        map.resetItemSpawns(rng);
    }

    // ================================================================== 主循环

    public void tick(PhysicsWorldView phys, Array<Roll> rolls, Combat combat,
                     Random rng, float dt) {
        tickRespawns(rng, dt);
        tickDrops(rolls, dt);
        tickPickups(rolls);
        tickUses(rolls, combat, rng);
        tickEffects(rolls, dt);
        tickSummons(rolls, combat, dt);
        tickPlaced(phys, dt);
    }

    /** 只暴露 ItemSystem 需要的那一点物理世界能力，避免整个 PhysicsWorld 依赖回流。 */
    public interface PhysicsWorldView {
        void setTempZones(Array<Zone> zones);

        float groundHeight(float x, float z);

        boolean walkable(float x, float z);
    }

    // ================================================================== 刷新点

    private void tickRespawns(Random rng, float dt) {
        for (int i = 0; i < map.itemSpawns.size; i++) {
            MapDef.ItemSpawn s = map.itemSpawns.get(i);
            if (s.ready) {
                continue;
            }
            s.timer -= dt;
            if (s.timer <= 0f) {
                map.refreshSpawn(s, rng);
            }
        }
    }

    private void tickDrops(Array<Roll> rolls, float dt) {
        for (int i = drops.size - 1; i >= 0; i--) {
            Drop d = drops.get(i);
            d.life -= dt;
            if (d.life <= 0f) {
                drops.removeIndex(i);
            }
        }
    }

    private void tickPickups(Array<Roll> rolls) {
        float r2 = GameConfig.ITEM_PICKUP_RADIUS * GameConfig.ITEM_PICKUP_RADIUS;

        for (int i = 0; i < map.itemSpawns.size; i++) {
            MapDef.ItemSpawn s = map.itemSpawns.get(i);
            if (!s.ready) {
                continue;
            }
            Roll taker = findTaker(rolls, s.pos, r2);
            if (taker != null) {
                taker.carried = s.current;
                s.ready = false;
                s.timer = s.respawnTime;
            }
        }

        for (int i = drops.size - 1; i >= 0; i--) {
            Drop d = drops.get(i);
            Roll taker = findTaker(rolls, d.pos, r2);
            if (taker != null) {
                taker.carried = d.type;
                drops.removeIndex(i);
            }
        }
    }

    private Roll findTaker(Array<Roll> rolls, Vector3 pos, float r2) {
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.alive || r.eliminated || r.respawnTimer > 0f || r.finished) {
                continue;
            }
            if (r.carried != null) {
                continue; // 背包已满：不消耗道具（docs/03 §1）
            }
            float dx = r.pos.x - pos.x;
            float dz = r.pos.z - pos.z;
            if (dx * dx + dz * dz > r2) {
                continue;
            }
            if (Math.abs(r.pos.y - pos.y) > GameConfig.ITEM_PICKUP_HEIGHT) {
                continue;
            }
            return r;
        }
        return null;
    }

    // ================================================================== 使用

    private void tickUses(Array<Roll> rolls, Combat combat, Random rng) {
        for (int i = 0; i < rolls.size; i++) {
            Roll r = rolls.get(i);
            if (!r.input.useItem) {
                continue;
            }
            r.input.useItem = false;
            if (r.carried == null || !r.canUseItem()) {
                continue;
            }
            ItemType type = r.carried;
            r.carried = null;
            apply(r, type, rolls, combat, rng, 0);
        }
    }

    /** 应用一个道具效果。depth 用于防止盲盒递归。 */
    public void apply(Roll user, ItemType type, Array<Roll> rolls, Combat combat,
                      Random rng, int depth) {
        switch (type) {
            case PIGEON_POP: {
                forEachEnemy(user, rolls, 8f, (e) -> {
                    e.addInvert(2.5f);
                    e.addNoJump(2.5f);
                });
                break;
            }
            case CHAIN_OFF: {
                Roll t = nearestAhead(user, rolls, 60f);
                if (t != null) {
                    t.addEffect(ItemType.CHAIN_OFF, 3f);
                    t.addNoJump(3f);
                }
                break;
            }
            case MONEY_FREE: {
                Roll t = nearestAhead(user, rolls, 60f);
                if (t != null) {
                    if (t.carried != null) {
                        dropItem(t, t.carried);
                        t.carried = null;
                    } else {
                        removeRandomBuff(t, rng);
                    }
                }
                break;
            }
            case CRY_BABY: {
                Roll t = nearestAhead(user, rolls, 60f);
                if (t != null) {
                    t.addEffect(ItemType.CRY_BABY, 3f);
                    t.addSlow(0.8f, 3f);
                }
                break;
            }
            case PO_FANG: {
                Roll t = nearestAhead(user, rolls, 60f);
                if (t != null) {
                    t.addEffect(ItemType.PO_FANG, 6f);
                }
                break;
            }
            case EGG_TART: {
                Roll t = nearestAhead(user, rolls, 60f);
                if (t != null) {
                    t.addEffect(ItemType.EGG_TART, 8f);
                }
                break;
            }
            case WILD_DOG_MILK: {
                user.hp = GameConfig.MAX_HP;
                user.addEffect(ItemType.WILD_DOG_MILK, 10f);
                break;
            }
            case LOVE_SELF: {
                user.hp = Math.min(GameConfig.MAX_HP, user.hp + 30f);
                user.clearDebuffs();
                break;
            }
            case CHAIN_GEARS:
                user.addEffect(ItemType.CHAIN_GEARS, 5f);
                break;
            case ZUOWAN_NIDE:
                user.addEffect(ItemType.ZUOWAN_NIDE, 3f);
                break;
            case XIE_XIU:
                user.addEffect(ItemType.XIE_XIU, 8f);
                break;
            case QIANXIN_WANKU:
                user.addEffect(ItemType.QIANXIN_WANKU, 8f);
                break;
            case OPOSSUM:
                user.opossumUsed = false;
                user.addEffect(ItemType.OPOSSUM, 1.5f);
                break;
            case YAOYAO_LINGXIAN:
                user.addEffect(ItemType.YAOYAO_LINGXIAN, 8f);
                break;
            case ZAO_BA:
                user.addEffect(ItemType.ZAO_BA, 6f);
                launchBoost(user);
                break;
            case SHAN_XIAN:
                blink(user);
                break;
            case YI_LU_LU:
                placeBoostLane(user);
                break;
            case EXTERNAL_BURN:
                user.addEffect(ItemType.EXTERNAL_BURN, 12f);
                break;
            case JIANGBAN_DUCK:
                user.addEffect(ItemType.JIANGBAN_DUCK, 10f);
                break;
            case BENGHZU:
                user.addEffect(ItemType.BENGHZU, 8f);
                break;
            case LIUWENXIANG:
                user.clearDebuffs();
                user.addEffect(ItemType.LIUWENXIANG, 4f);
                break;
            case CHANGSHA_HOT:
                user.addEffect(ItemType.CHANGSHA_HOT, 12f);
                break;
            case DONG_MEN:
                placeIce(user);
                break;
            case LOBSTER_FARM:
                summon(user, Summon.Kind.LOBSTER);
                break;
            case HAKIMI: {
                for (int i = 0; i < 3; i++) {
                    Summon s = summon(user, Summon.Kind.HAKIMI);
                    float spread = (i - 1) * 0.35f;
                    float fx = forwardX(user);
                    float fz = forwardZ(user);
                    s.dir.set(fx + fz * spread, 0f, fz - fx * spread).nor();
                }
                break;
            }
            case EBRUFEN:
                user.addEffect(ItemType.EBRUFEN, 10f);
                break;
            case BLIND_BOX: {
                if (depth == 0) {
                    ItemType pick = randomOther(rng);
                    apply(user, pick, rolls, combat, rng, 1);
                }
                break;
            }
            default:
                break;
        }
    }

    private void dropItem(Roll owner, ItemType type) {
        Drop d = new Drop();
        d.type = type;
        d.pos.set(owner.pos);
        d.pos.y += 0.6f;
        drops.add(d);
    }

    private void removeRandomBuff(Roll r, Random rng) {
        Array<Effect> buffs = new Array<>();
        for (int i = 0; i < r.effects.size; i++) {
            if (r.effects.get(i).type.isBuff()) {
                buffs.add(r.effects.get(i));
            }
        }
        if (buffs.size == 0) {
            return;
        }
        r.effects.removeValue(buffs.get(rng.nextInt(buffs.size)), true);
    }

    /**
     * 盲盒要开的那个道具。
     *
     * <p>**同样要按模式过滤**：竞速图开盲盒开出"免伤反弹"跟直接刷出来一样没意义，
     * 盲盒是个后门，不堵住就等于没修。权重口径与刷新点一致（走 {@code map.weightOf}）。
     */
    private ItemType randomOther(Random rng) {
        float total = 0f;
        for (int i = 0; i < ItemType.ALL.length; i++) {
            ItemType t = ItemType.ALL[i];
            if (t != ItemType.BLIND_BOX) {
                float w = map.weightOf(t);
                if (w > 0f) {
                    total += w;
                }
            }
        }
        if (total <= 0f) {
            return ItemType.LOVE_SELF;
        }
        float pick = rng.nextFloat() * total;
        for (int i = 0; i < ItemType.ALL.length; i++) {
            ItemType t = ItemType.ALL[i];
            if (t == ItemType.BLIND_BOX) {
                continue;
            }
            float w = map.weightOf(t);
            if (w <= 0f) {
                continue;
            }
            pick -= w;
            if (pick <= 0f) {
                return t;
            }
        }
        return ItemType.LOVE_SELF;
    }

    private void placeIce(Roll user) {
        Placed p = new Placed(Zone.Kind.ICE);
        float fx = forwardX(user);
        float fz = forwardZ(user);
        p.zone.at(user.pos.x + fx * 4.5f, user.pos.y - GameConfig.LOG_RADIUS,
                user.pos.z + fz * 4.5f);
        p.zone.size(3f, 3f).dir(0f, 1f, 0f);
        p.life = 8f;
        placed.add(p);
    }

    /**
     * 一路绿灯：在身前铺一条朝前延伸的加速带（复用地图本来就有的 {@code Zone.Kind.BOOST}）。
     *
     * <p>为什么用"身前"而不是"脚下"：铺在脚下自己会立刻冲出去、但看不见自己踩了什么；
     * 铺在身前 5 米，玩家能看着自己冲进那条带子里 —— 而且**对手也能用**，
     * 这是礼物也是陷阱（竞速图里的道具本来就该有这种双边性）。
     */
    private void placeBoostLane(Roll user) {
        Placed p = new Placed(Zone.Kind.BOOST);
        float fx = forwardX(user);
        float fz = forwardZ(user);
        p.zone.at(user.pos.x + fx * GameConfig.GREEN_LIGHT_AHEAD,
                user.pos.y - GameConfig.LOG_RADIUS,
                user.pos.z + fz * GameConfig.GREEN_LIGHT_AHEAD);
        // 局部 z 轴对准前进方向：Zone.contains 里 lz = dx·sinYaw + dz·cosYaw，
        // 要让它等于"沿前进方向的投影"，就得 sinYaw = fx、cosYaw = fz。
        p.zone.size(GameConfig.GREEN_LIGHT_HALF_W, GameConfig.GREEN_LIGHT_HALF_L)
                .yaw((float) Math.atan2(fx, fz))
                .dir(fx, fz, GameConfig.GREEN_LIGHT_POWER);
        p.life = 8f;
        placed.add(p);
    }

    /** 早八：起步冲量 —— 沿当前朝向把速度**至少**抬到 {@code ZAOBA_MIN_SPEED}。 */
    private void launchBoost(Roll user) {
        float fx = forwardX(user);
        float fz = forwardZ(user);
        float want = Math.max(user.speed(), GameConfig.ZAOBA_MIN_SPEED);
        user.vel.x = fx * want;
        user.vel.z = fz * want;
    }

    /**
     * 闪现：朝前方瞬移，落点必须**在路面上**。
     *
     * <p>逐 {@code 0.5 m} 往前探，遇到不可行走、或落差超过 3 米就停在上一个安全点 ——
     * 不能直接 {@code pos += dir × 12}：竞速图两侧是虚空，那样等于"用道具自杀"。
     * 前方完全没路时原地不动（仍然吃无敌帧）。
     */
    private void blink(Roll user) {
        float fx = forwardX(user);
        float fz = forwardZ(user);
        float h0 = map.field.heightAt(user.pos.x, user.pos.z);
        float best = 0f;
        for (float d = GameConfig.SHANXIAN_STEP;
             d <= GameConfig.SHANXIAN_RANGE + 1e-3f; d += GameConfig.SHANXIAN_STEP) {
            float x = user.pos.x + fx * d;
            float z = user.pos.z + fz * d;
            if (!map.field.walkableAt(x, z)) {
                break;
            }
            if (Math.abs(map.field.heightAt(x, z) - h0) > 3f) {
                break;
            }
            best = d;
        }
        if (best > 0f) {
            float x = user.pos.x + fx * best;
            float z = user.pos.z + fz * best;
            float keep = user.speed();
            user.pos.set(x, map.field.heightAt(x, z) + GameConfig.LOG_RADIUS, z);
            user.vel.x = fx * keep;
            user.vel.z = fz * keep;
            user.grounded = false;
        }
        user.invulnTimer = Math.max(user.invulnTimer, GameConfig.SHANXIAN_INVULN);
    }

    private Summon summon(Roll owner, Summon.Kind kind) {
        Summon s = new Summon(kind, owner.id);
        s.pos.set(owner.pos);
        if (kind == Summon.Kind.LOBSTER) {
            s.pos.x += forwardX(owner) * 1.4f;
            s.pos.z += forwardZ(owner) * 1.4f;
        } else {
            s.dir.set(forwardX(owner), 0f, forwardZ(owner));
        }
        summons.add(s);
        return s;
    }

    // ================================================================== 效果 Tick

    private void tickEffects(Array<Roll> rolls, float dt) {
        for (int ri = 0; ri < rolls.size; ri++) {
            Roll r = rolls.get(ri);
            for (int i = r.effects.size - 1; i >= 0; i--) {
                Effect e = r.effects.get(i);
                switch (e.type) {
                    case WILD_DOG_MILK:
                        r.hp = Math.min(GameConfig.MAX_HP, r.hp + 4f * dt);
                        break;
                    case EBRUFEN:
                        r.hp = Math.min(GameConfig.MAX_HP, r.hp + 6f * dt);
                        break;
                    case OPOSSUM:
                        r.rootTimer = Math.max(r.rootTimer, 0.06f);
                        break;
                    default:
                        break;
                }
                if (!e.tick(dt)) {
                    r.effects.removeIndex(i);
                    if (e.type == ItemType.ZUOWAN_NIDE) {
                        r.fatigueTimer = 2f;
                    }
                }
            }
        }
    }

    // ================================================================== 召唤物

    private void tickSummons(Array<Roll> rolls, Combat combat, float dt) {
        for (int i = summons.size - 1; i >= 0; i--) {
            Summon s = summons.get(i);
            s.life -= dt;
            s.hitCd = Math.max(0f, s.hitCd - dt);
            if (s.life <= 0f || s.dead) {
                summons.removeIndex(i);
                continue;
            }
            Roll owner = findById(rolls, s.ownerId);

            if (s.kind == Summon.Kind.LOBSTER) {
                Roll target = owner == null ? null
                        : nearestEnemyAnywhere(owner, rolls, 22f);
                float tx;
                float tz;
                if (target != null) {
                    tx = target.pos.x;
                    tz = target.pos.z;
                } else if (owner != null) {
                    tx = owner.pos.x - forwardX(owner) * 1.4f;
                    tz = owner.pos.z - forwardZ(owner) * 1.4f;
                } else {
                    continue;
                }
                float dx = tx - s.pos.x;
                float dz = tz - s.pos.z;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d > 0.05f) {
                    float step = Math.min(Summon.LOBSTER_SPEED * dt, d);
                    s.pos.x += dx / d * step;
                    s.pos.z += dz / d * step;
                }
                clampToGround(s);
                if (target != null && s.hitCd <= 0f) {
                    float ex = target.pos.x - s.pos.x;
                    float ez = target.pos.z - s.pos.z;
                    float rr = Summon.LOBSTER_RADIUS + GameConfig.LOG_COLLIDE_RADIUS;
                    if (ex * ex + ez * ez < rr * rr) {
                        combat.deal(target, owner, Summon.LOBSTER_DAMAGE,
                                DamageSource.ITEM, 0);
                        float dd = (float) Math.sqrt(ex * ex + ez * ez);
                        if (dd > 1e-4f) {
                            target.vel.x += ex / dd * 8f;
                            target.vel.z += ez / dd * 8f;
                        }
                        s.hitCd = 1.0f;
                    }
                }
            } else {
                float step = Summon.HAKIMI_SPEED * dt;
                s.pos.x += s.dir.x * step;
                s.pos.z += s.dir.z * step;
                s.travelled += step;
                clampToGround(s);
                if (s.travelled >= Summon.HAKIMI_RANGE) {
                    s.dead = true;
                    continue;
                }
                for (int j = 0; j < rolls.size; j++) {
                    Roll r = rolls.get(j);
                    if (!r.alive || r.eliminated || r.id == s.ownerId) {
                        continue;
                    }
                    float ex = r.pos.x - s.pos.x;
                    float ez = r.pos.z - s.pos.z;
                    float rr = Summon.HAKIMI_RADIUS + GameConfig.LOG_COLLIDE_RADIUS;
                    if (ex * ex + ez * ez < rr * rr) {
                        combat.deal(r, owner, Summon.HAKIMI_DAMAGE, DamageSource.ITEM, 0);
                        float dd = (float) Math.sqrt(ex * ex + ez * ez);
                        if (dd > 1e-4f) {
                            r.vel.x += ex / dd * Summon.HAKIMI_KNOCKBACK;
                            r.vel.z += ez / dd * Summon.HAKIMI_KNOCKBACK;
                        }
                        s.dead = true;
                        break;
                    }
                }
            }
        }
    }

    private void clampToGround(Summon s) {
        if (map.field.walkableAt(s.pos.x, s.pos.z)) {
            float h = map.field.heightAt(s.pos.x, s.pos.z) + s.radius();
            if (s.pos.y < h) {
                s.pos.y = h;
            }
        }
    }

    private Roll findById(Array<Roll> rolls, int id) {
        for (int i = 0; i < rolls.size; i++) {
            if (rolls.get(i).id == id) {
                return rolls.get(i);
            }
        }
        return null;
    }

    // ================================================================== 场地道具

    private void tickPlaced(PhysicsWorldView phys, float dt) {
        for (int i = placed.size - 1; i >= 0; i--) {
            Placed p = placed.get(i);
            p.life -= dt;
            if (p.life <= 0f) {
                placed.removeIndex(i);
            }
        }
        if (phys != null) {
            Array<Zone> out = new Array<>();
            for (int i = 0; i < placed.size; i++) {
                out.add(placed.get(i).zone);
            }
            phys.setTempZones(out);
        }
    }

    // ================================================================== 查询工具

    public static float forwardX(Roll r) {
        float sp = r.speed();
        if (sp > 0.35f) {
            return r.vel.x / sp;
        }
        // 静止时用长轴的垂直方向当朝向
        return -r.longAxis.z;
    }

    public static float forwardZ(Roll r) {
        float sp = r.speed();
        if (sp > 0.35f) {
            return r.vel.z / sp;
        }
        return r.longAxis.x;
    }

    /** 目标是否可见：隐身会被"长沙挺热"看破。 */
    public static boolean visibleTo(Roll viewer, Roll target) {
        if (!target.hidden()) {
            return true;
        }
        return viewer.hasEffect(ItemType.CHANGSHA_HOT);
    }

    public static Roll nearestAhead(Roll self, Array<Roll> rolls, float maxDist) {
        float fx = self.vel.x;
        float fz = self.vel.z;
        float fl = (float) Math.sqrt(fx * fx + fz * fz);
        boolean hasDir = fl > 0.5f;
        if (hasDir) {
            fx /= fl;
            fz /= fl;
        }
        Roll best = null;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == self || !o.alive || o.eliminated || o.respawnTimer > 0f) {
                continue;
            }
            if (!visibleTo(self, o)) {
                continue;
            }
            float dx = o.pos.x - self.pos.x;
            float dz = o.pos.z - self.pos.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > maxDist || d < 1e-3f) {
                continue;
            }
            if (hasDir && (dx * fx + dz * fz) / d < 0.2f) {
                continue;
            }
            if (d < bestScore) {
                bestScore = d;
                best = o;
            }
        }
        return best;
    }

    public static Roll nearestEnemyAnywhere(Roll self, Array<Roll> rolls, float maxDist) {
        Roll best = null;
        float bestD = maxDist;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == self || !o.alive || o.eliminated || o.respawnTimer > 0f) {
                continue;
            }
            if (!visibleTo(self, o)) {
                continue;
            }
            float dx = o.pos.x - self.pos.x;
            float dz = o.pos.z - self.pos.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    public interface RollVisitor {
        void visit(Roll r);
    }

    public static void forEachEnemy(Roll self, Array<Roll> rolls, float radius,
                                    RollVisitor visitor) {
        float r2 = radius * radius;
        for (int i = 0; i < rolls.size; i++) {
            Roll o = rolls.get(i);
            if (o == self || !o.alive || o.eliminated || o.respawnTimer > 0f) {
                continue;
            }
            if (!visibleTo(self, o)) {
                continue;
            }
            float dx = o.pos.x - self.pos.x;
            float dz = o.pos.z - self.pos.z;
            if (dx * dx + dz * dz <= r2) {
                visitor.visit(o);
            }
        }
    }
}
