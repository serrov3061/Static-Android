package com.exteragram.messenger.nowplaying.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.api.dto.NowPlayingDTO;
import com.exteragram.messenger.nowplaying.ServiceEmoji;
import com.exteragram.messenger.utils.ui.UIUtil;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.List;

/**
 * "Now playing" card in the profile list (port of exteraGram's Kotlin NowPlayingCard).
 * The preview player (exteraGram: media3 ExoPlayer) uses android.media.MediaPlayer here; previews come only from
 * the live API, which is off on StaticGram, so for profile music the play button is hidden as in exteraGram.
 */
@SuppressLint("ViewConstructor")
public abstract class NowPlayingCard extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private final AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    private AudioFocusRequest audioFocusRequest;
    private final FrameLayout cardLayout;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable emoji;
    private final BackupImageView imageView;
    private final SimpleTextView nameView;
    private final SimpleTextView artistView;
    private final SimpleTextView albumView;
    private final ImageView playPauseButton;
    private final PlayPauseDrawable playPauseDrawable;
    private NowPlayingCardData nowPlayingCardData;
    private long currentDocId = -1;
    private String currentPreviewUrl;
    private MediaPlayer player;
    private boolean playerPrepared;
    private boolean isPlaying;
    private boolean resumeOnFocusGain;

    public abstract void onSavedMusicClick();

    public NowPlayingCard(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.audioFocusChangeListener = this::onAudioFocusChange;
        setClickable(false);
        setWillNotDraw(false);

        cardLayout = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (nowPlayingCardData != null) {
                    Integer accent = nowPlayingCardData.getAccentColor();
                    emoji.setColor(accent != null ? accent : getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                    UIUtil.INSTANCE.drawNowPlayingPattern(canvas, emoji, getWidth(), getHeight(), nowPlayingCardData.getCoverBitmap() == null ? 0.4f : 1.0f);
                }
                super.dispatchDraw(canvas);
            }
        };
        GradientDrawable background = new GradientDrawable() {
            @Override
            protected void onBoundsChange(Rect r) {
                super.onBoundsChange(r);
                setGradientRadius(r.width() * 2.0f);
            }
        };
        background.setCornerRadius(AndroidUtilities.dpf2(ExteraConfig.getSectionRadiusDp()));
        cardLayout.setBackground(background);
        cardLayout.setClipToOutline(true);
        cardLayout.setOutlineProvider(ViewOutlineProviderImpl.fromDrawable(background));
        cardLayout.setClickable(true);
        ScaleStateListAnimator.apply(cardLayout, 0.035f, 1.5f);
        addView(cardLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        emoji = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(cardLayout, false, AndroidUtilities.dp(20), AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        cardLayout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 119, 12, 12, 12, 12));

        imageView = new BackupImageView(context);
        imageView.setClipToOutline(true);
        final float coverRadius = getCoverCornerRadius();
        imageView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), coverRadius);
            }
        });
        row.addView(imageView, LayoutHelper.createLinear(64, 64, 19, 0, 0, 12, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f, 16));

        nameView = new SimpleTextView(context);
        nameView.setGravity(19);
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setTextSize(16);
        nameView.setTypeface(AndroidUtilities.bold());
        NotificationCenter.listenEmojiLoading(nameView);
        texts.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 22));

        artistView = createSubtitle(context);
        texts.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 19, 0, 2, 0, 0));

        albumView = createSubtitle(context);
        texts.addView(albumView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 19, 0, 2, 0, 0));

        playPauseDrawable = new PlayPauseDrawable(16);
        playPauseDrawable.setPause(false);
        playPauseDrawable.setColor(0xFFFFFFFF);
        playPauseButton = new ImageView(context);
        ScaleStateListAnimator.apply(playPauseButton);
        playPauseButton.setScaleType(ImageView.ScaleType.CENTER);
        playPauseButton.setImageDrawable(playPauseDrawable);
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        row.addView(playPauseButton, LayoutHelper.createLinear(32, 32, 16, 8, 0, 8, 0));
    }

    private SimpleTextView createSubtitle(Context context) {
        SimpleTextView view = new SimpleTextView(context);
        view.setGravity(19);
        view.setTypeface(android.graphics.Typeface.DEFAULT);
        view.setTextSize(14);
        view.setTextColor(0xFFFFFFFF);
        view.setAlpha(0.6f);
        NotificationCenter.listenEmojiLoading(view);
        return view;
    }

    private float getCoverCornerRadius() {
        return AndroidUtilities.dpf2(Math.max(ExteraConfig.getSectionRadiusDp() - 12, 8));
    }

    public final void set(NowPlayingCardData cardData) {
        nowPlayingCardData = cardData;
        final NowPlayingDTO dto = cardData.getNowPlayingDTO();
        List<String> artists = dto.getArtists();
        artistView.setText(null);
        nameView.setText(null);
        albumView.setText(null);
        if (artists == null || artists.isEmpty()) {
            artistView.setText(LocaleController.getString(R.string.AudioUnknownArtist));
        } else {
            artistView.setText(TextUtils.join(", ", artists));
        }
        nameView.setText(Emoji.replaceEmoji(dto.getTrackName(), nameView.getPaint().getFontMetricsInt(), false));
        String album = dto.getAlbumName();
        albumView.setVisibility(!TextUtils.isEmpty(album) && !TextUtils.equals(dto.getTrackName(), album) ? VISIBLE : GONE);
        if (albumView.getVisibility() == VISIBLE) {
            albumView.setText(Emoji.replaceEmoji(album, albumView.getPaint().getFontMetricsInt(), false));
        }
        setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);

        Drawable bg = cardLayout.getBackground();
        if (bg instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable) bg;
            gradient.mutate();
            gradient.setDither(true);
            gradient.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            gradient.setGradientCenter(1.0f, 0.5f);
            Integer bgColor = cardData.getBackgroundColor();
            int color = bgColor != null ? bgColor : getThemedColor(Theme.key_windowBackgroundWhite);
            gradient.setColors(new int[]{color, bgColor != null ? UIUtil.INSTANCE.adjustHsl(color, 1.5f, -1.0f) : color});
        }

        playPauseButton.setVisibility(!TextUtils.isEmpty(dto.getPreviewUrl()) && !dto.isTelegram() ? VISIBLE : GONE);
        Integer accent = cardData.getAccentColor();
        playPauseButton.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(32), accent != null ? accent : getThemedColor(Theme.key_featuredStickers_addButton)));

        long documentId = cardData.getUserEmoji() > 0 && dto.isTelegram() ? cardData.getUserEmoji() : ServiceEmoji.fromString(dto.getPlatform()).getDocumentId();
        if (documentId != currentDocId) {
            currentDocId = documentId;
            emoji.set(documentId, true);
        }

        cardLayout.setOnClickListener(v -> {
            if (dto.isTelegram()) {
                onSavedMusicClick();
            } else if (!TextUtils.isEmpty(dto.getSongUrl())) {
                // exteraGram: opens the song only for supporters (BadgesController), otherwise SupporterBottomSheet
                Browser.openUrl(getContext(), dto.getSongUrl());
            }
        });
        cardLayout.setOnLongClickListener(v -> {
            if (dto.isTelegram()) {
                onSavedMusicClick();
            } else if (!TextUtils.isEmpty(dto.getSongUrl())) {
                Browser.openUrl(getContext(), dto.getSongUrl());
            }
            return true;
        });

        if (cardData.getImageLocation() != null) {
            imageView.setImage(cardData.getImageLocation(), null, cardData.getCoverBitmap() != null ? new BitmapDrawable(getContext().getResources(), cardData.getCoverBitmap()) : null, 0, null);
            int textColor = cardData.getCoverBitmap() != null ? 0xFFFFFFFF : getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
            setTextsColor(textColor);
        } else {
            imageView.setImageResource(R.drawable.nocover, getThemedColor(Theme.key_player_button));
            setTextsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        String previewUrl = dto.getPreviewUrl();
        if (!TextUtils.equals(previewUrl, currentPreviewUrl)) {
            currentPreviewUrl = previewUrl;
            initializePlayer();
        }
        invalidate();
    }

    private void setTextsColor(int color) {
        artistView.setTextColor(color);
        nameView.setTextColor(color);
        albumView.setTextColor(color);
    }

    private void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (player != null && isPlaying) {
                resumeOnFocusGain = true;
                pausePlayer();
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            if (player != null && isPlaying) {
                pausePlayer();
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocusGain) {
            startPlayer();
            resumeOnFocusGain = false;
        }
    }

    private void initializePlayer() {
        releasePlayer();
        if (nowPlayingCardData == null) {
            return;
        }
        String previewUrl = nowPlayingCardData.getNowPlayingDTO().getPreviewUrl();
        if (TextUtils.isEmpty(previewUrl) || nowPlayingCardData.getNowPlayingDTO().isTelegram()) {
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(previewUrl);
            mp.setOnPreparedListener(p -> playerPrepared = true);
            mp.setOnCompletionListener(p -> {
                isPlaying = false;
                updatePlayPauseButton();
                abandonAudioFocus();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                isPlaying = false;
                updatePlayPauseButton();
                abandonAudioFocus();
                return true;
            });
            mp.prepareAsync();
            player = mp;
        } catch (Exception e) {
            FileLog.e(e);
            player = null;
        }
    }

    private void togglePlayPause() {
        if (player == null || !playerPrepared) {
            return;
        }
        if (isPlaying) {
            pausePlayer();
            abandonAudioFocus();
        } else if (requestAudioFocus()) {
            startPlayer();
        }
    }

    private void startPlayer() {
        if (player == null || !playerPrepared) {
            return;
        }
        try {
            player.start();
            isPlaying = true;
        } catch (Exception e) {
            FileLog.e(e);
            isPlaying = false;
        }
        updatePlayPauseButton();
    }

    private void pausePlayer() {
        try {
            if (player != null) {
                player.pause();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        isPlaying = false;
        updatePlayPauseButton();
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < 26) {
            return audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build();
        return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            }
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
    }

    private void updatePlayPauseButton() {
        playPauseDrawable.setPause(isPlaying);
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        player = null;
        playerPrepared = false;
        isPlaying = false;
        updatePlayPauseButton();
        abandonAudioFocus();
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        emoji.attach();
        if (player == null && nowPlayingCardData != null) {
            initializePlayer();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        emoji.detach();
        releasePlayer();
    }
}
