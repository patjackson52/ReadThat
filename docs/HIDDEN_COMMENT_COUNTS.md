# Counting What Disappears: Fast, Honest Collapse UX for Comment Trees

*ReadThat engineering · September 1, 2026*

Collapsing a long comment branch used to create an information gap. ReadThat kept the parent comment visible, but the reader had no way to tell whether the tap hid one reply or an entire discussion. The interaction worked; its result was opaque.

The final UI adds a compact action beneath the collapsed preview:

> **Show 10 hidden replies**

That looks like a small copy change. The interesting part is making the number precise without adding a request, a client-side tree walk, or new work to time to interactive.

## Before and after

These screenshots use the same 20-comment seeded discussion on a Pixel 10 Pro running Android 17. The before image is the parent commit; the after image is the standalone feature commit. In both, the same root comment is collapsed.

<table>
  <tr>
    <th scope="col">Before: collapse has no visible magnitude</th>
    <th scope="col">After: the result is explicit and actionable</th>
  </tr>
  <tr>
    <td><img src="images/comment-collapse-before.png" alt="ReadThat before the feature. A collapsed comment shows its author and preview but no hidden-reply count." width="360"></td>
    <td><img src="images/comment-collapse-after.png" alt="ReadThat after the feature. A collapsed comment has a Show 10 hidden replies button." width="360"></td>
  </tr>
</table>

The post contains 20 comments, but the label says 10—not 19. That is intentional. The response materializes the root and ten replies before the depth-10 continuation boundary. The deeper comments are represented by a cursor and were never visible on this screen, so claiming that collapse hid them would be misleading.

## The UX decision: describe the action and the consequence

We considered terse badges such as `+10`, total-thread language such as “10 replies,” and copy closer to “Expand to see 10 more.” The chosen phrase, “Show 10 hidden replies,” carries the most useful meaning in a small space:

- **Show** makes it an action rather than passive metadata.
- **hidden** ties the number to the reader's collapse action.
- **replies** describes what will return, with singular and plural forms.

The pill is visually secondary to the comment but more discoverable than a tiny count beside the author. It is also a real expansion target. A reader can tap either the existing collapsed header or the explicit action, while accessibility state announces the same hidden-reply count.

The critical semantic choice is what *not* to count. ReadThat has two different kinds of absence:

1. Materialized comments removed from the visible projection by collapse.
2. Comments not materialized yet, represented by a load-more or continue-thread cursor.

The control reports only the first category. Cursor copy continues to communicate unresolved server work separately. This means the hidden count may grow after the reader loads more of that branch, but it never pretends that an unloaded reply was already on the screen.

This was a high-confidence product decision that did not need an A/B test. It adds missing state explanation, follows the existing interaction, and is easy to reverse. Verification focused on the risky edges instead: count semantics, pluralization, accessibility state, depth boundaries, and visual behavior on a real device.

## The technical options

The obvious implementation is to count descendants on the client when the user collapses a node. It is also the wrong place to spend time.

| Option | Benefit | Cost |
| --- | --- | --- |
| Walk the client subtree on collapse | No wire change | O(subtree) work at interaction time; repeats across clients and can land on the main thread |
| Maintain counts only through client mutations | O(1) reads after setup | More state invariants around cache restore, cursor splicing, optimistic create/delete, and multiple clients |
| Query total descendants from storage | Can report an authoritative corpus total | Extra database work, different semantics from “hidden by this collapse,” and potentially another request |
| Count during server tree assembly | O(1) client lookup, no extra request, one contract for Android and web | One integer per materialized node and a versioned wire/cache change |

We chose the last option. The server already knows exactly which comment nodes will be returned. It can annotate that selected tree once, cache it, and let every client consume the result cheaply.

## Reusing the heap-built tree

The Worker stores comments as an adjacency list and groups the fetched corpus by `parent_id`. A max-heap then selects the highest-ranked eligible comments within ReadThat's fixed response budgets: 8 comments for the fast first view, 200 for refinement, and a maximum depth of 10.

The hidden count does not change selection. It is derived after selection from the nodes already in the response:

```ts
function materializedDescendantCount(children: TreeNode[]): number {
  let count = 0;
  for (const child of children) {
    if (child.type === "comment") count += 1 + child.descendantCount;
  }
  return count;
}
```

Normal tree assembly is bottom-up: children exist before their parent is serialized. Each parent therefore sums `1 + child.descendantCount`. Load-more nodes contribute zero.

The flat load-more endpoint has a different shape, but the heap gives us another useful invariant: pop order is parent-before-child. One reverse pass over the selected comments propagates every child's `1 + count` to its selected parent. There is no descendant walk per node.

If `m` comments are materialized, the added server work is O(m). It does not alter the heap selection's asymptotic cost. Main-tree assembly needs no second tree structure; load-more accounting uses a set and count map proportional to the returned nodes.

## Keeping the client off the critical path

Each comment now carries `descendantCount`. When the flattener reaches a collapsed boundary, it reads that integer and stops:

```kotlin
val hidden = if (collapsed) node.descendantCount.coerceAtLeast(0) else 0
```

The count lookup is O(1). The existing visible-row projection still rebuilds as it did before, but the feature adds no hidden-subtree traversal, coroutine, database read, or network round trip. That distinction matters: “O(1) collapse count” does not mean the entire UI update is magically constant time; it means this feature adds no size-dependent work to the interaction.

The web client consumes the same field. Local tree changes preserve the invariant too. Android's iterative editor and cursor splicer recompute counts only along the copied ancestor spine, so an optimistic reply, rollback, delete, or load-more replacement cannot leave a stale label. Older or locally assembled nodes retain a bottom-up default as a compatibility fallback.

## Network and caching tradeoffs

The network cost is deliberately boring: one integer property per materialized comment, inside requests the app already makes.

For the live seeded payloads used during implementation, removing only `descendantCount` and reserializing the same JSON produced these deltas:

| Payload | Materialized comments | Added raw JSON | Added gzip bytes |
| --- | ---: | ---: | ---: |
| 20-level screenshot fixture at depth 10 | 11 | 221 B | 33 B |
| 200 selected comments from a wide thread | 200 | 4,000 B | 14 B |

The repeated property name and common zero values compress extremely well. More importantly, the feature adds no request waterfall, so it does not postpone interactivity while waiting for count metadata.

Caching required one explicit migration decision. Comment trees are cached by post, fixed count/depth, logical root, post version, and a five-minute server TTL; responses also carry a private 15-second HTTP cache policy. A rolling deploy could otherwise serve a pre-feature cached payload to a new client. Prefixing the logical root with `v2` cleanly separates the new wire shape without flushing unrelated cache data.

ReadThat's L1 and Room-backed L2 caches store the count with the tree, so warm rendering preserves the same O(1) lookup. Default values keep old local data readable during rollout, while the versioned server key prevents old remote payloads from silently looking authoritative.

## Final implementation

The shipped change is intentionally narrow:

1. Add `descendantCount` to full-tree and load-more response models.
2. Calculate it during existing bottom-up assembly, excluding cursors.
3. Version the server's comment-tree cache key.
4. Decode and persist the field on Android and web.
5. Read it at a collapsed boundary instead of walking the subtree.
6. Maintain it along local optimistic-edit and cursor-splice paths.
7. Render a clickable, pluralized “Show N hidden replies” control and matching accessibility state.

Tests pin the important contracts: the 8-comment response reports seven descendants, the depth-10 response reports ten, cache hits preserve the metadata, the flattener accepts a server-authored count without children present, cursor nodes are excluded, and the label handles zero, singular, and plural values. The final visual check used the exact parent and feature commits on the Pixel 10 Pro shown above.

The result is a clearer collapse interaction with no new client critical-path work. The general lesson is broader than comment counts: when a bounded server projection already knows what it selected, attach cheap presentation metadata there. Clients should not rediscover the shape of data they just received—especially at the moment a user taps.

## Code landmarks

- [`backend/src/comments.ts`](../backend/src/comments.ts) — heap selection, bottom-up counts, load-more reverse pass, and cache versioning
- [`shared/src/commonMain/kotlin/dev/readthat/comments/domain/CommentWire.kt`](../shared/src/commonMain/kotlin/dev/readthat/comments/domain/CommentWire.kt) — shared comment model and local count invariant
- [`shared/src/commonMain/kotlin/dev/readthat/comments/domain/CommentFlattener.kt`](../shared/src/commonMain/kotlin/dev/readthat/comments/domain/CommentFlattener.kt) — O(1) count lookup at collapse boundaries
- [`feature/detail-ui/src/commonMain/kotlin/dev/readthat/detail/ui/SharedPostDetailScreen.kt`](../feature/detail-ui/src/commonMain/kotlin/dev/readthat/detail/ui/SharedPostDetailScreen.kt) — Android/iOS shared UI and accessibility treatment
- [`www/src/logic.ts`](../www/src/logic.ts) and [`www/src/post-page.tsx`](../www/src/post-page.tsx) — web invariant maintenance and presentation

The isolated case-study commit is `e07ee1e`; the integrated feature commit on `main` is `eea8ffe`.
