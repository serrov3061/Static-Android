package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BirthdayController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.Premium.boosts.UserSelectorBottomSheet;

/**
 * StaticGram: a collectible username / number held by the chain wallet, laid out as Telegram
 * 13.1's wallet collectible sheet (SGCollectibleSheetUi): the server's 1000×1000 card, the name
 * with its kind chip, the «Передать» (to a user only: external addresses are refused for
 * usernames and numbers) · «Эксплорер» · «Ещё» tiles and the Владелец / Коллекция / Адрес table.
 */
public class SGNftSheet {

    public static void open(Context context, int currentAccount, TL_wallet.nftItem nft, Theme.ResourcesProvider resourcesProvider) {
        if (context == null || nft == null) return;
        if (SGTonDns.isDomain(nft)) {
            // a .ton domain: the domain sheet with «Сделать основным» and the wallet record
            SGDomainSheet.open(context, currentAccount, nft, resourcesProvider);
            return;
        }
        final boolean number = SGChainWallet.isNumber(nft);
        final SGCollectibleSheetUi ui = new SGCollectibleSheetUi(context, resourcesProvider);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);
        final int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);

        // the server card
        ui.imageView.setContentDescription(nft.name);
        final Drawable placeholder = context.getResources().getDrawable(R.drawable.wallet_nft_placeholder).mutate();
        placeholder.setColorFilter(new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN));
        final ImageLocation image = CollectibleCell.getImage(nft, false);
        if (image != null) {
            final ImageLocation small = nft.image_small != null ? CollectibleCell.getImage(nft, true) : null;
            ui.imageView.setImage(image, "280_280", small, "100_100", placeholder, null, 0, 0, nft);
        } else {
            ui.imageView.setImageDrawable(placeholder);
        }
        ui.titleView.setText(TextUtils.isEmpty(nft.name) ? getString(R.string.WalletCollectible) : nft.name);
        ui.addChip(getString(number ? R.string.SgNftNumber : R.string.SgNftUsername), accentColor);

        // 13.1 action tiles: Передать · Эксплорер · Ещё
        ui.addAction(R.drawable.wallet_transfer, getString(R.string.SgNftTransfer), v -> openTransfer(context, currentAccount, nft, ui.sheet, resourcesProvider));
        ui.addAction(R.drawable.msg_openin, getString(R.string.SgNftExplorer), v -> Browser.openUrl(context, SGChainWallet.explorerUrl(nft.address)));
        ui.addAction(R.drawable.ic_ab_other, getString(R.string.SgNftMore), v -> openMore(ui, nft.address));

        // Владелец · Коллекция · Адрес
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        ui.table.addRowUser(getString(R.string.SgNftOwner), currentAccount, selfId, null);
        ui.table.addRow(getString(R.string.SgNftCollection), getString(number ? R.string.SgNftCollectionNumbers : R.string.SgNftCollectionUsernames));
        if (!TextUtils.isEmpty(nft.address)) {
            ui.table.addWalletAddressRow(getString(R.string.SgNftAddress), nft.address,
                    () -> ui.bulletins().createSimpleBulletin(R.raw.copy, getString(R.string.SgNftAddressCopied)).show());
        }
        ui.show();
    }

    /** «Ещё»: ItemOptions with «Скопировать адрес» and «Поделиться». */
    static void openMore(SGCollectibleSheetUi ui, String address) {
        if (TextUtils.isEmpty(address)) return;
        final View anchor = ui.actions.getChildAt(ui.actions.getChildCount() - 1);
        ItemOptions.makeOptions(ui.sheet.container, ui.resourcesProvider, anchor)
                .add(R.drawable.msg_copy, getString(R.string.SgNftCopyAddress), () -> {
                    AndroidUtilities.addToClipboard(address);
                    ui.bulletins().createSimpleBulletin(R.raw.copy, getString(R.string.SgNftAddressCopied)).show();
                })
                .add(R.drawable.msg_share, getString(R.string.SgNftShare), () -> {
                    try {
                        final Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("text/plain");
                        intent.putExtra(Intent.EXTRA_TEXT, SGChainWallet.explorerUrl(address));
                        ui.context.startActivity(Intent.createChooser(intent, getString(R.string.SgNftShare)));
                    } catch (Exception ignore) {
                    }
                })
                .setGravity(Gravity.RIGHT)
                .show();
    }

    static void openTransfer(Context context, int currentAccount, TL_wallet.nftItem nft, BottomSheet parent, Theme.ResourcesProvider resourcesProvider) {
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        final UserSelectorBottomSheet[] picker = new UserSelectorBottomSheet[1];
        picker[0] = new UserSelectorBottomSheet(context, currentAccount, 0, BirthdayController.getInstance(currentAccount).getState(), UserSelectorBottomSheet.TYPE_TRANSFER, true, resourcesProvider);
        picker[0].setTitle(getString(R.string.SgNftTransferTitle));
        picker[0].setOnUserSelector(userId -> {
            if (userId == null || userId <= 0 || userId == selfId) {
                BulletinFactory.of(picker[0].container, resourcesProvider).createSimpleBulletin(R.raw.error, getString(R.string.SgNftOnlyUsers)).show();
                return;
            }
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(userId);
            final String name = TextUtils.isEmpty(nft.name) ? getString(R.string.WalletCollectible) : nft.name;
            final String requestKey = SGChainWallet.newRequestKey();
            new AlertDialog.Builder(context, resourcesProvider)
                .setTitle(getString(R.string.SgNftTransferTitle))
                .setMessage(AndroidUtilities.replaceTags(formatString(R.string.SgNftTransferToUserText, name, UserObject.getForcedFirstName(user))))
                .setPositiveButton(getString(R.string.SgNftTransfer), (di, w) -> {
                    final Browser.Progress progress = di.makeButtonLoading(w);
                    progress.init();
                    SGChainWallet.getInstance(currentAccount).transferNft(nft.address, userId, null, null, requestKey, err -> {
                        progress.end();
                        if (err != null) {
                            SGChainWallet.showError(BulletinFactory.of(picker[0].container, resourcesProvider), err);
                            return;
                        }
                        picker[0].dismiss();
                        parent.dismiss();
                        AndroidUtilities.runOnUIThread(() -> BulletinFactory.global().createSimpleBulletin(R.raw.contact_check, getString(R.string.SgNftSent)).show(), 200);
                    });
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
        });
        picker[0].show();
    }
}
