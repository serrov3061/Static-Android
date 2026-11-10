package org.telegram.ui.Wallet;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stars.StarsController;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * StaticGram: live refresh of everything that shows gifts / NFTs / domains after a chain action,
 * without re-entering the app. {@link #changed} reloads the profile gifts and the wallet NFTs and
 * posts {@link NotificationCenter#sgCollectiblesChanged}. Chain moves (mint to the wallet, return
 * to the profile, transfers) land asynchronously: {@link #watch} polls wallet.getNfts every 3 s for
 * up to 2 min until the NFT appears / disappears, then refreshes again and shows the landing bulletin.
 */
public final class SGCollectibles {

    private static final long INTERVAL_MS = 3000;
    private static final int MAX_ATTEMPTS = 40; // 2 min
    private static final int PAGE = 100;

    private SGCollectibles() {
    }

    private static final class Watch {
        int account;
        String slug, address;
        boolean present;
        CharSequence landedText;
        TLRPC.Document document;
        int attempts;
    }

    private static final ArrayList<Watch> watches = new ArrayList<>();
    private static final HashSet<Integer> inFlight = new HashSet<>();
    private static final Runnable tick = SGCollectibles::tick;

    /** Reloads the own profile gifts and the wallet NFTs, and tells every open screen. */
    public static void changed(int account) {
        StarsController.getInstance(account).invalidateProfileGifts(UserConfig.getInstance(account).getClientUserId());
        SGChainWallet.getInstance(account).invalidate();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.sgCollectiblesChanged);
    }

    /**
     * Waits until the NFT (by gift slug or NFT address) is ({@code present}) or is no longer in the
     * own wallet. {@code landedText} (optional) is shown as a bulletin when it lands.
     */
    public static void watch(int account, String slug, String address, boolean present, CharSequence landedText, TLRPC.Document document) {
        if (TextUtils.isEmpty(slug) && TextUtils.isEmpty(address)) {
            changed(account);
            return;
        }
        Watch w = null;
        for (Watch x : watches) {
            if (x.account == account && x.present == present
                    && (!TextUtils.isEmpty(slug) && TextUtils.equals(slug, x.slug) || !TextUtils.isEmpty(address) && TextUtils.equals(address, x.address))) {
                w = x;
                break;
            }
        }
        if (w == null) {
            w = new Watch();
            w.account = account;
            w.present = present;
            watches.add(w);
        }
        if (!TextUtils.isEmpty(slug)) w.slug = slug;
        if (!TextUtils.isEmpty(address)) w.address = address;
        if (landedText != null) {
            w.landedText = landedText;
            w.document = document;
        }
        w.attempts = 0;
        changed(account);
        AndroidUtilities.cancelRunOnUIThread(tick);
        AndroidUtilities.runOnUIThread(tick, INTERVAL_MS);
    }

    private static void tick() {
        final HashSet<Integer> accounts = new HashSet<>();
        for (Watch w : watches) accounts.add(w.account);
        for (int account : accounts) {
            if (inFlight.contains(account)) continue;
            inFlight.add(account);
            final TL_wallet.getNfts req = new TL_wallet.getNfts();
            req.offset = "";
            req.limit = PAGE;
            ConnectionsManager.getInstance(account).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
                inFlight.remove(account);
                check(account, res);
            });
        }
        if (!watches.isEmpty()) {
            AndroidUtilities.cancelRunOnUIThread(tick);
            AndroidUtilities.runOnUIThread(tick, INTERVAL_MS);
        }
    }

    private static void check(int account, TL_wallet.nftItems res) {
        boolean landedAny = false, expired = false;
        for (int i = watches.size() - 1; i >= 0; i--) {
            final Watch w = watches.get(i);
            if (w.account != account) continue;
            w.attempts++;
            if (res != null) {
                boolean found = false;
                for (TL_wallet.nftItem item : res.items) {
                    if (item == null) continue;
                    if (!TextUtils.isEmpty(w.address) && TextUtils.equals(w.address, item.address)
                            || !TextUtils.isEmpty(w.slug) && item.gift instanceof TL_stars.TL_starGiftUnique
                            && TextUtils.equals(w.slug, ((TL_stars.TL_starGiftUnique) item.gift).slug)) {
                        found = true;
                        break;
                    }
                }
                // a page that is not the whole wallet cannot prove that something is gone
                final boolean complete = TextUtils.isEmpty(res.next_offset);
                if (found == w.present && (w.present || complete)) {
                    watches.remove(i);
                    landedAny = true;
                    if (w.landedText != null) showLanded(w);
                    continue;
                }
            }
            if (w.attempts >= MAX_ATTEMPTS) {
                watches.remove(i);
                expired = true;
            }
        }
        if (landedAny || expired) changed(account);
    }

    private static void showLanded(Watch w) {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        final BulletinFactory factory = fragment != null ? BulletinFactory.of(fragment) : BulletinFactory.global();
        if (factory == null) return;
        if (w.document != null) {
            factory.createEmojiBulletin(w.document, w.landedText).show();
        } else {
            factory.createSimpleBulletin(R.raw.contact_check, w.landedText).show();
        }
    }
}
