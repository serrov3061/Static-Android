package com.exteragram.messenger.plugins.catalog.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;

import com.exteragram.messenger.plugins.catalog.GitPluginRepository;
import com.exteragram.messenger.plugins.catalog.PluginCatalogController;
import com.exteragram.messenger.plugins.catalog.PluginRepositoryLinks;
import com.exteragram.messenger.plugins.catalog.db.PluginRepositoryEntity;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Plugin catalog home (exteraGram 13.1.1 catalog/ui/PluginRepositoriesActivity): the built-in StaticGram
 * catalog under "Verified" (its channel repositories from sources.json, see PluginCatalogController) and the
 * index repositories the user added under "Your Repositories".
 * exteraGram's channel picker / channel link options are not ported (no channel repositories).
 */
public final class PluginRepositoriesActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ADD_REPOSITORY_ID = 1;
    private static final int FLICKER_ID_START = 100;
    private static final long HEADER_EMOJI_DOCUMENT_ID = 5431499171045581032L;

    private List<PluginRepositoryEntity> repositories = Collections.emptyList();
    private final HashSet<Long> syncRequested = new HashSet<>();
    private boolean loaded;

    private PluginCatalogController getController() {
        return PluginCatalogController.getInstance(currentAccount);
    }

    @Override
    public boolean needHideTitle() {
        return true;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.PluginCatalog);
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.pluginCatalogUpdated);
        reloadRepositories();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.pluginCatalogUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.pluginCatalogUpdated || args.length == 0 || !(args[0] instanceof Long)) {
            return;
        }
        long repositoryId = (Long) args[0];
        if (repositoryId == PluginCatalogController.ALL_REPOSITORIES) {
            reloadRepositories();
            return;
        }
        for (PluginRepositoryEntity repository : repositories) {
            if (repository.getRepositoryId() == repositoryId) {
                updateList();
                return;
            }
        }
    }

    private void reloadRepositories() {
        getController().loadRepositories(list -> {
            repositories = list;
            loaded = true;
            for (PluginRepositoryEntity repository : list) {
                if (syncRequested.add(repository.getRepositoryId())) {
                    getController().syncRepository(repository.getRepositoryId(), false);
                }
            }
            updateList();
        });
    }

    private void updateList() {
        if (fragmentView != null && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        ArrayList<PluginRepositoryEntity> verified = new ArrayList<>();
        ArrayList<PluginRepositoryEntity> own = new ArrayList<>();
        boolean hideDefault = getController().isDefaultRepositoryHidden();
        for (PluginRepositoryEntity repository : repositories) {
            if (hideDefault && repository.getRepositoryId() == PluginCatalogController.DEFAULT_REPOSITORY_ID) {
                continue;
            }
            if (repository.getSource() == PluginRepositoryEntity.SOURCE_FOLDER) {
                verified.add(repository);
            } else if (repository.getSource() == PluginRepositoryEntity.SOURCE_USER) {
                own.add(repository);
            }
        }
        Collections.sort(own, (a, b) -> Integer.compare(b.getPluginsCount(), a.getPluginsCount()));

        items.add(UItem.asTopViewEmojiDocument(getTitle(), LocaleController.getString(R.string.PluginCatalogInfo), HEADER_EMOJI_DOCUMENT_ID));
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.PluginCatalogVerified)));
        if (verified.isEmpty() || !loaded) {
            items.add(UItem.asFlicker(FLICKER_ID_START, 6));
        } else {
            for (PluginRepositoryEntity repository : verified) {
                items.add(CatalogRepositoryCell.Factory.of(repository, getSubtitle(repository)));
            }
        }
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.PluginCatalogYourRepositories)));
        for (PluginRepositoryEntity repository : own) {
            items.add(CatalogRepositoryCell.Factory.of(repository, getSubtitle(repository)));
        }
        UItem add = UItem.asButton(ADD_REPOSITORY_ID, R.drawable.msg_add, LocaleController.getString(R.string.PluginCatalogAddRepository)).accent();
        items.add(add);
        items.add(UItem.asShadow(AndroidUtilities.replaceTags(LocaleController.getString(R.string.PluginCatalogRepositoriesInfo))));
    }

    private CharSequence getSubtitle(PluginRepositoryEntity repository) {
        // a source of sources.json shows its server count until its own index is loaded
        if (repository.getSyncedAt() != 0 || repository.getPluginsCount() > 0) {
            return LocaleController.formatPluralString("PluginCatalogPlugins", repository.getPluginsCount());
        }
        return LocaleController.getString(getController().isSyncing(repository.getRepositoryId()) ? R.string.PluginCatalogUpdating : R.string.PluginCatalogNotLoaded);
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ADD_REPOSITORY_ID) {
            showLinkDialog(LocaleController.getString(R.string.PluginCatalogGitLinkHint), input -> {
                String url = GitPluginRepository.normalizeUrl(input);
                if (url == null) {
                    return null;
                }
                addGitRepository(url);
                return url;
            });
            return;
        }
        if (item.object instanceof PluginRepositoryEntity) {
            presentFragment(new PluginCatalogActivity(((PluginRepositoryEntity) item.object).getRepositoryId()));
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof PluginRepositoryEntity)) {
            return false;
        }
        PluginRepositoryEntity repository = (PluginRepositoryEntity) item.object;
        ItemOptions options = ItemOptions.makeOptions(this, view);
        String webUrl = GitPluginRepository.getWebUrl(repository.getUrl(), repository.getHomepage());
        String channel = repository.getUsername();
        if (!TextUtils.isEmpty(channel)) {
            options.add(R.drawable.msg_channel, LocaleController.getString(R.string.OpenChannel2), () -> getMessagesController().openByUserName(channel, this, 0));
            if (webUrl != null && webUrl.equalsIgnoreCase("https://t.me/" + channel)) {
                webUrl = null;
            }
        }
        if (webUrl != null) {
            String openUrl = webUrl;
            options.add(R.drawable.msg_openin, LocaleController.getString(R.string.PluginCatalogOpenRepository), () -> Browser.openUrl(getParentActivity(), openUrl));
        }
        String link = PluginRepositoryLinks.build(repository);
        if (link != null) {
            options.add(R.drawable.msg_share, LocaleController.getString(R.string.ShareLink), () -> PluginRepositoryLinks.share(this, link));
            options.add(R.drawable.msg_copy, LocaleController.getString(R.string.CopyLink), () -> {
                if (AndroidUtilities.addToClipboard(link)) {
                    BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.LinkCopied)).show();
                }
            });
        }
        if (repository.getSource() == PluginRepositoryEntity.SOURCE_USER) {
            options.add(R.drawable.msg_delete, LocaleController.getString(R.string.PluginCatalogRemoveRepository), true, () -> removeRepository(repository));
        }
        options.setScrimViewBackground(listView.getClipBackground(view));
        options.setGravity(LocaleController.isRTL ? 3 : 5);
        options.setDismissOnMoveOutside(true);
        options.show();
        return true;
    }

    /** onSubmit returns null when the input is invalid (the field shakes). */
    private void showLinkDialog(String hint, Utilities.CallbackReturn<String, String> onSubmit) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        EditTextBoldCursor editText = new EditTextBoldCursor(activity);
        editText.lineYFix = true;
        editText.setTextSize(1, 18.0f);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        editText.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText, resourceProvider));
        editText.setHintText(hint);
        editText.setFocusable(true);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        editText.setBackground(null);
        int activated = Theme.key_windowBackgroundWhiteInputFieldActivated;
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourceProvider), Theme.getColor(activated, resourceProvider), Theme.getColor(Theme.key_text_RedRegular, resourceProvider));
        editText.setCursorColor(Theme.getColor(activated, resourceProvider));
        editText.setPadding(0, AndroidUtilities.dp(8.0f), 0, AndroidUtilities.dp(8.0f));
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(editText, LayoutHelper.createLinear(-1, -2, 24, 0, 24, 12));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
        builder.setTitle(LocaleController.getString(R.string.PluginCatalogAddRepository));
        builder.setView(layout);
        builder.setWidth(AndroidUtilities.dp(292.0f));
        builder.setPositiveButton(LocaleController.getString(R.string.Add), (dialog, which) -> {
            Editable text = editText.getText();
            if (onSubmit.run(text != null ? text.toString() : null) == null) {
                AndroidUtilities.shakeView(editText);
            } else {
                dialog.dismiss();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> AndroidUtilities.hideKeyboard(editText));
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        dialog.setDismissDialogByButtons(false);
        showDialog(dialog);
    }

    private void addGitRepository(String url) {
        PluginRepositoryLinks.addGit(this, url, repository -> {
            syncRequested.add(repository.getRepositoryId());
            String title = repository.getTitle() != null ? repository.getTitle() : url;
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginCatalogRepositoryAdded, title))).show();
        });
    }

    private void removeRepository(PluginRepositoryEntity repository) {
        getController().removeRepository(repository.getRepositoryId());
        String text = LocaleController.formatString(R.string.PluginCatalogRepositoryRemoved, CatalogRepositoryCell.getTitle(repository));
        String url = repository.getUrl();
        BulletinFactory factory = BulletinFactory.of(this);
        if (url != null) {
            factory.createSimpleBulletin(R.raw.ic_delete, AndroidUtilities.replaceTags(text), LocaleController.getString(R.string.Undo),
                    () -> PluginRepositoryLinks.addGit(this, url, added -> syncRequested.add(added.getRepositoryId()))).show();
        } else {
            factory.createSimpleBulletin(R.raw.ic_delete, AndroidUtilities.replaceTags(text)).show();
        }
    }
}
