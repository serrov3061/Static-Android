package com.exteragram.messenger.speech.ui;

import android.widget.TextView;

import com.exteragram.messenger.speech.VoiceRecognitionController;
import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class RecognitionModelDialogs {

    private RecognitionModelDialogs() {
    }

    public static CharSequence getRecognitionLanguageOption(String code) {
        String title = TranslatorUtils.getLanguageTitleSystem(code);
        String own = TranslatorUtils.getLanguageDisplayName(code);
        return own == null ? title : title + " - " + own;
    }

    /** Asks the user to download the model (size shown on the button), then shows progress with a cancel button. */
    public static void showDownloadDialog(BaseFragment fragment, String language, VoiceRecognitionController.RecognitionModel model, Runnable onDownloaded) {
        if (fragment == null || fragment.getContext() == null || model == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getContext());
        builder.setTitle(LocaleController.getString(R.string.MissingLanguageModel));
        builder.setSubtitle(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.ModelDownloadInfo, TranslatorUtils.getLanguageTitleSystem(language))));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.formatString(R.string.ModelDownload, AndroidUtilities.formatFileSize(model.getSize())), (dialog, which) -> startDownload(fragment, language, onDownloaded));
        fragment.showDialog(builder.create());
    }

    private static void startDownload(BaseFragment fragment, String language, Runnable onDownloaded) {
        fragment.dismissCurrentDialog();
        if (fragment.getContext() == null) {
            return;
        }
        LoadingModelView loadingModelView = new LoadingModelView(fragment.getContext());
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getContext());
        builder.setView(loadingModelView);
        boolean[] done = {false};
        boolean[] cancelled = {false};
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> {
            cancelled[0] = true;
            VoiceRecognitionController.getInstance().cancelDownload(VoiceRecognitionController.PROVIDER_VOSK, language);
        });
        AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(false);
        alert.setCancelable(false);

        float[] progressValue = {0f};
        long[] start = {-1};
        Runnable updateProgress = () -> loadingModelView.setProgress(progressValue[0]);
        AndroidUtilities.runOnUIThread(() -> {
            if (done[0] || cancelled[0]) {
                return;
            }
            start[0] = System.currentTimeMillis();
            alert.show();
        }, 150);

        VoiceRecognitionController.getInstance().downloadModel(VoiceRecognitionController.PROVIDER_VOSK, language, new VoiceRecognitionController.DownloadModelCallback() {
            @Override
            public void onProgress(float progress) {
                progressValue[0] = progress;
                AndroidUtilities.cancelRunOnUIThread(updateProgress);
                AndroidUtilities.runOnUIThread(updateProgress);
            }

            @Override
            public void onCompleted() {
                AndroidUtilities.runOnUIThread(() -> {
                    done[0] = true;
                    loadingModelView.setProgress(1f);
                    if (start[0] > 0) {
                        AndroidUtilities.runOnUIThread(alert::dismiss, Math.max(0, 1000 - (System.currentTimeMillis() - start[0])));
                    } else {
                        alert.dismiss();
                    }
                    if (onDownloaded != null) {
                        onDownloaded.run();
                    }
                    BulletinFactory.of(fragment).createSuccessBulletin(LocaleController.getString(R.string.ModelDownloaded)).show();
                });
            }

            @Override
            public void onError(Exception e) {
                AndroidUtilities.runOnUIThread(() -> {
                    done[0] = true;
                    alert.dismiss();
                    if (!(e instanceof CancellationException) && !cancelled[0]) {
                        BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.ModelError)).show();
                    }
                });
            }
        });
    }

    public static void showDeleteFlow(BaseFragment fragment, List<VoiceRecognitionController.RecognitionModel> models, Utilities.Callback<VoiceRecognitionController.RecognitionModel> onDeleted) {
        if (fragment == null || fragment.getContext() == null || models == null || models.isEmpty()) {
            return;
        }
        if (models.size() == 1) {
            showDeleteConfirmDialog(fragment, models.get(0), onDeleted);
            return;
        }
        ArrayList<CharSequence> options = new ArrayList<>(models.size());
        for (VoiceRecognitionController.RecognitionModel model : models) {
            options.add(getRecognitionLanguageOption(model.getLanguage()));
        }
        PopupUtils.showDialogWithoutRadio(options, LocaleController.getString(R.string.DeleteRecognitionModel), fragment.getContext(), i -> {
            if (i >= 0 && i < models.size()) {
                showDeleteConfirmDialog(fragment, models.get(i), onDeleted);
            }
        });
    }

    private static void showDeleteConfirmDialog(BaseFragment fragment, VoiceRecognitionController.RecognitionModel model, Utilities.Callback<VoiceRecognitionController.RecognitionModel> onDeleted) {
        if (fragment.getContext() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getContext());
        builder.setTitle(LocaleController.getString(R.string.DeleteRecognitionModel));
        builder.setSubtitle(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeleteRecognitionModelInfo, getRecognitionLanguageOption(model.getLanguage()))));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) ->
            VoiceRecognitionController.getInstance().deleteModel(VoiceRecognitionController.PROVIDER_VOSK, model.getLanguage(), new VoiceRecognitionController.DeleteModelCallback() {
                @Override
                public void onCompleted() {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (onDeleted != null) {
                            onDeleted.run(model);
                        }
                        BulletinFactory.of(fragment).createSuccessBulletin(LocaleController.getString(R.string.RecognitionModelDeleted)).show();
                    });
                }

                @Override
                public void onError(Exception e) {
                    AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.RecognitionModelDeleteError)).show());
                }
            }));
        AlertDialog alert = builder.create();
        fragment.showDialog(alert);
        TextView button = (TextView) alert.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }
}
