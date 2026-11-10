/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.ProxyDisableCondition;
import com.exteragram.messenger.proxy.ProxyController;
import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProxyRotationController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;

public class ProxyListActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final static boolean IS_PROXY_ROTATION_AVAILABLE = true;
    private static final int MENU_DELETE = 0;
    private static final int MENU_SHARE = 1;
    private static final int MENU_PIN = 2; // exteraGram
    private static final ProxyDisableCondition[] PROXY_DISABLE_CONDITIONS = ProxyDisableCondition.values(); // exteraGram

    private ListAdapter listAdapter;
    private RecyclerListView listView;
    @SuppressWarnings("FieldCanBeLocal")
    private LinearLayoutManager layoutManager;

    private int currentConnectionState;

    private boolean useProxySettings;

    private int rowCount;
    @Keep
    private int offHeaderRow = -1;
    private int offStartRow = -1;
    private int offEndRow = -1;
    private int offInfoRow = -1;
    private ArrayList<String[]> officialProxies = new ArrayList<>();
    private int useProxyRow;
    private int useProxyShadowRow;
    private int connectionsHeaderRow;
    private int proxyStartRow;
    private int proxyEndRow;
    @Keep
    private int proxyAddRow;
    private int proxyShadowRow;
    private int rotationRow;
    private int rotationTimeoutRow;
    private int rotationTimeoutInfoRow;
    private int deleteAllRow;
    // exteraGram
    private int proxyDisableRow = -1;
    private int proxyDisableShadowRow = -1;
    private ActionBarMenuItem pinMenuItem;
    private boolean refreshActionEnabled;
    private final View.OnClickListener refreshActionClickListener = v -> {
        if (refreshActionEnabled) {
            refreshProxyStatus();
        }
    };

    private ItemTouchHelper itemTouchHelper;
    private ActionBarMenuItem shareMenuItem;
    private ActionBarMenuItem deleteMenuItem;

    private List<SharedConfig.ProxyInfo> selectedItems = new ArrayList<>();
    private List<SharedConfig.ProxyInfo> proxyList = new ArrayList<>();
    private boolean wasCheckedAllList;

    public class TextDetailProxyCell extends FrameLayout implements Theme.Colorable {

        @Override
        public void updateColors() {
            try {
                if (valueTextView != null && valueTextView.getTag() instanceof Integer) {
                    final int newColor = Theme.getColor((Integer) valueTextView.getTag());
                    if (newColor == color) {
                        return;
                    }
                    color = newColor;
                    valueTextView.setTextColor(color);
                    if (checkDrawable != null) {
                        checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        private TextView textView;
        private TextView valueTextView;
        private ImageView checkImageView;
        private SharedConfig.ProxyInfo currentInfo;
        private Drawable checkDrawable;

        private CheckBox2 checkBox;
        private boolean isSelected;
        private boolean isSelectionEnabled;
        // exteraGram
        private ImageView reorderImageView;
        private boolean isReorderAvailable;
        private Drawable pinDrawable;

        private int color;

        public TextDetailProxyCell(Context context) {
            super(context);

            textView = new TextView(context);
            ThemeBind.textColor(textView, Theme.key_windowBackgroundWhiteBlackText);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, (LocaleController.isRTL ? 56 : 21), 10, (LocaleController.isRTL ? 21 : 56), 0));

            valueTextView = new TextView(context);
            valueTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            valueTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            valueTextView.setLines(1);
            valueTextView.setMaxLines(1);
            valueTextView.setSingleLine(true);
            valueTextView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
            valueTextView.setEllipsize(TextUtils.TruncateAt.END);
            valueTextView.setPadding(0, 0, 0, 0);
            addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, (LocaleController.isRTL ? 56 : 21), 35, (LocaleController.isRTL ? 21 : 56), 0));

            checkImageView = new ImageView(context);
            checkImageView.setImageResource(R.drawable.msg_info);
            ThemeBind.colorFilter(checkImageView, Theme.key_windowBackgroundWhiteGrayText3, PorterDuff.Mode.MULTIPLY);
            checkImageView.setScaleType(ImageView.ScaleType.CENTER);
            checkImageView.setContentDescription(getString(R.string.Edit));
            addView(checkImageView, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 8, 8, 8, 0));
            checkImageView.setOnClickListener(v -> presentFragment(new ProxySettingsActivity(currentInfo)));

            // exteraGram: drag handle for pinned proxies
            reorderImageView = new ImageView(context);
            reorderImageView.setImageResource(R.drawable.list_reorder);
            reorderImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.MULTIPLY));
            reorderImageView.setScaleType(ImageView.ScaleType.CENTER);
            reorderImageView.setContentDescription(getString(R.string.ProfileBotReorder));
            reorderImageView.setVisibility(GONE);
            addView(reorderImageView, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 8, 0, 8, 0));

            checkBox = new CheckBox2(context, 21);
            checkBox.setColor(Theme.key_checkbox, Theme.key_radioBackground, Theme.key_checkboxCheck);
            checkBox.setDrawBackgroundAsArc(14);
            checkBox.setVisibility(GONE);
            addView(checkBox, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 16, 0, 8, 0));

            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + 1, MeasureSpec.EXACTLY));
        }

        public void setProxy(SharedConfig.ProxyInfo proxyInfo) {
            // exteraGram: custom name and pin mark
            textView.setText(ProxyController.getInstance().getDisplayName(proxyInfo));
            currentInfo = proxyInfo;
            if (ProxyController.getInstance().isPinned(proxyInfo)) {
                if (pinDrawable == null) {
                    pinDrawable = getContext().getResources().getDrawable(R.drawable.msg_pin_mini).mutate();
                    textView.setCompoundDrawablePadding(AndroidUtilities.dp(4));
                }
                pinDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_pinnedIcon), PorterDuff.Mode.MULTIPLY));
                if (LocaleController.isRTL) {
                    textView.setCompoundDrawablesWithIntrinsicBounds(null, null, pinDrawable, null);
                } else {
                    textView.setCompoundDrawablesWithIntrinsicBounds(pinDrawable, null, null, null);
                }
            } else {
                textView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            }
        }

        // exteraGram
        public void setReorderAvailable(boolean available, boolean animated) {
            if (isReorderAvailable == available) {
                if (!animated) {
                    return;
                }
                if (reorderImageView.getVisibility() == (isSelectionEnabled && available ? VISIBLE : GONE)) {
                    return;
                }
            }
            isReorderAvailable = available;
            updateReorderHandle(animated);
        }

        // exteraGram
        private void updateReorderHandle(boolean animated) {
            boolean visible = isSelectionEnabled && isReorderAvailable;
            reorderImageView.animate().cancel();
            if (!animated) {
                reorderImageView.setVisibility(visible ? VISIBLE : GONE);
                reorderImageView.setAlpha(1f);
                reorderImageView.setScaleX(1f);
                reorderImageView.setScaleY(1f);
                return;
            }
            if (visible) {
                reorderImageView.setVisibility(VISIBLE);
                reorderImageView.setAlpha(0f);
                reorderImageView.setScaleX(0.5f);
                reorderImageView.setScaleY(0.5f);
                reorderImageView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
            } else if (reorderImageView.getVisibility() == VISIBLE) {
                reorderImageView.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(200).setInterpolator(CubicBezierInterpolator.DEFAULT).withEndAction(() -> {
                    if (!isSelectionEnabled || !isReorderAvailable) {
                        reorderImageView.setVisibility(GONE);
                    }
                    reorderImageView.setAlpha(1f);
                    reorderImageView.setScaleX(1f);
                    reorderImageView.setScaleY(1f);
                }).start();
            }
        }

        public void updateStatus() {
            int colorKey;
            if (SharedConfig.currentProxy == currentInfo && useProxySettings) {
                if (currentConnectionState == ConnectionsManager.ConnectionStateConnected || currentConnectionState == ConnectionsManager.ConnectionStateUpdating) {
                    colorKey = Theme.key_windowBackgroundWhiteBlueText6;
                    if (currentInfo.ping != 0) {
                        valueTextView.setText(getString(R.string.Connected) + ", " + LocaleController.formatString("Ping", R.string.Ping, currentInfo.ping));
                    } else {
                        valueTextView.setText(getString(R.string.Connected));
                    }
                    if (!currentInfo.checking && !currentInfo.available) {
                        currentInfo.availableCheckTime = 0;
                    }
                } else {
                    colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                    valueTextView.setText(getString(R.string.Connecting));
                }
            } else {
                if (currentInfo.checking) {
                    valueTextView.setText(getString(R.string.Checking));
                    colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                } else if (currentInfo.available) {
                    if (currentInfo.ping != 0) {
                        valueTextView.setText(getString(R.string.Available) + ", " + LocaleController.formatString("Ping", R.string.Ping, currentInfo.ping));
                    } else {
                        valueTextView.setText(getString(R.string.Available));
                    }
                    colorKey = Theme.key_windowBackgroundWhiteGreenText;
                } else {
                    valueTextView.setText(getString(R.string.Unavailable));
                    colorKey = Theme.key_text_RedRegular;
                }
            }
            if (currentInfo.settings != null && MessagesController.isOfficialProxyHost(currentInfo.settings.getAddress())) {
                valueTextView.setText(valueTextView.getText() + " \u00b7 " + getString(R.string.SgOfficialProxy));
            }
            color = Theme.getColor(colorKey);
            valueTextView.setTag(colorKey);
            valueTextView.setTextColor(color);
            if (checkDrawable != null) {
                checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
            }
        }

        public void setSelectionEnabled(boolean enabled, boolean animated) {
            if (isSelectionEnabled == enabled && animated) {
                return;
            }
            isSelectionEnabled = enabled;

            float fromX = 0, toX = LocaleController.isRTL ? -AndroidUtilities.dp(32) : AndroidUtilities.dp(32);
            if (!animated) {
                float x = enabled ? toX : fromX;
                textView.setTranslationX(x);
                valueTextView.setTranslationX(x);
                checkImageView.setTranslationX(x);
                checkBox.setTranslationX((LocaleController.isRTL ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32)) + x);
                checkImageView.setVisibility(enabled ? GONE : VISIBLE);
                checkImageView.setAlpha(1f);
                checkImageView.setScaleX(1f);
                checkImageView.setScaleY(1f);
                checkBox.setVisibility(enabled ? VISIBLE : GONE);
                checkBox.setAlpha(1f);
                checkBox.setScaleX(1f);
                checkBox.setScaleY(1f);
                updateReorderHandle(false); // exteraGram
            } else {
                ValueAnimator animator = ValueAnimator.ofFloat(enabled ? 0 : 1, enabled ? 1 : 0).setDuration(200);
                animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
                animator.addUpdateListener(animation -> {
                    float val = (float) animation.getAnimatedValue();
                    float x = AndroidUtilities.lerp(fromX, toX, val);
                    textView.setTranslationX(x);
                    valueTextView.setTranslationX(x);
                    checkImageView.setTranslationX(x);
                    checkBox.setTranslationX((LocaleController.isRTL ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32)) + x);

                    float scale = 0.5f + val * 0.5f;
                    checkBox.setScaleX(scale);
                    checkBox.setScaleY(scale);
                    checkBox.setAlpha(val);

                    scale = 0.5f + (1f - val) * 0.5f;
                    checkImageView.setScaleX(scale);
                    checkImageView.setScaleY(scale);
                    checkImageView.setAlpha(1f - val);
                });
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        if (enabled) {
                            checkBox.setAlpha(0f);
                            checkBox.setVisibility(VISIBLE);
                        } else {
                            checkImageView.setAlpha(0f);
                            checkImageView.setVisibility(VISIBLE);
                        }
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (enabled) {
                            checkImageView.setVisibility(GONE);
                        } else {
                            checkBox.setVisibility(GONE);
                        }
                    }
                });
                animator.start();
                updateReorderHandle(true); // exteraGram
            }
        }

        public void setItemSelected(boolean selected, boolean animated) {
            if (selected == isSelected && animated) {
                return;
            }
            isSelected = selected;
            checkBox.setChecked(selected, animated);
        }

        public void setChecked(boolean checked) {
            if (checked) {
                if (checkDrawable == null) {
                    checkDrawable = getResources().getDrawable(R.drawable.proxy_check).mutate();
                }
                if (checkDrawable != null) {
                    checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                }
                if (LocaleController.isRTL) {
                    valueTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, checkDrawable, null);
                } else {
                    valueTextView.setCompoundDrawablesWithIntrinsicBounds(checkDrawable, null, null, null);
                }
            } else {
                valueTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            }
        }

        public void setValue(CharSequence value) {
            valueTextView.setText(value);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateStatus();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();

        ProxyController.getInstance().loadProxyList(); // exteraGram
        currentConnectionState = ConnectionsManager.getInstance(currentAccount).getConnectionState();

        NotificationCenter.getGlobalInstance().addWeakObserver(this, NotificationCenter.proxyChangedByRotation);
        NotificationCenter.getGlobalInstance().addWeakObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addWeakObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.didUpdateConnectionState);

        final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        useProxySettings = preferences.getBoolean("proxy_enabled", false) && !SharedConfig.proxyList.isEmpty();

        updateRows(true);

        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyChangedByRotation);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonDrawable(new BackDrawable(false));
        simpleActionBar.setTitle(getString(R.string.ProxySettings));
        if (parentLayout != null && parentLayout.isLayersLayout()) {
            simpleActionBar.setOccupyStatusBar(false);
        }
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setSections();
        ((DefaultItemAnimator) listView.getItemAnimator()).setDelayAnimations(false);
        ((DefaultItemAnimator) listView.getItemAnimator()).setTranslationInterpolator(CubicBezierInterpolator.DEFAULT);
        listView.setSectionsDrawBackground(true);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter);
        // exteraGram: drag-and-drop reorder of pinned proxies (in selection mode)
        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                int position = getProxyPosition(viewHolder);
                if (position == -1) {
                    return 0;
                }
                return makeMovementFlags(canStartReorder(position) ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0, 0);
            }

            @Override
            public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current, @NonNull RecyclerView.ViewHolder target) {
                int from = getProxyPosition(current);
                int to = getProxyPosition(target);
                return from != -1 && to != -1 && isPinnedProxyPos(from) && isPinnedProxyPos(to);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = getProxyPosition(viewHolder);
                int to = getProxyPosition(target);
                return from != -1 && to != -1 && movePinnedProxy(from, to);
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                if (actionState != ItemTouchHelper.ACTION_STATE_IDLE && viewHolder != null) {
                    listView.cancelClickRunnables(false);
                    viewHolder.itemView.setPressed(true);
                    viewHolder.itemView.bringToFront();
                    listView.invalidate();
                }
                super.onSelectedChanged(viewHolder, actionState);
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                viewHolder.itemView.setPressed(false);
                viewHolder.itemView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                listView.invalidate();
            }
        });
        itemTouchHelper.attachToRecyclerView(listView);
        listView.setOnItemClickListener((view, position) -> {
            if (position == proxyDisableRow) { // exteraGram
                showProxyDisableDialog();
                return;
            }
            if (offStartRow != -1 && position >= offStartRow && position < offEndRow) {
                addOfficialProxy(officialProxies.get(position - offStartRow));
                return;
            }
            if (position == useProxyRow) {
                if (SharedConfig.currentProxy == null) {
                    if (!proxyList.isEmpty()) {
                        SharedConfig.currentProxy = proxyList.get(0);

                        if (!useProxySettings) {
                            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                            SharedConfig.currentProxy.settings.toSharedPreferences(editor);
                            editor.commit();
                        }
                    } else {
                        presentFragment(new ProxySettingsActivity());
                        return;
                    }
                }
                useProxySettings = !useProxySettings;
                updateRows(true);

                SharedPreferences preferences = MessagesController.getGlobalMainSettings();

                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(useProxySettings);

                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putBoolean("proxy_enabled", useProxySettings);
                editor.remove("proxy_auto_disabled_by_vpn"); // exteraGram: manual choice wins over auto-disable
                editor.commit();

                ConnectionsManager.setProxySettings(useProxySettings, SharedConfig.currentProxy.settings);
                NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().addWeakObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);

                for (int a = proxyStartRow; a < proxyEndRow; a++) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.updateStatus();
                    }
                }
            } else if (position == rotationRow) {
                SharedConfig.proxyRotationEnabled = !SharedConfig.proxyRotationEnabled;
                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(SharedConfig.proxyRotationEnabled);
                SharedConfig.saveConfig();

                updateRows(true);
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                if (!selectedItems.isEmpty()) {
                    listAdapter.toggleSelected(position);
                    return;
                }
                SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                useProxySettings = true;
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                info.settings.toSharedPreferences(editor);
                editor.putBoolean("proxy_enabled", useProxySettings);
                editor.remove("proxy_auto_disabled_by_vpn"); // exteraGram
                editor.commit();
                SharedConfig.currentProxy = info;
                for (int a = proxyStartRow; a < proxyEndRow; a++) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.setChecked(cell.currentInfo == info);
                        cell.updateStatus();
                    }
                }
                updateRows(false);
                RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(useProxyRow);
                if (holder != null) {
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    textCheckCell.setChecked(true);
                }
                ConnectionsManager.setProxySettings(useProxySettings, SharedConfig.currentProxy.settings);
            } else if (position == proxyAddRow) {
                presentFragment(new ProxySettingsActivity());
            } else if (position == deleteAllRow) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setMessage(getString(R.string.DeleteAllProxiesConfirm));
                builder.setNegativeButton(getString(R.string.Cancel), null);
                builder.setTitle(getString(R.string.DeleteProxyTitle));
                builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    ProxyController.getInstance().clearAll(); // exteraGram
                    for (SharedConfig.ProxyInfo info : proxyList) {
                        ProxyController.getInstance().deleteProxy(info); // exteraGram
                    }
                    useProxySettings = false;
                    NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().addWeakObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    updateRows(true);
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(useProxyRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                        listAdapter.clearSelected();
                    }
                });
                AlertDialog dialog = builder.create();
                showDialog(dialog);
                TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (button != null) {
                    ThemeBind.textColor(button, Theme.key_text_RedBold);
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= proxyStartRow && position < proxyEndRow) {
                // exteraGram: in selection mode a long press on a pinned proxy starts dragging
                if (!selectedItems.isEmpty() && canStartReorder(position) && itemTouchHelper != null) {
                    listAdapter.selectForReorder(position);
                    listView.cancelClickRunnables(true);
                    view.setPressed(false);
                    view.jumpDrawablesToCurrentState();
                    RecyclerView.ViewHolder holder = listView.findContainingViewHolder(view);
                    if (getProxyPosition(holder) == position) {
                        itemTouchHelper.startDrag(holder);
                    }
                    return true;
                }
                listAdapter.toggleSelected(position);
                return true;
            }
            return false;
        });

        SimpleActionBarMenu actionMode = simpleActionBar.createActionMode();

        pinMenuItem = actionMode.addItemWithWidth(MENU_PIN, R.drawable.msg_pin, AndroidUtilities.dp(54)); // exteraGram
        shareMenuItem = actionMode.addItemWithWidth(MENU_SHARE, R.drawable.msg_share, AndroidUtilities.dp(54));
        shareMenuItem.setContentDescription(getString(R.string.StickersShare));
        deleteMenuItem = actionMode.addItemWithWidth(MENU_DELETE, R.drawable.msg_delete, AndroidUtilities.dp(54));
        deleteMenuItem.setContentDescription(getString(R.string.Delete));

        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                switch (id) {
                    case -1:
                        if (selectedItems.isEmpty()) {
                            finishFragment();
                        } else {
                            listAdapter.clearSelected();
                        }
                        break;
                    case MENU_DELETE:
                        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                        builder.setMessage(getString(selectedItems.size() > 1 ? R.string.DeleteProxyMultiConfirm : R.string.DeleteProxyConfirm));
                        builder.setNegativeButton(getString(R.string.Cancel), null);
                        builder.setTitle(getString(R.string.DeleteProxyTitle));
                        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                            for (SharedConfig.ProxyInfo info : selectedItems) {
                                ProxyController.getInstance().deleteProxy(info); // exteraGram
                            }
                            if (SharedConfig.currentProxy == null) {
                                useProxySettings = false;
                            }
                            NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                            NotificationCenter.getGlobalInstance().addWeakObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                            updateRows(true);
                            if (listAdapter != null) {
                                if (SharedConfig.currentProxy == null) {
                                    listAdapter.notifyItemChanged(useProxyRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                                }
                                listAdapter.clearSelected();
                            }
                        });
                        AlertDialog dialog = builder.create();
                        showDialog(dialog);
                        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                        if (button != null) {
                            ThemeBind.textColor(button, Theme.key_text_RedBold);
                        }
                        break;
                    case MENU_SHARE:
                        StringBuilder links = new StringBuilder();
                        for (SharedConfig.ProxyInfo info : selectedItems) {
                            if (links.length() > 0) {
                                links.append("\n\n");
                            }
                            links.append(ProxyController.getInstance().buildShareLink(info)); // exteraGram: with &title=
                        }

                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, links.toString());
                        Intent chooserIntent = Intent.createChooser(shareIntent, getString(selectedItems.size() > 1 ? R.string.ShareLinks : R.string.ShareLink));
                        chooserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(chooserIntent);

                        if (listAdapter != null) {
                            listAdapter.clearSelected();
                        }
                        break;
                    case MENU_PIN: // exteraGram
                        toggleSelectedPins();
                        break;
                }
            }
        });

        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selectedItems.isEmpty()) {
            if (invoked) listAdapter.clearSelected();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    // ----- exteraGram: proxy auto-disable, pins, refresh -----

    private void showProxyDisableDialog() {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] names = new CharSequence[PROXY_DISABLE_CONDITIONS.length];
        boolean[] checked = new boolean[PROXY_DISABLE_CONDITIONS.length];
        for (int i = 0; i < PROXY_DISABLE_CONDITIONS.length; i++) {
            names[i] = getProxyDisableConditionName(PROXY_DISABLE_CONDITIONS[i]);
            checked[i] = ExteraConfig.isProxyDisabledOn(PROXY_DISABLE_CONDITIONS[i]);
        }
        PopupUtils.showMultiSelectDialog(names, checked, getString(R.string.ProxyDisableOn), getParentActivity(), result -> {
            for (int i = 0; i < PROXY_DISABLE_CONDITIONS.length; i++) {
                ExteraConfig.setProxyDisabledOn(PROXY_DISABLE_CONDITIONS[i], result[i]);
            }
            ApplicationLoader.checkProxyForNetworkState();
            if (listAdapter != null && proxyDisableRow >= 0) {
                listAdapter.notifyItemChanged(proxyDisableRow);
            }
        }, getResourceProvider());
    }

    private String getProxyDisableConditionName(ProxyDisableCondition condition) {
        switch (condition) {
            case VPN:
                return getString(R.string.ProxyDisableOnVpn);
            case MOBILE_DATA:
                return getString(R.string.ProxyDisableOnMobileData);
            default:
                return getString(R.string.ProxyDisableOnWiFi);
        }
    }

    private String getProxyDisableValue() {
        ArrayList<String> enabled = new ArrayList<>();
        for (ProxyDisableCondition condition : PROXY_DISABLE_CONDITIONS) {
            if (ExteraConfig.isProxyDisabledOn(condition)) {
                enabled.add(getProxyDisableConditionName(condition));
            }
        }
        if (enabled.isEmpty()) {
            return getString(R.string.ProxyDisableNever);
        }
        if (enabled.size() == PROXY_DISABLE_CONDITIONS.length) {
            return getString(R.string.ProxyDisableAlways);
        }
        return TextUtils.join(", ", enabled);
    }

    private boolean isProxyPosition(int position) {
        return position >= proxyStartRow && position < proxyEndRow;
    }

    private int getProxyPosition(RecyclerView.ViewHolder holder) {
        if (holder == null) {
            return -1;
        }
        int position = holder.getAdapterPosition();
        if (position == RecyclerView.NO_POSITION) {
            position = holder.getLayoutPosition();
        }
        if (position == RecyclerView.NO_POSITION && listView != null) {
            position = listView.getChildAdapterPosition(holder.itemView);
        }
        return isProxyPosition(position) ? position : -1;
    }

    private SharedConfig.ProxyInfo getProxyAtPosition(int position) {
        return isProxyPosition(position) ? proxyList.get(position - proxyStartRow) : null;
    }

    private boolean isPinnedProxyPos(int position) {
        return ProxyController.getInstance().isPinned(getProxyAtPosition(position));
    }

    private boolean canStartReorder(int position) {
        return actionBar.isActionModeShowed() && ProxyController.getInstance().getPinnedCount() > 1 && isPinnedProxyPos(position);
    }

    private boolean canShowReorderHandle(SharedConfig.ProxyInfo info) {
        return info != null && actionBar != null && actionBar.isActionModeShowed() && !selectedItems.isEmpty() && ProxyController.getInstance().getPinnedCount() > 1 && ProxyController.getInstance().isPinned(info);
    }

    private boolean movePinnedProxy(int from, int to) {
        if (from == to || !isPinnedProxyPos(from) || !isPinnedProxyPos(to)) {
            return false;
        }
        SharedConfig.ProxyInfo fromInfo = getProxyAtPosition(from);
        SharedConfig.ProxyInfo toInfo = getProxyAtPosition(to);
        if (fromInfo == null || toInfo == null || !ProxyController.getInstance().movePinnedProxy(fromInfo, toInfo)) {
            return false;
        }
        proxyList.remove(from - proxyStartRow);
        proxyList.add(to - proxyStartRow, fromInfo);
        listAdapter.notifyItemMoved(from, to);
        listAdapter.notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1, ListAdapter.PAYLOAD_SELECTION_CHANGED);
        return true;
    }

    private void updatePinAction() {
        if (pinMenuItem == null) {
            return;
        }
        int action = ProxyController.getInstance().getSelectedPinAction(selectedItems);
        if (action == 0) {
            pinMenuItem.setVisibility(View.GONE);
        } else {
            pinMenuItem.setVisibility(View.VISIBLE);
            pinMenuItem.setIcon(action == 2 ? R.drawable.msg_unpin : R.drawable.msg_pin);
            pinMenuItem.setContentDescription(getString(action == 2 ? R.string.UnpinMessage : R.string.PinMessage));
        }
    }

    private void toggleSelectedPins() {
        ProxyController.PinOperationResult result = ProxyController.getInstance().applySelectedPinAction(selectedItems);
        if (result == ProxyController.PinOperationResult.LIMIT_REACHED) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.formatString(R.string.ProxyPinLimitReached, ProxyController.getInstance().getMaxPinnedProxies())).show();
        } else if (result == ProxyController.PinOperationResult.CHANGED) {
            updateRows(true);
            if (listAdapter != null) {
                listAdapter.clearSelected();
            }
        }
    }

    private boolean hasCheckingProxies() {
        for (SharedConfig.ProxyInfo info : proxyList) {
            if (info.checking) {
                return true;
            }
        }
        return false;
    }

    private boolean isRefreshActionEnabled() {
        return proxyStartRow != -1 && !hasCheckingProxies();
    }

    private void updateRefreshActionState(boolean force) {
        boolean enabled = isRefreshActionEnabled();
        if (force || refreshActionEnabled != enabled) {
            refreshActionEnabled = enabled;
            if (listAdapter != null && connectionsHeaderRow >= 0) {
                listAdapter.notifyItemChanged(connectionsHeaderRow);
            }
        }
    }

    private void bindRefreshActionView(SimpleTextView view) {
        view.setText(getString(hasCheckingProxies() ? R.string.Checking : R.string.Refresh));
        int colorKey = refreshActionEnabled ? (Theme.isCurrentThemeDark() ? Theme.key_windowBackgroundWhiteGrayText2 : Theme.key_windowBackgroundWhiteBlackText) : Theme.key_windowBackgroundWhiteGrayText3;
        float alpha = refreshActionEnabled ? (Theme.isCurrentThemeDark() ? 1f : 0.75f) : 0.55f;
        Object tag = view.getTag();
        if (!(tag instanceof Integer) || (Integer) tag != colorKey) {
            view.setTag(colorKey);
            view.setTextColor(Theme.getColor(colorKey));
        }
        if (view.getAlpha() != alpha) {
            view.setAlpha(alpha);
        }
        view.setClickable(refreshActionEnabled);
        view.setOnClickListener(refreshActionClickListener);
    }

    /** Manual re-check of all proxies (ping), regardless of the 2-minute auto-check interval. */
    private void refreshProxyStatus() {
        if (proxyStartRow == -1 || hasCheckingProxies()) {
            return;
        }
        wasCheckedAllList = false;
        boolean started = false;
        for (int a = 0, count = proxyList.size(); a < count; a++) {
            SharedConfig.ProxyInfo proxyInfo = proxyList.get(a);
            if (!proxyInfo.checking) {
                proxyInfo.availableCheckTime = 0;
                started = true;
            }
        }
        if (started) {
            checkProxyList();
            if (listAdapter != null) {
                listAdapter.notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow);
            }
            updateRefreshActionState(false);
        }
    }

    private void addOfficialProxy(String[] p) {
        ProxySettings.Builder pb = ProxySettings.builder().setAddress(p[1]).setPort(Integer.parseInt(p[2]));
        if (TextUtils.isEmpty(p[3])) {
            pb.setType(ProxySettings.Type.SOCKS5).setUser("").setPassword("");
        } else {
            pb.setType(ProxySettings.Type.MTPROTO).setSecret(p[3]);
        }
        ProxySettings settings = pb.build();
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", true);
        editor.remove("proxy_auto_disabled_by_vpn");
        settings.toSharedPreferences(editor);
        editor.commit();
        SharedConfig.currentProxy = SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
        useProxySettings = true;
        ConnectionsManager.setProxySettings(true, settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        updateRows(true);
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void updateRows(boolean notify) {
        rowCount = 0;
        officialProxies = MessagesController.getOfficialProxies();
        if (!officialProxies.isEmpty()) {
            offHeaderRow = rowCount++;
            offStartRow = rowCount;
            rowCount += officialProxies.size();
            offEndRow = rowCount;
            offInfoRow = rowCount++;
        } else {
            offHeaderRow = offStartRow = offEndRow = offInfoRow = -1;
        }
        useProxyRow = rowCount++;
        if (useProxySettings && SharedConfig.currentProxy != null && SharedConfig.proxyList.size() > 1 && IS_PROXY_ROTATION_AVAILABLE) {
            rotationRow = rowCount++;
            if (SharedConfig.proxyRotationEnabled) {
                rotationTimeoutRow = rowCount++;
                rotationTimeoutInfoRow = rowCount++;
            } else {
                rotationTimeoutRow = -1;
                rotationTimeoutInfoRow = -1;
            }
        } else {
            rotationRow = -1;
            rotationTimeoutRow = -1;
            rotationTimeoutInfoRow = -1;
        }
        if (rotationTimeoutInfoRow == -1) {
            useProxyShadowRow = rowCount++;
        } else {
            useProxyShadowRow = -1;
        }
        // exteraGram: "Disable proxy on" row
        proxyDisableRow = rowCount++;
        proxyDisableShadowRow = rowCount++;
        connectionsHeaderRow = rowCount++;

        if (notify) {
            proxyList.clear();
            proxyList.addAll(SharedConfig.proxyList);

            boolean checking = false;
            if (!wasCheckedAllList) {
                for (SharedConfig.ProxyInfo info : proxyList) {
                    if (info.checking || info.availableCheckTime == 0) {
                        checking = true;
                        break;
                    }
                }
                if (!checking) {
                    wasCheckedAllList = true;
                }
            }

            // exteraGram: pinned first, then stock order
            ProxyController.getInstance().sortProxyList(proxyList, checking, SharedConfig.currentProxy);
        }

        if (!proxyList.isEmpty()) {
            proxyStartRow = rowCount;
            rowCount += proxyList.size();
            proxyEndRow = rowCount;
        } else {
            proxyStartRow = -1;
            proxyEndRow = -1;
        }
        proxyAddRow = rowCount++;
        proxyShadowRow = rowCount++;
        if (proxyList.size() >= 10) {
            deleteAllRow = rowCount++;
        } else {
            deleteAllRow = -1;
        }
        checkProxyList();
        refreshActionEnabled = isRefreshActionEnabled(); // exteraGram
        if (notify && listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void checkProxyList() {
        for (int a = 0, count = proxyList.size(); a < count; a++) {
            final SharedConfig.ProxyInfo proxyInfo = proxyList.get(a);
            if (proxyInfo.checking || SystemClock.elapsedRealtime() - proxyInfo.availableCheckTime < 2 * 60 * 1000) {
                continue;
            }
            proxyInfo.checking = true;
            ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                proxyInfo.checking = false;
                if (time == -1) {
                    proxyInfo.available = false;
                    proxyInfo.ping = 0;
                } else {
                    proxyInfo.ping = time;
                    proxyInfo.available = true;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
            }));
        }
    }

    @Override
    protected void onDialogDismiss(Dialog dialog) {
        DownloadController.getInstance(currentAccount).checkAutodownloadSettings();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            // exteraGram: names/pins may have changed in ProxySettingsActivity
            useProxySettings = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false) && !SharedConfig.proxyList.isEmpty();
            updateRows(true);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyChangedByRotation) {
            listView.forAllChild(view -> {
                RecyclerView.ViewHolder holder = listView.getChildViewHolder(view);
                if (holder.itemView instanceof TextDetailProxyCell) {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    cell.setChecked(cell.currentInfo == SharedConfig.currentProxy);
                    cell.updateStatus();
                }
            });

            updateRows(false);
        } else if (id == NotificationCenter.proxySettingsChanged) {
            // exteraGram: may come from proxy auto-disable by network
            useProxySettings = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false) && !SharedConfig.proxyList.isEmpty();
            updateRows(true);
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (currentConnectionState != state) {
                currentConnectionState = state;
                if (listView != null && SharedConfig.currentProxy != null) {
                    int idx = proxyList.indexOf(SharedConfig.currentProxy);
                    if (idx >= 0) {
                        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(idx + proxyStartRow);
                        if (holder != null) {
                            TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                            cell.updateStatus();
                        }
                    }

                    if (currentConnectionState == ConnectionsManager.ConnectionStateConnected) {
                        updateRows(true);
                    }
                }
            }
        } else if (id == NotificationCenter.proxyCheckDone) {
            if (listView != null) {
                SharedConfig.ProxyInfo proxyInfo = (SharedConfig.ProxyInfo) args[0];
                int idx = proxyList.indexOf(proxyInfo);
                if (idx >= 0) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(idx + proxyStartRow);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.updateStatus();
                    }
                }

                boolean checking = false;
                if (!wasCheckedAllList) {
                    for (SharedConfig.ProxyInfo info : proxyList) {
                        if (info.checking || info.availableCheckTime == 0) {
                            checking = true;
                            break;
                        }
                    }
                    if (!checking) {
                        wasCheckedAllList = true;
                    }
                }
                if (!checking) {
                    updateRows(true);
                } else {
                    updateRefreshActionState(false); // exteraGram
                }
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final static int VIEW_TYPE_SHADOW = 0,
            VIEW_TYPE_TEXT_SETTING = 1,
            VIEW_TYPE_HEADER = 2,
            VIEW_TYPE_TEXT_CHECK = 3,
            VIEW_TYPE_INFO = 4,
            VIEW_TYPE_PROXY_DETAIL = 5,
            VIEW_TYPE_SLIDE_CHOOSER = 6;

        public static final int PAYLOAD_CHECKED_CHANGED = 0;
        public static final int PAYLOAD_SELECTION_CHANGED = 1;
        public static final int PAYLOAD_SELECTION_MODE_CHANGED = 2;

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;

            setHasStableIds(true);
        }

        public void toggleSelected(int position) {
            if (position < proxyStartRow || position >= proxyEndRow) {
                return;
            }
            SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
            if (selectedItems.contains(info)) {
                selectedItems.remove(info);
            } else {
                selectedItems.add(info);
            }
            notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED);
            checkActionMode();
        }

        // exteraGram
        public void selectForReorder(int position) {
            SharedConfig.ProxyInfo info = getProxyAtPosition(position);
            if (info == null) {
                return;
            }
            if (selectedItems.size() != 1 || !selectedItems.contains(info)) {
                selectedItems.clear();
                selectedItems.add(info);
                notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_CHANGED);
            } else {
                notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED);
            }
            checkActionMode();
        }

        public void clearSelected() {
            selectedItems.clear();
            notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_CHANGED);
            checkActionMode();
        }

        private void checkActionMode() {
            int selectedCount = selectedItems.size();
            boolean actionModeShowed = simpleActionBar.isActionModeShowed();
            if (selectedCount > 0) {
                simpleActionBar.setActionModeCount(selectedCount, actionModeShowed);
                updatePinAction(); // exteraGram
                if (!actionModeShowed) {
                    simpleActionBar.showActionMode();
                    notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
                }
            } else if (actionModeShowed) {
                simpleActionBar.hideActionMode();
                notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
            }
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SHADOW: {
                    break;
                }
                case VIEW_TYPE_TEXT_SETTING: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    ThemeBind.textColor(textCell.getTextView(), Theme.key_windowBackgroundWhiteBlackText);
                    if (position == proxyAddRow) {
                        textCell.setText(getString(R.string.AddProxy), deleteAllRow != -1);
                    } else if (position == deleteAllRow) {
                        ThemeBind.textColor(textCell.getTextView(), Theme.key_text_RedRegular);
                        textCell.setText(getString(R.string.DeleteAllProxies), false);
                    } else if (offStartRow != -1 && position >= offStartRow && position < offEndRow) {
                        String[] op = officialProxies.get(position - offStartRow);
                        textCell.setTextAndValue(op[0], op[1] + ":" + op[2], position != offEndRow - 1);
                    } else if (position == proxyDisableRow) { // exteraGram
                        textCell.setTextAndValue(getString(R.string.ProxyDisableOn), getProxyDisableValue(), false);
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == offHeaderRow) {
                        headerCell.setText(getString(R.string.SgOfficialProxiesHeader));
                        headerCell.setText2(null);
                    } else if (position == connectionsHeaderRow) {
                        headerCell.setText(getString(R.string.ProxyConnections));
                        // exteraGram: "Refresh" action in the header
                        headerCell.setText2(getString(hasCheckingProxies() ? R.string.Checking : R.string.Refresh));
                        SimpleTextView textView2 = headerCell.getTextView2();
                        if (textView2 != null) {
                            textView2.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
                            textView2.setTranslationY(-AndroidUtilities.dpf2(4));
                            textView2.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), 0.12f), 7));
                            bindRefreshActionView(textView2);
                        }
                    }
                    break;
                }
                case VIEW_TYPE_TEXT_CHECK: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == useProxyRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxySettings), useProxySettings, rotationRow != -1);
                    } else if (position == rotationRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyRotation), SharedConfig.proxyRotationEnabled, true);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == offInfoRow) {
                        cell.setText(getString(R.string.SgOfficialProxiesInfo));
                    } else if (position == rotationTimeoutInfoRow) {
                        cell.setText(getString(R.string.ProxyRotationTimeoutInfo));
                    }
                    break;
                }
                case VIEW_TYPE_PROXY_DETAIL: {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                    cell.setProxy(info);
                    cell.setChecked(SharedConfig.currentProxy == info);
                    cell.setItemSelected(selectedItems.contains(proxyList.get(position - proxyStartRow)), false);
                    cell.setReorderAvailable(canShowReorderHandle(info), false); // exteraGram
                    cell.setSelectionEnabled(!selectedItems.isEmpty(), false);
                    break;
                }
                case VIEW_TYPE_SLIDE_CHOOSER: {
                    if (position == rotationTimeoutRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        ArrayList<Integer> options = new ArrayList<>(ProxyRotationController.ROTATION_TIMEOUTS);
                        String[] values = new String[options.size()];
                        for (int i = 0; i < options.size(); i++) {
                            values[i] = LocaleController.formatString(R.string.ProxyRotationTimeoutSeconds, options.get(i));
                        }
                        chooseView.setCallback(i -> {
                            SharedConfig.proxyRotationTimeout = i;
                            SharedConfig.saveConfig();
                        });
                        chooseView.setOptions(SharedConfig.proxyRotationTimeout, values);
                    }
                    break;
                }
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List payloads) {
            if (holder.getItemViewType() == VIEW_TYPE_PROXY_DETAIL && !payloads.isEmpty()) {
                TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                if (payloads.contains(PAYLOAD_SELECTION_CHANGED)) {
                    cell.setItemSelected(selectedItems.contains(info), true);
                    cell.setReorderAvailable(canShowReorderHandle(info), true); // exteraGram
                }
                if (payloads.contains(PAYLOAD_SELECTION_MODE_CHANGED)) {
                    cell.setReorderAvailable(canShowReorderHandle(info), true); // exteraGram
                    cell.setSelectionEnabled(!selectedItems.isEmpty(), true);
                }
            } else if (holder.getItemViewType() == VIEW_TYPE_TEXT_CHECK && payloads.contains(PAYLOAD_CHECKED_CHANGED)) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                }
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            if (viewType == VIEW_TYPE_TEXT_CHECK) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                int position = holder.getAdapterPosition();
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == useProxyRow || position == proxyDisableRow /* exteraGram */ || position == rotationRow || position == proxyAddRow || position == deleteAllRow || position >= proxyStartRow && position < proxyEndRow || offStartRow != -1 && position >= offStartRow && position < offEndRow;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_TEXT_SETTING:
                    view = new TextSettingsCell(mContext);
                    ThemeBind.backgroundColor(view, Theme.key_windowBackgroundWhite);
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext, Theme.key_windowBackgroundWhiteBlueHeader, 18, 7, true); // exteraGram: text2 for "Refresh"
                    ThemeBind.backgroundColor(view, Theme.key_windowBackgroundWhite);
                    break;
                case VIEW_TYPE_TEXT_CHECK:
                    view = new TextCheckCell(mContext);
                    ThemeBind.backgroundColor(view, Theme.key_windowBackgroundWhite);
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_SLIDE_CHOOSER:
                    view = new SlideChooseView(mContext);
                    ThemeBind.backgroundColor(view, Theme.key_windowBackgroundWhite);
                    break;
                case VIEW_TYPE_PROXY_DETAIL:
                default:
                    view = new TextDetailProxyCell(mContext);
                    ThemeBind.backgroundColor(view, Theme.key_windowBackgroundWhite);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public long getItemId(int position) {
            // Random stable ids, could be anything non-repeating
            if (position == offHeaderRow) {
                return -20;
            } else if (position == offInfoRow) {
                return -21;
            } else if (offStartRow != -1 && position >= offStartRow && position < offEndRow) {
                return -1000 - (position - offStartRow);
            } else if (position == useProxyShadowRow) {
                return -1;
            } else if (position == proxyShadowRow) {
                return -2;
            } else if (position == proxyAddRow) {
                return -3;
            } else if (position == useProxyRow) {
                return -4;
            } else if (position == connectionsHeaderRow) {
                return -6;
            } else if (position == deleteAllRow) {
                return -8;
            } else if (position == rotationRow) {
                return -9;
            } else if (position == rotationTimeoutRow) {
                return -10;
            } else if (position == rotationTimeoutInfoRow) {
                return -11;
            } else if (position == proxyDisableRow) { // exteraGram
                return -12;
            } else if (position == proxyDisableShadowRow) {
                return -17;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                return proxyList.get(position - proxyStartRow).hashCode();
            } else {
                return -7;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == useProxyShadowRow || position == proxyShadowRow || position == proxyDisableShadowRow /* exteraGram */) {
                return VIEW_TYPE_SHADOW;
            } else if (position == proxyAddRow || position == deleteAllRow || position == proxyDisableRow /* exteraGram */ || offStartRow != -1 && position >= offStartRow && position < offEndRow) {
                return VIEW_TYPE_TEXT_SETTING;
            } else if (position == offHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == useProxyRow || position == rotationRow) {
                return VIEW_TYPE_TEXT_CHECK;
            } else if (position == connectionsHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == rotationTimeoutRow) {
                return VIEW_TYPE_SLIDE_CHOOSER;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                return VIEW_TYPE_PROXY_DETAIL;
            } else {
                return VIEW_TYPE_INFO;
            }
        }
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();


//        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));




        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText6));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGreenText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_text_RedRegular));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"checkImageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"reorderImageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2)); // exteraGram


        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));


        return themeDescriptions;
    }

    /** theme editor: colors this page shows (former ThemeDescription keys) */
    @Override
    public void addThemeColorKeys(HashSet<Integer> keys) {
        super.addThemeColorKeys(keys);
        keys.add(Theme.key_windowBackgroundWhiteGrayText4);
        keys.add(Theme.key_windowBackgroundGrayShadow);
        keys.add(Theme.key_windowBackgroundWhiteGrayText2);
        keys.add(Theme.key_windowBackgroundWhiteBlackText);
        keys.add(Theme.key_windowBackgroundWhiteBlueHeader);
        keys.add(Theme.key_windowBackgroundWhiteGrayText3);
        keys.add(Theme.key_windowBackgroundWhiteValueText);
        keys.add(Theme.key_divider);
        keys.add(Theme.key_listSelector);
        keys.add(Theme.key_actionBarDefaultSelector);
        keys.add(Theme.key_actionBarDefaultTitle);
        keys.add(Theme.key_actionBarDefaultIcon);
        keys.add(Theme.key_actionBarDefault);
        keys.add(Theme.key_windowBackgroundWhite);
    }
}
