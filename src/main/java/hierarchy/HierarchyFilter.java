package hierarchy;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * A node is present in the filtered hierarchy iff its node ID passes the predicate and all of its ancestors pass it as well.
 */
public final class HierarchyFilter {
    private HierarchyFilter() {
    }

    /**
     * Returns a new hierarchy that contains only the nodes whose ID passes {@code nodeIdPredicate} and whose
     * ancestors all pass it too. The relative order and the depths of the kept nodes are unchanged, and
     * {@code hierarchy} is not modified.
     *
     * <p>The predicate is evaluated at most once per node, in DFS order, and is not evaluated for descendants of a
     * rejected node. It is expected to be free of side effects.
     *
     * @param hierarchy       the forest to filter, expected to satisfy the {@link Hierarchy} invariants
     * @param nodeIdPredicate decides whether a node ID may be present in the result
     * @return the filtered hierarchy; empty if no root passes
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Hierarchy filter(Hierarchy hierarchy, IntPredicate nodeIdPredicate) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(nodeIdPredicate, "nodeIdPredicate");

        int size = hierarchy.size();
        int[] nodeIds = new int[size];
        int[] depths = new int[size];
        int count = 0;
        // Depth of the root of the subtree currently being skipped; MAX_VALUE when not skipping.
        int skipDepth = Integer.MAX_VALUE;

        for (int i = 0; i < size; i++) {
            int depth = hierarchy.depth(i);
            if (depth > skipDepth) {
                continue;
            }
            int nodeId = hierarchy.nodeId(i);
            if (nodeIdPredicate.test(nodeId)) {
                nodeIds[count] = nodeId;
                depths[count] = depth;
                count++;
                skipDepth = Integer.MAX_VALUE;
            } else {
                skipDepth = depth;
            }
        }

        // The arrays are freshly allocated, so they can be handed over as they are when nothing was removed.
        if (count == size) {
            return new ArrayBasedHierarchy(nodeIds, depths);
        }
        return new ArrayBasedHierarchy(Arrays.copyOf(nodeIds, count), Arrays.copyOf(depths, count));
    }
}
