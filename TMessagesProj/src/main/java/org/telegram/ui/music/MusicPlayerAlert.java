package org.telegram.ui.music;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileRefController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.DarkThemeResourceProvider;

import java.io.File;
import java.util.ArrayList;

/**
 * Full screen music player on a background in the cover's colors.
 *
 * Top: collapse arrow, playlist title with «Track N of M», menu. Middle: the big cover, which shrinks into the
 * title row when the lyrics or the queue page is open. Bottom: title and artist with the like (save to profile)
 * button, wave progress with times, shuffle / previous / play / next / repeat, then speed, lyrics and queue.
 */
public class MusicPlayerAlert extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private static final int PAGE_COVER = 0;
    private static final int PAGE_LYRICS = 1;
    private static final int PAGE_QUEUE = 2;

    public static MusicPlayerAlert instance;

    private final LaunchActivity parentActivity;
    private final int currentAccount;
    private final Theme.ResourcesProvider dark = new DarkThemeResourceProvider();
    private final MusicCoverColors.BackgroundDrawable background = new MusicCoverColors.BackgroundDrawable();
    private final PlayerLayout root;

    private final MusicGlassButton collapseButton;
    private final MusicGlassButton menuButton;
    private final MusicGlassButton shuffleButton;
    private final MusicGlassButton prevButton;
    private final MusicGlassButton playButton;
    private final MusicGlassButton nextButton;
    private final MusicGlassButton repeatButton;
    private final MusicGlassButton speedButton;
    private final MusicGlassButton lyricsButton;
    private final MusicGlassButton queueButton;
    private final MusicGlassButton likeButton;
    private final PlayPauseDrawable playPauseDrawable;

    private final TextView playlistTitle;
    private final TextView playlistSubtitle;
    private final MusicLyricsView lyricsView;
    private final LinearLayout lyricsEmpty;
    private final RLottieImageView lyricsEmptyImage;
    private final TextView lyricsEmptyTitle;
    private final TextView lyricsEmptyText;
    private final MusicQueueView queueView;
    private final BackupImageView coverView;
    private final TextView trackTitle;
    private final TextView trackAuthor;
    private final MusicWaveSeekBar seekBar;
    private final TextView timeCurrent;
    private final TextView timeRemaining;
    private final MusicPlayerSettingsView settingsView;

    private MessagesController.SavedMusicList savedMusicList;
    private MessageObject lastMessageObject;
    private MusicCoverColors.PlaceholderDrawable placeholder;
    private String coverKey;
    private String lyricsKey;
    private boolean lyricsLoading;
    private boolean noforwards;
    private boolean settingsShown;
    private int lastTime = -1;
    private int lastRemaining = -1;

    private int page = PAGE_COVER;
    private int pageLeaving = -1;
    private float pageProgress;
    private float pageSwitch = 1f;
    private ValueAnimator pageAnimator;

    /** Opens this player for music when it is on in settings, otherwise returns null so the caller picks another one. */
    public static BottomSheet createIfEnabled(Context context) {
        if (MusicPlayerConfig.isPlayerEnabled() && context instanceof LaunchActivity) {
            return new MusicPlayerAlert(context);
        }
        return null;
    }

    /** Turns the StaticGram player on from a classic player and opens it in place of {@code current}, no restart. */
    public static void switchFrom(BottomSheet current, Context context) {
        MusicPlayerConfig.setPlayerEnabled(true);
        final Context activity = context instanceof LaunchActivity ? context : LaunchActivity.instance;
        if (current != null) {
            current.dismiss();
        }
        final BottomSheet player = activity != null ? createIfEnabled(activity) : null;
        if (player != null) {
            player.show();
        }
    }

    public static boolean isShowingNow() {
        return instance != null && instance.isShowing();
    }

    private MusicPlayerAlert(Context context) {
        super(context, true, null);
        parentActivity = (LaunchActivity) context;
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        currentAccount = playing != null ? playing.currentAccount : UserConfig.selectedAccount;

        occupyNavigationBar = true;
        drawNavigationBar = false;
        backgroundPaddingLeft = 0;
        backgroundPaddingTop = 0;
        useBackgroundTopPadding = false;
        fixNavigationBar(0);
        setUseLightStatusBar(false);

        root = new PlayerLayout(context);
        root.setBackground(background);
        background.setOnChange(this::invalidateGlass);
        containerView = root;

        collapseButton = button(context, R.drawable.arrow_more, 24);
        collapseButton.setContentDescription(LocaleController.getString(R.string.Close));
        collapseButton.setOnClickListener(v -> dismiss());

        menuButton = button(context, R.drawable.ic_ab_other, 24);
        menuButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        menuButton.setOnClickListener(v -> showMenu());

        shuffleButton = button(context, R.drawable.player_new_shuffle, 24);
        shuffleButton.setGlassShown(false);
        shuffleButton.setOnClickListener(v -> toggleShuffle());

        prevButton = button(context, R.drawable.ic_action_previous, 28);
        prevButton.setOnClickListener(v -> MediaController.getInstance().playPreviousMessage());

        playButton = new MusicGlassButton(context);
        playPauseDrawable = new PlayPauseDrawable(26);
        playPauseDrawable.setParent(playButton);
        playButton.setIcon(playPauseDrawable);
        playButton.setIconSize(AndroidUtilities.dp(30));
        playButton.setFillColor(0xFFFFFFFF);
        playButton.setIconColor(0xFF141319);
        playButton.setOnClickListener(v -> togglePlay());
        root.addView(playButton);

        nextButton = button(context, R.drawable.ic_action_next, 28);
        nextButton.setOnClickListener(v -> MediaController.getInstance().playNextMessage());

        repeatButton = button(context, R.drawable.player_new_repeatall, 24);
        repeatButton.setGlassShown(false);
        repeatButton.setOnClickListener(v -> {
            SharedConfig.setRepeatMode((SharedConfig.repeatMode + 1) % 3);
            updateOrderButtons(true);
        });

        speedButton = button(context, 0, 0);
        speedButton.setOnClickListener(v -> showSpeeds());

        lyricsButton = button(context, R.drawable.formatting_quote, 24);
        lyricsButton.setGlassShown(false);
        lyricsButton.setContentDescription(LocaleController.getString(R.string.SgMusicLyrics));
        lyricsButton.setOnClickListener(v -> setPage(page == PAGE_LYRICS ? PAGE_COVER : PAGE_LYRICS));

        queueButton = button(context, R.drawable.msg_list, 22);
        queueButton.setGlassShown(false);
        queueButton.setContentDescription(LocaleController.getString(R.string.SgMusicQueue));
        queueButton.setOnClickListener(v -> setPage(page == PAGE_QUEUE ? PAGE_COVER : PAGE_QUEUE));

        likeButton = button(context, R.drawable.media_like, 24);
        likeButton.setOnClickListener(v -> toggleLike());

        playlistTitle = text(context, 15, true, 0xFFFFFFFF);
        playlistTitle.setGravity(Gravity.CENTER);
        playlistSubtitle = text(context, 13, false, 0x99FFFFFF);
        playlistSubtitle.setGravity(Gravity.CENTER);

        lyricsView = new MusicLyricsView(context);
        lyricsView.setOnSeek(timeMs -> {
            final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject == null || messageObject.getDuration() <= 0) {
                return;
            }
            MediaController.getInstance().seekToProgress(messageObject, Math.min(1f, timeMs / 1000f / (float) messageObject.getDuration()));
        });
        lyricsView.setVisibility(View.GONE);
        root.addView(lyricsView);

        lyricsEmpty = new LinearLayout(context);
        lyricsEmpty.setOrientation(LinearLayout.VERTICAL);
        lyricsEmpty.setGravity(Gravity.CENTER);
        lyricsEmptyImage = new RLottieImageView(context);
        lyricsEmptyImage.setAnimation(R.raw.utyan_empty, 120, 120);
        lyricsEmpty.addView(lyricsEmptyImage, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL));
        lyricsEmptyTitle = text(context, 18, true, 0xFFFFFFFF);
        lyricsEmptyTitle.setGravity(Gravity.CENTER);
        lyricsEmpty.addView(lyricsEmptyTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));
        lyricsEmptyText = text(context, 14, false, 0xAAFFFFFF);
        lyricsEmptyText.setGravity(Gravity.CENTER);
        lyricsEmptyText.setSingleLine(false);
        lyricsEmpty.addView(lyricsEmptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 6, 24, 0));
        lyricsEmpty.setVisibility(View.GONE);
        root.addView(lyricsEmpty);

        queueView = new MusicQueueView(context);
        queueView.setVisibility(View.GONE);
        root.addView(queueView);

        coverView = new BackupImageView(context);
        coverView.setRoundRadius(AndroidUtilities.dp(18));
        coverView.getImageReceiver().setDelegate(new ImageReceiver.ImageReceiverDelegate() {
            @Override
            public void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb, boolean memCache) {
                if (set && imageReceiver.getBitmap() != null) {
                    takeCoverColors(imageReceiver.getBitmap());
                }
            }
        });
        coverView.setOnClickListener(v -> {
            if (page != PAGE_COVER) {
                setPage(PAGE_COVER);
            }
        });
        root.addView(coverView);

        trackTitle = text(context, 21, true, 0xFFFFFFFF);
        trackTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        trackTitle.setMarqueeRepeatLimit(-1);
        trackTitle.setSelected(true);
        trackAuthor = text(context, 16, false, 0xB3FFFFFF);
        root.addView(trackTitle);
        root.addView(trackAuthor);

        seekBar = new MusicWaveSeekBar(context);
        seekBar.setDelegate((progress, done) -> {
            final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject == null) {
                return;
            }
            if (done) {
                MediaController.getInstance().seekToProgress(messageObject, progress);
            }
            updateTimes(messageObject, progress);
        });
        root.addView(seekBar);

        timeCurrent = text(context, 12, false, 0x99FFFFFF);
        timeRemaining = text(context, 12, false, 0x99FFFFFF);
        timeRemaining.setGravity(Gravity.RIGHT);
        timeCurrent.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        timeRemaining.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        root.addView(timeCurrent);
        root.addView(timeRemaining);
        root.addView(playlistTitle);
        root.addView(playlistSubtitle);

        settingsView = new MusicPlayerSettingsView(context, background, this::onSettingsChanged, () -> showSettings(false));
        settingsView.setVisibility(View.GONE);
        background.setOnUpdate(settingsView::updateColors);
        root.addView(settingsView);

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.moreMusicDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingSpeedChanged);

        savedMusicList = MediaController.getInstance().currentSavedMusicList;
        final MusicCoverColors colors = MusicPlayerConfig.isCoverColorsFull() ? MusicCoverColors.forTrack(playing) : null;
        background.setColors(colors != null ? colors : MusicCoverColors.theme(), false);
        seekBar.setColor((colors != null ? colors : MusicCoverColors.theme()).accent);
        updateOrderButtons(false);
        updateSpeed();
        updateTitle(false);
        queueView.update();
    }

    private MusicGlassButton button(Context context, int iconRes, int iconSizeDp) {
        final MusicGlassButton button = new MusicGlassButton(context);
        if (iconRes != 0) {
            button.setIcon(iconRes);
        }
        if (iconSizeDp > 0) {
            button.setIconSize(AndroidUtilities.dp(iconSizeDp));
        }
        root.addView(button);
        return button;
    }

    private static TextView text(Context context, int sizeDp, boolean bold, int color) {
        final TextView textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        textView.setTextColor(color);
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) {
            textView.setTypeface(AndroidUtilities.bold());
        }
        return textView;
    }

    private void invalidateGlass() {
        for (int i = 0; i < root.getChildCount(); ++i) {
            final View child = root.getChildAt(i);
            if (child instanceof MusicGlassButton) {
                child.invalidate();
            }
        }
        root.invalidate();
    }

    /** Root layout: places every control by hand and animates the cover between the big and the title-row slot. */
    private class PlayerLayout extends FrameLayout {
        private final Rect coverBig = new Rect();
        private final RectF coverSmall = new RectF();
        private final Rect pageRect = new Rect();
        private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF shadowRect = new RectF();
        private Bitmap shadow;
        private int shadowFor;
        private float shadowPad;
        private int topInset;
        private int bottomInset;
        private int infoTop;
        private int titleWidth;
        private int titlesWidth = -1;

        PlayerLayout(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
        }

        private int exactly(int size) {
            return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY);
        }

        private void place(View view, int x, int y, int width, int height) {
            if (view.getVisibility() == View.GONE) {
                return;
            }
            view.measure(exactly(width), exactly(height));
            view.layout(x, y, x + width, y + height);
        }

        /** Blurred rounded shadow under the cover, rendered once at quarter size for the current cover width. */
        private Bitmap shadow(int size) {
            if (shadow != null && shadowFor == size) {
                return shadow;
            }
            shadowFor = size;
            final float scaled = size * 0.25f;
            final float blur = AndroidUtilities.dp(26) * 0.25f;
            shadowPad = blur * 2f;
            final int bitmapSize = (int) Math.ceil(shadowPad * 2f + scaled);
            try {
                final Bitmap bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ALPHA_8);
                final Canvas canvas = new Canvas(bitmap);
                final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(0x70000000);
                paint.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
                final float radius = AndroidUtilities.dp(18) * 0.25f;
                canvas.drawRoundRect(shadowPad, shadowPad, shadowPad + scaled, shadowPad + scaled, radius, radius, paint);
                shadow = bitmap;
            } catch (Throwable e) {
                shadow = null;
            }
            shadowPad /= 0.25f;
            return shadow;
        }

        void applyPage() {
            if (coverBig.isEmpty()) {
                return;
            }
            final float t = pageProgress;
            final float bigSize = coverBig.width();
            final float scale = AndroidUtilities.lerp(bigSize, coverSmall.width(), t) / bigSize;
            coverView.setPivotX(0);
            coverView.setPivotY(0);
            coverView.setScaleX(scale);
            coverView.setScaleY(scale);
            coverView.setTranslationX(AndroidUtilities.lerp(coverBig.left, coverSmall.left, t) - coverBig.left);
            coverView.setTranslationY(AndroidUtilities.lerp(coverBig.top, coverSmall.top, t) - coverBig.top);
            final float radius = AndroidUtilities.lerp(AndroidUtilities.dp(18), AndroidUtilities.dp(10), t) / Math.max(0.01f, scale);
            coverView.setRoundRadius((int) radius);
            if (placeholder != null) {
                placeholder.setRadius(radius);
            }
            invalidate();

            final int shift = AndroidUtilities.dp(64);
            final int width = t > 0 || (pageAnimator != null && pageAnimator.isRunning()) ? titleWidth - shift : titleWidth;
            final int left = AndroidUtilities.dp(24);
            if (width != titlesWidth || trackTitle.getWidth() != width) {
                titlesWidth = width;
                trackTitle.measure(exactly(width), exactly(AndroidUtilities.dp(28)));
                trackTitle.layout(left, infoTop + AndroidUtilities.dp(4), left + width, infoTop + AndroidUtilities.dp(32));
                trackAuthor.measure(exactly(width), exactly(AndroidUtilities.dp(22)));
                trackAuthor.layout(left, infoTop + AndroidUtilities.dp(34), left + width, infoTop + AndroidUtilities.dp(56));
            }
            trackTitle.setTranslationX(shift * t);
            trackAuthor.setTranslationX(shift * t);

            applyPageView(lyricsView, PAGE_LYRICS, t);
            applyPageView(lyricsEmpty, PAGE_LYRICS, t);
            applyPageView(queueView, PAGE_QUEUE, t);
        }

        private void applyPageView(View view, int viewPage, float t) {
            float alpha;
            if (viewPage == page) {
                alpha = pageLeaving != -1 && pageLeaving != PAGE_COVER && pageLeaving != page ? pageSwitch : t;
            } else if (viewPage == pageLeaving) {
                alpha = page != PAGE_COVER ? 1f - pageSwitch : t;
            } else {
                alpha = 0f;
            }
            view.setAlpha(alpha);
            view.setTranslationY((1f - alpha) * AndroidUtilities.dp(16));
        }

        @Override
        protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
            if (child == coverView && coverView.getWidth() > 0) {
                final Bitmap bitmap = shadow(coverView.getWidth());
                if (bitmap != null) {
                    final float scale = coverView.getScaleX();
                    final float x = coverView.getLeft() + coverView.getTranslationX();
                    final float y = coverView.getTop() + coverView.getTranslationY();
                    final float size = coverView.getWidth() * scale;
                    final float pad = shadowPad * scale;
                    final float offset = AndroidUtilities.dp(12) * scale;
                    shadowRect.set(x - pad, y - pad + offset, x + size + pad, y + size + pad + offset);
                    shadowPaint.setColor(0xFF000000);
                    canvas.drawBitmap(bitmap, null, shadowRect, shadowPaint);
                }
            }
            return super.drawChild(canvas, child, drawingTime);
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            requestLayout();
            return super.onApplyWindowInsets(insets);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int height = MeasureSpec.getSize(heightMeasureSpec);
            topInset = AndroidUtilities.statusBarHeight;
            bottomInset = AndroidUtilities.navigationBarHeight;
            final WindowInsets windowInsets = getRootWindowInsets();
            if (windowInsets != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    final Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    topInset = Math.max(topInset, bars.top);
                    bottomInset = bars.bottom;
                } else {
                    topInset = Math.max(topInset, windowInsets.getStableInsetTop());
                    bottomInset = windowInsets.getStableInsetBottom();
                }
            }
            final int widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            final int heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); ++i) {
                final View child = getChildAt(i);
                if (child.getVisibility() != View.GONE) {
                    measureChildWithMargins(child, widthSpec, 0, heightSpec, 0);
                }
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            final int width = r - l;
            final int height = b - t;
            final int side = AndroidUtilities.dp(24);
            final int buttonSize = AndroidUtilities.dp(44);

            final int top = topInset + AndroidUtilities.dp(4);
            place(collapseButton, side - AndroidUtilities.dp(10), top, buttonSize + AndroidUtilities.dp(4), buttonSize + AndroidUtilities.dp(4));
            collapseButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
            final int right = width - side;
            final int rightButton = right - buttonSize;
            place(menuButton, rightButton + AndroidUtilities.dp(6), top, buttonSize + AndroidUtilities.dp(4), buttonSize + AndroidUtilities.dp(4));
            menuButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));

            final int titleWidthTop = width - (side + AndroidUtilities.dp(48)) * 2;
            final int titleLeft = (width - titleWidthTop) / 2;
            place(playlistTitle, titleLeft, top + AndroidUtilities.dp(playlistSubtitle.getVisibility() == View.VISIBLE ? 3 : 12), titleWidthTop, AndroidUtilities.dp(21));
            place(playlistSubtitle, titleLeft, top + AndroidUtilities.dp(24), titleWidthTop, AndroidUtilities.dp(18));
            final int headerBottom = top + AndroidUtilities.dp(48);

            final int bottomRow = height - bottomInset - AndroidUtilities.dp(14) - buttonSize;
            place(speedButton, side - AndroidUtilities.dp(6), bottomRow, AndroidUtilities.dp(68), buttonSize);
            speedButton.setGlassSize(AndroidUtilities.dp(60), AndroidUtilities.dp(34));
            place(queueButton, rightButton + AndroidUtilities.dp(4), bottomRow, buttonSize, buttonSize);
            queueButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
            place(lyricsButton, right - buttonSize * 2 - AndroidUtilities.dp(4), bottomRow, buttonSize, buttonSize);
            lyricsButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));

            final int controlsHeight = AndroidUtilities.dp(72);
            final int controlsTop = bottomRow - AndroidUtilities.dp(18) - controlsHeight;
            final float[] sizes = { 42, 52, 88, 52, 42 };
            final float edge = AndroidUtilities.dp(16);
            final float gap = AndroidUtilities.dp(14);
            final float needed = AndroidUtilities.dp(sizes[1] + sizes[2] + sizes[3]) + gap * 2 + AndroidUtilities.dp(sizes[0] + sizes[4]) + gap * 2 + edge * 2;
            final float scale = needed > width ? Math.max(0.7f, (width - gap * 4 - edge * 2) / (needed - gap * 4 - edge * 2)) : 1f;
            final float center = width / 2f;
            final float playHalf = AndroidUtilities.dp(sizes[2]) * scale / 2f;
            final float sideHalf = AndroidUtilities.dp(sizes[1]) * scale / 2f;
            final float outerHalf = AndroidUtilities.dp(sizes[0]) * scale / 2f;
            final float[] centers = {
                edge + outerHalf,
                center - playHalf - gap - sideHalf,
                center,
                center + playHalf + gap + sideHalf,
                width - edge - outerHalf
            };
            final MusicGlassButton[] controls = { shuffleButton, prevButton, playButton, nextButton, repeatButton };
            for (int i = 0; i < controls.length; ++i) {
                final int glassWidth = Math.round(AndroidUtilities.dp(sizes[i]) * scale);
                final int glassHeight = i == 2 ? Math.round(AndroidUtilities.dp(60) * scale) : glassWidth;
                final int viewWidth = glassWidth + AndroidUtilities.dp(6);
                place(controls[i], Math.round(centers[i] - viewWidth / 2f), controlsTop, viewWidth, controlsHeight);
                controls[i].setGlassSize(glassWidth, glassHeight);
            }

            final int timesTop = controlsTop - AndroidUtilities.dp(26);
            final int half = width / 2;
            place(timeCurrent, side, timesTop, half - side, AndroidUtilities.dp(16));
            place(timeRemaining, half, timesTop, half - side, AndroidUtilities.dp(16));
            final int seekTop = timesTop - AndroidUtilities.dp(30);
            final int contentWidth = width - side * 2;
            place(seekBar, side - AndroidUtilities.dp(4), seekTop, contentWidth + AndroidUtilities.dp(8), AndroidUtilities.dp(28));

            infoTop = seekTop - AndroidUtilities.dp(66);
            place(likeButton, rightButton + AndroidUtilities.dp(6), infoTop + AndroidUtilities.dp(6), buttonSize, buttonSize);
            likeButton.setGlassSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
            titleWidth = contentWidth - AndroidUtilities.dp(56);

            final int coverAreaTop = headerBottom + AndroidUtilities.dp(14);
            final int coverArea = infoTop - AndroidUtilities.dp(22) - coverAreaTop;
            final int coverSize = Math.max(AndroidUtilities.dp(120), Math.min(contentWidth, coverArea));
            coverBig.set((width - coverSize) / 2, coverAreaTop + Math.max(0, (coverArea - coverSize) / 2), (width + coverSize) / 2, 0);
            coverBig.bottom = coverBig.top + coverSize;
            place(coverView, coverBig.left, coverBig.top, coverSize, coverSize);
            final float smallSize = AndroidUtilities.dp(52);
            coverSmall.set(side, infoTop + AndroidUtilities.dp(3), side + smallSize, infoTop + AndroidUtilities.dp(3) + smallSize);

            pageRect.set(side, headerBottom + AndroidUtilities.dp(6), right, infoTop - AndroidUtilities.dp(12));
            place(lyricsView, pageRect.left, pageRect.top, pageRect.width(), pageRect.height());
            place(lyricsEmpty, pageRect.left, pageRect.top, pageRect.width(), pageRect.height());
            place(queueView, pageRect.left, pageRect.top, pageRect.width(), pageRect.height());
            place(settingsView, 0, topInset, width, height - topInset);
            applyPage();
        }
    }

    private void setPage(int newPage) {
        final int oldPage = page;
        if (oldPage == newPage) {
            return;
        }
        page = newPage;
        lyricsButton.setActive(newPage == PAGE_LYRICS, false);
        queueButton.setActive(page == PAGE_QUEUE, false);
        if (oldPage == PAGE_QUEUE) {
            queueView.closeKeyboard();
        }
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (page == PAGE_LYRICS && playing != null) {
            loadLyrics(playing, false);
            lyricsView.setTime((long) (playing.getDuration() * playing.audioProgress * 1000));
        }
        if (page == PAGE_QUEUE) {
            queueView.update();
            queueView.scrollToPlaying();
        }
        final float from = pageProgress;
        final float to = page == PAGE_COVER ? 0f : 1f;
        if (pageAnimator != null) {
            pageAnimator.cancel();
        }
        updatePageVisibility(oldPage);
        pageAnimator = ValueAnimator.ofFloat(0f, 1f);
        pageAnimator.setDuration(380);
        pageAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        pageAnimator.addUpdateListener(a -> {
            float value = (float) a.getAnimatedValue();
            pageProgress = AndroidUtilities.lerp(from, to, value);
            if (oldPage == PAGE_COVER || page == PAGE_COVER) {
                value = page == PAGE_COVER ? 0f : 1f;
            }
            pageSwitch = value;
            root.applyPage();
        });
        setPageLayers(true);
        pageAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                pageSwitch = 1f;
                updatePageVisibility(-1);
                setPageLayers(false);
                root.applyPage();
            }
        });
        pageAnimator.start();
    }

    private void setPageLayers(boolean animating) {
        setPageLayer(lyricsView, animating);
        setPageLayer(lyricsEmpty, animating);
        setPageLayer(queueView, animating);
    }

    private static void setPageLayer(View view, boolean animating) {
        final int type = animating && view.getVisibility() == View.VISIBLE ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (view.getLayerType() != type) {
            view.setLayerType(type, null);
        }
    }

    private void updatePageVisibility(int leaving) {
        pageLeaving = leaving;
        final boolean lyricsShown = page == PAGE_LYRICS || leaving == PAGE_LYRICS;
        final boolean queueShown = page == PAGE_QUEUE || leaving == PAGE_QUEUE;
        final boolean hasLyrics = lyricsView.getLyrics() != null;
        lyricsView.setVisibility(lyricsShown && hasLyrics ? View.VISIBLE : View.GONE);
        lyricsEmpty.setVisibility(lyricsShown && !hasLyrics ? View.VISIBLE : View.GONE);
        queueView.setVisibility(queueShown ? View.VISIBLE : View.GONE);
        if (lyricsShown && !hasLyrics && lyricsEmpty.getVisibility() == View.VISIBLE && !lyricsLoading) {
            lyricsEmptyImage.playAnimation();
        }
    }

    private void showLyricsEmpty(String title, String text) {
        lyricsEmptyTitle.setText(title);
        lyricsEmptyText.setText(text);
    }

    private void setLyrics(MusicLyrics lyrics) {
        lyricsView.setLyrics(lyrics);
        if (page == PAGE_LYRICS || pageLeaving == PAGE_LYRICS) {
            updatePageVisibility(pageLeaving);
            root.applyPage();
        }
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (lyrics != null && playing != null) {
            lyricsView.setTime((long) (playing.getDuration() * playing.audioProgress * 1000));
        }
    }

    /** File tags first, then the session cache, then LRCLIB when online lyrics are on. */
    private void loadLyrics(MessageObject messageObject, boolean force) {
        final String key = MusicLyrics.key(messageObject.getMusicTitle(), messageObject.getMusicAuthor(), (long) messageObject.getDuration());
        if (!force && TextUtils.equals(key, lyricsKey) && (lyricsView.getLyrics() != null || lyricsLoading)) {
            return;
        }
        lyricsKey = key;
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        final MusicLyrics fromFile = audioInfo != null ? MusicLyrics.parse(audioInfo.getLyrics(), true) : null;
        if (fromFile != null) {
            setLyrics(fromFile);
            return;
        }
        final MusicLyrics cached = MusicLyrics.cached(key);
        if (cached != null) {
            setLyrics(cached);
            return;
        }
        if (!MusicPlayerConfig.isOnlineLyrics()) {
            setLyrics(null);
            showLyricsEmpty(LocaleController.getString(R.string.SgMusicNoLyrics), LocaleController.getString(R.string.SgMusicNoLyricsOffline));
            return;
        }
        if (MusicLyrics.isKnownMissing(key)) {
            setLyrics(null);
            showLyricsEmpty(LocaleController.getString(R.string.SgMusicNoLyrics), LocaleController.getString(R.string.SgMusicNoLyricsInfo));
            return;
        }
        lyricsLoading = true;
        setLyrics(null);
        showLyricsEmpty(LocaleController.getString(R.string.SgMusicLyricsSearching), LocaleController.getString(R.string.SgMusicLyricsSource));
        MusicLyrics.fetch(messageObject.getMusicTitle(), messageObject.getMusicAuthor(), audioInfo != null ? audioInfo.getAlbum() : null, (long) messageObject.getDuration(), lyrics -> {
            if (!TextUtils.equals(key, lyricsKey)) {
                return;
            }
            lyricsLoading = false;
            if (lyrics != null) {
                setLyrics(lyrics);
                return;
            }
            showLyricsEmpty(LocaleController.getString(R.string.SgMusicNoLyrics), LocaleController.getString(R.string.SgMusicNoLyricsInfo));
            if (page == PAGE_LYRICS) {
                lyricsEmptyImage.playAnimation();
            }
        });
    }

    private void takeCoverColors(Bitmap bitmap) {
        final String key = coverKey;
        MusicCoverColors.take(key, bitmap, colors -> {
            if (!TextUtils.equals(key, coverKey)) {
                return;
            }
            MusicCoverColors.notifyChanged();
            final MusicCoverColors shown = MusicPlayerConfig.isCoverColorsFull() ? colors : MusicCoverColors.theme();
            background.setColors(shown, isShowing());
            seekBar.setColor(shown.accent);
        });
    }

    private void onSettingsChanged() {
        final MusicCoverColors colors = MusicCoverColors.get(coverKey);
        final MusicCoverColors shown = MusicPlayerConfig.isCoverColorsFull() && colors != null ? colors : MusicCoverColors.theme();
        background.setColors(shown, true);
        seekBar.setColor(shown.accent);
        MusicCoverColors.notifyChanged();
        if (page == PAGE_LYRICS && lyricsView.getLyrics() == null) {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null) {
                loadLyrics(playing, true);
            }
        }
    }

    private void showSettings(boolean show) {
        if (settingsShown == show) {
            return;
        }
        settingsShown = show;
        if (show) {
            settingsView.update();
            settingsView.setVisibility(View.VISIBLE);
            settingsView.setAlpha(0f);
            settingsView.setTranslationY(AndroidUtilities.dp(40));
        }
        settingsView.animate()
            .alpha(show ? 1f : 0f)
            .translationY(show ? 0 : AndroidUtilities.dp(40))
            .setDuration(300)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .withEndAction(() -> {
                if (!settingsShown) {
                    settingsView.setVisibility(View.GONE);
                }
            })
            .start();
    }

    private void showMenu() {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null) {
            return;
        }
        ItemOptions.makeOptions(root, dark, menuButton)
            .addIf(!noforwards, R.drawable.msg_forward, LocaleController.getString(R.string.Forward), () -> forward(messageObject))
            .addIf(!noforwards, R.drawable.msg_shareout, LocaleController.getString(R.string.ShareFile), () -> share(messageObject))
            .addIf(!noforwards, R.drawable.msg_download, LocaleController.getString(R.string.SaveToMusic), () -> saveToMusic(messageObject))
            .addIf(messageObject.getId() > 0, R.drawable.msg_message, LocaleController.getString(R.string.ShowInChat), () -> showInChat(messageObject))
            .addIf(isMyList(), R.drawable.msg_delete, LocaleController.getString(R.string.ProfilePlaylistRemoveFromProfile), true, () -> saveToProfile(messageObject, false, false))
            .addGap()
            .add(R.drawable.msg_settings, LocaleController.getString(R.string.SgMusicSettings), () -> showSettings(true))
            .setGravity(Gravity.RIGHT)
            .show();
    }

    private void showSpeeds() {
        final float current = MediaController.getInstance().getPlaybackSpeed(true);
        final ItemOptions options = ItemOptions.makeOptions(root, dark, speedButton);
        final float[] speeds = { 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f };
        for (final float speed : speeds) {
            options.addChecked(Math.abs(current - speed) < 0.01f, formatSpeed(speed) + "x", () -> MediaController.getInstance().setPlaybackSpeed(true, speed));
        }
        options.setGravity(Gravity.LEFT);
        options.show();
    }

    private static String formatSpeed(float speed) {
        String value = String.valueOf(speed);
        if (value.endsWith(".0")) {
            value = value.substring(0, value.length() - 2);
        }
        return value;
    }

    private void updateSpeed() {
        final float speed = MediaController.getInstance().getPlaybackSpeed(true);
        speedButton.setText(formatSpeed(Math.round(speed * 100f) / 100f) + "x");
        speedButton.setActive(Math.abs(speed - 1f) > 0.01f, true);
    }

    private void updateOrderButtons(boolean animated) {
        shuffleButton.setActive(SharedConfig.shuffleMusic, animated);
        shuffleButton.setIcon(SharedConfig.shuffleMusic ? R.drawable.player_new_shuffle : R.drawable.player_new_shuffle_off);
        final int repeatMode = SharedConfig.repeatMode;
        repeatButton.setActive(repeatMode != 0, animated);
        repeatButton.setIcon(repeatMode == 2 ? R.drawable.player_new_repeatone : repeatMode == 1 ? R.drawable.player_new_repeatall : R.drawable.player_new_repeat_off);
        shuffleButton.setContentDescription(LocaleController.getString(R.string.ShuffleList));
        repeatButton.setContentDescription(LocaleController.getString(repeatMode == 2 ? R.string.RepeatSong : R.string.RepeatList));
    }

    public void updateRepeatButton() {
        updateOrderButtons(true);
    }

    private void toggleShuffle() {
        MediaController.getInstance().setPlaybackOrderType(SharedConfig.shuffleMusic ? 0 : 2);
        updateOrderButtons(true);
        queueView.update();
        updatePlaylistTitles(MediaController.getInstance().getPlayingMessageObject());
    }

    private void togglePlay() {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null) {
            return;
        }
        if (MediaController.getInstance().isMessagePaused()) {
            MediaController.getInstance().playMessage(messageObject);
        } else {
            MediaController.getInstance().pauseMessage(messageObject);
        }
    }

    private boolean isLiked(MessageObject messageObject) {
        final TLRPC.Document document = messageObject != null ? messageObject.getDocument() : null;
        return document != null && MessagesController.getInstance(currentAccount).getSavedMusicIds().ids.contains(document.id);
    }

    private boolean isMyList() {
        return savedMusicList != null && savedMusicList.dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private void toggleLike() {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null || messageObject.getDocument() == null) {
            return;
        }
        saveToProfile(messageObject, !isLiked(messageObject), false);
    }

    private void updateLike(boolean animated) {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        final boolean available = messageObject != null && messageObject.getDocument() != null && !noforwards && messageObject.getDocument().id != 0;
        likeButton.setVisibility(available ? View.VISIBLE : View.INVISIBLE);
        final boolean liked = isLiked(messageObject);
        likeButton.setIcon(liked ? R.drawable.media_like_active : R.drawable.media_like);
        likeButton.setIconColor(liked ? 0xFFFF4D6D : 0xFFFFFFFF);
        likeButton.setContentDescription(LocaleController.getString(liked ? R.string.AudioRemoveFromProfile : R.string.AudioAddToProfile));
        if (animated) {
            likeButton.setScaleX(0.8f);
            likeButton.setScaleY(0.8f);
            likeButton.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        }
    }

    private static ImageLocation thumbLocation(MessageObject messageObject) {
        final TLRPC.Document document = messageObject.getDocument();
        final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
        if (thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive) {
            return ImageLocation.getForDocument(thumb, document);
        }
        final String artworkUrl = messageObject.getArtworkUrl(true);
        return artworkUrl != null ? ImageLocation.getForPath(artworkUrl) : null;
    }

    private void updateCover(MessageObject messageObject) {
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        coverKey = MusicCoverColors.keyOf(messageObject);
        placeholder = null;
        if (audioInfo != null && audioInfo.getCover() != null) {
            coverView.setImageBitmap(audioInfo.getCover());
            takeCoverColors(audioInfo.getCover());
            return;
        }
        final String artworkUrl = messageObject.getArtworkUrl(false);
        final ImageLocation thumb = thumbLocation(messageObject);
        placeholder = new MusicCoverColors.PlaceholderDrawable();
        placeholder.setRadius(coverView.getImageReceiver().getRoundRadius()[0]);
        takeCoverColors(null);
        if (!TextUtils.isEmpty(artworkUrl)) {
            coverView.setImage(ImageLocation.getForPath(artworkUrl), null, thumb, null, placeholder, null, 0, 1, messageObject);
        } else if (thumb != null) {
            coverView.setImage(thumb, null, null, null, placeholder, null, 0, 1, messageObject);
        } else {
            coverView.setImageDrawable(placeholder);
        }
    }

    private void updatePlaylistTitles(MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (savedMusicList != null) {
            final long dialogId = savedMusicList.dialogId;
            playlistTitle.setText(dialogId == selfId ? LocaleController.getString(R.string.ProfilePlaylistTitleMine) : LocaleController.formatString(R.string.ProfilePlaylistTitle, DialogObject.getShortName(dialogId)));
        } else {
            final long dialogId = messageObject.getDialogId();
            playlistTitle.setText(dialogId == selfId ? LocaleController.getString(R.string.SavedMessages) : DialogObject.getShortName(currentAccount, dialogId));
        }
        final ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaylist();
        final int size = playlist != null ? playlist.size() : 0;
        int index = -1;
        for (int i = 0; i < size; ++i) {
            final MessageObject m = playlist.get(i);
            if (m != null && m.getId() == messageObject.getId() && m.getDialogId() == messageObject.getDialogId()) {
                index = i;
                break;
            }
        }
        if (size <= 1 || index < 0) {
            playlistSubtitle.setVisibility(View.GONE);
        } else {
            final int number = SharedConfig.playOrderReversed == (savedMusicList == null) ? index + 1 : size - index;
            playlistSubtitle.setText(LocaleController.formatString(R.string.SgMusicTrackOf, number, size));
            playlistSubtitle.setVisibility(View.VISIBLE);
        }
    }

    private void updateTimes(MessageObject messageObject, float progress) {
        final int duration = (int) messageObject.getDuration();
        final int time = (int) (duration * progress);
        if (time != lastTime) {
            lastTime = time;
            timeCurrent.setText(AndroidUtilities.formatShortDuration(time));
        }
        final int remaining = Math.max(0, duration - time);
        if (remaining != lastRemaining) {
            lastRemaining = remaining;
            timeRemaining.setText(duration > 0 ? "−" + AndroidUtilities.formatShortDuration(remaining) : "-:--");
        }
    }

    private void updateProgress(MessageObject messageObject) {
        if (!seekBar.isDragging()) {
            seekBar.setProgress(messageObject.audioProgress);
            updateTimes(messageObject, messageObject.audioProgress);
        }
        if (MediaController.getInstance().isStreamingCurrentAudio()) {
            seekBar.setBufferedProgress(FileLoader.getInstance(currentAccount).getBufferedProgressFromPosition(messageObject.audioProgress, FileLoader.getAttachFileName(messageObject.getDocument())));
        } else {
            seekBar.setBufferedProgress(1f);
        }
        if (page == PAGE_LYRICS) {
            lyricsView.setTime((long) (messageObject.getDuration() * messageObject.audioProgress * 1000));
        }
    }

    private void updateTitle(boolean reset) {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if ((messageObject == null && reset) || (messageObject != null && !messageObject.isMusic())) {
            dismiss();
            return;
        }
        if (messageObject == null) {
            return;
        }
        final boolean same = messageObject == lastMessageObject;
        lastMessageObject = messageObject;
        final long dialogId = messageObject.getDialogId();
        final MessagesController messagesController = MessagesController.getInstance(currentAccount);
        noforwards = (dialogId < 0 && messagesController.isPeerNoForwards(dialogId)) || messagesController.isPeerNoForwards(messageObject.getDialogId()) || messageObject.messageOwner.noforwards;
        menuButton.setVisibility(messageObject.eventId != 0 || messageObject.getId() <= -2000000000 ? View.INVISIBLE : View.VISIBLE);
        final boolean paused = MediaController.getInstance().isMessagePaused();
        playPauseDrawable.setPause(!paused, same);
        playButton.setContentDescription(LocaleController.getString(paused ? R.string.AccActionPlay : R.string.AccActionPause));
        seekBar.setPlaying(!paused);
        if (!same) {
            trackTitle.setText(messageObject.getMusicTitle());
            trackAuthor.setText(messageObject.getMusicAuthor());
            updatePlaylistTitles(messageObject);
            updateCover(messageObject);
            lyricsView.setLyrics(null);
            lyricsKey = null;
            if (page == PAGE_LYRICS) {
                loadLyrics(messageObject, false);
            }
            lastRemaining = -1;
            lastTime = -1;
        }
        updateLike(false);
        updateProgress(messageObject);
    }

    // region menu actions

    private void forward(MessageObject messageObject) {
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", true);
        final DialogsActivity fragment = new DialogsActivity(args);
        final ArrayList<MessageObject> messages;
        final TLRPC.TL_document document;
        if (messageObject.getId() < 0) {
            if (!(messageObject.getDocument() instanceof TLRPC.TL_document)) {
                return;
            }
            messages = null;
            document = (TLRPC.TL_document) messageObject.getDocument();
        } else {
            messages = new ArrayList<>();
            messages.add(messageObject);
            document = null;
        }
        final MessagesController.SavedMusicList musicList = savedMusicList;
        fragment.setDelegate((fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.size() > 1 || dids.get(0).dialogId == UserConfig.getInstance(currentAccount).getClientUserId() || message != null || messages == null) {
                for (int a = 0; a < dids.size(); ++a) {
                    final long did = dids.get(a).dialogId;
                    if (message != null) {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did, null, null, null, true, null, null, null, true, 0, 0, null, false));
                    }
                    if (messages != null) {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(messages, did, false, false, true, 0, 0);
                    } else {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(document, null, messageObject.messageOwner.attachPath, did, null, null, null, null, null, null, notify, scheduleDate, 0, 0, musicList, null, false, false));
                    }
                }
                fragment1.finishFragment();
                final BaseFragment lastFragment = LaunchActivity.getLastFragment();
                if (lastFragment != null) {
                    final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                    BulletinFactory.of(lastFragment).createSimpleBulletin(
                        R.raw.forward,
                        dids.size() == 1 && dids.get(0).dialogId == selfId ?
                            LocaleController.getString(R.string.FwdMessageToSavedMessages) :
                        dids.size() == 1 && dids.get(0).dialogId > 0 ?
                            LocaleController.formatString(R.string.FwdMessageToUser, DialogObject.getShortName(dids.get(0).dialogId)) :
                        dids.size() == 1 ?
                            LocaleController.formatString(R.string.FwdMessageToGroup, DialogObject.getShortName(dids.get(0).dialogId)) :
                            LocaleController.formatPluralStringComma("FwdMessageToManyChats", dids.size())
                    ).show();
                }
            } else {
                final MessagesStorage.TopicKey topicKey = dids.get(0);
                final long did = topicKey.dialogId;
                final Bundle chatArgs = new Bundle();
                chatArgs.putBoolean("scrollToTopOnResume", true);
                if (DialogObject.isEncryptedDialog(did)) {
                    chatArgs.putInt("enc_id", DialogObject.getEncryptedChatId(did));
                } else if (DialogObject.isUserDialog(did)) {
                    chatArgs.putLong("user_id", did);
                } else {
                    chatArgs.putLong("chat_id", -did);
                }
                final ChatActivity chatActivity = new ChatActivity(chatArgs);
                if (topicKey.topicId != 0) {
                    ForumUtilities.applyTopic(chatActivity, topicKey);
                }
                if (parentActivity.presentFragment(chatActivity, true, false)) {
                    chatActivity.showFieldPanelForForward(true, messages);
                    if (topicKey.topicId != 0) {
                        fragment1.removeSelfFromStack();
                    }
                } else {
                    fragment1.finishFragment();
                }
            }
            return true;
        });
        parentActivity.presentFragment(fragment);
        dismiss();
    }

    private void share(MessageObject messageObject) {
        try {
            File file = null;
            if (!TextUtils.isEmpty(messageObject.messageOwner.attachPath)) {
                file = new File(messageObject.messageOwner.attachPath);
                if (!file.exists()) {
                    file = null;
                }
            }
            if (file == null) {
                file = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
            }
            if (!file.exists()) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(parentActivity);
                builder.setTitle(LocaleController.getString(R.string.AppName));
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                builder.setMessage(LocaleController.getString(R.string.PleaseDownload));
                builder.show();
                return;
            }
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(messageObject.getMimeType());
            try {
                intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", file));
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignore) {
                intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
            }
            parentActivity.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void saveToMusic(MessageObject messageObject) {
        if ((Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE) && parentActivity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            parentActivity.requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, 4);
            return;
        }
        String fileName = FileLoader.getDocumentFileName(messageObject.getDocument());
        if (TextUtils.isEmpty(fileName)) {
            fileName = messageObject.getFileName();
        }
        String path = messageObject.messageOwner.attachPath;
        if (path != null && path.length() > 0 && !new File(path).exists()) {
            path = null;
        }
        if (path == null || path.length() == 0) {
            path = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner).toString();
        }
        MediaController.saveFile(path, parentActivity, 3, fileName, messageObject.getDocument() != null ? messageObject.getDocument().mime_type : "", uri -> BulletinFactory.of(root, dark).createDownloadBulletin(BulletinFactory.FileType.AUDIO).show());
    }

    private void showInChat(MessageObject messageObject) {
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }
        final Bundle args = new Bundle();
        long did = messageObject.getDialogId();
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
        } else if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did);
        } else {
            final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
            if (chat != null && chat.migrated_to != null) {
                args.putLong("migrated_to", did);
                did = -chat.migrated_to.channel_id;
            }
            args.putLong("chat_id", -did);
        }
        args.putInt("message_id", messageObject.getId());
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.closeChats);
        parentActivity.presentFragment(new ChatActivity(args), false, false);
        dismiss();
    }

    /** Saves the track to the profile playlist or removes it (the like button and the menu); refetches a stale file reference once. */
    private void saveToProfile(MessageObject messageObject, boolean save, boolean triedFileRef) {
        final TLRPC.Document document = messageObject.getDocument();
        if (document == null) {
            return;
        }
        final long documentId = document.id;
        final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = !save;
        req.id = new TLRPC.TL_inputDocument();
        req.id.id = documentId;
        req.id.access_hash = document.access_hash;
        req.id.file_reference = document.file_reference != null ? document.file_reference : new byte[0];
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
            if (err != null && FileRefController.isFileRefError(err.text) && !triedFileRef && messageObject.getId() > 0) {
                refetchMessage(messageObject, fresh -> saveToProfile(fresh, save, true));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> onSavedToProfile(err, documentId, save, document, messageObject));
        });
    }

    private void onSavedToProfile(TLRPC.TL_error err, long documentId, boolean save, TLRPC.Document document, MessageObject messageObject) {
        if (err != null) {
            BulletinFactory.of(root, dark).showForError(err);
            return;
        }
        MessagesController.getInstance(currentAccount).getSavedMusicIds().update(documentId, save);
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        final TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(selfId);
        if (userFull != null) {
            if (save) {
                userFull.flags2 |= TLObject.FLAG_21;
                userFull.saved_music = document;
            } else if (userFull.saved_music != null && userFull.saved_music.id == documentId) {
                userFull.flags2 &= ~TLObject.FLAG_21;
                userFull.saved_music = null;
            }
            MessagesStorage.getInstance(currentAccount).updateUserInfo(userFull, true);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.profileMusicUpdated, selfId);
        }
        if (!save && isMyList() && savedMusicList != null) {
            savedMusicList.remove(messageObject);
            if (savedMusicList.list.isEmpty()) {
                MediaController.getInstance().cleanup();
                dismiss();
                return;
            }
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.musicListLoaded, savedMusicList);
        }
        updateLike(true);
        BulletinFactory.of(root, dark).createSimpleBulletin(save ? R.raw.saved_messages : R.raw.ic_delete, LocaleController.getString(save ? R.string.AudioSaveToMyProfileSaved : R.string.AudioSaveToMyProfileUnsaved)).show();
    }

    private void refetchMessage(MessageObject messageObject, Utilities.Callback<MessageObject> done) {
        final int messageId = messageObject.getId();
        final TLObject request;
        if (messageObject.getDialogId() >= 0) {
            final TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
            req.id.add(messageId);
            request = req;
        } else {
            final TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.channel = MessagesController.getInstance(currentAccount).getInputChannel(-messageObject.getDialogId());
            req.id.add(messageId);
            request = req;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (res, err) -> {
            TLRPC.Message found = null;
            if (res instanceof TLRPC.messages_Messages) {
                final ArrayList<TLRPC.Message> messages = ((TLRPC.messages_Messages) res).messages;
                for (int i = 0; i < messages.size(); ++i) {
                    if (messages.get(i).id == messageId) {
                        found = messages.get(i);
                        break;
                    }
                }
            }
            if (found != null) {
                done.run(new MessageObject(currentAccount, found, false, true));
            } else {
                AndroidUtilities.runOnUIThread(() -> {
                    if (err != null) {
                        BulletinFactory.of(root, dark).showForError(err);
                    } else {
                        BulletinFactory.of(root, dark).createErrorBulletin(LocaleController.formatString(R.string.UnknownErrorCode, "CLIENT_MESSAGE_NOT_FOUND")).show();
                    }
                });
            }
        });
    }

    // endregion

    @Override
    protected boolean canDismissWithSwipe() {
        if (settingsShown) {
            return false;
        }
        return !(page == PAGE_QUEUE && queueView.canScrollUp());
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged || id == NotificationCenter.messagePlayingDidReset) {
            updateTitle(id == NotificationCenter.messagePlayingDidReset && (Boolean) args[1]);
            queueView.update();
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject != null && messageObject.isMusic()) {
                updateProgress(messageObject);
            }
        } else if (id == NotificationCenter.messagePlayingSpeedChanged) {
            updateSpeed();
        } else if (id == NotificationCenter.musicDidLoad || id == NotificationCenter.moreMusicDidLoad) {
            savedMusicList = MediaController.getInstance().currentSavedMusicList;
            queueView.update();
            updatePlaylistTitles(MediaController.getInstance().getPlayingMessageObject());
        } else if (id == NotificationCenter.musicIdsLoaded) {
            updateLike(false);
        } else if (id == NotificationCenter.fileLoaded) {
            final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject == null || messageObject.getDocument() == null || args.length == 0 || !TextUtils.equals((String) args[0], FileLoader.getAttachFileName(messageObject.getDocument()))) {
                return;
            }
            updateCover(messageObject);
            if (page == PAGE_LYRICS && lyricsView.getLyrics() == null) {
                loadLyrics(messageObject, true);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (settingsShown) {
            showSettings(false);
        } else if (page != PAGE_COVER) {
            setPage(PAGE_COVER);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void show() {
        super.show();
        instance = this;
    }

    @Override
    public void dismiss() {
        if (instance == this) {
            instance = null;
        }
        queueView.closeKeyboard();
        super.dismiss();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.moreMusicDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingSpeedChanged);
    }
}
