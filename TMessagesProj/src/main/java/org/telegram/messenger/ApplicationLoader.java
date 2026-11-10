/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import org.json.JSONObject;
import org.telegram.messenger.utils.Choreographer60FpsContent;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LauncherIconController;

import java.io.File;
import java.util.Locale;

public class ApplicationLoader extends Application {

    // StaticGram: plugins (Chaquopy dynamic proxies) register ActivityLifecycleCallbacks
    // that implement only the classic methods. Android 16 calls the newer default methods
    // too (onActivityConfigurationChanged), the proxy throws AttributeError and the whole
    // app crashes on rotation. Proxies are wrapped: every callback is forwarded in try/catch.
    private final java.util.Map<ActivityLifecycleCallbacks, ActivityLifecycleCallbacks> safeLifecycleCallbacks =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    @Override
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        if (callback != null && java.lang.reflect.Proxy.isProxyClass(callback.getClass())) {
            final ActivityLifecycleCallbacks safe = new SafeLifecycleCallbacks(callback);
            safeLifecycleCallbacks.put(callback, safe);
            super.registerActivityLifecycleCallbacks(safe);
            return;
        }
        super.registerActivityLifecycleCallbacks(callback);
    }

    @Override
    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        final ActivityLifecycleCallbacks safe = callback == null ? null : safeLifecycleCallbacks.remove(callback);
        super.unregisterActivityLifecycleCallbacks(safe != null ? safe : callback);
    }

    private static final class SafeLifecycleCallbacks implements ActivityLifecycleCallbacks {
        private final ActivityLifecycleCallbacks target;

        SafeLifecycleCallbacks(ActivityLifecycleCallbacks target) {
            this.target = target;
        }

        private static void failed(Throwable e) {
            FileLog.e(e);
        }

        @Override public void onActivityCreated(@androidx.annotation.NonNull android.app.Activity a, android.os.Bundle b) { try { target.onActivityCreated(a, b); } catch (Throwable e) { failed(e); } }
        @Override public void onActivityStarted(@androidx.annotation.NonNull android.app.Activity a) { try { target.onActivityStarted(a); } catch (Throwable e) { failed(e); } }
        @Override public void onActivityResumed(@androidx.annotation.NonNull android.app.Activity a) { try { target.onActivityResumed(a); } catch (Throwable e) { failed(e); } }
        @Override public void onActivityPaused(@androidx.annotation.NonNull android.app.Activity a) { try { target.onActivityPaused(a); } catch (Throwable e) { failed(e); } }
        @Override public void onActivityStopped(@androidx.annotation.NonNull android.app.Activity a) { try { target.onActivityStopped(a); } catch (Throwable e) { failed(e); } }
        @Override public void onActivitySaveInstanceState(@androidx.annotation.NonNull android.app.Activity a, @androidx.annotation.NonNull android.os.Bundle b) { try { target.onActivitySaveInstanceState(a, b); } catch (Throwable e) { failed(e); } }
        @Override public void onActivityDestroyed(@androidx.annotation.NonNull android.app.Activity a) { try { target.onActivityDestroyed(a); } catch (Throwable e) { failed(e); } }
        // The newer default methods (pre/post callbacks, configuration changes) are not
        // forwarded: the plugin proxies do not implement them.
    }

    public static ApplicationLoader applicationLoaderInstance;

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile NetworkInfo currentNetworkInfo;
    public static volatile Handler applicationHandler;

    private static ConnectivityManager connectivityManager;
    private static volatile boolean applicationInited = false;
    private static volatile  ConnectivityManager.NetworkCallback networkCallback;
    private static long lastNetworkCheckTypeTime;
    private static int lastKnownNetworkType = -1;

    public static long startTime;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean mainInterfaceStopped = true;
    public static volatile boolean externalInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static boolean canDrawOverlays;
    public static volatile long mainInterfacePausedStageQueueTime;

    private static PushListenerController.IPushListenerServiceProvider pushProvider;
    private static IMapsProvider mapsProvider;
    private static ILocationServiceProvider locationServiceProvider;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    // exteraGram: icon packs - drawables requested through the application context (Theme.*, MessageObject, ...) go through ExteraResources too
    private com.exteragram.messenger.icons.ExteraResources exteraAppResources;

    @Override
    public android.content.res.Resources getResources() {
        android.content.res.Resources resources = super.getResources();
        try {
            if (resources == null || applicationContext == null || !com.exteragram.messenger.icons.IconManager.INSTANCE.hasReplacements()) {
                return resources;
            }
            com.exteragram.messenger.icons.ExteraResources wrapper = exteraAppResources;
            if (wrapper == null || wrapper.getSourceAssets() != resources.getAssets()) {
                wrapper = new com.exteragram.messenger.icons.ExteraResources(resources);
                exteraAppResources = wrapper;
            } else if (!wrapper.getConfiguration().equals(resources.getConfiguration())) {
                wrapper.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
            }
            return wrapper;
        } catch (Throwable e) {
            FileLog.e(e);
            return resources;
        }
    }

    public static ILocationServiceProvider getLocationServiceProvider() {
        if (locationServiceProvider == null) {
            locationServiceProvider = applicationLoaderInstance.onCreateLocationServiceProvider();
            locationServiceProvider.init(applicationContext);
        }
        return locationServiceProvider;
    }

    protected ILocationServiceProvider onCreateLocationServiceProvider() {
        return new GoogleLocationProvider();
    }

    public static IMapsProvider getMapsProvider() {
        if (mapsProvider == null) {
            mapsProvider = applicationLoaderInstance.onCreateMapsProvider();
        }
        return mapsProvider;
    }

    protected IMapsProvider onCreateMapsProvider() {
        return new GoogleMapsProvider();
    }

    public static PushListenerController.IPushListenerServiceProvider getPushProvider() {
        if (pushProvider == null) {
            pushProvider = applicationLoaderInstance.onCreatePushProvider();
        }
        return pushProvider;
    }

    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return PushListenerController.GooglePushListenerServiceProvider.INSTANCE;
    }

    public static String getApplicationId() {
        return applicationLoaderInstance.onGetApplicationId();
    }

    protected String onGetApplicationId() {
        return null;
    }

    public static boolean isHuaweiStoreBuild() {
        return applicationLoaderInstance.isHuaweiBuild();
    }

    public static boolean isStandaloneBuild() {
        return applicationLoaderInstance.isStandalone();
    }

    public static boolean isBetaBuild() {
        return applicationLoaderInstance.isBeta();
    }

    public static boolean isAndroidTestEnvironment() {
        return applicationLoaderInstance.isAndroidTestEnv();
    }

    protected boolean isHuaweiBuild() {
        return false;
    }

    protected boolean isStandalone() {
        return false;
    }

    protected boolean isBeta() {
        return false;
    }

    protected boolean isAndroidTestEnv() {
        return false;
    }

    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            File path = ApplicationLoader.applicationContext.getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new File("/data/data/org.telegram.messenger/files");
    }

    public static File getFilesDirFixed(String child) {
        try {
            File path = getFilesDirFixed();
            File dir = new File(path, child);
            dir.mkdirs();

            return dir;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static void postInitApplication() {
        if (applicationInited || applicationContext == null) {
            return;
        }
        applicationInited = true;
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            LocaleController.getInstance(); //TODO improve
        } catch (Exception e) {
            e.printStackTrace();
        }
        com.exteragram.messenger.icons.IconManager.INSTANCE.onPostInitApplication(); // exteraGram: ApplicationLoader.smali:1959

        try {
            connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    } catch (Throwable ignore) {

                    }
                    checkProxyForNetworkState(); // exteraGram

                    boolean isSlow = isConnectionSlow();
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ConnectionsManager.getInstance(a).checkConnection();
                        FileLoader.getInstance(a).onNetworkChanged(isSlow);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            ApplicationLoader.applicationContext.registerReceiver(networkStateReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        LiteMode.registerBatteryReceiver();

        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen state = " + isScreenOn);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedConfig.loadConfig();
        SharedPrefsHelper.init(applicationContext);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            UserConfig.getInstance(a).loadConfig();
            MessagesController.getInstance(a);
            if (a == 0) {
                SharedConfig.pushStringStatus = "__FIREBASE_GENERATING_SINCE_" + ConnectionsManager.getInstance(a).getCurrentTime() + "__";
            } else {
                ConnectionsManager.getInstance(a);
            }
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user != null) {
                MessagesController.getInstance(a).putUser(user, true);
                SendMessagesHelper.getInstance(a).checkUnsentMessages();
            }
        }

        checkProxyForNetworkState(); // exteraGram
        ApplicationLoader app = (ApplicationLoader) ApplicationLoader.applicationContext;
        app.initPushServices();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app initied");
        }

        MediaController.getInstance();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            ContactsController.getInstance(a).checkAppAccount();
            DownloadController.getInstance(a);
        }
        BillingController.getInstance().startConnection();
    }

    public ApplicationLoader() {
        super();
    }

    @Override
    public void onCreate() {
        applicationLoaderInstance = this;
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {

        }

        super.onCreate();

        // AndroidUtilities must be initialized before FileLog
        final String helloWorld = AndroidUtilities.getHelloWorld();

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d(helloWorld);
            FileLog.d("app start time = " + (startTime = SystemClock.elapsedRealtime()));
            try {
                final PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                final String abi;
                switch (info.versionCode % 10) {
                    case 1:
                    case 2:
                        abi = "store bundled " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        break;
                    default:
                    case 9:
                        if (ApplicationLoader.isStandaloneBuild()) {
                            abi = "direct " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        } else {
                            abi = "universal " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        }
                        break;
                }
                FileLog.d("buildVersion = " + String.format(Locale.US, "v%s (%d[%d]) %s", info.versionName, info.versionCode / 10, info.versionCode % 10, abi));
            } catch (Exception e) {
                FileLog.e(e);
            }
            FileLog.d("device = manufacturer=" + Build.MANUFACTURER + ", device=" + Build.DEVICE + ", model=" + Build.MODEL + ", product=" + Build.PRODUCT);
        }
        if (applicationContext == null) {
            applicationContext = getApplicationContext();
        }

        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            ConnectionsManager.native_setJava(false);
        } catch (UnsatisfiedLinkError error) {
            throw new RuntimeException("can't load native libraries " +  Build.CPU_ABI + " lookup folder " + NativeLoader.getAbiFolder());
        }
        // exteraGram plugins: native crash flag (no-op when the native handler is absent) and ART tweaks
        com.exteragram.messenger.plugins.utils.NativeCrashHandler.safeInit();
        try {
            com.exteragram.messenger.plugins.PluginsController.applyArtOpts();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        installPluginCrashMarker();
        com.exteragram.messenger.security.SensitiveRequestSwitch.init(this); // exteraGram 13.1.1: plugin action confirmations
        SgCrashReporter.install(); // StaticGram: save uncaught crash trace, offer to send on next start
        new ForegroundDetector(this) {
            @Override
            public void onActivityStarted(Activity activity) {
                boolean wasInBackground = isBackground();
                super.onActivityStarted(activity);
                if (wasInBackground) {
                    ensureCurrentNetworkGet(true);
                }
            }
        };
        if (BuildConfig.DEBUG_VERSION) {
            new ANRDetector(FileLog::dumpANR);
        }

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("load libs time = " + (SystemClock.elapsedRealtime() - startTime));
        }

        applicationHandler = new Handler(applicationContext.getMainLooper());

        com.exteragram.messenger.utils.network.RemoteUtils.initCached(); // exteraGram

        AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);

        LauncherIconController.tryFixLauncherIconIfNeeded();
        ProxyRotationController.init();
        try {
            // exteraGram 13.1.1 debug monitors (off by default, nothing runs when off)
            com.exteragram.messenger.debug.HeapMonitor.init();
            com.exteragram.messenger.debug.LoadMonitor.init();
            com.exteragram.messenger.debug.FreezeMonitor.init();
        } catch (Throwable t) {
            FileLog.e(t);
        }

        //if (BuildConfig.DEBUG_PRIVATE_VERSION) {
        //    Choreographer60FpsContent.getInstance().addFrameCallback(debugEverySecondChecks, 1);
        //}
    }

    // exteraGram plugins: remember an uncaught crash (and the plugin whose id is in the trace) so the next start can use safe mode
    private static void installPluginCrashMarker() {
        try {
            if (!com.exteragram.messenger.ExteraConfig.getPluginsEngine()) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                com.exteragram.messenger.plugins.PluginsController controller = com.exteragram.messenger.plugins.PluginsController.getInstance();
                if (!controller.getPlugins().isEmpty()) {
                    String trace = android.util.Log.getStackTraceString(throwable);
                    android.content.SharedPreferences.Editor editor = controller.getPreferences().edit().putBoolean("had_crash", true);
                    for (String id : controller.getPlugins().keySet()) {
                        if (trace.contains(id)) {
                            editor.putString("crashed_plugin_id", id);
                        }
                    }
                    editor.commit();
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private final Runnable debugEverySecondChecks = () -> AndroidUtilities.runOnUIThread(() -> {
        NotificationCenter.sanitize();
    });

    public static void startPushService() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        boolean enabled;
        if (preferences.contains("pushService")) {
            enabled = preferences.getBoolean("pushService", true);
        } else {
            enabled = MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false);
        }
        if (enabled) {
            Intent intent = new Intent(applicationContext, NotificationsService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // foreground keep-alive: survives in background without FCM
                    applicationContext.startForegroundService(intent);
                } else {
                    applicationContext.startService(intent);
                }
            } catch (Throwable e) {
                // Android 12+ refuses a foreground start from the background
                try {
                    applicationContext.startService(intent);
                } catch (Throwable ignore) {

                }
            }
        } else {
            applicationContext.stopService(new Intent(applicationContext, NotificationsService.class));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
            VideoCapturerDevice.checkScreenCapturerSize();
            AndroidUtilities.resetTabletFlag();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initPushServices() {
        AndroidUtilities.runOnUIThread(() -> {
            if (getPushProvider().hasServices()) {
                getPushProvider().onRequestPushToken();
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("No valid " + getPushProvider().getLogTitle() + " APK found.");
                }
                SharedConfig.pushStringStatus = "__NO_GOOGLE_PLAY_SERVICES__";
                PushListenerController.sendRegistrationToServer(getPushProvider().getPushType(), null);
            }
        }, 1000);
    }

    private boolean checkPlayServices() {
        try {
            int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
            return resultCode == ConnectionResult.SUCCESS;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    private static long lastNetworkCheck = -1;
    private static void ensureCurrentNetworkGet() {
        final long now = System.currentTimeMillis();
        ensureCurrentNetworkGet(now - lastNetworkCheck > 5000);
        lastNetworkCheck = now;
    }

    private static void ensureCurrentNetworkGet(boolean force) {
        if (force || currentNetworkInfo == null) {
            try {
                if (connectivityManager == null) {
                    connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                }
                currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (networkCallback == null) {
                        networkCallback = new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(@NonNull Network network) {
                                lastKnownNetworkType = -1;
                                AndroidUtilities.runOnUIThread(ApplicationLoader::checkProxyForNetworkState); // exteraGram
                            }

                            @Override
                            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                                lastKnownNetworkType = -1;
                                AndroidUtilities.runOnUIThread(ApplicationLoader::checkProxyForNetworkState); // exteraGram
                            }

                            // exteraGram
                            @Override
                            public void onLost(@NonNull Network network) {
                                lastKnownNetworkType = -1;
                                AndroidUtilities.runOnUIThread(ApplicationLoader::checkProxyForNetworkState);
                            }
                        };
                        connectivityManager.registerDefaultNetworkCallback(networkCallback);
                    }
                }
            } catch (Throwable ignore) {

            }
        }
    }

    // exteraGram: auto-disable proxy by network (ExteraConfig doNotUseProxy bitmask, ProxyDisableCondition).
    // doNotUseProxy == 0 and no pending auto-disable -> no-op (stock behaviour).
    public static void checkProxyForNetworkState() {
        if (applicationContext == null) {
            return;
        }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(ApplicationLoader::checkProxyForNetworkState);
            return;
        }
        try {
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            boolean autoDisabled = preferences.getBoolean("proxy_auto_disabled_by_vpn", false);
            int mask = com.exteragram.messenger.ExteraConfig.getDoNotUseProxy();
            if (mask == 0 && !autoDisabled) {
                return;
            }
            boolean shouldDisable = (com.exteragram.messenger.ExteraConfig.isProxyDisabledOn(com.exteragram.messenger.ProxyDisableCondition.VPN) && isVpnEnabled())
                    || (com.exteragram.messenger.ExteraConfig.isProxyDisabledOn(com.exteragram.messenger.ProxyDisableCondition.MOBILE_DATA) && hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                    || (com.exteragram.messenger.ExteraConfig.isProxyDisabledOn(com.exteragram.messenger.ProxyDisableCondition.WIFI) && hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            if (shouldDisable) {
                if (autoDisabled) {
                    return;
                }
                boolean enabled = preferences.getBoolean("proxy_enabled", false);
                String address = preferences.getString("proxy_ip", "");
                if (enabled && !android.text.TextUtils.isEmpty(address)) {
                    preferences.edit().putBoolean("proxy_auto_disabled_by_vpn", true).putBoolean("proxy_enabled", false).apply();
                    ConnectionsManager.setProxySettings(false, null);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                }
            } else if (autoDisabled) {
                String address = preferences.getString("proxy_ip", "");
                SharedPreferences.Editor editor = preferences.edit().putBoolean("proxy_auto_disabled_by_vpn", false);
                if (!android.text.TextUtils.isEmpty(address)) {
                    editor.putBoolean("proxy_enabled", true);
                }
                editor.apply();
                if (!android.text.TextUtils.isEmpty(address)) {
                    ConnectionsManager.setProxySettings(true, org.telegram.utils.proxy.ProxySettings.fromSharedPreferences(preferences));

                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // exteraGram
    private static boolean hasTransport(int transport) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                ensureCurrentNetworkGet(false);
                if (currentNetworkInfo == null) {
                    return false;
                }
                int type = currentNetworkInfo.getType();
                if (transport == NetworkCapabilities.TRANSPORT_CELLULAR) {
                    return type == ConnectivityManager.TYPE_MOBILE;
                } else if (transport == NetworkCapabilities.TRANSPORT_WIFI) {
                    return type == ConnectivityManager.TYPE_WIFI;
                }
                return false;
            }
            if (connectivityManager == null) {
                connectivityManager = (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            }
            if (connectivityManager != null) {
                Network network = connectivityManager.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                    return capabilities != null && capabilities.hasTransport(transport);
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    // exteraGram
    private static boolean isVpnEnabled() {
        try {
            if (connectivityManager == null) {
                connectivityManager = (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            }
            if (connectivityManager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = connectivityManager.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        return true;
                    }
                }
            }
            ensureCurrentNetworkGet(false);
            return currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_VPN;
        } catch (Throwable ignore) {
        }
        return false;
    }

    public static boolean isRoaming() {
        try {
            ensureCurrentNetworkGet(false);
            return currentNetworkInfo != null && currentNetworkInfo.isRoaming();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedOrConnectingToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET)) {
                NetworkInfo.State state = currentNetworkInfo.getState();
                if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) && currentNetworkInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectionSlow() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (currentNetworkInfo.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        return true;
                }
            }
        } catch (Throwable ignore) {

        }
        return false;
    }

    public static int getAutodownloadNetworkType() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return StatsController.TYPE_MOBILE;
            }
            if (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (lastKnownNetworkType == StatsController.TYPE_MOBILE || lastKnownNetworkType == StatsController.TYPE_WIFI) && System.currentTimeMillis() - lastNetworkCheckTypeTime < 5000) {
                    return lastKnownNetworkType;
                }
                if (connectivityManager.isActiveNetworkMetered()) {
                    lastKnownNetworkType = StatsController.TYPE_MOBILE;
                } else {
                    lastKnownNetworkType = StatsController.TYPE_WIFI;
                }
                lastNetworkCheckTypeTime = System.currentTimeMillis();
                return lastKnownNetworkType;
            }
            if (currentNetworkInfo.isRoaming()) {
                return StatsController.TYPE_ROAMING;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return StatsController.TYPE_MOBILE;
    }

    public static int getCurrentNetworkType() {
        if (isConnectedOrConnectingToWiFi()) {
            return StatsController.TYPE_WIFI;
        } else if (isRoaming()) {
            return StatsController.TYPE_ROAMING;
        } else {
            return StatsController.TYPE_MOBILE;
        }
    }

    public static boolean isNetworkOnlineFast() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return true;
            }
            if (currentNetworkInfo.isConnectedOrConnecting() || currentNetworkInfo.isAvailable()) {
                return true;
            }

            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnlineRealtime() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null && (netInfo.isConnectedOrConnecting() || netInfo.isAvailable())) {
                return true;
            }

            netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnline() {
        boolean result = isNetworkOnlineRealtime();
        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            boolean result2 = isNetworkOnlineFast();
            if (result != result2) {
                FileLog.d("network online mismatch");
            }
        }
        return result;
    }

    public static void startAppCenter(Activity context) {
        applicationLoaderInstance.startAppCenterInternal(context);
    }

    public static void checkForUpdates() {
        applicationLoaderInstance.checkForUpdatesInternal();
    }

    public static void appCenterLog(Throwable e) {
        applicationLoaderInstance.appCenterLogInternal(e);
    }

    protected void appCenterLogInternal(Throwable e) {

    }

    protected void checkForUpdatesInternal() {

    }

    protected void startAppCenterInternal(Activity context) {

    }

    public static void logDualCamera(boolean success, boolean vendor) {
        applicationLoaderInstance.logDualCameraInternal(success, vendor);
    }

    protected void logDualCameraInternal(boolean success, boolean vendor) {

    }

    public boolean checkApkInstallPermissions(final Context context) {
        return false;
    }

    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        return false;
    }

    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        return false;
    }

    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        return false;
    }

    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return null;
    }

    public TLRPC.Update parseTLUpdate(int constructor) {
        return null;
    }

    public void processUpdate(int currentAccount, TLRPC.Update update) {

    }

    public boolean onSuggestionFill(String suggestion, CharSequence[] output, boolean[] closeable) {
        return false;
    }

    public boolean onSuggestionClick(String suggestion) {
        return false;
    }

    public void addItemOptions(ItemOptions itemOptions) {

    }

    public boolean checkRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        return false;
    }

    public boolean consumePush(int account, JSONObject json) {
        return false;
    }

    public void onResume() {

    }

    public boolean onPause() {
        return false;
    }

    public BaseFragment openSettings(int n) {
        return null;
    }

    public boolean isCustomUpdate() {
        return false;
    }
    public void downloadUpdate() {}
    public void cancelDownloadingUpdate() {}
    public boolean isDownloadingUpdate() {
        return false;
    }
    public float getDownloadingUpdateProgress() {
        return 0.0f;
    }
    public void checkUpdate(boolean force, Runnable whenDone) {}
    public BetaUpdate getUpdate() {
        return null;
    }
    public File getDownloadedUpdateFile() {
        return null;
    }
}
