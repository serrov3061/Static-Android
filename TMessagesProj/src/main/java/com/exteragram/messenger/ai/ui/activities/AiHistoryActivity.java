package com.exteragram.messenger.ai.ui.activities;

import android.view.Gravity;
import android.view.View;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.ui.MarkdownPreview;
import com.exteragram.messenger.ai.ui.components.HistoryMessageCell;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/** Local AI conversation history (port of exteraGram's AiHistoryActivity). */
public final class AiHistoryActivity extends BasePreferencesActivity {
    private final ArrayList<Entry> entries = new ArrayList<>();
    private int lastEntryId = 100;

    public enum HistoryItem {
        SAVE_HISTORY,
        CLEAR_HISTORY;

        public int getId() {
            return ordinal() + 1;
        }
    }

    public static final class Entry {
        private final int id;
        private final Message message;
        private CharSequence content;
        private boolean expanded;

        public Entry(int id, Message message) {
            this.id = id;
            this.message = message;
            this.content = message.content() == null ? "" : message.content();
        }

        public int getId() {
            return id;
        }

        public Message getMessage() {
            return message;
        }

        public CharSequence getContent() {
            return content;
        }

        public void setContent(CharSequence content) {
            this.content = content;
        }

        public boolean getExpanded() {
            return expanded;
        }

        public void setExpanded(boolean expanded) {
            this.expanded = expanded;
        }
    }

    @Override
    public boolean onFragmentCreate() {
        for (Message message : AiConfig.getConversationHistory()) {
            entries.add(new Entry(lastEntryId++, message));
        }
        formatContents();
        return super.onFragmentCreate();
    }

    private void formatContents() {
        if (entries.isEmpty()) {
            return;
        }
        final ArrayList<Entry> snapshot = new ArrayList<>(entries);
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<CharSequence> formatted = new ArrayList<>(snapshot.size());
            for (Entry entry : snapshot) {
                formatted.add(MarkdownPreview.format(entry.getMessage().content()));
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (getContext() == null) {
                    return;
                }
                for (int i = 0; i < snapshot.size(); i++) {
                    snapshot.get(i).setContent(formatted.get(i));
                }
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.MessageHistory);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asRippleCheck(HistoryItem.SAVE_HISTORY.getId(), LocaleController.getString(R.string.MessageHistory)).setChecked(AiConfig.getSaveHistory()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HistoryInfo)));
        if (entries.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(LocaleController.formatPluralString("messages", entries.size())));
        for (Entry entry : entries) {
            items.add(HistoryMessageCell.Factory.asHistoryCell(entry.getId(), entry.getMessage(), entry.getContent(), entry.getExpanded()));
        }
        items.add(UItem.asShadow(null));
        items.add(uButton$TMessagesProj(HistoryItem.CLEAR_HISTORY, LocaleController.getString(R.string.ClearHistory), R.drawable.msg_delete, null, null, null, false, null, true, false, null));
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id <= 0 || id > HistoryItem.values().length) {
            Entry entry = findEntry(id);
            if (entry != null) {
                entry.setExpanded(!entry.getExpanded());
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            }
            return;
        }
        if (HistoryItem.values()[id - 1] == HistoryItem.SAVE_HISTORY) {
            toggleBooleanSettingAndRefresh$TMessagesProj(item, AiConfig::setSaveHistory);
        } else {
            AiController.clearHistory(this, getResourceProvider(), true, () -> {
                entries.clear();
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        }
    }

    @Override
    public boolean onLongClick(final UItem item, View view, int position, float x, float y) {
        final Message message = item.object instanceof Message ? (Message) item.object : null;
        if (message == null) {
            return false;
        }
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
            if (AndroidUtilities.addToClipboard(message.content())) {
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            }
        });
        options.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> deleteTurn(item.id));
        options.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        options.setScrimViewBackground(listView.getClipBackground(view));
        options.show();
        return true;
    }

    private Entry findEntry(int id) {
        for (Entry entry : entries) {
            if (entry.getId() == id) {
                return entry;
            }
        }
        return null;
    }

    /** Deletes the whole question/answer pair the entry belongs to. */
    private void deleteTurn(int id) {
        int index = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getId() == id) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            return;
        }
        int from = "assistant".equals(entries.get(index).getMessage().role()) ? index - 1 : index;
        int to = from + 1;
        if (from < 0 || to >= entries.size() || "assistant".equals(entries.get(from).getMessage().role()) || !"assistant".equals(entries.get(to).getMessage().role())) {
            from = index;
            to = index;
        }
        for (int i = to; i >= from; i--) {
            entries.remove(i);
        }
        ArrayList<Message> history = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            history.add(entry.getMessage());
        }
        AiConfig.saveConversationHistory(history);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
