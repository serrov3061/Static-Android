package com.exteragram.messenger.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.api.ApiController;
import com.exteragram.messenger.badges.AvatarFrames;
import com.exteragram.messenger.badges.BadgesConfig;
import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.badges.FrameParams;
import com.exteragram.messenger.components.SupporterBottomSheet;
import com.exteragram.messenger.preferences.components.AvatarFramePicker;
import com.exteragram.messenger.preferences.components.CustomPreferenceCell;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/**
 * StaticGram: «Avatar frame» screen (exteraStatic badge → «Avatar frame»). Live preview on the user's own
 * avatar, the style row, the «Settings» of the selected style and Save. A frame is stored on the badge row on the server, so without
 * a badge (BadgesController.canUseFrames) this is the locked placeholder: styles locked, the
 * explanation and the Buy Badge button (only when a shop URL is configured).
 */
public final class AvatarFramePreferencesActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_PREVIEW = 1;
    private static final int ID_FRAMES = 2;
    private static final int ID_SAVE = 3;
    private static final int ID_BUY = 4;

    private PreviewCell previewCell;
    private AvatarFramePicker picker;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        ApiController.syncNow(() -> {
            if (picker != null && !isFinished) {
                picker.reloadIfUnedited();
                refresh();
            }
        });
        BadgesController.INSTANCE.refreshAllowedFrames(() -> {
            if (picker != null && !isFinished) {
                picker.reloadIfUnedited();
                refresh();
            }
        });
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.updateInterfaces && args.length > 0 && args[0] instanceof Integer
                && (((Integer) args[0]) & MessagesController.UPDATE_MASK_EMOJI_STATUS) != 0
                && picker != null && !picker.isSaving()) {
            picker.reloadIfUnedited();
            refresh();
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.AvatarFrame);
    }

    @Override
    public View createView(Context context) {
        previewCell = new PreviewCell(context);
        picker = new AvatarFramePicker(this, context, this::refresh);
        picker.setPreviewInvalidator(() -> previewCell.update());
        View view = super.createView(context);
        fragmentView = view;
        return view;
    }

    private void refresh() {
        if (picker != null) {
            picker.invalidate();
        }
        if (previewCell != null) {
            previewCell.update();
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (picker == null || previewCell == null) {
            return;
        }
        picker.load();
        previewCell.update();
        items.add(UItem.asHeader(LocaleController.getString(R.string.ExteraBadgePreview)));
        items.add(UItem.asCustom(ID_PREVIEW, previewCell));
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.AvatarFrameStyle)));
        items.add(UItem.asCustom(ID_FRAMES, picker.getCell()));
        if (picker.isAvailable()) {
            if (picker.getPending() != null) {
                items.add(UItem.asShadow());
                picker.addSettingsItems(items);
            }
            items.add(UItem.asShadow());
            items.add(UItem.asButton(ID_SAVE, LocaleController.getString(R.string.AvatarFrameSave)).accent().setEnabled(picker.canSave()));
            items.add(UItem.asShadow(LocaleController.getString(R.string.AvatarFrameInfo)));
        } else {
            boolean available = !TextUtils.isEmpty(BadgesConfig.getApiUrl());
            items.add(UItem.asShadow(LocaleController.getString(available ? R.string.AvatarFrameNeedBadgeInfo : R.string.ExteraBadgeUnavailable)));
            if (!TextUtils.isEmpty(BadgesConfig.getShopUrl())) {
                items.add(UItem.asButton(ID_BUY, R.drawable.ic_sg_badge, LocaleController.getString(R.string.ExteraBadgeBuy)).accent());
                items.add(UItem.asShadow());
            }
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_SAVE:
                if (picker != null) {
                    picker.save();
                }
                break;
            case ID_BUY:
                SupporterBottomSheet.showAlert(this, getResourceProvider());
                break;
            default:
                if (picker != null) {
                    picker.onSettingClick(item);
                }
                break;
        }
    }

    /** Own avatar with the frame being chosen, the name and the frame name under it. */
    @SuppressLint("ViewConstructor")
    private final class PreviewCell extends LinearLayout implements CustomPreferenceCell {

        private static final int AVATAR_DP = 88;
        /** Room around the avatar for the frame (a frame reaches 50/36 of the avatar radius). */
        private static final int ROOM_DP = 20;

        private final FramePreviewView avatarView;
        private final TextView nameView;
        private final TextView frameView;

        PreviewCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(16));
            avatarView = new FramePreviewView(context);
            addView(avatarView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, AVATAR_DP + 2 * ROOM_DP, Gravity.CENTER_HORIZONTAL));
            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            nameView.setGravity(Gravity.CENTER);
            addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 21, 12, 21, 0));
            frameView = new TextView(context);
            frameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            frameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            frameView.setGravity(Gravity.CENTER);
            addView(frameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 21, 2, 21, 0));
        }

        void update() {
            TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (user == null) {
                return;
            }
            avatarView.bind(user);
            nameView.setText(UserObject.getUserName(user));
            frameView.setText(AvatarFramePicker.frameName(picker != null ? picker.getPending() : null));
        }

        /** The own avatar (88dp) with the frame and settings being chosen, animated, with room for the frame. */
        private final class FramePreviewView extends View {

            private final ImageReceiver avatar = new ImageReceiver(this);
            private final AvatarDrawable avatarDrawable = new AvatarDrawable();
            private final RectF frameRect = new RectF();

            FramePreviewView(Context context) {
                super(context);
                avatar.setRoundRadius(AndroidUtilities.dp(AVATAR_DP / 2f));
            }

            void bind(TLRPC.User user) {
                avatarDrawable.setInfo(currentAccount, user);
                avatar.setForUserOrChat(user, avatarDrawable);
                invalidate();
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                avatar.onAttachedToWindow();
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                avatar.onDetachedFromWindow();
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float size = AndroidUtilities.dp(AVATAR_DP);
                float left = (getWidth() - size) / 2f, top = (getHeight() - size) / 2f;
                avatar.setImageCoords(left, top, size, size);
                avatar.draw(canvas);
                FrameParams frame = picker != null ? picker.getPendingFrame() : null;
                if (frame != null) {
                    frameRect.set(left, top, left + size, top + size);
                    AvatarFrames.draw(canvas, frameRect, frame, 1f, this, false);
                }
            }
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
}
