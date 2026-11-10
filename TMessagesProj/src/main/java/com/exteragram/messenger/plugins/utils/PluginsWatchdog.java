package com.exteragram.messenger.plugins.utils;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.pip.PipController;
import java.io.File;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.Ref;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

public final class PluginsWatchdog {

    public static final Companion Companion = new Companion();
    private final PluginsController controller;
    private final ConcurrentHashMap<Thread, ExecutionInfo> executingPlugins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Thread, ExecutionInfo> frozenExecutions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Thread, ScheduledFuture<?>> scheduledChecks = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public static final void showNotRespondingAlert(Plugin plugin) {
        Companion.showNotRespondingAlert(plugin);
    }

    public static final class ExecutionInfo {
        private final String pluginId;

        public final String component1() {
            return this.pluginId;
        }

        public final ExecutionInfo copy(String pluginId) {
            return new ExecutionInfo(pluginId);
        }

        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return (other instanceof ExecutionInfo) && Intrinsics.areEqual(this.pluginId, ((ExecutionInfo) other).pluginId);
        }

        public int hashCode() {
            return this.pluginId.hashCode();
        }

        public String toString() {
            return "ExecutionInfo(pluginId=" + this.pluginId + ")";
        }

        public ExecutionInfo(String str) {
            this.pluginId = str;
        }

        public final String getPluginId() {
            return this.pluginId;
        }
    }

    public PluginsWatchdog(PluginsController pluginsController) {
        this.controller = pluginsController;
    }

    public final void start() {
        ScheduledExecutorService scheduledExecutorService = this.scheduler;
        if (scheduledExecutorService == null || scheduledExecutorService.isShutdown()) {
            ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1);
            scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
            this.scheduler = scheduledThreadPoolExecutor;
        }
    }

    public final void stop() {
        Iterator<ScheduledFuture<?>> it = this.scheduledChecks.values().iterator();
        while (it.hasNext()) {
            it.next().cancel(false);
        }
        this.scheduledChecks.clear();
        ScheduledExecutorService scheduledExecutorService = this.scheduler;
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();
        }
        this.scheduler = null;
        Iterator<ExecutionInfo> it2 = this.frozenExecutions.values().iterator();
        while (it2.hasNext()) {
            Plugin plugin = this.controller.getPlugins().get(it2.next().getPluginId());
            if (plugin != null) {
                plugin.setNotResponding(false);
            }
        }
        this.frozenExecutions.clear();
        this.executingPlugins.clear();
    }

    public final void onPluginExecutionStarted(final String pluginId) {
        if (pluginId == null) {
            return;
        }
        final Thread threadCurrentThread = Thread.currentThread();
        final ExecutionInfo executionInfo = new ExecutionInfo(pluginId);
        this.executingPlugins.put(threadCurrentThread, executionInfo);
        ExecutionInfo executionInfoRemove = this.frozenExecutions.remove(threadCurrentThread);
        if (executionInfoRemove != null) {
            if (Intrinsics.areEqual(executionInfoRemove.getPluginId(), pluginId)) {
                this.frozenExecutions.put(threadCurrentThread, executionInfo);
            } else {
                clearNotRespondingIfUnfrozen(executionInfoRemove.getPluginId());
            }
        }
        ScheduledFuture<?> scheduledFutureRemove = this.scheduledChecks.remove(threadCurrentThread);
        if (scheduledFutureRemove != null) {
            scheduledFutureRemove.cancel(false);
        }
        ScheduledExecutorService scheduledExecutorService = this.scheduler;
        if (scheduledExecutorService == null) {
            return;
        }
        try {
            this.scheduledChecks.put(threadCurrentThread, scheduledExecutorService.schedule(new Runnable() {
                @Override
                public final void run() {
                    PluginsWatchdog.$r8$lambda$UoMELmYGmxSMvupkn4u6yrELpNM(PluginsWatchdog.this, threadCurrentThread, executionInfo, pluginId);
                }
            }, 5L, TimeUnit.SECONDS));
        } catch (RejectedExecutionException unused) {
            this.executingPlugins.remove(threadCurrentThread, executionInfo);
        }
    }

    public static void $r8$lambda$UoMELmYGmxSMvupkn4u6yrELpNM(final PluginsWatchdog pluginsWatchdog, final Thread thread, final ExecutionInfo executionInfo, final String str) {
        try {
            final Ref.BooleanRef booleanRef = new Ref.BooleanRef();
            ConcurrentHashMap<Thread, ExecutionInfo> concurrentHashMap = pluginsWatchdog.executingPlugins;
            concurrentHashMap.computeIfPresent(thread, (t, current) -> markFrozen(executionInfo, pluginsWatchdog, thread, str, booleanRef, current));
            if (booleanRef.element) {
                NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginIsNotResponding);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static ExecutionInfo markFrozen(ExecutionInfo executionInfo, PluginsWatchdog pluginsWatchdog, Thread thread, String str, Ref.BooleanRef booleanRef, ExecutionInfo executionInfo2) {
        if (executionInfo2 == executionInfo) {
            pluginsWatchdog.frozenExecutions.put(thread, executionInfo);
            Plugin plugin = pluginsWatchdog.controller.getPlugins().get(str);
            if (plugin != null && !plugin.isNotResponding()) {
                plugin.setNotResponding(true);
                booleanRef.element = true;
            }
        }
        return executionInfo2;
    }

    public final void onPluginExecutionFinished(String pluginId) {
        Thread threadCurrentThread = Thread.currentThread();
        ExecutionInfo executionInfo = this.executingPlugins.get(threadCurrentThread);
        if (executionInfo != null && Intrinsics.areEqual(executionInfo.getPluginId(), pluginId) && this.executingPlugins.remove(threadCurrentThread, executionInfo)) {
            ScheduledFuture<?> scheduledFutureRemove = this.scheduledChecks.remove(threadCurrentThread);
            if (scheduledFutureRemove != null) {
                scheduledFutureRemove.cancel(false);
            }
            ExecutionInfo executionInfoRemove = this.frozenExecutions.remove(threadCurrentThread);
            if (executionInfoRemove != null) {
                clearNotRespondingIfUnfrozen(executionInfoRemove.getPluginId());
            }
        }
    }

    // clears the "not responding" mark of a plugin once none of its executions is frozen anymore
    private void clearNotRespondingIfUnfrozen(String pluginId) {
        for (ExecutionInfo info : this.frozenExecutions.values()) {
            if (Intrinsics.areEqual(info.getPluginId(), pluginId)) {
                return;
            }
        }
        Plugin plugin = this.controller.getPlugins().get(pluginId);
        if (plugin != null) {
            plugin.setNotResponding(false);
        }
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginIsNotResponding);
    }

    public final void forceDisablePlugin(String pluginId, Activity activity) {
        disablePluginPref(pluginId);
        restartApp(activity);
    }

    public final void forceDeletePlugin(String pluginId, Activity activity) {
        disablePluginPref(pluginId);
        PluginsController.Companion.setPluginPinned(pluginId, false);
        this.controller.cleanupPlugin(pluginId);
        try {
            PipController.INSTANCE.uninstallDependencies(pluginId);
        } catch (Exception e) {
            FileLog.e("Failed to uninstall dependencies for frozen plugin " + pluginId, e);
        }
        this.controller.clearPluginSettingsPreferences(pluginId, true);
        new File(this.controller.getPluginsDir(), pluginId + ".py").delete();
        this.controller.getPlugins().remove(pluginId);
        this.controller.notifyPluginsChanged();
        restartApp(activity);
    }

    private final void disablePluginPref(String pluginId) {
        SharedPreferences.Editor editorEdit = this.controller.getPreferences().edit();
        editorEdit.putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false);
        editorEdit.apply();
    }

    private final void restartApp(final Activity activity) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public final void run() {
                PluginsWatchdog.$r8$lambda$fgbn8TOwtFd9GpkUps4U7wX0DBU(activity);
            }
        }, 200L);
    }

    public static void $r8$lambda$fgbn8TOwtFd9GpkUps4U7wX0DBU(Activity activity) {
        if (activity != null) {
            Intent launchIntentForPackage = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
            activity.finishAffinity();
            if (launchIntentForPackage != null) {
                activity.startActivity(launchIntentForPackage);
            }
        }
        System.exit(0);
    }

    public static final class Companion {
        private Companion() {
        }

        public final void showNotRespondingAlert(final Plugin plugin) {
            BaseFragment safeLastFragment = LaunchActivity.getSafeLastFragment();
            if (safeLastFragment == null) {
                return;
            }
            final Activity parentActivity = safeLastFragment.getParentActivity();
            AlertDialog alertDialogCreate = new AlertDialog.Builder(parentActivity, safeLastFragment.getResourceProvider()).setTitle(LocaleController.formatString(R.string.PluginIsNotRespondingAlert, plugin.getName())).setItems(new String[]{LocaleController.getString(R.string.WaitMore), LocaleController.getString(R.string.Disable), LocaleController.getString(R.string.Delete)}, new int[]{R.drawable.msg_recent, R.drawable.msg_block, R.drawable.msg_delete}, new DialogInterface.OnClickListener() {
                @Override
                public final void onClick(DialogInterface dialogInterface, int i) {
                    PluginsWatchdog.Companion.$r8$lambda$YVUu_yybweI20Wq2RsPMfENiUXc(plugin, parentActivity, dialogInterface, i);
                }
            }).create();
            alertDialogCreate.show();
            alertDialogCreate.setItemColor(alertDialogCreate.getItemsCount() - 1, Theme.getColor(Theme.key_text_RedBold), Theme.getColor(Theme.key_text_RedRegular));
        }

        public static void $r8$lambda$YVUu_yybweI20Wq2RsPMfENiUXc(Plugin plugin, Activity activity, DialogInterface dialogInterface, int i) {
            if (i == 1) {
                PluginsController.Companion.getInstance().getWatchdog().forceDisablePlugin(plugin.getId(), activity);
            } else {
                if (i != 2) {
                    return;
                }
                PluginsController.Companion.getInstance().getWatchdog().forceDeletePlugin(plugin.getId(), activity);
            }
        }
    }
}
