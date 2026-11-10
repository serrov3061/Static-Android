package com.exteragram.messenger.plugins.catalog.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.catalog.PluginCatalogController;
import com.exteragram.messenger.plugins.catalog.db.PluginRepositoryEntity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.Objects;

/**
 * A repository row (exteraGram 13.1.1 catalog/ui/CatalogRepositoryCell). exteraGram shows the channel avatar
 * and badge; StaticGram repositories are indexes, so the built-in catalog gets the plugins icon and the
 * trusted badge, other repositories a letter avatar.
 */
@SuppressLint("ViewConstructor")
public final class CatalogRepositoryCell extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final Theme.ResourcesProvider resourcesProvider;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final BackupImageView avatarView;
    private final ImageView iconView;
    private final SimpleTextView titleView;
    private final SimpleTextView subtitleView;
    private final int currentAccount;
    private PluginRepositoryEntity currentRepository;
    private boolean needDivider;

    public CatalogRepositoryCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.currentAccount = currentAccount;
        avatarView = new BackupImageView(context);
        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconView.setImageResource(R.drawable.plugins_filled);
        iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider), PorterDuff.Mode.SRC_IN));
        int p = AndroidUtilities.dp(10.0f);
        iconView.setPadding(p, p, p, p);
        iconView.setVisibility(GONE);

        titleView = new SimpleTextView(context);
        titleView.setTextSize(16);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setDrawablePadding(AndroidUtilities.dp(4.0f));

        subtitleView = new SimpleTextView(context);
        subtitleView.setTextSize(14);
        subtitleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));

        setWillNotDraw(false);
        boolean rtl = LocaleController.isRTL;
        int gravity = (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP;
        addView(avatarView, LayoutHelper.createFrame(44, 44, gravity, rtl ? 0 : 12, 12, rtl ? 12 : 0, 0));
        addView(iconView, LayoutHelper.createFrame(44, 44, gravity, rtl ? 0 : 12, 12, rtl ? 12 : 0, 0));
        addView(titleView, LayoutHelper.createFrame(-1, 20, gravity, rtl ? 12 : 68, 12, rtl ? 68 : 12, 0));
        addView(subtitleView, LayoutHelper.createFrame(-1, 20, gravity, rtl ? 12 : 68, 36, rtl ? 68 : 12, 0));
    }

    /** exteraGram's trusted-source badge: a green scalloped seal with a puzzle piece (a local drawable, no custom emoji). */
    public static Drawable createTrustedBadge(Theme.ResourcesProvider resourcesProvider) {
        Context context = ApplicationLoader.applicationContext;
        Drawable seal = context.getResources().getDrawable(R.drawable.verified_area).mutate();
        seal.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        Drawable puzzle = context.getResources().getDrawable(R.drawable.plugins_filled).mutate();
        puzzle.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider), PorterDuff.Mode.SRC_IN));
        CombinedDrawable badge = new CombinedDrawable(seal, puzzle);
        badge.setCustomSize(AndroidUtilities.dp(20.0f), AndroidUtilities.dp(20.0f));
        badge.setIconSize(AndroidUtilities.dp(12.0f), AndroidUtilities.dp(12.0f));
        badge.setCenter(true);
        return badge;
    }

    public static String getTitle(PluginRepositoryEntity repository) {
        if (repository.getTitle() != null) {
            return repository.getTitle();
        }
        if (PluginCatalogController.isTrustedRepository(repository.getRepositoryId())) {
            return LocaleController.getString(R.string.PluginCatalogStaticGram);
        }
        String url = repository.getUrl();
        return url != null ? (url.startsWith("https://") ? url.substring(8) : url) : "";
    }

    public void set(PluginRepositoryEntity repository, CharSequence subtitle, boolean divider) {
        String title = getTitle(repository);
        boolean builtIn = PluginCatalogController.isTrustedRepository(repository.getRepositoryId());
        currentRepository = repository;
        bindAvatar(repository, title, builtIn);
        String channel = repository.getUsername();
        if (!TextUtils.isEmpty(channel) && PluginCatalogController.getInstance(currentAccount).getCachedChannel(channel) == null) {
            PluginCatalogController.getInstance(currentAccount).resolveChannel(channel, chat -> {
                if (chat != null && currentRepository == repository) {
                    bindAvatar(repository, title, builtIn);
                }
            });
        }
        titleView.setText(Emoji.replaceEmoji(title, titleView.getPaint().getFontMetricsInt(), false));
        titleView.setRightDrawable(builtIn ? createTrustedBadge(resourcesProvider) : null);
        subtitleView.setText(subtitle);
        needDivider = divider;
        invalidate();
    }

    /** The repository channel's avatar when it is resolved; else the plugins icon (built-in) or a letter avatar. */
    private void bindAvatar(PluginRepositoryEntity repository, String title, boolean builtIn) {
        int radius = ExteraConfig.getAvatarCorners(44.0f);
        String channel = repository.getUsername();
        TLRPC.Chat chat = TextUtils.isEmpty(channel) ? null : PluginCatalogController.getInstance(currentAccount).getCachedChannel(channel);
        if (chat != null) {
            iconView.setVisibility(GONE);
            avatarView.setVisibility(VISIBLE);
            avatarView.setRoundRadius(radius);
            avatarDrawable.setInfo(currentAccount, chat);
            avatarView.setForUserOrChat(chat, avatarDrawable);
        } else if (builtIn) {
            avatarView.setVisibility(GONE);
            iconView.setVisibility(VISIBLE);
            iconView.setBackground(Theme.createRoundRectDrawable(radius, Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
        } else {
            iconView.setVisibility(GONE);
            avatarView.setVisibility(VISIBLE);
            avatarView.setRoundRadius(radius);
            avatarDrawable.setInfo(repository.getRepositoryId(), title, null);
            avatarView.setImageDrawable(avatarDrawable);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(68.0f) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded) {
            titleView.invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(68.0f), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(68.0f) : 0), getMeasuredHeight() - 1, Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider));
        }
    }

    public static final class Factory extends UItem.UItemFactory<CatalogRepositoryCell> {

        static {
            setup(new Factory());
        }

        public static UItem of(PluginRepositoryEntity repository, CharSequence subtitle) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = Long.hashCode(repository.getRepositoryId());
            item.object = repository;
            item.subtext = subtitle;
            return item;
        }

        @Override
        public CatalogRepositoryCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new CatalogRepositoryCell(context, currentAccount, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof CatalogRepositoryCell && item.object instanceof PluginRepositoryEntity) {
                ((CatalogRepositoryCell) view).set((PluginRepositoryEntity) item.object, item.subtext != null ? item.subtext : "", divider);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return Objects.equals(a.object, b.object) && Objects.equals(a.subtext != null ? a.subtext.toString() : null, b.subtext != null ? b.subtext.toString() : null);
        }
    }
}
