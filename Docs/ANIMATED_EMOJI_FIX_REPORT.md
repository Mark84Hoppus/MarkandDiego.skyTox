# Animated emoji rendering and chat performance fix

## Root cause

Code inspection confirmed three defects in the previous implementation:

- A `LottieDrawable` was placed in an `ImageSpan` with the `TextView` as its callback, but there was no callback bridge that reliably forwarded every drawable invalidation/schedule event to the host view. This explains the first-frame-only symptom; visual confirmation still requires a device.
- Emoji keys were sorted on every message bind, adding avoidable work to `ChatAdapter.getView()`.
- Completion of the asynchronous emoji index load called `notifyDataSetChanged()`, rebinding the complete adapter during the chat enter transition.

No runtime trace or before/after profiler capture was possible because no Android device was connected. Performance conclusions beyond the confirmed code paths remain hypotheses until device testing.

## Changed files

- `atox/src/main/kotlin/ui/chat/AnimatedChatEmoji.kt`: frame callback bridge, lifecycle/visibility control, monotonic timing, fair scheduling, composition cache, load deduplication and safe binding reuse.
- `atox/src/main/kotlin/ui/chat/AnimatedEmojiMatcher.kt`: immutable pre-sorted index and codepoint-safe longest-match logic.
- `atox/src/main/kotlin/ui/chat/ChatAdapter.kt`: removes full-adapter refresh and preserves reusable emoji bindings.
- `atox/src/main/kotlin/ui/chat/ChatFragment.kt`: constrains visibility to the message list and delays visible-row refresh until the enter transition is released.
- `atox/src/test/kotlin/ui/chat/AnimatedEmojiMatcherTest.kt`: Unicode, VS16, ZWJ, surrogate, mixed-text and emoji-only tests.
- `atox/build.gradle.kts`: build 332 and JVM unit-test dependency.

## Rendering path

The JSON index is parsed off the main thread once and stores one pre-sorted key list. A message bind performs longest-match scanning by Unicode codepoint. Lottie compositions are held in a 32-entry LRU cache; concurrent requests for the same asset share one load. Each visible `TextView` owns distinct mutable drawables. `TextViewDrawableCallback` forwards invalidation and scheduling to that view without replacing text or requesting layout per frame.

Repeated binding of the same message/text/size in the same view reuses its drawables. Recycled or non-text rows cancel animation, remove listeners and detach callbacks. A stale asynchronous result is rejected by the view binding token, while a failed asset leaves the original Unicode emoji intact.

## Lifecycle and limits

- Direct user chats only; self-chat and AI remain unchanged.
- Maximum four running drawables.
- One Lottie cycle, with at least 20 seconds between starts per message using `SystemClock.uptimeMillis()`.
- Playback stops during scrolling, pause, detach, recycling, or when outside the message-list bounds.
- No coordinator tick runs when no emoji entries exist.
- System-disabled animations remain static.

## Verification

Commands:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :atox:testDebugUnitTest :atox:assembleDebug -PskytoxUniversal=true --no-daemon --console=plain
.\gradlew.bat :atox:assembleRelease -PskytoxUniversal=true --no-daemon --console=plain
adb devices -l
```

Results:

- Unit tests: 6 passed, 0 failed.
- Debug compilation and APK assembly: passed.
- Signed universal release assembly: passed.
- Debug APK: `C:\Temp\see\aTox\release-artifacts\0.8.34-332\skyTox-0.8.34-332-universal-debug.apk`.
- Signed release APK: `C:\Temp\see\aTox\release-artifacts\0.8.34-332\skyTox-0.8.34-332-universal-release-signed.apk`.
- Asset index: 94 entries; Lottie directory: 94 JSON files.
- Connected devices: none. Actual frame motion and transition smoothness are not yet visually confirmed.
- Profiling: not performed; no measurements are claimed.

## Known limitations and rollback

Manual Android checks from the task remain required, especially repeated 20-second playback, fast scrolling, 20 open/close cycles and app background/restore. Roll back to tag `backup-0.8.34-331-before-animated-emoji-fix-20261027` or restore `C:\Temp\see\skyTox-backups\skyTox-0.8.34-331-before-animated-emoji-fix-20261027`.

## Requirement status

| Requirement | Status |
| --- | --- |
| Preserve 94 assets/index/license | DONE |
| Direct user chats only | DONE |
| Reliable per-frame view invalidation | DONE (device validation pending) |
| Pre-sorted longest Unicode match | DONE |
| LRU composition cache and load deduplication | DONE |
| Reuse safe repeated bindings | DONE |
| No full refresh during enter transition | DONE |
| Scroll/background/detach/out-of-view pause | DONE |
| 20-second monotonic interval and max four | DONE |
| Matcher unit tests | DONE |
| Debug APK | DONE |
| Physical-device smoke test and profiling | NOT DONE (no device) |
