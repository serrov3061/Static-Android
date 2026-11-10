package com.exteragram.messenger.components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SlideView;

import java.util.HashMap;

// exteraGram: screen of the QR login (the logged-out device shows a code that another device scans).
// Simplified port: static QR bitmap and texts, no render animations.
public class QrCodeLoginView extends SlideView {

    private static final int QR_SIZE_DP = 264;

    private final TextView titleView;
    private final TextView subtitleView;
    private final ImageView qrImageView;
    private String currentLink;

    public QrCodeLoginView(Context context) {
        super(context);
        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER);
        titleView.setText(LocaleController.getString(R.string.LoginQrTitle));
        addView(titleView, LayoutHelper.createLinear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 30, 0, 0));

        qrImageView = new ImageView(context);
        qrImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrImageView.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xffffffff);
        background.setCornerRadius(dp(16));
        qrImageView.setBackground(background);
        addView(qrImageView, LayoutHelper.createLinear(QR_SIZE_DP, QR_SIZE_DP, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setLineSpacing(dp(2), 1f);
        subtitleView.setText(LocaleController.getString(R.string.LoginQrSubtitle));
        addView(subtitleView, LayoutHelper.createLinear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 24, 32, 0));

        updateColors();
    }

    public void setData(String link) {
        if (link == null || link.equals(currentLink)) {
            return;
        }
        currentLink = link;
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            Bitmap bitmap = new TelegramQRCodeWriter().encode(link, 768, 768, hints, null);
            qrImageView.setImageBitmap(bitmap);
        } catch (Exception e) {
            FileLog.e(e);
            currentLink = null;
            qrImageView.setImageBitmap(null);
        }
    }

    public void clear() {
        clear(false);
    }

    public void clear(boolean animated) {
        currentLink = null;
        qrImageView.setImageBitmap(null);
    }

    @Override
    public String getHeaderName() {
        return LocaleController.getString(R.string.LoginQrTitle);
    }

    @Override
    public boolean needBackButton() {
        return true;
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clear();
    }

    @Override
    public void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
    }
}
