# `:feature:comments` — the post-detail screen

Tap a feed item → post screen. This module models what Reddit actually does there, and it is **deliberately not SDUI** — because Reddit's isn't.

```bash
./gradlew :feature:comments:testDebugUnitTest
```

The module has no dependency on `:app`, `:feature:feed`, or `:flows`; the app
depends on it for composition, never the other way around.

Since the first revision this module gained a **working "x more replies"** (flat parent-linked `loadMore` + pure tree splicer + per-cursor state machine), **"continue this thread"** at the depth cap (re-rooted permalink fetch), a **coalescing repository** (in-flight prefetch shared with tap-through), bounded L1 trees and headers, a normalized Room L2, a **two-stage UiState derivation** (flag churn can never re-run the flatten, and the flatten runs off the main thread), the **user-vs-auto collapse split** (merger artifacts are not user intent: never persisted, never announced to TalkBack), and **`SavedStateHandle` persistence** of the user's collapse set across process death. Each behavior has a JVM test.

**Visual parity pass (2026-08-27):** `animateItem()` on every row (expansion slides children in, collapse closes the list up, spliced replies animate into place — the payoff of stable keys), **one thread rail per ancestor depth** drawn in `drawBehind` (rails are paint, not layout: no nested composables, no intrinsic passes, lines run unbroken through row padding), collapsed comments keep a **one-line grayed body preview** plus a clear **“Show N hidden replies”** affordance, and **compact age labels** (`5m`/`1h`/`2d`) formatted client-side from server minutes — hash-derived in the fake so the extra field cannot shift the deterministic rng sequence that shapes the tree.

---

## Why this screen is a different architecture from the feed

| | Feed (`:feature:feed`) | Post detail (`:feature:comments`) |
|---|---|---|
| Wire model | `Group → [Cell]` — **server-described UI** | recursive `Comment` tree — **domain model** |
| Shape | two fixed levels | arbitrary depth |
| What varies | *structure* (new unit types ship constantly) | *data* (composition is client-known) |
| Ordering | server-ranked | server-ranked, but client preserves what's on screen |
| Interaction | mostly tap-through | vote, reply, **collapse/expand** — heavy local mutation |
| Presentation | MVVM + Paging Flow + explicit intents | **MVVM + `combine`/`stateIn`** (Reddit's published idiom) |

SDUI's payoff is "ship a new unit type without a client release." Post detail doesn't need that, and it's interaction-heavy — the exact place SDUI is weakest. **Knowing where SDUI stops is a better interview answer than assuming it's everywhere.**

---

## What's modelled

### 1. The server's tree builder — Reddit's published algorithm
`FakeCommentsApi` implements it literally:

1. push all root comments into a **max-heap by score**
2. pop the highest, attach under its parent
3. push that comment's children back in as candidates
4. repeat until the requested count is exhausted
5. group whatever is left in the heap **by parent** into `load_more` cursors

Depth is capped (Reddit uses **10**) *"to limit the computational cost and make it easier to render from a mobile platform UX perspective"* — anything deeper becomes a "more replies" cursor.

**The consequence that matters: a bigger count does not just append.** Because the heap is score-ordered *globally*, a `count=200` tree expands children under comments that were childless leaves at `count=8`. There's a test asserting this actually happens — it's the premise of the flicker bug.

### 2. Flattening a recursive tree — harder than the feed's
`CommentFlattener` turns the tree into a flat `List<CommentRow>` carrying **depth** (for indentation), honouring:

- **collapse hides an entire subtree** — the collapsed node stays visible (you need something to tap) and reports the server-authored `descendantCount` in a “Show N hidden replies” control
- **`load_more` cursors** render at the depth of the children they stand in for
- **stable keys**, same as the feed — duplicate keys crash `LazyColumn`

Implemented **iteratively with an explicit stack, not recursively**. There's a test that flattens a **5,000-deep** thread; a recursive walk would `StackOverflowError`.

The hidden count adds no client tree walk. SQLite stores each comment's total
descendant count and the insert trigger increments the new reply's ancestor chain.
The max-heap only selects presentation nodes; it does not redefine subtree size.
That makes the value exact across the standard 8/200 responses and the depth-10
boundary, while cached reads and collapse lookup stay O(1). Loading a cursor
preserves the total; optimistic inserts and rollbacks adjust only their copied
ancestor spine.

Room encoding/decoding, load-more splicing, voting, and reply insertion are also
iterative. The mutation tests path-copy a 1,500-level thread without consuming
the call stack, while untouched subtrees retain referential identity.

### 3. Two-phase load + the anti-flicker merge
Reddit's client makes two requests: `count=8, depth=10` to render immediately, then `count=200, depth=10`. Their published problem:

> "comment trees with different counts will be built with a different number of expanded child comments. So when the 200-count fetch completes, the user will suddenly see a bunch of child comments expanding automatically. This leads to a jarring UX."

They fixed it server-side. `CommentTreeMerger` models the client-side contract:

> **Anything already on screen keeps its position and its expansion state.** The larger tree may only *add*. It may never auto-expand something the small tree showed collapsed, and never reorder what the user is already reading.

Tested three ways: newly-expanded nodes get auto-collapsed; already-visible roots keep their on-screen order even when server ranking shifted between requests; and a user's *own* explicit collapse always survives the merge.

### 4. Prefetch from the feed
`CommentsRepository.prefetch(postId)` is called **from the feed**, after a post has been on screen long enough to suggest intent. It fetches only the small tree. When the user taps through, phase 1 is served from memory and the screen renders **without waiting on the network at all**.

Reddit's published cost for this: **~40,000 extra requests/second** across iOS and Android combined. Worth quoting — it's the number that shows prefetching isn't free.

Tested: after `prefetch`, opening the post issues only the `count=200` call.

### 5. Requesting the *standard* sizes
A test asserts the client requests exactly `(8, 10)` then `(200, 10)`. That's not cosmetic — Reddit pre-computes and caches trees at fixed sizes, and *"to ensure a cache hit, the client apps request comment trees with the same max count and max depth parameters as the pre-computed trees in the cache."* Ask for an off-menu size and you force a dynamic build.

### 6. Progressive loading after the 200-tree

Paging 3 is intentionally not placed directly over comments. A `PagingSource`
models a flat ordered sequence, while a comment continuation can splice under
any parent and must preserve collapse state, render depth, and path-copy
identity. Flattening the server data first would throw away exactly the
structure the feature mutates.

The equivalent policy is tree-aware:

- the fixed 8/200 responses optimize first render and server-cache hits;
- every remaining `load_more` cursor carries at most 100 child IDs;
- `LazyColumn` reports its viewport as a UDF intent and the ViewModel prefetches
  one cursor when it is visible or within six rows;
- only one automatic branch is in flight, and an error is never auto-retried;
  the inline row remains the explicit retry affordance;
- each response is spliced iteratively and persisted to bounded L1 plus Room L2.

The Worker also batches viewer-vote hydration below D1's SQL variable budget,
including for the initial 200-comment response. Integration tests cover a
120-root thread split into 100- and 12-ID continuations.

---

## Presentation: MVVM, matching Reddit

`CommentsViewModel` uses `combine(tree, collapsedIds, loading).stateIn(...)` and
exposes one immutable `CommentsUiState`. It matches Reddit's published idiom
from *Reactive UI state on Android, starring Compose*:

> "Our job is to spit out a single stream of UiState for the view to consume... every UiState should be the result of a transformation on the latest values of everything it depends on."

Flattening happens *inside* the `combine` block, so **toggling a collapse re-derives the render list without refetching anything** — there's a test asserting the network call count is unchanged across a collapse toggle.

---

## The metric to name

Reddit defined **"comments TTI"**: timer starts when the user taps a post in the feed, stops when the first comment renders. Baseline was **~2.3s iOS / ~2.6s Android**.

After the two-phase split, prefetching, a trimmed first payload (comments + votes + mod details; flair and awards deferred to the 200-fetch), and a faster transition animation:

- p90 TTI **−60.9% iOS / −59.4% Android**
- **−30%** failure rate loading post detail from feeds
- **+4%** comments viewed

---

## Storage and remaining scope

Comment threads are normalized into `comment_threads` and `comment_nodes`, and
post headers live in `post_headers`. Every key is account/post/root-thread
scoped. Reads are L1 → Room → network, and successful/optimistic changes are
written back to both tiers before the next render.

The remaining product scope is comment sort switching, moderator action UI,
and a durable offline mutation outbox for replies/comment votes. Online replies
and three-state post/comment voting are implemented with optimistic UI and
authoritative reconciliation; failed mutations preserve the draft or roll back
with an actionable error.
