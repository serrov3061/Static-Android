# exteraStatic handoff — 26.10.2026 23:10 UTC

Repo: sg:/root/staticgram-client-extera, branch `test/sg-extera`, package `com.staticgram.org.extera` ("StaticGram Extera" test app, installs next to prod).
Build: `bash /root/build-sgx-<code>.sh` (arm64 ONLY now: `-PsgAbi=arm64-v8a :TMessagesProj_AppStandalone:assembleAfatStandalone`, copies to `/srv/dl/test/StaticGram-Extera-test-<code>-arm64.apk`, public URL https://dl.staticgram.top/test/...). Builds under `flock /root/.sg-build.lock`. Compile check: `:TMessagesProj:compileStandaloneJavaWithJavac`.
Last published: **7100** (arm64). `gradle.properties` APP_VERSION_CODE=7100 → next build is 7101 (script `/root/build-sgx-7100.sh` is the arm64-only template).

## Owner rules (enotdev)
- Build ONLY on the explicit command «собирай»; accumulate fixes. arm64 only, no universal APK for now.
- Many tasks → delegate to parallel Opus agents (never Haiku); each agent stages only its own files; verify on disk before reporting.
- Emulator (avd sgx30, port 5560, `/root/emu-7220.sh`, UI helper `/root/sgx-ui.py`) is logged into the owner's REAL account: never type/send in chats.
- Bundled "Official plugins" = owner-chosen third-party plugins; no author credit required (owner has permission). Do not decrypt/deobfuscate third-party plugins.
- Merge plan: finish extera → merge into main Android client → universal APK; then iOS/Desktop/Web.

## Done since 7098 (all in 7099/7100 unless noted "→7101")
- Plugins: bundled set = ZwyLib, Bot Login, Compact Bottom Nav, HiRez Photo, Modular Sections, Text Animation (assets/official_plugins, index.json v2). Official block visible with engine off.
- SDK/engine parity: XposedHook alias; ExteraConfig.pluginsSafeMode static field; BuildVars.EXTERA_APP_ID/HASH; files/temp FileProvider path (plugin Share); Cython/ctypes native modules (`python_sdk/_sg_native.py`); →7101: SimpleSettingFactory + set_all_settings + java_subclass hierarchy fix, create_settings errors shown red on the card (Plugin.settingsError); →7101: MediaDataController.setPlaceholderImageByIndex, TextCheckCell.setTextAndValueAndCheck(CharSequence…), ScaleStateListAnimator.apply(View,float,float,Consumer,Consumer) (Mandre Tweaks).
- Monet themes (assets monet_light/dark/black.attheme, MonetUtils, Android 12+), Monet settings icon tiles, header logo tint.
- Cherrygram port: iOS input field + footer (switch "iOS-style input field"), iOS chat header (separate switch, 7100; clipping fix →7101 in progress), share sheet folder tabs/search (being reworked →7101 as setting «Стиль окна пересылки» Стандартный/iOS).
- UI fixes: MD3 switches clipping (setClipChildren), translate button switch, pill stack menu (forceBelowScrim ×2 container Y), settings header (logo below 56dp bar, transparent at top, glass when scrolled), message menu icon row at bottom (groupMessageMenu), icon packs refresh, liquid glass only Android 13+/avg device, StaticGram title readable on light themes, Trusted plugins link removed, member-list tag colors rebind, tag colors (owner purple / admin with rights red / admin without rights green / member gray).
- →7101: settings row «Настройки exteraStatic» + larger Monet logo; avatar-corners preview and «Канал» row → StaticGram новости (channel id 2; @StaticGrams on our server is a foreign ad channel id 2187); sticker-size preview sticker; .extera backups (InvisibleEncryptor U+202F fix) + Import Settings button.

## Open / in progress (26.10 23:10)
- Agents running: share sheet iOS style + setting; iOS header pill clipping; NFT (collectible) usernames not copyable in profile.
- Text Animation: needs a separate `text_animation.dex` core; plugin is encrypted (ARM64 loader "nvcore"), expected path unknown → waiting for owner (source or path). Not decrypting.
- Mandre Tweaks: garbled text is inside the plugin's own dex (author's bug); module local_user_arrow needs exteraGram badges API (not supported).
- Not started: main Android client merge, iOS/Desktop/Web parity.
