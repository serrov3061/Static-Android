package com.exteragram.messenger.debug;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatTextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;

/**
 * exteraGram 13.1.1: small monospace text overlay with periodically refreshed debug values
 * (used by the round-video camera metrics). Tap copies the text.
 */
public class DebugOverlayView extends AppCompatTextView {

    public interface DataSource {
        void build(ContentBuilder builder);
    }

    public static class ContentBuilder {
        private final StringBuilder stringBuilder = new StringBuilder(512);

        public ContentBuilder reset() {
            stringBuilder.setLength(0);
            return this;
        }

        public ContentBuilder title(CharSequence text) {
            return line(text);
        }

        public ContentBuilder section(CharSequence text) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append('\n');
            }
            stringBuilder.append('[').append(text).append(']');
            return this;
        }

        public ContentBuilder kv(String key, Object value) {
            return line(key + "=" + value);
        }

        public ContentBuilder line(CharSequence text) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append('\n');
            }
            stringBuilder.append(text);
            return this;
        }

        public CharSequence build() {
            return stringBuilder;
        }
    }

    private final ContentBuilder contentBuilder = new ContentBuilder();
    private DataSource dataSource;
    private long updateIntervalMs = 250L;
    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            if (isAttachedToWindow()) {
                refresh();
                postDelayed(this, updateIntervalMs);
            }
        }
    };

    public DebugOverlayView(Context context) {
        super(context);
        setTextColor(0xffffffff);
        setTextSize(10);
        setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xb0000000));
        setOnClickListener(v -> {
            if (dataSource != null && AndroidUtilities.addToClipboard(contentBuilder.build()) && AndroidUtilities.shouldShowClipboardToast()) {
                Toast.makeText(getContext(), LocaleController.getString(R.string.TextCopied), Toast.LENGTH_SHORT).show();
            }
        });
        setLongClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        refresh();
    }

    public void setUpdateInterval(long ms) {
        updateIntervalMs = Math.max(16L, ms);
    }

    public void refresh() {
        if (dataSource == null) {
            setText("");
            return;
        }
        contentBuilder.reset();
        try {
            dataSource.build(contentBuilder);
        } catch (Throwable t) {
            contentBuilder.line("error: " + t);
        }
        // copy: the builder is reused on the next tick
        setText(contentBuilder.build().toString());
    }

    public static FrameLayout.LayoutParams createLayoutParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(AndroidUtilities.dp(220), FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.RIGHT);
        lp.setMargins(AndroidUtilities.dp(10), AndroidUtilities.dp(AndroidUtilities.statusBarHeight) - ActionBar.getCurrentActionBarHeight(), AndroidUtilities.dp(10), 0);
        return lp;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(updater);
        post(updater);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(updater);
    }
}
