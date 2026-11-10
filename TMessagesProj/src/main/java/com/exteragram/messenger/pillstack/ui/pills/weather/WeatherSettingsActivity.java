package com.exteragram.messenger.pillstack.ui.pills.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.core.PillType;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.WebFile;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ClipRoundedDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.ChatAttachAlertLocationLayout;
import org.telegram.ui.LocationActivity;
import org.telegram.ui.Stories.recorder.Weather;

import java.util.ArrayList;

/** exteraGram: weather pill settings - current location or a point picked on the map. */
public final class WeatherSettingsActivity extends BasePreferencesActivity {

    private FrameLayout addressContainer;
    private TextView addressText;
    private TLRPC.GeoPoint currentGeo;
    private ClipRoundedDrawable mapLoadingDrawable;
    private View mapMarker;
    private BackupImageView mapPreview;
    private FrameLayout mapPreviewContainer;

    @Override
    public boolean needHideTitle() {
        return true;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.WeatherPill);
    }

    @Override
    public View createView(Context context) {
        addressText = new TextView(context);
        addressText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.0f);
        addressText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        addressText.setGravity(Gravity.CENTER);
        addressText.setPadding(0, 0, 0, 0);
        addressContainer = new FrameLayout(context);
        addressContainer.addView(addressText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 21, 15, 21, 15));
        addressContainer.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        mapPreview = new BackupImageView(context) {
            @Override
            protected ImageReceiver createImageReciever() {
                return new ImageReceiver(this) {
                    @Override
                    protected boolean setImageBitmapByKey(Drawable drawable, String key, int type, boolean memCache, int guid) {
                        if (drawable != null && type != 1 && mapMarker != null) {
                            mapMarker.animate().alpha(1.0f).translationY(0.0f).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).setDuration(250L).start();
                        }
                        return super.setImageBitmapByKey(drawable, key, type, memCache, guid);
                    }
                };
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(240.0f), MeasureSpec.EXACTLY));
            }

            @Override
            protected boolean verifyDrawable(Drawable who) {
                return who == mapLoadingDrawable || super.verifyDrawable(who);
            }
        };
        SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(R.raw.map_placeholder, Theme.key_chat_outLocationIcon, 0.2f);
        svgThumb.setColorKey(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider());
        svgThumb.setAspectCenter(true);
        svgThumb.setParent(mapPreview.getImageReceiver());
        mapLoadingDrawable = new ClipRoundedDrawable(svgThumb);
        mapLoadingDrawable.setCallback(mapPreview);
        mapPreview.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        mapMarker = new View(context) {
            private final Drawable pin = context.getResources().getDrawable(R.drawable.map_pin_photo).mutate();
            private final AvatarDrawable avatarDrawable = new AvatarDrawable();
            private final ImageReceiver avatarImage = new ImageReceiver(this);

            {
                avatarDrawable.setInfo(getUserConfig().getCurrentUser());
                avatarImage.setForUserOrChat(getUserConfig().getCurrentUser(), avatarDrawable);
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                pin.setBounds(0, 0, AndroidUtilities.dp(62.0f), AndroidUtilities.dp(85.0f));
                pin.draw(canvas);
                avatarImage.setRoundRadius(AndroidUtilities.dp(62.0f));
                avatarImage.setImageCoords(AndroidUtilities.dp(6.0f), AndroidUtilities.dp(6.0f), AndroidUtilities.dp(50.0f), AndroidUtilities.dp(50.0f));
                avatarImage.draw(canvas);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(62.0f), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(85.0f), MeasureSpec.EXACTLY));
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                avatarImage.onAttachedToWindow();
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                avatarImage.onDetachedFromWindow();
            }
        };
        mapMarker.setWillNotDraw(false);
        mapPreviewContainer = new FrameLayout(context);
        mapPreviewContainer.addView(mapPreview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        mapPreviewContainer.addView(mapMarker, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, -31, 0, 0));
        mapPreviewContainer.setOnClickListener(v -> openMapPicker());
        String customWeatherLocation = PillStackConfig.getCustomWeatherLocation();
        if (customWeatherLocation != null) {
            try {
                currentGeo = ExteraConfig.getGSON().fromJson(customWeatherLocation, TLRPC.TL_geoPoint.class);
            } catch (Exception ignore) {
            }
        }
        updateMapPreview();
        return super.createView(context);
    }

    private void updateMapPreview() {
        TLRPC.GeoPoint geoPoint = currentGeo;
        if (geoPoint == null) {
            mapPreview.setImageBitmap(null);
            return;
        }
        mapMarker.setAlpha(0.0f);
        mapMarker.setTranslationY(-AndroidUtilities.dp(12.0f));
        int measuredWidth = mapPreview.getMeasuredWidth() <= 0 ? AndroidUtilities.displaySize.x : mapPreview.getMeasuredWidth();
        int width = (int) (measuredWidth / AndroidUtilities.density);
        int scale = Math.min(2, (int) Math.ceil(AndroidUtilities.density));
        mapPreview.setImage(ImageLocation.getForWebFile(WebFile.createWithGeoPoint(geoPoint.lat, geoPoint._long, 0L, scale * width, scale * 240, 15, scale)), width + "_240", mapLoadingDrawable, 0, (Object) null);
        addressText.setText(PillStackConfig.getCustomWeatherAddress());
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.WeatherPillTopInfo), "RestrictedEmoji", "🌤"));
        items.add(UItem.asButton(1, R.drawable.msg_settings_old, LocaleController.getString(R.string.PillStackPills)));
        items.add(UItem.asShadow(null));
        if (!PillStackConfig.getActivePills().contains(PillType.WEATHER.getId())) {
            return;
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.WeatherLocation)));
        items.add(UItem.asRadio(2, LocaleController.getString(R.string.CurrentLocation)).setChecked(PillStackConfig.getUseCurrentLocation()));
        items.add(UItem.asRadio(3, LocaleController.getString(R.string.SelectLocation)).setChecked(!PillStackConfig.getUseCurrentLocation()));
        if (PillStackConfig.getUseCurrentLocation()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.WeatherSettingsInfo)));
            if (!Weather.isLocationPermissionGranted()) {
                items.add(UItem.asButton(2, R.drawable.report, LocaleController.getString(R.string.WeatherLocationPermissionGrant)).red());
                items.add(UItem.asShadow(LocaleController.getString(R.string.WeatherLocationPermissionNo)));
            } else if (!Weather.isLocationEnabled()) {
                items.add(UItem.asButton(4, R.drawable.filled_location, LocaleController.getString(R.string.WeatherLocationServicesEnable)).accent());
                items.add(UItem.asShadow(LocaleController.getString(R.string.GpsDisabledAlertText)));
            }
            return;
        }
        items.add(UItem.asCustom(5, mapPreviewContainer));
        if (!TextUtils.isEmpty(PillStackConfig.getCustomWeatherAddress())) {
            items.add(UItem.asCustom(6, addressContainer));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.WeatherSettingsInfo)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case 1:
                presentFragment(new PillStackPreferencesActivity());
                break;
            case 2:
                if (!PillStackConfig.getUseCurrentLocation() || !Weather.isLocationPermissionGranted()) {
                    Weather.getUserLocation(true, location -> {
                        if (location != null) {
                            updateLocationSetting(true);
                        }
                    });
                }
                break;
            case 3:
                if (PillStackConfig.getUseCurrentLocation()) {
                    if (currentGeo == null) {
                        TLRPC.TL_geoPoint geoPoint = new TLRPC.TL_geoPoint();
                        geoPoint.lat = 55.7558d;
                        geoPoint._long = 37.6173d;
                        currentGeo = geoPoint;
                        PillStackConfig.setCustomWeatherLocation(ExteraConfig.getGSON().toJson(currentGeo));
                    }
                    updateLocationSetting(false);
                }
                openMapPicker();
                break;
            case 4:
                Weather.getUserLocation(true, location -> {
                    if (location == null || fragmentView == null || getListView$TMessagesProj().adapter == null) {
                        return;
                    }
                    getListView$TMessagesProj().adapter.update(true);
                });
                break;
            case 5:
            case 6:
                openMapPicker();
                break;
        }
    }

    private void updateLocationSetting(boolean useCurrent) {
        PillStackConfig.setUseCurrentLocation(useCurrent);
        PillStackConfig.notifySettingsChanged(PillType.WEATHER.getId());
        if (fragmentView != null) {
            updateMapPreview();
            if (getListView$TMessagesProj().adapter != null) {
                getListView$TMessagesProj().adapter.update(true);
            }
        }
    }

    private void openMapPicker() {
        final LocationActivity locationActivity = new LocationActivity(ChatAttachAlertLocationLayout.LOCATION_TYPE_BIZ);
        if (currentGeo != null) {
            TLRPC.TL_channelLocation location = new TLRPC.TL_channelLocation();
            location.geo_point = currentGeo;
            location.address = PillStackConfig.getCustomWeatherAddress();
            locationActivity.setInitialLocation(location);
        }
        locationActivity.setDelegate((media, live, notify, scheduleDate, payStars) -> {
            currentGeo = media.geo;
            String address = locationActivity.getAddressName();
            if (address == null) {
                address = "";
            }
            PillStackConfig.setCustomWeatherLocation(ExteraConfig.getGSON().toJson(currentGeo));
            PillStackConfig.setCustomWeatherAddress(address);
            updateLocationSetting(false);
        });
        presentFragment(locationActivity);
    }
}
