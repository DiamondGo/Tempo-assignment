# Tempo Senior Developer Assessment (Java)

Requires Java 21. Run the tests with:

```bash
./gradlew test
```

## Task 1: Hierarchy Filter

- Implementation: [`HierarchyFilter.java`](src/main/java/hierarchy/HierarchyFilter.java)
- Tests: [`HierarchyFilterTest.java`](src/test/java/hierarchy/HierarchyFilterTest.java)

The original single file was split into `Hierarchy`, `ArrayBasedHierarchy`, `HierarchyFilter` and a test class.

### Approach

One pass over the input. When a node fails the predicate I remember its depth and skip the following nodes that are
deeper than that, since in DFS order they are exactly its subtree. The first node at the same or a smaller depth ends
the skip.

Kept nodes keep their original depth (all their ancestors are kept too), so nothing needs to be recalculated.
O(n) time, O(n) space for the result, no recursion. The predicate is not called for nodes inside a rejected subtree.

### Tests

Besides the example from the task, the tests cover edge cases (empty input, single node, rejected root / leaf / inner
node, depth dropping by several levels after a rejected node, etc.), which nodes the predicate is called for, that the
input is not modified, and 1M-node inputs (a deep chain and a flat forest) with a timeout. There is also a randomized
test that compares the result with a simple recursive implementation on 2,000 generated forests.

### Assumptions

- The input satisfies the documented invariants (first depth is 0, depth increases by at most 1). It is not validated.
- Node IDs are unique.
- The predicate has no side effects, so not calling it for nodes in a rejected subtree is fine.
- The filter returns a new hierarchy and never modifies or shares the input arrays.
- `ArrayBasedHierarchy` doesn't copy its arrays. I added null checks and a check that both arrays have the same length.

## Task 2: Code Review

See [`SimpleCache.md`](SimpleCache.md).
