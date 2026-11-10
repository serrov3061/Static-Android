package com.exteragram.messenger.security;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;


import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.util.Date;

/**
 * exteraGram 13.1.1 switch for plugin action confirmations. Turning confirmations off needs the device
 * credential and only takes effect after {@link #DISABLE_DELAY} (a notification is shown meanwhile),
 * so a plugin cannot silently turn the protection off. exteraGram keeps this state in native code;
 * StaticGram keeps it in private preferences.
 */
public final class SensitiveRequestSwitch {

    public static final long DISABLE_DELAY = 6 * 60 * 60 * 1000L;
    public static final int STATE_ENABLED = 0;
    public static final int STATE_PENDING = 1;
    public static final int STATE_DISABLED = 2;
    private static final int REQUEST_CODE = 22400;
    private static final int NOTIFICATION_ID = 4100;

    private static Runnable pendingCredentialSuccess;
    private static final Runnable updateNotification = SensitiveRequestSwitch::updateNotification;

    private SensitiveRequestSwitch() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sensitive_requests", Context.MODE_PRIVATE);
    }

    public static void init(Context context) {
        try {
            AndroidUtilities.runOnUIThread(updateNotification);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static long getDisableAt() {
        return preferences().getLong("disable_at", 0);
    }

    public static int getState() {
        final long disableAt = getDisableAt();
        if (disableAt == 0) return STATE_ENABLED;
        return System.currentTimeMillis() >= disableAt ? STATE_DISABLED : STATE_PENDING;
    }

    /** Confirmations are asked while enabled or while turning off is still pending. */
    public static boolean isActive() {
        return getState() != STATE_DISABLED;
    }

    public static String formatDisableAt() {
        final Date date = new Date(getDisableAt());
        return LocaleController.formatString(R.string.formatDateAtTime, LocaleController.getInstance().getFormatterDayMonth().format(date), LocaleController.getInstance().getFormatterDay().format(date));
    }

    /** Turns confirmations (back) on, cancelling a pending turn-off. */
    public static void enforce() {
        preferences().edit().remove("disable_at").remove("notified_disable_at").apply();
        AndroidUtilities.cancelRunOnUIThread(updateNotification);
        cancelNotification();
    }

    public static void requestDisable(BaseFragment fragment, Runnable onDone) {
        final Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null || getState() != STATE_ENABLED) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(activity, fragment.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.SensitiveRequestsDisableTitle))
                .setMessage(LocaleController.getString(R.string.SensitiveRequestsDisableText))
                .setPositiveButton(LocaleController.getString(R.string.SensitiveRequestsDisableAction), (d, w) -> authenticate(fragment, () -> {
                    preferences().edit().putLong("disable_at", System.currentTimeMillis() + DISABLE_DELAY).apply();
                    updateNotification();
                    if (onDone != null) onDone.run();
                }))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        fragment.showDialog(dialog);
        dialog.redPositive();
    }

    public static boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_CODE) {
            return false;
        }
        final Runnable runnable = pendingCredentialSuccess;
        pendingCredentialSuccess = null;
        if (runnable != null && resultCode == Activity.RESULT_OK) {
            runnable.run();
        }
        return true;
    }

    private static void authenticate(BaseFragment fragment, Runnable onSuccess) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        final KeyguardManager keyguard = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            fragment.showDialog(new AlertDialog.Builder(activity, fragment.getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.SensitiveRequestsAuthTitle))
                    .setMessage(LocaleController.getString(R.string.SensitiveRequestsScreenLockRequired))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .create());
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                final BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                        .setTitle(LocaleController.getString(R.string.SensitiveRequestsAuthTitle))
                        .setSubtitle(LocaleController.getString(R.string.SensitiveRequestsDisableTitle));
                if (Build.VERSION.SDK_INT >= 30) {
                    builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else {
                    builder.setDeviceCredentialAllowed(true);
                }
                builder.build().authenticate(new CancellationSignal(), activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        onSuccess.run();
                    }
                });
            } else {
                final Intent intent = keyguard.createConfirmDeviceCredentialIntent(LocaleController.getString(R.string.SensitiveRequestsAuthTitle), LocaleController.getString(R.string.SensitiveRequestsDisableTitle));
                if (intent == null) return;
                pendingCredentialSuccess = onSuccess;
                activity.startActivityForResult(intent, REQUEST_CODE);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void updateNotification() {
        AndroidUtilities.cancelRunOnUIThread(updateNotification);
        try {
            final int state = getState();
            if (state == STATE_PENDING) {
                final long disableAt = getDisableAt();
                AndroidUtilities.runOnUIThread(updateNotification, Math.max(1000L, disableAt - System.currentTimeMillis() + 1000));
                final String text = LocaleController.getString(R.string.SensitiveRequestsPendingNotificationText);
                showNotification(createBuilder()
                        .setContentTitle(LocaleController.getString(R.string.SensitiveRequestsPendingNotificationTitle))
                        .setContentText(text)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setWhen(disableAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                        .setTimeoutAfter(Math.max(1L, disableAt - System.currentTimeMillis())));
            } else if (state == STATE_DISABLED) {
                final long disableAt = getDisableAt();
                final SharedPreferences prefs = preferences();
                if (prefs.getLong("notified_disable_at", 0) != disableAt) {
                    prefs.edit().putLong("notified_disable_at", disableAt).apply();
                    final String text = LocaleController.getString(R.string.SensitiveRequestsDisabledNotificationText);
                    showNotification(createBuilder()
                            .setContentTitle(LocaleController.getString(R.string.SensitiveRequestsDisabledNotificationTitle))
                            .setContentText(text)
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                            .setAutoCancel(true));
                }
            } else {
                cancelNotification();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static NotificationCompat.Builder createBuilder() {
        final Context context = ApplicationLoader.applicationContext;
        return new NotificationCompat.Builder(context, "sensitive_requests")
                .setSmallIcon(R.drawable.notification)
                .setColor(0xff11acfa)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, new Intent(context, LaunchActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    @SuppressLint("MissingPermission")
    private static void showNotification(NotificationCompat.Builder builder) {
        final NotificationManagerCompat manager = NotificationManagerCompat.from(ApplicationLoader.applicationContext);
        manager.createNotificationChannel(new NotificationChannelCompat.Builder("sensitive_requests", NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(LocaleController.getString(R.string.SensitiveRequestsNotificationChannel)).build());
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static void cancelNotification() {
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
