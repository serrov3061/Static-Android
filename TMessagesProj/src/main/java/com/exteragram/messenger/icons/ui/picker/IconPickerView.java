package com.exteragram.messenger.icons.ui.picker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.icons.IconPack;
import com.exteragram.messenger.icons.ui.IconPacksEditorActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * exteraGram IconPickerView: a draggable floating button shown while an icon pack is being edited; tapping it opens
 * a list of the icons used by the visible screens (IconObserver), and a tap on an icon opens the replace-icon sheet.
 */
@SuppressLint("ViewConstructor")
public class IconPickerView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final ActionBar actionBar;
    private final ImageView actionIcon;
    private final LinearLayout bigLayout;
    private SpringAnimation fabXSpring;
    private SpringAnimation fabYSpring;
    private Drawable floatingButtonBackground;
    private final FrameLayout floatingButtonContainer;
    private boolean inLongPress;
    private boolean isBigMenuShown;
    private boolean isFromFling;
    private boolean isScrollDisallowed;
    private boolean isScrolling;
    private final UniversalRecyclerView listView;
    private final SharedPreferences mPrefs;
    private final Runnable onLongPress;
    private final ActionBarMenuItem otherButton;
    private String query;
    private final ActionBarMenuSubItem saveItem;
    private boolean searching;
    private int systemBottomInset;
    private int systemTopInset;
    private final int touchSlop;
    private int wasStatusBar;

    public IconPickerView(Context context) {
        super(context);
        onLongPress = () -> {
            inLongPress = true;
            try {
                performHapticFeedback(0);
            } catch (Exception ignore) {
            }
        };
        mPrefs = context.getSharedPreferences("icon_picker", Context.MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        GestureDetector gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            private float startX;
            private float startY;

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (inLongPress || isBigMenuShown) {
                    return false;
                }
                showIconList(true);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (!isScrolling || inLongPress) {
                    return false;
                }
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                float x = fabXSpring.getSpring().getFinalPosition() + velocityX / 7f;
                fabXSpring.getSpring().setFinalPosition(clampX(metrics, x >= getWidth() / 2f ? Integer.MAX_VALUE : Integer.MIN_VALUE));
                fabYSpring.getSpring().setFinalPosition(clampY(metrics, fabYSpring.getSpring().getFinalPosition() + velocityY / 10f));
                fabXSpring.start();
                fabYSpring.start();
                isFromFling = true;
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (!inLongPress) {
                    AndroidUtilities.cancelRunOnUIThread(onLongPress);
                }
                if (!isScrolling && !isScrollDisallowed) {
                    if (Math.abs(distanceX) >= touchSlop || Math.abs(distanceY) >= touchSlop) {
                        startX = fabXSpring.getSpring().getFinalPosition();
                        startY = fabYSpring.getSpring().getFinalPosition();
                        isScrolling = true;
                    } else {
                        isScrollDisallowed = false;
                    }
                }
                if (isScrolling && !inLongPress && e1 != null) {
                    fabXSpring.getSpring().setFinalPosition(startX + e2.getRawX() - e1.getRawX());
                    fabYSpring.getSpring().setFinalPosition(startY + e2.getRawY() - e1.getRawY());
                    fabXSpring.start();
                    fabYSpring.start();
                }
                return isScrolling;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);

        floatingButtonContainer = new FrameLayout(context) {
            @Override
            public void invalidate() {
                super.invalidate();
                IconPickerView.this.invalidate();
            }

            @Override
            public void setTranslationX(float translationX) {
                super.setTranslationX(translationX);
                IconPickerView.this.invalidate();
            }

            @Override
            public void setTranslationY(float translationY) {
                super.setTranslationY(translationY);
                IconPickerView.this.invalidate();
            }

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                boolean result = gestureDetector.onTouchEvent(event);
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    AndroidUtilities.runOnUIThread(onLongPress, 200);
                    return result;
                }
                if (event.getAction() != MotionEvent.ACTION_UP && event.getAction() != MotionEvent.ACTION_CANCEL) {
                    return result;
                }
                AndroidUtilities.cancelRunOnUIThread(onLongPress);
                if (!isFromFling) {
                    updateSpringPositions();
                }
                inLongPress = false;
                isScrolling = false;
                isScrollDisallowed = false;
                isFromFling = false;
                return result;
            }
        };
        actionIcon = new ImageView(context);
        actionIcon.setImageResource(R.drawable.msg_palette);
        floatingButtonContainer.addView(actionIcon, LayoutHelper.createFrame(24, 24, android.view.Gravity.CENTER));
        floatingButtonContainer.setVisibility(GONE);
        addView(floatingButtonContainer, LayoutHelper.createFrame(56, 56));

        bigLayout = new LinearLayout(context);
        bigLayout.setOrientation(LinearLayout.VERTICAL);
        bigLayout.setVisibility(GONE);

        actionBar = new ActionBar(context);
        actionBar.setOccupyStatusBar(false);
        updateTitle();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                if (otherButton != null) {
                    otherButton.setVisibility(GONE);
                }
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                query = null;
                if (otherButton != null) {
                    otherButton.setVisibility(VISIBLE);
                }
                listView.adapter.update(true);
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString();
                listView.adapter.update(true);
            }
        });
        otherButton = menu.addItem(1, R.drawable.ic_ab_other);
        otherButton.addSubItem(2, R.drawable.msg_media, LocaleController.getString(R.string.IconPickerAllIcons));
        saveItem = otherButton.addSubItem(3, R.drawable.ic_ab_done, LocaleController.getString(R.string.IconPickerSaveAndExit));
        int saveColor = Theme.getColor(Theme.key_featuredStickers_addButtonPressed);
        saveItem.setColors(saveColor, saveColor);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    showIconList(false);
                } else if (id == 3) {
                    ExteraConfig.setEditingIconPackId(null);
                    if (context instanceof LaunchActivity) {
                        IconPickerController.setActive((LaunchActivity) context, false);
                    }
                } else if (id == 2 && ExteraConfig.getEditingIconPackId() != null) {
                    IconPack pack = IconManager.INSTANCE.findPackById(ExteraConfig.getEditingIconPackId());
                    if (pack != null && context instanceof LaunchActivity) {
                        ((LaunchActivity) context).presentFragment(new IconPacksEditorActivity(pack));
                        showIconList(false);
                    }
                }
            }
        });
        bigLayout.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // dialog = true: rows get the dialog background of the popup (exteraGram marks them UItem.transparent)
        listView = new UniversalRecyclerView(context, UserConfig.selectedAccount, 0, true, this::fillItems, this::onClick, null, null);
        listView.setLayoutManager(new LinearLayoutManager(context));
        bigLayout.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        addView(bigLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 8, 8, 8, 8));
        bigLayout.setClipToOutline(true);
        bigLayout.setOutlineProvider(ViewOutlineProviderImpl.boundsWithRoundRect(AndroidUtilities.dp(10)));

        setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(insets);
            if (!isScrolling && !isBigMenuShown && fabXSpring != null) {
                updateSpringPositions();
            }
            return insets;
        });
        updateDrawables();
        setWillNotDraw(false);
    }

    @SuppressWarnings("deprecation")
    private void applyInsets(WindowInsets insets) {
        systemTopInset = insets.getSystemWindowInsetTop();
        systemBottomInset = insets.getSystemWindowInsetBottom();
        LayoutParams params = (LayoutParams) bigLayout.getLayoutParams();
        if (params != null) {
            params.topMargin = AndroidUtilities.dp(8) + systemTopInset;
            params.bottomMargin = AndroidUtilities.dp(8) + systemBottomInset;
            bigLayout.setLayoutParams(params);
        }
    }

    private void updateSpringPositions() {
        if (fabXSpring == null || fabYSpring == null) {
            return;
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        fabXSpring.getSpring().setFinalPosition(clampX(metrics, fabXSpring.getSpring().getFinalPosition() >= getWidth() / 2f ? Integer.MAX_VALUE : Integer.MIN_VALUE));
        fabYSpring.getSpring().setFinalPosition(clampY(metrics, fabYSpring.getSpring().getFinalPosition()));
        fabXSpring.start();
        fabYSpring.start();
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        IconManager.INSTANCE.showReplaceAlert(getContext(), item.id);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String lowerQuery = !searching || TextUtils.isEmpty(query) ? null : query.toLowerCase(Locale.ROOT);
        IconPack pack = ExteraConfig.getEditingIconPackId() != null ? IconManager.INSTANCE.findPackById(ExteraConfig.getEditingIconPackId()) : null;
        ArrayList<UItem> result = new ArrayList<>();
        for (Integer resId : IconObserver.INSTANCE.getUsedIcons()) {
            try {
                String name = getResources().getResourceEntryName(resId);
                if ((lowerQuery != null && !name.toLowerCase(Locale.ROOT).contains(lowerQuery)) || IconManager.INSTANCE.isBlacklisted(name)) {
                    continue;
                }
                result.add(IconPacksEditorActivity.EditorIconCell.Factory.asIcon(resId, name, pack));
            } catch (Exception ignore) {
            }
        }
        Collections.sort(result, (a, b) -> a.text.toString().compareTo(b.text.toString()));
        items.addAll(result);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child == bigLayout) {
            canvas.drawColor(Color.argb((int) (bigLayout.getAlpha() * 122f), 0, 0, 0));
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    public boolean onBackPressed(boolean invoked) {
        if (!isBigMenuShown) {
            return false;
        }
        if (invoked) {
            if (searching) {
                actionBar.closeSearchField();
                return true;
            }
            showIconList(false);
        }
        return true;
    }

    public void saveConfig() {
        if (fabXSpring == null || fabYSpring == null) {
            return;
        }
        mPrefs.edit().putFloat("x", fabXSpring.getSpring().getFinalPosition()).putFloat("y", fabYSpring.getSpring().getFinalPosition()).apply();
    }

    private void updateTitle() {
        if (actionBar == null || ExteraConfig.getEditingIconPackId() == null) {
            return;
        }
        IconPack pack = IconManager.INSTANCE.findPackById(ExteraConfig.getEditingIconPackId());
        if (pack != null) {
            actionBar.setTitle(pack.getName());
        }
    }

    private void updateDrawables() {
        Drawable circle = Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(56), Theme.getColor(Theme.key_chats_actionBackground), Theme.getColor(Theme.key_chats_actionPressedBackground));
        Drawable shadow = ResourcesCompat.getDrawable(getResources(), R.drawable.floating_shadow, getContext().getTheme()).mutate();
        shadow.setColorFilter(new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.MULTIPLY));
        CombinedDrawable combined = new CombinedDrawable(shadow, circle, 0, 0);
        combined.setIconSize(AndroidUtilities.dp(56), AndroidUtilities.dp(56));
        floatingButtonBackground = combined;

        GradientDrawable background = new GradientDrawable();
        background.setColor(Theme.getColor(Theme.key_dialogBackground));
        background.setCornerRadius(AndroidUtilities.dp(10));
        bigLayout.setBackground(background);
        bigLayout.setElevation(AndroidUtilities.dp(4));
        if (actionIcon != null) {
            actionIcon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.SRC_IN));
        }
        if (actionBar != null) {
            actionBar.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground));
            actionBar.setTitleColor(Theme.getColor(Theme.key_dialogTextBlack));
            actionBar.setItemsColor(Theme.getColor(Theme.key_dialogTextBlack), false);
            actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_dialogButtonSelector), false);
        }
        if (otherButton != null) {
            otherButton.setPopupItemsColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem), false);
            otherButton.setPopupItemsColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon), true);
            otherButton.redrawPopup(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground));
            if (saveItem != null) {
                int color = Theme.getColor(Theme.key_featuredStickers_addButtonPressed);
                saveItem.setColors(color, color);
            }
        }
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return isBigMenuShown;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(floatingButtonContainer.getTranslationX(), floatingButtonContainer.getTranslationY());
        canvas.scale(floatingButtonContainer.getScaleX(), floatingButtonContainer.getScaleY(), floatingButtonContainer.getPivotX(), floatingButtonContainer.getPivotY());
        floatingButtonBackground.setAlpha((int) (floatingButtonContainer.getAlpha() * 255));
        floatingButtonBackground.setBounds(floatingButtonContainer.getLeft(), floatingButtonContainer.getTop(), floatingButtonContainer.getRight(), floatingButtonContainer.getBottom());
        floatingButtonBackground.draw(canvas);
        canvas.restore();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.iconPackUpdated) {
            updateTitle();
            Parcelable state = listView.getLayoutManager() != null ? listView.getLayoutManager().onSaveInstanceState() : null;
            listView.adapter.update(true);
            if (state != null) {
                listView.getLayoutManager().onRestoreInstanceState(state);
            }
        } else if (id == NotificationCenter.didSetNewTheme) {
            updateDrawables();
            listView.adapter.update(false);
        }
    }

    private float clampX(DisplayMetrics metrics, float x) {
        return MathUtils.clamp(x, AndroidUtilities.dp(16), metrics.widthPixels - AndroidUtilities.dp(72));
    }

    private float clampY(DisplayMetrics metrics, float y) {
        return MathUtils.clamp(y, systemTopInset + AndroidUtilities.dp(16), (metrics.heightPixels - systemBottomInset) - AndroidUtilities.dp(72));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        WindowInsets insets = getRootWindowInsets();
        if (insets != null) {
            applyInsets(insets);
        }
        float x = mPrefs.getFloat("x", -1f);
        float y = mPrefs.getFloat("y", -1f);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        floatingButtonContainer.setTranslationX(clampX(metrics, (x == -1f || x >= metrics.widthPixels / 2f) ? Integer.MAX_VALUE : Integer.MIN_VALUE));
        floatingButtonContainer.setTranslationY(clampY(metrics, y == -1f ? metrics.heightPixels / 2f : y));
        fabXSpring = new SpringAnimation(floatingButtonContainer, DynamicAnimation.TRANSLATION_X, floatingButtonContainer.getTranslationX())
                .setSpring(new SpringForce(floatingButtonContainer.getTranslationX()).setStiffness(650f).setDampingRatio(0.75f));
        fabYSpring = new SpringAnimation(floatingButtonContainer, DynamicAnimation.TRANSLATION_Y, floatingButtonContainer.getTranslationY())
                .setSpring(new SpringForce(floatingButtonContainer.getTranslationY()).setStiffness(650f).setDampingRatio(0.75f));
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.iconPackUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (fabXSpring != null) {
            fabXSpring.cancel();
        }
        if (fabYSpring != null) {
            fabYSpring.cancel();
        }
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.iconPackUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
    }

    public void showIconList(boolean show) {
        if (isBigMenuShown == show) {
            return;
        }
        isBigMenuShown = show;
        if (show) {
            bigLayout.setVisibility(VISIBLE);
            listView.adapter.update(true);
        }
        Window window = ((Activity) getContext()).getWindow();
        if (show) {
            wasStatusBar = window.getStatusBarColor();
        }
        LayoutParams params = (LayoutParams) bigLayout.getLayoutParams();
        float top = params.topMargin;
        float left = params.leftMargin;
        float fromX = floatingButtonContainer.getTranslationX() - left;
        float fromY = floatingButtonContainer.getTranslationY() - top;
        new SpringAnimation(new FloatValueHolder(show ? 0f : 1000f))
                .setSpring(new SpringForce(1000f).setStiffness(900f).setDampingRatio(1f).setFinalPosition(show ? 1000f : 0f))
                .addUpdateListener((animation, value, velocity) -> {
                    float progress = value / 1000f;
                    bigLayout.setAlpha(progress);
                    bigLayout.setTranslationX(AndroidUtilities.lerp(fromX, 0f, progress));
                    bigLayout.setTranslationY(AndroidUtilities.lerp(fromY, 0f, progress));
                    bigLayout.setPivotX((floatingButtonContainer.getTranslationX() - left) + AndroidUtilities.dp(28));
                    bigLayout.setPivotY((floatingButtonContainer.getTranslationY() - top) + AndroidUtilities.dp(28));
                    if (bigLayout.getWidth() != 0) {
                        bigLayout.setScaleX(AndroidUtilities.lerp(floatingButtonContainer.getWidth() / (float) bigLayout.getWidth(), 1f, progress));
                    }
                    if (bigLayout.getHeight() != 0) {
                        bigLayout.setScaleY(AndroidUtilities.lerp(floatingButtonContainer.getHeight() / (float) bigLayout.getHeight(), 1f, progress));
                    }
                    floatingButtonContainer.setTranslationX(AndroidUtilities.lerp(fromX + left, getWidth() / 2f - AndroidUtilities.dp(28), progress));
                    floatingButtonContainer.setTranslationY(AndroidUtilities.lerp(fromY + top, getHeight() / 2f - AndroidUtilities.dp(28), progress));
                    floatingButtonContainer.setAlpha(1f - progress);
                    window.setStatusBarColor(ColorUtils.blendARGB(wasStatusBar, 0x7a000000, progress));
                    invalidate();
                })
                .addEndListener((animation, canceled, value, velocity) -> {
                    floatingButtonContainer.setTranslationX(fromX + left);
                    floatingButtonContainer.setTranslationY(fromY + top);
                    if (!show) {
                        bigLayout.setVisibility(GONE);
                    }
                })
                .start();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (fabXSpring == null || fabYSpring == null) {
            return;
        }
        fabXSpring.cancel();
        fabYSpring.cancel();
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        floatingButtonContainer.setTranslationX(clampX(metrics, floatingButtonContainer.getTranslationX() >= metrics.widthPixels / 2f ? Integer.MAX_VALUE : Integer.MIN_VALUE));
        floatingButtonContainer.setTranslationY(clampY(metrics, floatingButtonContainer.getTranslationY()));
        fabXSpring.getSpring().setFinalPosition(floatingButtonContainer.getTranslationX());
        fabYSpring.getSpring().setFinalPosition(floatingButtonContainer.getTranslationY());
    }

    public void showFab() {
        floatingButtonContainer.setVisibility(VISIBLE);
        new SpringAnimation(new FloatValueHolder(0f))
                .setSpring(new SpringForce(1000f).setStiffness(750f).setDampingRatio(0.75f))
                .addUpdateListener((animation, value, velocity) -> {
                    float progress = value / 1000f;
                    floatingButtonContainer.setPivotX(AndroidUtilities.dp(28));
                    floatingButtonContainer.setPivotY(AndroidUtilities.dp(28));
                    floatingButtonContainer.setScaleX(progress);
                    floatingButtonContainer.setScaleY(progress);
                    floatingButtonContainer.setAlpha(MathUtils.clamp(progress, 0f, 1f));
                    invalidate();
                })
                .start();
    }

    public void dismiss(Runnable onDismissed) {
        onDismissed.run();
    }
}
