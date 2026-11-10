"""Sample: settings, a bulletin and a drawer menu item."""

from base_plugin import BasePlugin, MenuItemData, MenuItemType
from ui.bulletin import BulletinHelper
from ui.settings import Divider, Header, Input, Selector, Switch, Text

__id__ = "sg_hello"
__name__ = "Hello StaticGram"
__description__ = "Sample plugin: **settings**, a bulletin and a drawer menu item."
__author__ = "StaticGram"
__version__ = "1.0.0"
__icon__ = "exteraPlugins/1"
__sdk_version__ = ">=1.4.4.3"


class HelloPlugin(BasePlugin):
    def on_plugin_load(self):
        self.logger.info("sg_hello loaded, greeting=%s", self.get_setting("greeting", "Hello!"))
        self.add_menu_item(MenuItemData(
            menu_type=MenuItemType.DRAWER_MENU,
            text="Say hello",
            icon="msg_info",
            item_id="sg_hello_drawer",
            on_click=self._on_menu,
        ))
        if self.get_setting("greet_on_load", True):
            BulletinHelper.show_info(self.greeting())

    def on_plugin_unload(self):
        self.logger.info("sg_hello unloaded")

    def greeting(self):
        styles = ["{}", "{}!!!", "~ {} ~"]
        style = styles[int(self.get_setting("style", 0)) % len(styles)]
        return style.format(self.get_setting("greeting", "Hello!"))

    def _on_menu(self, context):
        BulletinHelper.show_success(self.greeting(), context.get("fragment"))

    def create_settings(self):
        return [
            Header(text="Greeting"),
            Switch(key="greet_on_load", text="Greet when enabled", default=True,
                   subtext="Shows a bulletin when the plugin starts"),
            Input(key="greeting", text="Greeting text", default="Hello!"),
            Selector(key="style", text="Style", default=0, items=["Plain", "Loud", "Fancy"]),
            Divider(text="Tap the row below to preview"),
            Text(text="Preview", accent=True, on_click=lambda view: BulletinHelper.show_info(self.greeting())),
        ]
