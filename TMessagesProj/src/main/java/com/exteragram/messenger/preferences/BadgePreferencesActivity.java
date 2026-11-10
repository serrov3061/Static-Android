package com.exteragram.messenger.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.api.ApiController;
import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.badges.BadgesConfig;
import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.components.SupporterBottomSheet;
import com.exteragram.messenger.preferences.components.AvatarFramePicker;
import com.exteragram.messenger.preferences.components.CustomPreferenceCell;
import com.exteragram.messenger.utils.ExternalTelegram;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.SelectAnimatedEmojiDialog;

import java.util.ArrayList;
import java.util.Objects;

/**
 * StaticGram: «exteraStatic бейдж» screen. Shows a preview of the current account's badge.
 * A custom badge (canChangeBadge) can be edited here: emoji from exteraGram's badge picker
 * (SelectAnimatedEmojiDialog badgePicker mode) and a caption, saved through the inline-query
 * protocol to the badge bot. Otherwise Buy / Upgrade open the shop link from app config.
 */
public final class BadgePreferencesActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_PREVIEW = 1;
    private static final int ID_EMOJI = 2;
    private static final int ID_TEXT = 3;
    private static final int ID_SAVE = 4;
    private static final int ID_BUY = 5;
    private static final int ID_UPGRADE = 6;
    /** «Avatar frame» button: opens AvatarFramePreferencesActivity (locked placeholder without frames). */
    public static final int ID_FRAME = 7;
    public static final String AVATAR_FRAME_ALIAS = "avatarFrame";
    /** exteraGram 13.1.1 «Your Perks» (SupporterWelcomeSheet) for supporters. */
    private static final int ID_PERKS = 8;
    /** Caption limit sent to the bot (the server moderates and may be stricter). */
    private static final int MAX_TEXT_LENGTH = 70;

    private PreviewCell previewCell;
    private FrameLayout textFieldContainer;
    private EditTextBoldCursor textField;
    private long pendingDocumentId;
    private boolean pendingLoaded;
    private boolean saving;
    private SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow emojiPopup;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        ApiController.syncNow(() -> {
            if (previewCell != null && !isFinished) {
                if (pendingLoaded && !isEdited()) {
                    pendingLoaded = false;
                }
                refresh();
            }
        });
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        if (emojiPopup != null) {
            emojiPopup.dismiss();
            emojiPopup = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.updateInterfaces && args.length > 0 && args[0] instanceof Integer
                && (((Integer) args[0]) & MessagesController.UPDATE_MASK_EMOJI_STATUS) != 0 && !saving) {
            if (pendingLoaded && !isEdited()) {
                pendingLoaded = false;
            }
            refresh();
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.ExteraBadge);
    }

    @Override
    public View createView(Context context) {
        previewCell = new PreviewCell(context);
        textFieldContainer = new FrameLayout(context);
        textField = new EditTextBoldCursor(context);
        textField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        textField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textField.setCursorSize(AndroidUtilities.dp(20));
        textField.setCursorWidth(1.5f);
        textField.setBackground(null);
        textField.setSingleLine(true);
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEXT_LENGTH)});
        textField.setHint(LocaleController.getString(R.string.ExteraBadgeTextHint));
        textField.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        textField.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (previewCell != null) {
                    previewCell.update(pendingBadge(), false);
                }
            }
        });
        textFieldContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        textFieldContainer.addView(textField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 21, 0, 21, 0));
        View view = super.createView(context);
        fragmentView = view;
        return view;
    }

    private TLRPC.User currentUser() {
        return getUserConfig().getCurrentUser();
    }

    private BadgeDTO currentBadge() {
        return BadgesController.INSTANCE.getBadge(currentUser());
    }

    private boolean isCustom() {
        TLRPC.User user = currentUser();
        return user != null && BadgesController.INSTANCE.hasBadge(user) && BadgesController.INSTANCE.canChangeBadge(user);
    }

    private void loadPending() {
        if (pendingLoaded) {
            return;
        }
        pendingLoaded = true;
        BadgeDTO badge = currentBadge();
        pendingDocumentId = badge != null ? badge.getDocumentId() : 0;
        if (textField != null) {
            String text = badge != null && badge.getText() != null ? badge.getText() : "";
            textField.setText(text);
            textField.setSelection(textField.length());
        }
    }

    private String pendingText() {
        if (textField == null) {
            return null;
        }
        String text = textField.getText().toString().trim();
        return TextUtils.isEmpty(text) ? null : text;
    }

    private BadgeDTO pendingBadge() {
        if (!isCustom()) {
            return currentBadge();
        }
        return pendingDocumentId != 0 ? new BadgeDTO(pendingDocumentId, pendingText()) : currentBadge();
    }

    private boolean isEdited() {
        BadgeDTO badge = currentBadge();
        if (badge == null) {
            return false;
        }
        return pendingDocumentId != badge.getDocumentId() || !Objects.equals(pendingText(), TextUtils.isEmpty(badge.getText()) ? null : badge.getText());
    }

    @Override
    public void onResume() {
        super.onResume();
        // the frame may have been changed on the «Avatar frame» screen
        refresh();
    }

    private void refresh() {
        if (previewCell != null) {
            previewCell.update(pendingBadge(), true);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean custom = isCustom();
        if (custom) {
            loadPending();
        }
        previewCell.update(pendingBadge(), false);
        items.add(UItem.asHeader(LocaleController.getString(R.string.ExteraBadgePreview)));
        items.add(UItem.asCustom(ID_PREVIEW, previewCell));
        items.add(UItem.asShadow());
        TLRPC.User self = currentUser();
        if (self != null && BadgesController.INSTANCE.isSupporter(self.id)) {
            items.add(UItem.asButton(ID_PERKS, R.drawable.menu_supporter_badge, LocaleController.getString(R.string.DeveloperPerksTitle)));
        }
        // «Avatar frame» for everyone: frame holders open the picker, others get the locked
        // placeholder (explanation + Buy Badge). Gate: BadgesController.canUseFrames only.
        boolean frames = BadgesController.INSTANCE.canUseFrames(currentUser());
        items.add(uButton$TMessagesProj(ID_FRAME, LocaleController.getString(R.string.AvatarFrame),
                frames ? R.drawable.msg_openprofile : R.drawable.msg_premium_lock2,
                AvatarFramePicker.frameName(frames ? AvatarFramePicker.currentFrame(currentAccount) : null),
                null, null, true, AVATAR_FRAME_ALIAS, false, false, null));
        String shopUrl = BadgesConfig.getShopUrl();
        boolean available = !TextUtils.isEmpty(BadgesConfig.getApiUrl());
        if (custom) {
            items.add(UItem.asShadow());
            items.add(UItem.asButton(ID_EMOJI, R.drawable.msg_emoji_smiles, LocaleController.getString(R.string.ExteraBadgeEmoji)));
            items.add(UItem.asHeader(LocaleController.getString(R.string.ExteraBadgeText)));
            items.add(UItem.asCustom(ID_TEXT, textFieldContainer));
            items.add(UItem.asButton(ID_SAVE, LocaleController.getString(R.string.ExteraBadgeSave)).accent().setEnabled(!saving));
            items.add(UItem.asShadow(LocaleController.getString(R.string.ExteraBadgeCustomInfo)));
        } else if (BadgesController.INSTANCE.hasBadge(currentUser())) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.ExteraBadgeStandardInfo)));
            if (!TextUtils.isEmpty(shopUrl)) {
                items.add(UItem.asButton(ID_UPGRADE, R.drawable.msg_premium_badge, LocaleController.getString(R.string.ExteraBadgeUpgrade)).accent());
                items.add(UItem.asShadow());
            }
        } else {
            items.add(UItem.asShadow(LocaleController.getString(available ? R.string.ExteraBadgeNoneInfo : R.string.ExteraBadgeUnavailable)));
            if (!TextUtils.isEmpty(shopUrl)) {
                items.add(UItem.asButton(ID_BUY, R.drawable.ic_sg_badge, LocaleController.getString(R.string.ExteraBadgeBuy)).accent());
                items.add(UItem.asShadow());
            }
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_PREVIEW: {
                BadgeDTO badge = pendingBadge();
                if (badge != null) {
                    BadgesController.INSTANCE.showBadgeBulletin(this, badge, currentUser(), getResourceProvider(), currentAccount, null, Boolean.FALSE);
                }
                break;
            }
            case ID_EMOJI:
                showEmojiPicker(view);
                break;
            case ID_PERKS:
                SupporterBottomSheet.showAlert(this, null, getResourceProvider(), null);
                break;
            case ID_SAVE:
                save();
                break;
            case ID_FRAME:
                // AvatarFramePreferencesActivity is also the locked placeholder (canUseFrames == false)
                presentFragment(new AvatarFramePreferencesActivity());
                break;
            case ID_BUY:
                SupporterBottomSheet.showAlert(this, getResourceProvider());
                break;
            case ID_UPGRADE: {
                String url = BadgesConfig.getShopUrl();
                if (!TextUtils.isEmpty(url)) {
                    ExternalTelegram.openUrl(getParentActivity(), url);
                }
                break;
            }
            default:
                break;
        }
    }

    private void showEmojiPicker(View anchor) {
        if (emojiPopup != null || getParentActivity() == null) {
            return;
        }
        BadgeDTO defaultBadge = BadgesController.INSTANCE.getDefaultBadge();
        int popupWidth = (int) Math.min(AndroidUtilities.dp(340 - 16), AndroidUtilities.displaySize.x * .95f);
        int xoff = Math.max(0, (anchor.getWidth() - popupWidth) / 2);
        SelectAnimatedEmojiDialog picker = new SelectAnimatedEmojiDialog(this, getContext(), true, null, SelectAnimatedEmojiDialog.TYPE_EMOJI_STATUS, true, getResourceProvider(), 16, Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon, getResourceProvider()), true) {
            @Override
            protected void onEmojiSelected(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                long id = documentId != null ? documentId : (defaultBadge != null ? defaultBadge.getDocumentId() : 0);
                if (id != 0) {
                    pendingDocumentId = id;
                    if (previewCell != null) {
                        previewCell.update(pendingBadge(), true);
                    }
                }
                if (emojiPopup != null) {
                    emojiPopup.dismiss();
                }
            }
        };
        if (defaultBadge != null) {
            picker.setDefaultBadge(defaultBadge.getDocumentId());
        }
        picker.useAccentForPlus = true;
        picker.setSelected(pendingDocumentId == 0 || defaultBadge != null && pendingDocumentId == defaultBadge.getDocumentId() ? 0L : pendingDocumentId);
        picker.setSaveState(3);
        emojiPopup = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(picker, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() {
                super.dismiss();
                emojiPopup = null;
            }
        };
        emojiPopup.showAsDropDown(anchor, xoff, 0, Gravity.TOP | Gravity.LEFT);
        emojiPopup.dimBehind();
    }

    private void save() {
        if (saving) {
            return;
        }
        BadgeDTO badge = pendingBadge();
        if (badge == null || badge.getDocumentId() == 0) {
            return;
        }
        if (TextUtils.isEmpty(BadgesConfig.getBotUsername())) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ExteraBadgeUnavailable)).show();
            return;
        }
        saving = true;
        AndroidUtilities.hideKeyboard(textField);
        refresh();
        BadgesController.INSTANCE.updateBadge(new BadgeDTO(badge.getDocumentId(), pendingText()), result -> AndroidUtilities.runOnUIThread(() -> {
            saving = false;
            if ("ok".equals(result)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ExteraBadgeSaved)).show();
            } else {
                String reason = TextUtils.isEmpty(result) ? LocaleController.getString(R.string.UnknownError) : result;
                BulletinFactory.of(this).createErrorBulletin(LocaleController.formatString(R.string.ExteraBadgeSaveError, reason)).show();
            }
            refresh();
        }));
    }

    /** Profile-like preview: avatar, name with emoji status and the badge right after it, and the badge caption. */
    @SuppressLint("ViewConstructor")
    private final class PreviewCell extends FrameLayout implements CustomPreferenceCell {

        private final BackupImageView avatarView;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final SimpleTextView nameView;
        private final TextView captionView;
        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable statusDrawable;
        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable badgeDrawable;
        private Drawable placeholder;
        // same size as the badge/emoji status slot in DialogCell (22dp)
        // TODO: show verified as the third icon (status -> badge -> verified) once SimpleTextView has that slot
        private static final int BADGE_SIZE_DP = 22;

        private final android.graphics.RectF frameRect = new android.graphics.RectF();

        /** The saved frame around the avatar, drawn here so it may extend past the avatar view. */
        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            com.exteragram.messenger.badges.FrameParams frame = AvatarFramePicker.currentParams(currentAccount);
            if (frame != null && avatarView.getVisibility() == VISIBLE) {
                frameRect.set(avatarView.getLeft(), avatarView.getTop(), avatarView.getRight(), avatarView.getBottom());
                com.exteragram.messenger.badges.AvatarFrames.draw(canvas, frameRect, frame, 1f, this, false);
            }
        }

        PreviewCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(AndroidUtilities.dp(28));
            addView(avatarView, LayoutHelper.createFrame(56, 56, Gravity.LEFT | Gravity.CENTER_VERTICAL, 21, 14, 0, 14));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            nameView = new SimpleTextView(context);
            nameView.setTextSize(17);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setDrawablePadding(AndroidUtilities.dp(4));
            statusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(nameView, AndroidUtilities.dp(BADGE_SIZE_DP), AnimatedEmojiDrawable.CACHE_TYPE_EMOJI_STATUS);
            badgeDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(nameView, AndroidUtilities.dp(BADGE_SIZE_DP), AnimatedEmojiDrawable.CACHE_TYPE_EMOJI_STATUS);
            texts.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24));
            captionView = new TextView(context);
            captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            captionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            captionView.setMaxLines(3);
            captionView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 89, 14, 21, 14));
        }

        void update(BadgeDTO badge, boolean animated) {
            TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (user == null) {
                return;
            }
            avatarDrawable.setInfo(currentAccount, user);
            avatarView.setForUserOrChat(user, avatarDrawable);
            avatarView.invalidate();
            invalidate();
            nameView.setText(UserObject.getUserName(user));
            int color = Theme.getColor(Theme.key_chats_verifiedBackground);
            Long statusId = UserObject.getEmojiStatusDocumentId(user);
            if (statusId != null) {
                statusDrawable.set(statusId, animated);
                statusDrawable.setColor(color);
                nameView.setRightDrawable(statusDrawable);
            } else {
                nameView.setRightDrawable(null);
            }
            if (badge != null && badge.getDocumentId() != 0) {
                badgeDrawable.set(badge.getDocumentId(), animated);
                badgeDrawable.setColor(color);
                nameView.setRightDrawable2(badgeDrawable);
                String text = badge.getText();
                CharSequence caption = text != null ? LocaleUtils.formatWithUsernames(text) : null;
                if (caption == null) {
                    caption = AndroidUtilities.replaceTags(LocaleController.formatString(BadgesController.INSTANCE.isDeveloper(user) ? R.string.Developer : R.string.Supporter, user.first_name));
                }
                captionView.setText(caption);
            } else {
                // no badge yet: show the standard StaticGram badge icon as a hint of what it looks like
                if (placeholder == null) {
                    placeholder = new FixedSizeDrawable(getContext().getResources().getDrawable(R.drawable.ic_sg_badge).mutate(), AndroidUtilities.dp(BADGE_SIZE_DP));
                }
                placeholder.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                placeholder.setAlpha(110);
                nameView.setRightDrawable2(placeholder);
                captionView.setText(LocaleController.getString(R.string.Online)); // the description lives in the section footer
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            statusDrawable.attach();
            badgeDrawable.attach();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            statusDrawable.detach();
            badgeDrawable.detach();
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /** Wraps a drawable so SimpleTextView (which lays icons out by intrinsic size) draws it at a fixed square size. */
    private static final class FixedSizeDrawable extends Drawable {
        private final Drawable inner;
        private final int size;

        FixedSizeDrawable(Drawable inner, int size) {
            this.inner = inner;
            this.size = size;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(Canvas canvas) {
            inner.setBounds(getBounds());
            inner.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            inner.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
