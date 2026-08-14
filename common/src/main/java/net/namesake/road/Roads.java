package net.namesake.road;

import net.namesake.settlement.Settlements;

/**
 * The one door to the road graph, and the memo that stops it being rebuilt on every drain.
 *
 * <p><b>The graph is derived, so somebody has to say when it is stale.</b> {@link RoadGraph} is a
 * pure function of the settlement table and is never persisted; {@link Settlements#revision()} is a
 * counter the only writing method increments. One entry is enough: a server has one settlement table
 * and the headless simulation has its own, and if the two alternate the memo simply misses and pays
 * for a rebuild that costs microseconds at any population a save can hold.
 *
 * <p><b>Callers ask on the server thread.</b> The A* worker is handed a graph rather than fetching
 * one, because a background thread reading a table the server thread is writing is the class of
 * defect session 03 avoided by keeping the census on the server thread.
 */
public final class Roads {

    private static Settlements memoTable;
    private static int memoRevision = -1;
    private static RoadGraph memo = RoadGraph.EMPTY;

    private Roads() {
    }

    /** The road graph for this settlement table, rebuilt only when the table has changed. */
    public static synchronized RoadGraph graphOf(Settlements settlements) {
        if (settlements == memoTable && settlements.revision() == memoRevision) {
            return memo;
        }
        memo = RoadGraph.of(settlements.all());
        memoTable = settlements;
        memoRevision = settlements.revision();
        return memo;
    }

    /** Every settlement with a road to this one. Empty for a village with no neighbours. */
    public static int[] neighboursOf(Settlements settlements, int settlementId) {
        return graphOf(settlements).neighboursOf(settlementId);
    }

    /** Drops the memo. A new world's settlement table is a different table, so this is belt only. */
    public static synchronized void forget() {
        memoTable = null;
        memoRevision = -1;
        memo = RoadGraph.EMPTY;
    }
}
