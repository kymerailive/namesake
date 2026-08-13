package net.namesake.social;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bond table and its half of the schema 3 → 4 migration. */
class BondsTest {

    private static final UUID ANNA = new UUID(1, 1);
    private static final UUID BRAM = new UUID(2, 2);
    private static final UUID PLAYER = new UUID(0x5EAF_0000_0000_0001L, 9);

    private static Bond bond(int trust, int warmth, int respect, int fear, int day, int peak) {
        return new Bond((byte) trust, (byte) warmth, (byte) respect, (byte) fear,
                (short) 7, day, (short) 0, (byte) peak);
    }

    @Test
    @DisplayName("a pair nobody has anything to do with reads as a bond at zero, not as absent")
    void anUnknownPairIsZeroRatherThanMissing() {
        Bonds bonds = new Bonds();
        Bond none = bonds.at(ANNA, PLAYER, 40);

        assertTrue(none.isNothing());
        assertEquals(40, none.lastSeenDay());
        assertTrue(bonds.stored(ANNA, PLAYER).isEmpty(), "and nothing was created by asking");
        assertEquals(0, bonds.size());
    }

    @Test
    @DisplayName("every field survives a save and a load")
    void everyFieldSurvivesARoundTrip() {
        Bonds written = new Bonds();
        Bond anna = bond(-30, 61, 12, 4, 88, 74).apply(new int[]{2, 0, 0, 0}, 88);
        written.put(ANNA, PLAYER, anna);
        written.put(BRAM, PLAYER, bond(5, 0, -64, 100, 3, 0));

        CompoundTag tag = new CompoundTag();
        written.save(tag);

        Bonds read = new Bonds();
        assertEquals(0, read.readFrom(tag));
        assertEquals(2, read.size());
        assertEquals(2, read.holders());
        assertEquals(anna, read.stored(ANNA, PLAYER).orElseThrow(),
                "including gainedToday and the high-water mark, not just the four axes");
        assertEquals(bond(5, 0, -64, 100, 3, 0), read.stored(BRAM, PLAYER).orElseThrow());
    }

    /**
     * The schema 3 → 4 migration, in one assertion.
     *
     * <p>There is no value to rewrite — bonds are a table that did not exist — so the whole content
     * of the migration is that an absent {@code bonds} key reads as "nobody has met anyone" rather
     * than as damage. Read as damage, {@code NpcRegistry} goes read-only and a world that has bonds
     * in it silently stops saving them, which is the same shape of failure as every other entry in
     * this project's defect list: not a crash, a save that looks like it worked.
     */
    @Test
    @DisplayName("a tag with no bond table at all loads as an empty one, not as a damaged one")
    void anAbsentTableIsNotADamagedOne() {
        CompoundTag preSchemaFour = new CompoundTag();
        assertFalse(preSchemaFour.contains("bonds"), "the fixture must not already have one");

        Bonds bonds = new Bonds();
        assertEquals(0, bonds.readFrom(preSchemaFour), "an absent table is not an unreadable one");
        assertEquals(0, bonds.size());
    }

    @Test
    @DisplayName("a bond record that cannot be read is counted rather than skipped")
    void damageIsCounted() {
        Bonds written = new Bonds();
        written.put(ANNA, PLAYER, bond(1, 2, 3, 4, 5, 6));
        CompoundTag tag = new CompoundTag();
        written.save(tag);

        // Two ways a row can be wrong, and both have to be visible: a lost key and a lost payload.
        ListTag list = tag.getList("bonds", Tag.TAG_COMPOUND);
        list.getCompound(0).remove("holder");
        CompoundTag noPayload = new CompoundTag();
        noPayload.putIntArray("holder", new int[]{1, 2, 3, 4});
        noPayload.putIntArray("about", new int[]{5, 6, 7, 8});
        list.add(noPayload);

        Bonds read = new Bonds();
        assertEquals(2, read.readFrom(tag),
                "the caller must be told, or it will happily rewrite the file without them");
        assertEquals(0, read.size());
    }

    @Test
    @DisplayName("removing a persona takes its bonds with it")
    void forgettingAHolderDropsTheirBonds() {
        Bonds bonds = new Bonds();
        bonds.put(ANNA, PLAYER, bond(1, 1, 0, 0, 1, 1));
        bonds.put(BRAM, PLAYER, bond(1, 1, 0, 0, 1, 1));

        assertTrue(bonds.forget(ANNA));
        assertFalse(bonds.forget(ANNA), "and says so if there was nothing to forget");
        assertEquals(1, bonds.size());
        assertTrue(bonds.of(ANNA).isEmpty());
    }

    @Test
    @DisplayName("the last bond of a holder takes the holder with it")
    void removingTheLastBondDropsTheHolder() {
        Bonds bonds = new Bonds();
        bonds.put(ANNA, PLAYER, bond(1, 1, 0, 0, 1, 1));

        assertTrue(bonds.remove(ANNA, PLAYER));
        assertEquals(0, bonds.holders(), "an empty map per holder is a row that means nothing");
        assertFalse(bonds.remove(ANNA, PLAYER));
    }
}
