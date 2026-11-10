package com.exteragram.messenger.utils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ShapeDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;
import java.util.Locale;
import java.util.Objects;

// exteraGram: external subtitles for PhotoViewer video playback (local files only)
public abstract class VideoSubtitlesHelper {

    private static final String PREFS_NAME = "video_external_subtitles";
    private static final String MIME_SRT = "application/x-subrip";
    private static final String MIME_VTT = "text/vtt";

    public enum LoadError {
        NONE,
        UNSUPPORTED_FORMAT,
        LOAD_FAILED
    }

    public static final class SubtitleState {
        private final String path;
        private final String mimeType;
        private final String label;

        public SubtitleState(String path, String mimeType, String label) {
            this.path = path;
            this.mimeType = mimeType;
            this.label = label;
        }

        public String path() {
            return path;
        }

        public String mimeType() {
            return mimeType;
        }

        public String getDisplayName() {
            if (!TextUtils.isEmpty(label)) {
                return label;
            }
            return new File(path).getName();
        }

        public boolean isValid() {
            if (TextUtils.isEmpty(path) || TextUtils.isEmpty(mimeType)) {
                return false;
            }
            File file = new File(path);
            return file.exists() && file.length() > 0;
        }

        public VideoPlayer.ExternalSubtitle toExternalSubtitle() {
            return new VideoPlayer.ExternalSubtitle(Uri.fromFile(new File(path)), mimeType, getDisplayName());
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SubtitleState)) {
                return false;
            }
            SubtitleState other = (SubtitleState) o;
            return Objects.equals(path, other.path) && Objects.equals(mimeType, other.mimeType) && Objects.equals(label, other.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, mimeType, label);
        }

        @Override
        public String toString() {
            return "SubtitleState[path=" + path + ", mimeType=" + mimeType + ", label=" + label + "]";
        }
    }

    public static final class SubtitleLoadResult {
        private final SubtitleState subtitleState;
        private final LoadError error;

        public SubtitleLoadResult(SubtitleState subtitleState, LoadError error) {
            this.subtitleState = subtitleState;
            this.error = error;
        }

        public SubtitleState subtitleState() {
            return subtitleState;
        }

        public LoadError error() {
            return error;
        }

        public boolean isSuccess() {
            return subtitleState != null;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SubtitleLoadResult)) {
                return false;
            }
            SubtitleLoadResult other = (SubtitleLoadResult) o;
            return Objects.equals(subtitleState, other.subtitleState) && Objects.equals(error, other.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(subtitleState, error);
        }

        @Override
        public String toString() {
            return "SubtitleLoadResult[subtitleState=" + subtitleState + ", error=" + error + "]";
        }
    }

    public static boolean areSame(SubtitleState a, SubtitleState b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return TextUtils.equals(a.path, b.path) && TextUtils.equals(a.mimeType, b.mimeType) && TextUtils.equals(a.label, b.label);
    }

    public static String buildVideoKey(MessageObject messageObject, String path, Uri uri, TL_iv.PageBlock pageBlock) {
        if (messageObject != null) {
            TLRPC.Document document = messageObject.getDocument();
            if (document != null) {
                return "doc_" + document.id;
            }
        }
        if (!TextUtils.isEmpty(path)) {
            return makePathKey(path);
        }
        if (uri != null) {
            return makeUriKey(uri);
        }
        if (pageBlock instanceof TL_iv.pageBlockVideo) {
            return "page_video_" + ((TL_iv.pageBlockVideo) pageBlock).video_id;
        }
        return null;
    }

    public static String makePathKey(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        return "uri_" + Utilities.MD5(path);
    }

    public static String makeUriKey(Uri uri) {
        if (uri == null) {
            return null;
        }
        return makePathKey(uri.toString());
    }

    public static void save(String key, SubtitleState state) {
        if (TextUtils.isEmpty(key) || state == null || !state.isValid()) {
            return;
        }
        getPreferences().edit()
            .putString("path_" + key, state.path())
            .putString("mime_" + key, state.mimeType())
            .putString("label_" + key, state.getDisplayName())
            .apply();
    }

    public static SubtitleState restore(String key) {
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        SharedPreferences prefs = getPreferences();
        String path = prefs.getString("path_" + key, null);
        String mimeType = prefs.getString("mime_" + key, null);
        String label = prefs.getString("label_" + key, null);
        if (TextUtils.isEmpty(path) || TextUtils.isEmpty(mimeType)) {
            return null;
        }
        SubtitleState state = new SubtitleState(path, mimeType, label);
        if (!state.isValid()) {
            clear(key);
            return null;
        }
        return state;
    }

    public static void clear(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        getPreferences().edit()
            .remove("path_" + key)
            .remove("mime_" + key)
            .remove("label_" + key)
            .apply();
    }

    public static Intent createPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{MIME_SRT, MIME_VTT});
        return intent;
    }

    public static SubtitleLoadResult loadFromPickerIntent(Intent data) {
        return loadFromUri(data != null ? data.getData() : null);
    }

    public static SubtitleLoadResult loadFromUri(Uri uri) {
        if (uri == null) {
            return new SubtitleLoadResult(null, LoadError.LOAD_FAILED);
        }
        String mimeType = resolveMimeType(uri);
        if (TextUtils.isEmpty(mimeType)) {
            return new SubtitleLoadResult(null, LoadError.UNSUPPORTED_FORMAT);
        }
        String ext = MIME_VTT.equals(mimeType) ? "vtt" : "srt";

        String path = null;
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                path = uri.getPath();
            } else {
                path = AndroidUtilities.getPath(uri);
                if (TextUtils.isEmpty(path) || path.startsWith("content://")) {
                    try {
                        path = MediaController.copyFileToCache(uri, ext);
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Exception ignore) {
            path = null;
        }

        if (TextUtils.isEmpty(path)) {
            return new SubtitleLoadResult(null, LoadError.LOAD_FAILED);
        }
        File file = new File(path);
        if (file.exists() && file.length() > 0) {
            return new SubtitleLoadResult(new SubtitleState(path, mimeType, file.getName()), LoadError.NONE);
        }
        return new SubtitleLoadResult(null, LoadError.LOAD_FAILED);
    }

    public static TextView createSubtitlesView(Context context) {
        TextView textView = new TextView(context);
        textView.setGravity(android.view.Gravity.CENTER);
        textView.setTextColor(0xFFFFFFFF);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, AndroidUtilities.isTablet() ? 20 : 16);
        textView.setMaxLines(3);
        textView.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(8), AndroidUtilities.dp(18), AndroidUtilities.dp(8));
        ShapeDrawable background = Theme.createRoundRectDrawable(AndroidUtilities.dp(8), 0xCC000000);
        textView.setBackground(background);
        textView.setShadowLayer(AndroidUtilities.dp(2), 0, AndroidUtilities.dp(1), 0x99000000);
        textView.setVisibility(android.view.View.GONE);
        return textView;
    }

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    private static String getSubtitleExtension(Uri uri) {
        String name = AndroidUtilities.getPath(uri);
        if (TextUtils.isEmpty(name)) {
            name = uri.getPath();
        }
        if (TextUtils.isEmpty(name)) {
            name = uri.toString();
        }
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".srt")) {
            return "srt";
        }
        if (lower.endsWith(".vtt")) {
            return "vtt";
        }
        return null;
    }

    private static String resolveMimeType(Uri uri) {
        String type = null;
        try {
            type = ApplicationLoader.applicationContext.getContentResolver().getType(uri);
        } catch (Exception ignore) {
        }
        if (!TextUtils.isEmpty(type)) {
            String lower = type.toLowerCase(Locale.US);
            if (MIME_VTT.equals(lower) || lower.contains("vtt")) {
                return MIME_VTT;
            }
            if (MIME_SRT.equals(lower) || lower.contains("subrip") || lower.contains("srt")) {
                return MIME_SRT;
            }
        }
        String ext = getSubtitleExtension(uri);
        if ("vtt".equals(ext)) {
            return MIME_VTT;
        }
        if ("srt".equals(ext)) {
            return MIME_SRT;
        }
        return null;
    }
}
