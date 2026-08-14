package net.namesake.road;

/**
 * Two settlements that are neighbours. Undirected, and normalised so it can be a map key.
 *
 * <p><b>Not persisted, and that is the point of it being a record of two ints.</b> The whole road
 * network is a pure function of the settlement table — which settlements exist, and where their
 * bells are — and both of those have been on disk since schema 3. Storing the graph would be
 * caching, and session 03 deleted {@code Settlement.culture} for exactly that. It is recomputed on
 * load, deterministically, and the world it describes does not move.
 */
public record RoadEdge(int a, int b) {

    public RoadEdge {
        if (a == b) {
            throw new IllegalArgumentException("A settlement is not its own neighbour: " + a);
        }
        if (a > b) {
            int swap = a;
            a = b;
            b = swap;
        }
    }

    /** The settlement at the other end, or {@code -1} if this edge does not touch {@code id}. */
    public int other(int id) {
        return id == a ? b : id == b ? a : -1;
    }

    public boolean touches(int id) {
        return id == a || id == b;
    }

    @Override
    public String toString() {
        return a + "-" + b;
    }
}
