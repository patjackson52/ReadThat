# Search architecture and behavior

Search follows the current Reddit mobile interaction shape while keeping the
implementation native to this sample. The home search field opens a dedicated
screen. An empty query shows recent searches, trending posts, and trending
communities. Typing is debounced for 250 ms and returns query completions,
communities, and profiles. Submission opens an `All` preview followed by
`Posts`, `Communities`, `Comments`, `Media`, and `Profiles` tabs. Post/comment
tabs support relevance, top, and new ordering as applicable, time windows, and
safe search.

Selecting a result passes only an identifier across the feature boundary:

- a post or media match opens typed post detail;
- a comment match opens its post, fetches a focused ancestor/reply slice,
  scrolls to the match, and highlights it;
- a community opens that community's cursor-paged feed;
- a profile opens its public profile.

The feed remains server-driven UI. Search results and post detail are typed
domain models because their interaction and navigation contracts are stable and
client-owned.

## Android data path

```text
Compose intent -> SearchViewModel -> SearchRepository -> SearchRemoteSource
       ^                  |                 |
       |                  v                 v
 PagingData/StateFlow <- Room paging source <- transactional page + cursor
```

`:feature:search` is a vertical Gradle feature with `ui`, `domain`, and `data`
packages. It depends on shared Room capability, while the app composition root
provides the backend adapter and navigation callbacks. The ViewModel exposes a
single immutable state and explicit intents. It cancels superseded `All`
requests and validates the request key before applying a response, so a slow
old query cannot overwrite a newer one.

The repository provides two structured-data cache tiers:

- L1 is a bounded 32-entry `LruCache` plus retained Paging flows;
- L2 is account-scoped Room storage for snapshots, ordered result rows,
  cursors, and recent searches.

Snapshots are fresh for five minutes (typeahead for one minute) and retained on
disk for seven days as an offline fallback. Non-`All` tabs use Paging 3 with a
`RemoteMediator`, 20-row pages, a 10-row prefetch distance, and a bounded
160-row in-memory window. Page response, row order, and next cursor commit in
one Room transaction; Room is the paging source of truth.

## Worker query path

`0007_search_fts.sql` adds independent FTS5 indexes for posts, comments,
communities, and profiles. Transactional insert/update/delete triggers keep
them synchronized with source tables, so newly committed content is searchable
without an asynchronous indexing gap. Unicode tokenization, diacritic folding,
and 2/3/4-character prefixes make mobile typeahead useful without unbounded
substring scans. Queries normalize NFKC input, accept at most 100 characters
and eight tokens, and quote every FTS term before adding a prefix operator.

The HTTP surface is:

| Endpoint | Result |
|---|---|
| `GET /v1/search/discover` | safe trending posts and readable communities |
| `GET /v1/search/typeahead?q=...&limit=...` | completions, communities, profiles |
| `GET /v1/search?q=...&type=...` | `All` previews or one paged result type |

`/v1/search` also accepts `sort`, `time`, `safe`, `subreddit`, `limit`, and
`cursor`. `All` deliberately returns small parallel section previews; the user
switches to a tab for infinite paging. Tab cursors bind the normalized query,
type, filters, optional community, snapshot time, and an anonymous hash of the
viewer. They keyset-page by rank/sort value, creation time, and ID. A cursor
cannot be reused for another query or viewer, and new rows beyond the snapshot
cannot shift the active result set.

Every query joins subreddit membership before returning content. Banned users
cannot see a community; private communities require member, moderator, or owner
access. These predicates apply to posts, media, comments, typeahead, and
discovery rather than relying on client filtering. Safe search excludes mature
posts and their comments. Response bodies are preview-truncated and media rows
contain thumbnails/dimensions instead of full detail payloads.

## Verification and performance

Worker integration tests cover trigger-indexed content, all result sections,
stable multi-page cursors, focused comment permalinks, private-community
non-disclosure, and viewer-bound cursor rejection. Android tests cover recent
query normalization/reactivity, L1 plus Room L2 snapshots, and multi-page
`RemoteMediator` append ordering.

The deployed D1 query plan uses the FTS virtual-table index followed by primary
key lookups for the source rows and membership. On the current small production
dataset, the representative plan executed in about 0.28 ms. Live uncached edge
requests returned compact payloads (about 2.5 KiB for discovery and 3.6 KiB for
an `All` result). Those figures are a deployment sanity check, not a load-test
capacity claim; latency distributions belong in the existing performance
telemetry and SLO path.
