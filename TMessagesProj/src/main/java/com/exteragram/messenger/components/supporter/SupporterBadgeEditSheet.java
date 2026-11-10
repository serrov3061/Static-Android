package com.exteragram.messenger.components.supporter;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.math.MathUtils;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.badges.BadgesController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.SelectAnimatedEmojiDialog;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * exteraGram 13.1.1 SupporterBadgeEditSheet: edit the own changeable (custom / developer) badge in place:
 * tap the badge for the emoji picker, caption with a live bubble preview, Save / Reset to Default /
 * Transfer. Saving goes through BadgesController.updateBadge ("badge &lt;id&gt; [text]" to the badge bot).
 * StaticGram: the caption is limited to 70 characters like the «exteraStatic badge» screen (server limit).
 */
public class SupporterBadgeEditSheet extends SupporterSheet {

    private static final int MAX_CAPTION_LENGTH = 70;

    private final EditTextCell captionCell;
    private final TextView captionPreview;
    private final long defaultId;
    private long documentId;
    private SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow emojiDialog;
    private final long initialId;
    private final String initialText;
    private final Runnable onSaved;
    private final ButtonWithCounterView resetButton;

    public static SupporterBadgeEditSheet show(BaseFragment fragment, Runnable onSaved) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        SupporterBadgeEditSheet sheet = new SupporterBadgeEditSheet(fragment, fragment.getResourceProvider(), onSaved);
        return fragment.showDialog(sheet) != null ? sheet : null;
    }

    public SupporterBadgeEditSheet(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider, Runnable onSaved) {
        super(fragment, true, resourcesProvider);
        this.onSaved = onSaved;
        Activity context = fragment.getParentActivity();
        TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
        BadgesController controller = BadgesController.INSTANCE;
        BadgeDTO defaultBadge = controller.getDefaultBadge(user);
        BadgeDTO badge = controller.getBadge(user);
        defaultId = defaultBadge != null ? defaultBadge.getDocumentId() : 0;
        initialId = badge != null ? badge.getDocumentId() : defaultId;
        initialText = badge == null || badge.getText() == null ? "" : badge.getText();
        documentId = initialId;
        setBadge(documentId);
        View iconView = getArrowView().getIconView();
        iconView.setOnClickListener(v -> openEmojiPicker());
        ScaleStateListAnimator.apply(iconView, 0.05f, 1.5f);
        setContent(LocaleController.getString(R.string.SupporterBadgeEditTitle), LocaleController.getString(R.string.SupporterBadgeEditInfo));
        addNumberPill(BadgesController.getNumber(user));
        final String name = DialogObject.getName(user);
        captionPreview = addCaptionBubble(null);

        captionCell = new EditTextCell(context, LocaleController.getString(R.string.Caption), true, false, MAX_CAPTION_LENGTH, resourcesProvider);
        captionCell.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), getThemedColor(Theme.key_windowBackgroundGray)));
        captionCell.setText(initialText);
        captionCell.editText.setTypeface(AndroidUtilities.regular());
        FrameLayout captionContainer = new FrameLayout(context);
        captionContainer.addView(captionCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 0, 12, 0));
        addView(captionContainer);

        TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.SupporterBadgeCaptionInfo));
        info.setTypeface(AndroidUtilities.regular());
        info.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setPadding(AndroidUtilities.dp(33), AndroidUtilities.dp(8), AndroidUtilities.dp(33), AndroidUtilities.dp(8));
        addView(info);

        button.setText(LocaleController.getString(R.string.Save), false);
        button.setOnClickListener(v -> save());
        resetButton = addSecondaryButton(LocaleController.getString(R.string.SupporterBadgeReset), v -> {
            setDocumentId(defaultId);
            captionCell.setText("");
        });
        if (SupporterTransfer.canTransfer(currentAccount)) {
            addSecondaryButton(LocaleController.getString(R.string.Gift2TransferDo), v ->
                    SupporterTransfer.start(fragment, container, v, resourcesProvider, this::dismiss));
        }
        captionCell.editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateResetButton();
                updateCaptionPreview(name);
            }
        });
        updateResetButton();
        updateCaptionPreview(name);
        behindKeyboardColorKey = Theme.key_dialogBackground;
        commit();
    }

    private void setDocumentId(long id) {
        documentId = id;
        setBadge(id);
        updateResetButton();
    }

    private void updateCaptionPreview(String name) {
        String text = captionCell.getText().toString().trim();
        captionPreview.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        captionPreview.setText(text.isEmpty() ? null : BadgesController.formatBadgeCaption(text, name, null, null));
    }

    private void updateResetButton() {
        resetButton.setEnabled(documentId != defaultId || captionCell.getText().length() > 0);
    }

    private void openEmojiPicker() {
        if (emojiDialog != null) {
            return;
        }
        final View iconView = getArrowView().getIconView();
        int[] location = new int[2];
        iconView.getLocationOnScreen(location);
        int popupWidth = (int) Math.min(AndroidUtilities.dp(324), AndroidUtilities.displaySize.x * 0.95f);
        int iconCenter = location[0] + iconView.getWidth() / 2;
        int popupX = MathUtils.clamp(iconCenter - popupWidth / 2, 0, AndroidUtilities.displaySize.x - popupWidth);
        final int topMarginDp = Math.round(iconView.getHeight() / AndroidUtilities.density);
        final SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] window = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        SelectAnimatedEmojiDialog picker = new SelectAnimatedEmojiDialog(fragment, getContext(), true, iconCenter - popupX, SelectAnimatedEmojiDialog.TYPE_EMOJI_STATUS, true, resourcesProvider, topMarginDp, getThemedColor(Theme.key_windowBackgroundWhiteBlueIcon), true) {
            @Override
            protected void onEmojiSelected(View view, Long id, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                if (gift != null) {
                    return; // collectible gifts are not valid badges (a badge is a custom emoji document)
                }
                setDocumentId(id == null ? defaultId : id);
                if (window[0] != null) {
                    window[0].dismiss();
                }
            }

            @Override
            protected float getScrimDrawableTranslationY() {
                return iconView.getHeight() / 2.0f - AndroidUtilities.dp(topMarginDp);
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getY() < AndroidUtilities.dp(topMarginDp)) {
                    if (window[0] != null) {
                        window[0].dismiss();
                    }
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        // the picker's scrim shows the badge view itself (the arrow or the custom emoji)
        final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable scrim = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(picker, iconView.getWidth(), AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_LARGE_140) {
            private int alpha = 255;

            @Override
            public void setAlpha(int alpha) {
                this.alpha = alpha;
            }

            @Override
            public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha);
                canvas.translate(bounds.left, bounds.top);
                canvas.scale(bounds.width() / (float) iconView.getWidth(), bounds.height() / (float) iconView.getHeight());
                iconView.draw(canvas);
                canvas.restore();
            }
        };
        scrim.setCurrentAccount(currentAccount);
        scrim.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
        scrim.setBounds(0, 0, iconView.getWidth(), iconView.getHeight());
        scrim.set(documentId, false);
        scrim.attach();
        picker.setScrimDrawable(scrim, null);
        if (defaultId != 0) {
            picker.setDefaultBadge(defaultId);
        }
        picker.useAccentForPlus = true;
        picker.setSelected(documentId != defaultId ? documentId : 0L);
        emojiDialog = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(picker, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() {
                super.dismiss();
                scrim.detach();
                emojiDialog = null;
            }
        };
        window[0] = emojiDialog;
        emojiDialog.showAsDropDown(iconView, popupX - location[0], -AndroidUtilities.dp(topMarginDp), Gravity.TOP | Gravity.LEFT);
        emojiDialog.dimBehind();
    }

    private void save() {
        String text = captionCell.getText().toString().trim();
        if (documentId == initialId && text.equals(initialText)) {
            dismiss();
            return;
        }
        AndroidUtilities.hideKeyboard(captionCell.editText);
        button.setLoading(true);
        BadgesController.INSTANCE.updateBadge(new BadgeDTO(documentId, text.isEmpty() ? null : text), result -> AndroidUtilities.runOnUIThread(() -> {
            button.setLoading(false);
            if ("ok".equals(result)) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
                if (onSaved != null) {
                    onSaved.run();
                }
                dismiss();
                return;
            }
            String error = result != null && result.startsWith("error:") ? result.substring(6).trim() : null;
            BulletinFactory.of(container, resourcesProvider).createErrorBulletin(error != null && !error.isEmpty() ? error : LocaleController.getString(R.string.UnknownError)).show();
        }));
    }

    @Override
    public void dismiss() {
        if (emojiDialog != null) {
            emojiDialog.dismiss();
        }
        super.dismiss();
    }
}
