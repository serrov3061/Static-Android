# Stars / GRAM cards in chat (StaticGram)

Owner request (08.11): every way Stars or GRAM reach a user is shown like the
sgcard Stars transfer: a service line («Вам отправили ⭐ N Stars») and the wallet
card under it (golden for Stars, the GRAM card for GRAM), with the counterpart's
name on the card and the «получено»/«отправлено» corner ribbon.

## How the client draws it

* `WalletActionLayout` (org/telegram/ui/Wallet) is the card. It knows two actions:
  `messageActionGramTransfer` (GRAM card, `set(...)`) and the private
  `sgcard.messageActionStarsTransfer` (golden card, `setStars(...)`).
* `SgGrantCards` projects the grant actions onto those two:
  * `messageActionGiftStars`, `messageActionPrizeStars` -> synthetic
    `sgcard.messageActionStarsTransfer` (stars, transaction id, direction = `isOutOwner`);
  * `messageActionGiftTon` -> synthetic `messageActionGramTransfer` (nanoton from
    `crypto_amount`, or `amount` when the currency is TON/GRAM; empty address).
* `MessageObject`: grants get type `TYPE_DATE` (service line + card, like the
  transfers) instead of `TYPE_GIFT_STARS` (gift box), and the transfer service
  texts: Stars `SgStarsTransferReceivedYou` / `SgStarsTransferSent` with the golden
  Stars icon; GRAM `WalletActionReceivedGrams` (from a user/bot), `SgStarsTransferReceivedYou`
  (from 777000), `WalletActionSentGrams` (outgoing), amounts via `GramWallet.formatGramsWithUnit`.
* `ChatActionCell`: grants count as wallet-card messages; the card name is the chat
  counterpart (`SgGrantCards.cardName`: 777000 shows as its own name «StaticGram»,
  a prize shows the organizer channel); the GRAM card gets the fiat worth line
  like transfers; a tap opens the existing receipt of that action
  (`openStarsGiftTransaction`: Stars transaction sheet, GRAM wallet receipt for
  legacy `d:` ids, else the TON gift sheet).
* Notifications / dialog-list texts of the grant actions are unchanged.

## Inventory: ways Stars / GRAM reach a user (gramsrv master d4ddd45c)

| # | Path (server) | Resulting message (sender -> recipient) | TL action seen by 13.1.x client | Before | Now |
|---|---|---|---|---|---|
| 1 | `sgcard.sendStars` user->user (`wallet_card.go SendWalletCardStars`) | sender -> recipient, comment | `sgcard.messageActionStarsTransfer` (gated: `TELESRV_SG_TRANSFER_ACTION_CLIENTS`; others get `messageActionGiftStars`) | golden card | golden card (unchanged) |
| 2 | Stars bought for a friend (`payments` stars gift purchase, `stars_gift_purchase.go`) | buyer -> friend | `messageActionGiftStars` | gift box «sent you a gift for N Stars» | golden card, «Вы отправили X N Stars» / «Вам отправили N Stars» |
| 3 | Partner `/v1/partner/stars/credit` with `gift=true` (`partner_stars.go creditGift`) | 777000 -> user | `messageActionGiftStars` | gift box from «Unknown» | golden card «StaticGram» |
| 4 | Partner `/stars/credit` without gift (`creditSelf`) | no message (self top-up, balance update only) | - | - | - (no message by design) |
| 5 | Admin `grant-stars` / `grant-stars-all` (`admin/service.go giftAdminStars` -> partner gift) | 777000 -> user | `messageActionGiftStars` | gift box | golden card |
| 6 | Bot Stars withdrawal «Звёзды придут от StaticGram» (`bot_star_withdrawal.go`) | 777000 -> owner/recipient (+ text receipts) | `messageActionGiftStars` | gift box | golden card |
| 7 | Stars giveaway prize (`rpc/stars_giveaway_settlement.go`) | 777000 -> winner, **plain text only** «🎉 Поздравляем! Вы выиграли N ⭐…» | none (text) | text | **server change (feat/grant-cards):** 777000 `messageActionGiftStars` card + the same text after it |
| 8 | Giveaway leftover refund to the organizer | 777000 text summary | none (text) | text | text (refund, not a grant; unchanged) |
| 9 | Admin `compensate` (`admin/ops.go`, `StarsReasonAdjust`) | 777000 text «Вам начислена компенсация: N ⭐.» (optional) | none (text) | text | text (proposal below) |
| 10 | Official `messageActionPrizeStars` (not produced by gramsrv today) | channel -> winner | `messageActionPrizeStars` | gift box | golden card, organizer channel name |
| 11 | Paid messages refund (`paid_messages_refunded`) | peer | `messageActionPaidMessagesRefunded` | service line | unchanged (official semantics) |
| 12 | Bot invoice refund (`payment_refunded`) | bot | `messageActionPaymentRefunded` | service line | unchanged |
| 13 | `gramwallet.send` user->user (`rpc/gramwallet.go`, id `d:<key>`) | sender -> recipient, comment | `messageActionGramTransfer` (legacy profiles: `messageActionGiftTon`) | GRAM card | GRAM card (unchanged) |
| 14 | Fragment-wallet GRAM transfer (`fragment_wallet.go`, id `w<n>`) | sender -> recipient | `messageActionGramTransfer` (legacy id) | GRAM card | GRAM card (unchanged) |
| 15 | Partner `/v1/partner/ton/credit` (`admin/partner_ton.go sendPartnerTonGift`) | 777000 -> user | `messageActionGiftTon` | TON gift box «sent you a gift for …» | GRAM card «StaticGram» |
| 16 | Admin `grant-gram` (`admin/gram.go`, notice=true; bots: no notice) | 777000 -> user | `messageActionGiftTon` | TON gift box | GRAM card |
| 17 | Bot GRAM withdrawal `withdrawGramBalance` (`rpc/botapi_gram.go`) | bot -> recipient | `messageActionGiftTon` | TON gift box | GRAM card with the bot name, «<bot> отправил(а) вам N GRAM» |
| 18 | Partner Stars<->GRAM convert (`partner_convert.go`) | no message (own conversion) | - | - | - |
| 19 | Sale proceeds (Fragment market/auction/gift market, chain NFT sale) | bot/777000 text notices «Вам зачислено …» | none (text) | text | text (earnings, not grants; out of scope) |
| 20 | Channel revenue withdrawal, star-gift convert/sale, suggested posts, reactions | own ledger moves / star-gift actions | star gift actions / none | official | unchanged |

## Server change (branch feat/grant-cards, not deployed)

`internal/rpc/stars_giveaway_settlement.go`: each winner first gets a 777000
`messageActionGiftStars` (XTR, stars = prize, balance_after from the settlement),
random_id slot `-1-slot` (disjoint from the notices `0..n` and the results post
`-1`, stable on retry), then the existing congratulation text. Test:
`internal/rpc/stars_giveaway_prize_card_test.go`.

Deploy: core role only (ядро, blue-green `sg-deploy-bg.sh`), can wait for a batch.

Proposal (not implemented): admin `compensate` could post the same 777000
`messageActionGiftStars` card before its text notice (`AdminSendSystemNotice` is
text-only today; needs a service-message send in the admin ops store).

## Test

* Admin `grant-stars` 1 to a test account -> 777000 chat shows «Вам отправили ⭐ 1 Stars»
  + golden card «STATICGRAM», ribbon «получено»; tap -> Stars transaction sheet.
* Buy Stars for a friend -> buyer: «Вы отправили X N Stars» + card «–N», ribbon «отправлено».
* Bot `withdrawStarBalance` -> golden card; bot `withdrawGramBalance` -> GRAM card with the bot name.
* Admin `grant-gram` / partner TON credit -> GRAM card, fiat worth in the service line.
* sgcard / gramwallet transfers unchanged (comment caption, ribbon, receive animation).
* Old messages already in the local cache re-render (type is computed per MessageObject).

## Captions under grant cards (owner 08.11: «пусть будет подпись к карточке»)

Transport: none of messageActionGiftStars / PrizeStars / GiftTon has a text field
(checked in gramsrv third_party/td/tg and the client TLRPC). The only actions with a
text are our sgcard.messageActionStarsTransfer (comment) and messageActionGramTransfer
(comment). So gramsrv (branch feat/grant-card-captions) sends a grant that has a
caption as that transfer action, the caption as its comment, the transaction id
prefixed `sggrant:` (tlprofile.GrantTransferIDPrefix):

* clients with the transfer_actions feature (StaticGram Android >= 71629) draw the
  card + caption; `SgGrantCards.isGrantTransfer` keeps the grant look (777000 named
  «StaticGram» on the card, «Вам отправили N GRAM» line, receipt id without the prefix);
* every other client (iOS, tdesktop, Web, official, older Android) always gets the
  grant's own legacy card back (messageActionGiftStars / messageActionGiftTon, prefix
  stripped) regardless of TELESRV_SG_CLIENT_FEATURE_FALLBACK: never "update the app".
  A captioned partner Stars gift paid in fiat then shows "N Stars" instead of the
  fiat price on those clients.

The caption is stored on the message (gift_stars.caption / gift_ton.caption), so
only messages created after the deploy get it.

| Path | Caption |
|---|---|
| Admin grant-stars / grant-stars-all | `comment` of the request, else «Подарок от StaticGram» |
| Partner /stars/credit gift=true | `comment`, else «Подарок от <buyer_label>», else «Подарок» |
| Bot Stars withdrawal | «Вывод с бота @bot» |
| Giveaway prize | «Приз розыгрыша в канале «X»» / «в группе «X»» |
| Admin grant-gram | `comment`, else «Подарок от StaticGram» |
| Partner /ton/credit | `comment`, else «Пополнение GRAM» |
| Bot GRAM withdrawal | «Вывод с бота @bot» |
| Stars bought for a friend | none (the TL purchase input has no message) |
| Admin compensate | text notice only (not a card; proposal above) |

`comment` is new and optional (max 255 chars) on POST /v1/accounts/grant-stars,
/grant-stars-all, /grant-gram (and the telesrv-admin UI proxies), /v1/partner/stars/credit
and /v1/partner/ton/credit; it is fingerprinted only when set, so old orders replay as before.
