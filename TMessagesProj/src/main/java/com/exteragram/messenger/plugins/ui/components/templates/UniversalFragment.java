package com.exteragram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.view.View;
import com.exteragram.messenger.plugins.PluginsConstants;
import java.util.ArrayList;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class UniversalFragment extends org.telegram.ui.Components.UniversalFragment {
    private UniversalFragmentDelegate delegate;

    public UniversalFragment() {
        this(null);
    }


    public final UniversalFragmentDelegate getDelegate() {
        return this.delegate;
    }

    public final void setDelegate(UniversalFragmentDelegate universalFragmentDelegate) {
        this.delegate = universalFragmentDelegate;
    }

    public UniversalFragment(UniversalFragmentDelegate universalFragmentDelegate) {
        this.delegate = universalFragmentDelegate;
    }

    @Override
    public View createView(Context context) {
        UniversalFragmentDelegate universalFragmentDelegate = this.delegate;
        View viewBeforeCreateView = universalFragmentDelegate != null ? universalFragmentDelegate.beforeCreateView() : null;
        if (viewBeforeCreateView != null) {
            return viewBeforeCreateView;
        }
        View viewCreateView = super.createView(context);
        this.actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                UniversalFragment universalFragment = UniversalFragment.this;
                if (id == -1) {
                    if (universalFragment.onBackPressed(true)) {
                        UniversalFragment.this.finishFragment();
                    }
                } else {
                    UniversalFragmentDelegate delegate = universalFragment.getDelegate();
                    if (delegate != null) {
                        delegate.onMenuItemClick(id);
                    }
                }
            }
        });
        UniversalFragmentDelegate universalFragmentDelegate2 = this.delegate;
        View viewAfterCreateView = universalFragmentDelegate2 != null ? universalFragmentDelegate2.afterCreateView(viewCreateView) : null;
        return viewAfterCreateView == null ? viewCreateView : viewAfterCreateView;
    }

    public final ActionBarMenu getActionBarMenu() {
        return this.actionBar.createMenu();
    }

    @Override
    public CharSequence getTitle() {
        UniversalFragmentDelegate universalFragmentDelegate = this.delegate;
        if (universalFragmentDelegate != null) {
            return universalFragmentDelegate.getTitle();
        }
        return null;
    }

    public final void setTitle(CharSequence title, boolean animated, long duration) {
        ActionBar actionBar = this.actionBar;
        if (animated) {
            if (duration <= 0) {
                duration = 300;
            }
            actionBar.setTitleAnimated(title, false, duration);
            return;
        }
        actionBar.setTitle(title);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        UniversalFragmentDelegate universalFragmentDelegate = this.delegate;
        if (universalFragmentDelegate != null) {
            universalFragmentDelegate.fillItems(items, adapter);
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        UniversalFragmentDelegate universalFragmentDelegate = this.delegate;
        if (universalFragmentDelegate != null) {
            universalFragmentDelegate.onClick(item, view, position, x, y);
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        UniversalFragmentDelegate universalFragmentDelegate = this.delegate;
        if (universalFragmentDelegate != null) {
            return universalFragmentDelegate.onLongClick(item, view, position, x, y);
        }
        return false;
    }

    @Override
    public boolean onFragmentCreate() {
        UniversalFragmentDelegate universalFragmentDelegate = this.delegate;
        if (universalFragmentDelegate != null) {
            universalFragmentDelegate.onFragmentCreate();
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        UniversalFragmentDelegate universalFragmentDelegate = this.delegate;
        if (universalFragmentDelegate != null) {
            universalFragmentDelegate.onFragmentDestroy();
        }
        super.onFragmentDestroy();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        UniversalFragmentDelegate universalFragmentDelegate = this.delegate;
        Boolean boolOnBackPressed = universalFragmentDelegate != null ? universalFragmentDelegate.onBackPressed() : null;
        if (boolOnBackPressed != null) {
            return !boolOnBackPressed.booleanValue();
        }
        return super.onBackPressed(invoked);
    }

    public interface UniversalFragmentDelegate {
        default View afterCreateView(View view) {
            return view;
        }

        default View beforeCreateView() {
            return null;
        }

        default void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        }

        default CharSequence getTitle() {
            return null;
        }

        default Boolean onBackPressed() {
            return null;
        }

        default void onClick(UItem item, View view, int position, float x, float y) {
        }

        default void onFragmentCreate() {
        }

        default void onFragmentDestroy() {
        }

        default boolean onLongClick(UItem item, View view, int position, float x, float y) {
            return false;
        }

        default void onMenuItemClick(int id) {
        }

    }
}
