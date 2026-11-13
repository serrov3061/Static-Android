package org.telegram.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

/** Queue page: search field and the current playlist; tap plays a track or toggles the playing one. */
public class MusicQueueView extends LinearLayout {

    private final RecyclerListView listView;
    private final EditTextBoldCursor searchField;
    private final Adapter adapter = new Adapter();
    private final ArrayList<MessageObject> shown = new ArrayList<>();
    private final ArrayList<MessageObject> previous = new ArrayList<>();
    private String query = "";
    private int textColor = 0xFFFFFFFF;

    public MusicQueueView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        final FrameLayout searchLayout = new FrameLayout(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                paint.setColor(0x24FFFFFF);
                final float r = getHeight() / 2f;
                canvas.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, paint);
                super.dispatchDraw(canvas);
            }
        };
        searchLayout.setWillNotDraw(false);
        final ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.msg_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(0x99FFFFFF, PorterDuff.Mode.SRC_IN));
        searchIcon.setScaleType(ImageView.ScaleType.CENTER);
        searchLayout.addView(searchIcon, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 2, 0, 0, 0));

        searchField = new EditTextBoldCursor(context);
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        searchField.setTextColor(0xFFFFFFFF);
        searchField.setHintTextColor(0x88FFFFFF);
        searchField.setCursorColor(0xFFFFFFFF);
        searchField.setCursorWidth(1.5f);
        searchField.setHint(LocaleController.getString(R.string.SgMusicSearchQueue));
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.setPadding(0, 0, AndroidUtilities.dp(12), 0);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                update();
            }
        });
        searchLayout.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 42, 0, 0, 0));
        addView(searchLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 0, 0, 8));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter);
        listView.setItemAnimator(null);
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        listView.setSelectorDrawableColor(0x14FFFFFF);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= shown.size()) {
                return;
            }
            final MessageObject messageObject = shown.get(position);
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && playing.getId() == messageObject.getId() && playing.getDialogId() == messageObject.getDialogId()) {
                if (MediaController.getInstance().isMessagePaused()) {
                    MediaController.getInstance().playMessage(playing);
                } else {
                    MediaController.getInstance().pauseMessage(playing);
                }
            } else {
                FileLoader.getInstance(messageObject.currentAccount).loadFile(messageObject.getDocument(), messageObject, FileLoader.PRIORITY_NORMAL, 0);
                MediaController.getInstance().findMessageInPlaylistAndPlay(messageObject);
            }
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                final LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (layoutManager == null || !TextUtils.isEmpty(query)) {
                    return;
                }
                if (layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 4) {
                    MediaController.getInstance().loadMoreMusic();
                }
            }
        });
        addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
    }

    public void setTextColor(int color) {
        textColor = color;
        adapter.notifyDataSetChanged();
    }

    public boolean canScrollUp() {
        return listView.canScrollVertically(-1);
    }

    public void closeKeyboard() {
        if (searchField.isFocused()) {
            searchField.clearFocus();
            AndroidUtilities.hideKeyboard(searchField);
        }
    }

    public void scrollToPlaying() {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        for (int i = 0; i < shown.size(); ++i) {
            if (shown.get(i).getId() == playing.getId()) {
                ((LinearLayoutManager) listView.getLayoutManager()).scrollToPositionWithOffset(i, AndroidUtilities.dp(48));
                return;
            }
        }
    }

    private int indexOf(MessageObject messageObject) {
        for (int i = 0; i < shown.size(); ++i) {
            final MessageObject m = shown.get(i);
            if (m.getId() == messageObject.getId() && m.getDialogId() == messageObject.getDialogId()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameTracks(ArrayList<MessageObject> a, ArrayList<MessageObject> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); ++i) {
            final MessageObject x = a.get(i), y = b.get(i);
            if (x != y && (x.getId() != y.getId() || x.getDialogId() != y.getDialogId())) {
                return false;
            }
        }
        return true;
    }

    /** Rebuilds the list from the playlist and the query, keeping the first visible track in place. */
    public void update() {
        final LinearLayoutManager layoutManager = (LinearLayoutManager) listView.getLayoutManager();
        MessageObject anchor = null;
        int anchorOffset = 0;
        if (layoutManager != null && !shown.isEmpty()) {
            final int first = layoutManager.findFirstVisibleItemPosition();
            final View firstView = first >= 0 ? layoutManager.findViewByPosition(first) : null;
            if (first >= 0 && first < shown.size() && firstView != null) {
                anchor = shown.get(first);
                anchorOffset = firstView.getTop() - listView.getPaddingTop();
            }
        }
        previous.clear();
        previous.addAll(shown);
        shown.clear();
        final ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaylist();
        if (playlist != null) {
            for (int i = 0; i < playlist.size(); ++i) {
                final MessageObject messageObject = playlist.get(i);
                if (messageObject == null) {
                    continue;
                }
                if (!TextUtils.isEmpty(query)) {
                    final String title = messageObject.getMusicTitle() != null ? messageObject.getMusicTitle().toLowerCase(Locale.getDefault()) : "";
                    final String author = messageObject.getMusicAuthor() != null ? messageObject.getMusicAuthor().toLowerCase(Locale.getDefault()) : "";
                    if (!title.contains(query) && !author.contains(query)) {
                        continue;
                    }
                }
                shown.add(messageObject);
            }
        }
        if (sameTracks(previous, shown)) {
            adapter.notifyItemRangeChanged(0, shown.size());
            previous.clear();
            return;
        }
        previous.clear();
        adapter.notifyDataSetChanged();
        if (anchor != null && layoutManager != null) {
            final int index = indexOf(anchor);
            if (index >= 0) {
                layoutManager.scrollToPositionWithOffset(index, anchorOffset);
            }
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Cell cell = new Cell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((Cell) holder.itemView).set(shown.get(position));
        }
    }

    private class Cell extends FrameLayout {
        private final BackupImageView cover;
        private final MusicCoverColors.PlaceholderDrawable placeholder;
        private final ImageView state;
        private final TextView title;
        private final TextView author;

        Cell(Context context) {
            super(context);
            cover = new BackupImageView(context);
            cover.setRoundRadius(AndroidUtilities.dp(8));
            placeholder = new MusicCoverColors.PlaceholderDrawable();
            placeholder.setRadius(AndroidUtilities.dp(8));
            addView(cover, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

            state = new ImageView(context);
            state.setScaleType(ImageView.ScaleType.CENTER);
            state.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            state.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(8), 0x66000000));
            addView(state, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

            title = new TextView(context);
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 60, 9, 4, 0));

            author = new TextView(context);
            author.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
            author.setSingleLine(true);
            author.setEllipsize(TextUtils.TruncateAt.END);
            addView(author, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 60, 31, 4, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(58), MeasureSpec.EXACTLY));
        }

        void set(MessageObject messageObject) {
            title.setText(messageObject.getMusicTitle());
            author.setText(messageObject.getMusicAuthor());
            title.setTextColor(textColor);
            author.setTextColor(ColorUtils.setAlphaComponent(textColor, 153));
            final TLRPC.Document document = messageObject.getDocument();
            final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90) : null;
            if (thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive) {
                cover.setImage(ImageLocation.getForDocument(thumb, document), "44_44", placeholder, messageObject);
            } else {
                final String artworkUrl = messageObject.getArtworkUrl(true);
                if (TextUtils.isEmpty(artworkUrl)) {
                    cover.setImageDrawable(placeholder);
                } else {
                    cover.setImage(ImageLocation.getForPath(artworkUrl), "44_44", placeholder, messageObject);
                }
            }
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            final boolean isPlaying = playing != null && playing.getId() == messageObject.getId() && playing.getDialogId() == messageObject.getDialogId();
            if (isPlaying) {
                state.setImageResource(MediaController.getInstance().isMessagePaused() ? R.drawable.ic_action_play : R.drawable.ic_action_pause);
                state.setVisibility(VISIBLE);
            } else {
                state.setVisibility(GONE);
            }
            setAlpha(isPlaying || TextUtils.isEmpty(query) ? 1f : 0.95f);
        }
    }
}
