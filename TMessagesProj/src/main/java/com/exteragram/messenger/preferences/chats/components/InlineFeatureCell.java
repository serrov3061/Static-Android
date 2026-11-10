package com.exteragram.messenger.preferences.chats.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.List;
import java.util.Objects;

/**
 * exteraGram 13.1.1: an expandable inline results feature (icon, title, "off" badge, subtitle) whose
 * documentation (texts, function chips and tappable examples with live results) slides out below.
 */
@SuppressLint("ViewConstructor")
public final class InlineFeatureCell extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private final Typeface mono;
    private final FrameLayout header;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView badgeView;
    private final TextView subtitleView;
    private final ImageView arrowView;
    private final LinearLayout docsView;

    private ValueAnimator animator;
    private List<? extends InlineDocBlock> boundDocs;
    private int boundId;
    private boolean expanded;
    private boolean needDivider;
    private float progress;

    public InlineFeatureCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        mono = AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO);
        header = new FrameLayout(context);
        iconView = new ImageView(context);
        titleView = new TextView(context);
        badgeView = new TextView(context);
        subtitleView = new TextView(context);
        arrowView = new ImageView(context);
        docsView = new LinearLayout(context);
        setWillNotDraw(false);

        final int start = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        final int end = LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT;

        header.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), 2));

        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setColorFilter(new PorterDuffColorFilter(color(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        header.addView(iconView, LayoutHelper.createFrame(24, 24, start | Gravity.CENTER_VERTICAL, 21, 0, 21, 0));

        titleView.setTypeface(AndroidUtilities.regular());
        titleView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(start);

        badgeView.setTypeface(AndroidUtilities.regular());
        badgeView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        badgeView.setSingleLine(true);
        badgeView.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(1), AndroidUtilities.dp(6), AndroidUtilities.dp(1));
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setCornerRadius(AndroidUtilities.dp(6));
        badgeBackground.setColor(chipColor());
        badgeView.setBackground(badgeBackground);

        subtitleView.setTypeface(AndroidUtilities.regular());
        subtitleView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout subtitleLayout = new LinearLayout(context);
        subtitleLayout.setOrientation(LinearLayout.HORIZONTAL);
        subtitleLayout.setGravity(start | Gravity.CENTER_VERTICAL);
        subtitleLayout.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 6 : 0, 0, LocaleController.isRTL ? 0 : 6, 0));
        subtitleLayout.addView(subtitleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        textLayout.addView(subtitleLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        header.addView(textLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, start | Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 56 : 71, 0, LocaleController.isRTL ? 71 : 56, 0));

        arrowView.setScaleType(ImageView.ScaleType.CENTER);
        arrowView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.arrow_more));
        arrowView.setColorFilter(new PorterDuffColorFilter(color(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        header.addView(arrowView, LayoutHelper.createFrame(24, 24, end | Gravity.CENTER_VERTICAL, 18, 0, 18, 0));
        addView(header);

        docsView.setOrientation(LinearLayout.VERTICAL);
        docsView.setClickable(true);
        docsView.setPadding(AndroidUtilities.dp(LocaleController.isRTL ? 21 : 71), AndroidUtilities.dp(2), AndroidUtilities.dp(LocaleController.isRTL ? 71 : 21), AndroidUtilities.dp(14));
        addView(docsView);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        header.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(60), MeasureSpec.EXACTLY));
        docsView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(width, header.getMeasuredHeight() + Math.round(docsView.getMeasuredHeight() * progress) + (needDivider ? 1 : 0));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        header.layout(0, 0, header.getMeasuredWidth(), header.getMeasuredHeight());
        docsView.layout(0, header.getMeasuredHeight(), docsView.getMeasuredWidth(), header.getMeasuredHeight() + docsView.getMeasuredHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            final float inset = AndroidUtilities.dp(68);
            final float y = getMeasuredHeight() - 1f;
            Paint dividerPaint = resourcesProvider != null ? resourcesProvider.getPaint(Theme.key_paint_divider) : null;
            if (dividerPaint == null) {
                dividerPaint = Theme.dividerPaint;
            }
            if (LocaleController.isRTL) {
                canvas.drawLine(0, y, getMeasuredWidth() - inset, y, dividerPaint);
            } else {
                canvas.drawLine(inset, y, getMeasuredWidth(), y, dividerPaint);
            }
        }
    }

    public void set(int id, InlineFeature feature, boolean divider, View.OnClickListener onClick) {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
        final boolean expand = feature.getDocs() != null;
        final boolean animated = boundId == id && expanded != expand;
        boundId = id;
        expanded = expand;

        header.setTag(id);
        header.setOnClickListener(onClick);
        iconView.setImageResource(feature.getIcon());
        titleView.setText(feature.getTitle());
        subtitleView.setText(feature.getSubtitle());
        badgeView.setText(feature.getBadge());
        badgeView.setVisibility(feature.getBadge() == null ? GONE : VISIBLE);
        if (feature.getDocs() != null && !Objects.equals(feature.getDocs(), boundDocs)) {
            boundDocs = feature.getDocs();
            buildDocs(feature.getDocs(), onClick);
        }

        final float target = expand ? 1f : 0f;
        arrowView.animate().cancel();
        if (animated) {
            arrowView.animate().rotation(180f * target).setDuration(340).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            ValueAnimator valueAnimator = ValueAnimator.ofFloat(progress, target);
            valueAnimator.setDuration(340);
            valueAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            valueAnimator.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
            valueAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!expanded) {
                        clearDocs();
                    }
                }
            });
            valueAnimator.start();
            animator = valueAnimator;
        } else {
            arrowView.setRotation(180f * target);
            setProgress(target);
            if (!expand) {
                clearDocs();
            }
        }
        needDivider = divider;
        requestLayout();
        invalidate();
    }

    private void setProgress(float value) {
        progress = value;
        docsView.setAlpha(value);
        requestLayout();
    }

    private void clearDocs() {
        boundDocs = null;
        docsView.removeAllViews();
    }

    private void buildDocs(List<? extends InlineDocBlock> blocks, View.OnClickListener onClick) {
        docsView.removeAllViews();
        for (int i = 0; i < blocks.size(); i++) {
            final InlineDocBlock block = blocks.get(i);
            final int topMargin = i == 0 ? 0 : 8;
            if (block instanceof InlineDocBlock.Text) {
                docsView.addView(text((InlineDocBlock.Text) block), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, topMargin, 0, 0));
            } else if (block instanceof InlineDocBlock.Codes) {
                docsView.addView(codes((InlineDocBlock.Codes) block), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, topMargin, 0, 0));
            } else if (block instanceof InlineDocBlock.Examples) {
                final List<InlineDocExample> examples = ((InlineDocBlock.Examples) block).getExamples();
                for (int j = 0; j < examples.size(); j++) {
                    docsView.addView(example(examples.get(j), onClick), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT, 0, j == 0 ? topMargin : 6, 0, 0));
                }
            }
        }
    }

    private TextView text(InlineDocBlock.Text block) {
        TextView textView = new TextView(getContext());
        textView.setText(block.getText());
        textView.setTypeface(AndroidUtilities.regular());
        textView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        textView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        return textView;
    }

    private TextView codes(InlineDocBlock.Codes block) {
        TextView textView = new TextView(getContext());
        SpannableStringBuilder builder = new SpannableStringBuilder();
        final int textColor = color(Theme.key_windowBackgroundWhiteBlackText);
        final List<String> codes = block.getCodes();
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                builder.append(' ');
            }
            final int spanStart = builder.length();
            builder.append(codes.get(i));
            builder.setSpan(new CodeSpan(mono, chipColor(), textColor), spanStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        textView.setText(builder);
        textView.setTypeface(AndroidUtilities.regular());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setLineSpacing(AndroidUtilities.dp(10), 1f);
        textView.setPadding(0, AndroidUtilities.dp(3), 0, AndroidUtilities.dp(3));
        return textView;
    }

    private TextView example(InlineDocExample example, View.OnClickListener onClick) {
        TextView textView = new TextView(getContext());
        SpannableStringBuilder builder = new SpannableStringBuilder(example.getInput());
        if (example.getResult() != null) {
            final int spanStart = builder.length();
            builder.append(' ').append(example.getResult());
            builder.setSpan(new ForegroundColorSpan(Theme.multAlpha(color(Theme.key_windowBackgroundWhiteBlackText), 0.4f)), spanStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        textView.setText(builder);
        textView.setTypeface(mono);
        textView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        textView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4));
        textView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(6), chipColor(), Theme.multAlpha(color(Theme.key_windowBackgroundWhiteBlackText), 0.12f)));
        textView.setTag(example.getInput()); // tapping an example copies it
        textView.setOnClickListener(onClick);
        return textView;
    }

    private int chipColor() {
        return Theme.multAlpha(color(Theme.key_windowBackgroundWhiteBlackText), 0.06f);
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    /** A monospace chip for a function name. */
    public static final class CodeSpan extends ReplacementSpan {
        private final Typeface typeface;
        private final int background;
        private final int color;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        public CodeSpan(Typeface typeface, int background, int color) {
            this.typeface = typeface;
            this.background = background;
            this.color = color;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            prepare(paint);
            if (fm != null) {
                paint.getFontMetricsInt(fm);
            }
            return (int) (textPaint.measureText(text, start, end) + AndroidUtilities.dp(12));
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            prepare(paint);
            rect.set(x, y + textPaint.ascent() - AndroidUtilities.dp(3), x + textPaint.measureText(text, start, end) + AndroidUtilities.dp(12), y + textPaint.descent() + AndroidUtilities.dp(3));
            backgroundPaint.setColor(background);
            canvas.drawRoundRect(rect, AndroidUtilities.dp(6), AndroidUtilities.dp(6), backgroundPaint);
            canvas.drawText(text, start, end, x + AndroidUtilities.dp(6), y, textPaint);
        }

        private void prepare(Paint paint) {
            textPaint.setTextSize(paint.getTextSize() * 0.93f);
            textPaint.setTypeface(typeface);
            textPaint.setColor(color);
        }
    }

    public static final class Factory extends UItem.UItemFactory<InlineFeatureCell> {
        static {
            setup(new Factory());
        }

        public static UItem of(int id, InlineFeature feature, View.OnClickListener onClick) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.text = feature.getTitle();
            item.object = feature;
            item.clickCallback = onClick;
            return item;
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public InlineFeatureCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new InlineFeatureCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (item.object instanceof InlineFeature) {
                ((InlineFeatureCell) view).set(item.id, (InlineFeature) item.object, divider, item.clickCallback);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.id == b.id && Objects.equals(a.object, b.object);
        }
    }
}
