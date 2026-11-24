package hierarchy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyFilterTest {

    private static final Hierarchy EXAMPLE = hierarchy(
        new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
        new int[]{0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2}
    );

    @Test
    void filtersExampleFromTaskDescription() {
        assertFiltered(EXAMPLE, id -> id % 3 != 0,
            new int[]{1, 2, 5, 8, 10, 11},
            new int[]{0, 1, 1, 0, 1, 2});
    }

    @Test
    void emptyHierarchyStaysEmpty() {
        assertFiltered(hierarchy(new int[0], new int[0]), id -> true, new int[0], new int[0]);
    }

    @Test
    void keepsEverythingWhenAllNodesPass() {
        assertFiltered(EXAMPLE, id -> true, ids(EXAMPLE), depths(EXAMPLE));
    }

    @Test
    void removesEverythingWhenNoNodePasses() {
        assertFiltered(EXAMPLE, id -> false, new int[0], new int[0]);
    }

    @Test
    void singleNode() {
        Hierarchy single = hierarchy(new int[]{42}, new int[]{0});
        assertFiltered(single, id -> true, new int[]{42}, new int[]{0});
        assertFiltered(single, id -> false, new int[0], new int[0]);
    }

    @Test
    void rejectedRootRemovesWholeTreeButKeepsFollowingTrees() {
        assertFiltered(EXAMPLE, id -> id != 1,
            new int[]{6, 7, 8, 9, 10, 11},
            new int[]{0, 1, 0, 1, 1, 2});
    }

    @Test
    void rejectedLastTreeIsRemoved() {
        assertFiltered(EXAMPLE, id -> id != 8,
            new int[]{1, 2, 3, 4, 5, 6, 7},
            new int[]{0, 1, 2, 3, 1, 0, 1});
    }

    @Test
    void rejectedLeafRemovesOnlyThatLeaf() {
        assertFiltered(EXAMPLE, id -> id != 4,
            new int[]{1, 2, 3, 5, 6, 7, 8, 9, 10, 11},
            new int[]{0, 1, 2, 1, 0, 1, 0, 1, 1, 2});
    }

    @Test
    void rejectedInnerNodeRemovesDescendantsButKeepsSiblings() {
        // 2 is rejected: 3 and 4 go with it even though they pass the predicate, sibling 5 stays.
        assertFiltered(EXAMPLE, id -> id != 2,
            new int[]{1, 5, 6, 7, 8, 9, 10, 11},
            new int[]{0, 1, 0, 1, 0, 1, 1, 2});
    }

    @Test
    void rejectedNodesInConsecutiveSubtrees() {
        // 1
        // - 2 (rejected)
        // - - 3
        // - 4 (rejected)
        // - - 5
        // - 6
        Hierarchy h = hierarchy(new int[]{1, 2, 3, 4, 5, 6}, new int[]{0, 1, 2, 1, 2, 1});
        assertFiltered(h, id -> id != 2 && id != 4, new int[]{1, 6}, new int[]{0, 1});
    }

    @Test
    void depthDropsByMoreThanOneLevelAfterRejectedNode() {
        // 1
        // - 2
        // - - 3 (rejected)
        // - - - 4
        // 5            <- depth falls from 3 to 0, a new tree starts and must be kept
        // - 6
        Hierarchy h = hierarchy(new int[]{1, 2, 3, 4, 5, 6}, new int[]{0, 1, 2, 3, 0, 1});
        assertFiltered(h, id -> id != 3, new int[]{1, 2, 5, 6}, new int[]{0, 1, 0, 1});
    }

    @Test
    void rejectedNodeFollowedByRejectedRootOfNextTree() {
        // Same shape as above, but the next tree's root is rejected too: its child must go as well.
        Hierarchy h = hierarchy(new int[]{1, 2, 3, 4, 5, 6}, new int[]{0, 1, 2, 3, 0, 1});
        assertFiltered(h, id -> id != 3 && id != 5, new int[]{1, 2}, new int[]{0, 1});
    }

    @Test
    void keptNodeAfterRejectedSiblingStillHasItsOwnChildren() {
        // 1
        // - 2 (rejected)
        // - 3
        // - - 4   <- deeper than the rejected sibling, must not be skipped any more
        Hierarchy h = hierarchy(new int[]{1, 2, 3, 4}, new int[]{0, 1, 1, 2});
        assertFiltered(h, id -> id != 2, new int[]{1, 3, 4}, new int[]{0, 1, 2});
    }

    @Test
    void predicateIsEvaluatedOncePerNodeAndNotForDescendantsOfRejectedNode() {
        List<Integer> evaluated = new ArrayList<>();
        HierarchyFilter.filter(EXAMPLE, id -> {
            evaluated.add(id);
            return id != 2 && id != 6;
        });
        assertEquals(List.of(1, 2, 5, 6, 8, 9, 10, 11), evaluated);
    }

    @Test
    void inputIsNotModified() {
        String before = EXAMPLE.formatString();
        HierarchyFilter.filter(EXAMPLE, id -> id % 2 == 0);
        assertEquals(before, EXAMPLE.formatString());
    }

    @Test
    void resultDoesNotShareArraysWithInput() {
        int[] ids = {1, 2, 3};
        int[] depths = {0, 1, 1};
        Hierarchy filtered = HierarchyFilter.filter(hierarchy(ids, depths), id -> true);

        ids[0] = 99;
        depths[1] = 7;

        assertArrayEquals(new int[]{1, 2, 3}, ids(filtered));
        assertArrayEquals(new int[]{0, 1, 1}, depths(filtered));
    }

    @Test
    void worksWithAnyHierarchyImplementation() {
        Hierarchy custom = new ListHierarchy(List.of(
            new int[]{1, 0}, new int[]{2, 1}, new int[]{3, 2}, new int[]{4, 1}, new int[]{5, 0}));
        assertFiltered(custom, id -> id != 2, new int[]{1, 4, 5}, new int[]{0, 1, 0});
    }

    @Test
    void predicateExceptionPropagates() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> HierarchyFilter.filter(EXAMPLE, id -> {
                throw new IllegalStateException("boom");
            }));
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> HierarchyFilter.filter(null, id -> true));
        assertThrows(NullPointerException.class, () -> HierarchyFilter.filter(EXAMPLE, null));
    }

    @Test
    void arrayBasedHierarchyRejectsArraysOfDifferentLength() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> new ArrayBasedHierarchy(new int[]{1, 2, 3}, new int[]{0, 1}));
        assertTrue(thrown.getMessage().contains("3 != 2"), thrown.getMessage());
    }

    @Test
    void arrayBasedHierarchyRejectsNullArrays() {
        assertThrows(NullPointerException.class, () -> new ArrayBasedHierarchy(null, new int[0]));
        assertThrows(NullPointerException.class, () -> new ArrayBasedHierarchy(new int[0], null));
    }

    @Test
    void deepChainIsCutAtFirstRejectedNode() {
        int n = 1_000_000;
        int[] ids = new int[n];
        int[] depths = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            depths[i] = i;
        }
        Hierarchy chain = hierarchy(ids, depths);

        Hierarchy filtered = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> HierarchyFilter.filter(chain, id -> id != 500_000));

        assertEquals(500_000, filtered.size());
        assertEquals(499_999, filtered.nodeId(filtered.size() - 1));
    }

    @Test
    void wideForestOfManyRoots() {
        int n = 1_000_000;
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
        }
        Hierarchy flat = hierarchy(ids, new int[n]);

        Hierarchy filtered = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> HierarchyFilter.filter(flat, id -> id % 2 == 1));

        assertEquals(n / 2, filtered.size());
        for (int i = 0; i < filtered.size(); i++) {
            assertEquals(2 * i + 1, filtered.nodeId(i));
            assertEquals(0, filtered.depth(i));
        }
    }

    @Test
    void matchesReferenceImplementationOnRandomForests() {
        Random random = new Random(12345);
        for (int run = 0; run < 2_000; run++) {
            Hierarchy h = randomForest(random, random.nextInt(60));
            double rejectRate = random.nextDouble();
            boolean[] rejected = new boolean[h.size() + 1];
            for (int i = 0; i < rejected.length; i++) {
                rejected[i] = random.nextDouble() < rejectRate;
            }
            IntPredicate predicate = id -> !rejected[id];

            Hierarchy actual = HierarchyFilter.filter(h, predicate);

            String context = "run " + run + ", input: " + h.formatString() + ", rejected: " + Arrays.toString(rejected);
            assertValidHierarchy(actual);
            assertEquals(referenceFilter(h, predicate).formatString(), actual.formatString(), context);
        }
    }

    /** Builds an explicit tree and walks it recursively, descending only into nodes that pass. */
    private static Hierarchy referenceFilter(Hierarchy h, IntPredicate predicate) {
        List<Node> roots = new ArrayList<>();
        Deque<Node> path = new ArrayDeque<>(); // ancestors of the next node, deepest on top
        for (int i = 0; i < h.size(); i++) {
            Node node = new Node(h.nodeId(i), h.depth(i));
            while (path.size() > node.depth) {
                path.pop();
            }
            if (path.isEmpty()) {
                roots.add(node);
            } else {
                path.peek().children.add(node);
            }
            path.push(node);
        }

        List<Integer> ids = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        for (Node root : roots) {
            collect(root, predicate, ids, depths);
        }
        return hierarchy(toArray(ids), toArray(depths));
    }

    private static void collect(Node node, IntPredicate predicate, List<Integer> ids, List<Integer> depths) {
        if (!predicate.test(node.id)) {
            return;
        }
        ids.add(node.id);
        depths.add(node.depth);
        for (Node child : node.children) {
            collect(child, predicate, ids, depths);
        }
    }

    private static final class Node {
        final int id;
        final int depth;
        final List<Node> children = new ArrayList<>();

        Node(int id, int depth) {
            this.id = id;
            this.depth = depth;
        }
    }

    /** Generates a valid forest with unique node IDs 1..size. */
    private static Hierarchy randomForest(Random random, int size) {
        int[] ids = new int[size];
        int[] depths = new int[size];
        for (int i = 0; i < size; i++) {
            ids[i] = i + 1;
            depths[i] = i == 0 ? 0 : random.nextInt(depths[i - 1] + 2);
        }
        return hierarchy(ids, depths);
    }

    /** A minimal non-array implementation, used to check that the filter only relies on the interface. */
    private record ListHierarchy(List<int[]> nodes) implements Hierarchy {
        @Override
        public int size() {
            return nodes.size();
        }

        @Override
        public int nodeId(int index) {
            return nodes.get(index)[0];
        }

        @Override
        public int depth(int index) {
            return nodes.get(index)[1];
        }
    }

    private static void assertValidHierarchy(Hierarchy h) {
        for (int i = 0; i < h.size(); i++) {
            int depth = h.depth(i);
            int maxDepth = i == 0 ? 0 : h.depth(i - 1) + 1;
            assertTrue(depth >= 0 && depth <= maxDepth, "invalid depth at index " + i + ": " + h.formatString());
        }
    }

    private static void assertFiltered(Hierarchy input, IntPredicate predicate, int[] expectedIds, int[] expectedDepths) {
        Hierarchy actual = HierarchyFilter.filter(input, predicate);
        assertArrayEquals(expectedIds, ids(actual), "node ids");
        assertArrayEquals(expectedDepths, depths(actual), "depths");
    }

    private static int[] ids(Hierarchy h) {
        int[] result = new int[h.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = h.nodeId(i);
        }
        return result;
    }

    private static int[] depths(Hierarchy h) {
        int[] result = new int[h.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = h.depth(i);
        }
        return result;
    }

    private static Hierarchy hierarchy(int[] ids, int[] depths) {
        return new ArrayBasedHierarchy(ids, depths);
    }

    private static int[] toArray(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }
}
