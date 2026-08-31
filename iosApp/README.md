# ReadThat iOS

Open `ReadThat.xcodeproj` after generating it with `xcodegen generate` in this
directory. The Xcode build phase invokes
`:composeApp:embedAndSignAppleFrameworkForXcode`, so Gradle owns the shared Room
3 database, repositories, lifecycle ViewModel, Compose UI, cache, and URLSession
transport while Xcode owns signing and the native app lifecycle. PhotosUI,
native sharing, AVPlayer HLS, and `StreamAssetDownloadManager` are narrow native
shims. See [`../docs/IOS_KMP.md`](../docs/IOS_KMP.md).

Set `READTHAT_API_BASE_URL` to the same HTTPS Worker origin used by Android.
Optional `READTHAT_DEMO_USERNAME` and `READTHAT_DEMO_PASSWORD` settings match
the Android demo build. Session tokens are stored in Apple Keychain.

For a local Wrangler server, a Debug build may use
`http://127.0.0.1:8787` or `http://localhost:8787`. The Debug configuration
explicitly enables this loopback-only exception; Release remains HTTPS-only.
The app does not set `NSAllowsArbitraryLoads`, and cleartext LAN/internet hosts
are still rejected in shared Kotlin code before URLSession sees a request.
