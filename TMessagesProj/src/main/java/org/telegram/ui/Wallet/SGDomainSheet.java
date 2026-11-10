package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.tl.TL_tondns;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/**
 * StaticGram: one .ton domain as Telegram 13.1's wallet collectible sheet (SGCollectibleSheetUi):
 * the card (server media.png over the vector render), the «Домен .ton» / «Минтится…» / «Основной»
 * chips, the «Основной» · «Передать» · «Эксплорер» tiles and the Владелец / Статус / Кошелёк
 * («Изменить»: own wallet by default) / Куплен / Адрес table.
 * Opens from «Мои домены» (a tondns.domain) and from «Коллекции» (a wallet.nftItem named *.ton).
 */
public class SGDomainSheet {

    public static void open(Context context, int currentAccount, TL_tondns.TL_domain domain, Theme.ResourcesProvider resourcesProvider) {
        if (domain == null) return;
        open(context, currentAccount, domain.name, null, resourcesProvider);
    }

    public static void open(Context context, int currentAccount, TL_wallet.nftItem nft, Theme.ResourcesProvider resourcesProvider) {
        if (nft == null) return;
        open(context, currentAccount, nft.name, nft, resourcesProvider);
    }

    private static void open(Context context, int currentAccount, String rawName, TL_wallet.nftItem nft, Theme.ResourcesProvider resourcesProvider) {
        if (context == null || TextUtils.isEmpty(rawName)) return;
        final String name = SGTonDns.fullName(rawName.trim());
        final SGTonDns dns = SGTonDns.getInstance(currentAccount);
        final SGCollectibleSheetUi ui = new SGCollectibleSheetUi(context, resourcesProvider);
        final BottomSheet sheet = ui.sheet;
        final int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final int greenColor = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText2, resourcesProvider);

        // the card: the server render once minted (or the wallet NFT image), the vector one meanwhile
        ui.imageView.setContentDescription(name);
        final SGDomainCardDrawable local = new SGDomainCardDrawable(name).setRadiusFraction(0);
        ui.titleView.setText(name);

        final LinearLayout[] tiles = new LinearLayout[4];
        final boolean[] imageSet = new boolean[1];
        final Runnable[] bindRef = new Runnable[1];
        final Runnable bind = () -> {
            final TL_tondns.TL_domain d = dns.findMine(name);
            final boolean minted = d != null ? d.minted : nft != null;
            final boolean primary = d != null && d.primary;
            if (!imageSet[0]) {
                if (nft != null && CollectibleCell.getImage(nft, false) != null) {
                    final ImageLocation image = CollectibleCell.getImage(nft, false);
                    final ImageLocation small = nft.image_small != null ? CollectibleCell.getImage(nft, true) : null;
                    ui.imageView.setImage(image, "280_280", small, "100_100", local, null, 0, 0, nft);
                    imageSet[0] = true;
                } else if (minted) {
                    ui.imageView.setImage(SGTonDns.mediaUrl(name), "280_280", local);
                    imageSet[0] = true;
                } else {
                    ui.imageView.setImageDrawable(local);
                }
            }
            // chips: Домен .ton · Минтится… / Основной
            ui.chips.removeAllViews();
            ui.addChip(getString(R.string.SgDnsKind), accentColor);
            if (!minted) {
                ui.addChip(getString(R.string.SgDnsStatusMintingChip), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
            } else if (primary) {
                ui.addChip(getString(R.string.SgDnsPrimaryChip), greenColor);
            }
            // tiles
            SGCollectibleSheetUi.setActionLabel(tiles[0], primary ? R.drawable.msg_unpin : R.drawable.msg_pin,
                    getString(primary ? R.string.SgDnsActionUnpin : R.string.SgDnsActionPin));
            if (tiles[1] != null) tiles[1].setAlpha(minted ? 1f : .5f);
            final TL_tondns.TL_listing listing = dns.getMyListing(name);
            if (tiles[3] != null) {
                SGCollectibleSheetUi.setActionLabel(tiles[3], R.drawable.menu_edit_price,
                        getString(listing != null ? R.string.SgDnsOnSale : R.string.SgDnsSell));
                tiles[3].setAlpha(minted && d != null ? 1f : .5f);
            }
            // table: Владелец · Статус · Кошелёк · Адрес
            ui.table.clear();
            ui.table.addRowUser(getString(R.string.SgNftOwner), currentAccount, UserConfig.getInstance(currentAccount).getClientUserId(), null);
            ui.table.addRow(getString(R.string.SgDnsStatus), getString(minted ? R.string.SgDnsStatusReadyFull : R.string.SgDnsStatusMintingFull));
            if (d != null) {
                final String record = d.wallet_address;
                final boolean own = TextUtils.isEmpty(record) || TextUtils.equals(record, d.owner_address);
                ui.table.addRow(getString(R.string.SgDnsWalletRecordShort),
                        own ? getString(R.string.SgDnsWalletOwnShort) : SGCollectibleSheetUi.shortAddress(record),
                        getString(R.string.SgDnsWalletChange),
                        () -> editWalletRecord(context, currentAccount, d, sheet, bindRef[0], resourcesProvider));
                if (listing != null) {
                    ui.table.addRow(getString(R.string.SgDnsOnSale), formatString(R.string.SgDnsSaleRowValue, GramWallet.formatGrams(listing.price)));
                }
                if (d.purchased_at > 0) {
                    ui.table.addRow(getString(R.string.SgDnsPurchased), LocaleController.formatDateChat(d.purchased_at, true));
                }
            }
            final String address = nft != null ? nft.address : d != null ? d.item_address : null;
            if (!TextUtils.isEmpty(address)) {
                ui.table.addWalletAddressRow(getString(R.string.SgNftAddress), address,
                        () -> ui.bulletins().createSimpleBulletin(R.raw.copy, getString(R.string.SgNftAddressCopied)).show());
            }
        };
        bindRef[0] = bind;

        tiles[0] = ui.addAction(R.drawable.msg_pin, getString(R.string.SgDnsActionPin), v -> {
            final TL_tondns.TL_domain d = dns.findMine(name);
            if (d == null) {
                ui.bulletins().createSimpleBulletin(R.raw.error, getString(R.string.SgDnsErrorNotOwner)).show();
                return;
            }
            final boolean primary = d.primary;
            v.setEnabled(false);
            dns.setPrimary(primary ? "" : name, err -> {
                v.setEnabled(true);
                if (err != null) {
                    SGTonDns.showError(ui.bulletins(), err);
                    return;
                }
                bind.run();
                ui.bulletins().createSimpleBulletin(primary ? R.raw.contact_check : R.raw.ic_pin,
                        primary ? getString(R.string.SgDnsPrimaryCleared) : formatString(R.string.SgDnsPrimarySet, name)).show();
            });
        });
        tiles[1] = ui.addAction(R.drawable.wallet_transfer, getString(R.string.SgNftTransfer), v -> {
            final TL_tondns.TL_domain d = dns.findMine(name);
            final boolean minted = d != null ? d.minted : nft != null;
            if (!minted) {
                ui.bulletins().createSimpleBulletin(R.raw.timer_3, getString(R.string.SgDnsTransferMinting)).show();
                return;
            }
            TL_wallet.nftItem item = nft;
            if (item == null) {
                item = new TL_wallet.nftItem();
                item.address = d.item_address;
                item.owner_address = d.owner_address;
                item.name = name;
            }
            SGNftSheet.openTransfer(context, currentAccount, item, sheet, resourcesProvider);
        });
        // «Продать»: list the own minted domain at a fixed GRAM price (or change / cancel the listing)
        tiles[3] = ui.addAction(R.drawable.menu_edit_price, getString(R.string.SgDnsSell), v -> {
            final TL_tondns.TL_domain d = dns.findMine(name);
            if (d == null) {
                ui.bulletins().createSimpleBulletin(R.raw.error, getString(R.string.SgDnsErrorNotOwner)).show();
                return;
            }
            if (!d.minted) {
                ui.bulletins().createSimpleBulletin(R.raw.timer_3, getString(R.string.SgDnsTransferMinting)).show();
                return;
            }
            SGDomainSaleSheet.open(context, currentAccount, name, resourcesProvider, bindRef[0]);
        });
        tiles[2] = ui.addAction(R.drawable.msg_openin, getString(R.string.SgNftExplorer), v -> {
            final TL_tondns.TL_domain d = dns.findMine(name);
            final String address = nft != null ? nft.address : d != null ? d.item_address : null;
            if (TextUtils.isEmpty(address)) {
                ui.bulletins().createSimpleBulletin(R.raw.timer_3, getString(R.string.SgDnsTransferMinting)).show();
                return;
            }
            Browser.openUrl(context, SGChainWallet.explorerUrl(address));
        });

        final Runnable listener = bind;
        dns.subscribe(listener);
        sheet.setOnDismissListener(di -> dns.unsubscribe(listener));
        bind.run();
        if (!dns.isMyLoaded() && !dns.isMyLoading()) dns.loadMyDomains(null);
        dns.loadMyListings(null);
        ui.show();
    }

    /** «Адрес кошелька»: a custom address, or back to the own custodial wallet. */
    private static void editWalletRecord(Context context, int currentAccount, TL_tondns.TL_domain domain, BottomSheet parent, Runnable onDone, Theme.ResourcesProvider resourcesProvider) {
        final SGTonDns dns = SGTonDns.getInstance(currentAccount);
        final FrameLayout container = new FrameLayout(context);
        final EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        edit.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        edit.setHint(getString(R.string.SgNftAddressHint));
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        edit.setCursorSize(dp(20));
        edit.setCursorWidth(1.5f);
        edit.setBackground(null);
        edit.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        edit.setPadding(0, dp(8), 0, dp(8));
        final boolean own = TextUtils.isEmpty(domain.wallet_address) || TextUtils.equals(domain.wallet_address, domain.owner_address);
        if (!own) {
            edit.setText(domain.wallet_address);
            edit.setSelection(edit.length());
        }
        container.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 0, 24, 0));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.SgDnsWalletRecord));
        builder.setMessage(formatString(R.string.SgDnsWalletRecordText, SGTonDns.fullName(domain.name)));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), (di, w) -> {
            final String address = edit.getText().toString().trim();
            if (!SGChainWallet.looksLikeAddress(address)) {
                BulletinFactory.of(parent.container, resourcesProvider).createSimpleBulletin(R.raw.error, getString(R.string.SgNftInvalidAddress)).show();
                return;
            }
            final Browser.Progress progress = di.makeButtonLoading(w);
            progress.init();
            dns.setWalletRecord(domain.name, address, (res, err) -> {
                progress.end();
                if (err != null) {
                    SGTonDns.showError(BulletinFactory.of(parent.container, resourcesProvider), err);
                    return;
                }
                onDone.run();
                BulletinFactory.of(parent.container, resourcesProvider).createSimpleBulletin(R.raw.contact_check, getString(R.string.SgDnsWalletSaved)).show();
            });
        });
        if (!own) {
            builder.setNeutralButton(getString(R.string.SgDnsWalletReset), (di, w) -> {
                final Browser.Progress progress = di.makeButtonLoading(w);
                progress.init();
                dns.setWalletRecord(domain.name, null, (res, err) -> {
                    progress.end();
                    if (err != null) {
                        SGTonDns.showError(BulletinFactory.of(parent.container, resourcesProvider), err);
                        return;
                    }
                    onDone.run();
                    BulletinFactory.of(parent.container, resourcesProvider).createSimpleBulletin(R.raw.contact_check, getString(R.string.SgDnsWalletSaved)).show();
                });
            });
        }
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.show();
        edit.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(edit), 200);
    }
}
