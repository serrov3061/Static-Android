package org.telegram.ui.Wallet;

import static org.telegram.messenger.LocaleController.getString;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_gramwallet;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.Components.BulletinFactory;

import java.security.SecureRandom;
import java.util.ArrayList;

/**
 * StaticGram: the account's custodial wallet on the StaticGram chain (server docs/chain-nft.md).
 * The key lives on the server, so the client only reads the address (wallet.getState), lists the
 * NFTs the wallet holds (wallet.getNfts) and moves them with gramwallet.transferNft. It does not
 * touch the official on-device TON wallet (GramWallet).
 */
public class SGChainWallet {

    public static final String EXPLORER_URL = "https://fragment.staticgram.top/explorer/";
    private static final int PAGE_SIZE = 30;
    private static final long[] POLL_DELAYS_MS = {2000, 4000, 7000, 12000, 20000, 30000};

    private static final SGChainWallet[] instances = new SGChainWallet[UserConfig.MAX_ACCOUNT_COUNT];

    public static SGChainWallet getInstance(int account) {
        SGChainWallet instance = instances[account];
        if (instance == null) {
            synchronized (SGChainWallet.class) {
                instance = instances[account];
                if (instance == null) {
                    instances[account] = instance = new SGChainWallet(account);
                }
            }
        }
        return instance;
    }

    private final int currentAccount;

    private SGChainWallet(int account) {
        currentAccount = account;
    }

    // ===== address =====

    private String address;
    private int addressReqId = -1;
    private final ArrayList<Utilities.Callback<String>> addressCallbacks = new ArrayList<>();

    /** The wallet's active (non-bounceable UQ…) address, null until wallet.getState answered. */
    public String getAddress() {
        return address;
    }

    /** Calls back with the active address (cached or freshly asked), or null when the node has no chain wallet. */
    public void loadAddress(Utilities.Callback<String> callback) {
        if (!TextUtils.isEmpty(address)) {
            if (callback != null) callback.run(address);
            return;
        }
        if (callback != null) addressCallbacks.add(callback);
        if (addressReqId >= 0) return;
        addressReqId = ConnectionsManager.getInstance(currentAccount).sendRequestTyped(new TL_wallet.getState(), AndroidUtilities::runOnUIThread, (state, err) -> {
            addressReqId = -1;
            if (state instanceof TL_wallet.TL_walletState) {
                address = ((TL_wallet.TL_walletState) state).address;
            }
            final ArrayList<Utilities.Callback<String>> callbacks = new ArrayList<>(addressCallbacks);
            addressCallbacks.clear();
            for (Utilities.Callback<String> c : callbacks) c.run(address);
        });
    }

    // ===== NFTs held by the wallet =====

    public final ArrayList<TL_wallet.nftItem> nfts = new ArrayList<>();
    private final ArrayList<String> removed = new ArrayList<>();
    private String nextOffset = "";
    private boolean loading, loaded, endReached;
    private String error;
    private int reqId = -1;
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    public boolean isLoading() {
        return loading;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isEndReached() {
        return endReached;
    }

    public String getError() {
        return error;
    }

    /** The next page (the first one when nothing is loaded yet). */
    public void load() {
        if (loading || endReached && loaded) return;
        load(!loaded);
    }

    /** Reloads the list from the first page; the current items stay visible until the answer. */
    public void invalidate() {
        if (reqId >= 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(reqId, true);
            reqId = -1;
        }
        loading = false;
        load(true);
    }

    private int pollAttempt;
    private final Runnable poll = () -> {
        invalidate();
        schedulePoll();
    };

    /** Chain ops finish asynchronously (mint, transfer, return): refresh a few times while they land. */
    public void invalidateSoon() {
        AndroidUtilities.cancelRunOnUIThread(poll);
        pollAttempt = 0;
        invalidate();
        schedulePoll();
    }

    private void schedulePoll() {
        if (pollAttempt >= POLL_DELAYS_MS.length) return;
        AndroidUtilities.runOnUIThread(poll, POLL_DELAYS_MS[pollAttempt++]);
    }

    private void load(boolean refresh) {
        final TL_wallet.getNfts req = new TL_wallet.getNfts();
        req.offset = refresh ? "" : (nextOffset == null ? "" : nextOffset);
        req.limit = PAGE_SIZE;
        loading = true;
        error = null;
        notifyUpdate();
        reqId = ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (result, err) -> {
            reqId = -1;
            loading = false;
            if (result != null) {
                if (refresh) nfts.clear();
                for (TL_wallet.nftItem item : result.items) {
                    if (item == null || isRemoved(item.address)) continue;
                    boolean replaced = false;
                    for (int i = 0; i < nfts.size(); ++i) {
                        if (TextUtils.equals(nfts.get(i).address, item.address)) {
                            nfts.set(i, item);
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced) nfts.add(item);
                }
                nextOffset = result.next_offset;
                endReached = TextUtils.isEmpty(nextOffset);
                loaded = true;
                if (refresh && endReached) {
                    // the full inventory no longer lists what was sent away: stop hiding it locally
                    for (int i = removed.size() - 1; i >= 0; --i) {
                        boolean listed = false;
                        for (TL_wallet.nftItem item : result.items) {
                            if (TextUtils.equals(item.address, removed.get(i))) {
                                listed = true;
                                break;
                            }
                        }
                        if (!listed) removed.remove(i);
                    }
                }
            } else {
                error = err == null ? "NULL_ERROR" : err.text;
                if (!loaded) endReached = false;
            }
            notifyUpdate();
        });
    }

    private boolean isRemoved(String nftAddress) {
        for (String a : removed) {
            if (TextUtils.equals(a, nftAddress)) return true;
        }
        return false;
    }

    /** Hides an NFT that was just sent away, until the server stops listing it. */
    public void removeLocally(String nftAddress) {
        if (TextUtils.isEmpty(nftAddress)) return;
        if (!isRemoved(nftAddress)) removed.add(nftAddress);
        for (int i = nfts.size() - 1; i >= 0; --i) {
            if (TextUtils.equals(nfts.get(i).address, nftAddress)) nfts.remove(i);
        }
        notifyUpdate();
    }

    public void subscribe(Runnable listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void unsubscribe(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyUpdate() {
        for (Runnable l : new ArrayList<>(listeners)) l.run();
    }

    // ===== moves =====

    /** One key per user action (8..128 bytes), reused only for retries of the same action. */
    public static String newRequestKey() {
        final byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        final StringBuilder sb = new StringBuilder("sgnft-");
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /**
     * gramwallet.transferNft: exactly one of {@code toUserId} (> 0), {@code toAddress} or {@code depositPayload}.
     * Calls back with null when the move is queued.
     */
    public void transferNft(String nftAddress, long toUserId, String toAddress, byte[] depositPayload, String requestKey, Utilities.Callback<TLRPC.TL_error> done) {
        final TL_gramwallet.TL_transferNft req = new TL_gramwallet.TL_transferNft();
        req.nft_address = nftAddress;
        if (toUserId > 0) {
            req.to_user = MessagesController.getInstance(currentAccount).getInputUser(toUserId);
        } else if (!TextUtils.isEmpty(toAddress)) {
            req.to_address = toAddress.trim();
        } else {
            req.deposit_payload = depositPayload;
        }
        req.request_key = TextUtils.isEmpty(requestKey) ? newRequestKey() : requestKey;
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res instanceof TLRPC.TL_boolTrue) {
                removeLocally(nftAddress);
                invalidateSoon();
                SGCollectibles.watch(currentAccount, null, nftAddress, false, null, null);
                if (done != null) done.run(null);
                return;
            }
            if (err == null) {
                err = new TLRPC.TL_error();
                err.text = "NULL_ERROR";
            }
            if (done != null) done.run(err);
        });
    }

    // ===== helpers =====

    public static boolean isGift(TL_wallet.nftItem item) {
        return item != null && item.gift instanceof TL_stars.TL_starGiftUnique;
    }

    public static boolean isNumber(TL_wallet.nftItem item) {
        return item != null && !isGift(item) && item.name != null && item.name.trim().startsWith("+");
    }

    public static String explorerUrl(String address) {
        return EXPLORER_URL + address;
    }

    /** Looks like a basechain address: friendly (48 url-safe base64 chars) or raw 0:<hex>. */
    public static boolean looksLikeAddress(String text) {
        if (text == null) return false;
        final String s = text.trim();
        if (s.matches("^-?[0-9]+:[0-9a-fA-F]{64}$")) return true;
        return s.length() == 48 && s.matches("^[A-Za-z0-9_\\-+/]{48}$");
    }

    /** Localized text for the chain-NFT errors of the server, null for the others. */
    public static String errorText(TLRPC.TL_error err) {
        if (err == null || err.text == null) return null;
        switch (err.text) {
            case "NFT_REQUEST_KEY_CONFLICT": return getString(R.string.SgNftErrorKeyConflict);
            case "NFT_EXTERNAL_TRANSFER_UNAVAILABLE": return getString(R.string.SgNftErrorExternal);
            case "STARGIFT_WITHDRAWAL_UNAVAILABLE": return getString(R.string.SgNftErrorWithdrawUnavailable);
            case "STARGIFT_EXPORT_TOO_EARLY": return getString(R.string.SgNftErrorTooEarly);
            case "NFT_BUSY": return getString(R.string.SgNftErrorBusy);
            case "NFT_UNAVAILABLE": return getString(R.string.SgNftErrorListed);
            case "NFT_INVALID": return getString(R.string.SgNftErrorInvalid);
            case "WALLET_ADDRESS_INVALID": return getString(R.string.SgNftInvalidAddress);
            case "WALLET_UNAVAILABLE": return getString(R.string.SgNftErrorWallet);
            case "USER_DISALLOWED_STARGIFTS": return getString(R.string.SgNftErrorDisallowed);
        }
        return null;
    }

    public static void showError(BulletinFactory factory, TLRPC.TL_error err) {
        if (factory == null) factory = BulletinFactory.global();
        final String text = errorText(err);
        if (text != null) {
            factory.createSimpleBulletin(R.raw.error, text).show();
        } else {
            factory.showForError(err);
        }
    }
}
