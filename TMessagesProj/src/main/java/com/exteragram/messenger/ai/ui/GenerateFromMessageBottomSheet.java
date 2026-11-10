package com.exteragram.messenger.ai.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.network.backend.OnDeviceAvailability;
import com.exteragram.messenger.ai.ui.components.CheckBoxRow;
import com.exteragram.messenger.utils.chats.ChatUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.Objects;

/** "Generate" sheet: editable prompt (prefilled with the message text) + use history / attach photo switches. */
public class GenerateFromMessageBottomSheet extends BottomSheet {
    private boolean includeImage;
    BaseFragment parentFragment;
    private final EditTextCell promptCell;
    private boolean useHistory;

    public GenerateFromMessageBottomSheet(MessageObject messageObject, MessageObject.GroupedMessages group, BaseFragment fragment, Context context, Utilities.Callback<GenerationData> callback) {
        this(Objects.toString(ChatUtils.getInstance().getMessageText(messageObject, group), ""), ChatUtils.getInstance().getPathToMessage(messageObject), fragment, context, callback);
    }

    public GenerateFromMessageBottomSheet(BaseFragment fragment, Context context, Utilities.Callback<GenerationData> callback, boolean allowHistory) {
        this("", "", fragment, context, callback, allowHistory);
    }

    public GenerateFromMessageBottomSheet(String prompt, String imagePath, BaseFragment fragment, Context context, Utilities.Callback<GenerationData> callback) {
        this(prompt, imagePath, fragment, context, callback, AiConfig.getSaveHistory());
    }

    @SuppressLint("ClickableViewAccessibility")
    public GenerateFromMessageBottomSheet(String prompt, final String imagePath, BaseFragment fragment, Context context, final Utilities.Callback<GenerationData> callback, boolean allowHistory) {
        super(context, true, fragment.getResourceProvider());
        setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundGray));
        setApplyTopPadding(false);
        smoothKeyboardAnimationEnabled = true;
        parentFragment = fragment;
        useHistory = AiConfig.getSaveHistory();
        includeImage = AiController.canSendImage(imagePath);
        OnDeviceAvailability.warmup();

        ScrollView scrollView = new ScrollView(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setClipChildren(false);
        layout.setClipToPadding(false);
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        FrameLayout header = new FrameLayout(context);
        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.Generate));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.LEFT | Gravity.TOP, 22, 6, 56, 0));
        ImageView close = new ImageView(context);
        close.setImageResource(R.drawable.ic_close_white);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        ScaleStateListAnimator.apply(close);
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.TOP, 0, 10, 12, 0));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 70));

        promptCell = new EditTextCell(context, LocaleController.getString(R.string.RolePrompt), true, false, -1, resourcesProvider);
        promptCell.editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        promptCell.editText.setMaxLines(8);
        promptCell.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        if (!TextUtils.isEmpty(prompt)) {
            promptCell.setText(prompt);
        }
        promptCell.editText.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        layout.addView(promptCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));

        boolean showHistory = AiConfig.getSaveHistory() && allowHistory;
        boolean showImage = AiController.canSendImage(imagePath);
        LinearLayout options = null;
        if (showHistory || showImage) {
            options = new LinearLayout(context);
            options.setOrientation(LinearLayout.HORIZONTAL);
            options.setGravity(Gravity.CENTER_HORIZONTAL);
            if (showHistory) {
                CheckBoxRow historyRow = new CheckBoxRow(context, LocaleController.getString(R.string.GenerateWithHistory), useHistory, resourcesProvider);
                historyRow.setOnCheckedChange(checked -> useHistory = checked);
                options.addView(historyRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
            }
            if (showImage) {
                CheckBoxRow imageRow = new CheckBoxRow(context, LocaleController.getString(R.string.GenerateWithPhoto), includeImage, resourcesProvider);
                imageRow.setOnCheckedChange(checked -> includeImage = checked);
                BackupImageView preview = new BackupImageView(context);
                preview.getImageReceiver().setImage(imagePath, "24_24", null, null, 0L);
                preview.setRoundRadius(AndroidUtilities.dp(4));
                imageRow.addView(preview, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 9, 0, 0, 0));
                options.addView(imageRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
            }
        }

        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setText(LocaleController.getString(R.string.Proceed), false);
        button.setOnClickListener(v -> {
            String text = promptCell.editText.getText().toString();
            if (TextUtils.isEmpty(text)) {
                if (!includeImage) {
                    AndroidUtilities.shakeViewSpring(promptCell);
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    return;
                }
                text = LocaleController.getString(R.string.AttachPhoto);
            }
            dismiss();
            callback.run(new GenerationData(text, useHistory, includeImage ? imagePath : null));
        });
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 12, 12, 12, options != null ? 16 : 12));
        if (options != null) {
            layout.addView(options, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
        setCustomView(scrollView);
    }

    @Override
    public void show() {
        super.show();
        AndroidUtilities.runOnUIThread(() -> {
            promptCell.editText.setSelection(promptCell.editText.length());
            AndroidUtilities.showKeyboard(promptCell.editText);
        }, 200);
    }

    public static final class GenerationData {
        private final String prompt;
        private final boolean useHistory;
        private final String imagePath;

        public GenerationData(String prompt, boolean useHistory, String imagePath) {
            this.prompt = prompt;
            this.useHistory = useHistory;
            this.imagePath = imagePath;
        }

        public String prompt() {
            return prompt;
        }

        public boolean useHistory() {
            return useHistory;
        }

        public String imagePath() {
            return imagePath;
        }
    }
}
