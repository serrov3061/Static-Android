# --- UIUtil: scale/ripple animator needs Telegram 12.10.6 ScaleStateListAnimator/RippleRadMaskDrawable APIs (stage 2)
METHOD('utils/ui/UIUtil.java', 'public static final void applyScaleStateListAnimator(', '''public static final void applyScaleStateListAnimator(final View view, final float selectorRad, final boolean top, final boolean bottom, final int basePadding, final float scale, final float tension) {
        // stage 2: exteraGram scale + ripple-mask animator (needs ScaleStateListAnimator.apply(view, scale, tension, Consumer, Consumer))
    }''')
METHOD('utils/ui/UIUtil.java', 'public static void m1801$r8$lambda$', '')
METHOD('utils/ui/UIUtil.java', 'public static Unit $r8$lambda$oVSOXQjJV1OPu6LNcwPkuNQY5Oo(', '')
# --- LocaleUtils
FIX('utils/text/LocaleUtils.java', 'return AndroidUtilities.defaultUrlScheme(str) + str;', 'return "https://" + str; // exteraGram: AndroidUtilities.defaultUrlScheme (http:// for IPv4 hosts)')
METHOD('utils/text/LocaleUtils.java', 'public static CharSequence insertHexColorsPreview(', '''public static CharSequence insertHexColorsPreview(CharSequence charSequence) {
        if (TextUtils.isEmpty(charSequence) || !containsHash(charSequence)) {
            return charSequence;
        }
        Spannable spannable = charSequence instanceof Spannable ? (Spannable) charSequence : new SpannableString(charSequence);
        for (ColorRectSpan span : spannable.getSpans(0, spannable.length(), ColorRectSpan.class)) {
            spannable.removeSpan(span);
        }
        Matcher matcher = HEX_PATTERN.matcher(spannable);
        while (matcher.find()) {
            int end = matcher.end();
            int start = end - 1;
            if (!hasConflictingHexPreviewSpan(spannable, start, end)) {
                try {
                    spannable.setSpan(new ColorRectSpan(Color.parseColor(matcher.group())), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } catch (IllegalArgumentException ignore) {
                    FileLog.e("Invalid HEX color: " + matcher.group());
                }
            }
        }
        return spannable;
    }''')
FIX('utils/text/LocaleUtils.java', 'import android.text.SpannableStringBuilder;', 'import android.text.SpannableStringBuilder;\nimport android.text.Spanned;')
# --- HeaderSettingsCell: no checked exception from the constructor
FIX('preferences/components/HeaderSettingsCell.java', 'public HeaderSettingsCell(Context context) throws PackageManager.NameNotFoundException {', 'public HeaderSettingsCell(Context context) {')
# --- StickerShapeCell
FIX('preferences/chats/components/StickerShapeCell.java', '''            this.stickerShape[i].setOnClickListener(new View.OnClickListener() {''', '''            final int index = i;
            this.stickerShape[i].setOnClickListener(new View.OnClickListener() {''')
FIX('preferences/chats/components/StickerShapeCell.java', 'StickerShapeCell.this.lambda$new$0(i, view);', 'StickerShapeCell.this.lambda$new$0(index, view);')
FIX('preferences/chats/components/StickerShapeCell.java', '''            canvas.drawText(LocaleController.getString(this.isRounded ? R.string.StickerShapeRounded : this.isRoundedAsMsg ? R.string.StickerShapeRoundedMsg : R.string.Default), (getMeasuredWidth() - ((int) Math.ceil(this.textPaint.measureText(r0)))) >> 1, AndroidUtilities.dp(102.0f), this.textPaint);''', '''            String label = LocaleController.getString(this.isRounded ? R.string.StickerShapeRounded : this.isRoundedAsMsg ? R.string.StickerShapeRoundedMsg : R.string.Default);
            canvas.drawText(label, (getMeasuredWidth() - ((int) Math.ceil(this.textPaint.measureText(label)))) >> 1, AndroidUtilities.dp(102.0f), this.textPaint);''')
FIX('preferences/chats/components/StickerShapeCell.java', 'AndroidUtilities.regular()', 'android.graphics.Typeface.DEFAULT')
# --- AltSeekbar: JADX register leftovers (r0 = factor, r7/r1/r3 = min), checked against smali
FIX('preferences/components/AltSeekbar.java', '        float f2 = (((i - i2) / 2) + i2) * 1.5f;', '        int factor = ((i - i2) / 2) + i2;\n        float f2 = factor * 1.5f;')
FIX('preferences/components/AltSeekbar.java', '        float f4 = (i2 + r0) * 0.5f;', '        float f4 = (i2 + factor) * 0.5f;')
FIX('preferences/components/AltSeekbar.java', '(this.currentValue - ((r0 + r7) * 0.5f)) / (this.min - ((r0 + r7) * 0.5f))', '(this.currentValue - f4) / (this.min - f4)')
FIX('preferences/components/AltSeekbar.java', 'seekBarView.setProgress((this.currentValue - this.min) / (this.max - r1));', 'seekBarView.setProgress((this.currentValue - this.min) / (this.max - this.min));')
FIX('preferences/components/AltSeekbar.java', 'float f2 = this.min + ((this.max - r3) * f);', 'float f2 = this.min + ((this.max - this.min) * f);')
# --- final loop variables captured by lambdas (JADX artifact)
FIX('preferences/chats/components/DoubleTapCell.java', '''        final int i2 = 0;
        while (i2 < 2) {''', '''        for (int i2Loop = 0; i2Loop < 2; i2Loop++) {
            final int i2 = i2Loop;''')
FIX('preferences/chats/components/DoubleTapCell.java', '''            }
            i2++;
        }''', '''            }
        }''')
FIX('preferences/chats/components/DoubleTapCell.java', '''                        for (final int i3 = 0; i3 < 2; i3++) {''', '''                        for (int i3Loop = 0; i3Loop < 2; i3Loop++) {
                            final int i3 = i3Loop;''')
FIX('preferences/chats/components/StickerShapeCell.java', '''        final int i = 0;
        while (i < 3) {''', '''        for (int i = 0; i < 3; i++) {''')
FIX('preferences/chats/components/StickerShapeCell.java', '''            });
            i++;
        }''', '''            });
        }''')
FIX('preferences/appearance/components/FabShapeCell.java', '''            final boolean z = true;
            if (i != 1) {
                z = false;
            }''', '''            final boolean z = i == 1;''')
# --- MessagesPreviewCell: ChatMessageCell hooks of exteraGram (online dot on avatar, share button, hideViews) come in stage 3
METHOD('preferences/chats/components/MessagesPreviewCell.java', '''                @Override // org.telegram.ui.Cells.ChatMessageCell
                public boolean isUserOnline(''', '')
FIX('preferences/chats/components/MessagesPreviewCell.java', '                        drawAvatarWithOnlineStatus(canvas, getAvatarImage());', '                        getAvatarImage().draw(canvas); // stage 3: drawAvatarWithOnlineStatus')
METHOD('preferences/chats/components/MessagesPreviewCell.java', '''                @Override // org.telegram.ui.Cells.ChatMessageCell
                public boolean shouldHideShareButton(''', '')
FIX('preferences/chats/components/MessagesPreviewCell.java', '            chatMessageCell.hideViews = i == 1;\n', '            // stage 3: chatMessageCell.hideViews = i == 1;\n')
# --- ChatListPreviewCell: ActionBar title centering/animation hooks come in stage 2
FIX('preferences/appearance/components/ChatListPreviewCell.java', 'this.actionBar.refreshTitlePosition(z);', 'this.actionBar.requestLayout(); // stage 2: ActionBar.refreshTitlePosition(z)')
FIX('preferences/appearance/components/ChatListPreviewCell.java', 'actionBar.setTitleAnimatedX(LocaleUtils.getActionBarTitle(), drawable, true, 250);', 'actionBar.setTitle(LocaleUtils.getActionBarTitle(), drawable); // stage 2: ActionBar.setTitleAnimatedX')
# --- FilterTabsPreviewCell: folder emoticons/static "All chats" tab come with tabs (stage 2)
FIX('preferences/appearance/components/FilterTabsPreviewCell.java', '        filterTabsView.setStaticAllChats(true);\n', '        // stage 2: filterTabsView.setStaticAllChats(true);\n')
FIX('preferences/appearance/components/FilterTabsPreviewCell.java', 'filterTabsView.addTab(dialogFilter.id, 0, LocaleController.getString(R.string.FilterAllChats), "💬", null, false, true, dialogFilter.locked);', 'filterTabsView.addTab(dialogFilter.id, 0, LocaleController.getString(R.string.FilterAllChats), null, false, true, dialogFilter.locked); // stage 2: emoticon "💬"')
FIX('preferences/appearance/components/FilterTabsPreviewCell.java', '''                    String str2 = dialogFilter.emoticon;
                    if (str2 == null) {
                        str2 = "📁";
                    }
                    filterTabsView.addTab(i4, i5, str, str2, dialogFilter.entities, dialogFilter.title_noanimate, false, dialogFilter.locked);''', '''                    filterTabsView.addTab(i4, i5, str, dialogFilter.entities, dialogFilter.title_noanimate, false, dialogFilter.locked); // stage 2: emoticon''')
FIX('preferences/appearance/components/FilterTabsPreviewCell.java', 'filterTabsView2.addTab(i8, i8, strArr[i6], strArr2[i6], null, false, false, false);', 'filterTabsView2.addTab(i8, i8, strArr[i6], null, false, false, false); // stage 2: emoticon strArr2[i6]')
SUB('preferences/appearance/components/FilterTabsPreviewCell.java', r'new Function\(\) \{ // from class: [^\n]*\n\s*@Override // java.util.function.Function\n\s*public final Object apply\(Object obj\) \{\n\s*return FilterTabsPreviewCell.AnonymousClass1.m1671\$r8\$lambda\$LUTilU3rkOEUjmtXHG1chqCQuQ\(\(Integer\) obj\);\n\s*\}\n\s*\}\)', 'key -> 0)')
METHOD('preferences/appearance/components/FilterTabsPreviewCell.java', 'public static /* synthetic */ Integer m1671$r8$lambda$LUTilU3rkOEUjmtXHG1chqCQuQ(', '')
# --- AvatarCornersPreviewCell: our UserNameResolver takes a Consumer<Long>
SUB('preferences/appearance/components/AvatarCornersPreviewCell.java', r'resolve\("staticgrams", new Utilities\.Callback\(\) \{[^\n]*\n\s*@Override[^\n]*\n\s*public final void run\(Object obj\) \{\n\s*AvatarCornersPreviewCell\.this\.lambda\$initRealMode\$2\(\(Long\) obj\);\n\s*\}\n\s*\}\);', 'resolve("staticgrams", id -> AvatarCornersPreviewCell.this.lambda$initRealMode$2(id));')
# --- MainPreferencesActivity: header click checks for app updates (our updater)
FIX('preferences/MainPreferencesActivity.java', 'if (!BuildVars.PM_BUILD) {', 'if (!BuildVars.isBetaApp() || true) { // exteraGram: !BuildVars.PM_BUILD (no Play Market build here)')
FIX('preferences/MainPreferencesActivity.java', 'launchActivity.checkAppUpdate(true);', 'launchActivity.checkAppUpdate(true, null);')
# --- OtherPreferencesActivity: no Firebase Crashlytics/Analytics in StaticGram; the switches only store the preference
METHOD('preferences/OtherPreferencesActivity.java', 'private final void handleCrashlyticsClick() {', '''private final void handleCrashlyticsClick() {
        // exteraGram: Firebase crashlytics collection toggle; not in StaticGram
    }''')
METHOD('preferences/OtherPreferencesActivity.java', 'private final Unit handleAnalyticsClick() {', '''private final Unit handleAnalyticsClick() {
        // exteraGram: Firebase analytics collection toggle; not in StaticGram
        return Unit.INSTANCE;
    }''')
SUB('preferences/chats/components/DoubleTapCell.java', r'\n            i2\+\+;\n        \}\n    \}\n', '\n        }\n    }\n')
# --- AppNavigationPreferencesActivity: anonymous AltSeekbar passes min=0, max=2 (smali)
FIX('preferences/appearance/AppNavigationPreferencesActivity.java', 'new AltSeekbar(context, onDrag, string, string2, string3) {', 'new AltSeekbar(context, onDrag, 0, 2, string, string2, string3) {')
# --- GeneralPreferencesActivity
FIX('preferences/GeneralPreferencesActivity.java', 'LocaleController.formatDateOnline(getFiveMinutesAgo(), null, new boolean[1])', 'LocaleController.formatDateOnline(getFiveMinutesAgo(), new boolean[1]) /* stage 3: relative variant (3-arg formatDateOnline) */')
FIX('preferences/GeneralPreferencesActivity.java', 'presentFragment(new RestrictedLanguagesSelectActivity(1));', 'presentFragment(new RestrictedLanguagesSelectActivity()); // stage 3: send-translation languages mode (ctor arg 1)')
# --- Kotlin SwitchGroup DSL -> Java SwitchGroup API
import importlib.util as _iu
_sp = _iu.spec_from_file_location('kprops', os.path.join(os.path.dirname(os.path.abspath(__file__)) if '__file__' in dir() else '/tmp/claude-0/-root/940cb9f5-d4d4-4fa3-a290-20db57db5d96/scratchpad/tools', 'kprops.py'))
_kp = _iu.module_from_spec(_sp); _sp.loader.exec_module(_kp)
for _f, _refs in [('preferences/chats/ChatsPreferencesActivity.java', {'ChatsPreferencesActivity$replyElements$1$1': 'updateReplySettings', 'ChatsPreferencesActivity$hideReactions$1$1': 'updateReplySettings'}),
                  ('preferences/appearance/AppearancePreferencesActivity.java', {'AppearancePreferencesActivity$md3Styles$1$1': 'updateMD3Styles'})]:
    _p = B + _f; _s = open(_p).read(); _s2 = _kp.convert(_s, _refs)
    if _s2 != _s: open(_p, 'w').write(_s2)
# --- AppearancePreferencesActivity
FIX('preferences/appearance/AppearancePreferencesActivity.java', '            textCell.valueImageRight = (textCell.heightDp - 34) / 2;\n', '            // stage 2: textCell.valueImageRight = (textCell.heightDp - 34) / 2;\n')
FIX('preferences/appearance/AppearancePreferencesActivity.java', 'this.actionBar.refreshTitlePosition(true);', 'this.actionBar.requestLayout(); // stage 2: ActionBar.refreshTitlePosition(true)')
# --- anonymous subclasses created in createView reference the fragment (JADX dropped this$0)
FIX('preferences/chats/ChatsPreferencesActivity.java', '''                this.parentLayout.rebuildFragments(0);
                SliderPreviewCell sliderPreviewCell2 = this.stickerSizeCell;''', '''                ChatsPreferencesActivity.this.parentLayout.rebuildFragments(0);
                SliderPreviewCell sliderPreviewCell2 = ChatsPreferencesActivity.this.stickerSizeCell;''')
FIX('preferences/appearance/AppearancePreferencesActivity.java', '''            public void rebuildFragments() {
                this.parentLayout.rebuildFragments(0);''', '''            public void rebuildFragments() {
                AppearancePreferencesActivity.this.parentLayout.rebuildFragments(0);''')
FIX('preferences/chats/ChatsPreferencesActivity.java', 'CollectionsKt.listOf((Object[]) new String[]{', 'java.util.Arrays.asList(new String[]{')
# --- SwipeActionsHelper.drawBubble: JADX output is broken ("decompiled incorrectly"); restore from smali in stage 3
METHOD('utils/chats/SwipeActionsHelper.java', 'private void drawBubble(Canvas canvas, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8, int i, boolean z) {', '''private void drawBubble(Canvas canvas, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8, int i, boolean z) {
        // stage 3: restore from smali (drawBubble), JADX output was broken
    }''')
# --- SwipeActionsCell.restartCycle: extra step only when looped with >1 action (smali)
FIX('preferences/chats/components/SwipeActionsCell.java', '''        if (this.looped) {
            i = this.actions.size() <= 1 ? 0 : 1;
        }''', '''        i = (this.looped && this.actions.size() > 1) ? 1 : 0;''')
FIX('preferences/chats/ChatsPreferencesActivity.java', '''        if (ExteraConfig.getVideoMessagesCamera() != VideoMessagesCamera.ASK) {
            z = true;
        }''', '''        z = ExteraConfig.getVideoMessagesCamera() != VideoMessagesCamera.ASK;''')
FIX('preferences/appearance/components/ChatListPreviewCell.java', '''    public void updateStatus(boolean z) {
        Drawable drawable;''', '''    public void updateStatus(boolean z) {
        Drawable drawable = null;''')
FIX('preferences/components/AltSeekbar.java', '''            if (i != this.min && i != this.max) {
            }''', '''            if (i != this.min && i != this.max) {
                i = -1;
            }''')
# --- recognition language: an already downloaded model is applied immediately (Kotlin any{} lost by JADX)
FIX('preferences/chats/ChatsPreferencesActivity.java', '''                    if (Intrinsics.areEqual(((VoiceRecognitionController.RecognitionModel) it.next()).getLanguage(), str)) {
                    }''', '''                    if (Intrinsics.areEqual(((VoiceRecognitionController.RecognitionModel) it.next()).getLanguage(), str)) {
                        runnable.run();
                        return;
                    }''')
# --- LocaleUtils.isCustomEmojiOnlyLinkMessage restored from smali (JADX set the flag for every entity)
METHOD('utils/text/LocaleUtils.java', 'public static boolean isCustomEmojiOnlyLinkMessage(TLRPC.Message message) {', '''public static boolean isCustomEmojiOnlyLinkMessage(TLRPC.Message message) {
        if (message == null || message.entities == null || message.entities.isEmpty() || !MessageObject.isMediaEmptyWebpage(message)) {
            return false;
        }
        boolean found = false;
        for (int i = 0; i < message.entities.size(); i++) {
            TLRPC.MessageEntity entity = message.entities.get(i);
            if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                String url = ((TLRPC.TL_messageEntityTextUrl) entity).url;
                if (url == null || !url.startsWith("tg://emoji?id=")) {
                    return false;
                }
                found = true;
            } else if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                if (((TLRPC.TL_messageEntityCustomEmoji) entity).local) {
                    found = true;
                }
            } else if (entity instanceof TLRPC.TL_messageEntityUrl || entity instanceof TLRPC.TL_messageEntityEmail) {
                return false;
            }
        }
        return found;
    }''')
# --- MaterialSliderUiHelper: material 1.14 (continuousModeTickCount) needs minSdk 23; we use 1.13 (minSdk 21)
FIX('utils/ui/MaterialSliderUiHelper.java', '''        if (slider.getContinuousModeTickCount() != 0) {
            slider.setContinuousModeTickCount(0);
        }''', '''        // material 1.14: slider.setContinuousModeTickCount(0)''')
FIX('utils/ui/MaterialSliderUiHelper.java', '''        if (slider.getContinuousModeTickCount() != i) {
            slider.setContinuousModeTickCount(i);
        }''', '''        // material 1.14: slider.setContinuousModeTickCount(i) (discrete tick marks)''')
