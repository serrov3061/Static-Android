package com.exteragram.messenger.ai;

import android.content.Context;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.data.Suggestions;
import com.exteragram.messenger.ai.network.backend.OnDeviceAvailability;
import com.exteragram.messenger.utils.chats.ChatUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Port of exteraGram's AiController.
 *
 * AI is available only when the user has configured a service with their own API key
 * ({@link #canUseAI()}); otherwise every hook keeps the stock Telegram behaviour.
 */
public class AiController {

    /**
     * exteraGram shows a "Tap to add an API key" hint in the send-button menu when AI is not configured.
     * Kept off for StaticGram, so that without a configured service the menu is exactly like in Telegram.
     */
    public static final boolean SHOW_SETUP_HINT = false;

    private final List<Role> roles = new ArrayList<>();
    private final List<Service> services = new ArrayList<>();

    private static class SingletonHolder {
        private static final AiController INSTANCE = new AiController();
    }

    public static AiController getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public AiController() {
        loadRoles();
        loadServices();
    }

    public static boolean isOnDeviceError(int code) {
        return code == -1 || code == -2 || code == -3 || code == -4 || code == -5;
    }

    public static void clearHistory(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider, boolean confirm) {
        clearHistory(fragment, resourcesProvider, confirm, null);
    }

    public static void clearHistory(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider, boolean confirm, Runnable onCleared) {
        if (fragment == null) {
            return;
        }
        if (confirm) {
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
            builder.setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.ClearConversationHistoryInfo)));
            builder.setTitle(LocaleController.getString(R.string.ClearHistory));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.setPositiveButton(LocaleController.getString(R.string.ClearButton), (dialog, which) -> doClearHistory(fragment, onCleared));
            AlertDialog dialog = builder.create();
            fragment.showDialog(dialog);
            TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (button != null) {
                button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            }
            return;
        }
        doClearHistory(fragment, onCleared);
    }

    private static void doClearHistory(BaseFragment fragment, Runnable onCleared) {
        AiConfig.clearConversationHistory();
        if (onCleared != null) {
            onCleared.run();
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.HistoryCleared)).show();
    }

    public static void showErrorBulletin(ViewGroup container, Theme.ResourcesProvider resourcesProvider, int code, String message, Runnable changeModel) {
        showErrorBulletin(null, container, resourcesProvider, code, message, changeModel);
    }

    public static void showErrorBulletin(BaseFragment fragment, int code, String message) {
        showErrorBulletin(fragment, null, null, code, message, null);
    }

    private static void showErrorBulletin(BaseFragment fragment, ViewGroup container, Theme.ResourcesProvider resourcesProvider, int code, String message, Runnable changeModel) {
        int title;
        int info;
        boolean unknown = false;
        switch (code) {
            case -5:
                title = R.string.AIErrorOnDeviceStopped;
                info = R.string.AIErrorOnDeviceStoppedInfo;
                break;
            case -4:
                title = R.string.AIErrorOnDeviceTooLong;
                info = R.string.AIErrorOnDeviceTooLongInfo;
                break;
            case -3:
                title = R.string.AIErrorOnDeviceDownloading;
                info = R.string.AIErrorOnDeviceDownloadingInfo;
                break;
            case -2:
                title = R.string.AIErrorOnDeviceDownload;
                info = R.string.AIErrorOnDeviceDownloadInfo;
                break;
            case -1:
                title = R.string.AIErrorOnDevice;
                info = R.string.AIErrorOnDeviceInfo;
                break;
            case 204:
                title = R.string.AIErrorEmpty;
                info = R.string.AIErrorEmptyInfo;
                break;
            case 400:
                title = R.string.AIError400;
                info = R.string.AIError400Info;
                break;
            case 401:
                title = R.string.AIError401;
                info = R.string.AIError401Info;
                break;
            case 402:
                title = R.string.AIError402;
                info = R.string.AIError402Info;
                break;
            case 403:
                title = R.string.AIError403;
                info = R.string.AIError403Info;
                break;
            case 408:
                title = R.string.AIError408;
                info = R.string.AIError408Info;
                break;
            case 429:
                title = R.string.AIError429;
                info = R.string.AIError429Info;
                break;
            case 502:
                title = R.string.AIError502;
                info = R.string.AIError502Info;
                break;
            case 503:
                title = R.string.AIError503;
                info = R.string.AIError503Info;
                break;
            default:
                title = R.string.AIError;
                info = R.string.AIErrorInfo;
                unknown = true;
                break;
        }
        String detail = (unknown || code == 400 || code == 403) ? trimErrorMessage(message) : null;
        if (unknown && detail != null) {
            title = R.string.UnknownError;
        }
        final int titleRes = title;
        final CharSequence subtitle = detail != null ? detail : LocaleController.getString(info);
        final BulletinFactory factory;
        if (container instanceof FrameLayout) {
            factory = BulletinFactory.of((FrameLayout) container, resourcesProvider);
        } else if (fragment != null) {
            factory = BulletinFactory.of(fragment);
        } else {
            factory = BulletinFactory.global();
        }
        final Runnable action = changeModel != null && isOnDeviceError(code) ? changeModel : null;
        AndroidUtilities.runOnUIThread(() -> {
            if (factory == null) {
                return;
            }
            if (action != null) {
                factory.createSimpleBulletin(R.raw.error, LocaleController.getString(titleRes), subtitle, LocaleController.getString(R.string.AIChangeModel), action).show();
            } else {
                factory.createSimpleBulletin(R.raw.error, LocaleController.getString(titleRes), subtitle).show();
            }
        });
    }

    private static String trimErrorMessage(String message) {
        if (TextUtils.isEmpty(message)) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= 160) {
            return trimmed;
        }
        return trimmed.substring(0, 160).trim() + "…";
    }

    /** True only when the selected service has an API key (or a ready on-device model, never on StaticGram). */
    public static boolean canUseAI() {
        Service selected = getInstance().getSelected();
        if (selected.isOnDevice()) {
            return OnDeviceAvailability.isReady();
        }
        return !TextUtils.isEmpty(selected.getKey()) && !TextUtils.isEmpty(selected.getUrl());
    }

    public void loadRoles() {
        roles.clear();
        for (Role role : AiConfig.getRoles()) {
            if (role != null && role.getName() != null && role.getPrompt() != null) {
                roles.add(role);
            }
        }
    }

    public List<Role> getRoles() {
        return Collections.unmodifiableList(roles);
    }

    public List<Role> getSuggestedRoles() {
        ArrayList<Role> list = new ArrayList<>();
        for (Suggestions suggestion : Suggestions.values()) {
            list.add(suggestion.getRole());
        }
        return list;
    }

    public boolean isCustomRole(Role role) {
        return role != null && roles.contains(role);
    }

    public boolean isSuggestedRole(Role role) {
        return role != null && getSuggestedRoles().contains(role);
    }

    public Role getSelectedRole() {
        for (Role role : roles) {
            if (role.isSelected()) {
                return role;
            }
        }
        for (Role role : getSuggestedRoles()) {
            if (role.isSelected()) {
                return role;
            }
        }
        return Suggestions.ASSISTANT.getRole();
    }

    public boolean addRole(Role role) {
        if (isSuggestedRole(role) || isCustomRole(role)) {
            return false;
        }
        roles.add(0, role);
        saveRoles();
        return true;
    }

    public boolean removeRole(Role role) {
        if (role == null) {
            return false;
        }
        boolean removed = roles.remove(role);
        if (removed) {
            saveRoles();
        }
        return removed;
    }

    public boolean updateRole(Role oldRole, Role newRole) {
        int index = roles.indexOf(oldRole);
        if (index == -1) {
            return false;
        }
        if (isSuggestedRole(newRole) && !oldRole.equals(newRole)) {
            return false;
        }
        roles.set(index, newRole);
        saveRoles();
        return true;
    }

    public void saveRoles() {
        AiConfig.saveRoles(new ArrayList<>(roles));
    }

    public void loadServices() {
        services.clear();
        for (Service service : AiConfig.getServices()) {
            if (!service.isOnDevice()) {
                services.add(service);
            }
        }
        if (OnDeviceAvailability.isSupported()) {
            String modelName = OnDeviceAvailability.getModelName();
            Service onDevice = AiConfig.ON_DEVICE_SERVICE;
            onDevice.setModel(TextUtils.isEmpty(modelName) ? "Gemini Nano" : modelName);
            onDevice.setReasoningEnabled(OnDeviceAvailability.isThinkingSupported() && AiConfig.getOnDeviceReasoning());
            services.add(onDevice);
        }
        if (!OnDeviceAvailability.isReady() && AiConfig.ON_DEVICE_SERVICE.getId().equals(AiConfig.getSelectedServiceId())) {
            AiConfig.setSelectedServiceId(null);
        }
    }

    public List<Service> getAll() {
        return Collections.unmodifiableList(services);
    }

    public boolean isServicesEmpty() {
        return services.isEmpty();
    }

    public void addService(Service service) {
        if (service == null || service.isOnDevice() || services.contains(service)) {
            return;
        }
        services.add(service);
        saveServices();
    }

    public void updateService(Service oldService, Service newService) {
        if (oldService == null || oldService.isOnDevice() || newService == null || newService.isOnDevice()) {
            return;
        }
        int index = services.indexOf(oldService);
        if (index == -1) {
            return;
        }
        newService.setId(oldService.getId());
        services.set(index, newService);
        saveServices();
    }

    public boolean removeService(Service service) {
        if (service == null || service.isOnDevice()) {
            return false;
        }
        boolean removed = services.remove(service);
        if (removed) {
            saveServices();
        }
        return removed;
    }

    public Service getSelected() {
        for (Service service : services) {
            if (service.isSelected()) {
                return service;
            }
        }
        for (Service service : services) {
            if (!service.isOnDevice()) {
                return service;
            }
        }
        return AiConfig.DEFAULT_SERVICE;
    }

    public void saveServices() {
        Collections.sort(services, Comparator.comparing(Service::getModel, Comparator.nullsLast(Comparator.naturalOrder())));
        ArrayList<Service> list = new ArrayList<>();
        for (Service service : services) {
            if (!service.isOnDevice()) {
                list.add(service);
            }
        }
        AiConfig.saveServices(list);
    }

    public static boolean canSendImage(MessageObject messageObject) {
        return messageObject != null && canSendImage(ChatUtils.getInstance().getPathToMessage(messageObject));
    }

    public static boolean canSendImage(String path) {
        if (path == null) {
            return false;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".heif");
    }
}
