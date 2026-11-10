package com.exteragram.messenger.components;

import android.app.Activity;

import com.exteragram.messenger.api.dto.BoostySubscriberDTO;

import org.telegram.ui.ActionBar.BottomSheet;

import java.util.List;

/** Stub: exteraGram Boosty subscribers sheet (never shown: the subscriber list is always empty). */
public class BoostyBottomSheet extends BottomSheet {

    public BoostyBottomSheet(Activity activity, List<BoostySubscriberDTO> subscribers) {
        super(activity, false);
    }

    public void onButtonClick() {
    }
}
