package com.exteragram.messenger.components.supporter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.utils.ui.UIUtil;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

/**
 * exteraGram 13.1.1 SupporterSheet: the base of the supporter sheets (3D arrow header, title, subtitle,
 * free list of views, sticky bottom buttons). Fading action bar: scrolled up it becomes a full-screen
 * page with a back arrow and the title. With an {@link SupporterEnterFrom} the tapped badge flies into the header.
 */
public abstract class SupporterSheet extends BottomSheetWithRecyclerListView {

    private UniversalAdapter adapter;
    protected final LinearLayout bottomContainer;
    protected final ButtonWithCounterView button;
    private ValueAnimator enterAnimator;
    private SupporterEnterFrom enterFrom;
    private float enterProgress = 1.0f;
    protected final BaseFragment fragment;
    private final SupporterArrowHeader header;
    private final int[] location = new int[2];
    private LinearLayout secondaryRow;
    private TextView subtitleView;
    private CharSequence title;
    private TextView titleView;
    private boolean touched;
    private final ArrayList<View> views = new ArrayList<>();

    public SupporterSheet(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        this(fragment, false, resourcesProvider);
    }

    public SupporterSheet(BaseFragment fragment, boolean needFocus, Theme.ResourcesProvider resourcesProvider) {
        super(fragment.getParentActivity(), fragment, needFocus, false, false, true, ActionBarType.FADING, resourcesProvider);
        this.fragment = fragment;
        currentAccount = fragment.getCurrentAccount();
        Activity context = fragment.getParentActivity();
        occupyNavigationBar = !needFocus;
        smoothKeyboardAnimationEnabled = needFocus;
        smoothKeyboardByBottom = needFocus;
        setApplyBottomPadding(false);
        topPadding = 0.2f;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));
        recyclerListView.setClipToPadding(false);
        recyclerListView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);
        recyclerListView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touched = true;
                }
                return false;
            }
        });
        recyclerListView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // keep the header in view while the content grows, until the user scrolls
            if (!touched && recyclerListView.canScrollVertically(-1)) {
                layoutManager.scrollToPositionWithOffset(0, 0);
            }
        });
        header = new SupporterArrowHeader(context, resourcesProvider);
        button = new ButtonWithCounterView(context, true, resourcesProvider);
        button.setRound();
        bottomContainer = new LinearLayout(context);
        bottomContainer.setOrientation(LinearLayout.VERTICAL);
        bottomContainer.setBackground(createBottomBackdrop(getThemedColor(Theme.key_dialogBackground)));
        bottomContainer.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, needFocus ? 0 : AndroidUtilities.navigationBarHeight);
        bottomContainer.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 12, 16, 12, 12));
        bottomContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int height = b - t;
            if (recyclerListView.getPaddingBottom() != height) {
                recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, height);
            }
        });
        containerView.addView(bottomContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
    }

    private static Drawable createBottomBackdrop(int color) {
        LayerDrawable drawable = new LayerDrawable(new Drawable[]{UIUtil.createBottomFade(color), new ColorDrawable(color)});
        drawable.setLayerGravity(0, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        drawable.setLayerHeight(0, AndroidUtilities.dp(16));
        drawable.setLayerInsetTop(1, AndroidUtilities.dp(16));
        return drawable;
    }

    public void setContent(CharSequence title, CharSequence subtitle) {
        this.title = title;
        updateTitle();
        views.clear();
        views.add(header);
        Context context = getContext();
        titleView = new TextView(context);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setText(Emoji.replaceEmoji(title, titleView.getPaint().getFontMetricsInt(), false));
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setPadding(AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22), 0);
        views.add(titleView);
        if (subtitle == null) {
            subtitleView = null;
            return;
        }
        LinkSpanDrawable.LinksTextView subtitleTextView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        subtitleTextView.setLinkTextColor(getThemedColor(Theme.key_chat_messageLinkIn));
        subtitleTextView.setText(subtitle);
        subtitleTextView.setTypeface(AndroidUtilities.regular());
        subtitleTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitleTextView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(6), AndroidUtilities.dp(22), AndroidUtilities.dp(14));
        subtitleView = subtitleTextView;
        views.add(subtitleView);
    }

    public void addView(View view) {
        views.add(view);
    }

    public void addHeaderAccessory(View view) {
        views.add(views.indexOf(header) + 1, view);
    }

    /** A gradient «Badge #N» pill under the header (custom badges, where the arrow is replaced by the emoji). */
    public void addNumberPill(Integer number) {
        if (number == null) {
            return;
        }
        SupporterArrowView arrowView = header.arrowView;
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{arrowView.color1, arrowView.color2});
        background.setCornerRadius(AndroidUtilities.dp(12));
        TextView pill = new TextView(getContext());
        pill.setBackground(background);
        pill.setTypeface(AndroidUtilities.bold());
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pill.setTextColor(SupporterBadgeHint.resolveTextColor(ColorUtils.blendARGB(arrowView.color1, arrowView.color2, 0.5f)));
        pill.setText(LocaleController.formatString(R.string.SupporterBadgeNameCustom, BadgesController.formatNumber(number)));
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        FrameLayout frame = new FrameLayout(getContext());
        frame.addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 24, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
        addHeaderAccessory(frame);
    }

    /** The badge caption in a message bubble under the title. */
    public TextView addCaptionBubble(CharSequence caption) {
        LinkSpanDrawable.LinksTextView textView = new LinkSpanDrawable.LinksTextView(getContext(), resourcesProvider);
        textView.setLinkTextColor(getThemedColor(Theme.key_chat_messageLinkIn));
        textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setTypeface(AndroidUtilities.regular());
        textView.setText(caption);
        MessageDrawable bubble = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, false, resourcesProvider);
        bubble.shadow = false;
        bubble.overrideColor = getThemedColor(Theme.key_windowBackgroundGray);
        textView.setBackground(bubble);
        textView.setPadding(AndroidUtilities.dp(19), AndroidUtilities.dp(9), AndroidUtilities.dp(15), AndroidUtilities.dp(8));
        FrameLayout frame = new FrameLayout(getContext());
        frame.addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 10, 32, 4));
        views.add(views.indexOf(titleView) + 1, frame);
        return textView;
    }

    public SupporterArrowView getArrowView() {
        return header.arrowView;
    }

    public void addHeader(CharSequence text) {
        TextView textView = new TextView(getContext());
        textView.setText(text);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        textView.setGravity(Gravity.CENTER_HORIZONTAL);
        boolean afterSubtitle = !views.isEmpty() && views.get(views.size() - 1) == subtitleView;
        textView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(afterSubtitle ? 4 : 20), AndroidUtilities.dp(22), AndroidUtilities.dp(6));
        views.add(textView);
    }

    /** A neutral button under the primary one; several secondary buttons share one row. */
    public ButtonWithCounterView addSecondaryButton(CharSequence text, View.OnClickListener listener) {
        ButtonWithCounterView secondary = new ButtonWithCounterView(getContext(), true, resourcesProvider).setNeutral();
        secondary.setRound();
        secondary.setText(text, false);
        secondary.setOnClickListener(listener);
        if (secondaryRow == null) {
            ((LinearLayout.LayoutParams) button.getLayoutParams()).bottomMargin = AndroidUtilities.dp(8);
            secondaryRow = new LinearLayout(getContext());
            secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomContainer.addView(secondaryRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 12, 0, 12, 12));
        }
        secondaryRow.addView(secondary, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, secondaryRow.getChildCount() > 0 ? 8 : 0, 0, 0, 0));
        return secondary;
    }

    /** Replaces the 3D arrow with a custom-emoji badge. */
    public void setBadge(long documentId) {
        header.arrowView.setBadge(currentAccount, documentId);
    }

    public void commit() {
        if (adapter != null) {
            adapter.update(false);
        }
        layoutManager.scrollToPositionWithOffset(0, 0);
    }

    @Override
    protected CharSequence getTitle() {
        return title != null ? title : "";
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(listView, getContext(), currentAccount, 0, true, this::fillItems, resourcesProvider);
        return adapter;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (views == null) {
            return;
        }
        for (View view : views) {
            UItem item = UItem.asCustom(view);
            item.transparent = true;
            items.add(item);
        }
        items.add(UItem.asSpace(AndroidUtilities.dp(8)));
    }

    public void setEnterFrom(SupporterEnterFrom enterFrom) {
        this.enterFrom = enterFrom;
    }

    @Override
    protected boolean onCustomOpenAnimation() {
        SupporterEnterFrom from = enterFrom;
        if (from == null) {
            return false;
        }
        enterProgress = 0.0f;
        header.arrowView.setIconHidden(true);
        from.drawable.setAlpha(0);
        from.invalidate();
        if (!header.arrowView.hasBadge()) {
            header.arrowView.iconView.startEnterAnimation(-360, 100L);
        }
        enterAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        enterAnimator.addUpdateListener(a -> {
            enterProgress = (float) a.getAnimatedValue();
            container.invalidate();
        });
        enterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                enterProgress = 1.0f;
                header.arrowView.setIconHidden(false);
                container.postOnAnimation(() -> {
                    enterFrom = null;
                    container.invalidate();
                });
                ValueAnimator restore = ValueAnimator.ofInt(0, 255);
                restore.addUpdateListener(a -> {
                    from.drawable.setAlpha((int) a.getAnimatedValue());
                    from.invalidate();
                });
                restore.start();
            }
        });
        enterAnimator.setDuration(600L);
        enterAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        enterAnimator.start();
        return super.onCustomOpenAnimation();
    }

    @Override
    protected void mainContainerDispatchDraw(Canvas canvas) {
        super.mainContainerDispatchDraw(canvas);
        SupporterEnterFrom from = enterFrom;
        if (from == null) {
            return;
        }
        View iconView = header.arrowView.getIconView();
        container.getLocationOnScreen(location);
        int containerX = location[0];
        int containerY = location[1];
        float fromX = from.screenX - containerX;
        float fromY = from.screenY - containerY;
        iconView.getLocationOnScreen(location);
        float toX = (location[0] - containerX) + (iconView.getWidth() / 2.0f);
        float toY = ((location[1] - containerY) + (iconView.getHeight() / 2.0f)) - containerView.getTranslationY();
        Drawable drawable = from.drawable;
        if (drawable instanceof AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable && ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) drawable).getDrawable() != null) {
            drawable = ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) drawable).getDrawable();
        }
        ColorFilter colorFilter = drawable != from.drawable ? from.drawable.getColorFilter() : null;
        float fromSize = from.scale * drawable.getIntrinsicWidth();
        float toSize = iconView.getHeight() * (header.arrowView.hasBadge() ? 1.0f : 0.8f);
        if (fromSize <= 0 || toSize <= 0) {
            return;
        }
        float x = AndroidUtilities.lerp(fromX, toX, CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(enterProgress));
        float y = AndroidUtilities.lerp(fromY, toY, enterProgress);

        canvas.save();
        float scale = AndroidUtilities.lerp(from.scale, (toSize / fromSize) * from.scale, enterProgress);
        canvas.scale(scale, scale, x, y);
        int cx = (int) x;
        int cy = (int) y;
        drawable.setBounds(cx - drawable.getIntrinsicWidth() / 2, cy - drawable.getIntrinsicHeight() / 2, cx + drawable.getIntrinsicWidth() / 2, cy + drawable.getIntrinsicHeight() / 2);
        drawable.setAlpha((int) ((1.0f - Utilities.clamp(enterProgress, 1.0f, 0.0f)) * 255.0f));
        if (colorFilter != null) {
            drawable.setColorFilter(colorFilter);
        }
        drawable.draw(canvas);
        if (colorFilter != null) {
            drawable.setColorFilter(null);
        }
        drawable.setAlpha(drawable == from.drawable ? 0 : 255);
        canvas.restore();

        canvas.save();
        float iconScale = AndroidUtilities.lerp(fromSize / toSize, 1.0f, enterProgress);
        canvas.scale(iconScale, iconScale, x, y);
        canvas.translate(x - iconView.getWidth() / 2.0f, y - iconView.getHeight() / 2.0f);
        iconView.draw(canvas);
        canvas.restore();
    }

    @Override
    public void dismiss() {
        if (enterAnimator != null) {
            enterAnimator.cancel();
        }
        super.dismiss();
    }

    public static class SupporterArrowHeader extends FrameLayout {

        final SupporterArrowView arrowView;

        public SupporterArrowHeader(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            setClipChildren(false);
            arrowView = new SupporterArrowView(context, resourcesProvider);
            addView(arrowView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 190, Gravity.TOP, 0, 10, 0, 0));
        }
    }
}
