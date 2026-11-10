package com.exteragram.messenger.plugins.catalog.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.plugins.catalog.PluginCategory;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Category chips of a catalog cell (exteraGram 13.1.1 catalog/ui/CatalogCategoryTagsView). */
@SuppressLint("ViewConstructor")
public final class CatalogCategoryTagsView extends View {

    public static final float HEIGHT = 18.0f;
    private static final float GAP = 4.0f;
    private static final float ICON_SIZE = 11.0f;
    private static final float ICON_DRAW_SIZE = 16.0f;
    private static final float PADDING_START = 5.0f;
    private static final float PADDING_END = 6.0f;
    private static final float RADIUS = 5.0f;

    public static final class Tag {
        final PluginCategory category;
        final Text text;
        final Drawable icon;
        final int fullWidth;
        int color;
        boolean compact;

        Tag(PluginCategory category, Text text, Drawable icon) {
            this.category = category;
            this.text = text;
            this.icon = icon;
            this.fullWidth = AndroidUtilities.dp(27.0f) + (int) text.getCurrentWidth();
        }

        int getWidth() {
            return compact ? AndroidUtilities.dp(21.0f) : fullWidth;
        }
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Tag> tags = Collections.emptyList();

    public CatalogCategoryTagsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
    }

    public void setCategories(List<PluginCategory> categories) {
        ArrayList<PluginCategory> current = new ArrayList<>();
        for (Tag tag : tags) {
            current.add(tag.category);
        }
        if (current.equals(categories)) {
            updateColors();
            return;
        }
        ArrayList<Tag> list = new ArrayList<>(categories.size());
        for (PluginCategory category : categories) {
            Text text = new Text(LocaleController.getString(category.getTitleRes()).toUpperCase(Locale.ROOT), 10.0f, AndroidUtilities.bold());
            Drawable icon = ContextCompat.getDrawable(getContext(), category.getIconRes());
            list.add(new Tag(category, text, icon != null ? icon.mutate() : null));
        }
        tags = list;
        updateColors();
        requestLayout();
    }

    private void updateColors() {
        for (Tag tag : tags) {
            tag.color = Theme.getColor(tag.category.getColorKey(), resourcesProvider);
            if (tag.icon != null) {
                tag.icon.setColorFilter(new PorterDuffColorFilter(tag.color, PorterDuff.Mode.SRC_IN));
            }
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        for (Tag tag : tags) {
            tag.compact = false;
        }
        // the last tags collapse to icons first, the first one always keeps its title
        for (int i = tags.size() - 1; i > 0 && totalWidth() > width; i--) {
            tags.get(i).compact = true;
        }
        setMeasuredDimension(width, AndroidUtilities.dp(HEIGHT));
    }

    private int totalWidth() {
        int width = 0;
        for (Tag tag : tags) {
            width += tag.getWidth();
        }
        return width + AndroidUtilities.dp(GAP) * Math.max(tags.size() - 1, 0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean rtl = LocaleController.isRTL;
        float height = AndroidUtilities.dp(HEIGHT);
        float radius = AndroidUtilities.dp(RADIUS);
        boolean dark = Theme.isCurrentThemeDark();
        float x = rtl ? getMeasuredWidth() : 0;
        canvas.save();
        canvas.clipRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
        for (Tag tag : tags) {
            int width = tag.getWidth();
            if (rtl) {
                x -= width;
            }
            paint.setColor(Theme.multAlpha(tag.color, dark ? 0.2f : 0.1f));
            RectF rect = AndroidUtilities.rectTmp;
            rect.set(x, 0, x + width, height);
            canvas.drawRoundRect(rect, radius, radius, paint);
            float iconCenter;
            if (tag.compact) {
                iconCenter = x + width / 2f;
            } else if (rtl) {
                iconCenter = x + width - AndroidUtilities.dp(PADDING_START) - AndroidUtilities.dp(ICON_SIZE) / 2f;
            } else {
                iconCenter = x + AndroidUtilities.dp(PADDING_START) + AndroidUtilities.dp(ICON_SIZE) / 2f;
            }
            if (tag.icon != null) {
                float half = AndroidUtilities.dp(ICON_DRAW_SIZE) / 2f;
                tag.icon.setBounds((int) (iconCenter - half), (int) (height / 2f - half), (int) (iconCenter + half), (int) (height / 2f + half));
                tag.icon.draw(canvas);
            }
            if (!tag.compact) {
                tag.text.draw(canvas, x + AndroidUtilities.dp(rtl ? PADDING_END : 21.0f), height / 2f, tag.color, 1.0f);
            }
            x = rtl ? x - AndroidUtilities.dp(GAP) : x + width + AndroidUtilities.dp(GAP);
        }
        canvas.restore();
    }
}
