# Port of Telegram Android 13.2.1 into StaticGram (exteraGram 13.1.1 fork)

Branch `feat/tg-13.2` (worktree `/root/sgx-wt-132`), base `feat/extera-13.1.1` @ 6061df9be (versionCode 7177).
Upstream range: `3de37c8c` (13.1.1, 7237) .. `7be672ea` (13.2.1, 7291), 983 Java files, layer 232 -> 233.

## Method

Same as the 13.1.1 port (bcb96f991 / 5e59d61b): `git diff --binary 3de37c8 7be672ead | git apply -3 --index`,
then every conflicted file re-merged with `git merge-file --zdiff3` (base shown) and resolved hunk by hunk.
ChatMessageCell was merged with `--diff-algorithm=histogram` (50 -> 34 hunks, upstream split the class).

Excluded from the patch: `TMessagesProj_AppTests` (our copy is the old 12.x tree, as in 13.1.1), `gradle.properties`
(only the version name taken: 13.2.1, versionCode stays 7177), submodule gitlinks (vendored by hand).

## Native

- jni diff applied cleanly (StaticGram tgnet patches kept: gramsrv addresses/keys, resolver, failover, 16 accounts).
- New `jni/twidget` (QuickJS + LiteHTML widget engine) compiled into libtmessages via `twidget.cmake`.
- Prisma built from source now (prebuilt libprisma.a removed upstream).
- Vendored: tgcalls 1398c164 -> 545f5ae5, libprisma 31a5d6fb -> 94640c63 (our copies were pristine upstream).
  ffmpeg/libvpx sources left at the old pins; the build links the updated prebuilt `.a` from `prebuild/lib`.

## Conflicts and resolutions (76 files, ~230 hunks)

General rule: StaticGram/exteraGram behaviour kept, upstream changes adopted around it. Upstream's theme-binding
refactor (`ThemeBind.*` instead of `setTextColor/setBackground`) taken wherever our line only differed in a string
or a corner radius; our strings/links (`staticgram.top`, `sg://`, Sg* strings) and exteraGram corner settings kept.

- **ChatMessageCell** - upstream moved polls, bot buttons, audio, phone call, contact, link preview, fact check,
  accessibility provider and transition params into `ChatMessage*.java`. Ported into the new classes:
  Wide Posts width for bot buttons and polls, `staticgram.top`/`sg://` invite-button detection, poll results preview
  (exteraGram showResultsBeforeVoting) incl. vote controls, feed poll timer room, `needDrawAvatarOutside()`
  (audio, phone call, poll), hidden views + forwards counter in accessibility, forwards counter in transition
  params, plugin file icons, "edited" icon (`timeString` now returns CharSequence; RichWidgetBlock uses toString()).
  Compact mode (`isCompactMode/hasCompactNameAvatar/getCompactInShift`) combined with Wide Posts / embedded
  comments-channel admin icon / exteraGram avatar corners.
- **ProfileActivity** - upstream replaced the two name/online views with one header. exteraGram badges rebuilt for the
  single `nameTextView` (`getBadgeDrawable/applyBadgeDrawables`, user and chat), badge tint in
  `updateEmojiStatusDrawableColor`, name long press, `setKeepAlive` for bot verification, verified icon pack tint,
  deleted accounts without status, `DialogObject.getBotVerificationIcon`, SG cards observer + refresh,
  `memberTagColorsUpdated`/plugin menu observers (weak), gooey plain-fade setting, gift reorder Back handling,
  phone options kept in our `showPhoneOptions`. Upstream dropped the mute icon next to the name (followed upstream).
- **ProfileActionsView** - our version kept (segmented dividers, Material 3 buttons); upstream glass
  (`setGlassFactory`) added for the stock style only. **ProfileMusicView** - upstream glass + our pill path.
- **ActionBar** - Cherrygram/iOS centered header and exteraGram glass padding kept; upstream `centerTitleAndGlass`
  and sections background added as separate branches.
- **ChatActivityEnterView** - iOS input field (glass attach/AI/rich buttons, scheduled button placement) kept,
  ThemeBind used in the stock branches; `isCaptionNearAttach()` returns false for the iOS field.
- **ChatActivity** - double tap: widget check first, then exteraGram double-tap action; forward-without-author long
  press, iOS reply close button, glass context menu, PDF viewer, feed reply all kept. Upstream removed
  `editTextItem` (taken).
- **LaunchActivity** - resizable tablet split kept inside upstream's new checkLayout() flow; plugin app events and
  SgCrashReporter kept next to the new PipCoordinator calls; Battery Saver receiver moved into
  `LiteMode.registerBatteryReceiver` (upstream removed the activity receiver).
- **DialogsActivity** - StaticGram logo title kept; blur bounds via `MainTabsUiHelper` and `getSearchFieldHeight()`
  kept in the new glass invalidation; the archive anchor offset follows upstream (`scrollYOffset`).
- **Settings/Contacts/CallLog/MediaActivity** - upstream simple-glass screens taken; exteraGram
  `MainTabsUiHelper.setBlurBounds` in their old blur code dropped with it (see TODO). Debug menu moved upstream to
  `DebugSettingsActivity` (our localized Sg debug strings not ported).
- **SharedMediaLayout** - upstream options refactor taken; gift reorder only with sort by date re-applied.
- **MediaController** - upstream async live-photo loader replaces exteraGram's `isLivePhotoAsync`;
  playlist direction keeps `getNextTrackDirection()` (shuffle).
- **PhotoViewer** - audio focus listener, subtitles and overlay ordering kept; re-applied in the new PiP
  `restorePrimary`. **PipVideoOverlay** deleted upstream (PiP rewritten).
- **BlockingUpdateView** - StaticGram mandatory update screen kept as is.
- **UserCell** - StaticGram member tag colors kept (explicit colors, no ThemeBind keys).
- **LiteMode** - Battery Saver follow + upstream battery receiver.
- **RoundVideoCodecRecorder** - constructor takes both upstream `segmentRejectedListener` and our amplitude listener.
- Small ones: Browser (sg language/real-Telegram links before token stripping), FileUploadOperation (upload speed
  boost + big-file chunk), Utilities (`DispatchQueue2`, plugins queue stays `DispatchQueue`), Theme (hidden
  dividers before compact colors), FragmentFloatingButton (square FAB, recolored in place),
  RecyclerListView (separated headers, single-section radius, drag), strings/ids (both sides).

## TODO / follow-ups

- DONE ab24602b6: main tabs capture place follows MainTabsUiHelper.setBlurBounds (MainTabsActivity.blur3_updateFragmentsGlass).
- DONE: balance pill on glass is two glass pills (ProfileMusicView.getGlassPills + second glass drawable).
- DONE: PiP play/pause calls PhotoViewer.requestAudioFocus from its PipContent play()/pause().
- Debug menu (DebugSettingsActivity) uses upstream English item names.
- Widgets (twidget) and Firebase PNV login need server support; keep entry points hidden
  until gramsrv implements them (`BuildVars.SUPPORTS_FPNV` is upstream `true` - review for our app id).
- `channels.toggleForum` changed id (sidebar): needs gramsrv before topic toggling works on 13.2.

## Compile fixes (after the merge)

- `blur3.source.BlurredBackgroundSource*` moved upstream to `org.telegram.utils.glass.source.GlassSource*`
  (exteraGram settings/plugins/PDF/app icons, SG card sheet, GlassMenuHelper, BlurredBackgroundWithFadeDrawable);
  `GlassSourceColor.createDrawable()` -> `new BlurredBackgroundDrawable(source)`.
- ChatMessageCell members used by the split classes made package-private (Wide Posts, poll preview, forwards counter);
  `ChatMessageCell.TransitionParams` -> `ChatMessageTransitionParams` in ForwardsCountUiHelper.
- `ActionBar.setAdaptiveBackground(list, hideTitle, top, lower)` public again (exteraGram BasePreferencesActivity).
- `MessagesStorage.isForum(long,int)` public again (FolderCounters); LoadMonitor posts to `DispatchQueue2`.
- PhotoViewer swipe-to-PiP uses `AndroidUtilities.checkInlinePermissions`.
- MediaController `parseXmp` taken from upstream (HEIF motion photos), ProfileActivity array leftovers removed.

## Merged

- feat/extera-13.1.1 @ 9f10a2a46 (frozen-account snowflake, input resize bug 112) merged cleanly.
- feat/extera-13.1.1 @ e47b81815 (brand v2 icon/logo, freeze reason UI) merged; strings.xml conflict = both sides.
