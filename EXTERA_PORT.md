# EXTERA_PORT — карта переноса фич exteraGram 12.10.6 в StaticGram (Android)

> Составлено 21.10.2026 по декомпиляции `exteraGram-full-universal-20261004.apk` (12.10.6, code 71129; пересобран как exteraStatic).
> Карта (§0–5) составлена до переноса; итог этапа 1 — §6. Пути `/root/.cache/jadx-extera/out` в §0–5 устарели: используйте `full2` (см. §6).

## 0. Главное коротко

1. **Объём exteraGram-кода:** `com/exteragram/messenger` — 514 классов (≈130 тыс. строк JADX). Из них 211 скомпилированы из **Kotlin** (`@kotlin.Metadata`, делегаты свойств, корутины, `kotlinx.serialization`). Наш `TMessagesProj` написан на чистой Java, плагина kotlin-android в нём нет (Kotlin подключён только в `TMessagesProj_AppTests`; `kotlin-gradle-plugin:2.1.0` уже есть в classpath корневого `build.gradle`). Решение принимаем на этапе 1: (а) подключить `kotlin-android` + stdlib и переписывать на Kotlin, (б) переписывать на Java. Даже при варианте (б) без `kotlin-stdlib` не соберутся куски, где JADX оставил вызовы `Intrinsics`, `CollectionsKt` и т.п.
2. **Хуки в Telegram-коде:** smali показывает ссылки на `com.exteragram` в **355 внешних классах org/telegram** (5 587 строк smali, без артефактов R8). JADX-дерево `decompiled/sources/org/telegram` неполное: 1 278 файлов против 2 061 класса в smali, нет ChatActivity, DialogsActivity, LaunchActivity, ProfileActivity и др. Поэтому `grep` по нему находит только 100 файлов и 352 места. Полная таблица — в §2.3.
3. **Два сквозных структурных хука** дают треть всех файлов. `EditTextBoldCursor` унаследован от `components/ReceiveContentEditText` — это 106 файлов. `android.text.TextPaint` заменён на `utils/ui/TextPaint` — это 75 файлов. Ещё 103 файла не содержат ничего, кроме этих двух замен. Их можно не трогать до тех пор, пока не понадобятся inline-math, вставка контента или шрифты.
4. **Классов из хукнутых файлов у нас нет только у 5**, и это не новые файлы, а разная раскладка или разница версий. `ChatsRemoteViewsFactory` и `ContactsRemoteViewsFactory` у нас вложены в `*WidgetService.java`, `AdaptiveIconImageView` лежит внутри `PremiumAppIconsPreviewView.java`. Действительно отсутствуют `ui/ActionBar/MonetAccentHelper` (это exteraGram: Monet-акценты) и `ui/Components/InstantCameraViewBase`.
5. **Разница версий: у нас 12.10.5, у exteraGram 12.10.6.** В org/telegram есть около 40 классов, которых нет у нас, и ссылок на exteragram в них нет — по всей видимости, это апстрим 12.10.6 или собственный рефакторинг extera:
   - новая камера кружков: `InstantCameraView2`, `InstantCameraViewBase`, `utils/camera/roundvideo/*` (≈10 классов), `RoundVideoSettingsActivity`, `TelegramRoundVideoUpload`;
   - веб-прокси: `utils/proxy/WebProxyTransport`, `WebProxyConnectionTester`, `ProxySettings`;
   - `ProxyPingController`;
   - `utils/glass/GlassEngine`, `GlassPositions*`;
   - `utils/settings/*`;
   - новая локализация `utils/localization/Localization` и `localization/LocalizationUtils` + `assets/localization_*.bin`;
   - `ResLottieMeta`, `AspectRatioFrameLayout`;
   - `StaticgramInvalidateRunnable` — это патч exteraStatic из README, не exteraGram.

   Кроме того, у exteraGram плеер переведён на **androidx.media3**: exoplayer2 удалён, ffmpeg-декодер `androidx_media3_decoder_ffmpeg` вшит в libtmessages. У нас по-прежнему исходники `com/google/android/exoplayer2`. Хуки VideoPlayer, MediaController и PhotoViewer придётся адаптировать вручную.
6. **Чужие зависимости, которых у нас нет:**
   - Kotlin stdlib, kotlinx-coroutines 1.11, kotlinx-serialization-json;
   - OkHttp 5.3.2, Retrofit 2 + converter-gson;
   - Room 2.8.4, WorkManager 2.11.2, DataStore 1.1.7;
   - CameraX 1.6.2, media3, Material 1.14, androidx.webkit 1.17;
   - ML Kit GenAI Prompt (Gemini Nano);
   - Vosk + JNA, Chaquopy (Python 3.11), dexmaker (`com.android.dx`), MVEL2;
   - AndroidSVG, `org.simplifiles`;
   - Xposed API (`de.robv`, это шим), `dev.exterahook`, ByteDance ShadowHook, Firebase Crashlytics/Analytics.

   Уже есть у нас: gson, guava, zxing, markwon + jlatexmath, nanohttpd, ML Kit language-id, credentials, `me.vkryl`, palette, dynamicanimation, exifinterface.
7. **Нативные библиотеки, которых нет у нас:**
   - `libetgadblock.so` — собственный Rust-движок блокировки рекламы exteraGram (JNI `NativeAdBlock`);
   - `libvosk.so` + `libjnidispatch.so` (JNA);
   - `libpython3.11.so`, `libchaquopy_java.so` и `*_python/_chaquopy` (Chaquopy);
   - `libshadowhook.so` — его **ссылает libtmessages.49.so** через DT_NEEDED;
   - `libc++_shared.so` — у exteraGram libtmessages собран с shared STL, у нас `c++_static`;
   - `libimage_processing_util_jni` и `libsurface_util_jni` (CameraX), `libdatastore_shared_counter`.

   Хук-движок **exterahook (на основе LSPlant) вшит в libtmessages.49.so** (natives регистрируются через RegisterNatives, `dev/exterahook/runtime/bridge/JniBridgeBindings`). Для плагинов придётся пересобрать наш libtmessages с LSPlant и ShadowHook — это главный нативный риск этапа 5.
8. **Строки и ресурсы.** 585 из 586 строк, нужных exteraGram, лежат в `assets/extera_locales/values*/extera.xml` (715 ключей, 33 локали) и подгружаются хуком `LocaleController`. Не хватает одной — `UseProxyWeb` (это апстрим 12.10.6). Строки надо перенести в `res/values*/strings_extera.xml`. Из 1 582 drawable, на которые ссылается exteraGram, у нас нет 1 028. Основная масса — иконпаки (`icons/BaseIconPacks` — 1 192 ссылки) и `filter_*`. Ещё нет 67 R.color.
9. **Связность и порядок.** `ExteraConfig.init()` сразу запускает `ApiController`, `RemoteUtils`, `PluginsController`, `AdBlockManager`, `IconManager`. На этапе 1 эти вызовы надо заглушить. Многие «зависимости» вроде `plugins/*`, `backup/*` или `pillstack/*`, видимые в import'ах JADX, — артефакты R8 (outline-методы и общие классы лямбд `$$ExternalSynthetic*`). Реальные связи проверять по smali.
10. **Внешние сервисы exteraGram** зашиты в код:
    - `api.exteragram.app/api/v1/` — профили, бейджи, now-playing, boosty;
    - remote-config `exteraremoteconfig` + бот `8083294286:exteraAuthBot`;
    - обновления из `update_beta` / `update_lite_beta`;
    - реклама в ленте `feed_ad*`.

    Для StaticGram их нужно либо отключить, либо переключить на наш backend.

### Методика (как получены цифры)
- Пакеты exteraGram: `/root/.cache/jadx-extera/out/sources/com/exteragram/messenger` (514 .java). Сторонние импорты собраны `grep ^import`; ссылки с `$`-суффиксом (`Native$`, `GlShader$`, `c.f$`, `org.mvel2.util.Make$Map$`) — это R8-outline, а не реальные зависимости.
- Хуки: `grep -rnoE 'Lcom/exteragram/messenger/...'` по `decoded/smali*/org/telegram`. Внутренние классы свёрнуты во внешний, ссылки на `$$ExternalSynthetic*` отброшены. «smali-ссылок» означает число строк smali (вызовы, поля, сигнатуры). Это метрика объёма патча, а не точное число мест в Java.
- Колонка «jadx-grep» — результат `grep -rc "ExteraConfig\|com\.exteragram"` по неполному JADX-дереву, как требовалось в задаче; прочерк — файла в JADX-дереве нет.
- Сравнение с нами: `/root/staticgram-client-extera/TMessagesProj/src/main/java/org/telegram` (1 885 .java, версия 12.10.5, code 7080).

---

## 1. Пакеты `com.exteragram.messenger`

Обозначения: **kt** — число Kotlin-классов в пакете, **LOC** — строки JADX. В графе «Зависит от» сначала указаны пакеты exteragram, затем сторонние библиотеки и `.so`. Артефакты R8 исключены.

### 1.1. Классы в корне пакета

| Класс | Назначение | Используется |
|---|---|---|
| `ExteraConfig` (2 108 строк, Kotlin `object`) | Центральный конфиг: **137 настроек** через делегаты `BasePref`, хранение в SharedPreferences `exteraconfig` (через `backup/PreferencesUtils.getPreferences`). Хранит GSON, раскладку главного меню и иконпаков, слушает remote-config (`extera_api_bot`). `init()`: `loadConfig()` → `ApiController.init()`, `RemoteUtils.init()`, `PluginsController.init(safeMode)` (события APP_START/RESUME), `AdBlockManager.preload()`, `IconManager.prefetchCustomPacks()`. Также `reloadConfig()`, `ensureSettingsVisibility()`, `sanitizeMenu()`, `saveMainMenuLayout()`, `saveIconPacksLayout()`, `toggleLogging()`, `migrateProxyConditions()`. Производные геттеры: `getAvatarCorners(f,z)`, `getAvatarSquareness`, `getOnlineDot*`, `getSpringSwipeback`, `getAospTransitions`, `getSectionRadiusDp`, `getDoubleTapSeekDurationMillis`, `isProxyDisabledOn`. | 164 файла org/telegram |
| `AvatarCornerType` | enum DEFAULT / FORUM / COMMUNITY — тип скругления аватара | DialogCell, ProfileSearchCell, UserCell, ChatActionCell, FiltersView, ActionBarMenuItem |
| `CameraType` | CAMERA_1 / CAMERA_2 / CAMERA_X (по умолчанию CAMERA_X на устройствах high-класса) | InstantCameraView(Base) |
| `DividerStyle` | HIDDEN / LINE / SEGMENTS — стиль разделителей | Theme, AndroidUtilities, DividerCell, ChatMessageCell, RecyclerListView, ProfileActionsView |
| `GlassOutlineStyle` | GLARE / SOLID / HIDDEN — обводка «стекла» | blur3/StrokeDrawable, BlurredBackgroundDrawable(RenderNode), SettingsActivity |
| `IconPackType` | DEFAULT / SOLAR / REMIX — встроенные иконпаки | ChatActivityEnterView, ProfileActivity и др. |
| `MainMenuItem` | пункты главного меню и drawer (DIVIDER, PROFILE, ARCHIVE, BOTS, NEW_GROUP, CONTACTS, NEW_CHANNEL, CALLS, SAVED, SETTINGS, PLUGINS, BROWSER, FEED) | MainTabsActivity, drawer, MainMenuHelper |
| `ProxyDisableCondition` | VPN / MOBILE_DATA / WIFI — когда автоматически выключать прокси | ApplicationLoader, ProxyListActivity |
| `StickerTimeMode` | DEFAULT / SIDE / HIDDEN — время на стикерах | ChatMessageCell (через `utils/chats/StickerTime`) |
| `TabCounterMode` | ALL / UNMUTED / HIDDEN — счётчики на табах папок | DialogsActivity |
| `TabIconsMode` | ICONS_AND_TITLES / TITLES_ONLY / ICONS_ONLY | FilterTabsView, DialogsActivity, DialogStoriesCell |
| `TransitionAnimation` | DEFAULT / AOSP / SPRING — анимация переходов | ActionBarLayout |
| `VideoMessagesCamera` | FRONT / REAR / ASK — камера для кружков | InstantCameraView, ChatActivityEnterView |

### 1.2. Подпакеты

| Пакет | Файлов (kt) / LOC | Назначение | Ключевые классы | Зависит от (exteragram / сторонние / .so) |
|---|---|---|---|---|
| **config** | 12 (12) / 559 | Типизированные preference-делегаты для ExteraConfig | `BasePref<T>` (`provideDelegate`, get/set по SharedPreferences), `BooleanPref`, `IntegerPref`, `SanitizedIntegerPref`, `FloatPref`, `LongPref`, `StringPref`, `NullableStringPref`, `StringSetPref`, `EnumPref`, `PrefClassesKt`, `BottomNavigationBar` (помощник нижней навигации) | backup/PreferencesUtils; kotlin-stdlib (reflect.KProperty) |
| **preferences** | 34 (14) / 9 301 | Экраны настроек exteraGram | `MainPreferencesActivity` (корень, вход из SettingsActivity), `GeneralPreferencesActivity`, `appearance/AppearancePreferencesActivity`, `appearance/AppNavigationPreferencesActivity`, `chats/ChatsPreferencesActivity`, `chats/SwipeActionsPreferencesActivity`, `OtherPreferencesActivity`, `BasePreferencesActivity` (наследник BaseFragment, строится на UniversalRecyclerView/UItem), `SwitchGroup`, `components/*` (AltSeekbar, HeaderSettingsCell, CustomPreferenceCell, PreviewColors, PreviewBackgroundDrawable), превью-ячейки `appearance/components/*` (AvatarCornersPreviewCell, ChatListPreviewCell, FabShapeCell, FilterTabsPreviewCell), `chats/components/*` (DoubleTapCell, SwipeActionsCell, StickerShapeCell, MessagesPreviewCell, SliderPreviewCell), `utils/SettingsRegistry` (поиск по настройкам, отметка «новое»), `utils/IconShapeHelper` | ссылается почти на все пакеты (ai, api, appicons, backup, badges, camera, components, config, export, feed, icons, pillstack, plugins, speech, translator, utils): это пункты меню; androidx.recyclerview, material (слайдеры) |
| **backup** | 5 (0) / 858 | Экспорт и импорт настроек; доступ к SharedPreferences | `PreferencesUtils` (getPreferences, backup/restore в JSON, isBackup), `BackupBottomSheet` (открывается при тапе на файл бэкапа в чате), `InvisibleEncryptor` | ai/AiConfig, pillstack/PillStackConfig, plugins, translator; gson |
| **utils** | 51 (9) / 10 928 | Общие утилиты и UI-хелперы | `AppUtils`, `ExtensionsKt`, `RuntimeCompatibility`, `MediaUtils`, `MarkdownUtils`, `IntentsController`, `JpegFingerprint`, `PasskeysUtil`, `VideoSubtitlesHelper`; `network/ExteraHttpClient` (общий OkHttp), `network/RemoteUtils` (remote-config `exteraremoteconfig`); `text/LocaleUtils`, `text/ZalgoFilter`; `system/SystemUtils`, `system/VibratorUtils`; `chats/*`: ChatUtils, StickerTime, SwipeAction(-sHelper), DoubleTapUtils, ForwardContext, FolderCounters, GlassMenuHelper, MainMenuHelper, WidePosts; `ui/*`: TextPaint, FontUtils, MonetUtils, SwitchUiHelper, FabUiHelper, MainTabsUiHelper, AccountsUiHelper, ChatHeaderUiHelper, MaterialSliderUiHelper, WindowTransitionAnimationHelper, PredictiveBackAnimationHelper, PopupUtils, UIUtil, FolderIcons, ColorRectSpan, ForwardsCountUiHelper | ai, backup, components, config, debug, export, feed, icons, pillstack, preferences; okhttp, gson, androidx.credentials (passkeys), material, androidx.core/dynamicanimation |
| **components** | 21 (5) / 4 394 | Общие UI-компоненты | `ReceiveContentEditText` (база EditTextBoldCursor: inline-math, OnReceiveContent), `ActionRow`, `CheckBoxRow`, `QRCodeSheet`, `QrCodeLoginView`, `ReverseImageSearchSheet`, `SearchPhotoPopupWrapper`, `MessageDetailsPopupWrapper`, `TranslateBeforeSendWrapper`, `ChooseSubtitlesLayout`, `PreformattedScrollView`, `Blended*` (цветные ответы), `ChatActivityEnterViewStaticIconView`, `IconSelectorAlert`, `SupporterBottomSheet`, `BoostyBottomSheet` | math/inline, translator, api, icons; zxing, media3, exifinterface |
| **adblock** | 11 (0) / 1 609 | Блокировка рекламы и трекеров в WebView/IV/ботах | `backend/AdBlockManager`, `backend/SubscriptionsManager` (uBO, EasyList/EasyPrivacy, AdGuard, RUAdList), `backend/ScriptletsManager` (uBO web_accessible_resources), `WebAdBlocker`, `AdBlockClient`, `interop/NativeAdBlock` (JNI), `ui/AdBlockMenuItem` | utils; okhttp; **libetgadblock.so** (Rust) |
| **translator** | 16 (14) / 2 823 | Переводчик: провайдеры и перевод чатов | `TranslatorUtils`, `TranslationProviders`, `ChatTranslator`, `ChatTranslationBridge`, `core/*` (BaseTranslator, HttpTranslator, TranslationDispatcher, ProviderLimits), `providers/*` (Google, Yandex, Microsoft, Telegram) | utils; okhttp |
| **ai** | 36 (6) / 9 202 | ИИ-ассистент: свои сервисы и роли, генерация из сообщения, замена Telegram AI, on-device | `AiController`, `AiConfig`, `TelegramAiReplacement`; `network/Client`, `network/ProxyDns` (DoH dns.comss.one), `backend/RestBackend` (OpenAI-совместимые API: Gemini, Perplexity и т.п.), `backend/OnDeviceBackend` + `OnDeviceAvailability` (ML Kit GenAI Prompt = Gemini Nano); `ui/AiResponseAlert`, `ui/GenerateFromMessageBottomSheet`, `ui/MarkdownPreview`, `ui/activities/*` (AiPreferences, Services, EditService, Roles, AiHistory); `data/*` | translator, components, icons, preferences, utils; okhttp, gson, guava, **com.google.mlkit:genai-prompt** |
| **api** | 26 (26) / 5 919 | Клиент backend'а exteraGram (`https://api.exteragram.app/api/v1/`) | `ApiController`, `network/ApiClient` + `ApiService` (Retrofit: `profiles`, `profiles/{id}`, `profiles/{id}/now-playing`, `profiles/updates`, `boosty-subscribers`), `db/ExteraDatabase` (Room: ProfileDao, BoostySubscriberDao, AddedRegDateDao, Migrations), `worker/SyncWorker` (WorkManager), `dto/*`, `model/*` | debug, utils; **Retrofit2 + converter-gson, OkHttp 5, Room 2.8 (+ksp/kapt), WorkManager 2.11, kotlinx-coroutines** |
| **badges** | 6 (4) / 738 | Кастомные бейджи у пользователей и каналов (из api) | `BadgesController`, `CachedRemoteSet`, `source/ApiBadgeSource`, `source/BadgeInfo` | api, components; coroutines |
| **regdate** | 1 (1) / 312 | Оценка даты регистрации аккаунта | `RegDateController` (asset `extera/registration_dates.bin` + api) | api, badges; gson |
| **nowplaying** | 9 (9) / 2 826 | «Сейчас играет» в профиле (Last.fm / stats.fm, обложки из iTunes) | `NowPlayingController`, `ServiceEmoji`, `ui/SetupNowPlayingActivity`, `ui/components/NowPlayingCard(+Data)` | api, badges, components; **kotlinx-serialization-json**, retrofit, okhttp, media3, palette |
| **feed** | 21 (2) / 7 111 | Лента каналов (отдельный таб или пункт меню) + вставка рекламы | `FeedController`, `FeedStore`, `FeedConfig`, `FeedTimelineLoader`, `FeedBackfillCoordinator`, `FeedUnreadTracker`, `FeedChannelRegistry`, `FeedChannelActions`, `FeedChatIntegration`, `FeedMessageUtils`, `FeedMessageIdentityMap`, `FeedRequestNormalizer` (вклинивается в ConnectionsManager), `ui/FeedActivity`, `ui/FeedChannelsActivity`, `ads/*` (FeedAdController и др., remote-config `feed_ad*`) | ai, badges, config, preferences, utils |
| **pillstack** | 19 (4) / 3 744 | «Пилюли» в поле поиска главного экрана: погода, курсы GRAM/BTC/USD, кэш, прокси | `core/PillRegistry`, `core/PillStackConfig`, `core/PillType`, `ui/PillStackView`, `ui/PillStackPreferencesActivity`, `ui/pills/*` (WeatherPill + WeatherSettingsActivity, RatePill, GramPill, BtcPill, UsdPill, CachePill, ProxyPill, `crypto/utils/ExchangeRates` — Coinbase API) | ai, backup, config, export, preferences, utils; okhttp, gson |
| **drawer** | 7 (0) / 3 307 | Боковое меню (navigation drawer) | `DrawerContainer`, `DrawerMenuView`, `DrawerMenuItemView`, `DrawerHeaderView`, `DrawerAccountPickerView`, `DrawerUnreadBadge`, `DrawerAccountUnreadBadge` | api, badges, feed, utils; dynamicanimation, recyclerview |
| **icons** | 22 (16) / 9 454 | Иконпаки: встроенные Solar/Remix и пользовательские из файлов, подмена drawable через Resources | `IconManager`, `ExteraResources` (обёртка над Resources), `BaseIconPacks` (карта 1 192 drawable), `IconPack`, `IconPackStorage`, `DecodedIconStore`, `IconPackProvider` (**ContentProvider в манифесте**), `ui/IconPacksActivity`, `ui/IconPacksEditorActivity`, `ui/picker/*`, `ui/components/*` | components, export, preferences, utils; **AndroidSVG (com.caverock), org.simplifiles**, coroutines, androidx.activity |
| **appicons** | 10 (0) / 2 184 | Альтернативные иконки лаунчера | `AppIconController`, `AppIcon`, `GeneratedAppIcons`, `AppIconPreviewDrawable`, `AppIconPreviewLoader`, `ui/AppIconsActivity`, `ui/components/*` | preferences, utils (+ activity-alias в манифесте) |
| **camera** | 6 (0) / 5 895 | CameraX для кружков, зум-слайдер, энкодер | `CameraXSession`, `RoundVideoEncoder`, `InstantCameraZoomSlider`, `CameraZoomSliderView`, `CameraLensStops`, `CameraDebugUtils` | utils; **CameraX 1.6.2** (camera-core, camera2, lifecycle), guava ListenableFuture, org.webrtc (GL); `.so`: libimage_processing_util_jni, libsurface_util_jni |
| **speech** | 6 (0) / 1 489 | Офлайн-распознавание голосовых (Vosk) | `VoiceRecognitionController`, `recognizers/VoskRecognizer`, `utils/FormatConverter`, `utils/MediaLoader`, `ui/RecognitionModelDialogs`, `ui/LoadingModelView` | ai (постобработка), translator; **vosk-android + JNA**; libvosk.so, libjnidispatch.so |
| **math** | 14 (13) / 2 047 | Калькулятор прямо в поле ввода | `MathExpression`, `MathFormat`, `MathOperators`, `Infix/Prefix/PostfixOperator`, `MathFunction`, `MathResult`, `MathSuggestion`, `MathOptions`, `inline/InlineMathController`, `inline/GhostTextLayout`, `inline/MathRevealAnimation` | — (только kotlin-stdlib) |
| **export** | 92 (1) / 17 743 | Экспорт чатов и данных (порт tdesktop export): HTML/JSON, takeout | `controllers/ExportController`, `controllers/ExportRequestsController`, `api/ApiWrap$*` (≈70 моделей), `api/ExportRequests$*` (TL takeout: InitTakeoutSession, InvokeWithTakeout, getLeftChannels …), `output/html/HtmlWriter`, `output/json/JsonWriter`, `output/htmlAndJson/*`, `ui/ExportActivity`, `ui/ChatViewer`, `ui/ExportMapper` | ai, components, preferences, utils; gson, okhttp; assets `extera/export/{css,js,images}` |
| **proxy** | 3 (2) / 1 272 | Расширения прокси: автоотключение по условию, IP-инфо | `ProxyController`, `IpAddressInfoController` (ipwho.is) | utils; okhttp |
| **notifications** | 2 (2) / 166 | Уведомления по аккаунтам | `AccountNotifications`, `AccountNotificationsActivity` | preferences |
| **updater** | 3 (0) / 1 006 | Внутренние обновления (из каналов `update_beta` / `update_lite_beta`) | `UpdaterUtils` (+ receiver в манифесте), `UpdateAppAlertDialog`, `UpdateLayout` | badges, icons, utils |
| **debug** | 7 (6) / 3 936 | Отладка: монитор кучи, фризов, загрузки, оверлей | `DebugActivity`, `DebugConfig`, `DebugOverlayView`, `HeapMonitor`, `FreezeMonitor`, `LoadMonitor`, `DebugFilesKt` | api, config, preferences, utils; appcompat |
| **plugins** | 61 (52) / 18 371 | Python-плагины (SDK 1.4.5.7): движок, хуки, UI | `PluginsController`, `PythonPluginsEngine`, `Plugin`, `PluginsConstants`; `hooks/*` (PluginsHooks — перехват TL-запросов, апдейтов и отправки; XposedHookRecord, EventHookRecord, MenuItemRecord, HookFilter); `xposed/PyMethodHook`, `xposed/PyMethodReplacement`; `utils/ClassProxy` (динамические прокси через dexmaker), `utils/NativeCrashHandler` (JNI в libtmessages), `utils/PluginsWatchdog`, `utils/SdkSignature`, `utils/PyObjectUtils`, `utils/MenuContextBuilder`; `pip/PipController` (pip-зависимости); `models/*` (настройки плагинов); `ui/*` (PluginsActivity, PluginSettingsActivity, PluginsInfoActivity, InstallPluginBottomSheet, SafeModeBottomSheet, templates/UniversalFragment …) | ai, badges, components, preferences, translator, updater, utils; **Chaquopy (com.chaquo.python), Xposed API (de.robv — шим), dev.exterahook (LSPlant в libtmessages), dexmaker, MVEL2, JNA, org.simplifiles**; .so: libpython3.11, libchaquopy_java, libshadowhook; assets `chaquopy/*`, `plugins_pysdk/sdk-*.zip` |

**Чем отличается свежая JADX-выгрузка от старой.** В `/root/exterastatic-src/decompiled` было 376 классов, сейчас 514. Добавились все Kotlin-классы `api/*`, `config/*`, `nowplaying/*`, `math/*`, `notifications/*`, большая часть `plugins/*` и `icons/*`. В некоторых файлах остались JADX WARN — эти места сверять со smali. Больше всего предупреждений в ApiController (52), IconManager (37), ReplaceIconBottomSheet (25), ChatViewer (24), PythonPluginsEngine (20), NowPlayingController (20), LoadMonitor (14) — в основном корутины.

### 1.3. ExteraConfig: все ключи (137)

Хранилище — `PreferencesUtils.getPreferences("exteraconfig")`. Тип и значение по умолчанию:

| Группа | Ключи (тип = default) |
|---|---|
| Общие | translationProvider:int=0, disableNumberRounding:bool=false, formatTimeWithSeconds=false, relativeLastSeen=false, inAppVibration=true, disableNotificationDelay=false, filterZalgo=true, downloadSpeedBoost:int=0, uploadSpeedBoost=false, hidePhoneNumber=false, showIdAndDc:int=1, hideArchiveFolder=false, archiveOnPull=false, disableUnarchiveSwipe=true, doNotUseProxy:int=0 (битовая маска ProxyDisableCondition), customSavePath:str="exteraStatic", targetLang:str="app" |
| Внешний вид | iconPack:enum=DEFAULT, editingIconPackId:str?=null, avatarCorners:float=28, singleCornerRadius=false, dividerStyle=LINE, forceSnow=false, hideActionBarStatus=false, centerTitle=false, hideStories=false, hideFloatingButton=false, hideDialogsSearchBar=false, senderMiniAvatars=true, titleText:int=0, tabIcons=TITLES_ONLY, tabCounterMode=ALL, hideAllChats=false, squareFab=true, sectionRadius:float=20, sectionsSeparatedHeaders=true, newLoadingStyle=true, newSliderStyle=true, newSwitchStyle=true, newChatHeaderStyle=false, newNavigationBarStyle=false, newFabStyle=false, useSystemFonts=true, gooeyAvatarAnimation=true, customThemes=true, glassOutlineStyle=GLARE, glassMessageMenu=true, forceBlur=false, eventType:int=0, useSystemIconShape |
| Навигация | tabletMode:int=0, predictiveBackIntensity:float=1, transitionAnimation=SPRING, navigationDrawer=false, immersiveDrawerAnimation=false, showFeedTab=false, showFeedUnreadCounter=true, раскладка главного меню (MainMenuLayout/HiddenItems — JSON) |
| Чаты | stickerSize:float=12, stickerTimeMode=DEFAULT, replyColors=true, replyEmoji=true, replyBackground=true, stickerShape:int=1, unlimitedRecentStickers=false, hideReactionsInPrivateChats/Channels/Groups=false, doubleTapAction:sint, doubleTapActionOutOwner:sint, swipeActions:str="2", swipeActionsLoop=false, swipeActionsReversed=false, bottomButton:int=2, widePostsInFeed=true, widePostsInChannels=false, telegramAiEditor=true, telegramAiSummaries=true, telegramAiInstantViewSummaries=true, quickAdminShortcuts=true, quickTransitionForChannels=true, quickTransitionForTopics=true, disableGreetingSticker=false, hideKeyboardOnScroll=true, addCommaAfterMention=true, inlineMathResult=true, disableMarkdown=false, hideSendAsPeer=false, removeMessageTail=true, replaceEditedWithIcon=true, showOnlineStatus=false, showForwardsCount=false, hideShareButton=true, showResultsBeforeVoting=false |
| Меню сообщения | showCopyPhotoButton=true, showSaveMessageButton=false, showRepeatMessageButton=false, showClearButton=true, showHistoryButton=false, showReportButton=true, showGenerateButton=true, showDetailsButton=false, groupMessageMenu=true |
| Медиа и камера | recognitionLanguage:str="none", postprocessingWithAi=false, cameraType:enum (CAMERA_X при performanceClass==2), extendedFramesPerSecond=false, cameraStabilization=false, cameraMirrorMode, videoMessagesCamera=FRONT, rememberLastUsedCamera=false, startWithWideAngleCamera=false, zoomSlider, staticZoom=false, alwaysSendInHD, hideCameraTile=false, doubleTapSeekDuration:int=1, preferOriginalQuality=false, swipeToPip=false, unmuteWithVolumeButtons=false, pauseOnMinimizeVideo, pauseOnMinimizeVoice=false, pauseOnMinimizeRound=false, flashWarmth:float=0.5, flashIntensity:float=1 |
| Прочее / сервис | useGoogleCrashlytics=BuildVars.isBetaApp(), useGoogleAnalytics=false, enableAdBlock, updateScheduleTimestamp:long, sdkUpdateScheduleTimestamp:long |
| Плагины | pluginsDevMode, pluginsSafeMode, pluginsCompactView, pluginsPySdkAutoUpdate, pluginsPySdkBetaVersions, pluginsDisableArtOpts, pluginsUnknownSources (все false), pinnedPlugins:Set<String>, getPluginsEngine() |

Ключи в разбивке по экранам настроек (по вызовам `ExteraConfig.get/set*`):
- **GeneralPreferencesActivity**: ArchiveOnPull, CustomSavePath, DisableNotificationDelay, DisableNumberRounding, DisableUnarchiveSwipe, DownloadSpeedBoost, FilterZalgo, FormatTimeWithSeconds, HideArchiveFolder, HidePhoneNumber, InAppVibration, RelativeLastSeen, ShowIdAndDc, TranslationProvider, UploadSpeedBoost.
- **AppearancePreferencesActivity**: CenterTitle, CustomThemes, DividerStyle, ForceBlur, ForceSnow, GlassMessageMenu, GlassOutlineStyle, GooeyAvatarAnimation, HideActionBarStatus, HideAllChats, HideDialogsSearchBar, HideFloatingButton, HideStories, NewChatHeaderStyle, NewFabStyle, NewLoadingStyle, NewNavigationBarStyle, NewSliderStyle, NewSwitchStyle, SectionRadius, SectionsSeparatedHeaders, SenderMiniAvatars, SingleCornerRadius, TabCounterMode, TabIcons, TitleText, UseSystemFonts, UseSystemIconShape.
- **AppNavigationPreferencesActivity**: MainMenuLayout / HiddenItems / DefaultMainMenuLayout, ImmersiveDrawerAnimation, NavigationDrawer, PredictiveBackIntensity, TabletMode, TransitionAnimation.
- **ChatsPreferencesActivity** (60 ключей): AddCommaAfterMention, AlwaysSendInHD, BottomButton, CameraMirrorMode, CameraStabilization, CameraType, DisableGreetingSticker, DoubleTapAction(+OutOwner), DoubleTapSeekDuration, ExtendedFramesPerSecond, GroupMessageMenu, HideCameraTile, HideKeyboardOnScroll, HideReactionsIn{PrivateChats,Channels,Groups}, HideSendAsPeer, HideShareButton, InlineMathResult, PauseOnMinimize{Round,Video,Voice}, PostprocessingWithAi, PreferOriginalQuality, QuickAdminShortcuts, QuickTransitionFor{Channels,Topics}, RecognitionLanguage, RememberLastUsedCamera, RemoveMessageTail, ReplaceEditedWithIcon, Reply{Background,Colors,Emoji}, Show{Clear,CopyPhoto,Details,ForwardsCount,Generate,History,OnlineStatus,RepeatMessage,Report,ResultsBeforeVoting,SaveMessage}Button, StartWithWideAngleCamera, StaticZoom, StickerSize, StickerTimeMode, SwipeToPip, TelegramAi{Editor,InstantViewSummaries,Summaries}, UnlimitedRecentStickers, UnmuteWithVolumeButtons, VideoMessagesCamera, WidePostsIn{Channels,Feed}, ZoomSlider.
- **SwipeActionsPreferencesActivity**: SwipeActionsLoop, SwipeActionsReversed (+ swipeActions).
- **OtherPreferencesActivity**: UseGoogleAnalytics, UseGoogleCrashlytics.
- **MainPreferencesActivity**: UseSystemIconShape.

Где в org/telegram читается каждый ключ — см. колонку ExteraConfig в §2.3 и раздел §4.

---

## 2. Хуки в `org/telegram`

### 2.1. Сводка
- Классов org/telegram, ссылающихся на exteragram (по smali): **355**; строк smali — **5 587**. В неполном JADX-дереве — 100 файлов и 352 вхождения.
- Файлы со ссылками на `ExteraConfig`: 164. С `utils/ui/TextPaint`: 75. С `ReceiveContentEditText`: 106. Только TP/RC и ничего больше: 103.
- Классы org/telegram, унаследованные от классов exteragram (`.super`):
  - `EditTextBoldCursor` → `ReceiveContentEditText`;
  - 5 классов → `TranslateBeforeSendWrapper`;
  - 3 → `PluginsMenuWrapper`;
  - по одному → `ChatActivityEnterViewStaticIconView`, `MessageDetailsPopupWrapper`, `PreformattedScrollView`, `QrCodeLoginView`, `NowPlayingCard`.
- **TP** (TextPaint) — это `class TextPaint extends android.text.TextPaint`. Подмена нужна для системных или кастомных шрифтов (`useSystemFonts`) и делается заменой import. **RC** (ReceiveContentEditText) — новая база EditTextBoldCursor: inline-math, `OnReceiveContentListener`, доработанный `getText`. Все RC-ссылки в 106 файлах — унаследованные вызовы (`getText` и т.п.). Достаточно поменять суперкласс EditTextBoldCursor, остальные файлы трогать не нужно.

### 2.2. Топ-30: что меняется

| # | Файл | Смысл хука |
|---|---|---|
| 1 | ui/Cells/ChatMessageCell (666) | Скругление аватаров (avatarCorners/Squareness), размер и форма стикеров (stickerSize, stickerShape), время на стикерах (`StickerTime`, stickerTimeMode), убрать хвостик (removeMessageTail), «изменено» → иконка, онлайн-точка на аватаре, скрыть кнопку «поделиться», результаты опроса до голосования, широкие посты (`WidePosts`), цветные ответы (`BlendedReplyFileText`), счётчик пересылок (`ForwardsCountUiHelper`), бейджи (`BadgesController`), кнопка распознавания Vosk (`VoiceRecognitionController`), ячейки ленты (`FeedMessageUtils`), хук отрисовки для плагинов, DividerStyle |
| 2 | ui/ChatActivity (664) | Двойной тап (DoubleTapUtils, doubleTapAction*), свайп-действия (SwipeActionsHelper), меню сообщения: copyPhoto/save/repeat/clear/history/report/generate/details, groupMessageMenu, стеклянное меню `GlassMenuHelper`; скрытие реакций по типу чата, быстрые админ-шорткаты, quickTransition для каналов и топиков, нижняя кнопка канала (bottomButton), запятая после упоминания, скрытие клавиатуры при скролле; перевод (TranslatorUtils/TranslationProviders), AI-генерация (AiController, GenerateFromMessageBottomSheet, AiResponseAlert), лента (FeedChatIntegration, FeedStore, FeedChannelActions), WidePosts, меню плагинов (PluginsMenuWrapper, MenuItemRecord, MenuContextBuilder), MessageDetailsPopupWrapper, ForwardContext, IP-инфо прокси, открытие бэкапа (BackupBottomSheet), Markdown |
| 3 | ui/ActionBar/Theme (438) | TP для всех theme-пейнтов; DividerStyle (Theme.dividerPaint), Monet-темы (MonetUtils), сезонные события (eventType, forceSnow) |
| 4 | ui/Components/InstantCameraView (392) | Кружки: выбор движка (CameraType, CameraXSession), зум-слайдер (InstantCameraZoomSlider, CameraZoomSliderView, staticZoom), выбор камеры (videoMessagesCamera, rememberLastUsedCamera), фронтальная вспышка (flashWarmth/Intensity), 60 fps (extendedFramesPerSecond), RoundVideoEncoder, отладочный оверлей |
| 5 | ui/ProfileActivity (292) | ID и DC в профиле (showIdAndDc), скрыть телефон, gooey-анимация аватара, скрыть истории, бейджи, now-playing карточка, дата регистрации (RegDateController/RegDateDTO), шапка (ChatHeaderUiHelper), меню плагинов, вход в настройки exteraGram и поиск по ним (SettingsRegistry), логирование (toggleLogging), ExteraDatabase |
| 6 | ui/Components/ChatActivityEnterView (251) | Поле ввода: AI-кнопки (AiController/Client/OnDeviceAvailability/AiResponseAlert, telegramAiEditor), перевод перед отправкой (TranslateBeforeSendWrapper), inline-math, скрыть «отправить как» (hideSendAsPeer), выбор камеры для кружка, иконки из иконпака, статичная иконка (ChatActivityEnterViewStaticIconView) |
| 7 | ui/PhotoViewer (116) | Субтитры видео (VideoSubtitlesHelper, ChooseSubtitlesLayout), обратный поиск изображения (ReverseImageSearchSheet, SearchPhotoPopupWrapper, JpegFingerprint), перевод подписи, перемотка двойным тапом (doubleTapSeekDuration), свайп в PiP, пауза при сворачивании, центрирование заголовка, ID/DC, оконные переходы |
| 8 | ui/DialogsActivity (113) | Табы папок (tabIcons, tabCounterMode, hideAllChats, FolderCounters), drawer (navigationDrawer, DrawerContainer), скрыть сторис, FAB и строку поиска, архив по pull (archiveOnPull, disableUnarchiveSwipe), аккаунты (AccountsUiHelper), главное меню (MainMenuHelper), центрирование заголовка, spring-свайпбэк, скругление аватаров |
| 9 | ui/Cells/DialogCell (111) | Скругление аватаров и онлайн-точки, мини-аватары отправителя (senderMiniAvatars), скрытие реакций в списке, бейджи, LocaleUtils (формат чисел и времени) |
| 10 | ui/ActionBar/ActionBarLayout (96) | Анимации переходов (transitionAnimation → spring/AOSP), predictive back (PredictiveBackAnimationHelper, predictiveBackIntensity), WindowTransitionAnimationHelper, вибрация |
| 11 | ui/LaunchActivity (89) | Точка инициализации: `ExteraConfig.init/loadConfig`, логирование, drawer, IconManager / ExteraResources (подмена Resources для иконпаков), IconPickerController, PluginsController, Monet, pillstack (погода), интенты (IntentsController), обновления (UpdaterUtils, updateScheduleTimestamp), громкость снимает mute, WidePosts |
| 12 | ui/ProxyListActivity (85) | Условия автоотключения прокси (ProxyDisableCondition, doNotUseProxy), ProxyController, попапы |
| 13 | messenger/MessageObject (67) | TP; LocaleUtils (фильтр zalgo, форматирование); WidePosts (ширина постов) |
| 14 | ui/Components/FragmentSearchField (60) | PillStack в строке поиска (PillStackView, PillRegistry, BasePill, PillStackConfig) |
| 15 | ui/LoginActivity (58) | Вход по QR (QrCodeLoginView), квадратный FAB (squareFab, FabUiHelper), скругление аватаров |
| 16 | ui/MainTabsActivity (56) | Нижняя навигация 12.x: таб ленты (showFeedTab, FeedActivity), состав меню (MainMenuItem/MainMenuHelper), аккаунты, BottomNavigationBar, hideAllChats |
| 17 | ui/iv/RichEditorListView (52) | только RC |
| 18 | ui/PassportActivity (46) | RC + avatarCorners |
| 19 | messenger/TranslateController (39) | Свой переводчик вместо Telegram (ChatTranslator, ChatTranslationBridge, BaseTranslator, TelegramTranslator, TranslationProviders), подмена Telegram AI-саммари (TelegramAiReplacement, telegramAiSummaries) |
| 20 | ui/Components/FilterTabsView (36) | Иконки папок (FolderIcons, TabIconsMode), hideAllChats, TP |
| 21 | ui/Components/EditTextCaption (35) | только RC |
| 22 | ui/ActionBar/SimpleTextView (33) | только TP |
| 23 | ui/ArticleViewer (32) | AdBlock в IV и вебе (WebAdBlocker), PreformattedScrollView (горизонтальный скролл кода), Markdown, AI-саммари IV (telegramAiInstantViewSummaries) |
| 24 | ui/UserInfoActivity (29) | Настройка now-playing (SetupNowPlayingActivity, NowPlayingController), бейджи, titleText |
| 25 | ui/Components/EmojiPacksAlert (29) | Бейджи авторов паков, вибрация, ChatUtils |
| 26 | messenger/ApplicationLoader (29) | Старт: PluginsController + NativeCrashHandler, IconManager, AppIconController, remote-config, обновления, Heap/Freeze/LoadMonitor, Firebase Crashlytics/Analytics (useGoogle*), автоотключение прокси, `dev.exterahook` (ранний bootstrap) |
| 27 | ui/Cells/ProfileSearchCell (28) | Скругление аватаров, бейджи, hidePhoneNumber |
| 28 | ui/Components/ChatAttachAlertPhotoLayout (27) | Скрыть плитку камеры (hideCameraTile), SystemUtils |
| 29 | ui/PaymentFormActivity (25) | только RC |
| 30 | ui/Components/EmojiView (25) | Бейджи, вибрация, preferences (недавние, unlimitedRecentStickers) |

Остальные заметные хуки:
- `ConnectionsManager` — PluginsHooks перехватывает TL-запросы, `FeedRequestNormalizer`;
- `MessagesController` — hideArchiveFolder, hideStories, PluginsHooks на апдейты;
- `SendMessagesHelper` — PluginsHooks на отправку;
- `AndroidUtilities` — useSystemFonts, tabletMode, inAppVibration, DividerStyle;
- `Switch` / `SeekBarView` / `SlideChooseView` — новые стили переключателей и слайдеров;
- `blur3/*` + `SizeNotifierFrameLayout` — обводка стекла и forceBlur;
- `UItem` / `UniversalAdapter` / `UniversalRecyclerView` — CustomPreferenceCell, разделённые заголовки, sectionRadius;
- `TLRPC` / `TL_iv` / `TL_account` — регистрация TL-классов export/takeout.

### 2.3. Полный список (355 классов, по убыванию)

Обозначения: **TP** — `utils/ui/TextPaint`, **RC** — `components/ReceiveContentEditText`. В графе ExteraConfig — ключи (геттеры без `get/is`, «set …» — запись).
Внимание: часть ссылок может оказаться R8-мерджем (смешанные методы). Перед патчем сверяйтесь со smali (`grep -n 'Lcom/exteragram' decoded/smali*/org/telegram/<путь>*.smali`).

| # | Файл (`org/telegram/…`) | smali-ссылок | jadx-grep | у нас | Классы exteraGram | ExteraConfig (ключи/методы) |
|---|---|---:|---:|:-:|---|---|
| 1 | ui/Cells/ChatMessageCell | 666 | — | да | **TP** StickerTime WidePosts BlendedReplyFileText ForwardsCountUiHelper BadgeDTO BadgesController ChatUtils LocaleUtils DividerStyle PluginsController FeedMessageUtils VoiceRecognitionController | avatarCorners, avatarSquareness, dividerStyle, hideShareButton, onlineDotOffset, removeMessageTail, replaceEditedWithIcon, showOnlineStatus, showResultsBeforeVoting, stickerShape, stickerSize |
| 2 | ui/ChatActivity | 664 | — | да | **TP** **RC** FeedChatIntegration ChatUtils WidePosts FeedController BadgesController MenuContextBuilder SwipeAction SwipeActionsHelper ActionRow IpAddressInfoController FeedMessageUtils BadgeDTO ChatHeaderUiHelper TranslatorUtils PluginsController ForwardContext Client PluginsMenuWrapper GenerateFromMessageBottomSheet GlassMenuHelper PreferencesUtils AiResponseAlert BackupBottomSheet FeedStore PluginsActivity InstantCameraZoomSlider LocaleUtils IconManager DoubleTapUtils MessageDetailsPopupWrapper AiController BottomNavigationBar VoiceRecognitionController SystemUtils TranslationProviders MenuItemRecord MainTabsUiHelper VibratorUtils FeedChannelActions MarkdownUtils | addCommaAfterMention, avatarCorners, bottomButton, customThemes, doubleTapAction, doubleTapActionOutOwner, groupMessageMenu, hideKeyboardOnScroll, hideReactionsInChannels, hideReactionsInGroups, hideReactionsInPrivateChats, quickAdminShortcuts, quickTransitionForChannels, quickTransitionForTopics, replyEmoji, showClearButton, showCopyPhotoButton, showDetailsButton, showGenerateButton, showHistoryButton, showRepeatMessageButton, showReportButton, showSaveMessageButton, widePostsInChannels, widePostsInFeed |
| 3 | ui/ActionBar/Theme | 438 | 11 | да | **TP** DividerStyle MonetUtils | dividerStyle, eventType, forceSnow |
| 4 | ui/Components/InstantCameraView | 392 | — | да | CameraXSession InstantCameraZoomSlider DebugOverlayView RoundVideoEncoder CameraType VideoMessagesCamera SystemUtils CameraDebugUtils CameraZoomSliderView DebugConfig | cameraType, extendedFramesPerSecond, flashIntensity, flashWarmth, rememberLastUsedCamera, set flashIntensity, set flashWarmth, set videoMessagesCamera, staticZoom, videoMessagesCamera |
| 5 | ui/ProfileActivity | 292 | — | да | **TP** ChatHeaderUiHelper BadgeDTO BadgesController NowPlayingCardData MenuContextBuilder SystemUtils NowPlayingDTO ChatUtils SettingsRegistry PluginsMenuWrapper RegDateDTO MainTabsUiHelper PluginsController RegDateController BottomNavigationBar NowPlayingCard ExteraDatabase NowPlayingController TranslatorUtils IconManager IconPackType PluginsActivity MainPreferencesActivity VibratorUtils RemoteUtils UIUtil ApiController AppUtils | avatarCorners, gooeyAvatarAnimation, hidePhoneNumber, hideStories, showIdAndDc, toggleLogging |
| 6 | ui/Components/ChatActivityEnterView | 251 | — | да | **TP** **RC** ChatActivityEnterViewStaticIconView Client ActionRow AiConfig AiController IconManager IconPackType TranslatorUtils VideoMessagesCamera InlineMathController EditServiceActivity AiResponseAlert AiPreferencesActivity TranslateBeforeSendWrapper LocaleUtils OnDeviceAvailability SystemUtils | hideSendAsPeer, telegramAiEditor, videoMessagesCamera |
| 7 | ui/PhotoViewer | 116 | — | да | **TP** VideoSubtitlesHelper ChooseSubtitlesLayout TranslatorUtils ReverseImageSearchSheet SearchPhotoPopupWrapper SystemUtils PopupUtils JpegFingerprint AppUtils MediaUtils | centerTitle, doubleTapSeekDuration, doubleTapSeekDurationMillis, pauseOnMinimizeVideo, preferences, showIdAndDc, swipeToPip |
| 8 | ui/DialogsActivity | 113 | — | да | **TP** **RC** DrawerContainer TabIconsMode FolderCounters AccountsUiHelper MainTabsUiHelper BottomNavigationBar MainMenuHelper TranslatorUtils TabCounterMode LocaleUtils AppUtils AvatarCornerType TranslateBeforeSendWrapper VibratorUtils | archiveOnPull, avatarCorners, centerTitle, disableUnarchiveSwipe, hideAllChats, hideDialogsSearchBar, hideFloatingButton, hideStories, navigationDrawer, springSwipeback, tabCounterMode, tabIcons |
| 9 | ui/Cells/DialogCell | 111 | 34 | да | **TP** BadgesController BadgeDTO AvatarCornerType LocaleUtils | avatarCorners, hideReactionsInChannels, hideReactionsInGroups, hideReactionsInPrivateChats, onlineDotInnerRadius, onlineDotOffset, onlineDotOuterRadius, senderMiniAvatars |
| 10 | ui/ActionBar/ActionBarLayout | 96 | — | да | PredictiveBackAnimationHelper WindowTransitionAnimationHelper VibratorUtils AppUtils | aospTransitions, inAppVibration, predictiveBackIntensity, springAnimations, springSwipeback |
| 11 | ui/LaunchActivity | 89 | — | да | **TP** WidePosts DrawerContainer PluginsController ExteraResources PillType IntentsController IconManager IconPickerController MonetUtils PillStackConfig UpdaterUtils RemoteUtils | init, loadConfig, navigationDrawer, predictiveBackIntensity, set logging, unmuteWithVolumeButtons, updateScheduleTimestamp, widePostsInChannels |
| 12 | ui/ProxyListActivity | 85 | — | да | ProxyController ProxyDisableCondition PopupUtils | proxyDisabledOn, set proxyDisabledOn |
| 13 | messenger/MessageObject | 67 | 3 | да | **TP** LocaleUtils WidePosts |  |
| 14 | ui/Components/FragmentSearchField | 60 | — | да | **RC** PillStackView PillRegistry BasePill PillStackConfig |  |
| 15 | ui/LoginActivity | 58 | — | да | **RC** QrCodeLoginView FabUiHelper UIUtil | avatarCorners, squareFab |
| 16 | ui/MainTabsActivity | 56 | — | да | **TP** MainMenuItem MainTabsUiHelper AccountsUiHelper FeedActivity FeedController MainMenuHelper ChatUtils BottomNavigationBar FeedChannelsActivity | hideAllChats, set showFeedTab, showFeedTab |
| 17 | ui/iv/RichEditorListView | 52 | — | да | **RC** |  |
| 18 | ui/PassportActivity | 46 | — | да | **RC** | avatarCorners |
| 19 | messenger/TranslateController | 39 | 10 | да | TranslatorUtils BaseTranslator ChatTranslator TelegramTranslator ChatTranslationBridge TelegramAiReplacement TranslationProviders AiController | telegramAiSummaries |
| 20 | ui/Components/FilterTabsView | 36 | — | да | **TP** FolderIcons TabIconsMode | hideAllChats, tabIcons |
| 21 | ui/Components/EditTextCaption | 35 | — | да | **RC** |  |
| 22 | ui/ActionBar/SimpleTextView | 33 | 1 | да | **TP** |  |
| 23 | ui/ArticleViewer | 32 | 7 | да | **TP** **RC** PreformattedScrollView WebAdBlocker MarkdownUtils AppUtils | telegramAiInstantViewSummaries |
| 24 | ui/UserInfoActivity | 29 | — | да | NowPlayingInfoDTO NowPlayingServiceType BadgesController SetupNowPlayingActivity NowPlayingController | titleText |
| 25 | ui/Components/EmojiPacksAlert | 29 | — | да | **RC** BadgeDTO BadgesController LocaleUtils ChatUtils VibratorUtils |  |
| 26 | messenger/ApplicationLoader | 29 | 19 | да | ProxyDisableCondition PluginsController IconManager NativeCrashHandler UpdateAppAlertDialog UpdateLayout RemoteUtils AppIconController HeapMonitor LoadMonitor FreezeMonitor UpdaterUtils | pluginsEngine, proxyDisabledOn, useGoogleAnalytics, useGoogleCrashlytics |
| 27 | ui/Cells/ProfileSearchCell | 28 | — | да | **TP** BadgeDTO BadgesController AvatarCornerType | avatarCorners, hidePhoneNumber |
| 28 | ui/Components/ChatAttachAlertPhotoLayout | 27 | — | да | SystemUtils | hideCameraTile |
| 29 | ui/PaymentFormActivity | 25 | — | да | **RC** |  |
| 30 | ui/Components/EmojiView | 25 | — | да | **RC** BadgeDTO BadgesController VibratorUtils | preferences |
| 31 | ui/ActionBar/ActionBarMenuItem | 24 | 10 | да | **RC** ActionRow AvatarCornerType GlassMenuHelper | avatarCorners, centerTitle |
| 32 | messenger/AndroidUtilities | 24 | 11 | да | ProxyController FontUtils DividerStyle | dividerStyle, inAppVibration, tabletMode, useSystemFonts |
| 33 | messenger/MessagesStorage | 24 | — | да | LocaleUtils FolderCounters FeedController |  |
| 34 | ui/Components/SharedMediaLayout | 23 | — | да | **TP** **RC** PluginsController IconManager PreferencesUtils BackupBottomSheet AppUtils LocaleUtils |  |
| 35 | ui/Components/EditTextBoldCursor | 22 | 12 | да | **RC** InlineMathController | disableMarkdown, set disableMarkdown |
| 36 | ui/ProxySettingsActivity | 22 | — | да | **RC** ProxyController |  |
| 37 | ui/Components/Switch | 22 | 1 | да | SwitchUiHelper |  |
| 38 | messenger/MessagesController | 21 | — | да | **TP** PluginsHooks RemoteUtils MonetUtils PluginsController | hideArchiveFolder, hideStories, showResultsBeforeVoting |
| 39 | ui/SelectAnimatedEmojiDialog | 19 | — | да | **RC** VibratorUtils BadgesController BadgeDTO |  |
| 40 | ui/TextMessageEnterTransition | 19 | — | да | **TP** |  |
| 41 | ui/NewContactBottomSheet | 19 | — | да | **RC** VibratorUtils |  |
| 42 | ui/SettingsActivity | 19 | — | да | **TP** MainTabsUiHelper GlassOutlineStyle BottomNavigationBar MainPreferencesActivity DebugActivity AppUtils | avatarCorners, glassOutlineStyle, hidePhoneNumber |
| 43 | ui/Components/TranslateAlert2 | 19 | — | да | TranslatorUtils AiController TelegramAiReplacement TranslationProviders | set translationProvider, translationProvider |
| 44 | ui/Components/ChatAttachAlert | 19 | — | да | **RC** SystemUtils TranslatorUtils LocaleUtils TranslateBeforeSendWrapper | hideCameraTile, telegramAiEditor |
| 45 | messenger/MediaDataController | 19 | — | да | UIUtil ChatUtils LocaleUtils PluginsController | disableMarkdown, squareFab, unlimitedRecentStickers |
| 46 | ui/web/BotWebViewContainer | 18 | 2 | да | **RC** WebAdBlocker |  |
| 47 | ui/ContactsActivity | 17 | — | да | **TP** **RC** MainTabsUiHelper BottomNavigationBar |  |
| 48 | ui/Cells/ChatActionCell | 17 | — | да | AvatarCornerType ChatUtils | avatarCorners |
| 49 | ui/Components/FragmentFloatingButton | 16 | — | да | FabUiHelper UIUtil | squareFab |
| 50 | ui/Components/SuggestEmojiView | 16 | — | да | BadgeDTO BadgesController VibratorUtils |  |
| 51 | ui/Components/glass/GlassTabView | 15 | 6 | да | MainTabsUiHelper IconManager IconPackType | avatarCorners |
| 52 | ui/Stories/DialogStoriesCell | 15 | — | да | **TP** TabIconsMode LocaleUtils | avatarCorners, hideActionBarStatus, tabIcons |
| 53 | ui/ContentPreviewViewer | 15 | — | да | ChatUtils BadgesController VibratorUtils | inAppVibration |
| 54 | ui/Components/TranscribeButton | 15 | — | да | **TP** VoiceRecognitionController ChatUtils MediaLoader | recognitionLanguage |
| 55 | ui/Components/ProfileActionsView | 15 | — | да | IconManager IconPackType DividerStyle VibratorUtils | dividerStyle, sectionRadiusDp |
| 56 | ui/iv/RichTextCell | 15 | — | да | **RC** |  |
| 57 | ui/CallLogActivity | 14 | — | да | **TP** MainTabsUiHelper BottomNavigationBar |  |
| 58 | ui/Components/Reactions/ChatCustomReactionsEditActivity | 14 | — | да | **RC** |  |
| 59 | ui/GroupCallActivity | 13 | — | да | **TP** **RC** IconManager IconPackType | avatarCorners |
| 60 | ui/ActionBar/DrawerLayoutContainer | 13 | — | да | DrawerContainer |  |
| 61 | ui/Stories/recorder/Weather | 13 | 6 | да | PillStackConfig PillType | gSON |
| 62 | ui/ChannelAdminLogActivity | 13 | — | да | **TP** WidePosts ChatHeaderUiHelper |  |
| 63 | ui/Components/ShareAlert | 13 | — | да | **RC** TranslatorUtils TranslateBeforeSendWrapper ForwardContext LocaleUtils PreferencesUtils |  |
| 64 | ui/Components/AlertsCreator | 13 | — | да | **RC** VibratorUtils | avatarCorners |
| 65 | ui/Components/AIEditorAlert | 13 | — | да | **RC** TranslatorUtils TelegramAiReplacement AiController |  |
| 66 | ui/Components/RecyclerListView | 12 | 11 | да | VibratorUtils DividerStyle | dividerStyle, inAppVibration, sectionRadiusDp, sectionsSeparatedHeaders |
| 67 | ui/Components/PullForegroundDrawable | 12 | — | да |  | avatarCorners |
| 68 | ui/Components/UItem | 12 | 4 | да | SettingsRegistry CustomPreferenceCell BasePreferencesActivity SettingItem |  |
| 69 | ui/FilteredSearchView | 12 | — | да | **TP** |  |
| 70 | ui/ActionBar/ActionBar | 12 | 7 | да | **TP** ChatHeaderUiHelper | centerTitle, hideActionBarStatus, navigationDrawer, newChatHeaderStyle |
| 71 | ui/Components/AudioPlayerAlert | 12 | — | да | ChatUtils |  |
| 72 | ui/iv/RichEditText | 12 | 5 | да | **RC** |  |
| 73 | ui/Stories/recorder/StoryPrivacyBottomSheet | 12 | — | да | **TP** VibratorUtils | avatarCorners |
| 74 | ui/Components/ChatAvatarContainer | 11 | — | да | **TP** ChatHeaderUiHelper BadgeDTO |  |
| 75 | ui/TopicsFragment | 11 | — | да | **TP** MainTabsUiHelper BottomNavigationBar FabUiHelper | avatarCorners |
| 76 | ui/ActionBar/MonetAccentHelper | 11 | 1 | **нет** | MonetUtils |  |
| 77 | ui/Cells/SharedAudioCell | 11 | — | да | **TP** |  |
| 78 | ui/Components/ReplyMessageLine | 11 | — | да |  | replyBackground, replyColors, replyEmoji |
| 79 | ui/Components/TextStyleSpan | 10 | 1 | да | FontUtils |  |
| 80 | ui/Cells/UserCell | 10 | — | да | **TP** BadgesController AvatarCornerType BadgeDTO | avatarCorners |
| 81 | ui/Cells/ShareDialogCell | 10 | — | да | **TP** | avatarCorners, onlineDotInnerRadius, onlineDotOffset, onlineDotOuterRadius |
| 82 | messenger/MediaController | 10 | 6 | да | SystemUtils | alwaysSendInHD, customSavePath, pauseOnMinimizeRound, pauseOnMinimizeVoice |
| 83 | ui/StakedDiceSheet | 10 | — | да | **RC** |  |
| 84 | ui/ThemeActivity | 10 | — | да | AppIcon AppIconPreviewDrawable AppIconsActivity AppIconController AppIconPreviewLoader |  |
| 85 | ui/Cells/ContextLinkCell | 10 | — | да | **TP** |  |
| 86 | ui/ChatPullingDownDrawable | 10 | — | да |  | avatarCorners |
| 87 | ui/Stories/recorder/StoryLinkSheet | 10 | — | да | **RC** |  |
| 88 | tgnet/ConnectionsManager | 10 | 3 | да | PluginsHooks PluginsController FeedRequestNormalizer |  |
| 89 | ui/Components/EditTextEffects | 9 | 5 | да | **RC** |  |
| 90 | ui/ChatEditActivity | 9 | — | да | **RC** | avatarCorners |
| 91 | ui/ChannelCreateActivity | 9 | — | да | **RC** | avatarCorners |
| 92 | ui/iv/RichEditor | 9 | — | да | **RC** LocaleUtils | telegramAiEditor |
| 93 | messenger/LocaleController | 9 | 9 | да | LocaleUtils | disableNumberRounding, formatTimeWithSeconds, relativeLastSeen |
| 94 | messenger/SendMessagesHelper | 9 | 4 | да | PluginsHooks LocaleUtils PluginsController MediaUtils |  |
| 95 | ui/ChatEditTypeActivity | 8 | — | да | **RC** |  |
| 96 | ui/Cells/UserInfoCell | 8 | — | да | BadgesController BadgeDTO RegDateController |  |
| 97 | ui/Cells/SharingLiveLocationCell | 8 | — | да | **TP** | avatarCorners |
| 98 | ui/Stars/StarsIntroActivity | 8 | — | да | **TP** **RC** |  |
| 99 | ui/Components/AvatarsDrawable | 7 | — | да |  | avatarCorners |
| 100 | ui/RestrictedLanguagesSelectActivity | 7 | — | да | TranslatorUtils |  |
| 101 | ui/ThemeSetUrlActivity | 7 | — | да | **RC** |  |
| 102 | ui/Components/Reactions/CustomReactionEditText | 7 | — | да | **RC** |  |
| 103 | ui/iv/ChatAttachAlertRichLayout | 7 | — | да | **RC** LocaleUtils |  |
| 104 | ui/Cells/HeaderCell | 6 | 7 | да |  | sectionsSeparatedHeaders |
| 105 | ui/Stories/StoriesController | 6 | — | да |  | hideStories |
| 106 | messenger/RichMessageLayout | 6 | — | да | FontUtils |  |
| 107 | ui/FiltersSetupActivity | 6 | — | да | **TP** FolderIcons VibratorUtils | sectionsSeparatedHeaders |
| 108 | ui/Cells/AboutLinkCell | 6 | — | да | **TP** |  |
| 109 | ui/Cells/AudioPlayerCell | 6 | — | да | **TP** |  |
| 110 | ui/TopicCreateFragment | 6 | — | да | **RC** VibratorUtils |  |
| 111 | ui/Components/EditTextEmoji | 6 | — | да | **RC** LocaleUtils |  |
| 112 | ui/Components/Reactions/ReactionsEffectOverlay | 6 | — | да |  | hideReactionsInChannels, hideReactionsInGroups, hideReactionsInPrivateChats |
| 113 | ui/Components/StickersAlert | 6 | — | да | **RC** ChatUtils |  |
| 114 | ui/Components/Premium/PremiumAppIconsPreviewView | 6 | 2 | да | AppIcon AppIconController |  |
| 115 | messenger/NotificationsController | 6 | 5 | да | AccountNotifications IconManager AppUtils | disableNotificationDelay |
| 116 | ui/CacheControlActivity | 6 | 2 | да | PillType PillStackConfig |  |
| 117 | ui/Components/LineProgressView | 5 | 6 | да |  | newLoadingStyle |
| 118 | ui/Components/UniversalAdapter | 5 | 6 | да | SettingItem TextSetting | sectionsSeparatedHeaders |
| 119 | ui/Components/UniversalRecyclerView | 5 | 6 | да |  | sectionRadiusDp, sectionsSeparatedHeaders |
| 120 | ui/Components/AnimatedEmojiDrawable | 5 | 1 | да | **TP** |  |
| 121 | ui/Components/blur3/drawable/color/impl/BlurredBackgroundProviderImpl | 5 | — | да | FabUiHelper ChatHeaderUiHelper MainTabsUiHelper |  |
| 122 | ui/Components/GroupCreateSpan | 5 | 5 | да |  | avatarCorners |
| 123 | ui/ActionBar/BottomSheet | 5 | 4 | да | VibratorUtils | inAppVibration, predictiveBackIntensity |
| 124 | ui/Stories/StoriesUtilities | 5 | — | да |  | avatarCorners, hideStories |
| 125 | messenger/FactCheckController | 5 | — | да | **RC** |  |
| 126 | ui/Adapters/FiltersView | 5 | — | да | AvatarCornerType | avatarCorners |
| 127 | ui/FilterCreateActivity | 5 | — | да | IconSelectorAlert FolderIcons |  |
| 128 | ui/Cells/ExpiredStoryView | 5 | — | да | **TP** |  |
| 129 | ui/PollCreateActivity | 5 | — | да | **RC** |  |
| 130 | ui/ChangeNameActivity | 5 | — | да | **RC** |  |
| 131 | ui/TwoStepVerificationSetupActivity | 5 | — | да | **RC** |  |
| 132 | ui/web/WebActionBar | 5 | — | да | **RC** WebAdBlocker AdBlockMenuItem |  |
| 133 | ui/NotificationsSettingsActivity | 5 | — | да | AccountNotifications AccountNotificationsActivity |  |
| 134 | ui/Components/ChatAttachAlertPollLayout | 5 | — | да | **RC** |  |
| 135 | ui/Components/SlideChooseView | 5 | 3 | да | MaterialSliderUiHelper | newSliderStyle |
| 136 | ui/Components/SimpleAvatarView | 5 | — | да |  | avatarCorners |
| 137 | ui/Components/SeekBarView | 5 | 3 | да | MaterialSliderUiHelper | newSliderStyle |
| 138 | ui/Components/Paint/Views/TextPaintView | 5 | — | да | **RC** |  |
| 139 | ui/iv/RichInlineButtonEditor | 5 | — | да | **RC** |  |
| 140 | ui/Stories/PeerStoriesView | 5 | — | да | **TP** **RC** VibratorUtils | avatarCorners |
| 141 | ui/Components/ImageUpdater | 4 | — | да | SystemUtils |  |
| 142 | ui/ActionBar/BaseFragment | 4 | 2 | да | IconManager IconObserver |  |
| 143 | messenger/PasskeysController | 4 | — | да | PasskeysUtil |  |
| 144 | ui/PasscodeActivity | 4 | — | да | **RC** |  |
| 145 | ui/PeerColorActivity | 4 | — | да | **TP** | avatarCorners |
| 146 | ui/Cells/BotHelpCell | 4 | — | да | **TP** |  |
| 147 | ui/Cells/EditTextCell | 4 | — | да | **RC** |  |
| 148 | ui/PhotoPickerActivity | 4 | — | да | LocaleUtils UIUtil | squareFab |
| 149 | ui/EditWidgetActivity | 4 | 9 | да |  | avatarCorners |
| 150 | ui/Business/ChatbotsActivity | 4 | — | да | **RC** |  |
| 151 | ui/Components/Reactions/ReactionsLayoutInBubble | 4 | — | да |  | avatarCorners, hideReactionsInChannels, hideReactionsInGroups, hideReactionsInPrivateChats |
| 152 | ui/Components/TranslateButton | 4 | — | да | TranslationProviders |  |
| 153 | ui/Components/MarkdownParser | 4 | 2 | да | MarkdownUtils |  |
| 154 | ui/Components/Premium/boosts/cells/msg/GiveawayMessageCell | 4 | — | да | **TP** |  |
| 155 | ui/Components/Premium/AdaptiveIconImageView | 4 | 1 | **нет** | IconShapeHelper |  |
| 156 | ui/Components/SenderSelectView | 4 | — | да |  | avatarCorners |
| 157 | ui/bots/BotVerifySheet | 4 | — | да | **RC** |  |
| 158 | ui/community/CommunitySheet | 4 | — | да | **RC** AvatarCornerType | avatarCorners |
| 159 | ui/Components/blur3/drawable/BlurredBackgroundDrawable | 3 | 3 | да | GlassOutlineStyle | glassOutlineStyle |
| 160 | ui/Components/ViewPagerFixed | 3 | 1 | да | AppUtils |  |
| 161 | ui/Stories/recorder/StoryRecorder | 3 | — | да | **TP** SystemUtils |  |
| 162 | ui/ChannelMonetizationLayout | 3 | — | да | **RC** |  |
| 163 | ui/Cells/UserCell2 | 3 | — | да | **TP** | avatarCorners |
| 164 | ui/Cells/DialogMeUrlCell | 3 | — | да | **TP** | avatarCorners |
| 165 | ui/Cells/PollEditTextCell | 3 | — | да | **RC** |  |
| 166 | ui/Cells/GroupCreateUserCell | 3 | — | да | **TP** | avatarCorners |
| 167 | ui/UsersSelectActivity | 3 | — | да | **TP** **RC** |  |
| 168 | ui/CodeFieldContainer | 3 | 1 | да | **RC** |  |
| 169 | ui/GroupCreateFinalActivity | 3 | — | да |  | avatarCorners |
| 170 | ui/PasskeysActivity | 3 | — | да | PasskeysUtil |  |
| 171 | ui/Business/LocationActivity | 3 | — | да | **RC** |  |
| 172 | ui/Business/QuickRepliesActivity | 3 | — | да | **RC** |  |
| 173 | ui/Business/BusinessLinksActivity | 3 | — | да | **RC** |  |
| 174 | ui/Stars/BotStarsActivity | 3 | — | да | **RC** |  |
| 175 | ui/Components/EmojiTabsStrip | 3 | 2 | да | IconManager IconPackType |  |
| 176 | ui/Components/MentionsContainerView | 3 | — | да |  | addCommaAfterMention |
| 177 | ui/Components/SearchTagsList | 3 | — | да | **RC** |  |
| 178 | ui/Components/PollVotesAlert | 3 | — | да | **TP** | avatarCorners |
| 179 | ui/Components/StatusBadgeComponent | 3 | — | да | BadgesController BadgeDTO |  |
| 180 | ui/Components/Paint/Views/LPhotoPaintView | 3 | — | да | **RC** |  |
| 181 | ui/Components/AvatarConstructorFragment | 3 | — | да |  | avatarCorners |
| 182 | ui/Components/ReactionsContainerLayout | 3 | 1 | да | GlassMenuHelper |  |
| 183 | ui/Components/blur3/StrokeDrawable | 3 | 3 | да | GlassOutlineStyle | glassOutlineStyle |
| 184 | ui/Gifts/ProfileGiftsContainer | 3 | — | да | **RC** |  |
| 185 | ui/community/CommunityEditActivity | 3 | — | да | **RC** AvatarCornerType | avatarCorners |
| 186 | ui/iv/RichTableCell | 3 | — | да | **RC** |  |
| 187 | ui/Stories/StoryViewer | 3 | — | да |  | avatarCorners |
| 188 | ui/Stories/recorder/DraftsController | 3 | — | да | **TP** |  |
| 189 | ui/Stories/recorder/PaintView | 3 | — | да | **RC** |  |
| 190 | messenger/Emoji | 3 | 2 | да | ZalgoFilter FontUtils |  |
| 191 | messenger/FileUploadOperation | 3 | — | да |  | uploadSpeedBoost |
| 192 | ui/AvatarSpan | 3 | — | да |  | avatarCorners |
| 193 | ui/Components/SizeNotifierFrameLayout | 2 | 4 | да |  | forceBlur |
| 194 | ui/Components/MediaActionDrawable | 2 | 1 | да | PluginsController |  |
| 195 | ui/Components/ItemOptions | 2 | 1 | да | VibratorUtils |  |
| 196 | ui/Components/blur3/drawable/BlurredBackgroundDrawableRenderNode | 2 | 4 | да | GlassOutlineStyle | glassOutlineStyle |
| 197 | messenger/camera/Camera2Session | 2 | 3 | да |  | cameraStabilization, extendedFramesPerSecond |
| 198 | messenger/ContactsRemoteViewsFactory | 2 | 3 | **нет** |  | avatarCorners |
| 199 | messenger/LanguageDetector | 2 | — | да | RuntimeCompatibility |  |
| 200 | messenger/ChatsRemoteViewsFactory | 2 | 3 | **нет** |  | avatarCorners |
| 201 | messenger/MusicPlayerService | 2 | — | да | IconManager AppUtils |  |
| 202 | ui/ContactAddActivity | 2 | — | да |  | avatarCorners |
| 203 | ui/Cells/ReactedUserHolderView | 2 | — | да | **TP** | avatarCorners |
| 204 | ui/Cells/PhotoAttachPhotoCell | 2 | — | да |  | alwaysSendInHD |
| 205 | ui/Cells/ManageChatUserCell | 2 | — | да | **TP** | avatarCorners |
| 206 | ui/Cells/TextSelectionHelper | 2 | 2 | да | TranslatorUtils VibratorUtils |  |
| 207 | ui/Cells/AccountSelectCell | 2 | — | да | **TP** | avatarCorners |
| 208 | ui/Cells/DividerCell | 2 | 3 | да | DividerStyle | dividerStyle |
| 209 | ui/LinkEditActivity | 2 | — | да | **RC** VibratorUtils |  |
| 210 | ui/PhotoAlbumPickerActivity | 2 | — | да | UIUtil | squareFab |
| 211 | ui/LanguageSelectActivity | 2 | — | да | GeneralPreferencesActivity |  |
| 212 | ui/Stars/MessageSuggestionOfferSheet | 2 | — | да | **RC** |  |
| 213 | ui/Stars/StarsReactionsSheet | 2 | — | да |  | avatarCorners |
| 214 | ui/Stars/GiftOfferSheet | 2 | — | да | **RC** |  |
| 215 | ui/Stars/SellGiftEnterPriceSheet | 2 | — | да | **RC** |  |
| 216 | ui/Components/ChatAttachAlertContactsLayout | 2 | — | да | **TP** | avatarCorners |
| 217 | ui/Components/Reactions/CustomEmojiReactionsWindow | 2 | — | да | GlassMenuHelper |  |
| 218 | ui/Components/Reactions/ChatSelectionReactionMenuOverlay | 2 | — | да | GlassMenuHelper |  |
| 219 | ui/Components/HintEditText | 2 | — | да | **RC** |  |
| 220 | ui/Components/TagEditCell | 2 | — | да | **RC** |  |
| 221 | ui/Components/GroupCallPipAlertView | 2 | — | да | VibratorUtils | avatarCorners |
| 222 | ui/Components/StickersDialogs | 2 | — | да | **RC** |  |
| 223 | ui/Components/Premium/boosts/cells/BoostTypeCell | 2 | — | да | **TP** |  |
| 224 | ui/Components/Premium/boosts/cells/msg/GiveawayResultsMessageCell | 2 | — | да | **TP** |  |
| 225 | ui/Components/ChatAttachAlertAudioLayout | 2 | — | да | **TP** |  |
| 226 | ui/Components/ColorPicker | 2 | — | да | **RC** |  |
| 227 | ui/Components/WallpaperUpdater | 2 | — | да | SystemUtils |  |
| 228 | ui/Components/InstantCameraViewBase | 2 | — | **нет** | CameraType | cameraType |
| 229 | ui/Components/RadialProgress2 | 2 | — | да |  | newLoadingStyle |
| 230 | ui/Gifts/AuctionBidSheet | 2 | — | да | **RC** |  |
| 231 | ui/community/cells/CommunityLinkView2 | 2 | — | да | AvatarCornerType | avatarCorners |
| 232 | ui/community/cells/CommunityLinkView | 2 | — | да | AvatarCornerType | avatarCorners |
| 233 | ui/community/CommunityCreateActivity | 2 | — | да | AvatarCornerType | avatarCorners |
| 234 | ui/iv/RichTableCellGrid | 2 | — | да | **RC** |  |
| 235 | ui/iv/RichAIComposeSheet | 2 | — | да | **RC** |  |
| 236 | ui/iv/RichEditorToolbar | 2 | — | да |  | telegramAiEditor |
| 237 | ui/iv/RichQuoteAuthorCell | 2 | — | да | **RC** |  |
| 238 | ui/iv/RichCaptionController | 2 | — | да | **RC** |  |
| 239 | ui/iv/RichDetailsCell | 2 | — | да | **RC** |  |
| 240 | ui/Stories/recorder/EmojiBottomSheet | 2 | — | да | **RC** |  |
| 241 | ui/Stories/recorder/StoryEntry | 2 | — | да | **TP** |  |
| 242 | messenger/SharedConfig | 2 | 1 | да | ProxyController |  |
| 243 | messenger/ProxyRotationController | 2 | — | да | ProxyController |  |
| 244 | messenger/FilesMigrationService | 2 | — | да | IconManager |  |
| 245 | messenger/FileLoadOperation | 2 | — | да |  | downloadSpeedBoost |
| 246 | ui/Components/VideoPlayer | 1 | 4 | да |  | preferOriginalQuality |
| 247 | ui/Components/RadialProgressView | 1 | 2 | да |  | newLoadingStyle |
| 248 | ui/Components/AvatarDrawable | 1 | — | да |  | avatarCorners |
| 249 | ui/Components/ShareTopView | 1 | — | да | **TP** |  |
| 250 | ui/Components/blur3/BlurredBackgroundWithFadeDrawable | 1 | 1 | да | DebugConfig |  |
| 251 | ui/Components/spoilers/SpoilerEffect | 1 | — | да | **TP** |  |
| 252 | ui/Components/UndoView | 1 | — | да |  | avatarCorners |
| 253 | ui/Adapters/DialogsAdapter | 1 | 2 | да |  | hideFloatingButton |
| 254 | ui/Adapters/DialogsSearchAdapter | 1 | — | да | RemoteUtils |  |
| 255 | ui/TwoStepVerificationActivity | 1 | — | да | **RC** |  |
| 256 | ui/Cells/TextCheckCell2 | 1 | 1 | да | SwitchUiHelper |  |
| 257 | ui/Cells/DialogsHintCell | 1 | — | да |  | avatarCorners |
| 258 | ui/ViewPagerActivity | 1 | — | да | BottomNavigationBar |  |
| 259 | ui/web/SearchEngine | 1 | 3 | да |  | yandexSearchEngine |
| 260 | ui/RightSlidingDialogContainer | 1 | — | да | AppUtils |  |
| 261 | ui/ActionBar/ActionBarMenuSubItem | 1 | — | да | UIUtil |  |
| 262 | messenger/HashtagSearchController | 1 | — | да | ChatUtils |  |
| 263 | messenger/BotWebViewVibrationEffect | 1 | 2 | да |  | inAppVibration |
| 264 | messenger/video/TextureRenderer | 1 | — | да | **RC** |  |
| 265 | messenger/video/VideoAds | 1 | — | да | RemoteUtils |  |
| 266 | ui/Charts/BaseChartView | 1 | 1 | да | VibratorUtils |  |
| 267 | ui/ChangeUsernameActivity | 1 | — | да |  | sectionsSeparatedHeaders |
| 268 | ui/MessageSeenView | 1 | — | да |  | avatarCorners |
| 269 | ui/ChatLinkActivity | 1 | — | да |  | avatarCorners |
| 270 | ui/FilterChatlistActivity | 1 | — | да | **RC** |  |
| 271 | ui/PrivacyControlActivity | 1 | — | да |  | avatarCorners |
| 272 | ui/GroupCreateActivity | 1 | — | да | **RC** |  |
| 273 | ui/QuickRepliesSettingsActivity | 1 | — | да | **RC** |  |
| 274 | ui/Cells/MemberRequestCell | 1 | — | да |  | avatarCorners |
| 275 | ui/Cells/EditTextSettingsCell | 1 | — | да | **RC** |  |
| 276 | ui/Cells/TextCheckCell | 1 | 1 | да | LocaleUtils |  |
| 277 | ui/Cells/DialogsEmptyCell | 1 | 2 | да |  | hideFloatingButton |
| 278 | ui/Cells/ThemePreviewMessagesCell | 1 | — | да |  | avatarCorners |
| 279 | ui/Cells/GroupCallInvitedCell | 1 | — | да |  | avatarCorners |
| 280 | ui/Cells/TextCell | 1 | — | да | **TP** |  |
| 281 | ui/Cells/StatisticPostInfoCell | 1 | — | да | **TP** |  |
| 282 | ui/Cells/StickerCell | 1 | — | да |  | stickerShape |
| 283 | ui/Cells/GroupCallUserCell | 1 | — | да |  | avatarCorners |
| 284 | ui/Cells/StickerEmojiCell | 1 | — | да |  | stickerShape |
| 285 | ui/Cells/SharedLinkCell | 1 | — | да | **TP** |  |
| 286 | ui/Cells/InviteUserCell | 1 | — | да |  | avatarCorners |
| 287 | ui/Cells/HintDialogCell | 1 | — | да |  | avatarCorners |
| 288 | ui/Cells/TextDetailCell | 1 | — | да | VibratorUtils |  |
| 289 | ui/Cells/EditEmojiTextCell | 1 | — | да | **RC** |  |
| 290 | ui/Cells/MentionCell | 1 | — | да |  | avatarCorners |
| 291 | ui/Cells/AvailableReactionCell | 1 | — | да | **TP** |  |
| 292 | ui/Cells/AdminedChannelCell | 1 | — | да |  | avatarCorners |
| 293 | ui/SelectChatUserSheet | 1 | — | да | **RC** |  |
| 294 | ui/LinkManager | 1 | — | да |  | telegramAiEditor |
| 295 | ui/PopupNotificationActivity | 1 | — | да |  | avatarCorners |
| 296 | ui/SecretMediaViewer | 1 | — | да | **TP** |  |
| 297 | ui/CodeNumberField | 1 | 3 | да | **RC** |  |
| 298 | ui/CachedMediaLayout | 1 | — | да |  | avatarCorners |
| 299 | ui/GradientHeaderActivity | 1 | — | да |  | sectionRadiusDp |
| 300 | ui/EmojiAnimationsOverlay | 1 | — | да | VibratorUtils |  |
| 301 | ui/SessionBottomSheet | 1 | — | да | UIUtil |  |
| 302 | ui/LiteModeSettingsActivity | 1 | — | да | VibratorUtils |  |
| 303 | ui/QrActivity | 1 | — | да | VibratorUtils |  |
| 304 | ui/Business/BusinessBotButton | 1 | — | да |  | avatarCorners |
| 305 | ui/ActionBar/ActionBarPopupWindow | 1 | 2 | да |  | groupMessageMenu |
| 306 | ui/ActionBar/MessageDrawable | 1 | 2 | да |  | removeMessageTail |
| 307 | ui/MessageSendPreview | 1 | — | да | **TP** |  |
| 308 | ui/Components/JoinCallByUrlAlert | 1 | — | да |  | avatarCorners |
| 309 | ui/Components/ChatAttachAlertDocumentLayout | 1 | — | да | **RC** |  |
| 310 | ui/Components/MessagePreviewView | 1 | — | да | StickerTime |  |
| 311 | ui/Components/UsersAlertBase | 1 | — | да | **RC** |  |
| 312 | ui/Components/EarListener | 1 | — | да | SystemUtils |  |
| 313 | ui/Components/chat/buttons/ChatActivityBlurredRoundButton | 1 | 2 | да |  | newLoadingStyle |
| 314 | ui/Components/TopicsTabsView | 1 | — | да |  | avatarCorners |
| 315 | ui/Components/SearchField | 1 | 1 | да | **RC** |  |
| 316 | ui/Components/ForwardBackground | 1 | — | да | **TP** |  |
| 317 | ui/Components/SenderSelectPopup | 1 | — | да |  | avatarCorners |
| 318 | ui/Components/PhotoFilterView | 1 | 1 | да | VibratorUtils |  |
| 319 | ui/Components/BackButtonMenu | 1 | — | да |  | avatarCorners |
| 320 | ui/Components/PhotoViewerWebView | 1 | 2 | да |  | centerTitle |
| 321 | ui/Components/AnimatedPhoneNumberEditText | 1 | 1 | да | **RC** |  |
| 322 | ui/Components/ChatGreetingsView | 1 | — | да |  | disableGreetingSticker |
| 323 | ui/Components/Crop/CropRotationWheel | 1 | 1 | да | VibratorUtils |  |
| 324 | ui/Components/ReportAlert | 1 | — | да | **RC** |  |
| 325 | ui/Components/ThemeEditorView | 1 | — | да | **RC** |  |
| 326 | ui/Components/voip/VoIPHelper | 1 | — | да | **RC** |  |
| 327 | ui/Components/PhonebookShareAlert | 1 | — | да |  | avatarCorners |
| 328 | ui/Components/CreateBotAlert | 1 | — | да | **RC** |  |
| 329 | ui/Components/poll/buttons/PollAddButtonDrawable | 1 | — | да | **TP** |  |
| 330 | ui/Components/poll/PollAddOptionFieldLayout | 1 | — | да | **RC** |  |
| 331 | ui/Components/Premium/boosts/cells/ChatCell | 1 | — | да | **TP** |  |
| 332 | ui/Components/Premium/boosts/cells/BaseCell | 1 | — | да | **TP** |  |
| 333 | ui/Components/SectionsScrollView | 1 | — | да |  | sectionRadiusDp |
| 334 | ui/Components/Paint/Views/EditTextOutline | 1 | 2 | да | **RC** |  |
| 335 | ui/Components/Paint/Views/StickerMakerView | 1 | — | да |  | editor |
| 336 | ui/Components/LetterDrawable | 1 | — | да | **TP** |  |
| 337 | ui/Components/PasscodeView | 1 | — | да | **RC** |  |
| 338 | ui/Components/GroupCallFullscreenAdapter | 1 | — | да |  | avatarCorners |
| 339 | ui/Components/JoinGroupAlert | 1 | — | да |  | avatarCorners |
| 340 | ui/Components/CircularProgressDrawable | 1 | 2 | да |  | newLoadingStyle |
| 341 | ui/Components/MediaActivity | 1 | — | да |  | avatarCorners |
| 342 | ui/Components/BlockingUpdateView | 1 | — | да | UpdaterUtils |  |
| 343 | ui/Components/CaptionPhotoViewer | 1 | — | да |  | telegramAiEditor |
| 344 | ui/Gifts/AcquiredGiftsSheet | 1 | — | да | **TP** |  |
| 345 | ui/Gifts/GiftSheet | 1 | — | да |  | avatarCorners |
| 346 | ui/iv/RichTableCellHost | 1 | — | да | **RC** |  |
| 347 | ui/Delegates/MemberRequestsDelegate | 1 | — | да |  | avatarCorners |
| 348 | ui/Stories/ProfileStoriesView | 1 | — | да |  | avatarCorners |
| 349 | ui/Stories/recorder/PreviewHighlightView | 1 | — | да | **TP** |  |
| 350 | ui/Stories/recorder/DualCameraView | 1 | — | да | VibratorUtils |  |
| 351 | ui/Stories/recorder/CaptionContainerView | 1 | — | да | LocaleUtils |  |
| 352 | messenger/FileRefController | 1 | — | да | UpdaterUtils |  |
| 353 | messenger/FingerprintController | 1 | 1 | да | SystemUtils |  |
| 354 | messenger/BuildVars | 1 | 2 | да |  | logging |
| 355 | messenger/ChatThemeController | 1 | — | да |  | customThemes |

### 2.4. Классы org/telegram из exteraGram, которых нет у нас

Сравнивали внешние классы smali (2 061) с нашими .java (1 885). Искали и вложенные объявления: `grep "class X"` по нашему дереву.

**Среди хукнутых файлов (355)** без пары у нас 5:

| Класс | Статус у нас | Что это |
|---|---|---|
| `ui/ActionBar/MonetAccentHelper` | **нет** | Добавлен exteraGram (11 ссылок на `utils/ui/MonetUtils`); используют Theme и ThemeActivity — Monet-акценты Material You. Портировать на этапе 2. |
| `ui/Components/InstantCameraViewBase` | **нет** | Общая база для InstantCameraView и InstantCameraView2 (новая камера кружков). Читает `cameraType`. Скорее всего, апстрим 12.10.6 + хук extera. Этап 4. |
| `ui/Components/Premium/AdaptiveIconImageView` | есть, внутри `PremiumAppIconsPreviewView.java` | Хук IconShapeHelper (форма иконок) |
| `messenger/ChatsRemoteViewsFactory` | есть, внутри `ChatsWidgetService.java` | Хук avatarCorners для виджета |
| `messenger/ContactsRemoteViewsFactory` | есть, внутри `ContactsWidgetService.java` | Хук avatarCorners для виджета |

**Остальные классы org/telegram, которых у нас нет.** Ссылок на exteragram в них нет (кроме отмеченных). Это разница версий 12.10.5 → 12.10.6 или рефакторинг exteraGram; хуки extera от них зависят:

| Группа | Классы | Кто использует |
|---|---|---|
| Новая камера кружков | `ui/Components/InstantCameraView2` (16 smali), `RoundVideoProgressView`, `TelegramRoundVideoUpload`, `ui/RoundVideoSettingsActivity`, `utils/camera/roundvideo/*` (RoundVideoSession — 48 smali, CameraController, CodecRecorder, GlProcessor, Mp4Writer, OverlayRenderer, Remuxer, Diagnostics, SwitchTimingStore), `utils/settings/{SettingsPreferences,BooleanSetting,EnumSetting,IntSetting}` | ChatActivity, ChatActivityEnterView, SettingsActivity |
| Веб-прокси | `utils/proxy/{ProxySettings,WebProxyTransport,WebProxyConnectionTester}`, `messenger/ProxyPingController` | ConnectionsManager, SharedConfig, AndroidUtilities, ApplicationLoader, ProxySettingsActivity, ProxyRotationController, **exteragram/proxy/ProxyController**; строка `UseProxyWeb` |
| Стекло | `utils/glass/{GlassEngine,positions/GlassPositionsArray,GlassPositionsMerger}` | BaseFragment, ChannelAdminLogActivity, blur3/BlurredBackgroundDrawableViewFactory, EmojiView |
| Локализация | `utils/localization/Localization`, `localization/LocalizationUtils`, assets `localization_*.bin`, `string_resource_ids.bin`, `raw/strings_discard.xml` | LocaleController: строки Telegram перенесены из `res/values/strings.xml` (осталось 1 017) в бинарные `.bin` |
| Прочее | `messenger/ResLottieMeta` (у нас это gradle-плагин `org.telegram.lottie-meta` → `lottie_meta.bin`), `ui/AspectRatioFrameLayout`, `ui/Components/StaticgramInvalidateRunnable` (патч exteraStatic из README) | RLottieDrawable; MediaController, ArticleViewer, ChatActivity, PipRoundVideoView, export/ChatViewer |

**Классы, которые есть только у нас** (91: voip/VoIPController*, customtabs, TestActivity, ProfileActivity2, LauncherIconController и т.п.). В основном это мёртвый код, вырезанный R8 в exteraGram, на порт не влияет. Важное исключение — `tgnet/SgServerResolver`, `StaticGramLaunchSplash`, `FeedWidgetConfigActivity`: это наши изменения, их сохраняем.

**Плеер.** У exteraGram VideoPlayer, MediaController, FileStreamLoadOperation, pip/*, chromecast/ChromecastFileServer работают на `androidx.media3` (в VideoPlayer 384 ссылки на media3), пакета `com.google.android.exoplayer2` нет. У нас лежат исходники exoplayer2 в `src/main/java/com/google/android/exoplayer2` и `jni/exoplayer`. Хуки, связанные с плеером (preferOriginalQuality, субтитры в VideoPlayer), переносить точечно — на media3 не мигрировать.

---

## 3. Сторонние библиотеки и нативка

### 3.1. Gradle-зависимости (угадано по пакетам `decompiled/sources/*` и `decoded/unknown/META-INF/*.version`)

| Пакет в APK | Gradle-артефакт (версия из META-INF, если есть) | Кто использует | У нас в TMessagesProj/build.gradle |
|---|---|---|---|
| `kotlin.*` | org.jetbrains.kotlin:kotlin-stdlib (2.x) | 211 Kotlin-классов | **нет** (только constraints на jdk7/jdk8 1.8.0) |
| `kotlinx.coroutines` | kotlinx-coroutines-core/android **1.11.0** (+guava, play-services) | api, badges, icons, nowplaying, plugins | **нет** |
| `kotlinx.serialization` | kotlinx-serialization-json (+ плагин компилятора) | nowplaying | **нет** |
| `kotlinx.atomicfu` | transitively (coroutines) | — | нет |
| `okhttp3`, `okio` | com.squareup.okhttp3:okhttp **5.3.2** | adblock, ai, api, nowplaying, pillstack, proxy, speech, translator, utils/network | **нет** |
| `retrofit2`, `retrofit2.converter.gson` | com.squareup.retrofit2:retrofit + converter-gson | api, nowplaying | **нет** |
| `androidx.room`, `androidx.sqlite` | room-runtime/room-ktx **2.8.4** (+ room-compiler через KSP/kapt), sqlite 2.6.2 | api/db | **нет** |
| `androidx.work` | work-runtime(-ktx) **2.11.2** | api/worker/SyncWorker, ApplicationLoader | **нет** |
| `androidx.datastore` | datastore(-preferences) **1.1.7** | транзитивно (камера/кредентиалы); .so `libdatastore_shared_counter` | нет |
| `androidx.camera` | camera-core/camera2/camera2-pipe/lifecycle **1.6.2** | camera, InstantCameraView, speech/plugins (ProcessCameraProvider) | **нет** |
| `androidx.media3` | media3-exoplayer/common/datasource/extractor/session/decoder | VideoPlayer и др. (см. §2.4), nowplaying, components | **нет** (у нас exoplayer2 в исходниках) |
| `com.google.android.material` | material **1.14.0** | utils/ui (слайдеры, MaterialShapes), preferences, ActionBarLayout, LineProgressView, CircularProgressDrawable | **нет** |
| `androidx.graphics.shapes` | graphics-shapes 1.0.1 | транзитивно от material 1.14 (MaterialShapes); прямых ссылок нет | нет |
| `androidx.webkit` | webkit **1.17.0** | BotWebViewContainer, WebProxyTransport, adblock | **нет** |
| `androidx.constraintlayout` | constraintlayout 2.2.1 | транзитивно (material) | нет |
| `androidx.appcompat`, fragment, activity, lifecycle 2.11, savedstate | appcompat 1.7.0, activity 1.8.0 | debug, icons (ActivityResult), CameraX lifecycle | нет (у нас только core 1.16.0) |
| `androidx.core` | core **1.18.0** / core-ktx | везде | есть — 1.16.0 (поднять) |
| `androidx.dynamicanimation` | dynamicanimation(-ktx) 1.1.0 | drawer, icons, utils/ui | есть — 1.0.0 |
| `androidx.exifinterface` | 1.4.2 | components | есть — 1.3.6 |
| `androidx.palette` | 1.0.0 (-ktx) | nowplaying | есть |
| `androidx.credentials` | 1.6.0 | utils/PasskeysUtil | есть — 1.6.0-alpha04 |
| `androidx.biometric`, car.app, mediarouter, sharetarget | как у нас | Telegram | есть |
| `androidx.privacysandbox.ads` | ads-adservices 1.1.0-beta11 | транзитивно (Firebase Analytics) | нет |
| `androidx.security` | security-crypto 1.1.0-alpha03 | прямых ссылок из exteragram/org.telegram нет (транзитивно) | нет — не нужен |
| `com.google.gson` | gson | ExteraConfig, backup, api, pillstack | есть 2.11.0 |
| `com.google.common` | guava | ai, camera (ListenableFuture) | есть 31.1-android |
| `com.google.zxing` | zxing core | components/QRCodeSheet, QrCodeLoginView | есть 3.5.4 |
| `com.google.mlkit.nl.languageid` | language-id | LanguageDetector | есть 17.0.6 |
| `com.google.mlkit.vision.{label,segmentation}` | play-services-mlkit-* | Telegram | есть |
| `com.google.mlkit.genai.prompt` | com.google.mlkit:genai-prompt (Gemini Nano, AICore) | ai/network/backend/OnDeviceBackend | **нет** |
| `com.google.firebase.crashlytics/analytics/remoteconfig/sessions/abt` | firebase-crashlytics, firebase-analytics (remoteconfig — транзитивно) | ApplicationLoader (useGoogleCrashlytics/Analytics) | **нет** (у нас messaging, datatransport, appindexing). Для StaticGram **не нужны** — выпилить флаги. |
| `com.google.protobuf` | protobuf-javalite | RoundVideoCodecRecorder (апстрим 12.10.6?), Firebase | нет |
| `com.caverock.androidsvg` | com.caverock:androidsvg(-aar) | icons/IconManager, ReplaceIconBottomSheet (SVG-иконки) | **нет** |
| `org.simplifiles` | неизвестная Kotlin-библиотека «simplifiles» (`simplifiles.kotlin_module`; archive/files) — вероятно, собственная библиотека команды extera, в Maven не ищется | icons (zip-иконпаки), plugins | **нет** — переписать на java.util.zip/File |
| `com.chaquo.python` | Chaquopy (Gradle-плагин `com.chaquo.python`, Python 3.11) | plugins | **нет** |
| `de.robv.android.xposed` | Xposed API **шим** (6 классов: XposedBridge, XC_MethodHook, XC_MethodReplacement, callbacks, HookCallbackDispatcher), реализован поверх dev.exterahook | plugins/hooks, plugins/xposed | **нет** — переносить исходниками |
| `dev.exterahook.runtime` | собственный runtime extera (`bridge/HookBridgeProvider`, `JniBridgeBindings`, `internal/ExteraHookInitProvider`); native — в libtmessages (LSPlant) | ApplicationLoader, Xposed-шим | **нет** |
| `com.bytedance.shadowhook` | com.bytedance.android:shadowhook (из Java не вызывается; libtmessages линкуется с libshadowhook.so) | native exterahook | **нет** |
| `org.lsposed.hiddenapibypass` | org.lsposed.hiddenapibypass:hiddenapibypass | Xposed-шим `de/robv/.../XposedBridge` | **нет** (этап 5) |
| `com.android.dx` | dexmaker (com.linkedin.dexmaker:dexmaker) | plugins/utils/ClassProxy | **нет** |
| `org.mvel2` | org.mvel:mvel2 | plugins/hooks/HookFilter, MenuItemRecord, ClassProxy (выражения-фильтры) | **нет** |
| `com.sun.jna` | net.java.dev.jna:jna (aar) | vosk-android | **нет** |
| `org.vosk` | com.alphacephei:vosk-android | speech | **нет** |
| `io.noties.markwon`, `org.commonmark`, `ru.noties.jlatexmath`, `org.scilab.forge` | markwon 4.6.2 + ext-latex + jlatexmath | utils/MarkdownUtils, ai/ui/MarkdownPreview, Telegram | есть (+ модуль `:jlatexmath`) |
| `fi.iki.elonen` | nanohttpd | Telegram (chromecast) | есть 2.3.1 |
| `me.vkryl` | исходники Telegram-X utils | Telegram | есть (в src) |
| `org.chromium.net` | Cronet (транзитивно от gms/ML Kit) | прямых ссылок из exteragram/org.telegram нет | нет — не нужен |
| `org.aspectj` | aspectjrt | прямых ссылок нет (транзитивно) | нет — не нужен |
| `dagger`, `javax.inject` | dagger 2.59 | транзитивно (Firebase/ML Kit) | нет |
| `com.googlecode.mp4parser`, `com.coremedia`, `com.stripe`, `com.android.billingclient`, gms | как у нас | Telegram | есть |
| `a`, `b`, `c` (обфусцированные) | — | артефакты R8 | — |

**Итог по Gradle.** Минимум для этапов 1–3: `kotlin-stdlib`, а при Kotlin-исходниках ещё и плагин `kotlin-android`. Для этапа 4: `okhttp 5`, `retrofit` + `converter-gson`, `kotlinx-coroutines`, `kotlinx-serialization`, `room` (+ KSP), `work-runtime`, `camera-*`, `material`, `androidsvg`, `vosk-android` + `jna`, `genai-prompt`, `webkit`. Для этапа 5: `chaquopy`, `dexmaker`, `mvel2`, `hiddenapibypass`, ShadowHook и LSPlant (native).
Учтите: `minSdk` у нас 21, у exteraGram — 24. Room 2.8, WorkManager 2.11, Chaquopy с Python 3.11, ML Kit GenAI и CameraX 1.6 требуют minSdk ≥ 23–24. Это обязательное поднятие minSdk или условная сборка.

### 3.2. Нативные библиотеки `decoded/lib/arm64-v8a` (20 шт., у armeabi-v7a тот же набор)

| .so | Размер | Чья | Нужна для | Есть у нас |
|---|---:|---|---|---|
| libtmessages.49.so | 19.5 МБ | Telegram, **пересобран exteraGram**: внутри exterahook/LSPlant (`lsplant::Init`, RegisterNatives для `JniBridgeBindings`), `NativeCrashHandler_init`, ffmpeg-декодер media3 (`Java_androidx_media3_decoder_ffmpeg_ExperimentalFfmpegVideoDecoder_*`); DT_NEEDED: **libshadowhook.so, libc++_shared.so** | всё | своя (без хук-движка, `c++_static`) |
| libshadowhook.so, libshadowhook_nothing.so | 109 КБ | ByteDance ShadowHook | inline-хуки для exterahook / плагинов | нет |
| libc++_shared.so | 1.4 МБ | NDK | libtmessages, libyuv, shadowhook | нет (static) |
| libetgadblock.so | 3.0 МБ | **exteraGram, Rust** (~180 строк `.rs`, JNI `Java_com_exteragram_messenger_adblock_interop_NativeAdBlock_*`: createEngine, addFilters, createFilterSet …) | adblock | нет — исходников нет, только бинарь |
| libpython3.11.so, libchaquopy_java.so, libcrypto_python/libssl_python/libsqlite3_python, lib*_chaquopy.so | ≈11 МБ | Chaquopy / CPython 3.11 | plugins | нет |
| libvosk.so | 10 МБ | Alpha Cephei Vosk (Kaldi) | speech | нет |
| libjnidispatch.so | 166 КБ | JNA | vosk | нет |
| libimage_processing_util_jni.so, libsurface_util_jni.so | 37 КБ | AndroidX CameraX | camera | нет |
| libdatastore_shared_counter.so | 7 КБ | AndroidX DataStore | транзитивно | нет |
| liblanguage_id_l2c_jni.so | 1 МБ | Google ML Kit language-id | LanguageDetector | есть (через AAR) |
| libyuv.so | 4 КБ | заглушка или шим (только libm/libc++) — чья, не ясно, вероятно из ML Kit или CameraX | — | нет (у нас libyuv внутри tmessages) |

### 3.3. Assets, которых нет у нас

| Asset | Что | Этап |
|---|---|---|
| `extera_locales/values*/extera.xml` (715 строк × 33 локали) | Строки exteraGram (грузит LocaleController) | 1 — конвертировать в `res/values*/strings_extera.xml` |
| `monet_black/dark/light.attheme` | Monet-темы | 2 |
| `fonts/impact.ttf`, `fonts/mw_bolditalic.ttf` | Шрифты (мемы / стикер-редактор) | 2–3 |
| `extera/registration_dates.bin` | Таблица id → дата регистрации | 4 (regdate) |
| `extera/export/{css,js,images}` | Шаблоны HTML-экспорта | 4 (export) |
| `chaquopy/*` (≈25 МБ: stdlib/bootstrap/requirements .imy, cacert.pem, build.json) | Python-рантайм | 5 |
| `plugins_pysdk/sdk-arm64-v8a.zip`, `sdk-armeabi-v7a.zip`, `v.txt` = 1.4.5.7 | Python SDK плагинов | 5 |
| `localization_*.bin`, `string_resource_ids.bin` | Бинарная локализация Telegram 12.10.6 | не переносить (у нас `strings.xml`) |
| `dexopt/baseline.prof(m)` | Профиль ART | не нужно |
| `tflite_langid.tflite.jpg` | Модель langid (TFLite) | проверить, если нужен on-device langid |

---

## 4. Разбивка по этапам

Для каждого этапа: что портировать, какие файлы org/telegram патчить (в скобках — число smali-ссылок и использованные классы), ключи ExteraConfig, внешние зависимости, риски.


### Этап 1 — основа (в работе)

**Портировать из exteraGram:**
- `ExteraConfig`: 137 полей. На Java переписываем делегаты `BasePref` в явные `static`-геттеры и сеттеры над `SharedPreferences("exteraconfig")`. **Ключи должны совпадать**, иначе не будут совместимы бэкапы exteraGram.
- `config/*`: `BasePref` и наследники, `BottomNavigationBar`.
- Корневые enum: `AvatarCornerType`, `CameraType`, `DividerStyle`, `GlassOutlineStyle`, `IconPackType`, `MainMenuItem`, `ProxyDisableCondition`, `StickerTimeMode`, `TabCounterMode`, `TabIconsMode`, `TransitionAnimation`, `VideoMessagesCamera`. Нужны уже на этапе 1, потому что на них ссылаются EnumPref.
- `backup/PreferencesUtils` — `getPreferences`, backup/restore. `BackupBottomSheet` и `InvisibleEncryptor` можно отложить.
- `preferences/*`:
  - `BasePreferencesActivity`, `MainPreferencesActivity`, `GeneralPreferencesActivity`, `OtherPreferencesActivity`;
  - каркасы `Appearance`, `AppNavigation`, `Chats`, `SwipeActions` — пункты, которые ещё не реализованы, скрываем;
  - `components/*`: AltSeekbar, HeaderSettingsCell, CustomPreferenceCell, PreviewColors, PreviewBackgroundDrawable;
  - `utils/SettingsRegistry`, `utils/IconShapeHelper`;
  - `SwitchGroup`.
- Нужные utils:
  - `utils/AppUtils`, `ExtensionsKt` (переписать как static-хелперы), `RuntimeCompatibility`;
  - `utils/system/{SystemUtils,VibratorUtils}`;
  - `utils/text/{LocaleUtils,ZalgoFilter}`;
  - `utils/ui/{PopupUtils,UIUtil}`;
  - `utils/network/RemoteUtils` — на этапе 1 как заглушка или с нашим источником remote-config.
- `components/{ActionRow,CheckBoxRow}`. `ReceiveContentEditText` и `utils/ui/TextPaint` — по желанию, это сквозные TP/RC-замены (§2.1).
- Ресурсы:
  - 585 строк из `assets/extera_locales/values*/extera.xml` перенести в `res/values*/strings_extera.xml` — как минимум en и ru;
  - иконки, которые нужны экранам настроек: `R.drawable.*` из preferences/* (часть из 1 028 недостающих);
  - 67 недостающих `R.color.*`;
  - логотип или заголовок раздела (`ic_foreground` в README заменён лого staticgram).

**Патчить в org/telegram:**
- **Точка входа и инициализация:**
  - `ui/SettingsActivity`: пункт → `MainPreferencesActivity` (+ `BottomNavigationBar`, `AppUtils`; там же hidePhoneNumber, avatarCorners, glassOutlineStyle — пока игнорируем);
  - `ui/LaunchActivity`: `ExteraConfig.init()` / `loadConfig()`, логирование;
  - `messenger/ApplicationLoader`: ранняя инициализация. Вызовы `PluginsController`, `AdBlockManager`, `ApiController`, `IconManager`, `AppIconController`, `Updater` и `Debug*Monitor` на этапе 1 закомментировать.
- **Инфраструктура списка настроек:**
  - `ui/Components/UItem` (13: SettingsRegistry, CustomPreferenceCell, BasePreferencesActivity);
  - `UniversalAdapter`, `UniversalRecyclerView`, `RecyclerListView`, `HeaderCell`: sectionsSeparatedHeaders, sectionRadiusDp;
  - `ui/LanguageSelectActivity` (2: GeneralPreferencesActivity).
- **Файлы, где читаются ключи General** (если реализуем General уже на этапе 1):

  | Ключ | Файлы |
  |---|---|
  | disableNumberRounding, formatTimeWithSeconds, relativeLastSeen | LocaleController |
  | inAppVibration | ActionBarLayout, AndroidUtilities, BotWebViewVibrationEffect, BottomSheet, ContentPreviewViewer, RecyclerListView |
  | disableNotificationDelay | NotificationsController |
  | filterZalgo | через `LocaleUtils` / `ZalgoFilter` (MessageObject, DialogCell) |
  | downloadSpeedBoost | FileLoadOperation |
  | uploadSpeedBoost | FileUploadOperation |
  | hidePhoneNumber | ProfileActivity, ProfileSearchCell, SettingsActivity |
  | showIdAndDc | ProfileActivity, PhotoViewer |
  | hideArchiveFolder | MessagesController |
  | archiveOnPull, disableUnarchiveSwipe | DialogsActivity |
  | customSavePath | MediaController |
  | translationProvider | TranslateAlert2 (фактически это этап 3) |
  | useGoogleCrashlytics, useGoogleAnalytics | ApplicationLoader — в StaticGram не нужны, ключи оставить «мертвыми» |
- Полный список файлов, где на этапе 1 встречаются `preferences/`, `config/`, `backup/` и `RemoteUtils`: `ui/ProfileActivity` (18: SettingsRegistry, BottomNavigationBar, MainPreferencesActivity, RemoteUtils, AppUtils); `ui/ChatActivity` (14: PreferencesUtils, BackupBottomSheet, BottomNavigationBar); `ui/Components/UItem` (13: SettingsRegistry, CustomPreferenceCell, BasePreferencesActivity); `ui/DialogsActivity` (10: BottomNavigationBar, AppUtils); `ui/Components/SharedMediaLayout` (8: PreferencesUtils, BackupBottomSheet, AppUtils); `messenger/MessagesController` (6: RemoteUtils); `ui/Components/Premium/AdaptiveIconImageView` (6: IconShapeHelper); `ui/SettingsActivity` (5: BottomNavigationBar, MainPreferencesActivity, AppUtils); `ui/PhotoViewer` (3: AppUtils); `ui/ContactsActivity` (3: BottomNavigationBar); `ui/TopicsFragment` (3: BottomNavigationBar); `ui/Components/ViewPagerFixed` (3: AppUtils); `ui/ActionBar/ActionBarLayout` (2: AppUtils); `ui/MainTabsActivity` (2: BottomNavigationBar); `ui/ArticleViewer` (2: AppUtils); `ui/CallLogActivity` (2: BottomNavigationBar); `messenger/LanguageDetector` (2: RuntimeCompatibility); `ui/LanguageSelectActivity` (2: GeneralPreferencesActivity); `ui/LaunchActivity` (1: RemoteUtils); `messenger/ApplicationLoader` (1: RemoteUtils); `ui/Components/ShareAlert` (1: PreferencesUtils); `messenger/NotificationsController` (1: AppUtils); `messenger/MusicPlayerService` (1: AppUtils); `ui/Adapters/DialogsSearchAdapter` (1: RemoteUtils); `ui/ViewPagerActivity` (1: BottomNavigationBar); `ui/RightSlidingDialogContainer` (1: AppUtils); `messenger/video/VideoAds` (1: RemoteUtils).

**Ключи ExteraConfig этапа 1:** вся схема (137 ключей) объявляется сразу, но читаются только ключи групп «Общие» и «Прочее/сервис» из §1.3.

**Внешние зависимости:** `kotlin-stdlib` — если хоть один класс переносится как есть или пишется на Kotlin; gson уже есть. Больше ничего не нужно.

**Риски:**
- Делегаты Kotlin (`provideDelegate`, `KProperty`) в Java не переносятся один в один, нужна ручная переписка 137 полей.
- `EnumPref` хранит значение по `ordinal` или `name` — проверить в `config/EnumPref` и сохранить формат.
- `ExteraConfig.init()` тянет за собой 5 подсистем — нужны заглушки.
- `BasePreferencesActivity` опирается на хуки UItem/UniversalAdapter (CustomPreferenceCell). Без патча UItem кастомные ячейки не отрисуются.
- Import'ы `plugins.*` в config/backup — это R8-outline, переносить их не надо.

### Этап 2 — внешний вид

**Портировать:**
- стекло: `GlassOutlineStyle`, `utils/chats/GlassMenuHelper`; движок `org/telegram/utils/glass/*` (апстрим 12.10.6, у нас его нет);
- аватары: `AvatarCornerType`, `ExteraConfig.getAvatarCorners/Squareness/OnlineDot*`, `preferences/appearance/components/AvatarCornersPreviewCell`;
- разделители: `DividerStyle`;
- табы: `TabIconsMode`, `TabCounterMode`, `utils/ui/FolderIcons`, `utils/chats/FolderCounters`, `FilterTabsPreviewCell`;
- переходы: `TransitionAnimation`, `utils/ui/{WindowTransitionAnimationHelper,PredictiveBackAnimationHelper}`;
- `pillstack/*` целиком;
- `badges/*` — требует `api` (BadgeDTO, ApiBadgeSource), без нашего backend'а лучше отложить;
- `drawer/*` + `MainMenuItem` + `utils/chats/MainMenuHelper` + `preferences/appearance/AppNavigationPreferencesActivity`;
- `icons/*`: IconManager, ExteraResources, BaseIconPacks, IconPackProvider (ContentProvider), редактор;
- `appicons/*`;
- `utils/ui/*`: FontUtils, MonetUtils (+ `ui/ActionBar/MonetAccentHelper`), SwitchUiHelper, FabUiHelper, MainTabsUiHelper, AccountsUiHelper, ChatHeaderUiHelper, MaterialSliderUiHelper, ColorRectSpan;
- `config/BottomNavigationBar`;
- `preferences/appearance/*`.

**Патчить (org/telegram):** `ui/ProfileActivity` (115: IconPackType, BadgesController, IconManager, ChatHeaderUiHelper, MainTabsUiHelper); `ui/ActionBar/ActionBarLayout` (76: PredictiveBackAnimationHelper, WindowTransitionAnimationHelper); `ui/ChatActivity` (67: BadgesController, IconManager, GlassMenuHelper, ChatHeaderUiHelper, MainTabsUiHelper); `ui/Components/FragmentSearchField` (64: PillRegistry, PillStackConfig, PillStackView, BasePill); `ui/DialogsActivity` (61: AvatarCornerType, TabCounterMode, TabIconsMode, DrawerContainer, FolderCounters, MainMenuHelper, AccountsUiHelper, MainTabsUiHelper); `ui/LaunchActivity` (49: DrawerContainer, ExteraResources, IconManager, IconPickerController, PillStackConfig, PillType, MonetUtils); `ui/MainTabsActivity` (43: MainMenuItem, MainMenuHelper, AccountsUiHelper, MainTabsUiHelper); `ui/Components/FilterTabsView` (32: TabIconsMode, FolderIcons); `ui/ActionBar/Theme` (25: DividerStyle, MonetUtils); `ui/Components/Switch` (22: SwitchUiHelper); `ui/Components/ProfileActionsView` (21: DividerStyle, IconPackType, IconManager); `ui/Components/ChatActivityEnterView` (16: IconPackType, IconManager); `ui/Components/glass/GlassTabView` (16: IconPackType, IconManager, MainTabsUiHelper); `ui/ThemeActivity` (15: AppIcon, AppIconController, AppIconPreviewDrawable, AppIconPreviewLoader, AppIconsActivity); `ui/Cells/DialogCell` (14: AvatarCornerType, BadgesController); `ui/Components/FragmentFloatingButton` (14: FabUiHelper); `ui/ActionBar/DrawerLayoutContainer` (13: DrawerContainer); `ui/Cells/ChatMessageCell` (12: DividerStyle, BadgesController); `ui/Cells/ProfileSearchCell` (12: AvatarCornerType, BadgesController); `ui/Cells/ChatActionCell` (11: AvatarCornerType); `ui/Stories/recorder/Weather` (11: PillStackConfig, PillType); `ui/ActionBar/MonetAccentHelper` (11: MonetUtils); `ui/Components/TextStyleSpan` (10: FontUtils); `ui/ActionBar/ActionBarMenuItem` (8: AvatarCornerType, GlassMenuHelper); `ui/SettingsActivity` (8: GlassOutlineStyle, MainTabsUiHelper); `ui/GroupCallActivity` (8: IconPackType, IconManager); `ui/CacheControlActivity` (8: PillStackConfig, PillType); `ui/Components/EmojiPacksAlert` (7: BadgesController); `ui/Components/EmojiView` (7: BadgesController); `messenger/AndroidUtilities` (7: DividerStyle, FontUtils); `ui/Components/SuggestEmojiView` (7: BadgesController); `messenger/MessagesStorage` (6: FolderCounters); `ui/ContactsActivity` (6: MainTabsUiHelper); `ui/Stories/DialogStoriesCell` (6: TabIconsMode); `ui/CallLogActivity` (6: MainTabsUiHelper); `ui/Components/ChatAvatarContainer` (6: ChatHeaderUiHelper); `ui/Cells/UserCell` (6: AvatarCornerType, BadgesController); `messenger/RichMessageLayout` (6: FontUtils); `ui/Components/Premium/PremiumAppIconsPreviewView` (6: AppIcon, AppIconController); `ui/ActionBar/BaseFragment` (6: IconManager, IconObserver); `ui/Components/EmojiTabsStrip` (6: IconPackType, IconManager); `ui/TopicsFragment` (5: FabUiHelper, MainTabsUiHelper); `ui/Components/blur3/drawable/color/impl/BlurredBackgroundProviderImpl` (5: ChatHeaderUiHelper, FabUiHelper, MainTabsUiHelper); `ui/Adapters/FiltersView` (5: AvatarCornerType); `ui/Components/blur3/drawable/BlurredBackgroundDrawable` (5: GlassOutlineStyle); `ui/Components/blur3/StrokeDrawable` (5: GlassOutlineStyle); `messenger/ApplicationLoader` (4: AppIconController, IconManager); `ui/Components/SharedMediaLayout` (4: IconManager); `ui/ChannelAdminLogActivity` (4: ChatHeaderUiHelper); `ui/Cells/UserInfoCell` (4: BadgesController); `ui/Components/SlideChooseView` (4: MaterialSliderUiHelper); `ui/Components/SeekBarView` (4: MaterialSliderUiHelper); `ui/UserInfoActivity` (3: BadgesController); `ui/SelectAnimatedEmojiDialog` (3: BadgesController); `ui/ContentPreviewViewer` (3: BadgesController); `ui/Components/RecyclerListView` (3: DividerStyle); `ui/ActionBar/ActionBar` (3: ChatHeaderUiHelper); `ui/FilterCreateActivity` (3: FolderIcons); `ui/community/CommunitySheet` (3: AvatarCornerType); `ui/Components/StatusBadgeComponent` (3: BadgesController); `ui/Components/ReactionsContainerLayout` (3: GlassMenuHelper); `ui/community/CommunityEditActivity` (3: AvatarCornerType); `ui/Components/blur3/drawable/BlurredBackgroundDrawableRenderNode` (3: GlassOutlineStyle); `ui/Cells/DividerCell` (3: DividerStyle); `ui/community/cells/CommunityLinkView2` (3: AvatarCornerType); `ui/community/cells/CommunityLinkView` (3: AvatarCornerType); `ui/community/CommunityCreateActivity` (3: AvatarCornerType); `ui/LoginActivity` (2: FabUiHelper); `messenger/MessagesController` (2: MonetUtils); `messenger/NotificationsController` (2: IconManager); `ui/Components/Reactions/CustomEmojiReactionsWindow` (2: GlassMenuHelper); `ui/Components/Reactions/ChatSelectionReactionMenuOverlay` (2: GlassMenuHelper); `messenger/FilesMigrationService` (2: IconManager); `ui/FiltersSetupActivity` (1: FolderIcons); `messenger/Emoji` (1: FontUtils); `messenger/MusicPlayerService` (1: IconManager); `ui/Cells/TextCheckCell2` (1: SwitchUiHelper).
Дополнительно по ключам:

| Ключ | Файлы |
|---|---|
| dividerStyle | AndroidUtilities, ChatMessageCell, DividerCell, ProfileActionsView, RecyclerListView, Theme |
| avatarCorners | ≈84 класса (AvatarDrawable, AvatarSpan, AvatarsDrawable, DialogCell, UserCell, ProfileActivity, StoriesUtilities, виджеты …; см. §2.3) |
| centerTitle | ActionBar, ActionBarMenuItem, DialogsActivity, PhotoViewer, PhotoViewerWebView |
| hideActionBarStatus | ActionBar, DialogStoriesCell |
| hideStories | DialogsActivity, MessagesController, ProfileActivity, StoriesController, StoriesUtilities |
| hideFloatingButton | DialogsActivity, DialogsAdapter, DialogsEmptyCell |
| squareFab | FragmentFloatingButton, LoginActivity, MediaDataController, PhotoAlbumPickerActivity, PhotoPickerActivity |
| newLoadingStyle | ChatActivityBlurredRoundButton, CircularProgressDrawable, LineProgressView, RadialProgress2, RadialProgressView |
| newSliderStyle | SeekBarView, SlideChooseView |
| newChatHeaderStyle | ActionBar |
| newSwitchStyle | Switch (через SwitchUiHelper) |
| forceBlur | SizeNotifierFrameLayout |
| glassOutlineStyle | blur3 BlurredBackgroundDrawable(RenderNode), StrokeDrawable, SettingsActivity |
| forceSnow, eventType | Theme |
| useSystemFonts | AndroidUtilities (+ TextPaint) |
| tabletMode | AndroidUtilities |
| predictiveBackIntensity | ActionBarLayout, BottomSheet, LaunchActivity |
| transitionAnimation (spring/aosp) | ActionBarLayout |
| navigationDrawer | ActionBar, DialogsActivity, LaunchActivity |
| tabIcons | DialogStoriesCell, DialogsActivity, FilterTabsView |
| tabCounterMode | DialogsActivity |
| hideAllChats | DialogsActivity, FilterTabsView, MainTabsActivity |
| senderMiniAvatars | DialogCell |
| gooeyAvatarAnimation | ProfileActivity |
| sectionRadius, sectionsSeparatedHeaders | GradientHeaderActivity, ProfileActionsView, RecyclerListView, SectionsScrollView, UniversalRecyclerView, ChangeUsernameActivity, FiltersSetupActivity, HeaderCell, UniversalAdapter |
| titleText | UserInfoActivity |

Ещё два ключа: iconPack читается через `IconManager` / `ExteraResources`, `useSystemIconShape` — через `IconShapeHelper` (AdaptiveIconImageView).

**Ключи:** всё, что перечислено в группах «Внешний вид» и «Навигация» §1.3.

**Внешние зависимости:**
- `material` 1.14 (слайдеры, MaterialShapes) — либо переписать без него;
- `androidsvg` и `simplifiles` (для пользовательских SVG/zip-иконпаков; встроенные Solar и Remix обходятся без них);
- `kotlinx-coroutines` (IconManager);
- `okhttp` (pillstack: погода, Coinbase);
- `dynamicanimation` 1.1.

**Ресурсы:**
- ≈1 000 drawable для иконпаков (Solar, Remix) и `filter_*` (FolderIcons);
- Monet-темы `monet_*.attheme`;
- activity-alias'ы для appicons в манифесте;
- `IconPackProvider` в манифесте.

**Риски:**
- `ExteraResources` подменяет `Resources` во всём приложении (LaunchActivity, BaseFragment), это может конфликтовать с нашими ресурсами и ребрендингом.
- Glass завязан на апстрим-движок 12.10.6 (`utils/glass`), которого у нас нет, — сначала нужен бэкпорт.
- avatarCorners затрагивает примерно 84 класса — объёмный механический патч.
- `badges` и drawer-бейджи опираются на api.exteragram.app — нужен наш backend или отключение.
- appicons требует правки манифеста `TMessagesProj_App*` и совместимости с нашим `LauncherIconController`.

### Этап 3 — чаты

**Портировать:**
- `adblock/*` (+ `libetgadblock.so`, подписки на фильтры);
- `translator/*`;
- время стикеров: `StickerTimeMode`, `utils/chats/StickerTime`, `StickerShapeCell`;
- double tap: `utils/chats/DoubleTapUtils`, `preferences/chats/components/DoubleTapCell`;
- swipe actions: `utils/chats/{SwipeAction,SwipeActionsHelper}`, `preferences/chats/SwipeActionsPreferencesActivity`, `SwipeActionsCell`;
- `utils/chats/{ChatUtils,WidePosts,ForwardContext}`, `utils/ui/ForwardsCountUiHelper`;
- `components/{Blended*,MessageDetailsPopupWrapper,TranslateBeforeSendWrapper,PreformattedScrollView,ChatActivityEnterViewStaticIconView,SearchPhotoPopupWrapper}`;
- `utils/{MarkdownUtils,MediaUtils,IntentsController}`;
- `notifications/*`;
- `preferences/chats/ChatsPreferencesActivity` (часть без камеры, AI и Vosk).

**Патчить (org/telegram):** `ui/ChatActivity` (239: MessageDetailsPopupWrapper, TranslationProviders, TranslatorUtils, MarkdownUtils, ChatUtils, DoubleTapUtils, ForwardContext, SwipeAction, SwipeActionsHelper, WidePosts); `ui/Cells/ChatMessageCell` (119: BlendedReplyFileText, ChatUtils, StickerTime, WidePosts, ForwardsCountUiHelper); `ui/Components/ChatActivityEnterView` (111: ChatActivityEnterViewStaticIconView, TranslateBeforeSendWrapper, TranslatorUtils); `messenger/TranslateController` (38: ChatTranslationBridge, ChatTranslator, TranslationProviders, TranslatorUtils, BaseTranslator, TelegramTranslator); `ui/ArticleViewer` (26: WebAdBlocker, PreformattedScrollView, MarkdownUtils); `ui/LaunchActivity` (25: IntentsController, WidePosts); `ui/Components/AudioPlayerAlert` (18: ChatUtils); `ui/PhotoViewer` (17: SearchPhotoPopupWrapper, TranslatorUtils, MediaUtils); `ui/web/BotWebViewContainer` (16: WebAdBlocker); `ui/ProfileActivity` (15: TranslatorUtils, ChatUtils); `ui/Components/TranslateAlert2` (14: TranslationProviders, TranslatorUtils); `ui/ContentPreviewViewer` (12: ChatUtils); `ui/Components/AIEditorAlert` (9: TranslatorUtils); `ui/Components/ShareAlert` (7: TranslateBeforeSendWrapper, TranslatorUtils, ForwardContext); `ui/RestrictedLanguagesSelectActivity` (7: TranslatorUtils); `ui/DialogsActivity` (6: TranslateBeforeSendWrapper, TranslatorUtils); `ui/Components/ChatAttachAlert` (6: TranslateBeforeSendWrapper, TranslatorUtils); `ui/Cells/ChatActionCell` (6: ChatUtils); `ui/Components/EmojiPacksAlert` (5: ChatUtils); `ui/NotificationsSettingsActivity` (5: AccountNotifications, AccountNotificationsActivity); `messenger/MediaDataController` (4: ChatUtils); `ui/ChannelAdminLogActivity` (4: WidePosts); `ui/Components/StickersAlert` (4: ChatUtils); `ui/web/WebActionBar` (4: WebAdBlocker, AdBlockMenuItem); `ui/Components/TranslateButton` (4: TranslationProviders); `ui/Components/MarkdownParser` (4: MarkdownUtils); `ui/MainTabsActivity` (3: ChatUtils); `ui/Components/TranscribeButton` (3: ChatUtils); `messenger/MessageObject` (2: WidePosts); `messenger/NotificationsController` (2: AccountNotifications); `messenger/SendMessagesHelper` (1: MediaUtils); `ui/Cells/TextSelectionHelper` (1: TranslatorUtils); `messenger/HashtagSearchController` (1: ChatUtils); `ui/Components/MessagePreviewView` (1: StickerTime).
Дополнительно по ключам:

| Ключ | Файлы |
|---|---|
| doubleTapAction(+OutOwner), bottomButton, quickAdminShortcuts, quickTransitionFor{Channels,Topics}, hideKeyboardOnScroll, show*Button (copyPhoto, save, repeat, clear, history, report, generate, details) | ChatActivity |
| groupMessageMenu | ActionBarPopupWindow, ChatActivity |
| addCommaAfterMention | ChatActivity, MentionsContainerView |
| hideReactionsIn* | ChatActivity, DialogCell, ReactionsEffectOverlay, ReactionsLayoutInBubble |
| replyColors, replyBackground, replyEmoji | ReplyMessageLine (+ ChatActivity) |
| stickerSize | ChatMessageCell |
| stickerShape | ChatMessageCell, StickerCell, StickerEmojiCell |
| stickerTimeMode | ChatMessageCell, MessagePreviewView |
| removeMessageTail | ChatMessageCell, MessageDrawable |
| replaceEditedWithIcon, showOnlineStatus, hideShareButton | ChatMessageCell |
| showResultsBeforeVoting | ChatMessageCell, MessagesController |
| showForwardsCount | ForwardsCountUiHelper в ChatMessageCell |
| unlimitedRecentStickers | MediaDataController |
| disableGreetingSticker | ChatGreetingsView |
| disableMarkdown | EditTextBoldCursor, MediaDataController |
| hideSendAsPeer | ChatActivityEnterView |
| widePostsInChannels, widePostsInFeed | ChatActivity, LaunchActivity (+ MessageObject, ChannelAdminLogActivity) |
| customThemes | ChatActivity, ChatThemeController |
| telegramAiEditor, telegramAiSummaries, telegramAiInstantViewSummaries | этап 4 (ai) |
| translationProvider, targetLang | TranslateAlert2, TranslateController |
| enableAdBlock | ArticleViewer, BotWebViewContainer, WebActionBar, video/VideoAds |
| swipeActions* | через SwipeActionsHelper (ChatActivity) |

**Ключи:** группы «Чаты» и «Меню сообщения» из §1.3, а также translationProvider, targetLang, enableAdBlock.

**Внешние зависимости:**
- `okhttp` 5 (translator, подписки adblock);
- `libetgadblock.so` — готовый бинарь для arm64 и armv7, исходников нет; x86 в APK нет;
- `markwon` уже есть.

**Риски:**
- ChatActivity и ChatMessageCell — самые большие патчи (≈660 smali-ссылок каждый) поверх наших собственных правок StaticGram. Делать маленькими коммитами по фичам.
- adblock — закрытый Rust-бинарь от сторонней команды (лицензия и аудит). Альтернатива — свой фильтр на `WebViewClient.shouldInterceptRequest`.
- Переводчик ходит в Google, Yandex и Microsoft напрямую — нужны приватность и прокси-настройки.
- В `TranslateController` exteraGram заменяет и Telegram-саммари (`TelegramAiReplacement`), а это уже связь с этапом 4.

### Этап 4 — медиа и сервисы

**Портировать:**
- `camera/*`, `CameraType`, `VideoMessagesCamera`. Сначала нужен бэкпорт апстрим-камеры 12.10.6: `InstantCameraViewBase`, `InstantCameraView2`, `utils/camera/roundvideo/*`, `utils/settings/*`, `RoundVideoSettingsActivity`.
- `speech/*` (Vosk).
- `ai/*`.
- `nowplaying/*`.
- `feed/*`, включая рекламу `feed/ads` — для нас выключить.
- `proxy/*` + `ProxyDisableCondition`, плюс апстрим `utils/proxy/*`.
- `backup/BackupBottomSheet` и `InvisibleEncryptor`.
- `export/*` (92 класса).
- `math/*` (inline-калькулятор; RC-база EditTextBoldCursor).
- `api/*` (Retrofit, Room, WorkManager).
- `regdate`, `updater`, `debug`.
- `components/{QRCodeSheet,QrCodeLoginView,ReverseImageSearchSheet,ChooseSubtitlesLayout,SupporterBottomSheet,BoostyBottomSheet,IconSelectorAlert}`.
- `utils/{VideoSubtitlesHelper,JpegFingerprint,PasskeysUtil}`.

**Патчить (org/telegram):** `ui/Components/InstantCameraView` (449: CameraType, VideoMessagesCamera, CameraDebugUtils, CameraXSession, CameraZoomSliderView, InstantCameraZoomSlider, RoundVideoEncoder, DebugConfig, DebugOverlayView); `ui/ChatActivity` (248: AiController, Client, AiResponseAlert, GenerateFromMessageBottomSheet, BadgeDTO, InstantCameraZoomSlider, FeedChannelActions, FeedChatIntegration, FeedController, FeedMessageUtils, FeedStore, IpAddressInfoController, VoiceRecognitionController); `ui/ProxyListActivity` (121: ProxyDisableCondition, ProxyController); `ui/ProfileActivity` (115: ApiController, ExteraDatabase, BadgeDTO, NowPlayingDTO, RegDateDTO, NowPlayingController, NowPlayingCard, NowPlayingCardData, RegDateController); `ui/Components/ChatActivityEnterView` (89: VideoMessagesCamera, AiConfig, AiController, Client, OnDeviceAvailability, AiResponseAlert, AiPreferencesActivity, EditServiceActivity, InlineMathController); `ui/PhotoViewer` (64: ChooseSubtitlesLayout, ReverseImageSearchSheet, JpegFingerprint, VideoSubtitlesHelper); `ui/UserInfoActivity` (27: NowPlayingInfoDTO, NowPlayingServiceType, NowPlayingController, SetupNowPlayingActivity); `messenger/ApplicationLoader` (17: ProxyDisableCondition, FreezeMonitor, HeapMonitor, LoadMonitor, UpdateAppAlertDialog, UpdateLayout, UpdaterUtils); `ui/MainTabsActivity` (16: FeedController, FeedActivity, FeedChannelsActivity); `ui/Cells/ChatMessageCell` (15: BadgeDTO, FeedMessageUtils, VoiceRecognitionController); `ui/Components/EmojiPacksAlert` (15: BadgeDTO); `ui/Components/EmojiView` (15: BadgeDTO); `messenger/AndroidUtilities` (15: ProxyController); `ui/ProxySettingsActivity` (14: ProxyController); `ui/Components/SuggestEmojiView` (13: BadgeDTO); `ui/Components/TranscribeButton` (13: VoiceRecognitionController, MediaLoader); `ui/Cells/DialogCell` (11: BadgeDTO); `ui/LoginActivity` (9: QrCodeLoginView); `ui/Cells/ProfileSearchCell` (9: BadgeDTO); `ui/Components/EditTextBoldCursor` (7: InlineMathController); `ui/Cells/UserInfoCell` (7: BadgeDTO, RegDateController); `ui/Components/TranslateAlert2` (4: AiController, TelegramAiReplacement); `messenger/PasskeysController` (4: PasskeysUtil); `messenger/TranslateController` (3: AiController, TelegramAiReplacement); `messenger/MessagesStorage` (3: FeedController); `ui/SelectAnimatedEmojiDialog` (3: BadgeDTO); `ui/Components/AIEditorAlert` (3: AiController, TelegramAiReplacement); `ui/Components/ChatAvatarContainer` (3: BadgeDTO); `ui/FilterCreateActivity` (3: IconSelectorAlert); `ui/PasskeysActivity` (3: PasskeysUtil); `ui/Components/InstantCameraViewBase` (3: CameraType); `messenger/SharedConfig` (3: ProxyController); `messenger/ProxyRotationController` (3: ProxyController); `ui/SettingsActivity` (2: DebugActivity); `ui/Cells/UserCell` (2: BadgeDTO); `ui/Components/StatusBadgeComponent` (2: BadgeDTO); `ui/LaunchActivity` (1: UpdaterUtils); `tgnet/ConnectionsManager` (1: FeedRequestNormalizer); `ui/Components/blur3/BlurredBackgroundWithFadeDrawable` (1: DebugConfig); `ui/Components/BlockingUpdateView` (1: UpdaterUtils); `messenger/FileRefController` (1: UpdaterUtils).
Дополнительно по ключам:

| Ключ | Файлы |
|---|---|
| cameraType | InstantCameraView, InstantCameraViewBase |
| videoMessagesCamera | ChatActivityEnterView, InstantCameraView |
| rememberLastUsedCamera, staticZoom, flashWarmth, flashIntensity | InstantCameraView |
| extendedFramesPerSecond | Camera2Session, InstantCameraView |
| cameraStabilization | Camera2Session |
| startWithWideAngleCamera, zoomSlider, cameraMirrorMode | CameraXSession / InstantCameraZoomSlider |
| alwaysSendInHD | MediaController, PhotoAttachPhotoCell |
| hideCameraTile | ChatAttachAlert, ChatAttachAlertPhotoLayout |
| doubleTapSeekDuration, swipeToPip, pauseOnMinimizeVideo | PhotoViewer |
| pauseOnMinimizeVoice, pauseOnMinimizeRound | MediaController |
| preferOriginalQuality | VideoPlayer |
| unmuteWithVolumeButtons | LaunchActivity |
| recognitionLanguage | TranscribeButton |
| postprocessingWithAi | speech/VoiceRecognitionController (внутри exteragram, предположительно) |
| telegramAiEditor | CaptionPhotoViewer, ChatActivityEnterView, ChatAttachAlert, LinkManager, RichEditor, RichEditorToolbar |
| telegramAiSummaries | TranslateController |
| telegramAiInstantViewSummaries | ArticleViewer |
| showFeedTab, showFeedUnreadCounter | MainTabsActivity |
| doNotUseProxy (proxyDisabledOn) | ApplicationLoader, ProxyListActivity |
| inlineMathResult | EditTextBoldCursor (InlineMathController) |
| updateScheduleTimestamp | LaunchActivity |
| yandexSearchEngine | web/SearchEngine |

**Ключи:** группа «Медиа и камера», а также telegramAi*, showFeedTab, showFeedUnreadCounter, doNotUseProxy, inlineMathResult, update*.

**Внешние зависимости:**
- CameraX 1.6.2 (+ `.so`), Vosk + JNA (+ 2 `.so`, модели скачиваются в рантайме);
- ML Kit GenAI Prompt;
- OkHttp 5, Retrofit, Room (+ KSP, Kotlin), WorkManager, kotlinx-coroutines и serialization;
- Material; protobuf-lite (roundvideo).

**Внешние сервисы:**
- api.exteragram.app;
- Last.fm, stats.fm, iTunes;
- ipwho.is, Coinbase;
- Gemini, Perplexity и OpenAI-совместимые API;
- DoH dns.comss.one.

**Риски:**
- **minSdk 21 → 24.**
- Kotlin-only код с корутинами (api, nowplaying) — большие JADX WARN.
- `TLRPC`, `TL_iv`, `TL_account` регистрируют `export/api/ExportRequests` (takeout). Эти TL-конструкторы должны поддерживаться нашим gramsrv, иначе экспорт не заработает.
- `feed/FeedRequestNormalizer` встроен в `ConnectionsManager`.
- Камера кружков у exteraGram стоит на апстрим-переписке 12.10.6, которой у нас нет.
- api, badges, now-playing и regdate привязаны к backend'у exteraGram — для StaticGram нужен свой сервер или полное отключение.
- updater тянет APK из каналов exteraGram, его заменить на наш механизм релизов (admin API).

### Этап 5 — plugins

**Портировать:**
- `plugins/*` (61 класс, 52 на Kotlin): PluginsController, PythonPluginsEngine, hooks/*, xposed/*, utils/ClassProxy, NativeCrashHandler, PluginsWatchdog, SdkSignature, pip/*, ui/*, models/*;
- Xposed-шим `de/robv/android/xposed/*` (6 классов);
- `dev/exterahook/runtime/*` (HookBridgeProvider, JniBridgeBindings, ExteraHookInitProvider).

**Патчить (org/telegram):** `ui/ChatActivity` (66: PluginsController, MenuItemRecord, PluginsActivity, PluginsMenuWrapper, MenuContextBuilder); `ui/ProfileActivity` (40: PluginsController, PluginsActivity, PluginsMenuWrapper, MenuContextBuilder); `ui/LaunchActivity` (12: PluginsController); `tgnet/ConnectionsManager` (11: PluginsController, PluginsHooks); `messenger/ApplicationLoader` (9: PluginsController, NativeCrashHandler); `messenger/MessagesController` (8: PluginsController, PluginsHooks); `messenger/SendMessagesHelper` (6: PluginsController, PluginsHooks); `ui/Components/SharedMediaLayout` (4: PluginsController); `messenger/MediaDataController` (3: PluginsController); `ui/Components/UniversalAdapter` (2: SettingItem, TextSetting); `ui/Components/MediaActionDrawable` (2: PluginsController); `ui/Cells/ChatMessageCell` (1: PluginsController); `ui/Components/UItem` (1: SettingItem).

Кроме того:
- `ApplicationLoader` — ранний bootstrap `dev.exterahook` и ключ pluginsEngine;
- `UniversalAdapter` / `UItem` — модели настроек плагинов;
- `ExteraConfig.init()` — `PluginsController.init(safeMode)` и события APP_START/RESUME;
- `MainMenuItem.PLUGINS`, пункт в MainPreferencesActivity.

**Ключи:** pluginsDevMode, pluginsSafeMode, pluginsCompactView, pluginsPySdkAutoUpdate, pluginsPySdkBetaVersions, pluginsDisableArtOpts, pluginsUnknownSources, pinnedPlugins, sdkUpdateScheduleTimestamp.

**Внешние зависимости:**
- **Chaquopy:** Gradle-плагин `com.chaquo.python`, Python 3.11, около 25 МБ assets и 11 МБ `.so` на ABI. Chaquopy — open-source (MIT) с 2022 года, лицензию конкретной версии проверить.
- dexmaker (`com.android.dx`), MVEL2, JNA, hiddenapibypass, org.simplifiles.
- **Native:** LSPlant + ShadowHook нужно встроить в наш `libtmessages.49.so` или собрать отдельной `.so`. У exteraGram они живут внутри libtmessages, natives `JniBridgeBindings` регистрируются через RegisterNatives, есть `NativeCrashHandler_init`. Переход на `libc++_shared` или отдельная .so с собственной STL.
- Python SDK плагинов (`plugins_pysdk/sdk-*.zip`, 1.4.5.7), который совместим с документированным SDK 1.4.4.3 (есть скилл `exteragram-plugin-sdk`).

**Риски:**
- Самый тяжёлый этап: нативные ART-хуки (LSPlant) ломаются от версии Android и на x86 — README фиксирует `shadowhook error 12` в эмуляторе.
- Размер APK вырастет примерно на 40 МБ.
- Выполнение стороннего Python-кода — вопрос безопасности для нашей экосистемы: плагины получают полный доступ к сессии и запросам через PluginsHooks в ConnectionsManager.
- Сеть exteraGram для каталога и обновлений SDK.
- Исходники `dev.exterahook` есть только в Java-части, нативной части нет, её придётся написать заново поверх LSPlant.

---

## 5. Сквозные риски и рекомендации

1. **Kotlin.** Решить на этапе 1. Рекомендация: включить `kotlin-android` в TMessagesProj — плагин уже в classpath — и восстанавливать Kotlin-классы из JADX близко к оригиналу. Ручная переписка корутин и делегатов на Java дороже и даёт больше ошибок.
2. **Версия базы.** Мы на 12.10.5, exteraGram — на 12.10.6. Часть хуков ссылается на апстрим-классы 12.10.6: камера кружков, web-proxy, glass, utils/settings, бинарная локализация. Либо сначала подтянуть апстрим 12.10.6, либо портировать хуки без этих фич.
3. **Плеер media3 против exoplayer2.** На media3 не мигрировать. Хуки плеера переносить логикой, а не кодом.
4. **R8-артефакты.** Импорты вида `X$`, `$$ExternalSynthetic*`, `RecordTag`, `c.f$` и «зависимости» `plugins`, `backup`, `pillstack` в несвязанных классах ложные. Реальную связь проверять по smali.
5. **Сервисы и брендинг exteraGram:**
   - api.exteragram.app, `exteraAuthBot` (8083294286), remote-config `exteraremoteconfig`, update-каналы, `exteraFeedAds`;
   - Boosty и Supporter-шиты;
   - `customSavePath` = "exteraStatic".

   Всё это заменить на StaticGram или выключить. Остальной брендинг держать вне `com.exteragram`, пакет `com.exteragram.messenger` можно оставить ради совместимости с плагинами.
6. **Ресурсы.** Строки берутся из `extera.xml` (assets), а не из `res`. Drawable для иконпаков — это большой объём (≈1 000 файлов); переносить по мере надобности, по этапам.
7. **Совместимость с нашими правками:** SgServerResolver, StaticGramLaunchSplash, admin-номера, OAuth и т.д. Особенно в ChatActivity, ProfileActivity, LaunchActivity, ApplicationLoader и ConnectionsManager — это одновременно топ хуков exteraGram и места наших изменений.

---

## Приложение: полезные команды

```bash
# все хуки конкретного класса (smali, точнее JADX)
cd /root/exterastatic-src/decoded && grep -n 'Lcom/exteragram' smali*/org/telegram/ui/ChatActivity*.smali | less
# где читается ключ ExteraConfig
grep -rl 'ExteraConfig;->getStickerSize' smali*/org/telegram
# исходник класса exteraGram (свежая JADX)
less /root/.cache/jadx-extera/out/sources/com/exteragram/messenger/ExteraConfig.java
# строки exteraGram
less /root/exterastatic-src/decoded/assets/extera_locales/values/extera.xml
```

---

## 6. Этап 1 — итог (сделано)

**Важно про исходники.** `/root/.cache/jadx-extera/out` (JADX по smali) **не использовать**: JADX при входе из smali путает тела вложенных анонимных классов (например, `widePosts` → `getShowReportButton`). Надёжная декомпиляция: smali → dex (`apktool b`) → JADX `--no-replace-consts`, результат в `/root/.cache/jadx-extera/full2/sources` (com/exteragram — 519 классов, org/telegram — 2 066 файлов, есть ChatActivity/LaunchActivity/ProfileActivity). Порядок и скрипты — `tools/extera-port/README.md`.

**Перенесено (`TMessagesProj/src/main/java/com/exteragram/messenger`):**
- `ExteraConfig` — переписан на Java: 137 настроек с теми же ключами, дефолтами и типами (хранилище `exteraconfig`, enum по ordinal), те же геттеры/сеттеры `getX/setX`, миграции, меню, иконпаки, avatar corners, логирование. `init()` вызывает `ExteraServices.start()` — сервисы подключат следующие этапы.
- `config/*` (BasePref и наследники, BottomNavigationBar), корневые enum (12), `debug/DebugConfig`.
- `preferences/*` целиком: Main, General, Appearance, AppNavigation, Chats, SwipeActions, Other, базовый `BasePreferencesActivity` (переписан), `SwitchGroup` (переписан с Kotlin-DSL), компоненты и превью-ячейки, `SettingsRegistry` (переписан: поиск и ссылки `https://t.me/exteraSettings?s=…`).
- utils: PopupUtils, ExtensionsKt, VibratorUtils, LocaleUtils, ZalgoFilter, DoubleTapUtils, SwipeAction, SwipeActionsHelper, FolderCounters, FabUiHelper, MaterialSliderUiHelper, UIUtil, ColorRectSpan, ChatHeaderUiHelper (часть), RemoteUtils (только кэш), IntentsController (часть), ExteraCompat.
- Заглушки будущих этапов с теми же именами классов (экран-заглушка `PortPendingActivity` с текстом «после этапа N»): ai, appicons, badges, camera, export, feed, icons, pillstack, plugins, speech, translator, api/db, components (Boosty/Supporter).
- Ресурсы: `res/values/strings_extera.xml` (231 строка, en), `res/values-ru/strings_extera.xml` (226, ru из `extera_locales/values-ru`), 11 drawable. Зависимость `com.google.android.material:material:1.13.0` (MD3-слайдер в AltSeekbar; 1.14 у exteraGram требует minSdk 23, у нас 21 — без continuousModeTickCount).

**Хуки в org/telegram (помечены `// exteraGram`):**
- `SettingsActivity` — пункт «exteraStatic» (id −1, чёрный фон, `ic_foreground`) перед «Аккаунт», открывает `MainPreferencesActivity`; `prepareExteraItems()` при открытии поиска.
- `ProfileActivity.SearchAdapter` — результаты `SettingsRegistry` в поиске по настройкам.
- `LaunchActivity` — `ExteraConfig.loadConfig()` / `init()`, `IntentsController.handleIntent` (tg://extera, t.me/exteraSettings).
- `ApplicationLoader` — `RemoteUtils.initCached()`.
- `UItem` — поля и методы exteraGram (setSearchable, setLinkAlias, setCheckBoxIcon, showDivider, prioritizeTitleOverValue, asExteraExpandableSwitch, asButtonWithSubtext, …), равенство CHECK по id и CustomPreferenceCell.
- `UniversalAdapter` — привязка: цветные иконки, подзаголовок/высота, приоритет заголовка, check с подписью/иконкой/иконкой переключателя, master-switch SwitchGroup.
- `TextCell` (флаг), `TextCheckCell` (setIcon/reset), `TextCheckCell2` (getCollapseContainer), `FilterCreateActivity.NewSpan` (setTextColor), `LinkifyPort` (WEB_URL_REGEX), `TLRPC.TL_messageEntityCustomEmoji.local`, `SharedConfig.toggleUseSystemEmoji`, `AndroidUtilities.clearTypefaceCache`, `Theme.isCurrentThemeMonet` (пока всегда false).

**Восстановлено по smali / вручную (JADX ошибался):** AltSeekbar (регистры r0/r1/r3, endpoint haptic), SwipeActionsCell.restartCycle, выбор языка распознавания (any{}), LocaleUtils.isCustomEmojiOnlyLinkMessage, циклы с final-переменными (DoubleTapCell, StickerShapeCell, FabShapeCell), анонимный AltSeekbar в AppNavigation (min=0, max=2), ссылки this$0 в анонимных классах. Все правки — `tools/extera-port/postfix_rules.py`.

**Не перенесено на этапе 1 / долги:**
- `SwipeActionsHelper.drawBubble` — JADX сломан, заглушка; восстановить по smali на этапе 3 (сейчас превью свайпов без пузыря).
- `UIUtil.applyScaleStateListAnimator`, ActionBar glass (setGlassPadding/TitleTextSize/DrawGlassMiddlePill/ShadowAlpha, refreshTitlePosition, setTitleAnimatedX), ChatActivityFadeView.setTopFadeColor, TextCell.valueImageRight, FilterTabsView (эмодзи-иконки, static All chats), Monet — этап 2.
- ChatMessageCell-хуки превью (online-точка, hideViews, share button), relative last seen (3-арг. formatDateOnline), RestrictedLanguagesSelectActivity(1), TranslatorUtils/TranslationProviders — этап 3.
- Экспорт/импорт настроек (`.extera`), RemoteUtils с сетью, Boosty/донаты, AI/Feed/Export/camera — этап 4; плагины — этап 5.
- Yandex в списке поисковиков: у нас нет поля homepage в SearchEngine (12.10.6), Yandex создан без него; в список движков не добавлен (этап 3/4).
- Поиск в старом профиле (`ProfileActivity`, не `SettingsActivity`) пока не вызывает `prepareExteraItems()`.
- Сервисы exteraGram в коде остаются выключенными (api.exteragram.app, exteraAuthBot, remote-config канал), `customSavePath` по умолчанию «exteraStatic», причина удаления аккаунта «ЭКСТЕРАГРАМ» — решить на этапе 4.

## 7. Отложено владельцем (22.10.2026)

- Сервисы exteraGram (api.exteragram.app, exteraAuthBot, донаты, Boosty) сейчас отключены. Решение «что с ними делать» отложено: вернуться после этапов 2–5 (заменить на свои сервисы StaticGram или убрать совсем).

## 8. Этап 2 — итоги (22.10.2026, versionCode 7082, APK /srv/dl/test/StaticGram-Extera-stage2.apk)

На устройстве не проверялось — только сборка и проверка логики по коду. Все вставки в org/telegram помечены `// exteraGram`.

**Решения владельца, учтённые на этапе 2:**
- Папка сохранения по умолчанию — «StaticGram» (`customSavePath`); путь подключён в `MediaController` через `ExteraConfig.getSaveDir()` вместо зашитого «Telegram» (Pictures/Movies/Downloads/Music, API 29+ и ниже). Пустое значение — сохранять в корень.
- Причина удаления аккаунта: «StaticGram» вместо «ЭКСТЕРАГРАМ».
- Ссылки на настройки: генерируются `https://staticgram.top/staticSettings?s=<key>` (`SettingsRegistry.SETTINGS_LINK`) и `sg://staticSettings?s=<key>`. Принимаются также `tg://`/`sg://exteraSettings`, `tg://extera`, `sg://extera`, `https://t.me/exteraSettings?s=` (совместимость). Внутренний `tg://emoji?id=` в LocaleUtils заменён на `sg://emoji?id=`.
- Главный экран настроек: пункт «Плагины» всегда виден (заглушка «после этапа 5»), Crowdin скрыт, логотип — знак StaticGram цвета акцента в круге 96dp с фоном акцента низкой прозрачности, заголовок 24sp, версия серым.
- Шапка экранов exteraGram: перенесены glass-методы ActionBar (`setGlassPadding`, `setGlassTitleTextSize`, `setDrawGlassMiddlePill`, `setGlassShadowAlpha`, `refreshTitlePosition`). На экранах настроек средняя капсула не рисуется (только круглая «назад»; капсула появляется лишь при поиске), заголовок 20sp, на главном экране — после ухода логотипа (`setAdaptiveBackground`).

**Ожило (переключатель → место чтения):**
- Стекло: `glassOutlineStyle` — `BlurredBackgroundDrawable.updateColors`, `blur3/StrokeDrawable` (GLARE = как в Telegram; SOLID — ровная обводка цветом верхнего блика, приближение: full-stroke 12.10.6 не бэкпортирован; HIDDEN — без обводки и тени). `forceBlur` — `SizeNotifierFrameLayout`.
- Разделители: `dividerStyle` — `Theme.getColor/getDividerColor` (key_divider прозрачен вне LINE), `AndroidUtilities.getShadowHeight`, `DividerCell`. SEGMENTS («Material»-карточки): `RecyclerListView.useSegmentedSections` — каждый пункт отдельная карточка, зазор 2dp, внешние углы `sectionRadius`, внутренние 4dp. `sectionsSeparatedHeaders` (дефолт true, как в exteraGram) — `HeaderCell` (отступ 24, без фона), `RecyclerListView/UniversalRecyclerView/UniversalAdapter` (заголовки вне карточек, без разделителя перед заголовком). `sectionRadius` — радиус секций всех списков с `setSections()` (дефолт 20 вместо телеграмовских 16 — как в exteraGram).
- ActionBar: `centerTitle` (центрирование заголовка/подзаголовка, доступная ширина как в exteraGram, без анимации перемещения), `hideActionBarStatus` (ActionBar, DialogsActivity, DialogStoriesCell), `titleText` (0 = наш логотип+StaticGram без изменений; 1–3 = username / имя / «Чаты»).
- Аватары: `avatarCorners`, `singleCornerRadius`, AvatarCornerType FORUM/COMMUNITY, онлайн-точка в DialogCell/ShareDialogCell, кольцо историй — ~80 файлов (форк; список в коммите). Дефолт 28 = круг, как в Telegram. Форумные аватары в ProfileActivity/PeerColorActivity/ChatAvatarContainer и `rectangularAvatar` ProfileSearchCell оставлены телеграмовскими; GroupCallActivity не тронут.
- Табы папок: `tabIcons` (FilterTabsView: иконка `filter_*` слева от заголовка, ICONS_ONLY — только иконки), emoticon папки берётся с сервера (`dialogFilter.emoticon`) и кэшируется в prefs `extera_folders` (без миграции таблицы dialog_filter), сохраняется при правке папки (`FilterCreateActivity.saveFilterToServer`). `tabCounterMode` (HIDDEN / UNMUTED через `FolderCounters` в `MessagesStorage.calcUnreadCounters/updateFiltersReadCounter`). `hideAllChats` (вкладка «Все чаты» не добавляется, выбирается первая папка; перетаскивание вкладок в этом режиме отключено).
- Главный экран: `hideStories` (StoriesController, MessagesController.storiesEnabled, кнопка камеры), `hideFloatingButton`, `hideDialogsSearchBar` (`getSearchFieldHeight()` вместо `dp(SEARCH_FIELD_HEIGHT)` в 24 местах DialogsActivity), pillstack (по умолчанию пусто).
- Профиль: `gooeyAvatarAnimation` (выкл. — плавное исчезновение вместо «капли»).
- MD3: `newSwitchStyle` (Switch, TextCheckCell2), `newSliderStyle` (SeekBarView, SlideChooseView), `newFabStyle` + `squareFab` (дефолт true — квадратный FAB, как в exteraGram), `useSystemFonts` (AndroidUtilities.getTypeface), `forceSnow`/`eventType` (Theme).
- Навигация: `transitionAnimation` DEFAULT/SPRING/AOSP (ActionBarLayout, WindowTransitionAnimationHelper, PredictiveBackAnimationHelper — часть «фон маршрута»), `getSpringSwipeback` (пружинный свайп назад, `slideFragmentLite`), `predictiveBackIntensity` (множитель прогресса), `tabletMode` (`AndroidUtilities.isTabletInternal`, после перезапуска), `inAppVibration` в LayoutContainer. **Дефолт `transitionAnimation` у нас DEFAULT, а не SPRING**: пружинные переходы — самый горячий путь, без устройства не проверены; SPRING/AOSP включаются в настройках.
- Drawer и главное меню: `navigationDrawer`, `immersiveDrawerAnimation`, редактор пунктов (`mainMenuLayout/HiddenItems`) — `drawer/*`, `MainMenuHelper`, `DrawerLayoutContainer`, LaunchActivity, DialogsActivity. FEED и PLUGINS скрыты, BROWSER открывает сайт поисковика, QR сканирует без QRCodeSheet. В меню ⋮ по дефолту появился «Архив» (только при наличии архива) — дефолт exteraGram.
- Иконпаки: `iconPack` DEFAULT/SOLAR/REMIX — `IconManager`, `ExteraResources` (подмена Resources только при не-DEFAULT паке), `BaseIconPacks` (536 пар), 470 drawable `*_solar/*_remix`, `IconPacksActivity`.
- Иконка приложения: `AppIconsActivity` + activity-alias `org.telegram.messenger.StaticLightIcon` и `StaticMonetIcon` (API 31+) с нашим знаком; дефолт — прежний `DefaultIcon`. Брендовые иконки exteraGram не переносились. Согласовано с `LauncherIconController` (выбор в одном пикере выключает alias другого).

**Не ожило / отложено:**
- `newLoadingStyle` — нужен бэкпорт M3-загрузчиков 12.10.6 (LineProgressView/RadialProgressView/CircularProgressDrawable/RadialProgress2).
- `newChatHeaderStyle` (ChatHeaderUiHelper в ChatActivity/ChatAvatarContainer/ChannelAdminLogActivity) и `glassMessageMenu` (GlassMenuHelper) — чат, этап 3. `newNavigationBarStyle` (MainTabsUiHelper, BottomNavigationBar hidden/floating) — не перенесён.
- `senderMiniAvatars` — в нашем 12.10.5 DialogCell нет мини-аватаров отправителя (фича 12.10.6).
- `tabIcons` ICONS_ONLY: заголовок экрана не заменяется названием папки (у exteraGram — через DialogStoriesCell.setDialogsTitleOverride 12.10.6); анимации смены иконки нет.
- `customThemes` — этап 3 (ChatThemeController). Monet (`isCurrentThemeMonet`) — это темы ThemeActivity, а не экран «Внешний вид»: не переносился, по-прежнему false.
- Бейджи (`badges/*`) — источник данных api.exteragram.app, выключены (§7). Бейджи в drawer тоже выключены.
- Пользовательские иконпаки из файлов (SVG/zip, редактор, IconPackProvider), иконки нижних табов и EmojiTabsStrip — нет (нужны androidsvg/simplifiles и ключи 12.10.6).
- Predictive back с уменьшением/скруглением экрана (exteraGram переписал drawChild), edge extension AOSP, пружинные превью — стоковые.
- `FolderIcons.getEmoticonFromFlags` и выбор иконки папки (IconSelectorAlert) — этап 4.

**Риски этапа 3:**
- ChatActivity/ChatMessageCell: на этапе 2 там уже есть вставки аватаров (скелетоны, ChatMessageCell) — следующий перенос делать маленькими коммитами поверх них.
- `sectionsSeparatedHeaders`=true меняет все HeaderCell в приложении (отступ 24, без белого фона) — проверить старые не-секционные списки на устройстве.
- SPRING-переходы, если владелец захочет их по умолчанию, сначала проверить на устройстве (особенно `onTransitionAnimationProgress` фрагментов и `needDelayOpenAnimation`); откат — дефолт DEFAULT в ExteraConfig.
- `getShadowHeight()`=0 при HIDDEN/SEGMENTS влияет на ~40 мест — проверить, что нет нулевых по высоте теней с логикой деления.
- Строки `strings_extera.xml` дописывались несколькими агентами — при конфликте переимпортировать `tools/extera-port/importres.py`.

## 9. Этап 3 — итоги (22.10.2026, versionCode 7083, APK /srv/dl/test/StaticGram-Extera-stage3.apk)

На устройстве не проверялось: проверены только сборка (`assembleAfatStandalone` проходит) и логика по коду. Все вставки в org/telegram помечены `// exteraGram`. Работу делали 6 исполнителей, у каждого свои файлы (переводчик, адблок, ячейки, основные и медиа, экран чата, стекло и шапка). Коммиты — `a9c8734e..HEAD`.

**Ожило (ключ → где читается):**
- **Переводчик** `translator/**`, перенесён на Java, сеть через HttpURLConnection.
  - Провайдеры: 0 Telegram (наш `messages.translateText`), 1 Google, 2 Yandex, 3 Microsoft. Подпись `X-Mt-Signature` сверена со smali. Сервисы exteraGram не используются.
  - Очередь запросов с лимитами, паузой после 429, повторами, кэшем.
  - `translationProvider`: TranslateController (перевод чата и опросов; альтернативный провайдер снимает Premium-гейт), TranslateAlert2 (выбор провайдера в заголовке), TranslateButton, TextSelectionHelper.
  - `targetLang`: TranslateAlert2 и RestrictedLanguagesSelectActivity type=1. Значение переносится из `translate_to_language`.
  - Перевод перед отправкой (`TranslateBeforeSendWrapper`): ChatActivityEnterView, ChatAttachAlert, DialogsActivity, ShareAlert.
  - Свайп и двойной тап «Перевести».
- **Адблок** `adblock/**`.
  - Движок — `libetgadblock.so` (Brave adblock-rust 0.12.1 + JNI-обёртка exteraGram) в `TMessagesProj/jniLibs/{arm64-v8a,armeabi-v7a}`, нужны только libc и libdl. Загрузка в try/catch: на x86 адблок просто недоступен.
  - Подписки: 10 списков (uBO, AdGuard, EasyList/Privacy, RUAdList) и 43 скриптлета, кэш `files/adblock`.
  - `enableAdBlock` читается во встроенном браузере (BotWebViewContainer; мини-аппы не фильтруются, как у exteraGram), в WebActionBar (переключатель со счётчиком) и в ArticleViewer (экран «Страница заблокирована»).
  - Yandex добавлен в SearchEngine, в конец списка, чтобы сохранённый индекс поисковика остался прежним.
- **Сообщения** (ChatMessageCell и соседние файлы):
  - `stickerSize`, `stickerShape` (+ StickerCell/StickerEmojiCell), `stickerTimeMode` (`StickerTime`, MessagePreviewView);
  - `removeMessageTail` (MessageDrawable), `replaceEditedWithIcon`, `hideShareButton`, `showOnlineStatus` (точка на аватарах в чате);
  - `showForwardsCount` (`ForwardsCountUiHelper`), `showResultsBeforeVoting` (UI + MessagesController: голос и сразу отзыв);
  - `replyColors`/`replyEmoji`/`replyBackground` (ReplyMessageLine), `hideReactionsIn*` (пузырь, оверлей эффектов, DialogCell, ChatActivity);
  - превью настроек (онлайн-точка, hideViews, share).
  - `ChatUtils` перенесён целиком; поиск через ботов заменён локальным кэшем.
- **Экран чата** (ChatActivity):
  - `SwipeActionsHelper.drawBubble` восстановлен по smali; `swipeActions`/`Loop`/`Reversed`, дефолт «2» = только ответ, как в Telegram;
  - `doubleTapAction(+OutOwner)`;
  - меню сообщения: сохранить, повторить, очистить, копировать фото, история, жалоба, «Подробнее» (`MessageDetailsPopupWrapper`), сохранить стикер в галерею;
  - `groupMessageMenu` (ряд иконок + каскад), `hideKeyboardOnScroll`, `addCommaAfterMention` (+ MentionsContainerView);
  - `quickTransitionForChannels`/`Topics`, `bottomButton`, `customThemes` (+ ChatThemeController), `quickAdminShortcuts`, `replyEmoji`;
  - `hideSendAsPeer` (ChatActivityEnterView).
- **Стекло и шапка:**
  - `glassMessageMenu`: GlassMenuHelper; меню сообщения, реакции, плашки, меню ⋮ (ActionBarMenuItem), ChatSelectionReactionMenuOverlay. При выключении — классическое непрозрачное меню.
  - `newChatHeaderStyle` (M3-шапка): ChatHeaderUiHelper, ActionBar, ChatAvatarContainer, ChannelAdminLogActivity, верхнее затухание, статус-бар.
- **«Основные» — раньше не читались, теперь подключены:**
  - `disableNumberRounding`, `formatTimeWithSeconds`, `relativeLastSeen` (LocaleController, шапка чата, подпись в настройках);
  - `disableNotificationDelay`, `downloadSpeedBoost`, `uploadSpeedBoost`;
  - `hidePhoneNumber`, `showIdAndDc` (строка ID в профиле, DC в PhotoViewer);
  - `hideArchiveFolder`, `archiveOnPull`, `disableUnarchiveSwipe`;
  - `filterZalgo` (Emoji.replaceEmoji).
- **«Чаты», прочее и медиа:**
  - `unlimitedRecentStickers`, `disableGreetingSticker`, `disableMarkdown` (getEntities + пункт в меню выделения);
  - `alwaysSendInHD`, `hideCameraTile`, `doubleTapSeekDuration` (ошибка exteraGram мс/с исправлена), `swipeToPip`, `pauseOnMinimize{Video,Voice,Round}`, `preferOriginalQuality`, `unmuteWithVolumeButtons`;
  - `notifications/*` (уведомления по аккаунтам).

**Не ожило и почему:**
- **Широкие посты** (`widePostsInChannels`/`widePostsInFeed`, WidePosts) — около 250 вставок в разметку ChatMessageCell под 12.10.6. Без устройства слишком велик риск сломать все сообщения в каналах. Нужен отдельный шаг с проверкой на устройстве. Сейчас переключатель ни на что не влияет: для каналов по умолчанию выключен, ленты нет.
- `PreformattedScrollView` + `MarkdownUtils` (просмотр кода и .txt в IV): переключателя нет, сам по себе меняет поведение — решение владельца.
- `ChatActivityEnterViewStaticIconView` нужен только с не-дефолтным иконпаком. Отдельного «нового стиля панели ввода» в 12.10.6 нет; `newNavigationBarStyle` относится к нижней навигации главного экрана.
- Долгие нажатия на «Ответить»/«Ссылка»/«Переслать» (ForwardContext), кнопка «показать результаты» у опроса, автор набора стикеров (нужен бот), «сохранить трек в канал», фильтр terms в хэштегах.
- Предзагрузка адблока при старте выключена: списки скачиваются при первом открытии браузера.
- ИИ (`showGenerateButton`, `telegramAi*`), Vosk, CameraX, feed, плагины, RegDate — этапы 4–5 или сервисы (§7).
- **Чего у exteraGram 12.10.6 нет:** скрытия спонсорских сообщений (есть только стоковое Premium-скрытие; feed/ads наоборот вставляет рекламу), подтверждений перед отправкой, отключения спойлеров. «Отправить без звука» — стоковое Telegram.

**Дефолты exteraGram, которые меняют поведение Telegram (оставлены, нужно утверждение):**
- `removeMessageTail=true`, `replaceEditedWithIcon=true`, `hideShareButton=true` (кнопка скрыта и везде, где рисуется аватар);
- `stickerSize=12` (стикеры примерно на 13% меньше), `stickerShape=1`;
- `hideKeyboardOnScroll=true`, `addCommaAfterMention=true`, `bottomButton=2` («Обсудить» вместо mute);
- `quickAdminShortcuts=true`, `groupMessageMenu=true`, `glassMessageMenu=true` (стекло exteraGram немного отличается от 12.10.5);
- `disableUnarchiveSwipe=true`, `showIdAndDc=1`, `unmuteWithVolumeButtons=false` (выключает стоковое включение звука кнопкой громкости), `filterZalgo=true`, разбор `[текст](url)`;
- `enableAdBlock=true` и remote-флаг `use_adblock`: у exteraGram дефолт remote-флага false, у нас true.
- Без ключа: иконка и тип файла в ответе, иконка канала, время у служебных сообщений, меню копирования по долгому нажатию на кнопку бота, «Перевести на…» в ShareAlert, заголовок TranslateAlert2 «Telegram ▾».

**Риски этапа 4:**
- ChatActivity: свайп переписан поверх стокового жеста; `bottomButton=0` меняет высоту острова ввода; у ряда groupMessageMenu могут быть неверные скругления; каскад может пересекаться со стоковой анимацией пунктов. Первое, что проверить на устройстве.
- `hideCameraTile` (сложный код камеры), `hideArchiveFolder` (удаление внутри `getDialogs` — нет ли цикла перерисовки), `showResultsBeforeVoting` (реально голосует и отзывает голос), `uploadSpeedBoost` (куски по 512 КБ).
- Адблок: закрытый бинарь (MPL-2.0 adblock-rust, обёртка без лицензии, около 5 МБ). Паника в Rust роняет процесс. Альтернатива — собрать свой .so.
- Переводчик: тексты уходят в Google/Yandex/Microsoft только при выборе не-Telegram провайдера; эндпоинты Yandex и MS неофициальные.
- Этап 4 трогает те же ChatActivity, ChatActivityEnterView, PhotoViewer и ChatMessageCell (ИИ, Vosk, камера, бейджи) — продолжать маленькими коммитами поверх этапа 3. Широкие посты лучше делать вместе с первым тестом на устройстве.

**Решения владельца от 22.10:**
- Адблок по умолчанию выключен (`enableAdBlock` = false в ExteraConfig). Гейт `use_adblock` (remote, фолбэк true) не тронут: включает фичу пользовательский переключатель.
- Просмотр кода и .txt в Instant View включён всегда (переключателя у exteraGram нет): PreformattedScrollView и MarkdownUtils портированы в `com.exteragram.messenger`, MarkdownParser.isMarkdown/fromMarkdown и BlockPreformattedCell в ArticleViewer повторяют smali exteraGram. Сервисы exteraGram не подключались.
