package com.exteragram.messenger.feed.ui;

import android.view.View;

import com.exteragram.messenger.feed.FeedConfig;
import com.exteragram.messenger.feed.FeedController;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.StickerEmptyView;

/** exteraGram 13.1.1: empty feed state (no posts / empty category / everything hidden from All). */
public final class FeedEmptyState {

    private FeedEmptyState() {
    }

    public static void bind(BaseFragment fragment, StickerEmptyView emptyView) {
        int currentAccount = fragment.getCurrentAccount();
        FeedController feedController = FeedController.getInstance(currentAccount);
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        emptyView.subtitle.setVisibility(View.GONE);
        emptyView.button.setVisibility(View.GONE);
        if (!feedController.hasChannels() || feedController.hasIncludedChannels()) {
            emptyView.title.setText(LocaleController.getString(R.string.FeedNoPosts));
            return;
        }
        FeedConfig.Category category = feedConfig.getSelectedCategory();
        if (category != null) {
            emptyView.title.setText(LocaleController.getString(R.string.FeedCategoryEmpty));
            emptyView.subtitle.setText(LocaleController.getString(R.string.FeedCategoryEmptyInfo));
            emptyView.subtitle.setVisibility(View.VISIBLE);
            setButton(emptyView, LocaleController.getString(R.string.FeedCategoryAddChannels), () -> fragment.presentFragment(new FeedCategoryEditActivity(category.getId(), false)));
            return;
        }
        emptyView.title.setText(LocaleController.getString(R.string.FeedAllChannelsExcluded));
        if (!feedConfig.getVisibleCategories().isEmpty()) {
            emptyView.subtitle.setText(LocaleController.getString(R.string.FeedAllChannelsExcludedInfo));
            emptyView.subtitle.setVisibility(View.VISIBLE);
        }
        setButton(emptyView, LocaleController.getString(R.string.FeedSettings), () -> fragment.presentFragment(new FeedChannelsActivity()));
    }

    private static void setButton(StickerEmptyView emptyView, CharSequence text, Runnable action) {
        emptyView.button.setText(text, false);
        emptyView.button.setOnClickListener(v -> action.run());
        emptyView.button.setVisibility(View.VISIBLE);
    }
}
