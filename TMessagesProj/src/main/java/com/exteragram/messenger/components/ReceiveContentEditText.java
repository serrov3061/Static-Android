package com.exteragram.messenger.components;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.text.Editable;
import android.text.Selection;
import android.util.AttributeSet;
import android.view.DragEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ContentInfoCompat;
import androidx.core.view.OnReceiveContentViewBehavior;
import androidx.core.view.ViewCompat;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.widget.TextViewOnReceiveContentListener;

/**
 * exteraGram: an EditText that routes keyboard content, paste and drag & drop through
 * {@link ViewCompat#setOnReceiveContentListener} on every API level (AppCompatEditText-like
 * backport for a plain EditText). Without a listener it behaves like a regular EditText.
 * StaticGram: inline math stays in EditTextBoldCursor.
 */
@SuppressLint({"RestrictedApi", "AppCompatCustomView"})
public abstract class ReceiveContentEditText extends EditText implements OnReceiveContentViewBehavior {

    private final TextViewOnReceiveContentListener defaultOnReceiveContentListener = new TextViewOnReceiveContentListener();

    public ReceiveContentEditText(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public Editable getText() {
        if (Build.VERSION.SDK_INT >= 28) {
            return super.getText();
        }
        return super.getEditableText();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        InputConnection connection = super.onCreateInputConnection(editorInfo);
        if (connection != null && Build.VERSION.SDK_INT <= 30) {
            String[] mimeTypes = ViewCompat.getOnReceiveContentMimeTypes(this);
            if (mimeTypes != null) {
                EditorInfoCompat.setContentMimeTypes(editorInfo, mimeTypes);
                connection = InputConnectionCompat.createWrapper(this, connection, editorInfo);
            }
        }
        return connection;
    }

    @Override
    public boolean onDragEvent(DragEvent event) {
        if (handleDragEventViaReceiveContent(event)) {
            return true;
        }
        return super.onDragEvent(event);
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (handleMenuActionViaReceiveContent(id)) {
            return true;
        }
        return super.onTextContextMenuItem(id);
    }

    @Nullable
    @Override
    public ContentInfoCompat onReceiveContent(@NonNull ContentInfoCompat payload) {
        return defaultOnReceiveContentListener.onReceiveContent(this, payload);
    }

    private boolean handleMenuActionViaReceiveContent(int id) {
        if (ViewCompat.getOnReceiveContentMimeTypes(this) == null || id != android.R.id.paste && id != android.R.id.pasteAsPlainText) {
            return false;
        }
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            ContentInfoCompat payload = new ContentInfoCompat.Builder(clip, ContentInfoCompat.SOURCE_CLIPBOARD)
                    .setFlags(id == android.R.id.paste ? 0 : ContentInfoCompat.FLAG_CONVERT_TO_PLAIN_TEXT)
                    .build();
            ViewCompat.performReceiveContent(this, payload);
        }
        return true;
    }

    private boolean handleDragEventViaReceiveContent(DragEvent event) {
        if (Build.VERSION.SDK_INT >= 31 || event.getLocalState() != null || ViewCompat.getOnReceiveContentMimeTypes(this) == null) {
            return false;
        }
        Activity activity = findActivity();
        if (activity == null || event.getAction() == DragEvent.ACTION_DRAG_STARTED || event.getAction() != DragEvent.ACTION_DROP) {
            return false;
        }
        return OnDropApi24Impl.onDropForTextView(event, this, activity);
    }

    private Activity findActivity() {
        for (Context context = getContext(); context instanceof ContextWrapper; context = ((ContextWrapper) context).getBaseContext()) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
        }
        return null;
    }

    public static final class OnDropApi24Impl {
        public static boolean onDropForTextView(DragEvent event, ReceiveContentEditText view, Activity activity) {
            activity.requestDragAndDropPermissions(event);
            int offset = view.getOffsetForPosition(event.getX(), event.getY());
            view.beginBatchEdit();
            try {
                Selection.setSelection(view.getText(), offset);
                ViewCompat.performReceiveContent(view, new ContentInfoCompat.Builder(event.getClipData(), ContentInfoCompat.SOURCE_DRAG_AND_DROP).build());
                return true;
            } finally {
                view.endBatchEdit();
            }
        }
    }
}
