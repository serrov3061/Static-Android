package com.exteragram.messenger.badges;

import android.text.TextUtils;
import android.widget.FrameLayout;

import com.exteragram.messenger.api.ApiController;
import com.exteragram.messenger.api.db.ExteraDatabase;
import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.badges.source.ApiBadgeSource;
import com.exteragram.messenger.api.dto.ProfileDTO;
import com.exteragram.messenger.api.model.ProfileStatus;
import com.exteragram.messenger.components.SupporterBottomSheet;
import com.exteragram.messenger.components.supporter.SupporterEnterFrom;
import com.exteragram.messenger.components.supporter.SupporterPeerSheet;
import com.exteragram.messenger.utils.chats.ChatUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * exteraGram 12.10.6 BadgesController (Kotlin object), ported to Java with the same public
 * members (INSTANCE, instance methods, overloads) and the same private static field names
 * (apiBadgeSource, DEV_BADGE, SUPPORTER_BADGE, TRUSTED_BADGE), since Mandre modules use them by
 * reflection. StaticGram: the badge documents come from app config (sg_badges_*_document_id)
 * and the badge list from our server (see {@link ApiController}).
 */
public final class BadgesController {

    private static BadgeDTO DEV_BADGE;
    public static final BadgesController INSTANCE;
    private static BadgeDTO SUPPORTER_BADGE;
    private static BadgeDTO TRUSTED_BADGE;
    private static final ApiBadgeSource apiBadgeSource;
    private static final CachedRemoteSet trustedPluginsCache;
    private static final ConcurrentHashMap<Integer, Set<String>> allowedFrames = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long> lastFramesQuery = new ConcurrentHashMap<>();

    static {
        DEV_BADGE = new BadgeDTO(0, null);
        SUPPORTER_BADGE = new BadgeDTO(0, null);
        TRUSTED_BADGE = new BadgeDTO(0, null);
        INSTANCE = new BadgesController();
        trustedPluginsCache = new CachedRemoteSet("trusted_plugins", Collections.emptySet());
        ExteraDatabase database = ExteraDatabase.INSTANCE.getInstance();
        apiBadgeSource = new ApiBadgeSource(database.profileDao());
        database.getInvalidationTracker().addObserver(BadgesController::reloadCache);
        reloadCache();
    }

    private BadgesController() {
    }

    /** Reloads the in-memory cache from the database on the API queue and refreshes the UI. */
    private static void reloadCache() {
        ApiController.getQueue().postRunnable(() -> {
            try {
                apiBadgeSource.loadToCache(ApiController.CONTINUATION);
            } catch (Throwable t) {
                FileLog.e(t);
            }
            notifyBadgesChanged();
            // exteraGram 13.1.1: the welcome sheet for a new supporter (BadgesController$1 / onProfilesSynced)
            AndroidUtilities.runOnUIThread(SupporterWelcomeController::check);
        });
    }

    /**
     * exteraGram 13.1.1 onProfilesSynced(profiles): exteraGram applies the synced rows to the cache, refreshes the
     * UI and checks the welcome sheet. StaticGram's sync writes the table and the invalidation tracker reloads the
     * cache (reloadCache does the same steps), so this only triggers that reload.
     */
    public final void onProfilesSynced(List<ProfileDTO> profiles) {
        reloadCache();
    }

    /** Asks every active account's UI to redraw names/statuses after the badge list changed. */
    public static void notifyBadgesChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
                }
            }
        });
    }

    private static BadgeDTO configBadge(BadgeDTO current, long documentId) {
        if (current == null || current.getDocumentId() != documentId) {
            return new BadgeDTO(documentId, null);
        }
        return current;
    }

    public final BadgeDTO getDEV_BADGE() {
        return DEV_BADGE = configBadge(DEV_BADGE, BadgesConfig.getDeveloperDocumentId());
    }

    public final BadgeDTO getSUPPORTER_BADGE() {
        return SUPPORTER_BADGE = configBadge(SUPPORTER_BADGE, BadgesConfig.getDefaultDocumentId());
    }

    public final BadgeDTO getTRUSTED_BADGE() {
        return TRUSTED_BADGE = configBadge(TRUSTED_BADGE, BadgesConfig.getTrustedDocumentId());
    }

    private static TLRPC.User currentUser() {
        return UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
    }

    public final BadgeDTO getBadge() {
        return getBadge(currentUser());
    }

    public static BadgeDTO getBadge$default(BadgesController controller, TLObject obj, int mask, Object unused) {
        if ((mask & 1) != 0) {
            obj = currentUser();
        }
        return controller.getBadge(obj);
    }

    public final BadgeDTO getBadge(TLObject obj) {
        try {
            long id;
            boolean isUser;
            if (obj instanceof TLRPC.User) {
                id = ((TLRPC.User) obj).id;
                isUser = true;
            } else if (obj instanceof TLRPC.Chat) {
                id = ((TLRPC.Chat) obj).id;
                isUser = false;
            } else {
                return null;
            }
            if (!isUser && isTrusted(id)) {
                BadgeDTO trusted = getTRUSTED_BADGE();
                return trusted.getDocumentId() != 0 ? trusted : null;
            }
            BadgeDTO badge = apiBadgeSource.getBadge(id, isUser);
            if (badge != null && badge.getDocumentId() != 0) {
                return badge;
            }
            return null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * StaticGram: the real badge, or (when there is none) a synthetic one carrying the peer's
     * bot_verification_icon if it is listed in sg_right_verification_icons, so it is drawn in the right badge slot.
     */
    public final BadgeDTO getBadgeOrMark(TLObject obj) {
        BadgeDTO badge = getBadge(obj);
        if (badge != null) {
            return badge;
        }
        long icon = org.telegram.messenger.DialogObject.getRightVerificationIcon(obj);
        return icon != 0 ? new BadgeDTO(icon, null) : null;
    }

    public final boolean hasBadge() {
        return hasBadge(currentUser());
    }

    public static boolean hasBadge$default(BadgesController controller, TLObject obj, int mask, Object unused) {
        if ((mask & 1) != 0) {
            obj = currentUser();
        }
        return controller.hasBadge(obj);
    }

    public final boolean hasBadge(TLObject obj) {
        return getBadge(obj) != null;
    }

    /**
     * StaticGram: a channel is a trusted plugin source when it is listed in app config
     * (sg_trusted_plugin_channels), is the legacy exteraGram channel, or is in the remote trusted set.
     * channelId is the positive bare channel id.
     */
    public static boolean isTrustedPluginChannel(long channelId) {
        if (channelId < 0) channelId = -channelId;
        if (channelId == 2562664432L) return true; // @exteraPlugins
        try {
            if (org.telegram.messenger.AppGlobalConfig.getInstance(org.telegram.messenger.UserConfig.selectedAccount).sgTrustedPluginChannels.contains(channelId)) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        try {
            return trustedPluginsCache.contains(channelId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** exteraGram 13.1.1: SUPPORTER or DEVELOPER status (a paid / service badge holder, not a trusted channel). */
    public final boolean isSupporter(long id) {
        ProfileStatus status = apiBadgeSource.getStatus(id);
        return status == ProfileStatus.SUPPORTER || status == ProfileStatus.DEVELOPER;
    }

    /** exteraGram 13.1.1: the badge number of a supporter, or null (no number, number &lt;= 0, or not a supporter). */
    public final Integer getNumber(long id) {
        Integer number = apiBadgeSource.getNumber(id);
        if (number == null || number <= 0 || !INSTANCE.isSupporter(id)) {
            return null;
        }
        return number;
    }

    public static Integer getNumber(TLObject peer) {
        Long id = INSTANCE.peerId(peer);
        return id != null ? INSTANCE.getNumber(id) : null;
    }

    /** exteraGram 13.1.1: "#N". */
    public static String formatNumber(int number) {
        return "#" + number;
    }

    /**
     * exteraGram 13.1.1: a badge caption for display: {name} becomes the peer name, then **bold**, [links](url)
     * and @usernames are formatted (LocaleUtils.fullyFormatText); onClick runs before a link opens.
     */
    public static CharSequence formatBadgeCaption(String text, String name, BaseFragment fragment, Runnable onClick) {
        if (name == null) {
            name = "";
        }
        return LocaleUtils.fullyFormatText(text.replace("{name}", name), fragment, onClick);
    }

    private Long peerId(TLObject peer) {
        if (peer instanceof TLRPC.User) {
            return ((TLRPC.User) peer).id;
        }
        if (peer instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) peer).id;
        }
        return null;
    }

    /** exteraGram 13.1.1 getDefaultBadge(peer): the developer or standard document of a user or chat; null until configured. */
    public final BadgeDTO getDefaultBadge(TLObject peer) {
        if (peer instanceof TLRPC.User) {
            return getDefaultBadge((TLRPC.User) peer);
        }
        Long id = peerId(peer);
        if (id == null) {
            return null;
        }
        BadgeDTO badge = apiBadgeSource.isDeveloper(id) ? getDEV_BADGE() : getSUPPORTER_BADGE();
        return badge.getDocumentId() != 0 ? badge : null;
    }

    /** exteraGram 13.1.1: the badge document when it differs from the default one (a chosen emoji), else 0. */
    public final long getCustomBadgeId(TLObject peer) {
        if (peer == null) {
            return 0;
        }
        BadgeDTO badge = getBadge(peer);
        BadgeDTO defaultBadge = getDefaultBadge(peer);
        if (badge == null || defaultBadge == null || badge.getDocumentId() == defaultBadge.getDocumentId()) {
            return 0;
        }
        return badge.getDocumentId();
    }

    /**
     * exteraGram 13.1.1: true when the badge is not the plain numbered supporter arrow, i.e. a non-supporter badge
     * (trusted / service) or a supporter with a chosen emoji. Developers are false (they get their own bulletin).
     */
    public final boolean hasCustomBadge(TLObject peer) {
        Long id = peerId(peer);
        if (id == null) {
            return false;
        }
        return !apiBadgeSource.isDeveloper(id) && (!isSupporter(id) || getCustomBadgeId(peer) != 0);
    }

    public final void openProfileBadge(BaseFragment fragment, TLRPC.User user, Theme.ResourcesProvider resourcesProvider) {
        openProfileBadge(fragment, user, resourcesProvider, null);
    }

    /**
     * exteraGram 13.1.1: a tap on a user's badge.
     * <ul>
     * <li>own supporter badge: «Your Perks» (SupporterWelcomeSheet, the badge flies in from enterFrom);</li>
     * <li>developer or custom/non-supporter badge: the badge bulletin (caption or «X supported StaticGram»), with
     * «Details» opening the read-only supporter sheet for non-developers;</li>
     * <li>plain numbered supporter badge: the read-only supporter sheet (SupporterPeerSheet) directly.</li>
     * </ul>
     */
    public final void openProfileBadge(BaseFragment fragment, TLRPC.User user, Theme.ResourcesProvider resourcesProvider, SupporterEnterFrom enterFrom) {
        if (fragment == null || user == null) {
            return;
        }
        if (getBadge(user) == null && org.telegram.messenger.DialogObject.getRightVerificationIcon(user) != 0) {
            return; // StaticGram: partner verification mark, not a supporter badge
        }
        boolean developer = isDeveloper(user);
        if (!developer && user.id == UserConfig.getInstance(fragment.getCurrentAccount()).clientUserId && isSupporter(user.id)) {
            SupporterBottomSheet.showAlert(fragment, null, resourcesProvider, enterFrom);
            return;
        }
        if (developer || hasCustomBadge(user)) {
            BadgeDTO badge = getBadge(user);
            if (badge == null) {
                return;
            }
            CharSequence text = formatBadgeText(badge, UserObject.getUserName(user), LocaleController.formatString(developer ? R.string.Developer : R.string.Supporter, user.first_name));
            Runnable onButton = developer ? null : () -> SupporterPeerSheet.show(fragment, user, resourcesProvider, null);
            showBadgeBulletin(fragment, badge, text, onButton, resourcesProvider, fragment.getCurrentAccount(), null);
            return;
        }
        SupporterBottomSheet.showAlert(fragment, user, resourcesProvider, enterFrom);
    }

    public final boolean isTrusted(long id) {
        return trustedPluginsCache.contains(id);
    }

    public final boolean isExtera(long id) {
        return apiBadgeSource.isDeveloper(id);
    }

    public final boolean isExtera(TLRPC.Chat chat) {
        return chat != null && INSTANCE.isExtera(chat.id);
    }

    /** The badge offered by default in the picker: developer or standard (supporter) document; null until configured. */
    public final BadgeDTO getDefaultBadge() {
        BadgeDTO badge = apiBadgeSource.isDeveloper(UserConfig.getInstance(UserConfig.selectedAccount).clientUserId) ? getDEV_BADGE() : getSUPPORTER_BADGE();
        return badge.getDocumentId() != 0 ? badge : null;
    }

    public final BadgeDTO getDefaultBadge(TLRPC.User user) {
        if (user == null) {
            return null;
        }
        BadgeDTO badge = isDeveloper(user) ? getDEV_BADGE() : getSUPPORTER_BADGE();
        return badge.getDocumentId() != 0 ? badge : null;
    }

    public final boolean shouldUseSecondaryBadgeSlot(TLRPC.User user, BadgeDTO badge) {
        return user != null && badge != null && canChangeBadge(user) && (!Objects.equals(badge, getDefaultBadge(user)) || isDeveloper(user));
    }

    public final BadgeDTO getSecondaryBadge(TLRPC.User user) {
        BadgeDTO badge = getBadge(user);
        if (badge != null && INSTANCE.shouldUseSecondaryBadgeSlot(user, badge)) {
            return badge;
        }
        return null;
    }

    public final boolean canChangeBadge() {
        return canChangeBadge(currentUser());
    }

    public static boolean canChangeBadge$default(BadgesController controller, TLRPC.User user, int mask, Object unused) {
        if ((mask & 1) != 0) {
            user = currentUser();
        }
        return controller.canChangeBadge(user);
    }

    public final boolean canChangeBadge(TLRPC.User user) {
        return user != null && apiBadgeSource.canChangeBadge(user.id);
    }

    /**
     * Sends "badge &lt;documentId&gt; [text]" as an inline query to the badge bot (sg_badges_bot);
     * the bot answers "ok" on success, then the badge is updated locally and a sync is forced
     * (the server stores its own copy of the emoji document).
     */
    public final void updateBadge(BadgeDTO badge, Consumer<String> callback) {
        if (badge == null) {
            callback.accept(null);
            return;
        }
        String text = badge.getText();
        String query = text == null || text.length() == 0
                ? "badge " + badge.getDocumentId()
                : "badge " + badge.getDocumentId() + " " + text;
        ChatUtils.getInstance(UserConfig.selectedAccount).sendBotRequest(query, false, result -> {
            if ("ok".equals(result)) {
                long selfId = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId;
                ApiController.getQueue().postRunnable(() -> {
                    try {
                        apiBadgeSource.updateLocalBadge(selfId, badge, ApiController.CONTINUATION);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                    notifyBadgesChanged();
                });
                ApiController.sync(null, true);
            }
            callback.accept(result);
        });
    }

    /** StaticGram: the avatar frame style of a user, or null (see {@link AvatarFrames}). */
    public final String getFrame(long userId) {
        try {
            return apiBadgeSource.getFrame(userId);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** StaticGram: the profile banner {documentId, kind} of a user, or null (see {@link ProfileBanners}). */
    public final String[] getBanner(long userId) {
        try {
            return apiBadgeSource.getBanner(userId);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Sends "banner &lt;documentId&gt; &lt;accessHash&gt;" ("banner none" when documentId is 0) to the badge
     * bot; on "ok" the banner is applied locally and a sync is forced. kind is "photo" or "video".
     */
    public final void updateBanner(long documentId, long accessHash, String kind, Consumer<String> callback) {
        String command = documentId == 0 ? "banner none" : "banner " + documentId + " " + accessHash;
        ChatUtils.getInstance(UserConfig.selectedAccount).sendBotRequest(command, false, result -> {
            if ("ok".equals(result)) {
                long selfId = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId;
                ApiController.getQueue().postRunnable(() -> {
                    try {
                        apiBadgeSource.updateLocalBanner(selfId, documentId == 0 ? null : String.valueOf(documentId), kind);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                    notifyBadgesChanged();
                });
                ApiController.sync(null, true);
            }
            callback.accept(result);
        });
    }

    /** StaticGram: the avatar frame settings of a user (never null; empty = defaults). */
    public final java.util.Map<String, String> getFrameParams(long userId) {
        try {
            return apiBadgeSource.getFrameParams(userId);
        } catch (Exception e) {
            FileLog.e(e);
            return Collections.emptyMap();
        }
    }

    /**
     * Any user badge (standard, custom, developer) includes every frame style. This is the single
     * gate for frames (badge screen button, frame screen, picker): when the badges API gets a
     * "frame granted without badge" flag (admin grant), add it here, e.g. {@code || hasFrameGrant(user)}.
     */
    public final boolean canUseFrames(TLRPC.User user) {
        if (user == null || user.bot) {
            return false;
        }
        Set<String> allowed = allowedFramesOf(user);
        return allowed != null ? !allowed.isEmpty() : hasBadge(user);
    }

    /**
     * All styles come with any badge or an admin frame grant (owner, 27.10: the frame is included in
     * the badge price). The bot's "frames" answer still decides for the current user.
     */
    public final boolean canUseFrame(TLRPC.User user, String style) {
        if (!canUseFrames(user)) {
            return false;
        }
        Set<String> allowed = allowedFramesOf(user);
        return allowed == null || allowed.contains(style);
    }

    /** Allowed frame styles of the current account from the bot, or null until the first answer (or for other users). */
    private Set<String> allowedFramesOf(TLRPC.User user) {
        int account = UserConfig.selectedAccount;
        if (user.id != UserConfig.getInstance(account).clientUserId) {
            return null;
        }
        return allowedFrames.get(account);
    }

    /** True once the bot has answered the "frames" query for the current account. */
    public final boolean isAllowedFramesKnown() {
        return allowedFrames.containsKey(UserConfig.selectedAccount);
    }

    /**
     * Fetches the styles the current user may pick ("frames" inline query to the badge bot: comma-separated
     * styles, or "error: a frame comes with a badge" = none). Called when the frame screen opens and after
     * each badges sync. A failed query keeps the previous answer. onDone runs on the UI thread.
     */
    public final void refreshAllowedFrames(Runnable onDone) {
        final int account = UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()) {
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = lastFramesQuery.get(account);
        if (last != null && now - last < 5000 && onDone == null) {
            return;
        }
        lastFramesQuery.put(account, now);
        try {
            ChatUtils.getInstance(account).sendBotRequest("frames", false, result -> {
                if (result != null) {
                    String text = result.trim();
                    if (text.startsWith("error")) {
                        allowedFrames.put(account, Collections.emptySet());
                    } else {
                        Set<String> set = new HashSet<>();
                        for (String s : Arrays.asList(text.split(","))) {
                            String style = s.trim();
                            if (AvatarFrames.isKnown(style)) {
                                set.add(style);
                            }
                        }
                        allowedFrames.put(account, set);
                    }
                    notifyBadgesChanged();
                }
                if (onDone != null) {
                    AndroidUtilities.runOnUIThread(onDone);
                }
            });
        } catch (Throwable t) {
            FileLog.e(t);
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        }
    }

    /**
     * Sends "frame &lt;style&gt; key=value ..." (only non-default settings; "frame none" clears) to the
     * badge bot; on "ok" the frame is applied locally and a sync is forced, like {@link #updateBadge}.
     */
    public final void updateFrame(String style, java.util.Map<String, String> params, Consumer<String> callback) {
        String frame = AvatarFrames.isKnown(style) ? style : null;
        java.util.Map<String, String> values = frame == null ? Collections.emptyMap() : FrameParams.nonDefault(frame, params);
        ChatUtils.getInstance(UserConfig.selectedAccount).sendBotRequest(FrameParams.toCommand(frame, values), false, result -> {
            if ("ok".equals(result)) {
                long selfId = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId;
                ApiController.getQueue().postRunnable(() -> {
                    try {
                        apiBadgeSource.updateLocalFrame(selfId, frame, values);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                    notifyBadgesChanged();
                });
                ApiController.sync(null, true);
            }
            callback.accept(result);
        });
    }

    public final boolean isDeveloper() {
        return isDeveloper(currentUser());
    }

    public static boolean isDeveloper$default(BadgesController controller, TLRPC.User user, int mask, Object unused) {
        if ((mask & 1) != 0) {
            user = currentUser();
        }
        return controller.isDeveloper(user);
    }

    public final boolean isDeveloper(TLRPC.User user) {
        return user != null && apiBadgeSource.isDeveloper(user.id);
    }

    public final void showBadgeBulletin(BaseFragment fragment, TLRPC.User user, Theme.ResourcesProvider resourcesProvider, int account) {
        showBadgeBulletin(fragment, user, resourcesProvider, account, null, null);
    }

    public final void showBadgeBulletin(BaseFragment fragment, TLRPC.User user, Theme.ResourcesProvider resourcesProvider, int account, FrameLayout containerLayout, Boolean showButton) {
        BadgeDTO badge;
        if (user == null || (badge = getBadge(user)) == null) {
            return;
        }
        showBadgeBulletin(fragment, badge, user, resourcesProvider, account, containerLayout, showButton);
    }

    public static void showBadgeBulletin$default(BadgesController controller, BaseFragment fragment, BadgeDTO badge, TLRPC.User user, Theme.ResourcesProvider resourcesProvider, int account, FrameLayout containerLayout, Boolean showButton, int mask, Object unused) {
        if ((mask & 8) != 0) {
            resourcesProvider = fragment.getResourceProvider();
        }
        if ((mask & 16) != 0) {
            account = fragment.getCurrentAccount();
        }
        controller.showBadgeBulletin(fragment, badge, user, resourcesProvider, account, (mask & 32) != 0 ? null : containerLayout, (mask & 64) != 0 ? null : showButton);
    }

    public final void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.User user, Theme.ResourcesProvider resourcesProvider, int account) {
        showBadgeBulletin(fragment, badge, user, resourcesProvider, account, null, null);
    }

    public final void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.User user, Theme.ResourcesProvider resourcesProvider, int account, FrameLayout containerLayout, Boolean showButton) {
        if (user == null || badge == null) {
            return;
        }
        boolean developer = isDeveloper(user);
        CharSequence badgeText = formatBadgeText(badge, LocaleController.formatString(developer ? R.string.Developer : R.string.Supporter, user.first_name));
        boolean button = showButton != null ? showButton : !developer && !canChangeBadge(user);
        showBadgeBulletin(fragment, badge, badgeText, button, resourcesProvider, account, containerLayout);
    }

    public static void showBadgeBulletin$default(BadgesController controller, BaseFragment fragment, BadgeDTO badge, TLRPC.Chat chat, Theme.ResourcesProvider resourcesProvider, int account, FrameLayout containerLayout, Boolean showButton, int mask, Object unused) {
        if ((mask & 8) != 0) {
            resourcesProvider = fragment.getResourceProvider();
        }
        if ((mask & 16) != 0) {
            account = fragment.getCurrentAccount();
        }
        controller.showBadgeBulletin(fragment, badge, chat, resourcesProvider, account, (mask & 32) != 0 ? null : containerLayout, (mask & 64) != 0 ? null : showButton);
    }

    public final void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.Chat chat, Theme.ResourcesProvider resourcesProvider, int account) {
        showBadgeBulletin(fragment, badge, chat, resourcesProvider, account, null, null);
    }

    public final void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.Chat chat, Theme.ResourcesProvider resourcesProvider, int account, FrameLayout containerLayout, Boolean showButton) {
        if (chat == null || badge == null) {
            return;
        }
        boolean extera = isExtera(chat);
        boolean trusted = isTrusted(chat.id);
        String text;
        if (extera) {
            text = LocaleController.formatString(R.string.OfficialChannel, chat.title);
        } else if (trusted) {
            text = LocaleController.formatString(R.string.ExteraBadgeTrusted, chat.title);
        } else {
            text = LocaleController.formatString(R.string.Supporter, chat.title);
        }
        CharSequence badgeText = formatBadgeText(badge, text);
        boolean button = showButton != null ? showButton : !extera && !trusted;
        showBadgeBulletin(fragment, badge, badgeText, button, resourcesProvider, account, containerLayout);
    }

    private void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, CharSequence text, boolean showButton, Theme.ResourcesProvider resourcesProvider, int account, FrameLayout containerLayout) {
        BulletinFactory factory = containerLayout != null ? BulletinFactory.of(containerLayout, resourcesProvider) : BulletinFactory.of(fragment);
        if (factory == null) {
            return;
        }
        TLRPC.Document cached = AnimatedEmojiDrawable.findDocument(account, badge.getDocumentId());
        if (cached == null && badge.getDocumentId() != 0) {
            // custom emoji is not cached yet: fetch it first, otherwise the bulletin has no icon
            AnimatedEmojiDrawable.getDocumentFetcher(account).fetchDocument(badge.getDocumentId(), fetched -> AndroidUtilities.runOnUIThread(() -> {
                showBadgeBulletinInternal(factory, fragment, fetched, text, showButton, resourcesProvider);
            }));
            return;
        }
        showBadgeBulletinInternal(factory, fragment, cached, text, showButton, resourcesProvider);
    }

    /** exteraGram 13.1.1 showBadgeBulletin(fragment, badge, text, ..., onButton): «Details» runs onButton when given. */
    private void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, CharSequence text, Runnable onButton, Theme.ResourcesProvider resourcesProvider, int account, FrameLayout containerLayout) {
        BulletinFactory factory = containerLayout != null ? BulletinFactory.of(containerLayout, resourcesProvider) : BulletinFactory.of(fragment);
        if (factory == null) {
            return;
        }
        Utilities.Callback<TLRPC.Document> show = document -> {
            Bulletin bulletin = factory.createEmojiBulletin(document, text, onButton != null ? LocaleController.getString(R.string.ExteraBadgeDetails) : null, onButton);
            if (onButton == null) {
                bulletin.wrapContent();
            }
            bulletin.show();
        };
        TLRPC.Document cached = AnimatedEmojiDrawable.findDocument(account, badge.getDocumentId());
        if (cached == null && badge.getDocumentId() != 0) {
            AnimatedEmojiDrawable.getDocumentFetcher(account).fetchDocument(badge.getDocumentId(), fetched -> AndroidUtilities.runOnUIThread(() -> show.run(fetched)));
            return;
        }
        show.run(cached);
    }

    /** exteraGram 13.1.1 formatBadgeText(badge, name, fallback): the caption with {name}, else the fallback text. */
    private CharSequence formatBadgeText(BadgeDTO badge, String name, CharSequence fallbackText) {
        String text = badge.getText();
        if (TextUtils.isEmpty(text)) {
            return AndroidUtilities.replaceTags(fallbackText.toString());
        }
        return formatBadgeCaption(text, name, null, null);
    }

    private void showBadgeBulletinInternal(BulletinFactory factory, BaseFragment fragment, TLRPC.Document document, CharSequence text, boolean showButton, Theme.ResourcesProvider resourcesProvider) {
        Bulletin bulletin = factory.createEmojiBulletin(
                document,
                text,
                showButton ? LocaleController.getString(R.string.ExteraBadgeDetails) : null,
                showButton ? () -> SupporterBottomSheet.showAlert(fragment, resourcesProvider) : null
        );
        if (!showButton) {
            bulletin.wrapContent();
        }
        bulletin.show();
    }

    private CharSequence formatBadgeText(BadgeDTO badge, CharSequence fallbackText) {
        String text = badge.getText();
        if (text == null) {
            return AndroidUtilities.replaceTags(fallbackText.toString());
        }
        CharSequence withUsernames = LocaleUtils.formatWithUsernames(text);
        return withUsernames != null ? withUsernames : AndroidUtilities.replaceTags(fallbackText.toString());
    }
}
