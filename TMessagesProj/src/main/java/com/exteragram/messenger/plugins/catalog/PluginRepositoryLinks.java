package com.exteragram.messenger.plugins.catalog;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import androidx.collection.LongSparseArray;

import com.exteragram.messenger.plugins.catalog.db.PluginRepositoryEntity;
import com.exteragram.messenger.plugins.catalog.ui.PluginCatalogActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ShareAlert;

/**
 * Repository links (exteraGram 13.1.1 catalog/PluginRepositoryLinks).
 * exteraGram: https://t.me/exteraPluginRepo?git=<url> (and ?c=<channel>).
 * StaticGram: https://staticgram.top/staticPluginRepo?git=<url> or sg://staticPluginRepo?git=<url>;
 * exteraGram's t.me links are still accepted by IntentsController. Channel links (?c=) are not supported.
 */
public final class PluginRepositoryLinks {

    public static final String LINK_PATH = "staticPluginRepo";
    public static final String EXTERA_LINK_PATH = "exteraPluginRepo";

    private PluginRepositoryLinks() {
    }

    public static String buildGit(String url) {
        return "https://staticgram.top/" + LINK_PATH + "?git=" + Uri.encode(url);
    }

    public static String build(PluginRepositoryEntity repository) {
        return repository != null && repository.getUrl() != null ? buildGit(repository.getUrl()) : null;
    }

    /** Opens the catalog of an index repository, asking to add it first when it is not in the list. */
    public static void openGit(BaseFragment fragment, String input) {
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null) {
            return;
        }
        String url = GitPluginRepository.normalizeUrl(input);
        if (url == null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PluginCatalogGitLoadFailed)).show();
            return;
        }
        PluginCatalogController.getInstance(fragment.getCurrentAccount()).loadRepositories(repositories -> {
            for (PluginRepositoryEntity repository : repositories) {
                if (url.equals(repository.getUrl())) {
                    fragment.presentFragment(new PluginCatalogActivity(repository.getRepositoryId()));
                    return;
                }
            }
            fragment.showDialog(new AlertDialog.Builder(activity, fragment.getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.PluginCatalogAddRepository))
                    .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginCatalogAddRepositoryConfirm, url.startsWith("https://") ? url.substring(8) : url)))
                    .setPositiveButton(LocaleController.getString(R.string.Add), (dialog, which) -> addGit(fragment, url,
                            repository -> fragment.presentFragment(new PluginCatalogActivity(repository.getRepositoryId()))))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create());
        });
    }

    public static void addGit(BaseFragment fragment, String url, Utilities.Callback<PluginRepositoryEntity> onAdded) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        PluginCatalogController.getInstance(fragment.getCurrentAccount()).addGitRepository(url, (code, repository) -> {
            progress.dismiss();
            if (fragment.fragmentView == null) {
                return;
            }
            if (repository != null) {
                onAdded.run(repository);
                return;
            }
            int error;
            if (code == PluginCatalogController.ADD_EXISTS) {
                error = R.string.PluginCatalogRepositoryExists;
            } else if (code == PluginCatalogController.ADD_UNSUPPORTED) {
                error = R.string.PluginCatalogGitUnsupported;
            } else {
                error = R.string.PluginCatalogGitLoadFailed;
            }
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(error)).show();
        });
    }

    /** exteraGram BasePreferencesActivity.showShareLinkAlert with the repository strings. */
    public static void share(BaseFragment fragment, String link) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        fragment.showDialog(new ShareAlert(context, null, link, false, link, false, fragment.getResourceProvider()) {
            @Override
            protected void onSend(LongSparseArray<TLRPC.Dialog> dids, int count, TLRPC.TL_forumTopic topic, boolean showToast) {
                if (!showToast) {
                    return;
                }
                CharSequence text;
                if (dids != null && dids.size() == 1) {
                    long did = dids.valueAt(0).id;
                    if (did == 0 || did == UserConfig.getInstance(fragment.getCurrentAccount()).getClientUserId()) {
                        text = AndroidUtilities.replaceTags(LocaleController.getString(R.string.PluginRepositoryLinkToSavedMessages));
                    } else {
                        text = AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginRepositoryLinkToUser, fragment.getMessagesController().getPeerName(did, true)));
                    }
                } else {
                    text = AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginRepositoryLinkToChats, LocaleController.formatPluralString("Chats", count)));
                }
                Bulletin bulletin = BulletinFactory.of(fragment).createSimpleBulletin(R.raw.forward, text);
                bulletin.hideAfterBottomSheet = false;
                bulletin.show(true);
            }
        });
    }
}
