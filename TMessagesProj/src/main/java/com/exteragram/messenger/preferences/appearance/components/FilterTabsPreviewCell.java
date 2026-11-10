package com.exteragram.messenger.preferences.appearance.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.TabCounterMode;
import com.exteragram.messenger.preferences.appearance.components.FilterTabsPreviewCell;
import com.exteragram.messenger.preferences.components.CustomPreferenceCell;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.source.GlassSourceColor;

@SuppressLint({"ViewConstructor"})
public class FilterTabsPreviewCell extends FrameLayout implements CustomPreferenceCell, NotificationCenter.NotificationCenterDelegate {
    private final int COUNTER_SEED;
    private final FilterTabsView filterTabsView;
    private final Map<Integer, Integer> idsWithCounters;

    @Override // com.exteragram.messenger.preferences.components.CustomPreferenceCell
    public boolean equals(Object obj) {
        return this == obj;
    }

    @Override // android.view.ViewGroup
    public boolean onInterceptTouchEvent(MotionEvent motionEvent) {
        return true;
    }

    public FilterTabsPreviewCell(Context context) {
        super(context);
        this.idsWithCounters = new HashMap();
        this.COUNTER_SEED = Utilities.random.nextInt(40) + 20;
        setWillNotDraw(false);
        GlassSourceColor blurredBackgroundSourceColor = new GlassSourceColor();
        blurredBackgroundSourceColor.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        BlurredBackgroundDrawableViewFactory blurredBackgroundDrawableViewFactory = new BlurredBackgroundDrawableViewFactory(blurredBackgroundSourceColor);
        FilterTabsView filterTabsView = new FilterTabsView(context, null);
        this.filterTabsView = filterTabsView;
        // stage 2: filterTabsView.setStaticAllChats(true);
        filterTabsView.setPadding(0, AndroidUtilities.dp(7.0f), 0, AndroidUtilities.dp(7.0f));
        BlurredBackgroundDrawable blurredBackgroundDrawableCreate = blurredBackgroundDrawableViewFactory.create(filterTabsView, BlurredBackgroundProviderImpl.topPanel(null));
        blurredBackgroundDrawableCreate.setRadius(AndroidUtilities.dp(18.0f));
        blurredBackgroundDrawableCreate.setPadding(AndroidUtilities.dp(6.666f));
        filterTabsView.setBlurredBackground(blurredBackgroundDrawableCreate);
        filterTabsView.setColors(Theme.key_actionBarTabLine, Theme.key_actionBarTabActiveText, Theme.key_actionBarTabUnactiveText, Theme.key_actionBarTabSelector, Theme.key_actionBarDefault);
        filterTabsView.setDelegate(new AnonymousClass1());
        addView(filterTabsView, LayoutHelper.createFrame(-1, 50.0f, 17, 12.0f, 0.0f, 12.0f, 0.0f));
        updateTabs(false);
    }

    /* renamed from: com.exteragram.messenger.preferences.appearance.components.FilterTabsPreviewCell$1, reason: invalid class name */
    public class AnonymousClass1 implements FilterTabsView.FilterTabsViewDelegate {
        @Override // org.telegram.ui.Components.FilterTabsView.FilterTabsViewDelegate
        public boolean canPerformActions() {
            return false;
        }

        @Override // org.telegram.ui.Components.FilterTabsView.FilterTabsViewDelegate
        public boolean didSelectTab(FilterTabsView.TabView tabView, boolean z) {
            return false;
        }

        @Override // org.telegram.ui.Components.FilterTabsView.FilterTabsViewDelegate
        public boolean isTabMenuVisible() {
            return false;
        }

        @Override // org.telegram.ui.Components.FilterTabsView.FilterTabsViewDelegate
        public void onDeletePressed(int i) {
        }

        @Override // org.telegram.ui.Components.FilterTabsView.FilterTabsViewDelegate
        public void onPageReorder(int i, int i2) {
        }

        @Override // org.telegram.ui.Components.FilterTabsView.FilterTabsViewDelegate
        public void onPageScrolled(float f) {
        }

        @Override // org.telegram.ui.Components.FilterTabsView.FilterTabsViewDelegate
        public void onPageSelected(FilterTabsView.Tab tab, boolean z) {
        }

        @Override // org.telegram.ui.Components.FilterTabsView.FilterTabsViewDelegate
        public void onSamePageSelected() {
        }

        public AnonymousClass1() {
        }

        @Override // org.telegram.ui.Components.FilterTabsView.FilterTabsViewDelegate
        public int getTabCounter(int i) {
            if (ExteraConfig.getTabCounterMode() == TabCounterMode.HIDDEN) {
                return 0;
            }
            return ((Integer) FilterTabsPreviewCell.this.idsWithCounters.computeIfAbsent(Integer.valueOf(i), key -> 0)).intValue();
        }

        /* renamed from: $r8$lambda$LUTilU3rkOEUjmtXHG-1chqCQuQ, reason: not valid java name */
        
    }

    private void updateTabs(boolean z) {
        this.filterTabsView.resetTabId();
        this.filterTabsView.removeTabs();
        this.idsWithCounters.clear();
        ArrayList<MessagesController.DialogFilter> dialogFilters = MessagesController.getInstance(UserConfig.selectedAccount).getDialogFilters();
        int i = 0;
        int i2 = -1;
        for (int i3 = 0; i3 < dialogFilters.size(); i3++) {
            MessagesController.DialogFilter dialogFilter = dialogFilters.get(i3);
            if (!dialogFilter.isDefault() || !ExteraConfig.getHideAllChats()) {
                if (i3 == 0 || i3 == 1 || i3 == 3) {
                    this.idsWithCounters.put(Integer.valueOf(dialogFilter.id), Integer.valueOf(this.COUNTER_SEED / (i3 + 1)));
                }
                if (i2 == -1) {
                    i2 = dialogFilter.id;
                }
                boolean zIsDefault = dialogFilter.isDefault();
                FilterTabsView filterTabsView = this.filterTabsView;
                if (zIsDefault) {
                    filterTabsView.addTab(dialogFilter.id, 0, LocaleController.getString(R.string.FilterAllChats), null, false, true, dialogFilter.locked); // stage 2: emoticon "💬"
                } else {
                    int i4 = dialogFilter.id;
                    int i5 = dialogFilter.localId;
                    String str = dialogFilter.name;
                    filterTabsView.addTab(i4, i5, str, dialogFilter.entities, dialogFilter.title_noanimate, false, dialogFilter.locked); // stage 2: emoticon
                }
                i++;
            }
        }
        if (i < 6) {
            int[] iArr = {100, 101, 102, 103};
            String[] strArr = {LocaleController.getString(R.string.FilterContacts), LocaleController.getString(R.string.FilterGroups), LocaleController.getString(R.string.FilterChannels), LocaleController.getString(R.string.FilterBots)};
            String[] strArr2 = {"👤", "👥", "📢", "🤖"};
            int i6 = 0;
            for (int i7 = 6; i6 < 4 && i < i7; i7 = 6) {
                int size = dialogFilters.size() + i6;
                if (size == 0 || size == 1 || size == 3) {
                    this.idsWithCounters.put(Integer.valueOf(iArr[i6]), Integer.valueOf(this.COUNTER_SEED / (size + 1)));
                }
                if (i2 == -1) {
                    i2 = iArr[i6];
                }
                FilterTabsView filterTabsView2 = this.filterTabsView;
                int i8 = iArr[i6];
                filterTabsView2.addTab(i8, i8, strArr[i6], null, false, false, false); // stage 2: emoticon strArr2[i6]
                i++;
                i6++;
            }
        }
        this.filterTabsView.finishAddingTabs(z);
        if (i2 == -1 && !dialogFilters.isEmpty()) {
            i2 = dialogFilters.get(0).id;
        }
        FilterTabsView filterTabsView3 = this.filterTabsView;
        if (i2 != -1) {
            filterTabsView3.selectTabWithId(i2, 1.0f);
        } else {
            filterTabsView3.selectFirstTab();
        }
    }

    @Override // android.view.ViewGroup, android.view.View
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(this, NotificationCenter.dialogFiltersUpdated);
    }

    @Override // android.view.ViewGroup, android.view.View
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(UserConfig.selectedAccount).removeObserver(this, NotificationCenter.dialogFiltersUpdated);
    }

    @Override // org.telegram.messenger.NotificationCenter.NotificationCenterDelegate
    public void didReceivedNotification(int i, int i2, Object... objArr) {
        if (i == NotificationCenter.dialogFiltersUpdated) {
            updateTabs(true);
        }
    }

    @Override // android.widget.FrameLayout, android.view.View
    public void onMeasure(int i, int i2) {
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(i), 1073741824), View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(74.0f), 1073741824));
    }

    @Override // android.view.View
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(0.0f, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
    }
}
