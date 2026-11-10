package com.exteragram.messenger.preferences.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.badges.AvatarFrames;
import com.exteragram.messenger.badges.BadgesConfig;
import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.badges.FrameParams;
import com.exteragram.messenger.badges.FrameRenderer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * StaticGram: avatar frame picker used by «Avatar frame» (AvatarFramePreferencesActivity).
 * Holds the frame and settings being chosen, the horizontal style row drawn on the user's own
 * avatar, the «Settings» section of the selected style and the save through the badge bot
 * ("frame &lt;style&gt; key=value ..."). All styles come with any badge or a frame grant; without one
 * the styles are shown locked.
 */
public final class AvatarFramePicker {

    public static final int ID_TOGGLE1 = 1001;
    public static final int ID_TOGGLE2 = 1002;
    public static final int ID_ANIM = 1003;
    private static final int ID_SPEED = 1004;
    private static final int ID_GLOW = 1005;
    private static final int ID_CHIPS_PALETTE = 1010;
    private static final int ID_CHIPS_GEM = 1011;
    private static final int ID_CHIPS_GEMPOS = 1012;

    private final BaseFragment fragment;
    private final int account;
    private final Runnable onChanged;
    private final PickerCell cell;
    private final HashMap<String, ChipsCell> chipCells = new HashMap<>();
    /** Frame being chosen (null = none) and its settings; loaded once from the synced profile. */
    private String pending;
    private TreeMap<String, String> pendingParams = new TreeMap<>();
    private FrameParams pendingFrame;
    private boolean loaded;
    private boolean saving;
    private final Runnable deferredChange = this::notifyChanged;

    /** {@code onChanged} is called after a selection or a save state change: refresh the list/preview there. */
    public AvatarFramePicker(BaseFragment fragment, Context context, Runnable onChanged) {
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        this.onChanged = onChanged;
        this.cell = new PickerCell(context);
    }

    public View getCell() {
        return cell;
    }

    public String getPending() {
        return pending;
    }

    /** The frame being chosen with its settings, or null. */
    public FrameParams getPendingFrame() {
        return pendingFrame;
    }

    public boolean isSaving() {
        return saving;
    }

    private TLRPC.User user() {
        return UserConfig.getInstance(account).getCurrentUser();
    }

    /** Whether the current account may set a frame at all (has a badge or a frame grant). */
    public boolean isAvailable() {
        return BadgesController.INSTANCE.canUseFrames(user());
    }

    public static String currentFrame(int account) {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        return user != null ? AvatarFrames.frameOf(user.id) : null;
    }

    /** The saved frame of the account with its settings, or null. */
    public static FrameParams currentParams(int account) {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        return user != null ? AvatarFrames.paramsOf(user.id) : null;
    }

    public String currentFrame() {
        return currentFrame(account);
    }

    private Map<String, String> currentFrameParams() {
        TLRPC.User user = user();
        Map<String, String> params = user != null ? AvatarFrames.frameParamsOf(user.id) : null;
        return params != null ? params : new TreeMap<>();
    }

    /** Call from fillItems: loads the saved frame once, or clears the choice when frames are unavailable. */
    public void load() {
        if (!isAvailable()) {
            setPending(null, null);
            loaded = false;
            return;
        }
        if (!loaded) {
            loaded = true;
            String frame = currentFrame();
            setPending(frame, frame != null ? currentFrameParams() : null);
        }
    }

    private void setPending(String style, Map<String, String> params) {
        pending = style;
        pendingParams = new TreeMap<>(FrameParams.sanitize(style, params));
        pendingFrame = style != null ? FrameParams.of(style, pendingParams) : null;
    }

    /** Drops an unedited choice so the next {@link #load()} picks up a freshly synced frame. */
    public void reloadIfUnedited() {
        if (loaded && !isEdited()) {
            loaded = false;
        }
    }

    public boolean isEdited() {
        if (!isAvailable()) {
            return false;
        }
        String current = currentFrame();
        if (!Objects.equals(pending, current)) {
            return true;
        }
        return pending != null && !FrameParams.nonDefault(pending, pendingParams).equals(FrameParams.nonDefault(current, currentFrameParams()));
    }

    public boolean canSave() {
        return !saving && isEdited();
    }

    public void invalidate() {
        cell.invalidateItems();
    }

    public void select(String style) {
        TLRPC.User user = user();
        if (!isAvailable()) {
            if (style != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.AvatarFrameNeedBadge)).show();
            }
            return;
        }
        if (style != null && !BadgesController.INSTANCE.canUseFrame(user, style)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.AvatarFrameNotIncluded)).show();
            return;
        }
        if (Objects.equals(style, pending)) {
            return;
        }
        // switching back to the saved style restores its saved settings; another style starts from defaults
        setPending(style, style != null && style.equals(currentFrame()) ? currentFrameParams() : null);
        cell.invalidateItems();
        notifyChanged();
    }

    /** Current value of a setting of the pending style (the default when unset). */
    public String getParam(String key) {
        String v = pendingParams.get(key);
        return v != null ? v : FrameParams.defaultValue(pending, key);
    }

    /** Sets a setting of the pending style. {@code deferList}: refresh the list later (sliders being dragged). */
    public void setParam(String key, String value, boolean deferList) {
        if (pending == null) {
            return;
        }
        pendingParams.put(key, value);
        pendingFrame = FrameParams.of(pending, pendingParams);
        cell.invalidateItems();
        for (ChipsCell c : chipCells.values()) {
            c.bind();
        }
        AndroidUtilities.cancelRunOnUIThread(deferredChange);
        if (deferList) {
            AndroidUtilities.runOnUIThread(deferredChange, 350);
            if (previewInvalidator != null) {
                previewInvalidator.run();
            }
        } else {
            notifyChanged();
        }
    }

    private Runnable previewInvalidator;

    /** Called on slider moves, so the preview follows without rebuilding the list. */
    public void setPreviewInvalidator(Runnable r) {
        previewInvalidator = r;
    }

    private boolean toggle(String key) {
        return !"0".equals(getParam(key));
    }

    /** Adds the «Settings» section of the pending style (nothing when no frame is chosen). */
    public void addSettingsItems(ArrayList<UItem> items) {
        if (pending == null || !isAvailable()) {
            return;
        }
        Context context = cell.getContext();
        items.add(UItem.asHeader(LocaleController.getString(R.string.AvatarFrameSettings)));
        String paletteKey = FrameParams.paletteKey(pending);
        items.add(UItem.asCustom(ID_CHIPS_PALETTE, chips(context, paletteKey)));
        if (FrameParams.SOLITAIRE.equals(pending)) {
            items.add(UItem.asCustom(ID_CHIPS_GEM, chips(context, FrameParams.K_GEM)));
            items.add(UItem.asCustom(ID_CHIPS_GEMPOS, chips(context, FrameParams.K_GEMPOS)));
        }
        String[] toggles = FrameParams.toggleKeys(pending);
        for (int i = 0; i < toggles.length; i++) {
            items.add(UItem.asCheck(i == 0 ? ID_TOGGLE1 : ID_TOGGLE2, keyName(toggles[i])).setChecked(toggle(toggles[i])));
        }
        items.add(UItem.asCheck(ID_ANIM, LocaleController.getString(R.string.AvatarFrameAnimation)).setChecked(toggle(FrameParams.K_ANIM)));
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.AvatarFrameSpeed)));
        String[] speeds = new String[FrameParams.SPEEDS.length];
        int chosen = 1;
        for (int i = 0; i < speeds.length; i++) {
            speeds[i] = FrameParams.SPEEDS[i] + "×";
            if (FrameParams.SPEEDS[i].equals(getParam(FrameParams.K_SPEED))) {
                chosen = i;
            }
        }
        items.add(UItem.asSlideView(speeds, chosen, i -> setParam(FrameParams.K_SPEED, FrameParams.SPEEDS[i], true)).setId(ID_SPEED));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AvatarFrameGlow)));
        int glow = pendingFrame != null ? pendingFrame.glow : FrameParams.DEFAULT_GLOW;
        items.add(UItem.asIntSlideView(1, 0, glow, 100, v -> v + "%", v -> setParam(FrameParams.K_GLOW, String.valueOf(v), true)).setId(ID_GLOW));
    }

    /** Handles a click on one of the settings rows; true when consumed. */
    public boolean onSettingClick(UItem item) {
        if (pending == null) {
            return false;
        }
        String[] toggles = FrameParams.toggleKeys(pending);
        String key;
        if (item.id == ID_TOGGLE1 && toggles.length > 0) {
            key = toggles[0];
        } else if (item.id == ID_TOGGLE2 && toggles.length > 1) {
            key = toggles[1];
        } else if (item.id == ID_ANIM) {
            key = FrameParams.K_ANIM;
        } else {
            return false;
        }
        setParam(key, toggle(key) ? "0" : "1", false);
        return true;
    }

    private ChipsCell chips(Context context, String key) {
        String cacheKey = pending + "/" + key;
        ChipsCell c = chipCells.get(cacheKey);
        if (c == null) {
            c = new ChipsCell(context, pending, key);
            chipCells.put(cacheKey, c);
        }
        c.bind();
        return c;
    }

    public void save() {
        if (!canSave()) {
            return;
        }
        if (TextUtils.isEmpty(BadgesConfig.getBotUsername())) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.ExteraBadgeUnavailable)).show();
            return;
        }
        saving = true;
        notifyChanged();
        BadgesController.INSTANCE.updateFrame(pending, pendingParams, result -> AndroidUtilities.runOnUIThread(() -> {
            saving = false;
            if ("ok".equals(result)) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.AvatarFrameSaved)).show();
            } else {
                String reason;
                if (TextUtils.isEmpty(result)) {
                    reason = LocaleController.getString(R.string.UnknownError);
                } else if (result.contains("a frame comes with a badge")) {
                    reason = LocaleController.getString(R.string.AvatarFrameNeedBadge);
                } else if (result.contains("this frame is not included")) {
                    reason = LocaleController.getString(R.string.AvatarFrameNotIncluded);
                } else {
                    reason = result;
                }
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.ExteraBadgeSaveError, reason)).show();
                BadgesController.INSTANCE.refreshAllowedFrames(() -> {
                    cell.invalidateItems();
                    notifyChanged();
                });
            }
            cell.invalidateItems();
            notifyChanged();
        }));
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    public static String frameName(String style) {
        if (style == null) {
            return LocaleController.getString(R.string.AvatarFrameNone);
        }
        switch (style) {
            case FrameParams.INFERNO:
                return LocaleController.getString(R.string.AvatarFrameInferno);
            case FrameParams.PIXEL:
                return LocaleController.getString(R.string.AvatarFramePixel);
            case FrameParams.SOLITAIRE:
                return LocaleController.getString(R.string.AvatarFrameSolitaire);
            case FrameParams.COSMOS:
                return LocaleController.getString(R.string.AvatarFrameCosmos);
            case FrameParams.FROST:
                return LocaleController.getString(R.string.AvatarFrameFrost);
            case FrameParams.NEKO:
                return LocaleController.getString(R.string.AvatarFrameNeko);
            case FrameParams.ANGEL:
                return LocaleController.getString(R.string.AvatarFrameAngel);
            case FrameParams.DEVIL:
                return LocaleController.getString(R.string.AvatarFrameDevil);
            default:
                return style;
        }
    }

    /** Localized name of a setting key. */
    public static String keyName(String key) {
        int res;
        switch (key) {
            case FrameParams.K_PALETTE: res = R.string.AvatarFramePalette; break;
            case FrameParams.K_METAL: res = R.string.AvatarFrameMetal; break;
            case FrameParams.K_GEM: res = R.string.AvatarFrameGem; break;
            case FrameParams.K_GEMPOS: res = R.string.AvatarFrameGemPos; break;
            case FrameParams.K_EMBERS: res = R.string.AvatarFrameEmbers; break;
            case FrameParams.K_STARS: res = R.string.AvatarFrameStars; break;
            case FrameParams.K_PLANET: res = R.string.AvatarFramePlanet; break;
            case FrameParams.K_METEORS: res = R.string.AvatarFrameMeteors; break;
            case FrameParams.K_SNOW: res = R.string.AvatarFrameSnowfall; break;
            case FrameParams.K_CAP: res = R.string.AvatarFrameCap; break;
            case FrameParams.K_WHISKERS: res = R.string.AvatarFrameWhiskers; break;
            case FrameParams.K_PAW: res = R.string.AvatarFramePaw; break;
            case FrameParams.K_WINGS: res = R.string.AvatarFrameWings; break;
            case FrameParams.K_SPARKLE: res = R.string.AvatarFrameSparkle; break;
            case FrameParams.K_TAIL: res = R.string.AvatarFrameTail; break;
            case FrameParams.K_PULSE: res = R.string.AvatarFramePulse; break;
            default: return key;
        }
        return LocaleController.getString(res);
    }

    /** Localized name of a setting value. */
    public static String optionName(String value) {
        int res;
        switch (value) {
            case "fire": res = R.string.AvatarFrameOptFire; break;
            case "blue": res = R.string.AvatarFrameOptBlue; break;
            case "green": res = R.string.AvatarFrameOptGreen; break;
            case "purple": res = R.string.AvatarFrameOptPurple; break;
            case "pink": res = R.string.AvatarFrameOptPink; break;
            case "rainbow": res = R.string.AvatarFrameOptRainbow; break;
            case "pastel": res = R.string.AvatarFrameOptPastel; break;
            case "neon": res = R.string.AvatarFrameOptNeon; break;
            case "mono": res = R.string.AvatarFrameOptMono; break;
            case "gold": res = R.string.AvatarFrameOptGold; break;
            case "platinum": res = R.string.AvatarFrameOptPlatinum; break;
            case "rose": res = R.string.AvatarFrameOptRose; break;
            case "black": res = R.string.AvatarFrameOptBlack; break;
            case "diamond": res = R.string.AvatarFrameOptDiamond; break;
            case "ruby": res = R.string.AvatarFrameOptRuby; break;
            case "emerald": res = R.string.AvatarFrameOptEmerald; break;
            case "sapphire": res = R.string.AvatarFrameOptSapphire; break;
            case "amethyst": res = R.string.AvatarFrameOptAmethyst; break;
            case "none": res = R.string.AvatarFrameNone; break;
            case "bottom": res = R.string.AvatarFrameOptBottom; break;
            case "top": res = R.string.AvatarFrameOptTop; break;
            case "right": res = R.string.AvatarFrameOptRight; break;
            case "violet": res = R.string.AvatarFrameOptViolet; break;
            case "red": res = R.string.AvatarFrameOptRed; break;
            case "teal": res = R.string.AvatarFrameOptTeal; break;
            case "ice": res = R.string.AvatarFrameOptIce; break;
            case "snow": res = R.string.AvatarFrameOptSnow; break;
            case "aurora": res = R.string.AvatarFrameOptAurora; break;
            case "white": res = R.string.AvatarFrameOptWhite; break;
            case "ginger": res = R.string.AvatarFrameOptGinger; break;
            case "lilac": res = R.string.AvatarFrameOptLilac; break;
            case "silver": res = R.string.AvatarFrameOptSilver; break;
            case "sky": res = R.string.AvatarFrameOptSky; break;
            default: return value;
        }
        return LocaleController.getString(res);
    }

    /** A titled row of choice chips (palette / metal / gem / gem position) with colour dots. */
    @SuppressLint("ViewConstructor")
    private final class ChipsCell extends LinearLayout implements CustomPreferenceCell {

        private final String style;
        private final String key;
        private final LinearLayout row;

        ChipsCell(Context context, String style, String key) {
            super(context);
            this.style = style;
            this.key = key;
            setOrientation(VERTICAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            title.setText(keyName(key));
            addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 21, 8, 21, 0));
            HorizontalScrollView scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(AndroidUtilities.dp(15), 0, AndroidUtilities.dp(15), 0);
            row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            scroll.addView(row, new FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 10));
            String[] values = FrameParams.allowedValues(style, key);
            if (values != null) {
                for (int i = 0; i < values.length; i++) {
                    row.addView(new Chip(context, key, values[i], dotColor(i)), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 3, 0, 3, 0));
                }
            }
        }

        private int dotColor(int index) {
            if (FrameParams.K_GEM.equals(key)) {
                return FrameRenderer.gemColor(index);
            }
            if (FrameParams.K_GEMPOS.equals(key)) {
                return 0;
            }
            return FrameRenderer.paletteColor(style, index);
        }

        void bind() {
            for (int i = 0; i < row.getChildCount(); i++) {
                row.getChildAt(i).invalidate();
            }
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /** One choice: a colour dot (when the option has a colour) and its name; accent outline when selected. */
    @SuppressLint("ViewConstructor")
    private final class Chip extends View {

        private final String key, value, label;
        private final int dot;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Chip(Context context, String key, String value, int dot) {
            super(context);
            this.key = key;
            this.value = value;
            this.dot = dot;
            this.label = optionName(value);
            textPaint.setTextSize(AndroidUtilities.dp(14));
            setOnClickListener(v -> setParam(key, value, false));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = (int) Math.ceil(textPaint.measureText(label)) + AndroidUtilities.dp(dot != 0 ? 40 : 26);
            setMeasuredDimension(w, AndroidUtilities.dp(34));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean selected = value.equals(getParam(key));
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            r.set(AndroidUtilities.dp(1), AndroidUtilities.dp(1), getWidth() - AndroidUtilities.dp(1), getHeight() - AndroidUtilities.dp(1));
            float rad = r.height() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(selected ? Theme.multAlpha(accent, 0.14f) : Theme.getColor(Theme.key_windowBackgroundGray));
            canvas.drawRoundRect(r, rad, rad, paint);
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dp(1.5f));
                paint.setColor(accent);
                canvas.drawRoundRect(r, rad, rad, paint);
            }
            float x = AndroidUtilities.dp(13);
            if (dot != 0) {
                float cy = getHeight() / 2f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(dot);
                canvas.drawCircle(x + AndroidUtilities.dp(6), cy, AndroidUtilities.dp(6), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dp(1));
                paint.setColor(0x33000000);
                canvas.drawCircle(x + AndroidUtilities.dp(6), cy, AndroidUtilities.dp(6), paint);
                x += AndroidUtilities.dp(16);
            }
            textPaint.setColor(selected ? accent : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textPaint.setTypeface(selected ? AndroidUtilities.bold() : null);
            canvas.drawText(label, x, getHeight() / 2f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
        }
    }

    /** Horizontal row of frame styles shown on the user's own avatar: none, then AvatarFrames.STYLES. */
    @SuppressLint("ViewConstructor")
    private final class PickerCell extends HorizontalScrollView implements CustomPreferenceCell {

        private final LinearLayout row;

        PickerCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            setHorizontalScrollBarEnabled(false);
            setClipToPadding(false);
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(4), AndroidUtilities.dp(9), AndroidUtilities.dp(6));
            addView(row, new FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            row.addView(new FrameItem(context, null), LayoutHelper.createLinear(74, 98));
            for (String style : AvatarFrames.STYLES) {
                row.addView(new FrameItem(context, style), LayoutHelper.createLinear(74, 98));
            }
        }

        void invalidateItems() {
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child instanceof FrameItem) {
                    ((FrameItem) child).bind();
                }
            }
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /**
     * One frame option: the own avatar with that frame (the selected one with the settings being
     * chosen), its name, an accent check when selected and a lock when unavailable.
     */
    @SuppressLint("ViewConstructor")
    private final class FrameItem extends View {

        private static final int AVATAR_DP = 44;

        private final String style;
        private final FrameParams defaults;
        private final ImageReceiver avatar = new ImageReceiver(this);
        private final AvatarDrawable drawable = new AvatarDrawable();
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frameRect = new RectF();
        private final Path checkPath = new Path();
        private final String label;
        private Drawable lockDrawable;

        FrameItem(Context context, String style) {
            super(context);
            this.style = style;
            this.defaults = style != null ? FrameParams.defaults(style) : null;
            this.label = frameName(style);
            avatar.setRoundRadius(AndroidUtilities.dp(AVATAR_DP / 2f));
            textPaint.setTextSize(AndroidUtilities.dp(12));
            textPaint.setTextAlign(Paint.Align.CENTER);
            setOnClickListener(v -> select(style));
            bind();
        }

        void bind() {
            TLRPC.User user = user();
            if (user != null) {
                drawable.setInfo(account, user);
                avatar.setForUserOrChat(user, drawable);
            }
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            avatar.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            avatar.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean selected = Objects.equals(style, pending);
            boolean allowed = style == null || BadgesController.INSTANCE.canUseFrame(user(), style);
            float size = AndroidUtilities.dp(AVATAR_DP);
            float left = (getWidth() - size) / 2f;
            float top = AndroidUtilities.dp(14);
            float alpha = allowed ? 1f : 0.4f;
            avatar.setAlpha(alpha);
            avatar.setImageCoords(left, top, size, size);
            avatar.draw(canvas);
            if (style != null) {
                frameRect.set(left, top, left + size, top + size);
                FrameParams params = selected && pendingFrame != null ? pendingFrame : defaults;
                AvatarFrames.draw(canvas, frameRect, params, alpha, this, false);
            }
            float cx = left + size - AndroidUtilities.dp(4), cy = top + size - AndroidUtilities.dp(4);
            if (selected) {
                // check badge at the bottom-right: no ring outside the avatar, so it never reads as a story
                checkPaint.setStyle(Paint.Style.FILL);
                checkPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(10), checkPaint);
                checkPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(8), checkPaint);
                checkPaint.setStyle(Paint.Style.STROKE);
                checkPaint.setStrokeWidth(AndroidUtilities.dp(1.6f));
                checkPaint.setStrokeCap(Paint.Cap.ROUND);
                checkPaint.setColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
                checkPath.rewind();
                checkPath.moveTo(cx - AndroidUtilities.dp(3.5f), cy);
                checkPath.lineTo(cx - AndroidUtilities.dp(1), cy + AndroidUtilities.dp(2.5f));
                checkPath.lineTo(cx + AndroidUtilities.dp(3.5f), cy - AndroidUtilities.dp(2.5f));
                canvas.drawPath(checkPath, checkPaint);
            } else if (!allowed) {
                // lock badge at the same spot: frames come with a badge or a frame grant
                checkPaint.setStyle(Paint.Style.FILL);
                checkPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(10), checkPaint);
                checkPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(8), checkPaint);
                if (lockDrawable == null) {
                    lockDrawable = getContext().getResources().getDrawable(R.drawable.mini_switch_lock).mutate();
                }
                lockDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhite), PorterDuff.Mode.SRC_IN));
                int half = AndroidUtilities.dp(6);
                lockDrawable.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
                lockDrawable.draw(canvas);
            }
            textPaint.setColor(Theme.getColor(selected ? Theme.key_windowBackgroundWhiteBlueText4 : Theme.key_windowBackgroundWhiteGrayText));
            textPaint.setTypeface(selected ? AndroidUtilities.bold() : null);
            canvas.drawText(label, getWidth() / 2f, top + size + AndroidUtilities.dp(26), textPaint);
        }
    }
}
