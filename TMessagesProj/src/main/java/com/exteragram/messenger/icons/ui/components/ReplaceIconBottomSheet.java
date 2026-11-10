package com.exteragram.messenger.icons.ui.components;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import com.caverock.androidsvg.SVG;
import com.exteragram.messenger.icons.ExteraResources;
import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.icons.IconPack;
import com.exteragram.messenger.icons.IconPackStorage;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * exteraGram ReplaceIconBottomSheet: the original drawable next to its replacement in a pack (resolution and aspect
 * ratio, red when it does not match). The new icon comes from the clipboard (image URI, SVG markup or a file path),
 * the photo picker or the file picker; Save copies it into the pack, Reset removes the replacement.
 */
public class ReplaceIconBottomSheet extends BottomSheet {

    private final IconPack iconPack;
    private final int resId;
    private final String resourceName;
    private int loadedOriginalWidth;
    private int loadedOriginalHeight;
    private int savedCustomFileWidth;
    private int savedCustomFileHeight;
    private boolean needSave;
    private boolean needReset;
    private boolean waitingForResult;
    private Drawable newDrawable;
    private Drawable originalDrawable;
    private IconInfoView newIconInfoView;
    private IconInfoView originalIconInfoView;
    private String newIconOriginalName;
    private File newIconTempFile;
    private ButtonWithCounterView resetButton;

    public ReplaceIconBottomSheet(Context context, int resId, IconPack iconPack) {
        super(context, false);
        fixNavigationBar();
        this.resId = resId;
        this.iconPack = iconPack;
        this.resourceName = context.getResources().getResourceEntryName(resId);
        setCustomView(createView(context));
        loadDrawables(context);
    }

    private static int[] svgSize(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            SVG svg = SVG.getFromInputStream(in);
            int width = (int) (svg.getDocumentWidth() > 0 ? svg.getDocumentWidth() : svg.getDocumentViewBox().width());
            int height = (int) (svg.getDocumentHeight() > 0 ? svg.getDocumentHeight() : svg.getDocumentViewBox().height());
            return new int[]{width, height};
        }
    }

    private static int[] bitmapSize(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return new int[]{options.outWidth, options.outHeight};
    }

    private void loadDrawables(Context context) {
        Utilities.globalQueue.postRunnable(() -> {
            Drawable original = null;
            if (context.getResources() instanceof ExteraResources) {
                try {
                    original = ((ExteraResources) context.getResources()).getOriginalDrawable(resId);
                } catch (Exception ignore) {
                }
            }
            if (original == null) {
                try {
                    original = ResourcesCompat.getDrawable(context.getResources(), resId, context.getTheme());
                } catch (Exception ignore) {
                }
            }
            int width = original != null ? original.getIntrinsicWidth() : 0;
            int height = original != null ? original.getIntrinsicHeight() : 0;
            Drawable custom = null;
            int customWidth = 0;
            int customHeight = 0;
            String fileName = iconPack.getIcons().get(resourceName);
            if (fileName != null) {
                File file = new File(IconPackStorage.INSTANCE.getIconPacksDirectory(), iconPack.getId() + "/" + fileName);
                if (file.exists()) {
                    try {
                        int[] size = file.getName().toLowerCase(Locale.ROOT).endsWith(".svg") ? svgSize(file) : bitmapSize(file);
                        customWidth = size[0];
                        customHeight = size[1];
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    Bitmap bitmap = IconManager.INSTANCE.createBitmapFromFile(file.getAbsolutePath(), resId, AndroidUtilities.displayMetrics.densityDpi, context.getTheme());
                    custom = bitmap != null ? new BitmapDrawable(context.getResources(), bitmap) : null;
                }
            }
            Drawable finalOriginal = original;
            Drawable finalCustom = custom;
            int finalCustomWidth = customWidth;
            int finalCustomHeight = customHeight;
            AndroidUtilities.runOnUIThread(() -> {
                originalDrawable = finalOriginal;
                loadedOriginalWidth = width;
                loadedOriginalHeight = height;
                newDrawable = finalCustom;
                savedCustomFileWidth = finalCustomWidth;
                savedCustomFileHeight = finalCustomHeight;
                if (originalIconInfoView != null) {
                    originalIconInfoView.update(finalOriginal, resourceName, width, height);
                }
                if (resetButton != null) {
                    resetButton.setText(LocaleController.getString(newDrawable != null ? R.string.Reset : R.string.Cancel), false);
                }
                updateNewInfo(newDrawable, iconPack.getIcons().get(resourceName), savedCustomFileWidth, savedCustomFileHeight);
            });
        });
    }

    private View createView(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, AndroidUtilities.dp(16), 0, 0);

        LinearLayout icons = new LinearLayout(context);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        icons.setGravity(Gravity.CENTER_HORIZONTAL);
        icons.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);

        originalIconInfoView = new IconInfoView(context, false);
        originalIconInfoView.update(originalDrawable, resourceName, loadedOriginalWidth, loadedOriginalHeight);
        icons.addView(originalIconInfoView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        LinearLayout arrow = new LinearLayout(context);
        arrow.setOrientation(LinearLayout.VERTICAL);
        arrow.setGravity(Gravity.CENTER_HORIZONTAL);
        arrow.addView(new ArrowView(context), LayoutHelper.createLinear(24, 60));
        icons.addView(arrow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 24, 0, 24, 0));

        newIconInfoView = new IconInfoView(context, true);
        newIconInfoView.setTargetDimensions(loadedOriginalWidth, loadedOriginalHeight);
        newIconInfoView.getIconView().setFocusable(true);
        newIconInfoView.getIconView().setOnClickListener(v -> showSourceOptions(context, v));
        icons.addView(newIconInfoView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        updateNewInfo(newDrawable, iconPack.getIcons().get(resourceName), savedCustomFileWidth, savedCustomFileHeight);
        layout.addView(icons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        ButtonWithCounterView save = new ButtonWithCounterView(context, true, resourcesProvider);
        save.setRound();
        save.setText(LocaleController.getString(R.string.Save), false);
        save.setOnClickListener(v -> {
            if (newIconTempFile != null) {
                needSave = true;
            }
            dismiss();
        });
        buttons.addView(save, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        resetButton = new ButtonWithCounterView(context, false, resourcesProvider);
        resetButton.setRound().setNeutral();
        resetButton.setText(LocaleController.getString(iconPack.getIcons().get(resourceName) != null ? R.string.Reset : R.string.Cancel), false);
        resetButton.setOnClickListener(v -> {
            if (newDrawable != null) {
                needReset = true;
            }
            dismiss();
        });
        buttons.addView(resetButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 8, 0, 0));
        layout.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return layout;
    }

    private void showSourceOptions(Context context, View anchor) {
        BaseFragment fragment;
        Activity activity;
        if (isDismissed() || (fragment = LaunchActivity.getSafeLastFragment()) == null || (activity = fragment.getParentActivity()) == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        boolean canPaste = false;
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                ClipData.Item item = clip.getItemAt(0);
                if (item.getUri() != null) {
                    canPaste = true;
                } else if (item.getText() != null) {
                    String text = item.getText().toString().trim();
                    canPaste = !text.isEmpty() && (text.contains("<svg") || text.contains("<SVG") || text.startsWith("/"));
                }
            }
        }
        ItemOptions.makeOptions(containerView, anchor)
                .addIf(canPaste, R.drawable.msg_copy, LocaleController.getString(R.string.PasteFromClipboard), () -> {
                    ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
                    if (clip == null || clip.getItemCount() <= 0) {
                        return;
                    }
                    ClipData.Item item = clip.getItemAt(0);
                    if (item.getUri() != null) {
                        processSelectedImage(context, item.getUri());
                    } else if (item.getText() != null) {
                        processClipboardText(context, item.getText());
                    }
                })
                .add(R.drawable.msg_photos, LocaleController.getString(R.string.SelectFromGallery), () -> startPicker(activity, false))
                .add(R.drawable.msg2_folder, LocaleController.getString(R.string.StoryMusicSelectFromFiles), () -> startPicker(activity, true))
                .setDrawScrim(false)
                .setOnTopOfScrim()
                .setDimAlpha(0)
                .setGravity(Gravity.CENTER_HORIZONTAL)
                .show();
    }

    private void updateNewInfo(Drawable drawable, String name, int width, int height) {
        if (newIconInfoView != null) {
            if (loadedOriginalWidth > 0 && loadedOriginalHeight > 0) {
                newIconInfoView.setTargetDimensions(loadedOriginalWidth, loadedOriginalHeight);
            }
            newIconInfoView.update(drawable, name, width, height);
        }
    }

    private void startPicker(Activity activity, boolean fromFiles) {
        waitingForResult = true;
        IconManager.INSTANCE.startIconPicker(activity, fromFiles, uri -> {
            waitingForResult = false;
            if (uri != null) {
                processSelectedImage(activity, uri);
            }
        });
    }

    private void updateNewIconFromFile(Context context, File file, String name, int width, int height) {
        Bitmap bitmap = IconManager.INSTANCE.createBitmapFromFile(file.getAbsolutePath(), resId, AndroidUtilities.displayMetrics.densityDpi, context.getTheme());
        if (bitmap == null) {
            file.delete();
            return;
        }
        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
        AndroidUtilities.runOnUIThread(() -> {
            if (isDismissed()) {
                file.delete();
                return;
            }
            if (newIconTempFile != null && newIconTempFile.exists() && !newIconTempFile.equals(file)) {
                newIconTempFile.delete();
            }
            newIconTempFile = file;
            newIconOriginalName = name;
            newDrawable = drawable;
            if (resetButton != null) {
                resetButton.setText(LocaleController.getString(R.string.Reset), false);
            }
            updateNewInfo(newDrawable, newIconOriginalName, width, height);
        });
    }

    private void processClipboardText(Context context, CharSequence text) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String string = text.toString();
                if (!string.contains("<svg") && !string.contains("<SVG")) {
                    String path = string.trim();
                    if (path.startsWith("/")) {
                        File file = new File(path);
                        if (file.exists()) {
                            processSelectedImage(context, Uri.fromFile(file));
                        }
                    }
                    return;
                }
                File file = new File(ApplicationLoader.applicationContext.getCacheDir(), "temp_import_" + System.currentTimeMillis() + ".svg");
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(string.getBytes(StandardCharsets.UTF_8));
                }
                int[] size = svgSize(file);
                updateNewIconFromFile(context, file, resourceName + ".svg", size[0], size[1]);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void processSelectedImage(Context context, Uri uri) {
        Utilities.globalQueue.postRunnable(() -> {
            File raw = null;
            try {
                String name = null;
                try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (column != -1) {
                            name = cursor.getString(column);
                        }
                    }
                } catch (Exception ignore) {
                }
                if (TextUtils.isEmpty(name)) {
                    name = uri.getLastPathSegment();
                }
                if (TextUtils.isEmpty(name)) {
                    name = "icon_" + System.currentTimeMillis();
                }
                raw = new File(ApplicationLoader.applicationContext.getCacheDir(), "temp_import_" + System.currentTimeMillis() + "_raw");
                try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(raw)) {
                    if (in != null) {
                        byte[] buffer = new byte[4096];
                        long total = 0;
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            total += read;
                            if (total > 10485760) { // StaticGram: same limit as a file in an icon pack archive
                                throw new java.io.IOException("Icon file is too large");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                }
                boolean svg = false;
                try (InputStream in = new FileInputStream(raw)) {
                    byte[] head = new byte[1024];
                    int read = in.read(head);
                    if (read > 0) {
                        String start = new String(head, 0, read, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
                        svg = start.contains("<svg");
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                String lower = name.toLowerCase(Locale.ROOT);
                String extension;
                if (svg) {
                    extension = "svg";
                } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                    extension = "jpg";
                } else if (lower.endsWith(".webp")) {
                    extension = "webp";
                } else {
                    extension = "png";
                }
                if (!lower.endsWith("." + extension)) {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        name = name.substring(0, dot);
                    }
                    name = name + "." + extension;
                }
                File file = new File(ApplicationLoader.applicationContext.getCacheDir(), "temp_import_" + System.currentTimeMillis() + "." + extension);
                if (raw.renameTo(file)) {
                    int[] size = svg ? svgSize(file) : bitmapSize(file);
                    updateNewIconFromFile(context, file, name, size[0], size[1]);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (raw != null && raw.exists()) {
                    raw.delete();
                }
            }
        });
    }

    @Override
    public void dismissInternal() {
        super.dismissInternal();
        if (needSave && newIconTempFile != null && !needReset) {
            IconManager.INSTANCE.saveCustomIcon(iconPack.getId(), resId, newIconTempFile, newIconOriginalName);
            return;
        }
        if (newIconTempFile != null && newIconTempFile.exists()) {
            newIconTempFile.delete();
        }
        if (needReset) {
            IconManager.INSTANCE.resetCustomIcon(iconPack.getId(), resId);
        }
    }

    @Override
    public void dismiss() {
        if (waitingForResult) {
            return;
        }
        super.dismiss();
    }

    /** Icon with its name and "W×H (a:b)" below. */
    public static class IconInfoView extends LinearLayout {

        private final BorderedImageView iconView;
        private final TextView infoName;
        private final TextView infoResolution;
        private float targetAspectRatio = -1f;

        public IconInfoView(Context context, boolean dashed) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            iconView = new BorderedImageView(context);
            iconView.setDashed(dashed);
            iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
            iconView.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6));
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(iconView, LayoutHelper.createLinear(60, 60));

            infoName = new TextView(context);
            infoName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            infoName.setTypeface(AndroidUtilities.bold());
            infoName.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            infoName.setGravity(Gravity.CENTER);
            infoName.setSingleLine(true);
            infoName.setEllipsize(TextUtils.TruncateAt.END);
            addView(infoName, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

            infoResolution = new TextView(context);
            infoResolution.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            infoResolution.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            infoResolution.setGravity(Gravity.CENTER);
            addView(infoResolution, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        public void setTargetDimensions(int width, int height) {
            targetAspectRatio = width > 0 && height > 0 ? (float) width / height : -1f;
        }

        public void update(Drawable drawable, String name, int width, int height) {
            if (drawable != null) {
                if (width <= 0) {
                    width = drawable.getIntrinsicWidth();
                }
                if (height <= 0) {
                    height = drawable.getIntrinsicHeight();
                }
                infoResolution.setText(String.format("%s (%s)", String.format(Locale.ROOT, "%d×%d", width, height), getAspectRatioString(width, height)));
                infoResolution.setVisibility(VISIBLE);
                if (targetAspectRatio > 0 && height > 0 && Math.abs((float) width / height - targetAspectRatio) > 0.1f) {
                    infoResolution.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                } else {
                    infoResolution.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                }
                iconView.setImageDrawable(drawable);
            } else {
                infoResolution.setVisibility(INVISIBLE);
                iconView.setImageDrawable(null);
            }
            if (name != null) {
                infoName.setText(name);
                infoName.setVisibility(VISIBLE);
            } else {
                infoName.setVisibility(INVISIBLE);
            }
        }

        public BorderedImageView getIconView() {
            return iconView;
        }

        private String getAspectRatioString(int width, int height) {
            if (height == 0) {
                return "?";
            }
            int gcd = gcd(width, height);
            return gcd == 0 ? "?" : (width / gcd) + ":" + (height / gcd);
        }

        private int gcd(int a, int b) {
            return b == 0 ? a : gcd(b, a % b);
        }
    }

    public static class ArrowView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        public ArrowView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float half = AndroidUtilities.dp(18) / 2f;
            path.reset();
            path.moveTo(cx - half, cy);
            path.lineTo(cx + half, cy);
            path.moveTo(cx + half - AndroidUtilities.dp(7), cy - AndroidUtilities.dp(7));
            path.lineTo(cx + half, cy);
            path.lineTo(cx + half - AndroidUtilities.dp(7), cy + AndroidUtilities.dp(7));
            canvas.drawPath(path, paint);
        }
    }

    public static class BorderedImageView extends ImageView {

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dashedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final float cornerRadius = AndroidUtilities.dp(12);
        private final float strokeWidth = AndroidUtilities.dpf2(1.25f);
        private boolean isDashed;

        public BorderedImageView(Context context) {
            this(context, null);
        }

        public BorderedImageView(Context context, AttributeSet attrs) {
            super(context, attrs);
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            int stroke = AndroidUtilities.multiplyAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.3f);
            solidPaint.setStyle(Paint.Style.STROKE);
            solidPaint.setColor(stroke);
            solidPaint.setStrokeWidth(strokeWidth);
            dashedPaint.setStyle(Paint.Style.STROKE);
            dashedPaint.setColor(stroke);
            dashedPaint.setStrokeWidth(strokeWidth);
            dashedPaint.setPathEffect(new DashPathEffect(new float[]{AndroidUtilities.dp(8), AndroidUtilities.dp(8)}, 0f));
        }

        public void setDashed(boolean dashed) {
            isDashed = dashed;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float half = strokeWidth / 2f;
            rect.set(half, half, getWidth() - half, getHeight() - half);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint);
            super.onDraw(canvas);
            path.reset();
            path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.drawPath(path, isDashed ? dashedPaint : solidPaint);
        }
    }
}
