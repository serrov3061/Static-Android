package org.telegram.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Player settings page shown over the player: cover colors, online lyrics and the switch back to Telegram's player.
 * Painted from the player's own cover palette (same background drawable, white text, translucent white plates,
 * accent-colored toggles), so it looks the same in light and dark app themes and follows cover changes frame by frame.
 */
public class MusicPlayerSettingsView extends FrameLayout {

    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0x99FFFFFF;
    private static final int PLATE = 0x14FFFFFF;
    private static final int DIVIDER = 0x14FFFFFF;
    private static final int RIPPLE = 0x1FFFFFFF;
    private static final int TRACK_OFF = 0x29FFFFFF;

    public interface Action {
        boolean toggle();
    }

    private final Runnable onChanged;
    private final MusicCoverColors.BackgroundDrawable background;
    private final LinearLayout list;
    private final ArrayList<Row> rows = new ArrayList<>();
    private final Row coverFull;
    private final Row coverMini;
    private final Row onlineLyrics;
    private final Row classic;
    private int accent;

    public MusicPlayerSettingsView(Context context, MusicCoverColors.BackgroundDrawable background, Runnable onChanged, Runnable onBack) {
        super(context);
        this.onChanged = onChanged;
        this.background = background;
        this.accent = background.getColors().accent;
        setWillNotDraw(false);
        setClickable(true);

        final FrameLayout header = new FrameLayout(context);
        final ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setColorFilter(TEXT);
        back.setBackground(Theme.createSelectorDrawable(RIPPLE, Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> onBack.run());
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        header.addView(back, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.SgMusicSettings));
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 68, 0, 16, 0));
        addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP));

        final ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(OVER_SCROLL_NEVER);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), AndroidUtilities.dp(24));
        scrollView.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 56, 0, 0));

        section(context, LocaleController.getString(R.string.SgMusicAppearance));
        final Group appearance = group(context);
        coverFull = row(context, appearance, LocaleController.getString(R.string.SgMusicCoverColorsFull), LocaleController.getString(R.string.SgMusicCoverColorsFullInfo), MusicPlayerConfig::toggleCoverColorsFull);
        coverMini = row(context, appearance, LocaleController.getString(R.string.SgMusicCoverColorsMini), LocaleController.getString(R.string.SgMusicCoverColorsMiniInfo), MusicPlayerConfig::toggleCoverColorsMini);
        section(context, LocaleController.getString(R.string.SgMusicLyrics));
        onlineLyrics = row(context, group(context), LocaleController.getString(R.string.SgMusicOnlineLyrics), LocaleController.getString(R.string.SgMusicOnlineLyricsInfo), MusicPlayerConfig::toggleOnlineLyrics);
        section(context, LocaleController.getString(R.string.SgMusicPlayerSection));
        classic = row(context, group(context), LocaleController.getString(R.string.SgMusicClassic), LocaleController.getString(R.string.SgMusicClassicInfo), MusicPlayerConfig::toggleClassicPlayer);
        update();
    }

    public void update() {
        coverFull.setChecked(MusicPlayerConfig.isCoverColorsFull());
        coverMini.setChecked(MusicPlayerConfig.isCoverColorsMini());
        onlineLyrics.setChecked(MusicPlayerConfig.isOnlineLyrics());
        classic.setChecked(!MusicPlayerConfig.isPlayerEnabled());
        updateColors();
    }

    /** Follows the player's background palette; called on every frame of a cover color change. */
    public void updateColors() {
        final int color = background.getColors().accent;
        if (color != accent) {
            accent = color;
            for (int i = 0; i < rows.size(); ++i) {
                rows.get(i).check.setAccent(accent);
            }
        }
        if (getVisibility() == VISIBLE) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // the exact player background (cover gradient + glow), aligned to the player's root
        canvas.save();
        canvas.translate(-getLeft(), -getTop());
        background.draw(canvas);
        canvas.restore();
    }

    private void section(Context context, String text) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(TEXT_SECONDARY);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(AndroidUtilities.bold());
        list.addView(view, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 12, 18, 12, 8));
    }

    private Group group(Context context) {
        final Group group = new Group(context);
        group.setClipToOutline(true);
        group.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(20));
            }
        });
        list.addView(group, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return group;
    }

    private Row row(Context context, Group group, String title, String info, Action action) {
        final Row row = new Row(context, title, info);
        row.check.setAccent(accent);
        row.setOnClickListener(v -> {
            row.setChecked(action.toggle());
            onChanged.run();
        });
        group.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rows.add(row);
        return row;
    }

    /** Rounded translucent white plate with hairline dividers between rows. */
    public static class Group extends LinearLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dividerPaint = new Paint();
        private final RectF rect = new RectF();

        public Group(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            paint.setColor(PLATE);
            dividerPaint.setColor(DIVIDER);
            dividerPaint.setStrokeWidth(Math.max(1, AndroidUtilities.dp(0.66f)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, AndroidUtilities.dp(20), AndroidUtilities.dp(20), paint);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            final float left = AndroidUtilities.dp(16);
            for (int i = 1; i < getChildCount(); ++i) {
                final float y = getChildAt(i).getTop();
                canvas.drawLine(left, y, getWidth(), y, dividerPaint);
            }
        }
    }

    public static class Row extends LinearLayout {
        private final Toggle check;

        public Row(Context context, String title, String info) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
            setBackground(Theme.createSelectorDrawable(RIPPLE, Theme.RIPPLE_MASK_ALL));

            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            final TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(TEXT);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            texts.addView(titleView);
            if (!TextUtils.isEmpty(info)) {
                final TextView infoView = new TextView(context);
                infoView.setText(info);
                infoView.setTextColor(TEXT_SECONDARY);
                infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                texts.addView(infoView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            }
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            check = new Toggle(context);
            addView(check, LayoutHelper.createLinear(46, 28, Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
        }

        public void setChecked(boolean checked) {
            check.setChecked(checked, isAttachedToWindow());
        }
    }

    /** Pill toggle in the player palette: translucent white track when off, cover accent when on, white knob. */
    public static class Toggle extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final AnimatedFloat progress = new AnimatedFloat(this, 0, 260, CubicBezierInterpolator.EASE_OUT_QUINT);
        private boolean checked;
        private int accent = 0xFFFFFFFF;

        public Toggle(Context context) {
            super(context);
            thumbPaint.setColor(0xFFFFFFFF);
            thumbPaint.setShadowLayer(AndroidUtilities.dp(1.5f), 0, AndroidUtilities.dp(0.5f), 0x33000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        public void setAccent(int accent) {
            if (this.accent != accent) {
                this.accent = accent;
                invalidate();
            }
        }

        public void setChecked(boolean checked, boolean animated) {
            this.checked = checked;
            if (!animated) {
                progress.set(checked ? 1f : 0f, true);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float p = progress.set(checked ? 1f : 0f);
            final float h = getHeight() - AndroidUtilities.dp(2);
            final float top = (getHeight() - h) / 2f;
            rect.set(AndroidUtilities.dp(1), top, getWidth() - AndroidUtilities.dp(1), top + h);
            trackPaint.setColor(ColorUtils.blendARGB(TRACK_OFF, accent, p));
            final float r = h / 2f;
            canvas.drawRoundRect(rect, r, r, trackPaint);
            final float inset = AndroidUtilities.dp(2.5f);
            final float thumbR = r - inset;
            final float cx = AndroidUtilities.lerp(rect.left + r, rect.right - r, p);
            canvas.drawCircle(cx, rect.centerY(), thumbR, thumbPaint);
        }
    }
}
