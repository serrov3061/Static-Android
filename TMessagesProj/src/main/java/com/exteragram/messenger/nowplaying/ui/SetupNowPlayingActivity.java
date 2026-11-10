package com.exteragram.messenger.nowplaying.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.api.dto.NowPlayingInfoDTO;
import com.exteragram.messenger.api.model.NowPlayingServiceType;
import com.exteragram.messenger.nowplaying.NowPlayingController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogRadioCell;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CircularProgressDrawable;
import org.telegram.ui.Components.CrossfadeDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Scrobbling service setup (Last.fm / stats.fm username) — port of exteraGram's Kotlin SetupNowPlayingActivity.
 * On StaticGram the entry point is hidden ({@link NowPlayingController#SETUP_ENABLED}): exteraGram registers the
 * account through its backend/bot, without it nobody else would see the track. The value is only stored locally.
 */
public class SetupNowPlayingActivity extends BaseFragment {

    /** Notified after a successful save (exteraGram: NotificationCenter.nowPlayingUpdated). */
    public interface OnUpdated {
        void onNowPlayingUpdated(NowPlayingServiceType serviceType);
    }

    private static final class NowPlayingState {
        final NowPlayingServiceType serviceType;
        final String username;

        NowPlayingState(NowPlayingServiceType serviceType, String username) {
            this.serviceType = serviceType;
            this.username = username;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof NowPlayingState)) return false;
            NowPlayingState s = (NowPlayingState) o;
            return serviceType == s.serviceType && Objects.equals(username, s.username);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serviceType, username);
        }
    }

    private ActionBarMenuItem doneButton;
    private CrossfadeDrawable doneButtonDrawable;
    private NowPlayingState initialState;
    private NowPlayingState currentState = new NowPlayingState(NowPlayingServiceType.NONE, null);
    private UniversalRecyclerView listView;
    private EditTextCell nowPlayingEdit;
    private float shiftDp = -4;
    private final OnUpdated onUpdated;

    public SetupNowPlayingActivity() {
        this(null);
    }

    public SetupNowPlayingActivity(OnUpdated onUpdated) {
        this.onUpdated = onUpdated;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NowPlaying));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == 0) {
                    processDone(true);
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.adapter.setApplyBackground(false);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        nowPlayingEdit = new EditTextCell(context, LocaleController.getString(R.string.Username), false, false, -1, getResourceProvider()) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                currentState = new NowPlayingState(currentState.serviceType, newText == null ? "" : newText.toString());
                checkDone(true);
            }
        };
        nowPlayingEdit.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        nowPlayingEdit.hideKeyboardOnEnter();

        NowPlayingController.getNowPlayingInfo(info -> AndroidUtilities.runOnUIThread(() -> {
            NowPlayingState state;
            if (info != null) {
                state = new NowPlayingState(info.getServiceType(), info.getUsername() != null ? info.getUsername() : "");
            } else {
                state = new NowPlayingState(NowPlayingServiceType.NONE, "");
            }
            currentState = state;
            initialState = state;
            nowPlayingEdit.setText(state.username);
            if (listView != null) {
                listView.adapter.update(true);
            }
            checkDone(false);
        }));

        Drawable checkmark = ContextCompat.getDrawable(context, R.drawable.ic_ab_done);
        if (checkmark != null) {
            checkmark = checkmark.mutate();
            checkmark.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon), PorterDuff.Mode.MULTIPLY));
        }
        doneButtonDrawable = new CrossfadeDrawable(checkmark, new CircularProgressDrawable(Theme.getColor(Theme.key_actionBarDefaultIcon)));
        doneButton = actionBar.createMenu().addItemWithWidth(0, doneButtonDrawable, AndroidUtilities.dp(56), LocaleController.getString(R.string.Done));
        checkDone(false);

        return fragmentView = frameLayout;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.SelectService)));
        for (NowPlayingServiceType type : NowPlayingServiceType.values()) {
            items.add(UItem.asRadio(type.ordinal(), type.getDisplayName()).setChecked(type == currentState.serviceType && initialState != null));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.SelectServiceInfo)));
        if (currentState.serviceType != NowPlayingServiceType.NONE) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.EnterUsername)));
            items.add(UItem.asCustom(nowPlayingEdit));
            items.add(UItem.asShadow(AndroidUtilities.replaceSingleTag(LocaleController.getString(R.string.EnterUsernameInfo), () ->
                    Browser.openUrl(getParentActivity(), currentState.serviceType == NowPlayingServiceType.LAST_FM ? "https://www.last.fm/" : "https://stats.fm/"))));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (view instanceof DialogRadioCell) {
            NowPlayingServiceType[] types = NowPlayingServiceType.values();
            if (item.id >= 0 && item.id < types.length) {
                currentState = new NowPlayingState(types[item.id], currentState.username);
                listView.adapter.update(true);
                checkDone(true);
            }
        }
    }

    private void processDone(boolean error) {
        if (doneButtonDrawable.getProgress() > 0f) {
            return;
        }
        if (error && currentState.serviceType != NowPlayingServiceType.NONE) {
            CharSequence text = nowPlayingEdit.getText();
            if (text == null || text.length() == 0) {
                BotWebViewVibrationEffect.APP_ERROR.vibrate();
                AndroidUtilities.shakeViewSpring(nowPlayingEdit, shiftDp = -shiftDp);
                return;
            }
        }
        doneButtonDrawable.animateToProgress(1f);
        final NowPlayingInfoDTO info = new NowPlayingInfoDTO(currentState.serviceType, currentState.serviceType == NowPlayingServiceType.NONE ? null : currentState.username);
        NowPlayingController.updateNowPlayingInfo(info, ok -> AndroidUtilities.runOnUIThread(() -> {
            if (!ok) {
                doneButtonDrawable.animateToProgress(0f);
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.UnknownError)).show();
            } else {
                if (onUpdated != null) {
                    onUpdated.onNowPlayingUpdated(info.getServiceType());
                }
                finishFragment();
            }
        }));
    }

    private void checkDone(boolean animated) {
        if (doneButton == null) {
            return;
        }
        boolean unchanged = Objects.equals(initialState, currentState);
        doneButton.setEnabled(!unchanged);
        if (animated) {
            doneButton.animate().alpha(unchanged ? 0f : 1f).scaleX(unchanged ? 0f : 1f).scaleY(unchanged ? 0f : 1f).setDuration(180).start();
        } else {
            doneButton.setAlpha(unchanged ? 0f : 1f);
            doneButton.setScaleX(unchanged ? 0f : 1f);
            doneButton.setScaleY(unchanged ? 0f : 1f);
        }
    }
}
