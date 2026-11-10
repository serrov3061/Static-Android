package com.exteragram.messenger.icons.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.icons.IconPack;
import com.exteragram.messenger.icons.ui.IconPacksEditorActivity;
import com.exteragram.messenger.icons.ui.picker.IconPickerController;
import com.exteragram.messenger.utils.system.VibratorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineEditText;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.HashMap;
import java.util.UUID;

/**
 * exteraGram NewIconPackBottomSheet: name (64 chars), author and version of a new custom pack ("custom.&lt;uuid&gt;"),
 * which is enabled and opened in the editor with the icon picker; with a pack it edits that pack's info.
 */
public class NewIconPackBottomSheet extends BottomSheet {

    private final IconPack packToEdit;
    private final BaseFragment parentFragment;
    private OutlineEditText nameField;
    private OutlineEditText authorField;
    private OutlineEditText versionField;
    private ButtonWithCounterView doneButton;

    public NewIconPackBottomSheet(BaseFragment fragment, Context context) {
        this(fragment, context, null);
    }

    public NewIconPackBottomSheet(BaseFragment fragment, Context context, IconPack packToEdit) {
        super(context, true);
        this.packToEdit = packToEdit;
        fixNavigationBar();
        waitingKeyboard = true;
        smoothKeyboardAnimationEnabled = true;
        parentFragment = fragment;
        setCustomView(createView(getContext()));
        setTitle(LocaleController.getString(packToEdit == null ? R.string.NewIconPack : R.string.EditInfo), true);
    }

    @SuppressLint("ClickableViewAccessibility")
    public View createView(Context context) {
        ScrollView scrollView = new ScrollView(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        layout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));
        layout.setOnTouchListener((v, event) -> true);

        FrameLayout fields = new FrameLayout(context);
        layout.addView(fields, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

        nameField = new OutlineEditText(context);
        nameField.getEditText().setInputType(49152);
        nameField.getEditText().setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            int keep = 64 - (dest.length() - (dend - dstart));
            if (keep < end - start) {
                VibratorUtils.vibrate();
                AndroidUtilities.shakeView(nameField);
            }
            if (keep <= 0) {
                return "";
            }
            if (keep >= end - start) {
                return null;
            }
            keep += start;
            if (Character.isHighSurrogate(source.charAt(keep - 1))) {
                keep--;
                if (keep == start) {
                    return "";
                }
            }
            return source.subSequence(start, keep);
        }});
        nameField.getEditText().setImeOptions(EditorInfo.IME_ACTION_NEXT);
        nameField.setHint(LocaleController.getString(R.string.PackName));
        if (packToEdit != null) {
            nameField.getEditText().setText(packToEdit.getName());
        }
        fields.addView(nameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 58, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));
        nameField.getEditText().setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT) {
                return false;
            }
            authorField.requestFocus();
            authorField.getEditText().setSelection(authorField.getEditText().length());
            return true;
        });

        authorField = new OutlineEditText(context);
        authorField.setBackground(null);
        authorField.getEditText().setInputType(49152);
        authorField.getEditText().setImeOptions(EditorInfo.IME_ACTION_NEXT);
        authorField.setHint(LocaleController.getString(R.string.AuthorNameOptional));
        if (packToEdit != null) {
            authorField.getEditText().setText(packToEdit.getAuthor());
        }
        fields.addView(authorField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 58, Gravity.LEFT | Gravity.TOP, 0, 68, 0, 0));
        authorField.getEditText().setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT) {
                return false;
            }
            versionField.requestFocus();
            versionField.getEditText().setSelection(versionField.getEditText().length());
            return true;
        });

        versionField = new OutlineEditText(context);
        versionField.setBackground(null);
        versionField.getEditText().setInputType(49152);
        versionField.getEditText().setImeOptions(EditorInfo.IME_ACTION_DONE);
        versionField.setHint(LocaleController.getString(R.string.Version));
        versionField.getEditText().setText(packToEdit != null ? packToEdit.getVersion() : "1.0");
        fields.addView(versionField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 58, Gravity.LEFT | Gravity.TOP, 0, 136, 0, 0));
        versionField.getEditText().setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            doneButton.callOnClick();
            return true;
        });

        doneButton = new ButtonWithCounterView(context, resourcesProvider);
        doneButton.setRound();
        doneButton.setText(LocaleController.getString(packToEdit == null ? R.string.Create : R.string.Save), false);
        doneButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        doneButton.setOnClickListener(v -> doOnDone());
        layout.addView(doneButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 16));
        return scrollView;
    }

    private void doOnDone() {
        if (nameField.getEditText().length() == 0) {
            VibratorUtils.vibrate();
            AndroidUtilities.shakeView(nameField);
            return;
        }
        String name = nameField.getEditText().getText().toString().trim();
        String author = authorField.getEditText().getText().toString().trim();
        String versionInput = versionField.getEditText().getText().toString().trim();
        String version = versionInput.isEmpty() ? "1.0" : versionInput;
        if (name.isEmpty()) {
            BulletinFactory.of(topBulletinContainer, resourcesProvider).createErrorBulletin(LocaleController.getString(R.string.NameCannotBeEmpty)).show();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String packAuthor = author.isEmpty() ? LocaleController.getString(R.string.PluginNoAuthor) : author;
            if (packToEdit != null) {
                if (!IconManager.INSTANCE.saveIconPackMetadata(new IconPack(packToEdit.getId(), name, packAuthor, version, packToEdit.getIcons(), packToEdit.getPreinstalledMap(), null))) {
                    showStorageError();
                } else {
                    AndroidUtilities.runOnUIThread(this::dismiss);
                }
                return;
            }
            String id = "custom." + UUID.randomUUID();
            IconPack pack = new IconPack(id, name, packAuthor, version, new HashMap<>(), null, null);
            if (!IconManager.INSTANCE.saveIconPackMetadata(pack)) {
                showStorageError();
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                IconManager.INSTANCE.setActiveCustomPack(id);
                dismiss();
                ExteraConfig.setEditingIconPackId(pack.getId());
                parentFragment.presentFragment(new IconPacksEditorActivity(pack) {
                    @Override
                    public void onBecomeFullyVisible() {
                        if (LaunchActivity.instance != null) {
                            IconPickerController.setActive(LaunchActivity.instance, true);
                        }
                        super.onBecomeFullyVisible();
                    }
                });
            });
        });
    }

    private void showStorageError() {
        AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(topBulletinContainer, resourcesProvider).createErrorBulletin(LocaleController.getString(R.string.IconPackErrorStorage)).show());
    }

    @Override
    public void onOpenAnimationEnd() {
        super.onOpenAnimationEnd();
        if (nameField == null || nameField.getEditText() == null) {
            return;
        }
        nameField.getEditText().requestFocus();
        nameField.getEditText().setSelection(nameField.getEditText().length());
        AndroidUtilities.showKeyboard(nameField.getEditText());
    }
}
