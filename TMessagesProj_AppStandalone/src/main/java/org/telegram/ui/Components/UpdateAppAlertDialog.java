package org.telegram.ui.Components;

import android.content.Context;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;

/**
 * StaticGram: the optional update sheet. Same card as the mandatory screen ({@link SgAppUpdateView}):
 * «Обновить» downloads in place with progress (the sheet stays open, the download keeps going in the
 * background if it is closed), then installs; «Скачать в браузере» is the fallback.
 */
public class UpdateAppAlertDialog extends BottomSheet {

    private final SgAppUpdateView updateView;

    public UpdateAppAlertDialog(Context context, TLRPC.TL_help_appUpdate update, int account) {
        super(context, false);
        setCanceledOnTouchOutside(!update.can_not_skip);
        setCancelable(!update.can_not_skip);
        fixNavigationBar();

        updateView = new SgAppUpdateView(context, update.can_not_skip, true, null);
        updateView.setOnLater(this::dismiss);
        updateView.setAppUpdate(account, update);
        setCustomView(updateView);
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }
}
