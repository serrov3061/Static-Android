package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;

/**
 * StaticGram: the app update card shared by the mandatory update screen (BlockingUpdateView)
 * and the update sheet (UpdateAppAlertDialog).
 *
 * The package is the help.appUpdate document, downloaded in-app through FileLoader. The card
 * shows live progress on the main button, detects a failed or stalled download (no bytes for
 * {@link #STALL_TIMEOUT_MS}) and then offers «Повторить» plus a browser download of the latest
 * package from dl.staticgram.top, which works even when MTProto downloads do not.
 */
public class SgAppUpdateView extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    /** Always the latest published package (the release's own versioned link may already be deleted from /srv/dl). */
    public static final String BROWSER_DOWNLOAD_URL = "https://dl.staticgram.top/StaticGram.apk";

    private static final long STALL_TIMEOUT_MS = 25_000;

    private static final int STATE_IDLE = 0;
    private static final int STATE_LOADING = 1;
    private static final int STATE_READY = 2;
    private static final int STATE_FAILED = 3;

    private final boolean forced;
    private final boolean inSheet;
    private final Theme.ResourcesProvider resourcesProvider;

    private final ScrollView scrollView;
    private final RLottieImageView imageView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView hintView;
    private final LinearLayout changelogCard;
    private final TextView changelogTitleView;
    private final TextView changelogView;
    private final TextView statusView;
    private final ProgressButton primaryButton;
    private final TonalButton browserButton;
    private final ButtonWithCounterView laterButton;

    private TLRPC.TL_help_appUpdate appUpdate;
    private int account;
    private String fileName;
    private int state = -1;
    private boolean stalled;
    private boolean observing;
    private long lastProgressTime;
    private long lastLoadedBytes = -1;
    private Runnable onLater;

    private final Runnable stallCheck = new Runnable() {
        @Override
        public void run() {
            if (state != STATE_LOADING) {
                return;
            }
            if (!stalled && SystemClock.elapsedRealtime() - lastProgressTime >= STALL_TIMEOUT_MS) {
                stalled = true;
                FileLog.d("sg_update: download of " + fileName + " stalled at " + lastLoadedBytes + " bytes");
                setStatus(LocaleController.getString(R.string.SgUpdateStalled), true);
                primaryButton.setText(LocaleController.getString(R.string.SgUpdateRetry), true);
            }
            AndroidUtilities.runOnUIThread(this, 2000);
        }
    };

    public SgAppUpdateView(Context context, boolean forced, boolean inSheet, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.forced = forced;
        this.inSheet = inSheet;
        this.resourcesProvider = resourcesProvider;
        setOrientation(VERTICAL);

        scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                if (SgAppUpdateView.this.inSheet) {
                    // a long changelog scrolls inside the sheet instead of pushing the buttons off screen
                    int max = (int) (AndroidUtilities.displaySize.y * 0.55f);
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(OVER_SCROLL_NEVER);
        if (inSheet) {
            addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        }

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scrollView.addView(content, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        imageView = new RLottieImageView(context);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = inSheet ? 112 : 136;
        imageView.setImageResource(R.drawable.staticgram_logo); // StaticGram brand v2 (09.11.2026): the app icon (tile + gem) instead of the plane animation
        content.addView(imageView, LayoutHelper.createLinear(iconSize, iconSize, Gravity.CENTER_HORIZONTAL, 0, inSheet ? 20 : 32, 0, 0));

        titleView = new TextView(context);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, inSheet ? 20 : 24);
        titleView.setGravity(Gravity.CENTER);
        titleView.setText(LocaleController.getString(forced ? R.string.SgUpdateRequiredTitle : R.string.SgUpdateAvailableTitle));
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 14, 24, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setGravity(Gravity.CENTER);
        content.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 4, 24, 0));

        hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        hintView.setGravity(Gravity.CENTER);
        hintView.setLineSpacing(dp(2), 1f);
        hintView.setText(LocaleController.getString(R.string.SgUpdateRequiredHint));
        hintView.setVisibility(forced ? VISIBLE : GONE);
        content.addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 32, 12, 32, 0));

        changelogCard = new LinearLayout(context);
        changelogCard.setOrientation(VERTICAL);
        changelogCard.setPadding(dp(16), dp(14), dp(16), dp(16));
        content.addView(changelogCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 20, 16, 16));

        changelogTitleView = new TextView(context);
        changelogTitleView.setTypeface(AndroidUtilities.bold());
        changelogTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        changelogTitleView.setText(LocaleController.getString(R.string.SgUpdateWhatsNew));
        changelogCard.addView(changelogTitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        changelogView = new TextView(context);
        changelogView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        changelogView.setLineSpacing(dp(2), 1f);
        changelogView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        changelogCard.addView(changelogView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0, 0));

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(VERTICAL);
        addView(bottom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setGravity(Gravity.CENTER);
        statusView.setVisibility(GONE);
        bottom.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 4, 24, 8));

        primaryButton = new ProgressButton(context, resourcesProvider);
        primaryButton.setRoundRadius(24);
        primaryButton.setOnClickListener(v -> onPrimaryClick());
        bottom.addView(primaryButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 4, 16, 0));

        browserButton = new TonalButton(context, resourcesProvider);
        browserButton.setRoundRadius(24);
        browserButton.setText(LocaleController.getString(R.string.SgUpdateOpenBrowser), false);
        browserButton.setOnClickListener(v -> openBrowserDownload());
        bottom.addView(browserButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 8, 16, 0));

        laterButton = new ButtonWithCounterView(context, false, resourcesProvider);
        laterButton.setRoundRadius(24);
        laterButton.setText(LocaleController.getString(R.string.SgUpdateLater), false);
        laterButton.setOnClickListener(v -> {
            if (onLater != null) {
                onLater.run();
            }
        });
        laterButton.setVisibility(forced ? GONE : VISIBLE);
        bottom.addView(laterButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 4, 16, 0));

        View bottomSpace = new View(context);
        bottom.addView(bottomSpace, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, inSheet ? 8 : 20));

        updateColors();
    }

    public void setOnHeaderClick(Runnable onClick) {
        imageView.setOnClickListener(onClick == null ? null : v -> onClick.run());
    }

    public void setOnLater(Runnable onLater) {
        this.onLater = onLater;
    }

    public TLRPC.TL_help_appUpdate getAppUpdate() {
        return appUpdate;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public void updateColors() {
        int textColor = getThemedColor(inSheet ? Theme.key_dialogTextBlack : Theme.key_windowBackgroundWhiteBlackText);
        int grayColor = getThemedColor(inSheet ? Theme.key_dialogTextGray3 : Theme.key_windowBackgroundWhiteGrayText);
        int linkColor = getThemedColor(inSheet ? Theme.key_dialogTextLink : Theme.key_windowBackgroundWhiteLinkText);
        titleView.setTextColor(textColor);
        subtitleView.setTextColor(grayColor);
        hintView.setTextColor(grayColor);
        changelogTitleView.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        changelogView.setTextColor(textColor);
        changelogView.setLinkTextColor(linkColor);
        changelogCard.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(textColor, Theme.isCurrentThemeDark() ? 0.07f : 0.045f)));
        statusView.setTextColor(getThemedColor(Theme.key_text_RedRegular));
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, getThemedColor(inSheet ? Theme.key_dialogBackground : Theme.key_windowBackgroundWhite));
        primaryButton.updateColors();
        browserButton.updateColors();
        laterButton.updateColors();
        laterButton.setTextColor(grayColor);
    }

    /** Binds a release; safe to call again with the same or a newer update. */
    public void setAppUpdate(int account, TLRPC.TL_help_appUpdate update) {
        boolean same = appUpdate != null && update != null && appUpdate.id == update.id && TextUtils.equals(appUpdate.version, update.version)
                && this.account == account;
        if (!same && observing) {
            removeObservers();
        }
        this.account = account;
        this.appUpdate = update;
        TLRPC.Document document = getDocument();
        fileName = document != null ? FileLoader.getAttachFileName(document) : null;

        String version = update != null && !TextUtils.isEmpty(update.version) ? update.version : "";
        if (document != null && document.size > 0) {
            subtitleView.setText(LocaleController.formatString(R.string.SgUpdateVersionAndSize, version, AndroidUtilities.formatFileSize(document.size)));
        } else {
            subtitleView.setText(LocaleController.formatString(R.string.SgUpdateVersion, version));
        }
        CharSequence notes = null;
        if (update != null && !TextUtils.isEmpty(update.text)) {
            SpannableStringBuilder builder = new SpannableStringBuilder(update.text.trim());
            try {
                MessageObject.addEntitiesToText(builder, update.entities, false, false, false, false);
            } catch (Exception e) {
                FileLog.e(e);
            }
            notes = Emoji.replaceEmoji(builder, changelogView.getPaint().getFontMetricsInt(), false);
        }
        if (TextUtils.isEmpty(notes)) {
            changelogView.setText(LocaleController.getString(R.string.SgUpdateNoNotes));
        } else {
            changelogView.setText(notes);
        }
        if (isAttachedToWindow()) {
            addObservers();
        }
        if (!same) {
            stalled = false;
            state = -1;
        }
        refreshState(false);
    }

    private TLRPC.Document getDocument() {
        return appUpdate != null && appUpdate.document instanceof TLRPC.TL_document ? appUpdate.document : null;
    }

    private File getApkFile() {
        TLRPC.Document document = getDocument();
        return document == null ? null : FileLoader.getInstance(account).getPathToAttach(document, true);
    }

    private boolean isApkReady() {
        File f = getApkFile();
        if (f == null || !f.exists()) {
            return false;
        }
        TLRPC.Document document = getDocument();
        return document.size <= 0 || f.length() == document.size;
    }

    /** Re-reads the download state (the file can finish or vanish while the view is hidden). */
    public void refreshState(boolean animated) {
        TLRPC.Document document = getDocument();
        if (document == null) {
            setState(STATE_IDLE, animated);
        } else if (isApkReady()) {
            setState(STATE_READY, animated);
        } else if (fileName != null && FileLoader.getInstance(account).isLoadingFile(fileName)) {
            Float p = ImageLoader.getInstance().getFileProgress(fileName);
            setState(STATE_LOADING, animated);
            updateProgress(p != null ? (long) (p * document.size) : 0, document.size, animated);
        } else if (state != STATE_FAILED) {
            setState(STATE_IDLE, animated);
        }
    }

    private void setState(int newState, boolean animated) {
        if (state == newState && newState != STATE_LOADING) {
            return;
        }
        boolean wasLoading = state == STATE_LOADING;
        state = newState;
        TLRPC.Document document = getDocument();
        switch (newState) {
            case STATE_IDLE:
                primaryButton.setText(LocaleController.getString(R.string.SgUpdateNow), animated);
                primaryButton.setSubText(document != null && document.size > 0 ? AndroidUtilities.formatFileSize(document.size) : null, animated);
                primaryButton.setProgress(0, false);
                setStatus(null, animated);
                break;
            case STATE_LOADING:
                if (!wasLoading) {
                    lastProgressTime = SystemClock.elapsedRealtime();
                    lastLoadedBytes = -1;
                    stalled = false;
                    setStatus(null, animated);
                    primaryButton.setText(LocaleController.formatString(R.string.SgUpdateDownloading, 0), animated);
                    AndroidUtilities.cancelRunOnUIThread(stallCheck);
                    AndroidUtilities.runOnUIThread(stallCheck, 2000);
                }
                break;
            case STATE_READY:
                primaryButton.setProgress(1f, animated);
                primaryButton.setText(LocaleController.getString(R.string.SgUpdateInstall), animated);
                primaryButton.setSubText(null, animated);
                setStatus(null, animated);
                break;
            case STATE_FAILED:
                primaryButton.setText(LocaleController.getString(R.string.SgUpdateRetry), animated);
                primaryButton.setSubText(null, animated);
                primaryButton.setProgress(0, animated);
                setStatus(LocaleController.getString(R.string.SgUpdateFailed), animated);
                break;
        }
        if (newState != STATE_LOADING) {
            AndroidUtilities.cancelRunOnUIThread(stallCheck);
            stalled = false;
        }
    }

    private void updateProgress(long loaded, long total, boolean animated) {
        if (total <= 0) {
            return;
        }
        if (loaded != lastLoadedBytes) {
            if (lastLoadedBytes >= 0 && loaded > lastLoadedBytes) {
                lastProgressTime = SystemClock.elapsedRealtime();
                if (stalled) {
                    stalled = false;
                    setStatus(null, true);
                }
            }
            lastLoadedBytes = loaded;
        }
        float progress = Math.max(0f, Math.min(1f, loaded / (float) total));
        primaryButton.setProgress(progress, animated);
        if (!stalled) {
            primaryButton.setText(LocaleController.formatString(R.string.SgUpdateDownloading, (int) (progress * 100)), animated);
        }
        primaryButton.setSubText(LocaleController.formatString(R.string.SgUpdateDownloadedOf, AndroidUtilities.formatFileSize(loaded), AndroidUtilities.formatFileSize(total)), animated);
    }

    private void setStatus(CharSequence text, boolean animated) {
        if (TextUtils.isEmpty(text)) {
            statusView.setVisibility(GONE);
        } else {
            statusView.setText(text);
            statusView.setVisibility(VISIBLE);
        }
    }

    private void onPrimaryClick() {
        TLRPC.Document document = getDocument();
        if (appUpdate == null) {
            return;
        }
        if (document == null) {
            // a link-only release (core-admin mode "link")
            openUrlExternally(!TextUtils.isEmpty(appUpdate.url) ? appUpdate.url : BROWSER_DOWNLOAD_URL);
            return;
        }
        if (state == STATE_READY) {
            install();
            return;
        }
        if (state == STATE_LOADING && !stalled) {
            return;
        }
        startDownload(state == STATE_LOADING || state == STATE_FAILED);
    }

    private void startDownload(boolean restart) {
        TLRPC.Document document = getDocument();
        if (document == null) {
            return;
        }
        File f = getApkFile();
        if (f != null && f.exists() && !isApkReady()) {
            // a truncated file from an older build would be "installed" and fail with a parse error
            try {
                f.delete();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        FileLoader loader = FileLoader.getInstance(account);
        if (restart && fileName != null && loader.isLoadingFile(fileName)) {
            loader.cancelLoadFile(document);
        }
        FileLog.d("sg_update: start download " + fileName + " size=" + document.size + " dc=" + document.dc_id + " restart=" + restart);
        state = -1;
        setState(STATE_LOADING, true);
        updateProgress(0, document.size, false);
        // restart after cancel on the next frame: cancelLoadFile is processed on the file loader queue
        AndroidUtilities.runOnUIThread(() -> loader.loadFile(document, "update", FileLoader.PRIORITY_HIGH, 1), restart ? 300 : 0);
    }

    private void install() {
        Activity activity = AndroidUtilities.findActivity(getContext());
        if (activity == null) {
            return;
        }
        if (!ApplicationLoader.applicationLoaderInstance.checkApkInstallPermissions(activity)) {
            return;
        }
        if (!ApplicationLoader.applicationLoaderInstance.openApkInstall(activity, getDocument())) {
            if (!isApkReady()) {
                state = -1;
                setState(STATE_IDLE, true);
            }
            setStatus(LocaleController.getString(R.string.SgUpdateInstallFailed), true);
        }
    }

    private void openBrowserDownload() {
        openUrlExternally(BROWSER_DOWNLOAD_URL);
    }

    private void openUrlExternally(String url) {
        // an external browser (or the system download manager) resumes a broken download and does not
        // depend on the MTProto connection; the in-app browser cannot save an APK
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
            Browser.openUrl(getContext(), url, false);
        }
    }

    private void addObservers() {
        if (observing) {
            return;
        }
        observing = true;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadProgressChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
    }

    private void removeObservers() {
        if (!observing) {
            return;
        }
        observing = false;
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (appUpdate != null) {
            addObservers();
            refreshState(false);
        }
        if (!imageView.isPlaying()) {
            imageView.playAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeObservers();
        AndroidUtilities.cancelRunOnUIThread(stallCheck);
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            updateColors();
            return;
        }
        if (fileName == null || args.length == 0 || !fileName.equals(args[0])) {
            return;
        }
        if (id == NotificationCenter.fileLoaded) {
            FileLog.d("sg_update: downloaded " + fileName);
            setState(STATE_READY, true);
            if (getVisibility() == VISIBLE && isShown()) {
                install();
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            int reason = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : -1;
            FileLog.d("sg_update: download failed " + fileName + " reason=" + reason);
            if (reason == 1) {
                // canceled: by our own restart, or by the user from the download list
                if (state == STATE_LOADING && !FileLoader.getInstance(account).isLoadingFile(fileName)) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (state == STATE_LOADING && !FileLoader.getInstance(account).isLoadingFile(fileName)) {
                            setState(STATE_FAILED, true);
                        }
                    }, 1000);
                }
                return;
            }
            setState(STATE_FAILED, true);
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            if (state != STATE_LOADING) {
                state = -1;
                setState(STATE_LOADING, true);
            }
            Long loaded = (Long) args[1];
            Long total = (Long) args[2];
            TLRPC.Document document = getDocument();
            long totalBytes = total != null && total > 0 ? total : (document != null ? document.size : 0);
            updateProgress(loaded != null ? loaded : 0, totalBytes, true);
        }
    }

    /**
     * A secondary (tonal) round button: translucent accent background, accent text and a trailing
     * "open externally" arrow. Same size and corner radius as the filled primary button.
     */
    private static class TonalButton extends ButtonWithCounterView {

        private final Drawable icon;
        private final int gap = dp(6);
        private final Theme.ResourcesProvider provider;

        TonalButton(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context, true, resourcesProvider);
            this.provider = resourcesProvider;
            icon = context.getResources().getDrawable(R.drawable.mini_external_link).mutate();
            setWillNotDraw(false);
        }

        @Override
        public void updateColors() {
            super.updateColors();
            if (icon == null) {
                return; // called from the super constructor
            }
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, provider);
            setColor(Theme.multAlpha(accent, Theme.isCurrentThemeDark() ? 0.22f : 0.14f), Theme.multAlpha(accent, 0.16f));
            text.setTextColor(accent);
            icon.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int size = dp(18);
            float shift = (size + gap) / 2f;
            canvas.save();
            canvas.translate(-shift, 0);
            super.onDraw(canvas);
            float textRight = (getWidth() + text.getCurrentWidth()) / 2f;
            int left = (int) (textRight + gap);
            int top = (getHeight() - size) / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            canvas.restore();
        }
    }

    /** A filled round button whose background fills from the left as the download goes. */
    private static class ProgressButton extends ButtonWithCounterView {

        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF rect = new RectF();
        private float progress;
        private float drawnProgress;
        private ValueAnimator animator;

        ProgressButton(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context, true, resourcesProvider);
            progressPaint.setColor(Theme.multAlpha(Color.WHITE, 0.22f));
        }

        void setProgress(float value, boolean animated) {
            progress = value;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animated || value < drawnProgress) {
                drawnProgress = value;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(drawnProgress, value);
            animator.addUpdateListener(a -> {
                drawnProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(220);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (drawnProgress > 0f && drawnProgress < 1f) {
                rect.set(0, 0, getWidth(), getHeight());
                clipPath.rewind();
                clipPath.addRoundRect(rect, dp(24), dp(24), Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clipPath);
                canvas.drawRect(0, 0, getWidth() * drawnProgress, getHeight(), progressPaint);
                canvas.restore();
            }
            super.onDraw(canvas);
        }
    }
}
