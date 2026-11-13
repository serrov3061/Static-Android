# StaticGram Android — performance audit 2026-11-11

Branch base: `feat/tg-13.2` @ 98324b281 (build 7188). Work branch: `perf/audit-20261111`
(worktree `/root/sgx-wt-perf`, kept separate because 7188 was compiling in `/root/sgx-wt-132`).
Method: static review only (no build, no device): our commits vs Telegram 13.2.1 (7be672ea),
a scanner for allocations / saveLayer / drawable loads inside `onDraw`/`dispatchDraw`/`draw`
restricted to lines blamed on our commits, grep for loops/timers/sensors/wakelocks/commit(),
and the gramsrv core logs for client request storms.

## Fixed (one commit each, on perf/audit-20261111)

| # | Commit | Where | Impact |
|---|--------|-------|--------|
| 1 | 99e39ab82 | `ui/Wallet/GramWallet.java` `getTransaction()` | **High (network/battery, server load).** `wallet.getTransactionsByIDs` is not implemented by gramsrv; the router answers `500 NOT_IMPLEMENTED` and tgnet resends any request with a 500/negative error forever (backoff capped at 10 s, `ConnectionsManager.cpp:1423-1436`), also after the transaction sheet that asked for it is closed. Core logs: 1341 such requests in ~6 h from 6 users, one every ~10 s per session. Now sent with `RequestFlagFailOnServerErrors`, an error is remembered per id for 10 min. Visible behaviour unchanged (sheet keeps the preview transaction). |
| 2 | 6dfe78ff1 | `ui/Components/FilterTabsView.java` TabView.onDraw (exteraGram folder icons) | Low-medium. A new `PorterDuffColorFilter` per tab per frame (folder swipe/tab animations). Now rebuilt only when the colour changes. |
| 3 | 72f479278 | `ui/Components/AnimatedEmojiDrawable.java` SwapAnimatedEmojiDrawable.draw | Medium in power saving. Every exteraStatic badge (dialogs list, chat sender names) is drawn with particles; the drawable kept a 15 fps Choreographer callback re-invalidating the host cell even with LiteMode particles off, while `Particles.draw` draws nothing then. Loop skipped in that case. |
| 4 | 9d0d2138f | `badges/AvatarFrames.java` + `FrameParams.java` | Low. Avatar frames built a cache-key String on every draw of every framed avatar (up to 30 fps × 6 animated + all static ones). Key memoized per FrameParams (UI thread only). |

## Findings not fixed (prioritized)

### P1 — server side, fixes all installed clients at once
- **gramsrv `notImplementedErr()` returns 500** (`/root/gramsrv/upstream/internal/rpc/errors.go:21`).
  Every Telegram client treats 500 as "retry later", so every unhandled RPC becomes an endless
  retry loop on old APKs (fix #1 only helps new builds). Also seen: `payments.getCurrencyRates`
  (`CurrenciesController.java:128`) and `toncenter.performApiRequest` (GramWallet/WalletEngine2/
  GramWalletDns) from a beta tester. Suggest answering 400 (e.g. `METHOD_NOT_SUPPORTED`) and/or
  implementing `wallet.getTransactionsByIDs`. Needs owner approval (prod core deploy).
- `messages.translateText` → `406 TRANSLATIONS_DISABLED` ~113/h from a single user (auto-translate).
  Client keeps asking per message; either enable translations or have the client stop after the
  first TRANSLATIONS_DISABLED in a session (upstream TranslateController code, left as is).

### P2 — client, worth a device check before touching
- **`Choreographer60FpsContent` posts a vsync callback forever** (`messenger/utils/Choreographer60FpsContent.java:213,230`),
  even with no subscribers: the main thread wakes on every vsync whenever the process is alive and
  the screen is on. Upstream Telegram 13.2 code, but our keep-alive foreground service keeps the
  process alive permanently, so it costs more for us. Fix idea: post only while any group/list is
  non-empty, re-post on add. Not done: shared by 27 call sites, timing-sensitive; measure first
  (`adb shell dumpsys gfxinfo` / Perfetto with app in background, screen on).
- **Launch splash Lottie parsed on the main thread in onCreate** (`StaticGramLaunchSplash`,
  raw `staticgram_splash_{light,dark}.json` ~238 KB each). Measure cold start with/without
  (`adb shell am start -W`). Could load the RLottieDrawable on a background queue.
- **ActionBarLayout opaque underlay** (`ActionBar/ActionBarLayout.java:1308-1320`, a0416a8e7):
  two extra full-screen fills per frame during every screen transition (overdraw); fine on most
  GPUs, check on a low-end device with "Debug GPU overdraw".
- **Badge particles at 15 fps in lists** (by design from exteraGram): each visible badge holder
  redraws its whole DialogCell/ChatMessageCell 15×/s. Consider dropping particles for badges on
  PERFORMANCE_CLASS_LOW only (visual change, owner decision).
- **MemberTagColors first read on the UI thread** (`messenger/MemberTagColors.java:getChat`):
  the first tag colour lookup per chat during message-cell layout blocks on the
  `sg_tag_colors<N>` SharedPreferences load. Prewarm `prefs()` on a background queue at startup.
- **Avatar frames retry invalidation** (`AvatarFrames.java`): a framed avatar without one of the
  6 animation slots calls `postInvalidateDelayed(1000)` on its host forever while visible.
  Cheap, but it keeps whole list cells redrawing once a second.

### P3 — low impact / settings screens only
- Allocations in draw of settings-only views (exteraGram): `DoubleTapCell`, `FabShapeCell`,
  `StickerShapeCell`, `AppIconPreviewView`, `AppIconsActivity` (RadialGradient per draw),
  `SupporterNumberView`, `TransactionCell` pending shimmer gradient (only while pending).
- `DialogCell` creates an `AvatarSpan` (with ImageReceiver) per cell even when sender mini
  avatars are off (0edca61f8); lazy creation would save memory per cell.
- APK size: we add ~10 MB of res/assets over Telegram 13.2.1, mostly exteraGram (official
  plugins 1.2 MB, `extera_arrow.binobj` 330 KB, app-icon webps, strings). Nothing uncompressed
  or obviously duplicated found among our own files.

### Checked and fine
- Keep-alive `NotificationsService`: foreground service only, no wakelocks/alarms; native push
  ping every 3 min (upstream).
- Wallet: TON streaming websocket runs only while a wallet page listens; `GramWalletPolling`
  backs off to 10 s and stops on result; `WalletCardMotion` sensor is off on low-end, auto-stops.
- `SgServerResolver`: background, debounced (30 s), TTL 10 min.
- Send button gem animation, nav vsync stepping (9bd5ae718): no per-frame allocations beyond a
  lambda per frame during transitions.
- Blur: canBlurChat() is ungated, but LiteMode presets keep chat blur off by default on
  low/average devices, so only users who enable it pay for it.
- `ApiController` badge sync: 1/min only in foreground.

## Needs a device to verify
- Fix #3: in power saving, badge holders in the dialogs list no longer redraw continuously
  (Profile GPU rendering bars flat when idle).
- Fix #1: open a pending/preview GRAM transaction; core logs must show at most one
  `wallet.getTransactionsByIDs` per id per 10 min from that session.
- Fixes #2/#4: folder icons and avatar frames look unchanged (tint follows active/inactive tab,
  frames animate, theme switch rebuilds frames).
