# `:flows` — Kotlin Flow, as actually used

A self-contained Android library module exercising the Flow patterns that show up in real Android codebases and in interviews. **`:app` does not depend on it.** It stands alone, with its own tests and an optional Compose demo screen.

```bash
./gradlew :flows:testDebugUnitTest    # 29 tests
./gradlew build                        # both modules, incl. lint
```

**Verified: 29 tests, 0 failures.** No `INTERNET` permission — every "remote" source is an in-process fake.

---

## The data path

```
source/                     repo/                        vm/                    ui/
─────────                   ─────                        ───                    ──
FakeRemoteSource   ──┐
  suspend fetchUser  │      UserRepository
  suspend search     ├──►     flow + onStart              DashboardViewModel     collectAsState
  streamPosts (cold) │        retryWhen (backoff)   ──►     combine(...)    ──►    WithLifecycle
                     │        catch → Failure                stateIn(              LaunchedEffect
LocalStore         ──┤        flowOn(io)                      WhileSubscribed)      for events
  StateFlow<Settings>│        shareIn (multicast)
  StateFlow<Posts>   │
                     │      FeedRepository               SearchViewModel
FakeConnectivity   ──┘        combine(3 sources)           debounce
  callbackFlow                distinctUntilChanged         distinctUntilChanged
  + awaitClose                                             flatMapLatest
                                                           stateIn
```

---

## Patterns, and why each one is there

### Cold vs hot
`ticker()` is `flow { }` — **cold**: the producer re-runs per collector, and nothing happens until someone collects. Two collectors get two independent sequences. That's the "why did my counter restart?" bug.

`shareIn` / `stateIn` make it **hot**: one upstream, N collectors. Tested both ways — `sharedUser` makes one network call for two collectors; plain `user` makes two.

### `callbackFlow` + `awaitClose`
The bridge from listener APIs (ConnectivityManager, SensorManager, any SDK) into Flow. Three things make it correct:

- **`trySend`** — the callback fires on another thread outside a suspend context, so you can't `emit`.
- **`awaitClose { }`** — suspends until the collector leaves, then unregisters. **Omit it and you leak the listener**; `callbackFlow` throws at runtime if it's missing. This is the entire reason the builder exists.
- **Push an initial value** before awaiting, so a late subscriber isn't stranded until the next change.

There's a test asserting the listener count returns to zero after cancellation — the leak test.

### `combine`
Emits when **any** input emits, pairing with the latest of the others. The trap: **it emits nothing until every input has produced at least one value.** One never-emitting input silently stalls the whole combination. Tested explicitly with `emptyFlow()`.

That's why every input here is either a `StateFlow` (always has a value) or emits an initial value up front.

`distinctUntilChanged` after a combine stops upstream churn that doesn't change the visible result — tested by blocking a subreddit that isn't in the list and asserting **no** downstream emission.

### `stateIn` and `SharingStarted`
| Strategy | Behavior | Problem |
|---|---|---|
| `Eagerly` | runs forever from creation | works while backgrounded, nobody listening |
| `Lazily` | starts on first collector, never stops | same leak, delayed |
| **`WhileSubscribed(5_000)`** | stops 5s after the last collector | ✅ |

The 5-second grace period is the point: a rotation tears down and re-attaches the collector well inside the window, so the upstream **doesn't** restart. Shorter and you re-fetch on every rotation; `Eagerly` and you never stop.

This only works if the UI uses `collectAsStateWithLifecycle()` — `collectAsState()` keeps collecting in the background and the timeout never fires.

### `retryWhen` / `catch` / `flowOn` — order matters
```kotlin
flow { emit(fetch()) }
  .retryWhen { cause, attempt -> ... }   // sees the raw exception
  .catch { emit(Failure(...)) }          // only fires once retries are exhausted
  .onStart { emit(Loading) }             // prepended to the stream
  .flowOn(ioDispatcher)                  // affects everything ABOVE it only
```
`catch` only sees exceptions from **upstream of itself**. Put it above `retryWhen` and it swallows the error before retry ever gets a chance. `flowOn` is likewise upstream-only — the collector stays on its own dispatcher.

Backoff is `100ms × 2^attempt`. Tested: 2 transient failures → 3 total attempts → Success; permanent failure → 4 attempts → `Failure` **as a value, not a thrown exception**, so the collector survives.

### Search: `debounce` → `distinctUntilChanged` → `flatMapLatest`
The most-asked pipeline, and every operator earns its place:

- **`debounce(300)`** — don't query on every keystroke. Tested: 4 rapid keystrokes → **1** network call.
- **`distinctUntilChanged()`** — same text again (cursor moved, IME churn) must not re-query. Tested.
- **`flatMapLatest`** — a new query **cancels** the in-flight one. `flatMapConcat` would queue and show stale results in order; `flatMapMerge` would race and let the slowest response win.
- **`catch` placed inside the inner flow** — a failed search terminates only that query's flow. Catch on the *outer* flow and one bad search kills search for the rest of the session. Tested: failure, then a successful query on the same pipeline.

### One-shot events: `Channel` vs `SharedFlow` vs `StateFlow`
| | Replays? | Delivery | Use for |
|---|---|---|---|
| `StateFlow` | always (current value) | all collectors | **state** |
| `SharedFlow(replay=0)` | no | all collectors; **lost if none attached** | broadcasts |
| `Channel` + `receiveAsFlow` | no | exactly one collector; **buffered** | **one-shot events** |

A snackbar modelled as `StateFlow` re-fires on every rotation. Both behaviors are pinned by tests: the Channel event is delivered once and **not** replayed to a second collector, and it *is* buffered when emitted with nobody listening — while the `SharedFlow` broadcast emitted with no collector is gone forever.

### `MutableStateFlow.update { }`
The compare-and-set loop. `value = value.copy(...)` is a read-modify-write race under concurrent writers.

### Testing
- `runTest` gives **virtual time** — `advanceTimeBy(2_001)` makes two seconds of `delay` instant. There's a test proving it.
- **Turbine** (`.test { awaitItem() }`) for assertions on emissions, including `expectNoEvents()` for "this must NOT emit," which is how the `combine` and `distinctUntilChanged` traps get pinned.
- `backgroundScope` for collectors that should die with the test.
- Dispatchers are **injected**, so `flowOn(Dispatchers.IO)` becomes `StandardTestDispatcher(testScheduler)` in tests and virtual time still works. A hardcoded `Dispatchers.IO` would break it.

---

## Wiring the demo screen in (optional)

The module is additive; nothing references it. To see it:

```kotlin
// app/build.gradle.kts
implementation(project(":flows"))
```

```kotlin
FlowsDemoScreen(
    dashboardViewModel = DashboardViewModel(
        userRepository = UserRepository(FakeRemoteSource()),
        feedRepository = FeedRepository(LocalStore(), FakeRemoteSource()),
        connectivity = FakeConnectivityManager().asFlow(),
    ),
    searchViewModel = SearchViewModel(FakeRemoteSource()),
)
```

---

## Deliberately not covered

Named so "what else would you use?" has an answer:

- **`Flow` ↔ `Channel` fan-out**, `produceIn`, `broadcastIn` (deprecated).
- **Backpressure operators** — `buffer`, `conflate`, `collectLatest`. `collectLatest` is the collector-side twin of `flatMapLatest`; `conflate` is what `StateFlow` does implicitly.
- **`Paging 3`**, which wraps a lot of this for lists.
- **Room/DataStore Flows** — the real sources these fakes stand in for.
- **`SharedFlow` replay caches > 1** and `WhileSubscribed(replayExpiration)`.
- **`callbackFlow` with `buffer(CONFLATED)`** for high-frequency sensors.
