package net.namesake.social;

/**
 * The six things that can happen between a person and a villager, and what each one is nominally
 * worth on the four bond axes.
 *
 * <p><b>Deeds are structs, not sentences.</b> That is the whole reason session 06 can dedupe a deed
 * ring exactly and session 08 can degrade a rumour's confidence without inventing anything: a deed
 * is a small fixed shape with an id, and every stage downstream reads the same six columns rather
 * than parsing a description of what happened.
 *
 * <p><b>The numbers here are the <i>nominal</i> value to the subject.</b> Three things happen to
 * them before they reach a bond, and {@link Deeds#deltaFor} is where:
 *
 * <ul>
 *   <li>the <b>witness share</b> — a bystander gets a fraction of what the subject gets, and that
 *       fraction is a property of the deed rather than of the bystander. Being fed is mostly about
 *       you; being struck in the square is nearly as much about everyone watching;</li>
 *   <li>the <b>personality weight</b> ({@link Personality}), which may soften or sharpen a positive
 *       and may only ever sharpen a negative;</li>
 *   <li>the <b>daily cap</b> ({@link Bond#apply}), which limits positives and never negatives.</li>
 * </ul>
 *
 * <p><b>Ids are explicit and never the ordinal.</b> A persisted deed stores {@code typeId}; reading
 * it back through {@code values()[ordinal]} would silently re-label every stored deed the day
 * somebody inserts a type in the middle of this list. The addon API in {@code DESIGN.md} §2 hands
 * out deed types to third parties, which makes that a certainty rather than a risk.
 */
public enum DeedType {

    /** A gift the villager actually wanted — vanilla's own {@code wantsToPickUp} decides. */
    GIFT_WANTED(0, 2, 3, 0, 0, 1F / 3F),

    /** A gift they had no use for. The thought counts a little; the object does not. */
    GIFT_UNWANTED(1, 1, 1, 0, 0, 1F / 3F),

    /** Food, to a villager vanilla itself says wants more of it. {@code DESIGN.md} §5's threshold. */
    FED_HUNGRY(2, 3, 3, 0, 0, 1F / 3F),

    /**
     * Violence against a resident. Respect rises a little on purpose: force <i>works</i>, and the
     * settlement remembering that it worked is what makes {@code DESIGN.md} §6's force-resolution a
     * real option rather than a strictly dominated one.
     */
    STRUCK_RESIDENT(3, -6, -8, 1, 6, 0.5F),

    /** The worst thing in the mod. Witnesses take it at full weight — there is no bystanding. */
    KILLED_RESIDENT(4, -40, -60, 0, 35, 1F),

    /**
     * A raider killed inside a settlement during an active raid. Full witness share: this is the
     * one deed whose whole point is that the village saw it.
     */
    DEFENDED_RAID(5, 8, 6, 12, 0, 1F);

    private final short id;
    private final int[] delta;
    private final float witnessShare;

    DeedType(int id, int trust, int warmth, int respect, int fear, float witnessShare) {
        this.id = (short) id;
        this.delta = new int[]{trust, warmth, respect, fear};
        this.witnessShare = witnessShare;
    }

    public short id() {
        return id;
    }

    /** The nominal change to one axis, for the subject, before any weighting. */
    public int delta(int axis) {
        return delta[axis];
    }

    /** What a bystander gets, as a fraction of the subject's share. */
    public float witnessShare() {
        return witnessShare;
    }

    /** True if any axis moves the wrong way — the deeds the cap and personality must not soften. */
    public boolean isHarmful() {
        for (int value : delta) {
            if (value < 0) {
                return true;
            }
        }
        return false;
    }

    public static DeedType byId(short id) {
        for (DeedType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new IllegalArgumentException("No deed type with id " + id);
    }
}
