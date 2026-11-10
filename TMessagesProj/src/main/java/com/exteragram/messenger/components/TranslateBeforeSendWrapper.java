package com.exteragram.messenger.components;

import android.annotation.SuppressLint;
import android.content.Context;

import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.Theme;

/**
 * "Translate to …" item for send menus (long press on Send). Click translates the input text into the
 * send target language; long press or the right arrow picks that language.
 * In exteraGram it is subclassed in ShareAlert, ChatActivityEnterView, ChatAttachAlert and DialogsActivity.
 */
@SuppressLint("ViewConstructor")
public abstract class TranslateBeforeSendWrapper extends ActionBarMenuSubItem {

    public abstract void onClick();

    public TranslateBeforeSendWrapper(Context context, boolean top, boolean bottom, Theme.ResourcesProvider resourcesProvider) {
        super(context, top, bottom, resourcesProvider);
        setTextAndIcon(LocaleController.getString(R.string.TranslateTo), R.drawable.msg_translate);
        setSubtext(TranslatorUtils.getSendTargetLanguageTitle());
        setMinimumWidth(AndroidUtilities.dp(196));
        setItemHeight(56);
        setOnClickListener(v -> onClick());
        setOnLongClickListener(v -> showDialog(context));
        setRightIcon(R.drawable.msg_arrowright);
        getRightIcon().setOnClickListener(v -> showDialog(context));
    }

    private boolean showDialog(Context context) {
        CharSequence[] titles = TranslatorUtils.getTargetLanguageTitles();
        PopupUtils.showDialog(titles.clone(), LocaleController.getString(R.string.Language), TranslatorUtils.getSendTargetLanguageIndex(), context, i -> {
            TranslatorUtils.setSendTargetLanguage(TranslatorUtils.getTargetLanguageCodeByIndex(i));
            setSubtext(TranslatorUtils.getSendTargetLanguageTitle());
        });
        return true;
    }
}
