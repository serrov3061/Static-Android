package com.exteragram.messenger.feed.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.exteragram.messenger.feed.FeedConfig;
import com.exteragram.messenger.feed.FeedController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.utils.ui.UIUtil;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.SelectAnimatedEmojiDialog;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * exteraGram 13.1.1: create / edit a feed category: emoji icon, name and the channels it contains
 * (channels hidden from All can be added too).
 */
public final class FeedCategoryEditActivity extends BasePreferencesActivity {

    private static final int MENU_SEARCH = 0;
    private static final int MENU_OTHER = 2;
    private static final int MENU_SELECT_ALL = 3;
    private static final int MENU_DESELECT_ALL = 4;
    private static final int MENU_DELETE = 5;

    private static final Comparator<TLRPC.Chat> BY_TITLE = (a, b) -> {
        String ta = a.title != null ? a.title.toLowerCase(Locale.ROOT) : "";
        String tb = b.title != null ? b.title.toLowerCase(Locale.ROOT) : "";
        return ta.compareTo(tb);
    };

    private final FeedConfig.Category initialCategory;
    private final boolean selectOnCreate;
    private final HashSet<Long> selectedChannels;
    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private boolean channelsLoaded;
    private long emojiId;
    private String query;
    private boolean searching;

    private FrameLayout iconCell;
    private FrameLayout iconButton;
    private BackupImageView iconView;
    private EditTextCell titleCell;
    private FrameLayout buttonContainer;
    private View otherItem;
    private SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow emojiDialog;

    /**
     * @param categoryId     0 creates a new category
     * @param selectOnCreate select the new category in the feed after it is created
     */
    public FeedCategoryEditActivity(int categoryId, boolean selectOnCreate) {
        this.selectOnCreate = selectOnCreate;
        initialCategory = FeedConfig.getInstance(currentAccount).getCategory(categoryId);
        emojiId = initialCategory != null ? initialCategory.getEmojiId() : 0;
        selectedChannels = new HashSet<>(initialCategory != null ? initialCategory.getChannels() : Collections.emptySet());
    }

    public static void showDeleteDialog(BaseFragment fragment, FeedConfig.Category category, Runnable onDeleted) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.FeedCategoryDelete));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.FeedCategoryDeleteConfirm, category.getTitle())));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
            FeedConfig.getInstance(account).deleteCategory(category.getId());
            FeedController.getInstance(account).notifyCategoriesChanged();
            if (onDeleted != null) {
                onDeleted.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold, fragment.getResourceProvider()));
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(initialCategory != null ? R.string.FeedCategoryEdit : R.string.FeedCategoryNew);
    }

    @Override
    public View createView(Context context) {
        createHeaderViews(context);
        View view = super.createView(context);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == MENU_SELECT_ALL) {
                    setAllSelected(true);
                } else if (id == MENU_DESELECT_ALL) {
                    setAllSelected(false);
                } else if (id == MENU_DELETE && initialCategory != null) {
                    showDeleteDialog(FeedCategoryEditActivity.this, initialCategory, FeedCategoryEditActivity.this::finishFragment);
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                if (otherItem != null) {
                    otherItem.setVisibility(View.GONE);
                }
                showSaveButton(false);
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                if (otherItem != null) {
                    otherItem.setVisibility(View.VISIBLE);
                }
                showSaveButton(true);
                query = null;
                updateList();
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString().trim().toLowerCase(Locale.getDefault());
                updateList();
            }
        });
        searchItem.setSearchPaddingStart(7);
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        ActionBarMenuItem other = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        other.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        other.addSubItem(MENU_SELECT_ALL, R.drawable.msg_select, LocaleController.getString(R.string.SelectAll));
        other.addSubItem(MENU_DESELECT_ALL, R.drawable.msg_cancel, LocaleController.getString(R.string.DeselectAll));
        if (initialCategory != null) {
            other.addColoredGap();
            ActionBarMenuSubItem delete = other.addSubItem(MENU_DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.FeedCategoryDelete));
            int red = getThemedColor(Theme.key_text_RedRegular);
            delete.setColors(red, red);
            delete.setSelectorColor(Theme.multAlpha(red, 0.12f));
        }
        otherItem = other;

        ButtonWithCounterView button = new ButtonWithCounterView(context, true, getResourceProvider());
        button.setRound();
        button.setText(LocaleController.getString(initialCategory != null ? R.string.Save : R.string.Create), false);
        button.setOnClickListener(v -> save());
        buttonContainer = new FrameLayout(context);
        buttonContainer.setBackground(UIUtil.createBottomFade(getThemedColor(Theme.key_windowBackgroundGray)));
        buttonContainer.addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 12, 16, 12));
        ((FrameLayout) view).addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        listView.setPadding(0, listView.getPaddingTop(), 0, AndroidUtilities.dp(72));
        loadChannels();
        return view;
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        if (buttonContainer != null) {
            buttonContainer.setPadding(0, 0, 0, bottom);
        }
        listView.setPadding(0, listView.getPaddingTop(), 0, bottom + AndroidUtilities.dp(72));
    }

    private void showSaveButton(boolean show) {
        FrameLayout container = buttonContainer;
        if (container == null) {
            return;
        }
        container.animate().cancel();
        if (show) {
            container.setVisibility(View.VISIBLE);
        }
        container.animate()
            .alpha(show ? 1f : 0f)
            .translationY(show ? 0 : AndroidUtilities.dp(24))
            .setDuration(220)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .withEndAction(() -> {
                if (!show) {
                    container.setVisibility(View.GONE);
                }
            })
            .start();
    }

    private void createHeaderViews(Context context) {
        iconCell = new FrameLayout(context);
        iconCell.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        iconView = new BackupImageView(context);
        iconButton = createIconCircle(context, iconView);
        iconButton.setOnClickListener(v -> openEmojiDialog());
        ScaleStateListAnimator.apply(iconButton);
        iconCell.addView(iconButton, LayoutHelper.createFrame(100, 100, Gravity.CENTER));
        bindIcon(iconView);

        titleCell = new EditTextCell(context, LocaleController.getString(R.string.FeedCategoryName), false, false, 24, getResourceProvider());
        titleCell.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleCell.editText.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        titleCell.hideKeyboardOnEnter();
        titleCell.setText(initialCategory != null ? initialCategory.getTitle() : "");
    }

    private FrameLayout createIconCircle(Context context, BackupImageView imageView) {
        FrameLayout circle = new FrameLayout(context);
        circle.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(100), getThemedColor(Theme.key_windowBackgroundWhite)));
        circle.addView(imageView, LayoutHelper.createFrame(64, 64, Gravity.CENTER));
        return circle;
    }

    private void bindIcon(BackupImageView imageView) {
        if (emojiId == 0) {
            imageView.setAnimatedEmojiDrawable(null);
            imageView.setImageResource(R.drawable.menu_smile_add);
            imageView.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogEmptyImage), PorterDuff.Mode.SRC_IN));
            return;
        }
        imageView.setAnimatedEmojiDrawable(AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_LARGE, emojiId));
        imageView.setColorFilter(null);
        imageView.setEmojiColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
    }

    private void openEmojiDialog() {
        if (emojiDialog != null || !iconButton.isAttachedToWindow()) {
            return;
        }
        AndroidUtilities.hideKeyboard(titleCell.editText);
        titleCell.editText.clearFocus();
        Context context = iconButton.getContext();
        final SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] popup = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        final BackupImageView scrimIconView = new BackupImageView(context);
        FrameLayout scrimIcon = createIconCircle(context, scrimIconView);
        scrimIcon.setOnClickListener(v -> {
            if (popup[0] != null) {
                popup[0].dismiss();
            }
        });
        bindIcon(scrimIconView);
        SelectAnimatedEmojiDialog layout = new SelectAnimatedEmojiDialog(this, context, true, null, SelectAnimatedEmojiDialog.TYPE_AI_STYLE_ICON, getResourceProvider()) {
            @Override
            public boolean isBottom() {
                return false;
            }

            @Override
            protected void onEmojiSelected(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                FeedCategoryEditActivity.this.emojiId = documentId != null ? documentId : 0;
                FeedCategoryEditActivity.this.bindIcon(FeedCategoryEditActivity.this.iconView);
                FeedCategoryEditActivity.this.bindIcon(scrimIconView);
                if (popup[0] != null) {
                    popup[0].dismiss();
                }
            }
        };
        layout.setSelected(emojiId != 0 ? emojiId : null);
        FrameLayout content = new FrameLayout(context);
        content.addView(scrimIcon, LayoutHelper.createFrame(100, 100, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        content.addView(layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 108, 0, 0));
        SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow window = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(content, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            private boolean dismissing;

            @Override
            public void dismiss() {
                if (dismissing) {
                    return;
                }
                dismissing = true;
                FeedCategoryEditActivity.this.emojiDialog = null;
                layout.onDismiss(super::dismiss);
            }
        };
        popup[0] = window;
        emojiDialog = window;
        int width = Math.min(AndroidUtilities.dp(324), (int) (AndroidUtilities.displaySize.x * 0.95f));
        int[] location = new int[2];
        iconButton.getLocationInWindow(location);
        window.showAtLocation(iconButton, Gravity.TOP | Gravity.LEFT, location[0] + (iconButton.getWidth() - width) / 2, location[1]);
        window.dimBehind();
        layout.onShow(() -> {
            if (popup[0] != null) {
                popup[0].dismiss();
            }
        });
    }

    private void loadChannels() {
        FeedController.getInstance(currentAccount).loadChannels(true, (list, includedCount, failed, configGeneration) -> {
            if (failed) {
                return;
            }
            channels.clear();
            for (int i = 0; i < list.size(); i++) {
                TLRPC.Chat chat = list.get(i);
                TLRPC.Chat actual = getMessagesController().getChat(chat.id);
                channels.add(actual != null ? actual : chat);
            }
            Set<Long> initial = initialCategory != null ? initialCategory.getChannels() : Collections.emptySet();
            // channels already in the category first, then by title
            Collections.sort(channels, (a, b) -> {
                boolean ia = initial.contains(-a.id);
                boolean ib = initial.contains(-b.id);
                if (ia != ib) {
                    return ia ? -1 : 1;
                }
                return BY_TITLE.compare(a, b);
            });
            channelsLoaded = true;
            updateList();
        });
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        String q = query;
        boolean noQuery = TextUtils.isEmpty(q);
        if (noQuery) {
            items.add(UItem.asCustomShadow(iconCell));
            items.add(UItem.asCustom(titleCell));
            items.add(UItem.asShadow());
        }
        ArrayList<UItem> shown = new ArrayList<>();
        ArrayList<UItem> hidden = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat chat = channels.get(i);
            if (noQuery || (chat.title != null && chat.title.toLowerCase(Locale.getDefault()).contains(q))) {
                (feedConfig.isExcluded(-chat.id) ? hidden : shown).add(UItem.asUserCheckbox(Long.hashCode(chat.id), chat).setChecked(selectedChannels.contains(-chat.id)));
            }
        }
        if (!shown.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedChannels)));
            items.addAll(shown);
        }
        if (!hidden.isEmpty()) {
            if (!shown.isEmpty()) {
                items.add(UItem.asShadow());
            }
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedHiddenFromAll)));
            items.addAll(hidden);
        }
        if (noQuery && (!shown.isEmpty() || !hidden.isEmpty())) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedCategoryChannelsInfo)));
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        TLRPC.Chat chat = item.object instanceof TLRPC.Chat ? (TLRPC.Chat) item.object : null;
        if (chat != null) {
            toggleBooleanSettingAndRefresh$TMessagesProj(item, checked -> {
                if (checked) {
                    selectedChannels.add(-chat.id);
                } else {
                    selectedChannels.remove(-chat.id);
                }
            });
        }
    }

    private void setAllSelected(boolean selected) {
        if (selected) {
            for (int i = 0; i < channels.size(); i++) {
                selectedChannels.add(-channels.get(i).id);
            }
        } else {
            selectedChannels.clear();
        }
        updateList();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (searching) {
            if (invoked) {
                actionBar.closeSearchField();
            }
            return false;
        }
        if (!hasChanges()) {
            return super.onBackPressed(invoked);
        }
        if (invoked) {
            showUnsavedDialog();
        }
        return false;
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return !hasChanges() && super.isSwipeBackEnabled(event);
    }

    private String currentTitle() {
        CharSequence text = titleCell != null ? titleCell.getText() : null;
        return text == null ? "" : text.toString().trim();
    }

    private boolean hasChanges() {
        if (initialCategory == null) {
            return currentTitle().length() > 0 || emojiId != 0 || !selectedChannels.isEmpty();
        }
        return !Objects.equals(currentTitle(), initialCategory.getTitle()) || emojiId != initialCategory.getEmojiId() || !selectedChannels.equals(initialCategory.getChannels());
    }

    private void showUnsavedDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.UnsavedChanges));
        builder.setMessage(LocaleController.getString(R.string.FeedCategoryUnsavedChanges));
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> save());
        builder.setNegativeButton(LocaleController.getString(R.string.PassportDiscard), (dialog, which) -> finishFragment());
        showDialog(builder.create());
    }

    private void save() {
        String title = currentTitle();
        if (title.length() == 0) {
            BotWebViewVibrationEffect.APP_ERROR.vibrate();
            AndroidUtilities.shakeViewSpring(titleCell);
            titleCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(titleCell.editText);
            return;
        }
        HashSet<Long> result;
        if (channelsLoaded) {
            // drop channels that are no longer feed channels
            HashSet<Long> available = new HashSet<>();
            for (int i = 0; i < channels.size(); i++) {
                available.add(-channels.get(i).id);
            }
            result = new HashSet<>(selectedChannels);
            result.retainAll(available);
        } else {
            result = selectedChannels;
        }
        FeedController feedController = FeedController.getInstance(currentAccount);
        FeedConfig.Category saved = FeedConfig.getInstance(currentAccount).saveCategory(initialCategory != null ? initialCategory.getId() : 0, title, emojiId, result);
        if (initialCategory != null || !selectOnCreate || !feedController.selectCategory(saved.getId())) {
            feedController.notifyCategoriesChanged();
        }
        finishFragment();
    }

    @Override
    public void onFragmentDestroy() {
        if (emojiDialog != null) {
            emojiDialog.dismiss();
        }
        super.onFragmentDestroy();
    }
}
