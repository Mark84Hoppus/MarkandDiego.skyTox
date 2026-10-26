# Chat history pagination implementation report

## Scope

Implemented on top of SkyTox 0.8.34 build 329. The installable result uses versionCode 330. Room schema remains version 8 and no migration or user-data deletion was introduced.

## Changed code

- `core/src/main/kotlin/db/MessageDao.kt`: bounded `pageLatest`, `pageBefore`, `pageAfter`, `pageTarget`, `pageRange`, boundary checks, and a Room invalidation signal.
- `core/src/main/kotlin/repository/MessageRepository.kt`: suspend wrappers for the page API.
- `domain/src/main/kotlin/feature/ChatManager.kt`: page API delegation; send/receive behavior is unchanged.
- `atox/src/main/kotlin/ui/chat/ChatHistoryCache.kt`: process-local 10-minute LRU cache.
- `atox/src/main/kotlin/ui/chat/ChatViewModel.kt`: single paged window, cancellation generation, live refresh and id-based merge.
- `atox/src/main/kotlin/ui/chat/ChatFragment.kt`: top/bottom prefetch and ListView anchor restoration.
- `atox/src/main/kotlin/ui/chat/ChatAdapter.kt`: stable ids now use `Message.id`.
- `core/src/androidTest/kotlin/db/MessagePaginationTest.kt`: keyset ordering, chat isolation, target lookup and deleted-id coverage.

## SQL and paging

- Latest: inner `ORDER BY id DESC LIMIT :limit`, outer `ORDER BY id ASC`.
- Older: strict `id < :beforeId`, descending limited subquery, returned ascending.
- Newer: strict `id > :afterId ORDER BY id ASC LIMIT :limit`.
- Search target: exact `(conversation, id)`, then independently bounded before/after pages.
- No `OFFSET`, timestamp cursor, schema change or database migration.

Normal opening loads 30 messages. Top prefetch loads 40 older messages. Search opens at most 30 older + target + 30 newer and can page in both directions.

## Live updates and continuity

Room's conversation invalidation signal refreshes the currently loaded id range. Existing ids are replaced with their newest Room values, deleted ids disappear, and results are deduplicated/sorted by id. In latest mode, all ids after the loaded newest id are fetched in bounded batches, so a burst larger than 30 does not create a gap. In around-target mode, remote latest messages are not joined beside the old target; `hasNewer` exposes that more history exists and the down button explicitly reloads latest.

The initial Room signal is intentionally processed, closing the race between the first page query and observer subscription.

## Scroll anchor

Before prepend, the Fragment records the first visible `Message.id` and the top pixel offset. After adapter replacement it finds the same id and calls `setSelectionFromTop(position, topPx)`. Paging does not use position arithmetic.

## Cache

- TTL: 10 minutes from leaving each chat, measured with `SystemClock.elapsedRealtime()`.
- Independent per-contact entries; active ViewModels are not expired.
- Maximum 5 inactive chats, 1500 messages total, and 800 messages per cached/active window.
- LRU pressure removes only RAM snapshots. SQLite rows are never touched.
- Cache is process-local and intentionally disappears after process death.

## Verification

- Debug Kotlin compilation: passed for core, domain and atox.
- Android instrumentation test sources: compiled successfully.
- Existing JVM unit-test task: passed (`NO-SOURCE` in atox).
- Device instrumentation execution: not run because no ADB device was connected.
- Release universal APK assembly and release lint-vital: passed.
- Full debug lint is still blocked by six pre-existing API-level findings in the map tile cache and theme styles; no pagination file appears among those errors.
- Manual Poco M5s behavior and memory measurements remain required.

## Diff stat from the backed-up commit

Code/resources excluding this report: `9 files changed, 528 insertions, 17 deletions`. The Room schema remains unchanged.

## Remaining risks

- The global search still reads all messages and filters in memory; it was intentionally left unchanged.
- A real-device pass is required for pixel-perfect ListView anchoring during simultaneous incoming traffic.
- The active window limit can intentionally discard the far edge from RAM; both boundaries remain reloadable by id.

