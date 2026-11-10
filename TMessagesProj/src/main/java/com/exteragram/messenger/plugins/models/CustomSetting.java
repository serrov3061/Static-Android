package com.exteragram.messenger.plugins.models;

import android.view.View;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import org.telegram.ui.Components.UItem;

public final class CustomSetting extends SettingItem {
    private PyObject createSubFragmentCallback;
    private Factory<?> factory;
    private PyObject factoryArgs;
    private UItem item;
    private PyObject onClickCallback;

    public final PyObject getOnClickCallback() {
        return this.onClickCallback;
    }

    public final void setOnClickCallback(PyObject pyObject) {
        this.onClickCallback = pyObject;
    }

    public final PyObject getCreateSubFragmentCallback() {
        return this.createSubFragmentCallback;
    }

    public final void setCreateSubFragmentCallback(PyObject pyObject) {
        this.createSubFragmentCallback = pyObject;
    }

    private CustomSetting(PyObject pyObject, PyObject pyObject2, PyObject pyObject3, String str) {
        super(PluginsConstants.Settings.TYPE_CUSTOM, null, pyObject3, str);
        this.onClickCallback = pyObject;
        this.createSubFragmentCallback = pyObject2;
    }

    public final UItem getItem() {
        return this.item;
    }

    public final void setItem(UItem uItem) {
        this.item = uItem;
    }

    public final Factory<?> getFactory() {
        return this.factory;
    }

    public final void setFactory(Factory<?> factory) {
        this.factory = factory;
    }

    public final PyObject getFactoryArgs() {
        return this.factoryArgs;
    }

    public final void setFactoryArgs(PyObject pyObject) {
        this.factoryArgs = pyObject;
    }

    public CustomSetting(UItem uItem, PyObject pyObject, PyObject pyObject2, PyObject pyObject3, String str) {
        this(pyObject, pyObject2, pyObject3, str);
        this.item = uItem;
        uItem.settingItem = this;
    }

    public CustomSetting(Factory<?> factory, PyObject pyObject, PyObject pyObject2, PyObject pyObject3, String str) {
        this(pyObject, pyObject2, pyObject3, str);
        this.factory = factory;
    }

    public CustomSetting(Factory<?> factory, PyObject pyObject, PyObject pyObject2, PyObject pyObject3, PyObject pyObject4, String str) {
        this(factory, pyObject2, pyObject3, pyObject4, str);
        this.factoryArgs = pyObject;
    }

    public CustomSetting(View view, PyObject pyObject, PyObject pyObject2, PyObject pyObject3, String str) {
        this(UItem.asCustom(view), pyObject, pyObject2, pyObject3, str);
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(this.onClickCallback);
        closeCallback(this.createSubFragmentCallback);
        closeCallback(this.factoryArgs);
        this.onClickCallback = null;
        this.createSubFragmentCallback = null;
        this.factoryArgs = null;
    }

    public static abstract class Factory<V extends View> extends UItem.UItemFactory<V> {
        private boolean isClickableValue = true;
        private boolean isShadowValue;

        public UItem create(Plugin plugin, CustomSetting setting, PyObject args) {
            return null;
        }

        public void onClick(Plugin plugin, UItem item, View view) {
        }

        public void onLongClick(Plugin plugin, UItem item, View view) {
        }

        public final boolean isShadowValue() {
            return this.isShadowValue;
        }

        public final void setShadowValue(boolean z) {
            this.isShadowValue = z;
        }

        public final boolean isClickableValue() {
            return this.isClickableValue;
        }

        public final void setClickableValue(boolean z) {
            this.isClickableValue = z;
        }

        @Override
        public boolean isShadow() {
            return this.isShadowValue;
        }

        @Override
        public boolean isClickable() {
            return this.isClickableValue;
        }
    }
}
