package com.exteragram.messenger.utils.chats;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;

/**
 * exteraGram: messages.search can't combine "from" with a type filter, so when both a sender
 * and a type filter are selected one of them is applied on the server and the other one is
 * applied locally on the returned pages. Which one is chosen by comparing the counts of both.
 */
public final class ChatSearchLocalFilter {

    private final int currentAccount;
    private boolean active;
    private boolean decided;
    private String key;
    private boolean localSender;
    private volatile int scanRequestId;
    private long senderId;
    private volatile int token;
    private TLRPC.MessagesFilter typeFilter;
    private final int[] rawOffsetId = new int[2];
    private final boolean[] rawEndReached = new boolean[2];
    private final int[] discarded = new int[2];

    private static final class Scan {
        final int token;
        final int sourceIndex;
        final boolean localSender;
        final long senderId;
        final TLRPC.MessagesFilter typeFilter;
        final RequestDelegate delegate;
        final int flags;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        int discarded;
        boolean endReached;
        int offsetId;
        int requests;

        Scan(int token, int sourceIndex, boolean localSender, long senderId, TLRPC.MessagesFilter typeFilter, RequestDelegate delegate, int flags) {
            this.token = token;
            this.sourceIndex = sourceIndex;
            this.localSender = localSender;
            this.senderId = senderId;
            this.typeFilter = typeFilter;
            this.delegate = delegate;
            this.flags = flags;
        }
    }

    public ChatSearchLocalFilter(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    /**
     * @return true if the search must wait: {@code onReady} will be called once counts are known.
     */
    public boolean prepare(String query, long dialogId, long replyMessageId, TLRPC.User user, TLRPC.Chat chat, ReactionsLayoutInBubble.VisibleReaction reaction, TLRPC.MessagesFilter filter, Runnable onReady) {
        rawOffsetId[0] = rawOffsetId[1] = 0;
        rawEndReached[0] = rawEndReached[1] = false;
        discarded[0] = discarded[1] = 0;
        long sender = user != null ? user.id : chat != null ? -chat.id : 0;
        if (sender == 0 || filter == null) {
            reset();
            return false;
        }
        String newKey = dialogId + "_" + replyMessageId + "_" + sender + "_" + filter.getClass().getName() + "_" + query + "_" + (reaction != null ? reaction.emojicon + reaction.documentId : "");
        if (decided && newKey.equals(key)) {
            active = true;
            return false;
        }
        TLRPC.InputPeer peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        TLRPC.InputPeer fromPeer = user != null ? MessagesController.getInputPeer(user) : MessagesController.getInputPeer(chat);
        if (peer == null || fromPeer == null) {
            reset();
            return false;
        }
        key = newKey;
        decided = false;
        active = false;
        senderId = sender;
        typeFilter = filter;
        final int currentToken = ++token;

        TLRPC.TL_messages_search typeReq = createCountRequest(peer, query, dialogId, replyMessageId, reaction);
        typeReq.filter = filter;
        TLRPC.TL_messages_search senderReq = createCountRequest(peer, query, dialogId, replyMessageId, reaction);
        senderReq.from_id = fromPeer;
        senderReq.flags |= 1;
        senderReq.filter = new TLRPC.TL_inputMessagesFilterEmpty();

        final int[] counts = {-1, -1};
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
        connectionsManager.sendRequest(typeReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> onCount(currentToken, 0, response, counts, onReady)));
        connectionsManager.sendRequest(senderReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> onCount(currentToken, 1, response, counts, onReady)));
        return true;
    }

    public void applyTo(TLRPC.TL_messages_search req, boolean main) {
        if (!active) {
            return;
        }
        if (localSender) {
            req.from_id = null;
            req.flags &= ~1;
        } else {
            req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        }
        if (main) {
            req.limit = 100;
        }
    }

    public int sendRequest(int sourceIndex, TLRPC.TL_messages_search req, RequestDelegate delegate, int flags) {
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
        if (!active) {
            return connectionsManager.sendRequest(req, delegate, flags);
        }
        Scan scan = new Scan(token, sourceIndex, localSender, senderId, typeFilter, delegate, flags);
        scan.offsetId = req.offset_id;
        return sendPage(scan, req);
    }

    public boolean isActive() {
        return active;
    }

    public boolean isEndReached(int sourceIndex, boolean fallback) {
        return active ? rawEndReached[sourceIndex] : fallback;
    }

    public int getOffsetId(int sourceIndex, int fallback) {
        return active ? rawOffsetId[sourceIndex] : fallback;
    }

    public void cancel() {
        token++;
        if (scanRequestId != 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(scanRequestId, true);
            scanRequestId = 0;
        }
    }

    public void reset() {
        cancel();
        key = null;
        decided = false;
        active = false;
    }

    private TLRPC.TL_messages_search createCountRequest(TLRPC.InputPeer peer, String query, long dialogId, long replyMessageId, ReactionsLayoutInBubble.VisibleReaction reaction) {
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = peer;
        req.q = query;
        req.limit = 1;
        if (replyMessageId != 0) {
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId() || messagesController.isMonoForum(dialogId)) {
                req.saved_peer_id = messagesController.getInputPeer(replyMessageId);
                req.flags |= 4;
            } else {
                req.top_msg_id = (int) replyMessageId;
                req.flags |= 2;
            }
        }
        if (reaction != null) {
            req.saved_reaction.add(reaction.toTLReaction());
            req.flags |= 8;
        }
        return req;
    }

    private void onCount(int currentToken, int index, TLObject response, int[] counts, Runnable onReady) {
        if (currentToken != token) {
            return;
        }
        counts[index] = response instanceof TLRPC.messages_Messages ? getCount((TLRPC.messages_Messages) response) : Integer.MAX_VALUE;
        if (counts[0] < 0 || counts[1] < 0) {
            return;
        }
        // scan the smaller server-side set and filter the other criterion locally
        localSender = counts[0] <= counts[1];
        decided = true;
        active = true;
        onReady.run();
    }

    private int sendPage(Scan scan, TLRPC.TL_messages_search req) {
        scan.requests++;
        int id = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> onPage(scan, req, response, error), scan.flags);
        scanRequestId = id;
        return id;
    }

    private void onPage(Scan scan, TLRPC.TL_messages_search req, TLObject response, TLRPC.TL_error error) {
        if (scan.token != token) {
            return;
        }
        if (!(response instanceof TLRPC.messages_Messages)) {
            int wait = error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_") ? Utilities.parseInt(error.text) : 0;
            if (wait > 0 && wait <= 60) {
                Utilities.stageQueue.postRunnable(() -> {
                    if (scan.token == token) {
                        sendPage(scan, req);
                    }
                }, wait * 1000L);
            } else {
                scanRequestId = 0;
                scan.delegate.run(response, error);
            }
            return;
        }
        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
        int n = Math.min(res.messages.size(), req.limit - 1);
        int i = 0;
        while (i < n && scan.messages.size() < req.limit - 1) {
            TLRPC.Message message = res.messages.get(i);
            i++;
            boolean matches = scan.localSender ? MessageObject.getPeerId(message.from_id) == scan.senderId : matchesType(message, scan.typeFilter);
            if (matches) {
                scan.messages.add(message);
            } else {
                scan.discarded++;
            }
        }
        scan.users.addAll(res.users);
        scan.chats.addAll(res.chats);
        if (i > 0) {
            scan.offsetId = res.messages.get(i - 1).id;
        }
        scan.endReached = i == n && res.messages.size() < req.limit;
        int size = scan.messages.size();
        if (!scan.endReached && (size == 0 || size < 10 && scan.requests < 5)) {
            TLRPC.TL_messages_search next = copy(req);
            next.offset_id = scan.offsetId;
            Utilities.stageQueue.postRunnable(() -> {
                if (scan.token == token) {
                    sendPage(scan, next);
                }
            }, 300);
            return;
        }
        scanRequestId = 0;
        rawOffsetId[scan.sourceIndex] = scan.offsetId;
        rawEndReached[scan.sourceIndex] = scan.endReached;
        discarded[scan.sourceIndex] += scan.discarded;
        TLRPC.TL_messages_messagesSlice slice = new TLRPC.TL_messages_messagesSlice();
        slice.messages = scan.messages;
        slice.users = scan.users;
        slice.chats = scan.chats;
        slice.count = Math.max(getCount(res) - discarded[scan.sourceIndex], size);
        scan.delegate.run(slice, null);
    }

    private static int getCount(TLRPC.messages_Messages res) {
        return res instanceof TLRPC.TL_messages_messagesSlice || res instanceof TLRPC.TL_messages_channelMessages ? res.count : res.messages.size();
    }

    private static TLRPC.TL_messages_search copy(TLRPC.TL_messages_search req) {
        TLRPC.TL_messages_search c = new TLRPC.TL_messages_search();
        c.flags = req.flags;
        c.peer = req.peer;
        c.q = req.q;
        c.from_id = req.from_id;
        c.saved_peer_id = req.saved_peer_id;
        c.saved_reaction = req.saved_reaction;
        c.top_msg_id = req.top_msg_id;
        c.filter = req.filter;
        c.min_date = req.min_date;
        c.max_date = req.max_date;
        c.offset_id = req.offset_id;
        c.add_offset = req.add_offset;
        c.limit = req.limit;
        c.max_id = req.max_id;
        c.min_id = req.min_id;
        c.hash = req.hash;
        return c;
    }

    private static boolean matchesType(TLRPC.Message message, TLRPC.MessagesFilter filter) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        TLRPC.Document document = media != null ? media.document : null;
        if (filter instanceof TLRPC.TL_inputMessagesFilterPhotos) {
            return media instanceof TLRPC.TL_messageMediaPhoto;
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterVideo) {
            return MessageObject.isVideoDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterDocument) {
            return document != null && !MessageObject.isVideoDocument(document) && !MessageObject.isRoundVideoDocument(document) && !MessageObject.isVoiceDocument(document) && !MessageObject.isMusicDocument(document) && !MessageObject.isGifDocument(document) && !MessageObject.isStickerDocument(document) && !MessageObject.isAnimatedStickerDocument(document, true);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterUrl) {
            return media instanceof TLRPC.TL_messageMediaWebPage || hasUrlEntity(message);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterMusic) {
            return MessageObject.isMusicDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterVoice) {
            return MessageObject.isVoiceDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterRoundVideo) {
            return MessageObject.isRoundVideoDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterGif) {
            return MessageObject.isGifDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterPoll) {
            return media instanceof TLRPC.TL_messageMediaPoll;
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterGeo) {
            return MessageObject.isLocationMessage(message);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterPinned) {
            return message.pinned;
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterMyMentions) {
            return message.mentioned;
        }
        return true;
    }

    private static boolean hasUrlEntity(TLRPC.Message message) {
        if (message.entities == null) {
            return false;
        }
        for (int i = 0; i < message.entities.size(); i++) {
            TLRPC.MessageEntity entity = message.entities.get(i);
            if (entity instanceof TLRPC.TL_messageEntityUrl || entity instanceof TLRPC.TL_messageEntityTextUrl) {
                return true;
            }
        }
        return false;
    }
}
