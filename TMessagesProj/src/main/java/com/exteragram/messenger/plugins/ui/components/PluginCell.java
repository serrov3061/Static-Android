package com.exteragram.messenger.plugins.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.utils.AppUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;
import kotlin.collections.ArraysKt;
import kotlin.jvm.internal.Intrinsics;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EffectsTextView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.LaunchActivity;

@SuppressLint({"ViewConstructor"})
public final class PluginCell extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {
    private final Switch checkBox;
    private boolean compactMode;
    private final ImageView deleteButton;
    private final EffectsTextView descriptionView;
    private final LinearLayout headerLayout;
    private final BackupImageView imageView;
    private final ImageView openInButton;
    private final ImageView pinButton;
    private Plugin plugin;
    private PluginCellDelegate pluginCellDelegate;
    private final TextView pluginNameView;
    private final PluginRequirementsView requirementsLayout;
    private final ImageView settingsButton;
    private final ImageView shareButton;
    private final EffectsTextView subtitleView;

    public PluginCell(Context context) {
        super(context);
        setClickable(false);
        setClipChildren(false);
        setClipToPadding(false);
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(1);
        addView(linearLayout, LayoutHelper.createFrame(-1, -2.0f, 119, 16.0f, 16.0f, 16.0f, 8.0f));
        LinearLayout linearLayout2 = new LinearLayout(context);
        linearLayout2.setOrientation(1);
        this.headerLayout = linearLayout2;
        linearLayout.addView(linearLayout2, LayoutHelper.createLinear(-1, -2));
        BackupImageView backupImageView = new BackupImageView(context);
        backupImageView.setVisibility(8);
        backupImageView.setOutlineProvider(createPluginIconOutlineProvider());
        backupImageView.setClipToOutline(true);
        backupImageView.getImageReceiver().setAutoRepeat(1);
        this.imageView = backupImageView;
        linearLayout2.addView(backupImageView, LayoutHelper.createLinear(56, 56, 51, 0.0f, 0.0f, 0.0f, 12.0f));
        LinearLayout linearLayout3 = new LinearLayout(context);
        linearLayout3.setOrientation(1);
        linearLayout2.addView(linearLayout3, LayoutHelper.createLinear(-1, -2));
        TextView textView = new TextView(context);
        textView.setGravity(3);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(1, 18.0f);
        TextUtils.TruncateAt truncateAt = TextUtils.TruncateAt.END;
        textView.setEllipsize(truncateAt);
        textView.setTypeface(AndroidUtilities.bold());
        this.pluginNameView = textView;
        linearLayout3.addView(textView, LayoutHelper.createLinear(-1, -2));
        EffectsTextView effectsTextView = new EffectsTextView(context);
        effectsTextView.setGravity(3);
        effectsTextView.setTypeface(AndroidUtilities.regular());
        effectsTextView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        int i = Theme.key_windowBackgroundWhiteLinkText;
        effectsTextView.setLinkTextColor(Theme.getColor(i));
        effectsTextView.setTextSize(1, 14.0f);
        effectsTextView.setEllipsize(truncateAt);
        effectsTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        this.subtitleView = effectsTextView;
        linearLayout3.addView(effectsTextView, LayoutHelper.createLinear(-1, -2, 0.0f, 2.0f, 0.0f, 0.0f));
        EffectsTextView effectsTextView2 = new EffectsTextView(context);
        effectsTextView2.setGravity(3);
        effectsTextView2.setTypeface(AndroidUtilities.regular());
        effectsTextView2.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        effectsTextView2.setLinkTextColor(Theme.getColor(i));
        this.descriptionView = effectsTextView2;
        linearLayout.addView(effectsTextView2, LayoutHelper.createLinear(-1, -2, 0, 0, 12, 0, 0));
        PluginRequirementsView pluginRequirementsView = new PluginRequirementsView(context, null);
        pluginRequirementsView.setVisibility(8);
        this.requirementsLayout = pluginRequirementsView;
        linearLayout.addView(pluginRequirementsView, LayoutHelper.createLinear(-1, -2, 0, 0, 12, 0, 0));
        View view = new View(context);
        view.setBackgroundColor(Theme.getDividerColor(null));
        linearLayout.addView(view, LayoutHelper.createLinear(-1, 1.0f / AndroidUtilities.density, 0, 0, 12, 0, 8));
        LinearLayout linearLayout4 = new LinearLayout(context);
        linearLayout4.setOrientation(0);
        ImageView imageViewCreateButton = createButton(context, R.drawable.msg_share, false, new View.OnClickListener() {
            @Override
            public final void onClick(View view2) {
                PluginCell.$r8$lambda$gPhyRoTYC0gInhhjd5k0po863gM(PluginCell.this, view2);
            }
        });
        this.shareButton = imageViewCreateButton;
        linearLayout4.addView(imageViewCreateButton, LayoutHelper.createFrame(40, 40.0f, 51, 0.0f, 0.0f, 8.0f, 0.0f));
        ImageView imageViewCreateButton2 = createButton(context, R.drawable.msg_openin, false, new View.OnClickListener() {
            @Override
            public final void onClick(View view2) {
                PluginCell.$r8$lambda$TkTsTkgHS2XoNteC2HxEG9EpXDk(PluginCell.this, view2);
            }
        });
        this.openInButton = imageViewCreateButton2;
        linearLayout4.addView(imageViewCreateButton2, LayoutHelper.createFrame(40, 40.0f, 51, 0.0f, 0.0f, 8.0f, 0.0f));
        ImageView imageViewCreateButton3 = createButton(context, R.drawable.msg_pin, false, new View.OnClickListener() {
            @Override
            public final void onClick(View view2) {
                PluginCell.m1606$r8$lambda$miXUx0RJdhvQyk3TfMBgG7YnBI(PluginCell.this, view2);
            }
        });
        this.pinButton = imageViewCreateButton3;
        linearLayout4.addView(imageViewCreateButton3, LayoutHelper.createFrame(40, 40.0f, 51, 0.0f, 0.0f, 8.0f, 0.0f));
        ImageView imageViewCreateButton4 = createButton(context, R.drawable.msg_settings, false, new View.OnClickListener() {
            @Override
            public final void onClick(View view2) {
                PluginCell.$r8$lambda$vFaf3kpNKKpdG19431rjWCjd68Y(PluginCell.this, view2);
            }
        });
        imageViewCreateButton4.setVisibility(8);
        this.settingsButton = imageViewCreateButton4;
        linearLayout4.addView(imageViewCreateButton4, LayoutHelper.createFrame(40, 40.0f, 51, 0.0f, 0.0f, 8.0f, 0.0f));
        ImageView imageViewCreateButton5 = createButton(context, R.drawable.msg_delete, true, new View.OnClickListener() {
            @Override
            public final void onClick(View view2) {
                PluginCell.m1607$r8$lambda$txZU19WGsUHbCEQUldGESo9Wwc(PluginCell.this, view2);
            }
        });
        this.deleteButton = imageViewCreateButton5;
        addView(imageViewCreateButton5, LayoutHelper.createFrame(40, 40.0f, 85, 0.0f, 0.0f, 16.0f, 8.0f));
        linearLayout.addView(linearLayout4, LayoutHelper.createFrame(-1, 40, 83));
        Switch r3 = new Switch(context);
        int i2 = Theme.key_switchTrack;
        int i3 = Theme.key_switchTrackChecked;
        int i4 = Theme.key_windowBackgroundWhite;
        r3.setColors(i2, i3, i4, i4);
        r3.setFocusable(false);
        this.checkBox = r3;
        addView(r3, LayoutHelper.createFrame(37, 40.0f, 53, 0.0f, 16.0f, 24.0f, 0.0f));
        setCompact(ExteraConfig.getPluginsCompactView());
    }

    public static void $r8$lambda$gPhyRoTYC0gInhhjd5k0po863gM(PluginCell pluginCell, View view) {
        PluginCellDelegate pluginCellDelegate = pluginCell.pluginCellDelegate;
        if (pluginCellDelegate != null) {
            pluginCellDelegate.sharePlugin();
        }
    }

    public static void $r8$lambda$TkTsTkgHS2XoNteC2HxEG9EpXDk(PluginCell pluginCell, View view) {
        PluginCellDelegate pluginCellDelegate = pluginCell.pluginCellDelegate;
        if (pluginCellDelegate != null) {
            pluginCellDelegate.openInExternalApp();
        }
    }

    public static void m1606$r8$lambda$miXUx0RJdhvQyk3TfMBgG7YnBI(PluginCell pluginCell, View view) {
        PluginCellDelegate pluginCellDelegate = pluginCell.pluginCellDelegate;
        if (pluginCellDelegate != null) {
            pluginCellDelegate.pinPlugin(pluginCell);
        }
    }

    public static void $r8$lambda$vFaf3kpNKKpdG19431rjWCjd68Y(PluginCell pluginCell, View view) {
        PluginCellDelegate pluginCellDelegate = pluginCell.pluginCellDelegate;
        if (pluginCellDelegate != null) {
            pluginCellDelegate.openPluginSettings();
        }
    }

    public static void m1607$r8$lambda$txZU19WGsUHbCEQUldGESo9Wwc(PluginCell pluginCell, View view) {
        PluginCellDelegate pluginCellDelegate = pluginCell.pluginCellDelegate;
        if (pluginCellDelegate != null) {
            pluginCellDelegate.deletePlugin();
        }
    }

    public final void setCompact(boolean compact) {
        if (this.compactMode == compact) {
            return;
        }
        this.compactMode = compact;
        updateLayout();
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec), TLObject.FLAG_30), heightMeasureSpec);
    }

    private final void updateLayout() {
        Plugin plugin = this.plugin;
        if (plugin == null) {
            return;
        }
        LinearLayout linearLayout = (LinearLayout) this.headerLayout.getChildAt(1);
        this.headerLayout.setOrientation(!this.compactMode ? 1 : 0);
        LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) this.imageView.getLayoutParams();
        layoutParams.rightMargin = this.compactMode ? AndroidUtilities.dp(16.0f) : 0;
        layoutParams.bottomMargin = this.compactMode ? 0 : AndroidUtilities.dp(12.0f);
        layoutParams.width = this.compactMode ? AndroidUtilities.dp(49.0f) : AndroidUtilities.dp(56.0f);
        layoutParams.height = this.compactMode ? AndroidUtilities.dp(49.0f) : AndroidUtilities.dp(56.0f);
        LinearLayout.LayoutParams layoutParams2 = (LinearLayout.LayoutParams) linearLayout.getLayoutParams();
        boolean z = this.compactMode;
        layoutParams2.gravity = z ? 16 : 3;
        this.pluginNameView.setSingleLine(z);
        this.subtitleView.setSingleLine(this.compactMode);
        final int iDp = ((!this.compactMode || plugin.hasError()) && (this.compactMode || (plugin.getPack() != null && plugin.getIndex() >= 0) || plugin.hasError())) ? 0 : AndroidUtilities.dp(61.0f);
        this.pluginNameView.setPadding(0, 0, iDp, 0);
        this.pluginNameView.post(new Runnable() {
            @Override
            public final void run() {
                PluginCell.$r8$lambda$y2vqhelF7XUBHwvSinOwdrg10bo(PluginCell.this, iDp);
            }
        });
        requestLayout();
    }

    public static void $r8$lambda$y2vqhelF7XUBHwvSinOwdrg10bo(PluginCell pluginCell, int i) {
        EffectsTextView effectsTextView = pluginCell.subtitleView;
        if (pluginCell.pluginNameView.getLineCount() > 1) {
            i = 0;
        }
        effectsTextView.setPadding(0, 0, i, 0);
    }

    /**
     * exteraGram plugins: shows sticker #index of the sticker set {@code shortName} as a plugin icon
     * (exteraGram keeps this in MediaDataController).
     */
    public static void setPlaceholderImageByIndex(org.telegram.ui.Components.BackupImageView imageView, String shortName, int index, String filter, Theme.ResourcesProvider resourcesProvider) {
        org.telegram.tgnet.TLRPC.TL_inputStickerSetShortName inputStickerSet = new org.telegram.tgnet.TLRPC.TL_inputStickerSetShortName();
        inputStickerSet.short_name = shortName;
        String tag = "sticker_" + shortName + "_" + index;
        imageView.setTag(tag);
        imageView.setImageDrawable(null);
        imageView.setColorFilter(null);
        MediaDataController.getInstance(UserConfig.selectedAccount).getStickerSet(inputStickerSet, 0, false, stickerSet -> {
            if (!tag.equals(imageView.getTag())) {
                return;
            }
            if (stickerSet == null || stickerSet.documents == null || index < 0 || index >= stickerSet.documents.size()) {
                imageView.setImageDrawable(null);
                return;
            }
            org.telegram.tgnet.TLRPC.Document document = stickerSet.documents.get(index);
            if (document == null) {
                imageView.setImageDrawable(null);
                return;
            }
            android.graphics.drawable.Drawable svgThumb = org.telegram.messenger.DocumentObject.getSvgThumb(document, Theme.key_windowBackgroundWhiteGrayIcon, 0.2f, 1.0f, null);
            imageView.setColorFilter(org.telegram.messenger.MessageObject.isTextColorEmoji(document) ? Theme.getAnimatedEmojiColorFilter(resourcesProvider) : null);
            imageView.setImage(org.telegram.messenger.ImageLocation.getForDocument(document), filter, svgThumb, 0, document);
            imageView.invalidate();
        });
    }

    public final void set(Plugin plugin, final PluginCellDelegate pluginCellDelegate) {
        String str;
        if (plugin == null || pluginCellDelegate == null) {
            return;
        }
        this.pluginCellDelegate = pluginCellDelegate;
        this.plugin = plugin;
        updatePluginIconOutlineProvider();
        PluginsController.Companion companion = PluginsController.Companion;
        setPinned(companion.isPluginPinned(plugin.getId()));
        this.openInButton.setVisibility(pluginCellDelegate.canOpenInExternalApp() ? 0 : 8);
        boolean z = plugin.getPack() != null && plugin.getIndex() >= 0;
        this.imageView.setVisibility(z ? 0 : 8);
        if (z) {
            setPlaceholderImageByIndex(this.imageView, plugin.getPack(), plugin.getIndex(), "100_100", null);
        } else {
            this.imageView.setImageDrawable(null);
        }
        this.pluginNameView.setText(plugin.getName());
        EffectsTextView effectsTextView = this.subtitleView;
        if (this.compactMode) {
            str = "v";
        } else {
            str = LocaleController.getString(R.string.PluginVersion) + " ";
        }
        effectsTextView.setText(new SpannableStringBuilder(str).append((CharSequence) plugin.getVersion()).append((CharSequence) " • ").append(LocaleUtils.formatWithUsernames(plugin.getAuthor())));
        if (plugin.isNotResponding()) {
            bindNotRespondingState();
        } else if (plugin.hasError()) {
            bindErrorState();
        } else {
            bindNormalState();
        }
        this.requirementsLayout.setRequirements(plugin.getRequirements());
        updateLayout();
        this.checkBox.setChecked(plugin.isEnabled(), false);
        this.checkBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                pluginCellDelegate.togglePlugin(PluginCell.this);
            }
        });
        AndroidUtilities.updateViewVisibilityAnimated(this.settingsButton, plugin.isEnabled() && companion.getInstance().hasPluginSettings(plugin.getId()), 0.5f, true, false);
    }

    private final void bindNotRespondingState() {
        this.descriptionView.setText(LocaleController.getString(R.string.PluginIsNotResponding));
        this.descriptionView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        this.descriptionView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        this.descriptionView.setTextSize(1, 12.0f);
        this.descriptionView.setOnClickListener(null);
        this.checkBox.setVisibility(8);
        updateDeleteButton();
    }

    private final void updateDeleteButton() {
        int color;
        Plugin plugin = this.plugin;
        if (plugin == null) {
            return;
        }
        boolean isNotResponding = plugin.isNotResponding();
        this.deleteButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(!isNotResponding ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        ImageView imageView = this.deleteButton;
        if (!isNotResponding) {
            color = Theme.multAlpha(Theme.getColor(Theme.key_text_RedRegular), 0.12f);
        } else {
            color = Theme.getColor(Theme.key_dialogButtonSelector);
        }
        imageView.setBackground(Theme.createSelectorDrawable(color, 1, AndroidUtilities.dp(20.0f)));
        this.deleteButton.setImageResource(!isNotResponding ? R.drawable.msg_delete : R.drawable.ic_ab_other);
    }

    private final void bindErrorState() {
        final Plugin plugin = this.plugin;
        if (plugin == null) {
            return;
        }
        EffectsTextView effectsTextView = this.descriptionView;
        Throwable error = plugin.getError();
        effectsTextView.setText(error != null ? error.getLocalizedMessage() : null);
        this.descriptionView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        this.descriptionView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        this.descriptionView.setTextSize(1, 12.0f);
        this.descriptionView.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                PluginCell.$r8$lambda$NpWdx7yMfEnXZdYKD0YE3NuqgTE(plugin, view);
            }
        });
        this.checkBox.setVisibility(8);
        updateDeleteButton();
    }

    public static void $r8$lambda$NpWdx7yMfEnXZdYKD0YE3NuqgTE(Plugin plugin, View view) {
        if (AndroidUtilities.addToClipboard(AppUtils.stackTraceToString(plugin.getError()))) {
            BulletinFactory.of(LaunchActivity.getSafeLastFragment()).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
    }

    private final void bindNormalState() {
        Plugin plugin = this.plugin;
        if (plugin == null) {
            return;
        }
        final Throwable settingsError = plugin.isEnabled() ? plugin.getSettingsError() : null;
        if (settingsError != null) {
            // sg: create_settings() raised - show it like other plugin errors (tap copies the trace)
            this.descriptionView.setText("create_settings: " + settingsError.getLocalizedMessage());
            this.descriptionView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            this.descriptionView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
            this.descriptionView.setTextSize(1, 12.0f);
            this.descriptionView.setOnClickListener(v -> {
                if (AndroidUtilities.addToClipboard(AppUtils.stackTraceToString(settingsError))) {
                    BulletinFactory.of(LaunchActivity.getSafeLastFragment()).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                }
            });
        } else {
            this.descriptionView.setText(LocaleUtils.fullyFormatText(plugin.getDescription()));
            this.descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            this.descriptionView.setTypeface(AndroidUtilities.regular());
            this.descriptionView.setTextSize(1, 15.0f);
            this.descriptionView.setOnClickListener(null);
        }
        this.checkBox.setVisibility(0);
        updateDeleteButton();
    }

    public final void setChecked(boolean checked, boolean animated) {
        this.checkBox.setChecked(checked, animated);
    }

    public final void setPinned(boolean pinned) {
        this.pinButton.setImageResource(pinned ? R.drawable.msg_unpin : R.drawable.msg_pin);
    }

    private final ImageView createButton(Context context, int iconResId, boolean isRed, View.OnClickListener onClickListener) {
        int color;
        ImageView imageView = new ImageView(context);
        applyClickAnimation(imageView);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setImageResource(iconResId);
        imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(isRed ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        if (isRed) {
            color = Theme.multAlpha(Theme.getColor(Theme.key_text_RedRegular), 0.12f);
        } else {
            color = Theme.getColor(Theme.key_dialogButtonSelector);
        }
        imageView.setBackground(Theme.createSelectorDrawable(color, 1, AndroidUtilities.dp(20.0f)));
        imageView.setOnClickListener(onClickListener);
        return imageView;
    }

    private final void applyClickAnimation(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public final boolean onTouch(View view2, MotionEvent motionEvent) {
                return PluginCell.m1608$r8$lambda$ws7ImX_AXdMw58mZnvhtanEhYQ(view2, motionEvent);
            }
        });
    }

    public static boolean m1608$r8$lambda$ws7ImX_AXdMw58mZnvhtanEhYQ(View view, MotionEvent motionEvent) {
        int action = motionEvent.getAction();
        if (action == 0) {
            view.setPressed(true);
            view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80L).start();
            return true;
        }
        if (action != 1) {
            if (action != 3) {
                return false;
            }
            view.setPressed(false);
            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(350L).setInterpolator(new OvershootInterpolator(1.5f)).start();
            return true;
        }
        view.setPressed(false);
        if (motionEvent.getX() >= 0.0f && motionEvent.getX() <= view.getWidth() && motionEvent.getY() >= 0.0f && motionEvent.getY() <= view.getHeight()) {
            view.performClick();
        }
        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(350L).setInterpolator(new OvershootInterpolator(1.5f)).start();
        return true;
    }

    private final int getPluginIconRadiusDp() {
        return Math.max(ExteraConfig.getSectionRadiusDp() - 16, 8);
    }

    private final ViewOutlineProvider createPluginIconOutlineProvider() {
        return ViewOutlineProviderImpl.boundsWithRoundRect(AndroidUtilities.dp(getPluginIconRadiusDp()));
    }

    private final void updatePluginIconOutlineProvider() {
        this.imageView.setOutlineProvider(createPluginIconOutlineProvider());
        this.imageView.invalidateOutline();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsUnregistered);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsUnregistered);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        Plugin plugin = this.plugin;
        if (plugin == null) {
            return;
        }
        if (id == NotificationCenter.pluginSettingsRegistered || id == NotificationCenter.pluginSettingsUnregistered) {
            Object objFirstOrNull = ArraysKt.firstOrNull(args);
            String str = objFirstOrNull instanceof String ? (String) objFirstOrNull : null;
            if (str != null && Intrinsics.areEqual(str, plugin.getId())) {
                AndroidUtilities.updateViewVisibilityAnimated(this.settingsButton, id == NotificationCenter.pluginSettingsRegistered && plugin.isEnabled(), 0.5f, true, true);
                if (!plugin.isNotResponding() && !plugin.hasError()) {
                    bindNormalState(); // sg: show/clear a create_settings() error
                }
            }
        }
    }

    public static final class Factory extends UItem.UItemFactory<PluginCell> {

            public static final Companion Companion = new Companion();

        public static final UItem asPlugin(Plugin plugin, PluginCellDelegate pluginCellDelegate) {
            return Companion.asPlugin(plugin, pluginCellDelegate);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public PluginCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new PluginCell(context);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof PluginCell) {
                PluginCell pluginCell = (PluginCell) view;
                Object obj = item.object;
                Plugin plugin = obj instanceof Plugin ? (Plugin) obj : null;
                Object obj2 = item.object2;
                pluginCell.set(plugin, obj2 instanceof PluginCellDelegate ? (PluginCellDelegate) obj2 : null);
            }
        }

        @Override
        public boolean equals(UItem a2, UItem b2) {
            return a2.id == b2.id;
        }

        @Override
        public boolean contentsEquals(UItem a2, UItem b2) {
            if (a2.id != b2.id || a2.intValue != b2.intValue) {
                return false;
            }
            Object obj = a2.object;
            Plugin plugin = obj instanceof Plugin ? (Plugin) obj : null;
            if (plugin == null) {
                return false;
            }
            Object obj2 = b2.object;
            Plugin plugin2 = obj2 instanceof Plugin ? (Plugin) obj2 : null;
            if (plugin2 == null) {
                return false;
            }
            return Intrinsics.areEqual(plugin, plugin2);
        }

        public static final class Companion {
            private Companion() {
            }

            public final UItem asPlugin(Plugin plugin, PluginCellDelegate pluginCellDelegate) {
                UItem uItemOfFactory = UItem.ofFactory(Factory.class);
                uItemOfFactory.id = plugin.getId().hashCode();
                uItemOfFactory.object = plugin;
                uItemOfFactory.object2 = pluginCellDelegate;
                uItemOfFactory.intValue = (plugin.isNotResponding() ? 1 : 0) | (plugin.isEnabled() ? 2 : 0) | (plugin.hasError() ? 4 : 0);
                return uItemOfFactory;
            }
        }

        static {
            UItem.UItemFactory.setup(new Factory());
        }
    }
}
