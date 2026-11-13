package org.telegram.ui.Wallet;

import static org.telegram.messenger.LocaleController.getString;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_tondns;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.Components.BulletinFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * StaticGram: .ton domains of the StaticGram chain (server docs/ton-dns.md, tondns.*).
 * Per account: the domains the user bought (getMyDomains), a batched in-memory cache of
 * address → primary domain (getDomainsForAddresses) for showing «justi.ton» in place of UQ…,
 * and name → address resolution (tondns.resolve) for sending to «name.ton».
 */
public class SGTonDns {

    public static final String SUFFIX = ".ton";
    public static final int MAX_NAME = 32;
    /** Media of a live domain: 1000×1000 card rendered by the server. */
    private static final String MEDIA_URL = "https://fragment.staticgram.top/api/nft/item/domain/%s/media.png?v=v3";
    private static final int MAX_BATCH = 200;
    private static final long NEGATIVE_TTL_MS = 60 * 1000L; // StaticGram: a freshly set primary domain shows up within a minute

    private static final SGTonDns[] instances = new SGTonDns[UserConfig.MAX_ACCOUNT_COUNT];

    public static SGTonDns getInstance(int account) {
        SGTonDns instance = instances[account];
        if (instance == null) {
            synchronized (SGTonDns.class) {
                instance = instances[account];
                if (instance == null) {
                    instances[account] = instance = new SGTonDns(account);
                }
            }
        }
        return instance;
    }

    private final int currentAccount;

    private SGTonDns(int account) {
        currentAccount = account;
    }

    private ConnectionsManager connections() {
        return ConnectionsManager.getInstance(currentAccount);
    }

    // ===== names =====

    /** "Alice.TON", "@alice", " alice " → "alice"; null when it is not a valid name. */
    public static String normalize(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("@")) s = s.substring(1);
        if (s.endsWith(SUFFIX)) s = s.substring(0, s.length() - SUFFIX.length());
        if (s.isEmpty() || s.length() > MAX_NAME) return null;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) return null;
        }
        return s;
    }

    /** "alice" / "alice.ton" → "alice.ton". */
    public static String fullName(String name) {
        if (name == null) return null;
        final String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(SUFFIX) ? n : n + SUFFIX;
    }

    /** "alice.ton" → "alice". */
    public static String shortName(String name) {
        if (name == null) return "";
        final String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(SUFFIX) ? n.substring(0, n.length() - SUFFIX.length()) : n;
    }

    /** Text that names a .ton domain (e.g. a pasted recipient), so it should be resolved, not used as an address. */
    public static boolean looksLikeDomain(String text) {
        if (text == null) return false;
        final String s = text.trim().toLowerCase(Locale.ROOT);
        return s.endsWith(SUFFIX) && normalize(s) != null;
    }

    public static String mediaUrl(String name) {
        return String.format(Locale.US, MEDIA_URL, shortName(name));
    }

    /** A wallet NFT that is one of our domains: the item name is "name.ton". */
    public static boolean isDomain(TL_wallet.nftItem item) {
        return item != null && !SGChainWallet.isGift(item) && looksLikeDomain(item.name);
    }

    public static String newRequestKey() {
        final byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        final StringBuilder sb = new StringBuilder("sgdns-");
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ===== my domains =====

    public final ArrayList<TL_tondns.TL_domain> myDomains = new ArrayList<>();
    private boolean myLoaded, myLoading;
    private TLRPC.TL_error myError;
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    public boolean isMyLoaded() {
        return myLoaded;
    }

    public boolean isMyLoading() {
        return myLoading;
    }

    public TLRPC.TL_error getMyError() {
        return myError;
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

    /** The account's primary domain ("alice.ton"), null when none or not loaded yet. */
    public String getPrimary() {
        for (TL_tondns.TL_domain d : myDomains) {
            if (d.primary) return d.name;
        }
        return null;
    }

    public TL_tondns.TL_domain findMine(String name) {
        final String full = fullName(name);
        for (TL_tondns.TL_domain d : myDomains) {
            if (TextUtils.equals(fullName(d.name), full)) return d;
        }
        return null;
    }

    /** Names bought on resale that are not in getMyDomains yet (the item still moves to our wallet). */
    private final HashSet<String> expectedNames = new HashSet<>();
    /** Names last seen not minted, to tell when one becomes ready. */
    private final HashSet<String> mintingNames = new HashSet<>();
    private static final long MINT_POLL_INTERVAL_MS = 3000, MINT_POLL_MAX_MS = 3 * 60 * 1000L;
    private long mintPollStartedAt;

    public boolean hasPendingMint() {
        for (TL_tondns.TL_domain d : myDomains) {
            if (!d.minted) return true;
        }
        return !expectedNames.isEmpty();
    }

    /** A bought listed domain: poll getMyDomains until it is ours and minted. */
    public void expectDomain(String name) {
        if (TextUtils.isEmpty(name)) return;
        expectedNames.add(fullName(name));
        mintPollStartedAt = 0;
        pollMints();
    }

    /** After a getMyDomains answer: «Домен name.ton готов» for every name that just became minted. */
    private void checkMintedTransitions() {
        final ArrayList<String> ready = new ArrayList<>();
        for (TL_tondns.TL_domain d : myDomains) {
            final String full = fullName(d.name);
            if (d.minted) {
                if (mintingNames.remove(full) | expectedNames.remove(full)) ready.add(full);
            } else {
                mintingNames.add(full);
                expectedNames.remove(full);
            }
        }
        if (ready.isEmpty()) return;
        SGCollectibles.changed(currentAccount);
        for (String name : ready) {
            final org.telegram.ui.ActionBar.BaseFragment fragment = org.telegram.ui.LaunchActivity.getSafeLastFragment();
            final BulletinFactory factory = fragment != null ? BulletinFactory.of(fragment) : BulletinFactory.global();
            if (factory != null) {
                factory.createSimpleBulletin(R.raw.contact_check, org.telegram.messenger.LocaleController.formatString(R.string.SgDnsDomainReady, name)).show();
            }
        }
    }

    public void loadMyDomains(Utilities.Callback<TLRPC.TL_error> done) {
        myLoading = true;
        connections().sendRequestTyped(new TL_tondns.TL_getMyDomains(), AndroidUtilities::runOnUIThread, (res, err) -> {
            myLoading = false;
            if (res != null) {
                final int mintedBefore = mintedCount(myDomains);
                final boolean hadLoaded = myLoaded;
                myDomains.clear();
                myDomains.addAll(res.domains);
                checkMintedTransitions();
                // a mint that finished puts the domain NFT into the wallet: refresh the collectibles
                if (hadLoaded && mintedCount(myDomains) != mintedBefore) {
                    AndroidUtilities.runOnUIThread(() -> SGCollectibles.changed(currentAccount));
                }
                myLoaded = true;
                myError = null;
                // our own custodial addresses map to the primary domain (stale answers dropped first)
                forgetOwnAddresses();
                final String primary = getPrimary();
                final String own = SGChainWallet.getInstance(currentAccount).getAddress();
                if (!TextUtils.isEmpty(own)) putCache(own, primary);
                for (TL_tondns.TL_domain d : myDomains) {
                    if (!TextUtils.isEmpty(d.owner_address)) {
                        putCache(d.owner_address, primary);
                    }
                }
                notifyCache();
            } else {
                myError = err;
            }
            notifyUpdate();
            if (done != null) done.run(err);
        });
    }

    private static int mintedCount(java.util.List<TL_tondns.TL_domain> list) {
        int n = 0;
        for (TL_tondns.TL_domain d : list) if (d != null && d.minted) n++;
        return n;
    }

    private void replaceMine(TL_tondns.TL_domain domain) {
        if (domain == null) return;
        for (int i = 0; i < myDomains.size(); i++) {
            if (TextUtils.equals(fullName(myDomains.get(i).name), fullName(domain.name))) {
                myDomains.set(i, domain);
                notifyUpdate();
                return;
            }
        }
        myDomains.add(0, domain);
        notifyUpdate();
    }

    private final Runnable mintPoll = new Runnable() {
        @Override
        public void run() {
            if (!hasPendingMint() || mintPollExpired()) {
                mintPollStartedAt = 0;
                return;
            }
            loadMyDomains(err -> {
                if (hasPendingMint() && !mintPollExpired()) {
                    AndroidUtilities.cancelRunOnUIThread(mintPoll);
                    AndroidUtilities.runOnUIThread(mintPoll, MINT_POLL_INTERVAL_MS);
                } else {
                    mintPollStartedAt = 0;
                    expectedNames.clear();
                }
            });
        }
    };

    private boolean mintPollExpired() {
        return mintPollStartedAt > 0 && System.currentTimeMillis() - mintPollStartedAt > MINT_POLL_MAX_MS;
    }

    /**
     * Mints (and resale transfers) are async: polls getMyDomains every 3 s while a domain is not
     * minted yet, for at most 3 minutes from the start of the wait.
     */
    public void pollMints() {
        AndroidUtilities.cancelRunOnUIThread(mintPoll);
        if (!hasPendingMint()) return;
        if (mintPollStartedAt == 0) mintPollStartedAt = System.currentTimeMillis();
        AndroidUtilities.runOnUIThread(mintPoll, MINT_POLL_INTERVAL_MS);
    }

    public void stopPolling() {
        AndroidUtilities.cancelRunOnUIThread(mintPoll);
        mintPollStartedAt = 0;
    }

    // ===== resale (tondns.listForSale / cancelSale / buyListed / getListings / getMyListings) =====

    /** The own active listings by full name. */
    private final HashMap<String, TL_tondns.TL_listing> myListings = new HashMap<>();
    /** Listing bounds (nanoton) and the burned fee, from getMyListings / getListings. */
    public long saleMinPrice = 1_000_000_000L, saleMaxPrice = 1_000_000L * 1_000_000_000L;
    public int saleFeePercent = 10;
    /** False after the server answered checkDomainEx as unknown (an older server): plain checkDomain. */
    private boolean checkExSupported = true;

    public TL_tondns.TL_listing getMyListing(String name) {
        return myListings.get(fullName(name));
    }

    private void applyListingsMeta(TL_tondns.TL_listings res) {
        if (res.max_price > 0) {
            saleMinPrice = res.min_price;
            saleMaxPrice = res.max_price;
        }
        if (res.fee_percent >= 0) saleFeePercent = res.fee_percent;
        org.telegram.messenger.MessagesController.getInstance(currentAccount).putUsers(res.users, false);
    }

    public void loadMyListings(Utilities.Callback<TLRPC.TL_error> done) {
        connections().sendRequestTyped(new TL_tondns.TL_getMyListings(), AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) {
                applyListingsMeta(res);
                myListings.clear();
                for (TL_tondns.TL_listing l : res.listings) {
                    if (l != null) myListings.put(fullName(l.name), l);
                }
                notifyUpdate();
            }
            if (done != null) done.run(err);
        });
    }

    public void getListings(int offset, int limit, String sort, Utilities.Callback2<TL_tondns.TL_listings, TLRPC.TL_error> done) {
        final TL_tondns.TL_getListings req = new TL_tondns.TL_getListings();
        req.offset = offset;
        req.limit = limit;
        req.sort = sort;
        connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) applyListingsMeta(res);
            if (done != null) done.run(res, err);
        });
    }

    public void listForSale(String name, long price, Utilities.Callback2<TL_tondns.TL_listing, TLRPC.TL_error> done) {
        final TL_tondns.TL_listForSale req = new TL_tondns.TL_listForSale();
        req.name = name;
        req.price = price;
        connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) {
                myListings.put(fullName(res.name), res);
                notifyUpdate();
                SGCollectibles.changed(currentAccount);
            }
            if (done != null) done.run(res, err);
        });
    }

    public void cancelSale(String name, Utilities.Callback<TLRPC.TL_error> done) {
        final TL_tondns.TL_cancelSale req = new TL_tondns.TL_cancelSale();
        req.name = name;
        connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res instanceof TLRPC.TL_boolTrue || err != null && "TONDNS_NOT_LISTED".equals(err.text)) {
                myListings.remove(fullName(name));
                notifyUpdate();
                SGCollectibles.changed(currentAccount);
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

    /** Buys a listed domain; expectedPrice is the price the client showed, requestKey one per attempt (same on retries). */
    public void buyListed(TL_tondns.TL_listing listing, String requestKey, Utilities.Callback2<TL_tondns.TL_buyResult, TLRPC.TL_error> done) {
        final TL_tondns.TL_buyListed req = new TL_tondns.TL_buyListed();
        req.name = listing.name;
        req.expected_price = listing.price;
        req.request_key = requestKey;
        connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) {
                SGCardsController.getInstance(currentAccount).loadGramBalance(null);
                // the item moves to our wallet within seconds; getMyDomains lists it once it lands
                SGCollectibles.watch(currentAccount, null, listing.item_address, true, null, null);
                expectDomain(listing.name);
            }
            if (done != null) done.run(res, err);
        });
    }

    /** checkDomain plus the listing of a taken domain for sale; an older server falls back to checkDomain. */
    public int checkDomainEx(String name, Utilities.Callback2<TL_tondns.TL_domainCheckEx, TLRPC.TL_error> done) {
        if (!checkExSupported) {
            return checkDomain(name, (res, err) -> done.run(res != null ? TL_tondns.TL_domainCheckEx.of(res) : null, err));
        }
        final TL_tondns.TL_checkDomainEx req = new TL_tondns.TL_checkDomainEx();
        req.name = name;
        return connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res == null && isUnknownMethod(err)) {
                checkExSupported = false;
                checkDomain(name, (r, e) -> done.run(r != null ? TL_tondns.TL_domainCheckEx.of(r) : null, e));
                return;
            }
            if (res != null) {
                org.telegram.messenger.MessagesController.getInstance(currentAccount).putUsers(res.users, false);
            }
            done.run(res, err);
        });
    }

    private static boolean isUnknownMethod(TLRPC.TL_error err) {
        if (err == null || err.text == null) return false;
        return err.text.contains("METHOD_INVALID") || err.text.contains("INPUT_CONSTRUCTOR") || err.text.contains("CONSTRUCTOR_INVALID")
                || err.text.contains("CONNECTION_LAYER_INVALID") || err.text.contains("INPUT_METHOD") || err.code == 501;
    }

    // ===== requests =====

    public int checkDomain(String name, Utilities.Callback2<TL_tondns.TL_domainCheck, TLRPC.TL_error> done) {
        final TL_tondns.TL_checkDomain req = new TL_tondns.TL_checkDomain();
        req.name = name;
        return connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, done);
    }

    public void cancel(int reqId) {
        if (reqId >= 0) connections().cancelRequest(reqId, true);
    }

    public void buyDomain(String name, long price, String requestKey, Utilities.Callback2<TL_tondns.TL_buyResult, TLRPC.TL_error> done) {
        final TL_tondns.TL_buyDomain req = new TL_tondns.TL_buyDomain();
        req.name = name;
        req.price = price;
        req.request_key = requestKey;
        connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) {
                replaceMine(res.domain);
                SGCardsController.getInstance(currentAccount).loadGramBalance(null);
                if (res.domain != null && !res.domain.minted) mintingNames.add(fullName(res.domain.name));
                mintPollStartedAt = 0;
                pollMints();
                SGCollectibles.changed(currentAccount);
            }
            if (done != null) done.run(res, err);
        });
    }

    /** name = "" clears the primary domain. */
    public void setPrimary(String name, Utilities.Callback<TLRPC.TL_error> done) {
        final TL_tondns.TL_setPrimaryDomain req = new TL_tondns.TL_setPrimaryDomain();
        req.name = name == null ? "" : name;
        connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res instanceof TLRPC.TL_boolTrue) {
                final String full = TextUtils.isEmpty(req.name) ? null : fullName(req.name);
                for (TL_tondns.TL_domain d : myDomains) {
                    d.primary = full != null && TextUtils.equals(fullName(d.name), full);
                }
                // our addresses now map to another name (or none)
                forgetOwnAddresses();
                for (TL_tondns.TL_domain d : myDomains) {
                    if (!TextUtils.isEmpty(d.owner_address)) putCache(d.owner_address, full);
                }
                final String own = SGChainWallet.getInstance(currentAccount).getAddress();
                if (!TextUtils.isEmpty(own)) putCache(own, full);
                notifyUpdate();
                notifyCache();
                SGCollectibles.changed(currentAccount);
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

    /** address = null sets the record back to the owner's own custodial wallet. */
    public void setWalletRecord(String name, String address, Utilities.Callback2<TL_tondns.TL_domain, TLRPC.TL_error> done) {
        final TL_tondns.TL_setWalletRecord req = new TL_tondns.TL_setWalletRecord();
        req.name = name;
        req.address = TextUtils.isEmpty(address) ? null : address.trim();
        connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) {
                final TL_tondns.TL_domain mine = findMine(res.name);
                // keep the local primary flag if the answer omits it
                if (mine != null && mine.primary) res.primary = true;
                replaceMine(res);
                SGCollectibles.changed(currentAccount);
            }
            if (done != null) done.run(res, err);
        });
    }

    private final HashMap<String, String> resolved = new HashMap<>();

    /** tondns.resolve: calls back with the address name.ton points to, or null with the error. */
    public int resolve(String name, Utilities.Callback2<String, TLRPC.TL_error> done) {
        final TL_tondns.TL_resolve req = new TL_tondns.TL_resolve();
        req.name = name;
        return connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            final String address = res != null && !TextUtils.isEmpty(res.address) ? res.address : null;
            if (address != null) resolved.put(fullName(name), address);
            if (done != null) done.run(address, err);
        });
    }

    public String getResolvedCached(String name) {
        return name == null ? null : resolved.get(fullName(name));
    }

    // ===== address → primary domain (batched, in memory) =====

    private final HashMap<String, String> domainByAddress = new HashMap<>();
    private final HashMap<String, Long> missingSince = new HashMap<>();
    private final HashSet<String> inFlight = new HashSet<>();
    private final ArrayList<String> queue = new ArrayList<>();
    private final ArrayList<Runnable> cacheListeners = new ArrayList<>();

    /**
     * The cache key of an address: one canonical raw form «workchain:hex» for every spelling
     * (raw 0:…, user-friendly UQ… / EQ…, url-safe or not), so a primary domain change replaces the
     * entry whatever form a screen asks with.
     */
    static String key(String address) {
        if (address == null) return null;
        final String a = address.trim();
        if (a.isEmpty()) return a;
        final int colon = a.indexOf(':');
        if (colon > 0) {
            return a.substring(0, colon) + ":" + a.substring(colon + 1).toLowerCase(Locale.ROOT);
        }
        if (a.length() == 48) {
            try {
                final byte[] b = android.util.Base64.decode(a.replace('-', '+').replace('_', '/'), android.util.Base64.DEFAULT);
                if (b != null && b.length == 36) {
                    final StringBuilder sb = new StringBuilder(70);
                    sb.append((int) b[1]).append(':');
                    for (int i = 2; i < 34; i++) sb.append(String.format(Locale.ROOT, "%02x", b[i] & 0xff));
                    return sb.toString();
                }
            } catch (Exception ignore) {
            }
        }
        return a;
    }

    /** Forgets every cached address → domain answer of the own addresses (before re-filling them). */
    private void forgetOwnAddresses() {
        for (TL_tondns.TL_domain d : myDomains) {
            if (!TextUtils.isEmpty(d.owner_address)) {
                final String k = key(d.owner_address);
                domainByAddress.remove(k);
                missingSince.remove(k);
            }
        }
        final String own = SGChainWallet.getInstance(currentAccount).getAddress();
        if (!TextUtils.isEmpty(own)) {
            domainByAddress.remove(key(own));
            missingSince.remove(key(own));
        }
    }

    private void putCache(String address, String domain) {
        final String k = key(address);
        if (TextUtils.isEmpty(k)) return;
        if (TextUtils.isEmpty(domain)) {
            domainByAddress.remove(k);
            missingSince.put(k, System.currentTimeMillis());
        } else {
            domainByAddress.put(k, fullName(domain));
            missingSince.remove(k);
        }
    }

    /** The primary domain of a custodial address if it is already known, else null (no request). */
    public String getCachedDomain(String address) {
        final String k = key(address);
        return TextUtils.isEmpty(k) ? null : domainByAddress.get(k);
    }

    /** Listeners called whenever new address → domain answers land (re-render the addresses). */
    public void subscribeCache(Runnable listener) {
        if (!cacheListeners.contains(listener)) cacheListeners.add(listener);
    }

    public void unsubscribeCache(Runnable listener) {
        cacheListeners.remove(listener);
    }

    private void notifyCache() {
        for (Runnable l : new ArrayList<>(cacheListeners)) l.run();
    }

    private final Runnable flush = this::flushQueue;

    /**
     * Calls back on the UI thread with the primary domain of the address (null when it has none).
     * Answers from memory when known; otherwise the address joins the next batched request.
     */
    public void getDomain(String address, Utilities.Callback<String> callback) {
        final String k = key(address);
        if (TextUtils.isEmpty(k)) {
            if (callback != null) callback.run(null);
            return;
        }
        final String known = domainByAddress.get(k);
        if (known != null) {
            if (callback != null) callback.run(known);
            return;
        }
        final Long missing = missingSince.get(k);
        if (missing != null && System.currentTimeMillis() - missing < NEGATIVE_TTL_MS) {
            if (callback != null) callback.run(null);
            return;
        }
        if (callback != null) {
            ArrayList<Utilities.Callback<String>> list = pending.get(k);
            if (list == null) pending.put(k, list = new ArrayList<>());
            list.add(callback);
        }
        if (!inFlight.contains(k) && !queue.contains(k)) {
            queue.add(k);
            AndroidUtilities.cancelRunOnUIThread(flush);
            AndroidUtilities.runOnUIThread(flush, 60);
        }
    }

    private final HashMap<String, ArrayList<Utilities.Callback<String>>> pending = new HashMap<>();

    private void flushQueue() {
        while (!queue.isEmpty()) {
            final int n = Math.min(MAX_BATCH, queue.size());
            final ArrayList<String> batch = new ArrayList<>(queue.subList(0, n));
            queue.subList(0, n).clear();
            inFlight.addAll(batch);
            final TL_tondns.TL_getDomainsForAddresses req = new TL_tondns.TL_getDomainsForAddresses();
            req.addresses = batch;
            connections().sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
                inFlight.removeAll(batch);
                if (res != null) {
                    final HashSet<String> matched = new HashSet<>();
                    for (TL_tondns.TL_addressDomain d : res.domains) {
                        if (d == null || TextUtils.isEmpty(d.address)) continue;
                        putCache(d.address, d.domain);
                        matched.add(key(d.address));
                    }
                    for (String a : batch) {
                        if (!matched.contains(a)) putCache(a, null);
                    }
                }
                // on errors (TONDNS_UNAVAILABLE, network) the addresses just stay as addresses
                for (String a : batch) {
                    final ArrayList<Utilities.Callback<String>> list = pending.remove(a);
                    if (list == null) continue;
                    final String domain = domainByAddress.get(a);
                    for (Utilities.Callback<String> c : list) c.run(domain);
                }
                if (res != null && !res.domains.isEmpty()) notifyCache();
            });
        }
    }

    /**
     * Receipts: fills {@code peer.domain} from the cache; when unknown, asks once and runs
     * {@code onFound} after a domain arrives (so the sheet can rebuild its rows).
     */
    public void fillPeerDomain(TL_wallet.WalletTransactionPeer peer, Runnable onFound) {
        if (peer == null || !TextUtils.isEmpty(peer.domain) || TextUtils.isEmpty(peer.address)) return;
        final String cached = getCachedDomain(peer.address);
        if (cached != null) {
            peer.domain = cached;
            return;
        }
        getDomain(peer.address, domain -> {
            if (domain != null && TextUtils.isEmpty(peer.domain)) {
                peer.domain = domain;
                if (onFound != null) onFound.run();
            }
        });
    }

    // ===== «name.ton» typed or pasted where an address is expected =====

    /**
     * Watches an address field: when it holds «name.ton», resolves it (debounced) with
     * tondns.resolve and shows «→ UQ…» in {@code status}; {@link #address} is the answer.
     */
    public static final class FieldResolver {
        public String domain;
        public String address;
        public boolean loading;
        private int reqId = -1;
        private int seq;

        /** The address to send to: the resolved one for a domain, the text itself otherwise (null while unknown). */
        public String target(String text) {
            final String t = text == null ? "" : text.trim();
            if (!looksLikeDomain(t)) return t;
            return TextUtils.equals(fullName(normalize(t)), domain) ? address : null;
        }
    }

    public static FieldResolver attachResolver(int account, android.widget.EditText edit, android.widget.TextView status) {
        final FieldResolver r = new FieldResolver();
        final SGTonDns dns = getInstance(account);
        final Runnable[] pendingRun = new Runnable[1];
        if (status != null) status.setVisibility(android.view.View.GONE);
        edit.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (pendingRun[0] != null) AndroidUtilities.cancelRunOnUIThread(pendingRun[0]);
                dns.cancel(r.reqId);
                r.reqId = -1;
                final int seq = ++r.seq;
                final String text = s == null ? "" : s.toString().trim();
                if (!looksLikeDomain(text)) {
                    r.domain = null;
                    r.address = null;
                    r.loading = false;
                    if (status != null) status.setVisibility(android.view.View.GONE);
                    return;
                }
                final String name = normalize(text);
                r.domain = fullName(name);
                r.address = dns.getResolvedCached(name);
                r.loading = r.address == null;
                if (status != null) {
                    status.setVisibility(android.view.View.VISIBLE);
                    status.setText(r.address != null ? formatResolved(r.address) : getString(R.string.SgDnsResolving));
                }
                if (r.address != null) return;
                pendingRun[0] = () -> r.reqId = dns.resolve(name, (address, err) -> {
                    if (seq != r.seq) return;
                    r.reqId = -1;
                    r.loading = false;
                    r.address = address;
                    if (status != null) {
                        status.setText(address != null ? formatResolved(address) : getString(R.string.SgDnsNotResolved));
                    }
                });
                AndroidUtilities.runOnUIThread(pendingRun[0], 300);
            }
        });
        return r;
    }

    public static CharSequence formatResolved(String address) {
        return "\u2192 " + address;
    }

    // ===== errors =====

    public static String errorText(TLRPC.TL_error err) {
        if (err == null || err.text == null) return getString(R.string.SgDnsErrorUnknown);
        switch (err.text) {
            case "TONDNS_NAME_INVALID": return getString(R.string.SgDnsErrorInvalid);
            case "TONDNS_NAME_TAKEN": return getString(R.string.SgDnsErrorTaken);
            case "TONDNS_NOT_FOUND": return getString(R.string.SgDnsErrorNotFound);
            case "TONDNS_NOT_OWNER": return getString(R.string.SgDnsErrorNotOwner);
            case "TONDNS_PRICE_CHANGED": return getString(R.string.SgDnsErrorPriceChanged);
            case "TONDNS_ADDRESS_INVALID": return getString(R.string.SgNftInvalidAddress);
            case "TONDNS_UNAVAILABLE": return getString(R.string.SgDnsErrorUnavailable);
            case "TONDNS_REQUEST_KEY_CONFLICT": return getString(R.string.SgNftErrorKeyConflict);
            case "BALANCE_TOO_LOW": return getString(R.string.SgCardErrorBalance);
            case "TONDNS_NOT_LISTED": return getString(R.string.SgDnsSaleErrorNotListed);
            case "TONDNS_SELF_BUY": return getString(R.string.SgDnsSaleErrorSelfBuy);
            case "TONDNS_PRICE_INVALID": return getString(R.string.SgDnsSaleErrorPriceInvalid);
            case "TONDNS_NOT_MINTED": return getString(R.string.SgDnsSaleErrorNotMinted);
            case "TONDNS_SALE_UNAVAILABLE": return getString(R.string.SgDnsSaleErrorUnavailable);
            case "TONDNS_BUSY": return getString(R.string.SgDnsSaleErrorBusy);
        }
        // the edge / server does not know a newer request yet: a human text, never the raw code
        if (isUnknownMethod(err)) {
            org.telegram.messenger.FileLog.e("tondns: " + err.text);
            return getString(R.string.SgDnsErrorUnavailable);
        }
        final String nft = SGChainWallet.errorText(err);
        if (nft != null) return nft;
        final String card = SGCardsController.errorText(err);
        if (card != null && card.equals(org.telegram.messenger.LocaleController.formatString(R.string.SgCardErrorCode, err.text))) {
            org.telegram.messenger.FileLog.e("tondns: " + err.text);
            return getString(R.string.SgDnsErrorUnknown);
        }
        return card;
    }

    public static void showError(BulletinFactory factory, TLRPC.TL_error err) {
        if (factory == null) factory = BulletinFactory.global();
        factory.createSimpleBulletin(R.raw.error, errorText(err)).show();
    }
}
