"""SettingsController: an Elyx-style view of a plugin's persistent settings."""

import plugin_settings

__all__ = ["SettingsController"]


class SettingsController:
    def __init__(self, plugin_id: str):
        self.plugin_id = str(plugin_id)

    def get_settings(self):
        return plugin_settings.get_all_settings(self.plugin_id)

    def get_setting(self, key, default=None):
        return plugin_settings.get_setting(self.plugin_id, key, default)

    get = get_setting

    def set_setting(self, key, value, reload_settings: bool = False):
        plugin_settings.set_setting(self.plugin_id, key, value)
        try:
            from _sg_java import J, plugins_controller
            J("com.exteragram.messenger.plugins.PythonPluginsEngine").invalidateSettingsCache(self.plugin_id)
            if reload_settings:
                plugins_controller().loadPluginSettings(self.plugin_id)
        except Exception:
            pass

    set = set_setting

    def clear_settings(self):
        plugin_settings.clear_settings(self.plugin_id)

    def __getitem__(self, key):
        return self.get_setting(key)

    def __setitem__(self, key, value):
        self.set_setting(key, value)

    def __call__(self, key, default=None):
        return self.get_setting(key, default)

    def __repr__(self):
        return "SettingsController(%r)" % self.plugin_id
