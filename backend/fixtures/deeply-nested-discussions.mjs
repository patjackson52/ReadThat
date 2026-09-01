export const community = {
  fixtureVersion: 1,
  name: "deeply_nested",
  displayName: "Deeply Nested",
  description: "A performance lab for pathological discussion trees: wide fan-out, 10–20-level reply chains, hybrid forests, long bodies, load-more math, offline caches, and rendering experiments.",
  accessType: "public",
  owner: "martin_builds",
  member: "evan_ontrail",
};

const sources = {
  d1Limits: "https://developers.cloudflare.com/d1/platform/limits/",
  composeLists: "https://developer.android.com/develop/ui/compose/lists",
  composePerformance: "https://developer.android.com/develop/ui/compose/performance/bestpractices",
  pagingCache: "https://developer.android.com/topic/libraries/architecture/paging/v3-network-db",
  sqliteRecursive: "https://sqlite.org/lang_with.html",
  http: "https://www.rfc-editor.org/rfc/rfc9110.html",
  websocket: "https://www.rfc-editor.org/rfc/rfc6455.html",
};

export const posts = [
  {
    fixtureId: "initial-fetch-budget",
    author: "martin_builds",
    title: "The first 200 comments are a product decision, not a default",
    workload: { kind: "wide", rootCount: 50 },
    links: [sources.composeLists, sources.pagingCache],
    debatePoints: [
      ["Time to first useful comment", "Optimize for the first coherent screen, not the first byte. Eight ranked comments can be useful when they preserve visible branches and arrive before the post-reading transition finishes."],
      ["A count budget is not enough", "A budget of 200 nodes can become 200 roots or one long branch. The contract needs both a count cap and a traversal-depth cap, plus stable expansion behavior between phases."],
      ["Prefetch is a probability bet", "Prefetch only after dwell or another intent signal, coalesce it with navigation, and cap retained trees. Otherwise a faster tap-through is purchased with uncontrolled radio and cache work."],
      ["Measure decoded work", "Wire bytes are only one budget. Track JSON parsing, normalization, flattening, composition, layout, and the number of rows that become visible after the merge."],
      ["Protect what is already on screen", "The second response may add comments, but it should not reorder or auto-expand the eight comments the user already started reading."],
    ],
    body: `A comment screen has at least three clocks: **time to first useful content**, time until the discussion feels complete enough to browse, and time until the entire reachable subtree is locally available. Treating those clocks as one request is the easiest way to make a fast network look slow. A fixed “fetch 200” default sounds concrete, but it hides the actual product question: what is the smallest response that lets a person begin reading without immediately hitting a placeholder or seeing the tree rearrange under their finger?

For this demo I would start with a two-phase contract. Phase one asks for **8 selected comments at depth 10**. It is small enough to prefetch after a feed dwell signal and large enough to cover a phone viewport with real bodies rather than skeletons. Phase two asks for **200 selected comments at the same depth**. The client coalesces a navigation with an in-flight prefetch, then merges the larger tree behind the already-visible rows. Fixed sizes matter because the server can cache exactly those shapes and clients can reason about their cost. The [Android network-plus-database Paging guidance](${sources.pagingCache}) is useful here even though a tree is not a simple linear page: the durable database should remain the UI source of truth, while the network fills gaps.

The subtle contract is not the number 8 or 200. It is **stability across the transition**. Suppose phase one contains a root that looks like a leaf, while phase two reveals twelve children. Blind replacement causes the row to sprout replies and pushes everything below it down. A person who was reading comment four is suddenly reading comment nine. The safer merge records which phase-one nodes were visible and childless, retains root order, and auto-collapses only the branches that would otherwise pop open. New roots append below existing roots. Explicit user collapse state always wins over both responses.

Count and depth are independent budgets. Two hundred root comments exercise breadth; a single twenty-level chain exercises depth; a ranked traversal can spend the same count budget very differently depending on scores. The server therefore selects candidates using a priority queue but emits a normal tree plus bounded \`load_more\` cursors. At depth 10 the cursor should become “continue this thread,” which re-roots a new screen and resets visual indentation. That is a navigation decision, not merely another inline load.

Rendering is a separate budget from fetching. [Compose lazy lists](${sources.composeLists}) compose and lay out the visible window rather than every supplied item, but the app still pays to decode and normalize every object it fetched. A healthy trace separates request time, compressed bytes, decoded bytes, parse time, tree-to-map normalization, visible-list flattening, first row composed, and frame misses while the second phase merges. “The API was 90 ms” is not an answer if 20 ms of parsing and 35 ms of main-thread transformation follow it.

Prefetch should be bounded by probability and eviction cost. A feed card visible for 500–800 ms is a stronger signal than a card that flashed through the viewport. Only the small phase belongs in speculative work. In-flight requests should be shared between feed and detail rather than duplicated, completed trees should live in a small account-scoped LRU, and Room should retain a larger but still bounded set for revisit and offline entry. Media, comment trees, and post bodies need separate budgets because they have different reuse and byte profiles.

My starting policy is therefore **8 now, 200 next, 10 levels per screen, 100 IDs per expansion cursor**, followed by measurement. It is intentionally a hypothesis, not a universal constant. On a tablet, eight short comments may leave most of the screen blank. On a phone with long Markdown bodies, three comments may fill multiple viewports. A more mature system could choose among a few cacheable response classes using viewport and network class, but it should resist per-device arbitrary counts that destroy shared cacheability.

Questions for this 50-root fixture:

- Is phase one useful before the post body is fully read, or is the prefetch early enough already?
- Should the second phase begin immediately, after the first comment becomes visible, or only near the first cursor?
- Which rows must retain exact position when phase two merges?
- Do we optimize the p50 tap or constrain the p95 radio/cache cost across all feed impressions?
- What evidence would justify changing 8/200/10 rather than simply making the numbers larger?`,
  },
  {
    fixtureId: "more-comments-accounting",
    author: "evan_ontrail",
    title: "Who owns “872 more comments”? Counting omitted work without lying",
    workload: { kind: "wide", rootCount: 250 },
    links: [sources.sqliteRecursive, sources.http],
    debatePoints: [
      ["Server authority", "The server knows the filtered corpus and should author the cursor's omitted-ID set. A client cannot infer comments it has never been told exist."],
      ["Local presentation", "The client reads an authoritative total-descendant count for collapse; cursor counts still describe bounded continuation candidates and use different labels."],
      ["Bounded cursor identity", "Chunk omitted child IDs so expansion requests remain bounded. A thousand UUIDs in one button payload is a hidden pagination bug."],
      ["Mutation drift", "Counts are snapshots. New, deleted, or moderated comments can make a cursor stale, so expansion should tolerate missing IDs and return replacement cursors."],
      ["Subtree totals", "If product needs 'all descendants' rather than immediate omitted candidates, store or calculate that metric explicitly; do not overload child_count."],
    ],
    body: `“More comments” looks like a label, but it is really a consistency contract. When a client shows **872 more comments**, users reasonably assume the number describes comments that exist and are reachable from that control. The client cannot manufacture that fact from the 128 nodes it happened to receive. It does not know about moderation filters, blocked authors, deleted ancestors, ranking exclusions, or comments created after the response snapshot. The server owns corpus membership; therefore the server must author the omission count and enough cursor identity to request the omitted work.

That does not mean every displayed number comes from the server. There are at least three different counts:

1. **Omitted candidates represented by a cursor.** Server-calculated and sent with the cursor.
2. **Total descendants hidden by a local collapse.** Server-authored from a maintained subtree aggregate.
3. **Materialized descendants in this response.** A transport detail used for rendering, not collapse copy.

Those counts deserve different language. “100 more replies” can mean this cursor contains 100 immediate candidate IDs. “12 hidden” means the collapsed comment has 12 total descendants, including continuations. “1.4k replies” might be a post-level approximate total. Reusing one number for all three creates bugs that are hard to see in small fixtures.

The current API uses a Reddit-style stateless cursor: \`parentId\`, \`remainingCount\`, and a bounded list of \`childIds\`. The list is deliberately capped at 100 IDs. If a root-level selection leaves 800 comments unreturned, the payload contains eight separate cursor nodes instead of one control carrying 800 UUIDs. Expansion posts at most 100 IDs back to \`/comments/more\`, and the server can select up to 100 comments below them. That keeps request bodies and SQL bound parameters predictable.

There is a UX tradeoff. Eight adjacent “Load 100 more comments” rows are mechanically honest but visually noisy. A presentation layer could group adjacent cursor chunks into one control that says “Load 800 more comments” while internally scheduling one chunk at a time. If it does, the grouping is client presentation over **server-authored chunk counts**. The client must not assume that all chunks still resolve: comments can be deleted, access can change, and the corpus snapshot can move between taps.

For nested omissions, immediate-child counts and subtree counts diverge quickly. One omitted child might have 600 descendants. A cursor with \`remainingCount = 1\` is correct if it represents one next candidate, but surprising if the UI label implies only one comment remains in the discussion. If product requires a subtree total, add a field whose name states that meaning, such as \`omittedSubtreeCount\`, and define whether it is exact, approximate, or snapshot-bound. In SQL, an adjacency list can be traversed with a [recursive common table expression](${sources.sqliteRecursive}); at high read volume, a maintained aggregate may be cheaper, but then every insert/delete path must preserve it.

Snapshot semantics matter more than exact arithmetic. A load-more cursor should either carry an opaque snapshot/version token or be documented as best effort against the latest visible corpus. This sample chooses best effort with stable child IDs. Missing IDs are ignored, new children are not silently smuggled into an old cursor, and replacement cursors describe descendants found while expanding the requested set. That makes retry behavior simple and keeps the server stateless.

REST is a good fit for this interaction because each expansion has a resource-like input and a bounded response. [HTTP semantics](${sources.http}) give GET retrieval strong caching properties; this sample uses POST for \`morechildren\` because the bounded ID set is request content rather than an unwieldy query string. A websocket notification can later say “this post changed,” but it should not become the authority for reconstructing omission counts after disconnects.

Questions for this 250-root fixture:

- Should adjacent 100-ID cursors render separately, or as one aggregated button with progressive expansion?
- Does the label describe immediate omitted candidates or all omitted descendants?
- Are counts exact at one snapshot, approximate, or live?
- What should happen when 7 of 100 requested IDs were deleted before expansion?
- Can the API expose enough information to be honest without sending the entire hidden tree?`,
  },
  {
    fixtureId: "thread-data-structures",
    author: "martin_builds",
    title: "A comment thread is not a heap: storage, selection, and rendering structures",
    workload: { kind: "wide", rootCount: 1000 },
    links: [sources.sqliteRecursive, sources.composePerformance],
    debatePoints: [
      ["Adjacency-list storage", "Persist each comment once with post_id and parent_id, then index the access paths. A recursive JSON blob makes partial updates and moderation unnecessarily expensive."],
      ["Heap for bounded selection", "A max-heap is useful while choosing the next ranked candidate under a node budget. It is an algorithmic tool, not the domain model or cache representation."],
      ["Normalized client state", "Use an id-to-node map plus ordered child-id lists, with cursors as nodes. This makes splice, vote, edit, collapse, and dedupe localized."],
      ["Flat visible projection", "Render a pre-order list of rows with derived depth. Rebuild or incrementally patch that projection when expansion state changes."],
      ["Iterative traversal", "Use explicit stacks for flattening and cache encoding so adversarial depth cannot overflow the call stack."],
    ],
    body: `The question “is the comment structure a heap, tree, list, or map?” has no single answer because storage, selection, state mutation, and rendering have different optimal representations. Calling the entire feature “a tree” is conceptually correct but operationally incomplete. Calling it “a heap” confuses a temporary ranking algorithm with the data itself.

**Server storage: adjacency list.** Store one row per comment with \`id\`, \`post_id\`, nullable \`parent_id\`, author, body, score, version, timestamps, and a denormalized structural depth. Index \`(post_id, parent_id, score DESC, id)\` for sibling lookup and \`(post_id, created_at)\` for corpus scans. This shape supports independent edits, moderation, votes, and inserts. A giant recursive JSON document makes a one-comment edit rewrite or invalidate a large blob and creates awkward concurrency. SQLite can walk adjacency lists using [recursive CTEs](${sources.sqliteRecursive}), but hot tree assembly may still be cheaper in application code after one bounded corpus query.

**Selection: max-heap or priority queue.** Given a count budget, push eligible roots into a max-heap ordered by score and stable ID. Pop the best candidate, add it to the selected set, and push its children if the depth budget permits. Stop at the node limit; group remaining candidates by parent into \`load_more\` cursors. The heap answers “which candidate should consume the next slot?” It is discarded after response construction. It is not how comments should be persisted or presented.

**Wire format: recursive tree plus explicit cursor nodes.** A recursive response is easy to consume for an initial bounded tree and keeps parent-child order visible in the payload. Structural depth need not cross the wire because position already determines render depth; duplicated depth can only agree with the tree or contradict it. Load-more nodes belong in the same union as comments because they occupy a stable ordered position and need a stable UI key.

**Client state: normalized map plus ordered child lists.** After decoding, normalize each comment into \`Map<CommentId, Comment>\` and keep each parent's ordered child IDs separately. Keep root IDs as another ordered list. A cursor is an entity with its own ID, parent, omitted count, and child-ID chunk. This makes a vote update O(1), an edit localized, and a load-more splice proportional to the returned page rather than the entire corpus. It also prevents object duplication when a focused permalink and the main tree reference the same comment.

**Rendering: flat visible list.** \`LazyColumn\` wants ordered rows with stable keys, content types, and indentation metadata. It does not want to recursively compose arbitrary nested \`Column\`s. Flatten the normalized tree in pre-order using an explicit stack. Skip descendants of collapsed nodes, insert cursor rows at their structural positions, and replace depth-cap cursors with “continue thread” rows. The [Compose performance guidance](${sources.composePerformance}) emphasizes stable lazy-layout keys and moving expensive calculations outside composition; both apply directly here.

This sample currently keeps a recursive domain tree at the repository boundary, then iteratively flattens it. The Room L2 cache stores normalized rows with parent IDs and sort indices, and reconstructs the domain tree bottom-up without recursion. That split is pragmatic: the wire/domain model remains readable, persistence stays update-friendly, and rendering gets exactly the linear projection it needs.

There are two common traps. First, do not use the database's \`depth\` column as permanent visual indentation. A permalink rooted at level 14 should render its root at level 0 on the new screen. Visual depth is relative to the current presentation root. Second, do not recursively traverse untrusted depth on the main thread. Even if the product only displays ten levels per screen, corrupted local data or a future server regression should not turn cache decoding into a stack overflow.

For extremely wide threads, the root child list itself becomes pageable. A thousand roots should not become a thousand objects in the first response or one cursor containing a thousand UUIDs. Store the corpus normally, select a bounded prefix, and chunk omission identity. On the client, represent root-level cursor rows in the same normalized ordering as comments so insertion does not require replacing the whole list.

The useful answer is therefore:

- **Adjacency-list rows** for durable server storage.
- A **heap** for best-first bounded selection.
- A recursive **tree** for a small, understandable wire/domain response.
- A normalized **map plus ordered ID lists** for mutable client state and Room.
- A flat **list** for actual rendering.

This 1,000-root fixture is designed to make accidental conflation visible. If any layer attempts to hold or render all roots eagerly, the cost should show immediately in payload size, cache growth, and frame traces.`,
  },
  {
    fixtureId: "payload-budget-math",
    author: "evan_ontrail",
    title: "Back-of-the-napkin budgets for a 1,000-comment discussion",
    workload: { kind: "deep", levels: 10 },
    links: [sources.d1Limits, sources.pagingCache],
    debatePoints: [
      ["Measure the real schema", "Estimate with representative IDs, author metadata, bodies, cursor arrays, and JSON field names. Tiny lorem ipsum records understate both wire and cache cost."],
      ["Compressed versus decoded", "Network gzip can be four or five times smaller than JSON, but parsing allocates the decoded text and object graph. Track both."],
      ["Room is not JSON", "Normalized SQLite rows repeat keys and indices but avoid recursive rewrite costs. Measure page count and file growth rather than multiplying payload bytes blindly."],
      ["L1 object overhead", "A Kotlin object graph can use several times the UTF-8 JSON size due to object headers, references, UTF-16 strings, maps, and list capacity."],
      ["Budget by retained working set", "The 200-comment response matters less than retaining twelve full trees plus a hundred Room snapshots. Define account-scoped eviction and measure the aggregate."],
    ],
    body: `Performance debates improve when every participant writes down a rough budget before reaching for a profiler. The estimate will be wrong, but it makes assumptions visible and tells us which measurements could change the decision. Here is a deliberately simple model for a 1,000-comment corpus.

Assume an average serialized comment has:

- 36 bytes for its UUID and roughly 36 for a parent UUID when present.
- 20–60 bytes of author/display metadata after JSON syntax.
- 120 bytes of body text for short comments, but 600–1,500 bytes for the long technical comments in this demo.
- 80–140 bytes for field names, timestamps, score, vote, edit state, and array punctuation.

A compact short-comment object might therefore be **350–500 bytes of UTF-8 JSON**. A realistic long-comment mix could average **900–1,300 bytes**. One thousand comments land around **0.4–1.3 MB uncompressed**, before post content and cursor IDs. Repeated field names and similar prose compress well, so gzip might reduce that to roughly **100–400 KB**, but the device must inflate the response before JSON decoding. These are hypotheses; the seeder records actual byte counts for 8- and 200-comment responses so we can replace guesses with measurements.

The initial response is much smaller. Eight comments at 900 bytes each plus envelope and cursor IDs might be 10–20 KB raw and only a few kilobytes compressed. Two hundred comments might be 180–300 KB raw and 40–100 KB compressed. A root cursor containing 100 UUIDs adds roughly 3.9 KB before compression; eight such cursor chunks can add tens of kilobytes even when comment bodies are short. This is why a bounded cursor size belongs in the wire contract.

Wire size is not memory size. During decoding, the app may simultaneously hold the compressed network buffer, decoded UTF-8/UTF-16 text, serializer objects, normalized domain objects, child lists, the previous eight-comment tree, the incoming 200-comment tree, and the flattened render rows. A rough **2×–5× multiplier over raw JSON** for the transient object graph is plausible on a managed runtime, depending on string representation and collection overhead. The right measurement is a heap profile around decode and merge, not a confident spreadsheet constant.

For a simple planning number, suppose a 200-comment raw response is 240 KB and the peak decode/merge working set is 3×: about 720 KB. Retaining twelve full L1 trees at that size could approach 8–10 MB before headers and allocator fragmentation. If comments average 1 KB of body text, the same policy can be materially larger. L1 therefore needs both an entry cap and eventually a byte-weighted cap; “12 trees” is only safe while corpus distributions remain understood.

Room L2 has different overhead. The normalized cache stores one row per comment/cursor with account, post, thread, parent, and sort keys. Repeated text keys, B-tree pages, indices, free pages, and WAL behavior mean the file will not equal JSON size. The upside is localized lookup, bounded pruning, and no need to rewrite one giant recursive blob after a splice. Measure \`page_count × page_size\`, table/index contributions, and bytes added per cached 200-node tree. Android's [network-and-database paging guidance](${sources.pagingCache}) reinforces the key principle: the database is the durable UI source of truth, while the network incrementally fills it.

The server has its own budget. This Worker currently scans at most 5,001 visible comments for one post, retains 5,000, constructs parent maps and a selection heap, then serializes at most 200 selected comments plus cursors. D1 limits and Worker memory/CPU still matter; the current [Cloudflare D1 limits](${sources.d1Limits}) document notes that query execution and result serialization share Worker CPU and memory constraints. A 5,000-row safety cap is not a product pagination strategy, but it prevents an unbounded corpus from turning one request into an unbounded invocation.

An actionable budget sheet should include:

| Layer | Unit | Starting budget |
|---|---:|---:|
| Phase-one wire | compressed bytes | target under 20 KB |
| Phase-two wire | compressed bytes | target under 150 KB |
| Parse + normalize | elapsed time | p95 under one frame off main, zero main-thread blocking |
| L1 full trees | retained bytes | measure; start around 8–16 MB total |
| Room comments cache | on-disk bytes | byte cap plus 100-thread count cap |
| Visible rows | composed/layout | viewport plus lazy prefetch window, not all decoded nodes |

The numbers are intentionally debatable. The discipline is to name whether a number is compressed wire, decoded payload, transient peak, retained heap, or disk. Saying “the thread is 200 KB” without the layer is not enough.`,
  },
  {
    fixtureId: "offline-first-policy",
    author: "martin_builds",
    title: "Offline first without downloading the whole argument",
    workload: { kind: "deep", levels: 15 },
    links: [sources.pagingCache, sources.http],
    debatePoints: [
      ["Cache the user's path", "Persist the post, selected tree, collapse state, and explicitly expanded branches. Do not infer that opening one thread authorizes downloading every omitted branch."],
      ["Stale is display state", "A cached thread can render immediately with an age marker while a conditional refresh runs. Cached identity is not fresh authorization for private content."],
      ["Expansion is durable", "Once a user taps load more, persist the splice and cursor replacement transactionally so process death does not erase their reading path."],
      ["Prefetch small only", "Speculative feed prefetch should request the eight-comment tree and share an in-flight request with navigation. Full trees belong to deliberate reading."],
      ["Byte-weighted eviction", "Entry-count LRUs are easy but long bodies make trees unequal. Combine recency with byte estimates and account isolation."],
    ],
    body: `Offline-first comments should preserve **reading continuity**, not mirror the whole server corpus. The useful offline unit is the path a person actually opened: post header and body, the selected comment tree, collapse state, and any branches explicitly expanded. Downloading every hidden sibling because one root entered the viewport wastes radio, disk, and privacy budget.

The fast path is memory. Keep a small account-scoped L1 of completed 200-comment trees and a separate smaller set of speculative eight-comment trees. Coalesce in-flight prefetch with navigation. Revisiting a retained full tree should render with zero network. L1 is intentionally disposable; process death should not destroy the user's ability to reopen recently read discussions.

Room is the L2 source for that continuity. Normalize comments and cursors into rows keyed by account, post, thread root, and node ID. Store parent ID and pre-order sort index so reconstruction is O(n) and iterative. Store thread metadata separately: requested count, requested depth, root key, server version/validator, and update time. A transaction should replace or splice the relevant rows and metadata together. On startup the repository can emit Room immediately, then revalidate in the background.

The [Android Paging network-and-database pattern](${sources.pagingCache}) captures the principle even though nested comments need custom cursor splicing rather than a linear \`PagingSource\`: UI observes local durable state; a mediator fetches missing data; network results commit to the database; database invalidation updates the UI. That prevents three competing sources—network, memory, and screen state—from each claiming authority.

What should be cached?

- The post header/body and the selected 8/200 tree.
- Stable cursor IDs, their parent IDs, omitted counts, and bounded child-ID chunks.
- User-triggered load-more results and replacement cursors.
- Rooted “continue thread” screens the user actually opened.
- Local collapse state if product considers it a durable reading preference.
- Pending optimistic replies and vote mutations in an outbox, account scoped.

What should not be prefetched automatically?

- Every descendant of every visible root.
- All 1,000 root comments just because the post detail opened.
- Long branches below a depth-cap cursor before the user chooses that branch.
- Realtime event history that can be recovered by revalidation.

Cache invalidation needs explicit semantics. A comment-tree response can use a post version or ETag; a conditional GET can return \`304 Not Modified\` when the selected representation is unchanged. [HTTP GET and validators](${sources.http}) are valuable precisely because offline-first clients need cheap revalidation after connectivity returns. If authorization can change, cached public/private policy must be separate from freshness: stale data may be acceptable for a public demo, while private-community content may require account-bound encryption or removal at sign-out.

Load-more splices deserve special care. The user tapped because they wanted that branch; it is more valuable than speculative roots. Persist the returned flat parent-linked comments and replacement cursors before reporting completion. If the process dies after the network response but before the UI update, the transaction should still make the expansion visible next launch. If the request fails, retain the old cursor and its retry affordance.

Schema evolution belongs in the offline contract too. Cached tree rows should carry a representation version, and migrations should preserve only fields whose meaning remains stable. If cursor semantics change from immediate candidates to subtree totals, silently reading old rows under the new interpretation would produce convincing but false labels. Prefer a transactional migration when compatibility is straightforward; otherwise invalidate only the affected tree representation and retain the post plus pending mutations. On startup, recovery should reconcile incomplete writes, expire orphan cursor rows, and restore the last committed reading state before scheduling revalidation.

Eviction should eventually be byte weighted. A cap of 100 Room threads is predictable operationally but not in bytes when one post contains 10 KB comments and another contains one-line jokes. Keep the count cap as a guardrail, then track approximate UTF-8 body bytes plus row overhead and evict least-recently-read trees until both count and byte budgets are satisfied. Always scope by account; one user's cached discussion must never appear under another user's session.

This 15-level chain tests whether the cached root screen stops at the presentation depth and whether a rooted continuation can be reopened offline. The debate is not “cache or network.” It is **which reading decisions become durable, how they are versioned, and what work is allowed without explicit intent**.`,
  },
  {
    fixtureId: "rendering-deep-chains",
    author: "evan_ontrail",
    title: "Rendering twenty levels deep without turning indentation into the interface",
    workload: { kind: "deep", levels: 20 },
    links: [sources.composeLists, sources.composePerformance],
    debatePoints: [
      ["Flatten before composition", "Transform the loaded tree into a stable keyed row list in the ViewModel or domain layer. Recursive nested Columns multiply measure work and complicate virtualization."],
      ["Cap visual depth", "Structural depth can continue indefinitely, but indentation should stop or re-root around level 8–10 so content width remains usable."],
      ["Stable keys preserve anchors", "Comment and cursor IDs must be stable across merges and splices so the list can retain scroll position and avoid unnecessary recomposition."],
      ["Collapse skips subtrees", "A collapsed parent stays visible while its branch disappears. Read the server-authored total descendant count instead of walking only the loaded projection."],
      ["Benchmark release builds", "Measure macrobenchmarks and frame timing in optimized builds; debug Compose behavior is not a production performance result."],
    ],
    body: `Twenty structural levels are easy to store and surprisingly easy to render badly. The naive UI recursively nests a \`Column\` for every comment, adds padding at each level, and places the whole structure inside one scroll container. That forfeits lazy composition, multiplies measure/layout work, makes stable scroll anchors difficult, and eventually leaves a narrow ribbon for the actual body text.

The rendering contract should be a **flat list of visible rows**. Each row carries a stable key, a row kind, a render depth relative to the current screen root, and presentation metadata. Build the list with an explicit stack in pre-order. When a comment is collapsed, emit the parent row and skip its loaded descendants. When a cursor is encountered, emit either load-more or continue-thread. The \`LazyColumn\` sees ordinary keyed rows and only composes the viewport plus its prefetch window.

This does not mean the domain has stopped being a tree. It means hierarchy is represented as data instead of nested layout containers. The [Compose lazy-list documentation](${sources.composeLists}) explains that lazy layouts compose and lay out only visible items; the [performance guidance](${sources.composePerformance}) adds the importance of stable keys and moving expensive sorting/calculation out of composition. Flattening belongs upstream in state production, memoized by tree and collapse state, not repeated in each row's composable.

Structural depth and visual depth are separate. The database can record depth 19, but a screen rooted at that comment displays it at depth 0. On the main post screen, indentation should be capped around 8–10 levels. At the cap, a cursor becomes **Continue this thread →** and opens a new rooted screen. That preserves readable width, gives navigation a clear semantic boundary, and bounds the amount of context the user must hold.

Indentation itself should also saturate. A fixed 12 dp per level makes a level-10 body lose 120 dp before avatars and rails. Consider a decreasing function or a hard cap after a few levels, with color/rail treatment conveying additional hierarchy. Accessibility descriptions should announce reply context without reading “nested level nineteen” on every focus move. Touch targets cannot shrink with the text column.

Merges and expansions must preserve scroll position. Stable comment IDs and cursor IDs are essential. When the 200-comment phase arrives, existing roots retain order, and newly revealed branches do not auto-expand if they were visually leaves in phase one. When load-more replaces one cursor with returned comments and replacement cursors, splice at that exact keyed position. If the list is rebuilt, unchanged keys allow Compose to reuse composition and keep the visible anchor.

Collapse counts need disciplined semantics. The client should not derive the value from the depth-limited projection: doing so makes the label change as continuations load. Read the server-authored total descendant count in O(1), including replies behind cursors. A collapsed badge of \`+12\` then means the logical branch contains twelve replies; a separate cursor still communicates how much network work remains.

Infinite scrolling is useful for breadth but not a replacement for branch controls. Near the bottom of a wide root list, automatically request the next root cursor if network and lifecycle state permit. For a nested cursor under a specific comment, require a tap: expanding it can insert rows above the current viewport bottom and is a semantic choice. Use one load per cursor ID, deduplicate concurrent taps, expose retry state in the row, and cancel work when the screen leaves the active lifecycle unless it is committing durable cache state.

The performance test should capture:

- flatten duration for 8, 200, and a locally expanded 1,000-node tree;
- number of composed and laid-out rows versus decoded nodes;
- p50/p95 frame time while expanding and collapsing a large subtree;
- scroll-anchor displacement after phase-two merge and cursor splice;
- allocation and GC around long Markdown bodies;
- time to restore a rooted level-10 continuation from Room.

This 20-level chain intentionally crosses two depth windows. The first screen should stop with a continuation affordance; the next rooted screen should reset indentation and expose the remaining debate without recursive layout or stack overflow.`,
  },
  {
    fixtureId: "rest-vs-websocket",
    author: "martin_builds",
    title: "REST for history, WebSocket for invalidation—not a 1,000-comment firehose",
    workload: { kind: "hybrid", rootCount: 120, chainLevels: 15, sideRepliesPerLevel: 2 },
    links: [sources.http, sources.websocket],
    debatePoints: [
      ["REST is the recovery path", "Bounded snapshots and cursor expansions are independently retryable, cacheable, observable, and compatible with offline revalidation."],
      ["Sockets carry hints", "Use realtime transport for small versioned events or invalidations: comment created, score changed, moderation changed. Re-fetch authoritative slices when needed."],
      ["Reconnect creates gaps", "Every socket design needs sequence numbers and a REST catch-up path. Without gap recovery it is a demo, not a consistency model."],
      ["Do not mutate offscreen trees eagerly", "A new-comment event can update a badge and invalidate a cursor without rebuilding a 200-node visible projection on every message."],
      ["Lifecycle and battery matter", "Keep a socket only while product value justifies it. Mobile background execution and flaky networks make permanent connections costly."],
    ],
    body: `The REST-versus-WebSocket question is often framed as old versus modern. For comment threads, the more useful distinction is **authoritative history versus live change notification**. Initial trees, rooted continuations, and load-more expansions are bounded historical reads. They need retry, caching, offline persistence, and deterministic reconstruction. REST is excellent at those jobs. A socket is valuable when the user is actively watching a fast thread and wants low-latency hints that something changed.

Use REST for:

- \`GET /posts/{id}\` and the selected comment tree with count/depth parameters;
- rooted or focused thread reads;
- bounded \`morechildren\` expansion requests;
- conditional revalidation by version or ETag;
- recovery after process death, network loss, or missed realtime events.

[HTTP GET semantics](${sources.http}) provide a mature cache and validator model. A cursor expansion may use POST when the request contains up to 100 child IDs, but the operation still returns a self-contained bounded result that can be retried with an idempotency/snapshot contract. The request does not require a connection that has been alive since the post opened.

Use a WebSocket for small events such as:

- \`comment.created { postId, commentId, parentId, version }\`
- \`comment.score_changed { commentId, score, version }\`
- \`comment.deleted { commentId, version }\`
- \`post.comment_count_changed { postId, count, version }\`

The [WebSocket protocol](${sources.websocket}) enables two-way communication after its opening handshake, but transport capability does not define application consistency. Events need monotonic sequence/version information, deduplication, authorization, and a recovery path. After reconnect, the client should say which version it last applied; if the gap cannot be replayed cheaply, it revalidates the affected REST resource.

Do not stream the entire thousand-comment corpus through the socket on subscription. That recreates a less-cacheable REST response with harder retry semantics. Do not assume ordered delivery across reconnect. Do not update every offscreen branch eagerly: a \`comment.created\` event below an unloaded cursor can increment a visible activity badge or invalidate the cursor's snapshot without materializing the new node immediately.

There is also a product question: what does “live” mean? For votes, updating every score tick may cause visual noise and battery/network churn; a 5–15 second coalesced refresh can feel live enough. For a live event thread, new root comments might appear behind a “27 new comments” affordance rather than jumping into the list and moving the user's reading position. For a quiet technical discussion, refresh-on-resume is probably sufficient.

Mobile lifecycle makes permanent connectivity nontrivial. The socket belongs to the visible active screen or a foreground use case, not every cached post. Background execution may suspend it; radio transitions and reconnect backoff cost energy; authentication can expire; and the app must avoid one socket per post. A multiplexed account connection or server subscription channel is more reasonable if realtime becomes a product requirement.

There is a useful middle ground before adopting bidirectional transport. Conditional polling on resume and while a thread is foregrounded can fetch a tiny version resource, use cache validators, and back off when nothing changes. Server-Sent Events can carry one-way invalidations when the platform and infrastructure make them operationally simpler. Neither removes the need for versioned recovery. The decision should compare freshness, concurrent connections, reconnect rate, bytes per idle minute, battery impact, observability, and failure behavior—not merely implementation fashion.

If WebSocket wins, subscribe by post on one account-level connection and acknowledge the last applied sequence. Bound both client and server queues. When a slow client falls behind, send a resync-required control message instead of retaining an unbounded event backlog. Coalesce repeated score events by comment ID, preserve create/delete ordering, and avoid including full Markdown bodies unless the active screen can place them immediately. Authentication refresh and community-access revocation must invalidate subscriptions as deliberately as REST authorization invalidates a read.

The current sample already has a per-post realtime publication seam for writes, but REST remains the read model. That is the right default. Add a socket consumer only after defining:

1. event envelope and versions;
2. gap detection and REST catch-up;
3. lifecycle/subscription policy;
4. whether events mutate loaded state or only invalidate it;
5. backpressure and coalescing;
6. observability for reconnects, missed gaps, and resync cost.

This hybrid fixture—120 roots plus a featured 15-level branch with side replies—tests both paths conceptually. REST should return a stable selected tree, bounded cursors, and write-side total descendant counts. Realtime can announce that the corpus changed, but the client should not need to receive or retain all hidden siblings merely to keep a count current.`,
  },
  {
    fixtureId: "pathological-thread-test-plan",
    author: "evan_ontrail",
    title: "The pathological-thread test plan: breadth × depth × body size × churn",
    workload: { kind: "hybrid", rootCount: 300, chainLevels: 20, sideRepliesPerLevel: 3 },
    links: [sources.composePerformance, sources.d1Limits],
    debatePoints: [
      ["Separate dimensions", "A thousand shallow roots, one twenty-level chain, and a hybrid forest stress different code paths. Do not call one fixture 'large comments' and assume coverage."],
      ["Add body-length distributions", "Short bodies stress row count; long Markdown bodies stress decode, layout, text measurement, accessibility, and disk bytes."],
      ["Test state transitions", "Cold load, warm L1, warm Room, phase merge, expand, collapse, rotate, process death, reconnect, and deletion all need separate traces."],
      ["Define invariants", "No duplicate keys, no lost comments, bounded cursor IDs, stable visible order, correct omitted counts, and no stack recursion are correctness gates before frame metrics."],
      ["Record the workload", "Every benchmark result should include corpus shape, response count/depth, body bytes, cache tier, build type, and device class."],
    ],
    body: `“Large thread” is not one workload. Breadth stresses root pagination and cursor count. Depth stresses traversal, indentation, re-rooting, and recursion safety. Long bodies stress transfer, parsing, text measurement, accessibility, and disk. Churn stresses merge stability, cache invalidation, and realtime recovery. A useful demo community needs fixtures that isolate each dimension and then combine them.

This dataset provides the following matrix:

| Fixture | Roots | Maximum structural levels | Purpose |
|---|---:|---:|---|
| Wide-small | 50 | 1 | baseline root paging and long bodies |
| Wide-medium | 250 | 1 | multiple root cursors |
| Wide-extreme | 1,000 | 1 | payload/cursor/cache pressure |
| Deep-window | 1 | 10 | exactly one presentation window |
| Deep-overflow | 1 | 15 | rooted continuation required |
| Deep-extreme | 1 | 20 | two depth windows and recursion safety |
| Hybrid-live | 120 | 15 | ranked branch plus root pagination |
| Hybrid-extreme | 300 | 20 | breadth, depth, side replies, and long content |

The first test layer is correctness. Assert unique stable IDs, valid parent links, stored depth equal to parent depth plus one, exact post/root/max-depth counts, and child/descendant-count triggers. For every 8/200 response, count real comments separately from cursor nodes. Sum server-authored cursor chunks and confirm they represent omitted next candidates. Expand every cursor once, ensure the spent cursor disappears, and verify returned comments splice under their real parents. Collapse a subtree and ensure its total descendant count stays stable before and after cursor expansion.

The second layer is network. Record status, cache hit/miss, server timing, uncompressed bytes, gzip bytes, parse time, and retry behavior. Run cold and warm server-cache requests. Test loss and latency, cancellation during navigation, duplicate taps, and expansion after comments were deleted. Confirm that the initial eight-comment response remains bounded even when the corpus is 1,000 roots and that no cursor carries more than 100 IDs.

The third layer is persistence. Measure Room file growth after caching each fixture, write transaction time, iterative decode time, and pruning behavior at 100 cached threads. Kill the process after an expansion commits and before the next frame; on restart, the expanded branch should still be present. Switch accounts and prove cache isolation. Corrupt or version-mismatch one cached thread and verify the app discards it without losing unrelated threads.

The fourth layer is rendering. The [Compose performance guidance](${sources.composePerformance}) recommends measuring optimized builds and using stable lazy-layout keys. Capture time to first comment, rows composed versus nodes decoded, p50/p95 frame time during fast scroll, allocation during Markdown layout, and scroll-anchor movement during merge/splice. Test collapse and expand on a branch containing long bodies. At depth 10, verify a continuation row replaces further indentation and that the rooted screen begins visually at depth 0.

The fifth layer is mutation and churn. Insert a root while the user reads comment 150; the list should offer new content without moving the anchor. Insert a reply beneath an unloaded cursor; update activity/count state without forcing the branch open. Apply vote bursts, edits, and moderation deletions. Disconnect a realtime channel, create events, reconnect with a sequence gap, and prove REST revalidation repairs the state.

Back-of-the-napkin budgets become benchmark thresholds only after measurement. For this Worker, [D1's current limits](${sources.d1Limits}) frame query/result serialization constraints, but application budgets should be tighter: bounded corpus scans, sub-150 KB compressed phase-two responses where content permits, no main-thread JSON/tree work, bounded L1 bytes, and deterministic Room pruning. A threshold must name the fixture and device; otherwise a fast result on the 50-root short-body case can hide a regression in the 300-root hybrid.

Finally, record **why** a policy changed. If 8/200 becomes 12/150, preserve before/after traces and the user outcome. If visual depth moves from 10 to 8, test readability and continuation taps. If a websocket is added, measure freshness gained against connection time, battery, gap recovery, and invalidations that caused no visible update.

The goal of this community is not to prove one magic set of constants. It is to make tradeoffs reproducible. Every optimization should state which layer it helps, which fixture demonstrates it, what invariant it preserves, and what new cost it introduces.`,
  },
];

export { sources };
