package com.exteragram.messenger.feed;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * exteraGram feed: loads older history of up to 4 channels at a time through the regular
 * MessagesController.loadMessages (messages.getHistory), so the local timeline can be paged further.
 * A channel that returned fewer than 20 messages (or failed / timed out) is marked exhausted.
 */
final class FeedBackfillCoordinator {

    private static final int PAGE_SIZE = 20;
    private static final int MAX_PARALLEL = 4;
    private static final long ROUND_TIMEOUT_MS = 10000L;

    private final int currentAccount;
    private int loadIndex;
    private final Runnable onRoundFinished;
    private int roundId;
    private boolean running;
    private final int guid = ConnectionsManager.generateClassGuid();
    private final HashSet<Long> pending = new HashSet<>();
    private final HashSet<Long> exhausted = new HashSet<>();

    FeedBackfillCoordinator(int account, Runnable onRoundFinished) {
        this.currentAccount = account;
        this.onRoundFinished = onRoundFinished;
    }

    public HashSet<Long> getExhaustedSnapshot() {
        return new HashSet<>(exhausted);
    }

    public void clearExhausted() {
        exhausted.clear();
    }

    public void cancel() {
        running = false;
        roundId++;
        pending.clear();
        ConnectionsManager.getInstance(currentAccount).cancelRequestsForGuid(guid);
    }

    public void startRound(ArrayList<long[]> candidates) {
        running = true;
        final int round = ++roundId;
        pending.clear();
        int count = Math.min(MAX_PARALLEL, candidates.size());
        for (int i = 0; i < count; i++) {
            pending.add(candidates.get(i)[0]);
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        for (int i = 0; i < count; i++) {
            long dialogId = candidates.get(i)[0];
            int maxId = (int) candidates.get(i)[1];
            messagesController.loadMessages(dialogId, 0, false, PAGE_SIZE, maxId, 0, false, 0, guid, 0, 0, 0, 0, 0, loadIndex++, false);
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (round == roundId && running) {
                exhausted.addAll(pending);
                finishRound();
            }
        }, ROUND_TIMEOUT_MS);
    }

    public void onMessagesDidLoad(Object... args) {
        if ((Integer) args[10] != guid) {
            return;
        }
        Long dialogId = (Long) args[0];
        if (((ArrayList<?>) args[2]).size() < PAGE_SIZE) {
            exhausted.add(dialogId);
        }
        onResult(dialogId);
    }

    public void onLoadingMessagesFailed(Object... args) {
        if ((Integer) args[0] != guid) {
            return;
        }
        long dialogId = 0;
        Object request = args[1];
        if (request instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.InputPeer peer = ((TLRPC.TL_messages_getHistory) request).peer;
            if (peer != null) {
                long id = peer.channel_id;
                if (id == 0) {
                    id = peer.chat_id;
                }
                dialogId = -id;
            }
        }
        if (dialogId != 0) {
            exhausted.add(dialogId);
        }
        onResult(dialogId);
    }

    private void onResult(long dialogId) {
        if (running && pending.remove(dialogId) && pending.isEmpty()) {
            finishRound();
        }
    }

    private void finishRound() {
        running = false;
        roundId++;
        pending.clear();
        onRoundFinished.run();
    }
}
