package com.exteragram.messenger;

/**
 * Background services started once by {@link ExteraConfig#init()}.
 * exteraGram starts ApiController, RemoteUtils, PluginsController, AdBlockManager and IconManager here;
 * each porting stage adds its service (see EXTERA_PORT.md).
 */
public final class ExteraServices {

    private ExteraServices() {
    }

    static void start() {
        // exteraGram ApiController: profiles/badges sync (StaticGram: our badges server from app config)
        try {
            com.exteragram.messenger.api.ApiController.init();
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
        }
        // exteraGram icon packs: installed packs that are neither enabled nor hidden go to the hidden list
        try {
            com.exteragram.messenger.icons.IconManager.INSTANCE.prefetchCustomPacks();
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
        }
        // exteraGram plugins: start the engine (a no-op when it is disabled or unsupported)
        try {
            com.exteragram.messenger.plugins.PluginsController controller = com.exteragram.messenger.plugins.PluginsController.getInstance();
            controller.init(ExteraConfig.getPluginsSafeMode(), () -> {
                controller.executeOnAppEvent("app_start");
                if (!org.telegram.messenger.ApplicationLoader.mainInterfacePaused) {
                    controller.executeOnAppEvent("app_resume");
                }
            });
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
        }
    }
}
