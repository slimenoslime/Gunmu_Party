package top.gunmu.party.items;

/**
 * 一个正在生效的道具效果实例。
 * 同名效果不叠加，只刷新剩余时间（docs/03-ITEMS.md §1）。
 */
public final class Effect {

    public final ItemType type;
    public float remaining;
    public final float duration;
    /** 来源玩家 id，用于反弹与击杀归属；-1 表示环境。 */
    public int sourceId = -1;

    public Effect(ItemType type, float duration) {
        this.type = type;
        this.duration = duration;
        this.remaining = duration;
    }

    public boolean tick(float dt) {
        remaining -= dt;
        return remaining > 0f;
    }

    public float ratio() {
        return duration <= 0f ? 0f : Math.max(0f, remaining / duration);
    }
}
