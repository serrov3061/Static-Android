package com.exteragram.messenger.preferences.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.exteragram.messenger.preferences.utils.IconShapeHelper;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

public class HeaderSettingsCell extends LinearLayout implements CustomPreferenceCell {
    public final RLottieImageView imageView;
    private Path shapePath;
    public final TextView titleTextView;

    public HeaderSettingsCell(Context context) {
        super(context);
        setOrientation(1);
        setGravity(17);
        // StaticGram: the regular app logo (brand v2: red tile, light card with the red gem) on the stock theme; with a theme that
        // recolours the accent (custom theme, other accent, monet) the mark follows the theme accent
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final boolean themed = isThemedAccent(accent);
        final Drawable logo;
        final android.graphics.Paint tilePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        if (themed) {
            logo = ContextCompat.getDrawable(context, R.drawable.extera_header_logo_mark).mutate();
            logo.setColorFilter(new android.graphics.PorterDuffColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN));
            tilePaint.setColor(androidx.core.graphics.ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundGray), accent, 0.22f));
        } else {
            logo = ContextCompat.getDrawable(context, R.mipmap.icon_foreground);
            tilePaint.setColor(0xffe83030);
        }
        RLottieImageView rLottieImageView = new RLottieImageView(context) {
            @Override
            public void draw(Canvas canvas) {
                canvas.save();
                canvas.clipPath(HeaderSettingsCell.this.getPath());
                canvas.drawRect(0, 0, getWidth(), getHeight(), tilePaint);
                super.draw(canvas);
                canvas.restore();
            }
        };
        this.imageView = rLottieImageView;
        rLottieImageView.setBackgroundColor(0);
        rLottieImageView.setScaleType(themed ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        rLottieImageView.setImageDrawable(logo);
        addView(rLottieImageView, LayoutHelper.createLinear(96, 96, 49, 0, 52, 0, 0)); // StaticGram: below the 56dp action bar (its glass panel covered the top of the logo)
        TextView textView = new TextView(context);
        this.titleTextView = textView;
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTypeface(AndroidUtilities.bold());
        textView.setTextSize(1, 24.0f);
        textView.setText(LocaleController.getString(R.string.exteraAppName));
        textView.setLines(1);
        textView.setMaxLines(1);
        textView.setSingleLine(true);
        textView.setPadding(0, 0, 0, 0);
        textView.setGravity(17);
        addView(textView, LayoutHelper.createLinear(-2, -2, 49, 50, 16, 50, 28));
    }

    /** StaticGram: true when the theme recolours the accent away from the StaticGram red. */
    private static boolean isThemedAccent(int accent) {
        if (Theme.isCurrentThemeMonet()) {
            return true;
        }
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(accent, hsl);
        float hue = hsl[0];
        boolean red = (hue <= 15f || hue >= 345f) && hsl[1] >= 0.45f;
        return !red;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Path getPath() {
        if (this.shapePath == null) {
            // StaticGram: a plain rounded square; the system icon mask (Samsung) was resized off the 96dp box
            // and cut the logo, so the header no longer follows the system icon shape
            this.shapePath = new Path();
            float size = AndroidUtilities.dpf2(96.0f);
            this.shapePath.addRoundRect(new android.graphics.RectF(0, 0, size, size), AndroidUtilities.dpf2(24.0f), AndroidUtilities.dpf2(24.0f), Path.Direction.CW);
        }
        return this.shapePath;
    }

    @Override // android.view.View
    public void invalidate() {
        this.shapePath = null;
        this.imageView.invalidate();
        super.invalidate();
    }

    @Override // com.exteragram.messenger.preferences.components.CustomPreferenceCell
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof HeaderSettingsCell) {
            HeaderSettingsCell headerSettingsCell = (HeaderSettingsCell) obj;
            if (this.imageView.equals(headerSettingsCell.imageView) && this.titleTextView.equals(headerSettingsCell.titleTextView)) {
                return true;
            }
        }
        return false;
    }

    @Override // android.widget.LinearLayout, android.view.View
    public void onMeasure(int i, int i2) {
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(i), 1073741824), View.MeasureSpec.makeMeasureSpec(0, 0));
    }
}
