package com.exteragram.messenger.player.ui;

import android.view.View;
import com.exteragram.messenger.ExteraConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public class SpeedPitchMenuItem {
    private final ActionBarMenuSubItem cell;
    private final ActionBarMenuItem.Item gap;
    private final ActionBarMenuItem.Item item;

    public SpeedPitchMenuItem(ActionBarMenuItem actionBarMenuItem, Theme.ResourcesProvider resourcesProvider) {
        ActionBarMenuSubItem actionBarMenuSubItem = new ActionBarMenuSubItem(actionBarMenuItem.getContext(), 2, false, false, resourcesProvider);
        this.cell = actionBarMenuSubItem;
        actionBarMenuSubItem.setTextAndIcon(LocaleController.getString(R.string.PlayerSpeedPitch), R.drawable.msg_tone_on);
        actionBarMenuSubItem.setMinimumWidth(AndroidUtilities.dp(196.0f));
        actionBarMenuSubItem.setOnClickListener(new View.OnClickListener() { // from class: com.exteragram.messenger.player.ui.SpeedPitchMenuItem$$ExternalSyntheticLambda0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                SpeedPitchMenuItem.this.lambda$new$0(view);
            }
        });
        this.gap = actionBarMenuItem.lazilyAddColoredGap();
        this.item = actionBarMenuItem.lazilyAddView(actionBarMenuSubItem, LayoutHelper.createLinear(-1, 48));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$0(View view) {
        ExteraConfig.setPlayerSpeedPitch(!ExteraConfig.getPlayerSpeedPitch());
        this.cell.setChecked(ExteraConfig.getPlayerSpeedPitch());
        MediaController.getInstance().restoreMusicPlaybackSpeed();
    }

    public void update(boolean z) {
        int i = (z && ExteraConfig.getNewPlayerStyle()) ? 0 : 8;
        this.gap.setVisibility(i);
        this.item.setVisibility(i);
        this.cell.setChecked(ExteraConfig.getPlayerSpeedPitch());
    }
}
