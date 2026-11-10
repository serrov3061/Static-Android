package com.exteragram.messenger.feed.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import com.exteragram.messenger.feed.FeedConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ChatSearchTabs;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TopicsTabsView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.GradientClip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * exteraGram 13.1.1: feed category tabs ("All", the visible categories, "+") shown in the feed ChatActivity's
 * hashtag search tabs bar. Long press on a category: edit / hide / delete.
 */
public class FeedCategoryTabsView extends FrameLayout {

    public interface Delegate {
        void onCategorySelected(int categoryId);

        void onCreateCategory();

        void onDeleteCategory(FeedConfig.Category category);

        void onEditCategory(FeedConfig.Category category);

        void onHideCategory(FeedConfig.Category category);
    }

    private static final int ID_ADD = -2;

    private final int currentAccount;
    private final Delegate delegate;
    private final BaseFragment fragment;
    private final UniversalRecyclerView listView;
    private final int[] location = new int[2];
    private final Theme.ResourcesProvider resourcesProvider;
    private int selectedCategoryId;
    private int drawnSelectedCategoryId;
    private int animateFromCategoryId;
    private HashMap<Integer, TLRPC.TL_forumTopic> topics = new HashMap<>();

    public FeedCategoryTabsView(Context context, BaseFragment fragment, int currentAccount, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.fragment = fragment;
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;
        selectedCategoryId = drawnSelectedCategoryId = FeedConfig.getInstance(currentAccount).getSelectedCategoryId();
        listView = new UniversalRecyclerView(context, currentAccount, 0, this::fillItems, this::onItemClick, this::onItemLongClick, resourcesProvider) {
            private final AnimatedFloat animatedClipL = new AnimatedFloat(this, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
            private final AnimatedFloat animatedClipR = new AnimatedFloat(this, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
            private final AnimatedFloat animateTab = new AnimatedFloat(this, 420, CubicBezierInterpolator.EASE_OUT_QUINT);
            private final GradientClip clip = new GradientClip();
            private final RectF selectionRect = new RectF();
            private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void dispatchDraw(Canvas canvas) {
                float left = animatedClipL.set(canScrollHorizontally(-1));
                float right = animatedClipR.set(canScrollHorizontally(1));
                boolean clipEdges = left > 0 || right > 0;
                if (clipEdges) {
                    canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), 0xFF);
                }
                super.dispatchDraw(canvas);
                drawSelection(canvas);
                if (clipEdges) {
                    canvas.save();
                    if (left > 0) {
                        AndroidUtilities.rectTmp.set(0, 0, AndroidUtilities.dp(12), getHeight());
                        clip.draw(canvas, AndroidUtilities.rectTmp, GradientClip.LEFT, left);
                    }
                    if (right > 0) {
                        AndroidUtilities.rectTmp.set(getWidth() - AndroidUtilities.dp(12), 0, getWidth(), getHeight());
                        clip.draw(canvas, AndroidUtilities.rectTmp, GradientClip.RIGHT, right);
                    }
                    canvas.restore();
                    canvas.restore();
                }
            }

            private void drawSelection(Canvas canvas) {
                if (FeedCategoryTabsView.this.drawnSelectedCategoryId != FeedCategoryTabsView.this.selectedCategoryId) {
                    FeedCategoryTabsView.this.animateFromCategoryId = FeedCategoryTabsView.this.drawnSelectedCategoryId;
                    animateTab.force(0);
                }
                FeedCategoryTabsView.this.drawnSelectedCategoryId = FeedCategoryTabsView.this.selectedCategoryId;
                View selectedView = null;
                View fromView = null;
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    UItem item = adapter.getItem(getChildAdapterPosition(child));
                    if (item == null || item.id == ID_ADD) {
                        continue;
                    }
                    if (item.id == FeedCategoryTabsView.this.selectedCategoryId) {
                        selectedView = child;
                    }
                    if (item.id == FeedCategoryTabsView.this.animateFromCategoryId) {
                        fromView = child;
                    }
                }
                if (selectedView == null) {
                    return;
                }
                setTabRect(selectionRect, selectedView);
                if (fromView != null) {
                    setTabRect(AndroidUtilities.rectTmp, fromView);
                    AndroidUtilities.lerp(AndroidUtilities.rectTmp, selectionRect, animateTab.set(1f), selectionRect);
                }
                selectionPaint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton, FeedCategoryTabsView.this.resourcesProvider), 31));
                canvas.drawRoundRect(selectionRect, AndroidUtilities.dp(14), AndroidUtilities.dp(14), selectionPaint);
            }

            private void setTabRect(RectF rect, View view) {
                rect.set(view.getX() + AndroidUtilities.dp(1), view.getY() + AndroidUtilities.dp(4), view.getX() + view.getWidth() - AndroidUtilities.dp(1), view.getY() + getHeight() - AndroidUtilities.dp(4));
            }

            @Override
            public Integer getSelectorColor(int position) {
                return 0;
            }
        };
        listView.setWillNotDraw(false);
        listView.adapter.setApplyBackground(false);
        listView.makeHorizontal();
        listView.setPadding(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(3), 0);
        listView.setClipToPadding(false);
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.CENTER_VERTICAL));
    }

    /** Replaces the hashtag search tabs of the feed ChatActivity with the category tabs. */
    public void attachTo(ChatSearchTabs tabs) {
        if (tabs.tabs != null) {
            tabs.tabs.setVisibility(View.GONE);
        }
        tabs.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));
        tabs.addView(this, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 7, 0, 7, 0));
    }

    public boolean isTouchInside(MotionEvent event) {
        if (event == null || !isShown()) {
            return false;
        }
        getLocationOnScreen(location);
        return event.getRawX() >= location[0] && event.getRawX() < location[0] + getWidth() && event.getRawY() >= location[1] && event.getRawY() < location[1] + getHeight();
    }

    public boolean hasCategories() {
        return !FeedConfig.getInstance(currentAccount).getVisibleCategories().isEmpty();
    }

    public void setSelectedCategory(int categoryId) {
        if (selectedCategoryId == categoryId) {
            return;
        }
        selectedCategoryId = categoryId;
        listView.adapter.update(true);
        listView.invalidate();
        scrollToSelected();
    }

    public void update() {
        selectedCategoryId = FeedConfig.getInstance(currentAccount).getSelectedCategoryId();
        listView.adapter.update(true);
        listView.invalidate();
        scrollToSelected();
    }

    private void scrollToSelected() {
        List<FeedConfig.Category> visible = FeedConfig.getInstance(currentAccount).getVisibleCategories();
        int position = 0;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).getId() == selectedCategoryId) {
                position = i + 1;
                break;
            }
        }
        listView.smoothScrollToPosition(position);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        HashMap<Integer, TLRPC.TL_forumTopic> old = topics;
        topics = new HashMap<>();
        items.add(TopicsTabsView.HorizontalTabView.Factory.asAll(false, false).setChecked(selectedCategoryId == 0));
        for (FeedConfig.Category category : FeedConfig.getInstance(currentAccount).getVisibleCategories()) {
            // the tab cell draws forum topics: reuse the objects so the cells do not rebind needlessly
            TLRPC.TL_forumTopic topic = old.get(category.getId());
            if (topic == null) {
                topic = new TLRPC.TL_forumTopic();
            }
            topic.id = category.getId();
            topic.title = category.getTitle();
            topic.icon_emoji_id = category.getEmojiId();
            topics.put(category.getId(), topic);
            items.add(TopicsTabsView.HorizontalTabView.Factory.asTab(0, topic, false).setChecked(category.getId() == selectedCategoryId));
        }
        items.add(TopicsTabsView.HorizontalTabView.Factory.asAdd());
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD) {
            delegate.onCreateCategory();
        } else if (item.id != selectedCategoryId) {
            delegate.onCategorySelected(item.id);
        }
    }

    private boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        FeedConfig.Category category = item.id > 0 ? FeedConfig.getInstance(currentAccount).getCategory(item.id) : null;
        if (category == null) {
            return false;
        }
        ItemOptions.makeOptions(fragment, view)
            .add(R.drawable.msg_edit, LocaleController.getString(R.string.FeedCategoryEdit), () -> delegate.onEditCategory(category))
            .add(R.drawable.msg_view_file, LocaleController.getString(R.string.FeedCategoryHide), () -> delegate.onHideCategory(category))
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.FeedCategoryDelete), true, () -> delegate.onDeleteCategory(category))
            .setScrimViewBackground(new TabScrimDrawable(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider)))
            .setGravity(Gravity.CENTER_HORIZONTAL)
            .show();
        return true;
    }

    public static class TabScrimDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        public TabScrimDrawable(int color) {
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            bounds.set(getBounds());
            bounds.inset(AndroidUtilities.dp(1), (bounds.height() - AndroidUtilities.dp(28)) / 2f);
            canvas.drawRoundRect(bounds, AndroidUtilities.dp(14), AndroidUtilities.dp(14), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
