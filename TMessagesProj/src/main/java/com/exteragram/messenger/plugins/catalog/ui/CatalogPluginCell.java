package com.exteragram.messenger.plugins.catalog.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.catalog.CatalogPlugin;
import com.exteragram.messenger.plugins.catalog.PluginCategory;
import com.exteragram.messenger.plugins.catalog.db.PluginMetadataEntity;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A plugin of the catalog (exteraGram 13.1.1 catalog/ui/CatalogPluginCell): icon from a sticker set
 * ("setShortName/index"), title with version and NEW badges, description, author and category chips,
 * a delete button for installed plugins and a progress while the file downloads.
 */
@SuppressLint("ViewConstructor")
public final class CatalogPluginCell extends FrameLayout {

    public static final int STATE_GET = 0;
    public static final int STATE_UPDATE = 1;
    public static final int STATE_INSTALLED = 2;
    public static final float ICON_SIZE = 44.0f;
    public static final float PADDING = 12.0f;
    public static final float TEXT_START = 68.0f;
    private static final float AUTHOR_HEIGHT = 16.0f;
    private static final float BADGE_GAP = 4.0f;
    private static final float DESCRIPTION_LINE_HEIGHT = 18.5f;
    private static final float TITLE_BADGE_GAP = 8.0f;
    private static final float TITLE_HEIGHT = 20.0f;
    private static final Pattern ICON_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*/\\d+$");

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final BackupImageView iconView;
    private final ImageView placeholderView;
    private final TextView titleView;
    private final TextView versionView;
    private final TextView newView;
    private final TitleRow titleRow;
    private final LinksTextView descriptionView;
    private final LinksTextView authorView;
    private final CatalogCategoryTagsView tagsView;
    private final LinearLayout textColumn;
    private final ImageView deleteButton;
    private final RadialProgressView progressView;
    private Integer boundEntryId;
    private int iconRadius;
    private boolean needDivider;

    public static float getIconRadius() {
        return Math.max(ExteraConfig.getSectionRadiusDp() - 12.0f, TITLE_BADGE_GAP);
    }

    public CatalogPluginCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;

        iconView = new BackupImageView(context);
        iconView.setClipToOutline(true);
        iconView.getImageReceiver().setAutoRepeat(1);

        placeholderView = new ImageView(context);
        placeholderView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        placeholderView.setImageResource(R.drawable.plugins_filled);
        placeholderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider), PorterDuff.Mode.SRC_IN));
        int p = AndroidUtilities.dp(10.0f);
        placeholderView.setPadding(p, p, p, p);

        titleView = new TextView(context);
        titleView.setTextSize(1, AUTHOR_HEIGHT);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setIncludeFontPadding(false);
        titleView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);

        versionView = createBadge(context);
        newView = createBadge(context);
        newView.setText(LocaleController.getString(R.string.PluginCatalogNew));
        newView.setVisibility(GONE);

        titleRow = new TitleRow(context);

        descriptionView = new LinksTextView(context);
        setup(descriptionView, 14.0f, Theme.key_windowBackgroundWhiteBlackText);
        descriptionView.setLineSpacing(AndroidUtilities.dp(DESCRIPTION_LINE_HEIGHT) - descriptionView.getPaint().getFontMetricsInt(null), 1.0f);
        descriptionView.setIncludeFontPadding(false);
        descriptionView.setMaxLines(2);
        descriptionView.setEllipsize(TextUtils.TruncateAt.END);

        authorView = new LinksTextView(context);
        setup(authorView, 13.0f, Theme.key_windowBackgroundWhiteGrayText);
        authorView.setSingleLine(true);
        authorView.setIncludeFontPadding(false);
        authorView.setGravity(authorView.getGravity() | Gravity.CENTER_VERTICAL);
        authorView.setEllipsize(TextUtils.TruncateAt.END);

        tagsView = new CatalogCategoryTagsView(context, resourcesProvider);

        textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.addView(titleRow, LayoutHelper.createLinear(-1, -2));
        textColumn.addView(descriptionView, LayoutHelper.createLinear(-1, -2, 0, BADGE_GAP, 0, 0));
        textColumn.addView(authorView, LayoutHelper.createLinear(-1, 16, 0, 7, 0, 0));
        textColumn.addView(tagsView, LayoutHelper.createLinear(-1, 18, 0, 9, 0, 0));

        deleteButton = new ImageView(context);
        deleteButton.setScaleType(ImageView.ScaleType.CENTER);
        deleteButton.setImageResource(R.drawable.msg_delete);
        int red = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
        deleteButton.setColorFilter(new PorterDuffColorFilter(red, PorterDuff.Mode.SRC_IN));
        deleteButton.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(red, 0.12f), 1, AndroidUtilities.dp(TITLE_HEIGHT)));
        deleteButton.setContentDescription(LocaleController.getString(R.string.PluginDelete));
        deleteButton.setVisibility(GONE);

        progressView = new RadialProgressView(context, resourcesProvider);
        progressView.setSize(AndroidUtilities.dp(TITLE_HEIGHT));
        progressView.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
        progressView.setVisibility(GONE);

        setWillNotDraw(false);
        boolean rtl = LocaleController.isRTL;
        int gravity = (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP;
        addView(placeholderView, LayoutHelper.createFrame(44, 44, gravity, rtl ? 0 : 12, 12, rtl ? 12 : 0, 0));
        addView(iconView, LayoutHelper.createFrame(44, 44, gravity, rtl ? 0 : 12, 12, rtl ? 12 : 0, 0));
        addView(textColumn, LayoutHelper.createFrame(-1, -2, gravity, rtl ? 12 : 68, 12, rtl ? 68 : 12, 12));
        addView(deleteButton, LayoutHelper.createFrameRelatively(40, 40, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        addView(progressView, LayoutHelper.createFrameRelatively(40, 40, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
    }

    private TextView createBadge(Context context) {
        TextView view = new TextView(context);
        view.setTextSize(1, 12.0f);
        view.setTypeface(AndroidUtilities.bold());
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(AndroidUtilities.dp(6.0f), 0, AndroidUtilities.dp(6.0f), 0);
        return view;
    }

    private void setup(TextView view, float size, int colorKey) {
        view.setTextSize(1, size);
        view.setTextColor(Theme.getColor(colorKey, resourcesProvider));
        view.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        view.setTypeface(AndroidUtilities.regular());
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
    }

    public void set(CatalogPlugin plugin, int state, CharSequence versionLabel, boolean isNew, boolean loading, boolean detailsAvailable, View.OnClickListener onDelete, boolean divider) {
        updateIconRadius();
        PluginMetadataEntity metadata = plugin.getMetadata();
        if (metadata != null && metadata.getError() != null) {
            metadata = null;
        }
        String icon = metadata != null ? metadata.getIcon() : null;
        if (icon != null && ICON_PATTERN.matcher(icon).matches()) {
            loadIcon(icon);
        } else {
            showPlaceholder();
        }
        String title = metadata != null ? metadata.getName() : null;
        if (title == null) {
            String fileName = plugin.getFileName();
            int dot = fileName.lastIndexOf('.');
            title = dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        titleView.setText(title);

        versionView.setText(versionLabel);
        versionView.setVisibility(TextUtils.isEmpty(versionLabel) ? GONE : VISIBLE);
        int versionColor = Theme.getColor(state == STATE_UPDATE ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        versionView.setTextColor(versionColor);
        versionView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(TITLE_BADGE_GAP), Theme.multAlpha(versionColor, 0.12f)));
        newView.setVisibility(isNew ? VISIBLE : GONE);
        if (isNew) {
            newView.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
            newView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(TITLE_BADGE_GAP), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
        }

        CharSequence description;
        if (metadata != null) {
            String text = metadata.getDescription();
            description = LocaleUtils.fullyFormatText(text != null ? text : LocaleController.getString(R.string.PluginNoDescription));
        } else if (plugin.isFailed()) {
            description = LocaleController.getString(R.string.PluginCatalogInfoFailed);
        } else if (!detailsAvailable) {
            description = LocaleController.getString(R.string.PluginCatalogInfoUnavailable);
        } else {
            description = LocaleController.getString(R.string.PluginCatalogInfoLoading);
        }
        descriptionView.setText(description);

        List<PluginCategory> categories = plugin.getCategories();
        tagsView.setCategories(categories);
        tagsView.setVisibility(categories.isEmpty() ? GONE : VISIBLE);

        if (metadata != null) {
            String author = metadata.getAuthor();
            authorView.setText(LocaleUtils.formatWithUsernames(author != null ? author : LocaleController.getString(R.string.PluginNoAuthor)));
        } else {
            authorView.setText(AndroidUtilities.formatFileSize(plugin.getSize()));
        }

        boolean sameEntry = boundEntryId != null && boundEntryId == plugin.getEntryId();
        boundEntryId = plugin.getEntryId();
        boolean showDelete = !loading && state != STATE_GET && onDelete != null;
        AndroidUtilities.updateViewVisibilityAnimated(deleteButton, showDelete, 0.5f, true, sameEntry);
        deleteButton.setOnClickListener(onDelete);
        AndroidUtilities.updateViewVisibilityAnimated(progressView, loading, 0.5f, true, sameEntry);
        int margin = AndroidUtilities.dp(showDelete || loading ? 56.0f : 12.0f);
        LayoutParams lp = (LayoutParams) textColumn.getLayoutParams();
        if (LocaleController.isRTL) {
            if (lp.leftMargin != margin) {
                lp.leftMargin = margin;
                textColumn.requestLayout();
            }
        } else if (lp.rightMargin != margin) {
            lp.rightMargin = margin;
            textColumn.requestLayout();
        }
        needDivider = divider;
        invalidate();
    }

    private void updateIconRadius() {
        int radius = AndroidUtilities.dp(getIconRadius());
        if (iconRadius == radius) {
            return;
        }
        iconRadius = radius;
        iconView.setRoundRadius(radius);
        iconView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        iconView.invalidateOutline();
        placeholderView.setBackground(Theme.createRoundRectDrawable(radius, Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
    }

    private void loadIcon(String icon) {
        iconView.setVisibility(VISIBLE);
        placeholderView.setVisibility(GONE);
        int slash = icon.lastIndexOf('/');
        String setName = icon.substring(0, slash);
        int index;
        try {
            index = Integer.parseInt(icon.substring(slash + 1));
        } catch (NumberFormatException e) {
            showPlaceholder();
            return;
        }
        MediaDataController mediaDataController = MediaDataController.getInstance(currentAccount);
        mediaDataController.setPlaceholderImageByIndex(iconView, setName, index, "100_100", resourcesProvider);
        Object tag = iconView.getTag();
        TLRPC.TL_inputStickerSetShortName input = new TLRPC.TL_inputStickerSetShortName();
        input.short_name = setName;
        final int finalIndex = index;
        mediaDataController.getStickerSet(input, 0, false, set -> {
            if (Objects.equals(iconView.getTag(), tag)) {
                if (set == null || set.documents == null || finalIndex < 0 || finalIndex >= set.documents.size()) {
                    showPlaceholder();
                }
            }
        });
    }

    private void showPlaceholder() {
        iconView.setTag(null);
        iconView.setImageDrawable((Drawable) null);
        iconView.setVisibility(GONE);
        placeholderView.setVisibility(VISIBLE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        super.onMeasure(widthMeasureSpec, unspecified);
        if (alignDescription()) {
            super.onMeasure(widthMeasureSpec, unspecified);
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.max(getMeasuredHeight(), AndroidUtilities.dp(68.0f)) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    // centers the first description line on the icon when the cell is short
    private boolean alignDescription() {
        Layout layout = descriptionView.getLayout();
        if (layout == null || descriptionView.getVisibility() == GONE || layout.getLineCount() == 0) {
            return false;
        }
        int margin = Math.max(AndroidUtilities.dp(BADGE_GAP), AndroidUtilities.dp(ICON_SIZE) - titleRow.getMeasuredHeight() - (layout.getLineBaseline(0) + descriptionView.getPaint().getFontMetricsInt().descent));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) descriptionView.getLayoutParams();
        if (lp.topMargin == margin) {
            return false;
        }
        lp.topMargin = margin;
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(TEXT_START), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(TEXT_START) : 0), getMeasuredHeight() - 1, Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider));
        }
    }

    /** Links in the description/author stay clickable inside the clickable cell. */
    public final class LinksTextView extends LinkSpanDrawable.LinksTextView {
        public LinksTextView(Context context) {
            super(context, resourcesProvider);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN && hit((int) event.getX(), (int) event.getY()) != null) {
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
            }
            return super.onTouchEvent(event);
        }
    }

    /** Title followed by the version and NEW badges; the title shrinks first. */
    public final class TitleRow extends FrameLayout {
        private final TextView[] badges;

        public TitleRow(Context context) {
            super(context);
            badges = new TextView[]{versionView, newView};
            addView(titleView, new LayoutParams(-2, -2));
            addView(versionView, new LayoutParams(-2, -2));
            addView(newView, new LayoutParams(-2, -2));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int badgesWidth = 0;
            for (TextView badge : badges) {
                if (badge.getVisibility() != GONE) {
                    badge.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(TITLE_HEIGHT), MeasureSpec.EXACTLY));
                    badgesWidth += badge.getMeasuredWidth() + AndroidUtilities.dp(badgesWidth == 0 ? TITLE_BADGE_GAP : BADGE_GAP);
                }
            }
            titleView.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - badgesWidth), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(TITLE_HEIGHT), MeasureSpec.EXACTLY));
            setMeasuredDimension(width, AndroidUtilities.dp(TITLE_HEIGHT));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int height = bottom - top;
            int width = right - left;
            int titleTop = (height - titleView.getMeasuredHeight()) / 2;
            if (LocaleController.isRTL) {
                titleView.layout(width - titleView.getMeasuredWidth(), titleTop, width, titleTop + titleView.getMeasuredHeight());
            } else {
                titleView.layout(0, titleTop, titleView.getMeasuredWidth(), titleTop + titleView.getMeasuredHeight());
            }
            int x = titleView.getMeasuredWidth() + AndroidUtilities.dp(TITLE_BADGE_GAP);
            for (TextView badge : badges) {
                if (badge.getVisibility() != GONE) {
                    int badgeTop = (height - badge.getMeasuredHeight()) / 2;
                    if (LocaleController.isRTL) {
                        int end = width - x;
                        badge.layout(end - badge.getMeasuredWidth(), badgeTop, end, badgeTop + badge.getMeasuredHeight());
                    } else {
                        badge.layout(x, badgeTop, x + badge.getMeasuredWidth(), badgeTop + badge.getMeasuredHeight());
                    }
                    x += badge.getMeasuredWidth() + AndroidUtilities.dp(BADGE_GAP);
                }
            }
        }
    }

    public static final class Factory extends UItem.UItemFactory<CatalogPluginCell> {

        static {
            setup(new Factory());
        }

        public static UItem of(CatalogPlugin plugin, int state, CharSequence versionLabel, boolean isNew, boolean loading, boolean detailsAvailable, View.OnClickListener onDelete) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = Long.hashCode(plugin.getRepositoryId() * 31 + plugin.getEntryId());
            item.object = plugin;
            item.intValue = state;
            item.text = versionLabel;
            item.accent = isNew;
            item.checked = loading;
            item.locked = !detailsAvailable;
            item.clickCallback = onDelete;
            return item;
        }

        @Override
        public CatalogPluginCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new CatalogPluginCell(context, currentAccount, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof CatalogPluginCell && item.object instanceof CatalogPlugin) {
                ((CatalogPluginCell) view).set((CatalogPlugin) item.object, item.intValue, item.text, item.accent, item.checked, !item.locked, item.clickCallback, divider);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            if (!(a.object instanceof CatalogPlugin) || !(b.object instanceof CatalogPlugin)) {
                return false;
            }
            CatalogPlugin pa = (CatalogPlugin) a.object;
            CatalogPlugin pb = (CatalogPlugin) b.object;
            return pa.getDocumentId() == pb.getDocumentId() && Objects.equals(pa.getMetadata(), pb.getMetadata())
                    && a.intValue == b.intValue && TextUtils.equals(a.text, b.text) && a.checked == b.checked
                    && a.accent == b.accent && a.locked == b.locked;
        }
    }
}
