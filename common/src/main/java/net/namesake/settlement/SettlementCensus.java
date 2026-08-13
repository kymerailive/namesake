package net.namesake.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The raw material of a survey: every village point of interest found in the scanned area, and
 * nothing derived from it yet.
 *
 * <p><b>This is the half that cannot leave the server thread.</b> {@code PoiManager} is a
 * {@code SectionStorage} over a plain {@code Long2ObjectOpenHashMap} that loads a whole column off
 * disk on a miss — it is not thread-safe, and no amount of wanting the survey to be off-thread
 * changes that. So the split is drawn where safety actually puts it: reading POIs happens on the
 * server thread, a few chunks per tick, and everything derived from the result — {@link
 * SettlementSurvey} — runs off it. See {@code SettlementRegistrar}.
 *
 * <p>Once filled, this object holds only immutable values ({@link BlockPos}, enums, ints), so
 * handing it to a background thread is safe.
 */
public final class SettlementCensus {

    /** Which trade each vanilla workstation POI belongs to. */
    private static final Map<ResourceKey<PoiType>, Specialty> TRADES = Map.ofEntries(
            Map.entry(PoiTypes.FARMER, Specialty.FARMING),
            Map.entry(PoiTypes.BUTCHER, Specialty.PASTORAL),
            Map.entry(PoiTypes.SHEPHERD, Specialty.PASTORAL),
            Map.entry(PoiTypes.LEATHERWORKER, Specialty.PASTORAL),
            Map.entry(PoiTypes.ARMORER, Specialty.SMITHING),
            Map.entry(PoiTypes.WEAPONSMITH, Specialty.SMITHING),
            Map.entry(PoiTypes.TOOLSMITH, Specialty.SMITHING),
            Map.entry(PoiTypes.FLETCHER, Specialty.SMITHING),
            Map.entry(PoiTypes.LIBRARIAN, Specialty.SCHOLARLY),
            Map.entry(PoiTypes.CARTOGRAPHER, Specialty.SCHOLARLY),
            Map.entry(PoiTypes.CLERIC, Specialty.SCHOLARLY),
            Map.entry(PoiTypes.FISHERMAN, Specialty.FISHING),
            Map.entry(PoiTypes.MASON, Specialty.MASONRY));

    private final List<BlockPos> bells = new ArrayList<>();
    private final List<BlockPos> beds = new ArrayList<>();
    private final List<BlockPos> workstations = new ArrayList<>();
    private final List<Specialty> trades = new ArrayList<>();

    /** Files one POI record. Anything that is not a bell, a bed or a workstation is ignored. */
    public void record(Holder<PoiType> type, BlockPos pos) {
        Optional<ResourceKey<PoiType>> key = type.unwrapKey();
        if (key.isEmpty()) {
            return;
        }
        ResourceKey<PoiType> poi = key.get();
        if (poi.equals(PoiTypes.MEETING)) {
            bells.add(pos.immutable());
            return;
        }
        if (poi.equals(PoiTypes.HOME)) {
            beds.add(pos.immutable());
            return;
        }
        Specialty trade = TRADES.get(poi);
        if (trade != null) {
            workstations.add(pos.immutable());
            trades.add(trade);
        }
    }

    public boolean hasBell() {
        return !bells.isEmpty();
    }

    /**
     * The bell that becomes this settlement's centre: the one nearest whoever triggered the scan.
     *
     * <p>Two bells inside one scan is a real shape — a large village, or two villages that have
     * grown together. Taking the nearest is the v1 answer, and the membership radius then decides
     * whether the second bell later registers a settlement of its own or is swallowed by this one.
     */
    public Optional<BlockPos> centre(BlockPos origin) {
        return bells.stream().min(Comparator.comparingDouble(bell -> bell.distSqr(origin)));
    }

    public int bellCount() {
        return bells.size();
    }

    /**
     * Counts everything within {@code radius} of {@code centre}, horizontally.
     *
     * <p>Horizontal on purpose: a village on a hillside spreads vertically for reasons that have
     * nothing to do with how big it is, and a spherical radius would quietly drop the mine-shaft
     * half of a mountain settlement.
     *
     * <p>Pure arithmetic over immutable values, so this is safe to call from a background thread.
     */
    public SettlementSurvey.Tally tally(BlockPos centre, int radius) {
        long radiusSqr = (long) radius * radius;
        int[] sites = new int[Specialty.values().length];
        int bedCount = 0;
        int workCount = 0;
        long distanceSum = 0;

        for (int i = 0; i < workstations.size(); i++) {
            BlockPos pos = workstations.get(i);
            long distanceSqr = horizontalDistanceSqr(centre, pos);
            if (distanceSqr > radiusSqr) {
                continue;
            }
            sites[trades.get(i).ordinal()]++;
            workCount++;
            distanceSum += (long) Math.sqrt(distanceSqr);
        }
        for (BlockPos pos : beds) {
            long distanceSqr = horizontalDistanceSqr(centre, pos);
            if (distanceSqr > radiusSqr) {
                continue;
            }
            bedCount++;
            distanceSum += (long) Math.sqrt(distanceSqr);
        }

        int counted = workCount + bedCount;
        int meanDistance = counted == 0 ? 0 : (int) (distanceSum / counted);
        return new SettlementSurvey.Tally(sites, bedCount, workCount, meanDistance);
    }

    private static long horizontalDistanceSqr(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
