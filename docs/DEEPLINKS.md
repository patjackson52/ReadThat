# ReadThat deep links

ReadThat supports canonical HTTPS links for sharing and verified app links, plus
a custom scheme for local development and integrations.

| Target | Canonical URL | Custom scheme |
| --- | --- | --- |
| Post | `https://sdui-reddit-api.patjackson52.workers.dev/post/{postId}` | `readthat://post/{postId}` |
| Comment | `https://sdui-reddit-api.patjackson52.workers.dev/post/{postId}/comment/{commentId}` | `readthat://comment/{postId}/{commentId}` |

Legacy post links using `?commentId={commentId}` or `#comment-{commentId}` are
also accepted. New links should always use the canonical path form.

## Architecture

`core:deeplink` is the platform-independent source of truth. It validates and
parses URLs, creates canonical post/comment URLs, and exposes `DeepLinkInbox` as
a small `StateFlow` boundary. The inbox retains a cold-start link until the UI
has navigated to it and explicitly consumes it.

- The classic Android app feeds both its initial intent and `onNewIntent` into
  the inbox. `MainActivity` uses `singleTask`, so a second link updates the
  existing activity instead of creating a duplicate task.
- The Compose Multiplatform app maps the parsed target to its typed
  `AppDestination.PostDetail`. A comment target also supplies
  `focusCommentId`, which requests the focused comment tree, scrolls to it, and
  highlights it.
- The iOS shell keeps one inbox for the lifetime of the SwiftUI root and feeds
  SwiftUI `onOpenURL` events into shared Kotlin code.
- The React PWA implements the same canonical routes as a browser fallback and
  focuses the requested comment.
- Links in supported post/comment text are intercepted when they point back to
  ReadThat. Other HTTPS links continue to the system browser.

Post detail remains readable when signed out. Mutating actions still send the
visitor through authentication.

## Domain association

The production Worker serves:

- `/.well-known/assetlinks.json` for Android App Links
- `/.well-known/apple-app-site-association` for iOS Universal Links

Both are emitted as `application/json` with a one-hour cache lifetime. The
Android manifest verifies `/post/` HTTPS paths for
`sdui-reddit-api.patjackson52.workers.dev`. The iOS app declares
`applinks:sdui-reddit-api.patjackson52.workers.dev` and the AASA document allows
`/post/*` for `2XAXFD3872.dev.readthat.ios`.

### Release signing requirement

The checked-in Android association includes the local debug certificate so
emulator and local-device builds verify today:

```text
15:A0:66:E7:BF:25:07:CB:0E:4D:5C:24:DE:FC:C9:75:06:EE:FF:19:B1:06:CB:7F:76:DF:C0:E9:23:00:87:6B
```

Before publishing Android, add the SHA-256 fingerprint shown under **Play App
Signing** in Play Console to the same `sha256_cert_fingerprints` array. Keep the
debug value if local builds must continue to verify. If the iOS team or bundle
identifier changes, update the AASA `appID` and the Xcode entitlement together.

## Verification

Run the shared parser tests and mobile builds:

```shell
./gradlew :core:deeplink:allTests
./gradlew :app:assembleDebug :app:testDebugUnitTest :feature:app-ui:allTests
xcodebuild -project iosApp/ReadThat.xcodeproj -scheme ReadThat \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

Verify Android after installing the APK:

```shell
adb shell pm verify-app-links --re-verify dev.readthat
adb shell pm get-app-links dev.readthat
adb shell am start -W -a android.intent.action.VIEW \
  -c android.intent.category.BROWSABLE \
  -d 'https://sdui-reddit-api.patjackson52.workers.dev/post/{postId}'
adb shell am start -W -a android.intent.action.VIEW \
  -c android.intent.category.BROWSABLE \
  -d 'https://sdui-reddit-api.patjackson52.workers.dev/post/{postId}/comment/{commentId}'
```

`pm get-app-links` must report the production host as `verified`. Test Universal
Links by tapping an HTTPS link in Notes or Messages on a signed simulator/device;
typing or pasting the URL directly into Safari's address bar intentionally keeps
the user in Safari.
