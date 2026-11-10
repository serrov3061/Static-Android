package com.exteragram.messenger.plugins.utils;

import android.content.Context;
import com.exteragram.messenger.plugins.PluginsConstants;
import java.util.HashMap;
import java.util.Map;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.BaseFragment;

public final class MenuContextBuilder {

    public static final Companion Companion = new Companion();
    private final HashMap<String, Object> contextData;

    public static final MenuContextBuilder create() {
        return Companion.create();
    }

    public static final MenuContextBuilder from(BaseFragment baseFragment) {
        return Companion.from(baseFragment);
    }

    private MenuContextBuilder() {
        this.contextData = new HashMap<>();
    }

    public final MenuContextBuilder withAccount(int account) {
        this.contextData.put("account", Integer.valueOf(account));
        return this;
    }

    public final MenuContextBuilder withContext(Context context) {
        if (context != null) {
            this.contextData.put("context", context);
        }
        return this;
    }

    public final MenuContextBuilder withEncryptedChat(TLRPC.EncryptedChat encryptedChat) {
        if (encryptedChat != null) {
            this.contextData.put("encryptedChat", encryptedChat);
        }
        return this;
    }

    public final MenuContextBuilder withChat(TLRPC.Chat chat) {
        if (chat != null) {
            this.contextData.put("chat", chat);
            this.contextData.put("chatId", Long.valueOf(chat.id));
        }
        return this;
    }

    public final MenuContextBuilder withChatFull(TLRPC.ChatFull chatFull) {
        if (chatFull != null) {
            this.contextData.put("chatFull", chatFull);
        }
        return this;
    }

    public final MenuContextBuilder withUser(TLRPC.User user) {
        if (user != null) {
            this.contextData.put("user", user);
            this.contextData.put("userId", Long.valueOf(user.id));
        }
        return this;
    }

    public final MenuContextBuilder withUserFull(TLRPC.UserFull userFull) {
        if (userFull != null) {
            this.contextData.put("userFull", userFull);
        }
        return this;
    }

    public final MenuContextBuilder withBotInfo(TL_bots.BotInfo botInfo) {
        if (botInfo != null) {
            this.contextData.put("botInfo", botInfo);
        }
        return this;
    }

    public final MenuContextBuilder withDialogId(long dialogId) {
        this.contextData.put("dialog_id", Long.valueOf(dialogId));
        return this;
    }

    public final MenuContextBuilder withMessage(MessageObject message) {
        if (message != null) {
            this.contextData.put("message", message);
        }
        return this;
    }

    public final MenuContextBuilder withGroupedMessage(MessageObject.GroupedMessages groupedMessages) {
        if (groupedMessages != null) {
            this.contextData.put("groupedMessages", groupedMessages);
        }
        return this;
    }

    public final MenuContextBuilder withCustom(String key, Object value) {
        if (key != null && value != null) {
            this.contextData.put(key, value);
        }
        return this;
    }

    public final Map<String, Object> build() {
        return this.contextData;
    }

    public static final class Companion {
        private Companion() {
        }

        public final MenuContextBuilder create() {
            return new MenuContextBuilder();
        }

        public final MenuContextBuilder from(BaseFragment fragment) {
            if (fragment == null) {
                return create();
            }
            return create().withCustom("fragment", fragment).withAccount(fragment.getCurrentAccount()).withContext(fragment.getParentActivity());
        }
    }
}
