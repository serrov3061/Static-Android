package com.exteragram.messenger.security;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TableView;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.Stars.StarsIntroActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * exteraGram 13.1.1 confirmation sheet for a sensitive request (SensitiveRequestSheet, layout from its smali):
 * accent circle with the stop icon, "Confirm action" with the category, a table (account, details, method),
 * the warning, a filled "Allow" and an outlined "Decline". "Allow" only reacts to genuine (non-injected) taps.
 * Same-kind requests asked while it is open are merged into it ({@link #merge}): amounts are summed, the
 * subtitle gets "×N" and Allow is disabled for a second so the user sees the new total before allowing.
 */
public class SensitiveRequestSheet extends BottomSheet {

    /** Detail keys (pairs of key, value). Text values index {@link #texts}. */
    public static final int RECIPIENT = 1, CHAT = 2, USER = 3, BOT = 4, STARS = 5, TON = 6, GIFT_TEXT = 7,
            FORM_ID = 8, INVOICE = 9, GIFT_ID = 10, ANONYMOUS = 11, UPGRADE = 12, GIFT_MESSAGE = 13, SAVED_GIFT = 14;

    private final ArrayList<Utilities.Callback<Boolean>> callbacks = new ArrayList<>();
    private final int constructor;
    private final String method;
    private long[] details;
    private final String[] texts;
    private final CharSequence category;
    private final SensitivePaymentForms.Payment payment;
    private final TextView subtitle;
    private final TableView tableView;
    private final ButtonWithCounterView allowButton;
    private final Runnable enableAllowButton;
    private boolean answered;
    private boolean verifiedDown;
    private long verifiedTapTime;

    public SensitiveRequestSheet(Context context, Theme.ResourcesProvider resourcesProvider, int account, int constructor, CharSequence category,
                                 String method, long[] details, String[] texts, Utilities.Callback<Boolean> callback) {
        super(context, false, resourcesProvider);
        this.currentAccount = account;
        this.constructor = constructor;
        this.method = method;
        this.details = details != null ? details : new long[0];
        this.texts = texts;
        this.category = category;
        long formId = 0;
        String invoiceHash = null;
        for (int i = 0; i + 1 < this.details.length; i += 2) {
            final long key = this.details[i], value = this.details[i + 1];
            if (key == FORM_ID) {
                formId = value;
            } else if (key == INVOICE && texts != null && value >= 0 && value < texts.length) {
                invoiceHash = texts[(int) value];
            }
        }
        this.payment = SensitivePaymentForms.get(currentAccount, formId, invoiceHash);
        callbacks.add(callback);
        fixNavigationBar();

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(18), AndroidUtilities.dp(18), AndroidUtilities.dp(18));
        icon.setImageResource(R.drawable.sensitive_request_stop);
        icon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN));
        icon.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(80), getThemedColor(Theme.key_featuredStickers_addButton)));
        layout.addView(icon, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));

        final TextView title = new TextView(context);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(LocaleController.getString(R.string.SensitiveRequestTitle));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 16, 24, 0));

        subtitle = new TextView(context);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTypeface(AndroidUtilities.regular());
        subtitle.setText(category);
        subtitle.setVisibility(TextUtils.isEmpty(category) ? View.GONE : View.VISIBLE);
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 6, 24, 0));

        tableView = new TableView(context, resourcesProvider);
        fillTable();
        layout.addView(tableView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 20, 16, 0));

        final TextView warning = new TextView(context);
        warning.setGravity(Gravity.CENTER_HORIZONTAL);
        warning.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        warning.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        warning.setTypeface(AndroidUtilities.regular());
        warning.setText(LocaleController.getString(R.string.SensitiveRequestWarning));
        layout.addView(warning, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 14, 24, 0));

        allowButton = new ButtonWithCounterView(context, true, resourcesProvider);
        allowButton.setRound();
        allowButton.setText(LocaleController.getString(R.string.Allow), false);
        allowButton.setOnClickListener(v -> {
            if (consumeVerifiedTap()) {
                finish(true);
                dismiss();
            }
        });
        enableAllowButton = () -> allowButton.setEnabled(true);
        layout.addView(allowButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 20, 16, 8));

        final ButtonWithCounterView declineButton = new ButtonWithCounterView(context, false, resourcesProvider);
        declineButton.setRound();
        declineButton.setNeutral();
        declineButton.setText(LocaleController.getString(R.string.Decline), false);
        declineButton.setOnClickListener(v -> {
            finish(false);
            dismiss();
        });
        layout.addView(declineButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 16, 8));

        final ScrollView scrollView = new ScrollView(context);
        scrollView.addView(layout);
        setCustomView(scrollView);
        setOnDismissListener(d -> {
            AndroidUtilities.cancelRunOnUIThread(enableAllowButton);
            finish(false);
        });
    }

    private void fillTable() {
        addPeerRow(tableView, LocaleController.getString(R.string.Account), UserConfig.getInstance(currentAccount).getClientUserId());
        for (int i = 0; i + 1 < details.length; i += 2) {
            addDetailRow(tableView, (int) details[i], details[i + 1], texts);
        }
        if (constructor == TL_stars.TL_payments_sendStarsForm.constructor || constructor == TLRPC.TL_payments_sendPaymentForm.constructor) {
            boolean hasForm = false;
            for (int i = 0; i + 1 < details.length; i += 2) {
                hasForm |= details[i] == FORM_ID;
            }
            if (!hasForm) {
                tableView.addRow(LocaleController.getString(R.string.SensitiveRequestDebit), LocaleController.getString(R.string.SensitiveRequestUnknownAmount));
            }
        }
        if (!TextUtils.isEmpty(method)) {
            tableView.addRowMonospaced(LocaleController.getString(R.string.SensitiveRequestMethod), method, 13, null);
        }
    }

    private static CharSequence stars(long amount) {
        return StarsIntroActivity.replaceStars(false, "XTR " + LocaleController.formatNumber(amount, ','), 0.85f);
    }

    private static CharSequence ton(long nanos) {
        return StarsIntroActivity.replaceStars(true, "XTR " + BigDecimal.valueOf(nanos, 9).stripTrailingZeros().toPlainString(), 0.85f);
    }

    private void addDetailRow(TableView table, int key, long value, String[] texts) {
        switch (key) {
            case RECIPIENT:
                addPeerRow(table, LocaleController.getString(R.string.WalletRecipient), value);
                break;
            case CHAT:
                addPeerRow(table, LocaleController.getString(R.string.SensitiveRequestChat), value);
                break;
            case USER:
                addPeerRow(table, LocaleController.getString(R.string.SensitiveRequestUser), value);
                break;
            case BOT:
                addPeerRow(table, LocaleController.getString(R.string.StarsSubscriptionBot), value);
                break;
            case STARS:
                table.addRow(LocaleController.getString(R.string.SensitiveRequestAmount), stars(value));
                break;
            case TON:
                table.addRow(LocaleController.getString(R.string.SensitiveRequestAmount), ton(value));
                break;
            case GIFT_TEXT:
                if (texts != null && value >= 0 && value < texts.length && !TextUtils.isEmpty(texts[(int) value])) {
                    table.addRow(LocaleController.getString(R.string.ActionStarGift), texts[(int) value]);
                }
                break;
            case FORM_ID: {
                CharSequence text = LocaleController.getString(R.string.SensitiveRequestUnknownAmount);
                if (payment != null && "XTR".equals(payment.currency)) {
                    text = stars(payment.amount);
                } else if (payment != null && "TON".equals(payment.currency)) {
                    text = ton(payment.amount);
                }
                table.addRow(LocaleController.getString(R.string.SensitiveRequestDebit), text);
                break;
            }
            case GIFT_ID: {
                final TL_stars.StarGift gift = StarsController.getInstance(currentAccount).getStarGift(value);
                table.addRow(LocaleController.getString(R.string.ActionStarGift), gift == null || TextUtils.isEmpty(gift.title) ? "ID " + value : gift.title + " \u00b7 ID " + value);
                break;
            }
            case ANONYMOUS:
                table.addRow(LocaleController.getString(R.string.SensitiveRequestAnonymous), LocaleController.getString(value != 0 ? R.string.CheckPhoneNumberYes : R.string.CheckPhoneNumberNo));
                break;
            case UPGRADE:
                table.addRow(LocaleController.getString(R.string.SensitiveRequestUpgradeIncluded), LocaleController.getString(value != 0 ? R.string.CheckPhoneNumberYes : R.string.CheckPhoneNumberNo));
                break;
            case GIFT_MESSAGE:
                table.addRow(LocaleController.getString(R.string.SensitiveRequestGiftMessage), Long.toString(value));
                break;
            case SAVED_GIFT:
                table.addRow(LocaleController.getString(R.string.SensitiveRequestSavedGift), Long.toString(value));
                break;
        }
    }

    private void addPeerRow(TableView table, CharSequence title, long dialogId) {
        if (dialogId == 0) dialogId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (table.addRowUser(title, currentAccount, dialogId, null) == null) {
            table.addRow(title, "ID " + dialogId);
        }
    }

    /**
     * exteraGram: same keys in the same order, equal values except the amounts (STARS / TON), which are
     * summed; null when the requests differ (or a sum overflows).
     */
    private static long[] mergeDetails(long[] a, long[] b) {
        if (a.length != b.length) return null;
        final long[] out = a.clone();
        for (int i = 0; i + 1 < a.length; i += 2) {
            if (a[i] != b[i]) return null;
            if (a[i] == STARS || a[i] == TON) {
                try {
                    out[i + 1] = Math.addExact(a[i + 1], b[i + 1]);
                } catch (ArithmeticException e) {
                    return null;
                }
            } else if (a[i + 1] != b[i + 1]) {
                return null;
            }
        }
        return out;
    }

    /** Joins another request into this open sheet; false when it has to be asked separately. */
    public boolean merge(int account, int constructor, long[] details, String[] texts, Utilities.Callback<Boolean> callback) {
        if (answered || !isShowing() || account != currentAccount || constructor != this.constructor || !Arrays.equals(this.texts, texts)) {
            return false;
        }
        final long[] merged = mergeDetails(this.details, details != null ? details : new long[0]);
        if (merged == null) {
            return false;
        }
        this.details = merged;
        callbacks.add(callback);
        subtitle.setText(TextUtils.isEmpty(category)
                ? String.format(Locale.US, "\u00d7%d", callbacks.size())
                : String.format(Locale.US, "%s \u00d7%d", category, callbacks.size()));
        subtitle.setVisibility(View.VISIBLE);
        tableView.clear();
        fillTable();
        allowButton.setEnabled(false);
        AndroidUtilities.cancelRunOnUIThread(enableAllowButton);
        AndroidUtilities.runOnUIThread(enableAllowButton, 1000);
        return true;
    }

    // ---- only genuine taps on "Allow" count (an accessibility/automation injection is ignored)

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            verifiedDown = isOverAllowButton(event) && isGenuine(event);
        } else if (action == MotionEvent.ACTION_UP) {
            if (verifiedDown && isOverAllowButton(event) && isGenuine(event)) {
                verifiedTapTime = event.getEventTime();
            }
            verifiedDown = false;
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            verifiedDown = false;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && isConfirmKey(event.getKeyCode()) && allowButton.isFocused() && isGenuine(event)) {
            verifiedTapTime = event.getEventTime();
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isOverAllowButton(MotionEvent event) {
        if (!allowButton.isShown() || !allowButton.isEnabled()) return false;
        final int[] location = new int[2];
        allowButton.getLocationOnScreen(location);
        final float x = event.getRawX(), y = event.getRawY();
        return x >= location[0] && x < location[0] + allowButton.getWidth() && y >= location[1] && y < location[1] + allowButton.getHeight();
    }

    private static boolean isConfirmKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /**
     * exteraGram: the system service wrapper must still talk to the real system service: every non-null
     * field of the binder interface type has to be the framework's {@code $Stub$Proxy} over a
     * {@code android.os.BinderProxy}, both from the boot class loader. A plugin that swapped the
     * InputManager / AccessibilityManager service proxy (to fake verifyInputEvent) fails this check.
     */
    private static boolean hasSystemBinders(Object service, String interfaceName) {
        final ClassLoader boot = Object.class.getClassLoader();
        final ArrayList<Object> objects = new ArrayList<>();
        objects.add(service);
        if (Build.VERSION.SDK_INT >= 34 && service instanceof InputManager) {
            try {
                objects.add(Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null));
            } catch (Throwable ignore) {
            }
        }
        for (Object object : objects) {
            if (object == null) continue;
            for (Class<?> cls = object.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                final java.lang.reflect.Field[] fields;
                try {
                    fields = cls.getDeclaredFields();
                } catch (Throwable e) {
                    continue;
                }
                for (java.lang.reflect.Field field : fields) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !interfaceName.equals(field.getType().getName())) {
                        continue;
                    }
                    final Object value;
                    try {
                        field.setAccessible(true);
                        value = field.get(object);
                    } catch (Throwable e) {
                        continue;
                    }
                    if (value == null) continue;
                    final Class<?> valueClass = value.getClass();
                    if (!(interfaceName + "$Stub$Proxy").equals(valueClass.getName()) || valueClass.getClassLoader() != boot
                            || !(value instanceof android.os.IInterface)) {
                        return false;
                    }
                    final android.os.IBinder binder = ((android.os.IInterface) value).asBinder();
                    if (binder == null || !"android.os.BinderProxy".equals(binder.getClass().getName()) || binder.getClass().getClassLoader() != boot) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean isGenuine(InputEvent event) {
        if (Build.VERSION.SDK_INT >= 30) {
            final InputManager inputManager = getContext().getSystemService(InputManager.class);
            return inputManager != null && hasSystemBinders(inputManager, "android.hardware.input.IInputManager")
                    && inputManager.verifyInputEvent(event) != null;
        }
        if (event instanceof MotionEvent) {
            return event.getDeviceId() > 0 && event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        }
        return event.getDeviceId() > 0 && event.getDeviceId() != -1;
    }

    private boolean consumeVerifiedTap() {
        final long time = verifiedTapTime;
        verifiedTapTime = 0;
        if (time != 0 && SystemClock.uptimeMillis() - time <= 1000) {
            return true;
        }
        // screen readers deliver clicks without a touch
        final android.view.accessibility.AccessibilityManager am = (android.view.accessibility.AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && hasSystemBinders(am, "android.view.accessibility.IAccessibilityManager")
                && !am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    public boolean isAnswered() {
        return answered;
    }

    /** Keeps the sheet above other dialogs that a plugin may open over it. */
    public void bringToFront() {
        final Window window = getWindow();
        if (answered || !isShowing() || window == null) return;
        final View decor = window.getDecorView();
        final android.view.ViewGroup.LayoutParams lp = decor.getLayoutParams();
        if (!decor.isAttachedToWindow() || lp == null) return;
        final WindowManager wm = window.getWindowManager();
        try {
            wm.removeViewImmediate(decor);
            wm.addView(decor, lp);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void finish(boolean allowed) {
        if (answered) return;
        answered = true;
        for (Utilities.Callback<Boolean> c : new ArrayList<>(callbacks)) {
            c.run(allowed);
        }
    }
}
