package hierarchy;

import java.util.Objects;

/**
 * A {@link Hierarchy} backed by two parallel arrays.
 *
 * <p>The arrays are not copied and not validated beyond their lengths: the caller must pass a valid forest
 * (see the invariants in {@link Hierarchy}) and must not modify the arrays after construction.
 */
public class ArrayBasedHierarchy implements Hierarchy {
    private final int[] nodeIds;
    private final int[] depths;

    /**
     * Creates a hierarchy that wraps the given arrays without copying them.
     *
     * @param nodeIds node IDs in DFS order
     * @param depths  depth of each node, parallel to {@code nodeIds}
     * @throws NullPointerException     if an array is {@code null}
     * @throws IllegalArgumentException if the arrays have different lengths
     */
    public ArrayBasedHierarchy(int[] nodeIds, int[] depths) {
        Objects.requireNonNull(nodeIds, "nodeIds");
        Objects.requireNonNull(depths, "depths");
        if (nodeIds.length != depths.length) {
            throw new IllegalArgumentException(
                "nodeIds and depths must have the same length: " + nodeIds.length + " != " + depths.length);
        }
        this.nodeIds = nodeIds;
        this.depths = depths;
    }

    @Override
    public int size() {
        return depths.length;
    }

    @Override
    public int nodeId(int index) {
        return nodeIds[index];
    }

    @Override
    public int depth(int index) {
        return depths[index];
    }
}
