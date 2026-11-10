package com.exteragram.messenger.components;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.exteragram.messenger.utils.chats.ChatUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.ProfileActivity;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Port of exteraGram MessageDetailsPopupWrapper ("Details" swipe-back page of the message context menu).
 * JpegFingerprint / MediaUtils.getPhotoPlatform and com.google.zxing.Dimension are inlined as private
 * nested classes (we do not ship those classes).
 */
public abstract class MessageDetailsPopupWrapper {

    private static final int SET_OWNER = 0;
    private static final int FILE_PATH = 1;
    private static final int LOCATION = 2;
    private static final int BITRATE = 3;
    private static final int RESOLUTION = 4;
    private static final int PLATFORM = 5;

    public LinearLayout swipeBack;

    private final BaseFragment fragment;
    private final Theme.ResourcesProvider resourcesProvider;
    private String filePath;
    private String[] geo;
    private long ownerId = 0L;
    private JpegFingerprint photoFingerprint;

    public void closeMenu() {
    }

    public abstract void copy(String text);

    // exteraGram: restored from smali (constructor was "decompiled incorrectly" by JADX)
    public MessageDetailsPopupWrapper(BaseFragment fragment, PopupSwipeBackLayout swipeBackLayout, MessageObject messageObject, Theme.ResourcesProvider resourcesProvider) {
        this.fragment = fragment;
        this.resourcesProvider = resourcesProvider;
        final Activity activity = fragment.getParentActivity();

        swipeBack = new LinearLayout(activity);
        swipeBack.setOrientation(LinearLayout.VERTICAL);

        ScrollView scrollView = new ScrollView(activity) {
            final AnimatedFloat alphaFloat = new AnimatedFloat(this, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
            private Drawable topShadowDrawable;
            private boolean wasCanScrollVertically;

            @Override
            public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
                super.onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed);
                boolean canScrollVertically = canScrollVertically(-1);
                if (wasCanScrollVertically != canScrollVertically) {
                    invalidate();
                    wasCanScrollVertically = canScrollVertically;
                }
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                float alpha = alphaFloat.set(canScrollVertically(-1) ? 1f : 0f) * 0.5f;
                if (alpha > 0) {
                    if (topShadowDrawable == null) {
                        topShadowDrawable = ContextCompat.getDrawable(getContext(), R.drawable.header_shadow);
                    }
                    if (topShadowDrawable != null) {
                        topShadowDrawable.setBounds(0, getScrollY(), getWidth(), getScrollY() + topShadowDrawable.getIntrinsicHeight());
                        topShadowDrawable.setAlpha((int) (255 * alpha));
                        topShadowDrawable.draw(canvas);
                    }
                }
            }
        };
        LinearLayout linearLayout = new LinearLayout(activity);
        scrollView.addView(linearLayout);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        ActionBarMenuSubItem backItem = new ActionBarMenuSubItem(fragment.getParentActivity(), true, false, resourcesProvider);
        backItem.setItemHeight(44);
        backItem.setTextAndIcon(LocaleController.getString(R.string.Back), R.drawable.msg_arrow_back);
        backItem.getTextView().setPadding(LocaleController.isRTL ? 0 : AndroidUtilities.dp(40), 0, LocaleController.isRTL ? AndroidUtilities.dp(40) : 0, 0);
        backItem.setOnClickListener(v -> swipeBackLayout.closeForeground());
        swipeBack.addView(backItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        linearLayout.addView(createGap(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        TLRPC.Message owner = messageObject.messageOwner;
        ArrayList<Item> items = new ArrayList<>();
        if (owner.views > 0) {
            items.add(new Item(R.drawable.msg_view_file, String.format(LocaleController.getPluralString("Views", owner.views), AndroidUtilities.formatCount(owner.views)), (String) null));
        }
        if (owner.forwards > 0) {
            items.add(new Item(R.drawable.msg_forward, String.format(LocaleController.getPluralString("Shares", owner.forwards), AndroidUtilities.formatCount(owner.forwards)), (String) null));
        }
        if (!items.isEmpty()) {
            items.add(null);
        }

        items.add(new Item(R.drawable.msg_info, "ID", owner.id));
        if (owner.date > 0) {
            items.add(new Item(R.drawable.msg_calendar2, LocaleController.getString(R.string.Date), formatTime(owner.date, true)));
        }
        if (owner.fwd_from != null && owner.fwd_from.date > 0 && owner.fwd_from.date != owner.date) {
            items.add(new Item(R.drawable.msg_recent, LocaleController.getString(R.string.ForwardedDate), formatTime(owner.fwd_from.date, true)));
        }
        if (owner.edit_date > 0 && owner.edit_date != owner.date && !owner.edit_hide) {
            items.add(new Item(R.drawable.msg_edit, LocaleController.getString(R.string.EditedDate), formatTime(owner.edit_date, true)));
        }
        items.add(null);

        if (messageObject.getSize() > 0) {
            items.add(new Item(R.drawable.msg_sendfile, LocaleController.getString(R.string.FileSize), AndroidUtilities.formatFileSize(messageObject.getSize())));
        }
        String mime = messageObject.getMimeType();
        if (mime != null && !mime.isEmpty()) {
            items.add(new Item(R.drawable.msg_media, LocaleController.getString(R.string.MimeType), mime));
        }

        TLRPC.MessageMedia media = MessageObject.getMedia(owner);
        if (media != null && media.document != null) {
            for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                    items.add(new Item(R.drawable.msg_log, LocaleController.getString(R.string.FileName), attribute.file_name));
                }
                if (attribute instanceof TLRPC.TL_documentAttributeSticker && attribute.stickerset != null) {
                    ownerId = ChatUtils.extractOwnerId(attribute.stickerset.id);
                    if (ownerId > 0) {
                        items.add(new Item(SET_OWNER, R.drawable.msg_sticker, LocaleController.getString(R.string.ChannelCreator), String.valueOf(ownerId)));
                    }
                }
            }
        }

        filePath = ChatUtils.getInstance().getPathToMessage(messageObject);
        if (!TextUtils.isEmpty(filePath)) {
            items.add(new Item(FILE_PATH, R.drawable.msg_map, LocaleController.getString(R.string.FilePath), LocaleController.getString(R.string.Open)));
        }

        final boolean isAudio = messageObject.isVoice() || messageObject.isMusic();
        final boolean isVideo = messageObject.isVideo() || messageObject.isRoundVideo() || messageObject.isVideoSticker() || messageObject.isGif();
        final boolean photoAsDocument = isPhotoAsDocument(messageObject);
        final boolean isPhoto = photoAsDocument || messageObject.isPhoto() || messageObject.isSticker();

        if (isPhoto && !TextUtils.isEmpty(filePath)) {
            items.add(new Item(PLATFORM, R.drawable.menu_devices, LocaleController.getString(R.string.Platform), LocaleController.getString(R.string.NumberUnknown)));
        }
        if (isVideo || isPhoto) {
            items.add(new Item(RESOLUTION, R.drawable.msg_photo_crop, LocaleController.getString(R.string.Resolution), "0x0"));
        }
        if (photoAsDocument && !TextUtils.isEmpty(filePath)) {
            items.add(new Item(LOCATION, R.drawable.msg_location, LocaleController.getString(R.string.ShareLocation), "0.0, 0.0"));
        }
        if (isVideo || isAudio) {
            items.add(new Item(BITRATE, R.drawable.msg_noise_on, LocaleController.getString(R.string.Bitrate), "0 Kbps"));
            int duration = (int) messageObject.getDuration();
            if (duration > 0) {
                items.add(new Item(R.drawable.msg2_animations, LocaleController.getString(R.string.Duration), AndroidUtilities.formatShortDuration(duration)));
            }
        }

        int dc = 0;
        if (media != null) {
            if (media.photo != null && media.photo.dc_id > 0) {
                dc = media.photo.dc_id;
            } else if (media.document != null && media.document.dc_id > 0) {
                dc = media.document.dc_id;
            } else if (media.webpage != null && media.webpage.photo != null && media.webpage.photo.dc_id > 0) {
                dc = media.webpage.photo.dc_id;
            } else if (media.webpage != null && media.webpage.document != null && media.webpage.document.dc_id > 0) {
                dc = media.webpage.document.dc_id;
            }
        }
        if (dc != 0) {
            items.add(new Item(R.drawable.msg_satellite, LocaleController.getString(R.string.Datacenter), String.format(Locale.ROOT, "DC%d, %s", dc, ChatUtils.getDCName(dc))));
        }

        if (items.get(items.size() - 1) == null) {
            items.remove(items.size() - 1);
        }

        int height = 0;
        for (int a = 0; a < items.size(); a++) {
            final Item item = items.get(a);
            if (item == null) {
                linearLayout.addView(createGap(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
                height += 8;
                continue;
            }
            final ActionBarMenuSubItem cell = new ActionBarMenuSubItem(fragment.getParentActivity(), false, false, resourcesProvider);
            cell.setTextAndIcon(item.title, item.resId);
            cell.setMinimumWidth(AndroidUtilities.dp(196));
            cell.setOnClickListener(v -> swipeBackLayout.closeForeground());
            linearLayout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
            if (item.subtitle != null) {
                cell.setSubtext(item.subtitle);
                cell.subtextView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                cell.subtextView.setMarqueeRepeatLimit(-1);
                cell.subtextView.setSelected(true);
                cell.setItemHeight(56);
                height += 56;
            } else {
                height += 48;
            }

            if (item.id == SET_OWNER && ownerId > 0) {
                ChatUtils.getInstance().searchUserById(ownerId, user -> {
                    if (user != null) {
                        String username = UserObject.getPublicUsername(user);
                        item.subtitle = !TextUtils.isEmpty(username) ? "@" + username : ContactsController.formatName(user);
                        cell.setSubtext(item.subtitle);
                    }
                });
            } else if (item.id == LOCATION) {
                ChatUtils.utilsQueue.postRunnable(() -> {
                    geo = getLatLongFromPhoto(new File(filePath));
                    AndroidUtilities.runOnUIThread(() -> {
                        if (geo != null) {
                            cell.setSubtext(geo[0] + ", " + geo[1]);
                            item.subtitle = geo[0] + ", " + geo[1];
                        } else {
                            cell.setVisibility(View.GONE);
                        }
                    });
                });
            } else if (item.id == BITRATE) {
                ChatUtils.utilsQueue.postRunnable(() -> {
                    final int bitrate = getBitrate(messageObject, filePath);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (bitrate > 0) {
                            cell.setSubtext(bitrate + " Kbps");
                            item.subtitle = bitrate + " Kbps";
                        } else {
                            cell.setVisibility(View.GONE);
                        }
                    });
                });
            } else if (item.id == RESOLUTION) {
                ChatUtils.utilsQueue.postRunnable(() -> {
                    final Dimension resolution = isVideo ? getVideoResolution(messageObject, filePath) : getPhotoResolution(messageObject, filePath);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (resolution != null) {
                            cell.setSubtext(resolution.toString());
                            item.subtitle = resolution.toString();
                        } else {
                            cell.setVisibility(View.GONE);
                        }
                    });
                });
            } else if (item.id == PLATFORM) {
                ChatUtils.utilsQueue.postRunnable(() -> {
                    photoFingerprint = JpegFingerprint.parse(filePath);
                    final String platform = getPhotoPlatform(photoFingerprint);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!TextUtils.isEmpty(platform)) {
                            cell.setSubtext(platform);
                            item.subtitle = platform;
                        } else {
                            cell.setVisibility(View.GONE);
                        }
                    });
                });
            }

            cell.setTag(item);
            cell.setOnClickListener(v -> onItemClick(item, activity, isPhoto, isVideo, messageObject, fragment));
            cell.setOnLongClickListener(v -> {
                String text;
                if (item.id == FILE_PATH && !TextUtils.isEmpty(filePath)) {
                    text = filePath;
                } else if (item.id == SET_OWNER) {
                    text = String.valueOf(ownerId);
                } else if (item.id == PLATFORM && photoFingerprint != null) {
                    text = photoFingerprint.describe();
                } else {
                    text = item.subtitle != null ? item.subtitle : item.title;
                }
                copy(text);
                return true;
            });
        }

        if (height > 380 && Math.abs(height - 380) > 112) {
            swipeBack.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 380));
        } else {
            swipeBack.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private void onItemClick(Item item, Activity activity, boolean isPhoto, boolean isVideo, MessageObject messageObject, BaseFragment fragment) {
        closeMenu();
        if (item.id == FILE_PATH && !TextUtils.isEmpty(filePath)) {
            try {
                Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", new File(filePath));
                if (isPhoto || isVideo) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setDataAndType(uri, messageObject.getMimeType());
                    if (!activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                        activity.startActivity(intent);
                        return;
                    }
                }
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.setDataAndType(uri, messageObject.getMimeType());
                activity.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            } catch (IllegalArgumentException e) {
                FileLog.e(e);
            }
            return;
        }
        if (item.id == SET_OWNER) {
            if (item.subtitle != null && item.subtitle.startsWith("@")) {
                Bundle args = new Bundle();
                args.putLong("user_id", ownerId);
                fragment.presentFragment(new ProfileActivity(args));
                return;
            }
            copy(String.valueOf(ownerId));
        } else if (item.id == LOCATION && geo != null) {
            Browser.openUrl(fragment.getParentActivity(), String.format("https://maps.google.com/?q=%s,%s", geo[0], geo[1]));
        } else {
            copy(item.subtitle != null ? item.subtitle : item.title);
        }
    }

    // region bitrate / resolution

    public static int getBitrate(MessageObject messageObject, String path) {
        int bitrate = -1;
        if (!TextUtils.isEmpty(path)) {
            try {
                bitrate = getBitrateFromPath(path);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (bitrate != -1) {
            return bitrate;
        }
        try {
            return getBitrateFromAttributes(messageObject);
        } catch (Exception e) {
            FileLog.e(e);
            return bitrate;
        }
    }

    public static int getBitrateFromPath(String path) {
        int bitrate;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            bitrate = value == null ? -1 : Integer.parseInt(value) / 1000;
        } catch (Exception e) {
            FileLog.e(e);
            bitrate = -1;
        }
        try {
            retriever.release();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return bitrate;
    }

    public static int getBitrateFromAttributes(MessageObject messageObject) {
        long size = MessageObject.getMessageSize(messageObject.messageOwner);
        TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        if (size > 0 && media != null && media.document != null) {
            for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeAudio || attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    double duration = attribute.duration;
                    if (duration > 0) {
                        return (int) (size / duration * 8.0 / 1000.0);
                    }
                }
            }
        }
        return -1;
    }

    private static Dimension getPhotoResolution(MessageObject messageObject, String path) {
        Dimension result = null;
        if (!TextUtils.isEmpty(path)) {
            try {
                result = getPhotoResolutionFromPath(path);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (result != null) {
            return result;
        }
        try {
            return getPhotoResolutionFromAttributes(messageObject);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Dimension getPhotoResolutionFromPath(String path) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        // zxing Dimension threw on negative values; keep the same fall-through to attributes
        if (options.outWidth < 0 || options.outHeight < 0) {
            return null;
        }
        return new Dimension(options.outWidth, options.outHeight);
    }

    private static Dimension getPhotoResolutionFromAttributes(MessageObject messageObject) {
        TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        if (media != null && media.photo != null) {
            Dimension dimension = null;
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize(), false, null, true);
            if (photoSize != null && photoSize.w > 0 && photoSize.h > 0) {
                dimension = new Dimension(photoSize.w, photoSize.h);
            }
            if (dimension == null) {
                TLRPC.VideoSize videoSize = FileLoader.getClosestVideoSizeWithSize(media.photo.video_sizes, AndroidUtilities.getPhotoSize(), false, true);
                if (videoSize != null && videoSize.w > 0 && videoSize.h > 0) {
                    dimension = new Dimension(videoSize.w, videoSize.h);
                }
            }
            return dimension;
        }
        if (media != null && media.document != null) {
            for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeImageSize && attribute.w > 0 && attribute.h > 0) {
                    return new Dimension(attribute.w, attribute.h);
                }
            }
        }
        return null;
    }

    private static Dimension getVideoResolution(MessageObject messageObject, String path) {
        Dimension result = null;
        if (!TextUtils.isEmpty(path)) {
            try {
                result = getVideoResolutionFromPath(path);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (result != null) {
            return result;
        }
        try {
            return getVideoResolutionFromAttributes(messageObject);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Dimension getVideoResolutionFromPath(String path) {
        int width = 0;
        int height = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (w != null) width = Integer.parseInt(w);
            if (h != null) height = Integer.parseInt(h);
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            retriever.release();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return new Dimension(width, height);
    }

    private static Dimension getVideoResolutionFromAttributes(MessageObject messageObject) {
        TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        if (media == null || media.document == null) {
            return null;
        }
        for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeVideo && attribute.w > 0 && attribute.h > 0) {
                return new Dimension(attribute.w, attribute.h);
            }
        }
        return null;
    }

    // endregion

    // region geo

    public static String[] getLatLongFromPhoto(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            String lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
            String lon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);
            String latRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
            String lonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
            if (lat == null || lon == null || latRef == null || lonRef == null) {
                return null;
            }
            double latitude = convertToDegrees(lat);
            if ("S".equalsIgnoreCase(latRef)) {
                latitude = -latitude;
            }
            double longitude = convertToDegrees(lon);
            if ("W".equalsIgnoreCase(lonRef)) {
                longitude = -longitude;
            }
            DecimalFormat format = new DecimalFormat("#.######");
            format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.ENGLISH));
            return new String[]{format.format(latitude), format.format(longitude)};
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static double convertToDegrees(String value) {
        String[] parts = value.split(",");
        return convertToDouble(parts[0]) + convertToDouble(parts[1]) / 60.0 + convertToDouble(parts[2]) / 3600.0;
    }

    private static double convertToDouble(String value) {
        String[] parts = value.split("/");
        if (parts.length == 1) {
            return Double.parseDouble(parts[0]);
        }
        if (parts.length == 2) {
            double num = Double.parseDouble(parts[0]);
            double den = Double.parseDouble(parts[1]);
            if (den != 0) {
                return num / den;
            }
            FileLog.e("Division by zero in GPS data");
            return 0;
        }
        FileLog.e("Invalid rational number format: " + value);
        return 0;
    }

    // endregion

    private boolean isPhotoAsDocument(MessageObject messageObject) {
        try {
            TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
            if (media != null && media.document != null) {
                for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
                    if (attribute instanceof TLRPC.TL_documentAttributeImageSize && attribute.w > 0 && attribute.h > 0) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    private String formatTime(int time, boolean full) {
        if (time == 0x7ffffffe) {
            return LocaleController.getString(R.string.SendWhenOnline);
        }
        if (full) {
            long millis = time * 1000L;
            return LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime,
                    LocaleController.getInstance().getFormatterYear().format(new Date(millis)),
                    LocaleController.getInstance().getFormatterDayWithSeconds().format(new Date(millis)));
        }
        return LocaleController.formatDateAudio(time, true);
    }

    private View createGap() {
        ActionBarPopupWindow.GapView gap = new ActionBarPopupWindow.GapView(fragment.getContext(), resourcesProvider);
        gap.setDividerVisible(false);
        return gap;
    }

    public static class Item {
        int id;
        int resId;
        String title;
        String subtitle;

        public Item(int resId, String title, String subtitle) {
            this(-1, resId, title, subtitle);
        }

        public Item(int resId, String title, int subtitle) {
            this(-1, resId, title, String.valueOf(subtitle));
        }

        public Item(int id, int resId, String title, String subtitle) {
            this.id = id;
            this.resId = resId;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /** Replacement for com.google.zxing.Dimension (toString() = "WxH"). */
    private static final class Dimension {
        final int width;
        final int height;

        Dimension(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    // region photo platform (exteraGram MediaUtils.getPhotoPlatform)

    private static String getPhotoPlatform(JpegFingerprint fp) {
        if (fp == null || fp.jfifUnits < 0) {
            return null;
        }
        boolean skia = (fp.iccVersion == 0x430 && fp.iccYear == 2016) || (fp.iccDescription != null && fp.iccDescription.startsWith("Google/Skia/"));
        if (fp.jfifUnits == 0) {
            if (fp.jfifDensityX != 1) {
                return null;
            }
            if (skia) {
                return "Android";
            }
            if (fp.iccLength == 0) {
                if (fp.quality == 72) {
                    return "iOS";
                }
                if (fp.quality == 75) {
                    return "macOS";
                }
            }
            return null;
        }
        if (fp.jfifUnits != 1 || fp.jfifDensityX != 72) {
            return fp.iccVersion == 0x240 ? "macOS" : "Desktop";
        }
        if (skia) {
            return "Android";
        }
        if (fp.iccVersion == 0x240) {
            return "macOS";
        }
        if (fp.iccVersion == 0x440 && fp.iccLength == 480) {
            return "Desktop";
        }
        return null;
    }

    // endregion

    /** Minimal port of com.exteragram.messenger.utils.JpegFingerprint (JPEG header markers inspection). */
    private static final class JpegFingerprint {
        private static final int[] ZIGZAG = {0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};
        private static final int[] STANDARD_LUMA = {16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};

        int components;
        boolean exif;
        boolean frameBeforeQuantization;
        int width;
        int height;
        int horizontalSampling;
        int verticalSampling;
        int huffmanTables;
        String iccDescription;
        int iccLength;
        int iccVersion;
        int iccYear;
        int jfifDensityX;
        int jfifDensityY;
        boolean jfifThumbnail;
        boolean progressive;
        boolean quantizationTable;
        boolean restartInterval;
        boolean xmp;
        int jfifVersion = -1;
        int jfifUnits = -1;
        int quality = -1;
        String markerOrder = "";

        // exteraGram: restored from smali (JADX failed on JpegFingerprint.parse)
        static JpegFingerprint parse(String path) {
            if (path == null) {
                return null;
            }
            File file = new File(path);
            if (!file.exists()) {
                return null;
            }
            try (InputStream in = new BufferedInputStream(new FileInputStream(file), 0x4000)) {
                if (in.read() != 0xFF || in.read() != 0xD8) {
                    return null;
                }
                JpegFingerprint fp = new JpegFingerprint();
                StringBuilder order = new StringBuilder();
                int offset = 2;
                while (offset < 0x40000) {
                    if (in.read() != 0xFF) {
                        break;
                    }
                    int marker = in.read();
                    while (marker == 0xFF) {
                        marker = in.read();
                        offset++;
                    }
                    offset += 2;
                    if (marker < 0 || marker == 0xD9 || marker == 0xDA) {
                        break;
                    }
                    if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                        continue;
                    }
                    int hi = in.read();
                    int lo = in.read();
                    if (hi < 0 || lo < 0) {
                        break;
                    }
                    int segLength = (hi << 8) | lo;
                    int dataLength = segLength - 2;
                    if (dataLength < 0) {
                        break;
                    }
                    offset += segLength;
                    if (order.length() > 0) {
                        order.append('>');
                    }
                    order.append(String.format(Locale.US, "%02X", marker));
                    boolean interesting = marker == 0xE0 || marker == 0xE1 || marker == 0xE2 || marker == 0xDB
                            || marker == 0xC4 || marker == 0xDD || (marker >= 0xC0 && marker <= 0xC2);
                    if (interesting) {
                        byte[] data = new byte[dataLength];
                        int read = 0;
                        while (read < dataLength) {
                            int r = in.read(data, read, dataLength - read);
                            if (r < 0) {
                                break;
                            }
                            read += r;
                        }
                        if (read < dataLength) {
                            break;
                        }
                        fp.readSegment(marker, data);
                    } else {
                        long skipped = 0;
                        while (skipped < dataLength) {
                            long s = in.skip(dataLength - skipped);
                            if (s <= 0) {
                                break;
                            }
                            skipped += s;
                        }
                        if (skipped < dataLength) {
                            break;
                        }
                    }
                }
                fp.markerOrder = order.toString();
                return fp;
            } catch (IOException e) {
                FileLog.e(e);
                return null;
            }
        }

        private void readSegment(int marker, byte[] data) {
            if (marker == 0xE0) {
                if (data.length < 14 || !startsWith(data, "JFIF")) {
                    return;
                }
                jfifVersion = ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
                jfifUnits = data[7] & 0xFF;
                jfifDensityX = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
                jfifDensityY = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
                jfifThumbnail = data[12] != 0 || data[13] != 0;
            } else if (marker == 0xE1) {
                exif = exif || startsWith(data, "Exif");
                xmp = xmp || startsWith(data, "http://ns.adobe.com/xap/1.0/");
            } else if (marker == 0xE2) {
                if (iccLength == 0 && data.length >= 50 && startsWith(data, "ICC_PROFILE")) {
                    iccLength = readInt(data, 14);
                    iccVersion = ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
                    iccYear = ((data[38] & 0xFF) << 8) | (data[39] & 0xFF);
                    iccDescription = readIccDescription(data, 14);
                }
            } else if (marker == 0xDB) {
                int i = 0;
                while (i < data.length) {
                    int precision = (data[i] & 0xF0) >> 4;
                    int tableId = data[i] & 0x0F;
                    int start = i + 1;
                    int end = start + (precision == 0 ? 64 : 128);
                    if (end > data.length) {
                        return;
                    }
                    if (tableId == 0 && precision == 0 && !quantizationTable) {
                        quantizationTable = true;
                        quality = standardQuality(data, start);
                    }
                    i = end;
                }
            } else if (marker == 0xC4) {
                int i = 0;
                while (i + 17 <= data.length) {
                    int count = 0;
                    for (int k = 0; k < 16; k++) {
                        count += data[i + 1 + k] & 0xFF;
                    }
                    huffmanTables++;
                    i += count + 17;
                }
            } else if (marker == 0xDD) {
                restartInterval = data.length >= 2 && (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) > 0;
            } else if (marker >= 0xC0 && marker <= 0xC2 && data.length >= 9) {
                progressive = marker == 0xC2;
                frameBeforeQuantization = !quantizationTable;
                height = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                width = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
                components = data[5] & 0xFF;
                horizontalSampling = (data[7] & 0xF0) >> 4;
                verticalSampling = data[7] & 0x0F;
            }
        }

        private static int readInt(byte[] b, int i) {
            return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
        }

        private static String readIccDescription(byte[] b, int base) {
            try {
                int tagCount = readInt(b, base + 128);
                for (int t = 0; t < Math.min(tagCount, 64); t++) {
                    int entry = base + 132 + t * 12;
                    if (entry + 12 > b.length) {
                        break;
                    }
                    if (b[entry] == 'd' && b[entry + 1] == 'e' && b[entry + 2] == 's' && b[entry + 3] == 'c') {
                        int off = readInt(b, entry + 4) + base;
                        int size = readInt(b, entry + 8);
                        int end = off + size;
                        if (off >= base && size >= 12 && end <= b.length) {
                            if (b[off] == 'd') { // "desc"
                                int len = Math.min(readInt(b, off + 8) - 1, size - 12);
                                return len > 0 ? new String(b, off + 12, len, StandardCharsets.US_ASCII) : null;
                            }
                            if (b[off] == 'm') { // "mluc"
                                int len = readInt(b, off + 20);
                                int strOff = readInt(b, off + 24) + off;
                                if (len > 0 && strOff >= off && strOff + len <= end) {
                                    return new String(b, strOff, len, StandardCharsets.UTF_16BE);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
            }
            return null;
        }

        // exteraGram: restored from smali (JADX lost the inner "continue" — try each quality 100..1, return first exact match)
        private static int standardQuality(byte[] b, int offset) {
            for (int q = 100; q >= 1; q--) {
                int scale = q < 50 ? 5000 / q : 200 - q * 2;
                boolean match = true;
                for (int k = 0; k < 64; k++) {
                    int v = (STANDARD_LUMA[ZIGZAG[k]] * scale + 50) / 100;
                    if (v < 1) {
                        v = 1;
                    } else if (v > 255) {
                        v = 255;
                    }
                    if (v != (b[offset + k] & 0xFF)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return q;
                }
            }
            return -1;
        }

        private static boolean startsWith(byte[] b, String prefix) {
            if (b.length <= prefix.length()) {
                return false;
            }
            for (int i = 0; i < prefix.length(); i++) {
                if (b[i] != (byte) prefix.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            if (jfifUnits >= 0) {
                sb.append(String.format(Locale.US, "jfif=%d.%02d/u%d/%dx%d", jfifVersion >> 8, jfifVersion & 0xFF, jfifUnits, jfifDensityX, jfifDensityY));
            } else {
                sb.append("jfif=none");
            }
            if (jfifThumbnail) {
                sb.append("/thumb");
            }
            if (iccLength > 0) {
                sb.append(String.format(Locale.US, " icc=%d/%04x/%d/%s", iccLength, iccVersion, iccYear, iccDescription == null ? "?" : iccDescription));
            } else {
                sb.append(" icc=none");
            }
            sb.append(quality > 0 ? String.format(Locale.US, " dqt=q%d", quality) : quantizationTable ? " dqt=custom" : " dqt=none");
            sb.append(String.format(Locale.US, " sof=%s/%dc/%dx%d/%dx%d", progressive ? "prog" : "base", components, horizontalSampling, verticalSampling, width, height));
            sb.append(String.format(Locale.US, " dht=%d", huffmanTables));
            if (exif) {
                sb.append(" exif");
            }
            if (xmp) {
                sb.append(" xmp");
            }
            if (restartInterval) {
                sb.append(" dri");
            }
            sb.append(" order=").append(markerOrder);
            return sb.toString();
        }
    }
}
