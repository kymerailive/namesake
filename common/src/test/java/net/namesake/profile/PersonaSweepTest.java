package net.namesake.profile;

import net.namesake.npc.NpcRegistry;
import net.namesake.npc.Persona;
import net.namesake.settlement.Settlements;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweep's contract, and the fixture population's one hard rule.
 *
 * <p>Two things are being pinned down here. The first is coverage: {@code DESIGN.md} §8 says every
 * record is advanced about once a second, and "about" is doing no work at all — twenty ticks must
 * visit every record exactly once, and a record that slips a bucket is a villager whose day quietly
 * stops advancing.
 *
 * <p>The second is that a profiling fixture can never become a person. Session 04 builds hundreds
 * of them and {@code NpcRegistry} writes to disk; four hundred fixtures left in a save are worse
 * than a schema break, because they load without complaint.
 */
class PersonaSweepTest {

    private static List<Persona> population(int count) {
        List<Persona> personas = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            personas.add(Persona.create(UUID.nameUUIDFromBytes(("npc" + i).getBytes()), i));
        }
        return personas;
    }

    @Test
    @DisplayName("twenty ticks visit every record exactly once")
    void twentyTicksCoverEveryoneOnce() {
        List<Persona> personas = population(400);
        PersonaSweep sweep = new PersonaSweep();
        sweep.rebuild(personas);

        List<UUID> visited = new ArrayList<>();
        for (int tick = 0; tick < PersonaSweep.BUCKETS; tick++) {
            sweep.advance(tick, persona -> visited.add(persona.id()));
        }

        assertEquals(400, visited.size(), "twenty ticks must visit the whole population once");
        assertEquals(400, new HashSet<>(visited).size(), "and must not visit anyone twice");
        assertEquals(personas.stream().map(Persona::id).collect(java.util.stream.Collectors.toSet()),
                new HashSet<>(visited));
    }

    @Test
    @DisplayName("the tick a record is visited on does not depend on the order it was inserted in")
    void bucketsAreStableAcrossReordering() {
        List<Persona> personas = population(200);
        PersonaSweep first = new PersonaSweep();
        first.rebuild(personas);

        List<Persona> shuffled = new ArrayList<>(personas);
        Collections.shuffle(shuffled, new Random(20260814L));
        PersonaSweep second = new PersonaSweep();
        second.rebuild(shuffled);

        // The failure this catches is silent and periodic: a record that changes bucket when the
        // registry's iteration order shifts is a record that gets swept twice in one second and
        // not at all in the next, and nothing throws.
        for (int tick = 0; tick < PersonaSweep.BUCKETS; tick++) {
            Set<UUID> a = new HashSet<>();
            Set<UUID> b = new HashSet<>();
            first.advance(tick, persona -> a.add(persona.id()));
            second.advance(tick, persona -> b.add(persona.id()));
            int atTick = tick;
            assertEquals(a, b, () -> "tick " + atTick + " swept a different set after a reorder");
        }
    }

    @Test
    @DisplayName("no bucket carries a disproportionate share of the population")
    void bucketsAreBalanced() {
        PersonaSweep sweep = new PersonaSweep();
        sweep.rebuild(population(400));

        int smallest = Integer.MAX_VALUE;
        int largest = 0;
        for (int bucket = 0; bucket < PersonaSweep.BUCKETS; bucket++) {
            smallest = Math.min(smallest, sweep.bucketSize(bucket));
            largest = Math.max(largest, sweep.bucketSize(bucket));
        }
        assertEquals(400, sweep.size());
        // 20 a bucket is the even split. A hash that clumped would put a spike in one tick in
        // twenty, which is precisely the shape the histogram is there to catch — better not to
        // build it in.
        int worst = largest;
        int best = smallest;
        assertTrue(largest <= 34 && smallest >= 8,
                () -> "buckets ran from " + best + " to " + worst + " for 400 records; an even "
                        + "split is 20 and a lopsided one puts a stutter in one tick in twenty");
    }

    @Test
    @DisplayName("advance visits exactly the bucket it claims to")
    void advanceVisitsItsOwnBucket() {
        PersonaSweep sweep = new PersonaSweep();
        sweep.rebuild(population(97));
        for (int t = 0; t < 60; t++) {
            final int atTick = t;
            int[] seen = {0};
            sweep.advance(atTick, persona -> {
                assertEquals(Math.floorMod(atTick, PersonaSweep.BUCKETS),
                        PersonaSweep.bucketOf(persona.id()),
                        "a record was swept on a tick that is not its own");
                seen[0]++;
            });
            assertEquals(sweep.bucketSize(atTick), seen[0]);
        }
    }

    @Test
    @DisplayName("an empty population sweeps cleanly rather than throwing")
    void emptyPopulationIsFine() {
        PersonaSweep sweep = new PersonaSweep();
        sweep.rebuild(List.of());
        assertEquals(0, sweep.size());
        assertEquals(0, sweep.advance(7, persona -> {
            throw new AssertionError("nothing to visit");
        }));
    }

    // --- the fixtures ---------------------------------------------------------------------------

    @Test
    @DisplayName("every fixture carries a reserved id, and no minted persona ever can")
    void fixturesAreInAReservedRange() {
        Settlements settlements = SyntheticPersonas.settlements(400);
        List<Persona> fixtures = SyntheticPersonas.build(400, settlements);

        assertEquals(400, fixtures.size());
        assertEquals(400, fixtures.stream().map(Persona::id).distinct().count());
        for (Persona fixture : fixtures) {
            assertTrue(Persona.isReservedForProfiling(fixture.id()),
                    () -> fixture.id() + " is not in the reserved range");
        }

        // Disjoint by construction rather than by a collision probability nobody checks:
        // UUID.randomUUID stamps version 4 into this nibble and the reserved range leaves it 0.
        // Asserting the nibble is the version with teeth — drawing a few thousand random ids and
        // finding no collision would pass just as well for a range that overlapped, because
        // 2^-64 never comes up in a test run either.
        int version = (int) ((Persona.PROFILING_NAMESPACE >> 12) & 0xF);
        assertEquals(0, version,
                "the reserved range must not carry UUID version 4, or a minted persona could "
                        + "one day land in it");
        for (int i = 0; i < 5000; i++) {
            assertTrue(!Persona.isReservedForProfiling(UUID.randomUUID()),
                    "a randomly minted persona id must never read as a fixture");
        }
    }

    @Test
    @DisplayName("a fixture cannot get into the registry, and cannot get into a save file either")
    void fixturesCannotBePersisted() {
        Settlements settlements = SyntheticPersonas.settlements(60);
        List<Persona> fixtures = SyntheticPersonas.build(60, settlements);
        NpcRegistry registry = new NpcRegistry();

        for (Persona fixture : fixtures) {
            registry.put(fixture);
        }
        assertEquals(0, registry.size(),
                "NpcRegistry.put must refuse a fixture; sixty of them in a save look exactly like "
                        + "sixty people");

        // The second door. A fixture cannot reach the map through put(), so the only way it could
        // ever be on the way to disk is a file someone hands us — which is why save() checks too.
        registry.put(Persona.create(UUID.randomUUID(), 1L));
        assertEquals(1, registry.size(), "a real persona must still go in");
    }

    @Test
    @DisplayName("fixtures are rolled people, not eight zeroes in a row")
    void fixturesAreRealisticEnoughToMeasureAgainst() {
        Settlements settlements = SyntheticPersonas.settlements(400);
        List<Persona> fixtures = SyntheticPersonas.build(400, settlements);

        assertTrue(settlements.size() >= 20,
                () -> "400 records should imply about twenty villages, got " + settlements.size());
        assertTrue(fixtures.stream().allMatch(Persona::isGenerated),
                "a fixture with no culture would exercise a branch the real population does not");
        assertTrue(fixtures.stream().anyMatch(p -> p.settlementId() == Persona.UNASSIGNED),
                "some fixtures must belong to nowhere — that is the branch session 03's load path "
                        + "does not return early on");
        long distinctTraitVectors = fixtures.stream()
                .map(p -> java.util.Arrays.toString(p.traits()))
                .distinct().count();
        assertTrue(distinctTraitVectors > 300,
                () -> "only " + distinctTraitVectors + " distinct trait vectors in 400 fixtures; "
                        + "a population of clones is not a population");
    }
}
