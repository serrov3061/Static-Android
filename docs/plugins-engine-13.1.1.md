# Движок плагинов: что в exteraGram 13.1.1 «в разы быстрее» и что из этого переносим

Ветка `port/c-pyengine` (от `feat/extera-13.1.1`). Базовая линия — наш текущий код
(исходников exteraGram 12.10.6 больше нет, наш движок — их реконструкция 12.10.6 с
доводкой до паритета 13.1.1 по Java-части).

## Источники

- exteraGram 13.1.1 (72379), apktool: `mgmt:/root/ext-compare/ex/dec/` (smali*, `assets/plugins_pysdk`).
- jadx 1.5.6 по smali движка (`PythonPluginsEngine*`, `PluginsController*`, `hooks/*`, `models/*`,
  `pip/*`, `utils/*`, `xposed/*`, `de/robv/android/xposed/*`): `mgmt:/root/ext-compare/work-pyengine/out/`.
- Aliuhook 1.1.4-sg1 (наш мост Xposed API), jadx: `mgmt:/root/ext-compare/work-pyengine/aliu/out/`.
- Отчёт сверки `mgmt:/root/ext-compare/report/full-diff.txt`.

## Что на самом деле изменилось в 13.1.1

### 1. Java-движок (PluginsController / PythonPluginsEngine / hooks / xposed) — по скорости почти ничего

Построчная сверка декомпиляции 13.1.1 с нашим кодом:

| Место | 13.1.1 | У нас | Влияние на скорость |
|---|---|---|---|
| `PluginsController.init` | `lifecycleGeneration` + `PendingInitialization` (init во время shutdown откладывается, а не теряется) | нет | нет, это корректность рестарта |
| Старт движков | тот же `runOnPluginsQueue`, движки по очереди | так же | нет |
| `loadPlugins` | последовательно: `validatePluginFromFile` → `loadPlugin` на pluginsQueue; добавлена только проверка поколения | так же | нет |
| `createPluginInstance` | + `BasePlugin._begin_lifecycle(id)` (SDK), иначе идентично, включая `refreshImportCaches` → `importlib.invalidate_caches()` на КАЖДЫЙ плагин | идентично | нет (и там, и у нас лишняя работа — см. ниже) |
| `unloadPlugin` | + `extera_utils.classes.release_plugin(id)` | свой `_cleanup` | нет, это освобождение объектов |
| Кэш хуков: `exactMatchEventHooksCache`, `substringMatchEventHooksCache`, `interestedPluginsCache`, `hooksCacheDirty` | есть | уже есть (паритет) | — |
| `hookedMembersCache` + `hasXposedHook(name, classes)` | новое | нет | не ускорение: используется ровно в одном месте — `PredictiveBackNavigationHelper` проверяет, перехвачен ли `onBackPressed`, чтобы не включать предиктивный «назад» |
| `getPluginSetting` (Java-кэш `settingsCache`) | есть | уже есть | — |
| `executeHook` / `PyMethodCaller` / стратегии | идентично | идентично | — |
| `HookFilter` (MVEL, `CompiledCondition`) | компиляция выражения 1 раз | уже так же | — |
| `PyMethodHook` / `PyMethodReplacement` | идентично | идентично | — |
| `de.robv...XposedBridge` + `HookCallbackDispatcher` | поверх dev.exterahook (LSPlant в libtmessages + ShadowHook); снапшот колбэков, `EMPTY_ARGS` для методов без аргументов, проверка примитивного результата | Aliuhook (LSPlant): тот же алгоритм, снапшот `CopyOnWriteSortedSet` | пренебрежимо (одна аллокация `Object[0]`); exterahook это 12.10.x→13.1.1 не меняли |
| SDK из `assets/plugins_pysdk/sdk-<abi>.zip` + `v.txt`, `SdkState`, распаковка только при новой версии в APK | новое состояние/UI, логика распаковки та же | у нас SDK — исходники приложения, Chaquopy пакует их в `app.imy` (.pyc) и не распаковывает | неприменимо |

Вывод: в Java-части 13.1.1 нет изменений, которые дают «в разы». Порт «как в 13.1.1»
по Java дал бы только корректность рестарта.

### 2. Python SDK — вот где их ускорение

`plugins_pysdk/sdk-arm64-v8a.zip` 13.1.1 (SDK 1.4.6.3): все модули, кроме трёх пустых
`__init__.py` и `elyxcore/__init__.py`, — нативные расширения **Cython 3.1.4**
(`PyInit_hook_utils`, `_cython_3_1_4`…): `base_plugin.so`, `hook_utils.so`, `client_utils.so`,
`plugin_settings.so`, `extera_utils/classes.so`, `elyxcore/_plugin_engine.so` и т.д.
SDK 1.4.5.x (12.10.x) тоже был Cython (+ ollvm-обфускация, см. память по opexgram), так что
ускорение 13.1.1 — это внутренности закрытого SDK 1.4.6.3 (сборка/кэши внутри .so),
которые изнутри не читаются. Что видно по строкам .so: кэш хуков классов
(`__jclass_build_hooks_cache__`), `_settings_cache` в plugin_settings, очередь
`CACHE_CLEAR_QUEUE` в client_utils.

Наш SDK — свой чистый Python (`python_sdk`, 1.4.5.7), его горячие места надо ускорять
самим. Ниже — горячие точки нашего движка и решения.

## Таблица: ускорение → применимо? → решение

| # | Ускорение | Применимо к нам (Chaquopy + Aliuhook + свой SDK) | Риск | Решение |
|---|---|---|---|---|
| 1 | Скомпилировать SDK в Cython, как exteraGram | Технически да (Chaquopy грузит расширения), но: `app.imy` общий для всех ABI → нужны per-ABI сборки .so и упаковка как requirements; кросс-компиляция под 4 ABI с заголовками Python 3.11 Chaquopy; Cython ломает `sys._getframe()`-логику (`log_utils`, `elyxcore._importer` — определение плагина по кадру вызова); отладка трейсбеков плагинов хуже | высокий | **ПРОПУЩЕНО** в этом заходе; кандидат отдельной задачи после замеров (сначала понять, сколько времени реально уходит в SDK) |
| 2 | Распаковка SDK только при смене версии (`v.txt`) | SDK не распаковывается вовсе (Chaquopy читает .pyc из `app.imy`; свои ассеты Chaquopy распаковывает только при смене APK) | — | **НЕ НУЖНО** |
| 3 | Метаданные плагина: у нас `ast.parse` ВСЕГО файла каждого плагина на каждом старте (большие плагины — сотни КБ) | да | низкий (ключ = путь + sha256 содержимого) | **СДЕЛАНО**: постоянный кэш метаданных `plugins/.cache/metadata.json`; при промахе — тот же парсер. Экономится сам разбор; модуль `ast` всё равно грузится через `dataclasses` (см. п. 9) |
| 4 | `importlib.invalidate_caches()` + сброс `path_importer_cache` на каждый плагин при старте (так же и в 13.1.1) | да | низкий | **СДЕЛАНО**: на старте один сброс на всю пачку; для модуля, ещё не импортированного, повторный сброс не делается. Установка/включение/перезагрузка одного плагина — как раньше |
| 5 | `python.getModule(...)` (это `importlib.import_module` через JNI) в каждом `getPluginSetting`/`setPluginSetting`/`executeOnAppEvent`/`parsePluginMetadata` | да | низкий | **СДЕЛАНО**: модули `plugin_settings`, `base_plugin.AppEvent`, `metadata_parser` кэшируются как PyObject на время жизни SDK (сбрасываются в `stopAndUnloadSdk`) |
| 6 | Рефлексия в хуках: `get_private_field`/`set_private_field`/`get_static_private_field` каждый раз `getDeclaredField` по цепочке суперклассов с Java-исключениями (NoSuchFieldException → PyException) + `setAccessible` | да — самые частые вызовы из `before/after_hooked_method` | низкий | **СДЕЛАНО**: кэш Field по (класс, имя), включая «нет поля» |
| 7 | `find_class()`/`class_for_name()` каждый раз: `ApplicationLoader.applicationContext.getClassLoader()` + `Class.forName` (3–4 JNI-вызова, при отсутствии класса — исключение) | да | низкий (загрузчик приложения неизменен) | **СДЕЛАНО**: кэш по имени класса (и отрицательный) |
| 8 | `plugins_controller()` = `jclass(...).getInstance()` на каждый вызов SDK | да (синглтон `SingletonHolder`) | нулевой | **СДЕЛАНО**: кэш экземпляра |
| 9 | Тяжёлый `import inspect` (тянет `ast`, `dis`, `tokenize`…) при импорте `base_plugin` — на хосте ≈ 45 % времени импорта SDK | нет: в Python 3.11 `dataclasses` сам импортирует `inspect` и `ast`, а dataclass — часть публичного API SDK (`HookResult`, `MenuItemData`, настройки) и почти всех плагинов | — | **ПРОПУЩЕНО** (выигрыша нет) |
| 10 | `set_setting` перезаписывает JSON-файл плагина даже при том же значении (плагины пишут счётчики/флаги в хуках) | да | нулевой | **СДЕЛАНО**: нет записи и нет сброса Java-кэша, если значение не изменилось |
| 11 | `lifecycleGeneration`/`PendingInitialization` | корректность, не скорость | — | **ПРОПУЩЕНО** (вне задачи «быстрее») |
| 12 | `hasXposedHook` + `hookedMembersCache` | нужен только для предиктивного «назад» | — | **ПРОПУЩЕНО** (не ускорение) |
| 13 | `EMPTY_ARGS`/валидация примитивов в `HookCallbackDispatcher` | мост — библиотека Aliuhook; выигрыш — одна аллокация | — | **ПРОПУЩЕНО** |
| 14 | Параллельная загрузка плагинов | ни 13.1.1, ни мы: один интерпретатор + GIL, порядок `on_plugin_load` важен | высокий | **НЕ ДЕЛАЕМ** |

Безопасность не трогается: окно установки, серверный скан, локальный блок-лист
(`PluginGuard.applyLocalBlocks` до загрузки), подтверждения чувствительных действий,
доверенные каналы, safe mode — всё на прежних местах. API SDK 1.4.5.7 не меняется.

## Замер (7169 против новой сборки)

Тайминги пишутся только при включённых логах (`BuildVars.LOGS_ENABLED`: debug-сборка
или «Включить логи» в отладочном меню) — в FileLog и в logcat с тегом **`sgPluginsPerf`**:

```
adb logcat -s sgPluginsPerf
```

- `python start` — `Python.start` (первый запуск после установки APK включает распаковку Chaquopy);
- `sdk init` — импорт `_sdk_version` + `base_plugin`;
- `settings init`, `native init` — `plugin_settings.init`, `_sg_native.install`;
- `plugin <id>: metadata X ms, import Y ms, enable Z ms` — на каждый плагин
  (`enable` = `on_plugin_load` + регистрация настроек);
- `plugins loaded: N (enabled M) in X ms` — вся пачка;
- `engine ready in X ms` — от `PluginsController.init` до готовности всех движков.

В 7169 этих строк нет; для сравнения со старой сборкой есть строка logcat `sgPlugins`
`Python plugin system initialized. Total: …` — разница её времени и старта процесса
(`am start -W` / время первой строки процесса в logcat). Для чистого сравнения: тот же
набор плагинов, холодный старт (`am force-stop`), 3–5 прогонов, второй и последующие
(первый после установки включает распаковку Chaquopy и компиляцию .pyc плагинов).

## Что проверить на устройстве

1. Установка плагина из файла/канала: окно установки, скан, блок-лист — без изменений;
   плагин виден в списке, метаданные (имя/версия/иконка) верные.
2. Обновление плагина тем же id (новый файл) — метаданные обновились (кэш по sha256).
3. Хуки: Xposed-хуки плагина срабатывают; `get_private_field`/`set_private_field` в хуках
   (поля суперклассов, статические поля) работают; плагины с `find_class`.
4. Настройки: переключатели/поля сохраняются после рестарта; `set_setting` из кода плагина.
5. Safe mode: включение, выход, после краша плагина.
6. Включить/выключить/удалить плагин без рестарта; рестарт движка из настроек.

## Коммиты ветки `port/c-pyengine`

| Коммит | Что |
|---|---|
| `0aeab8be4` | этот документ |
| `286f766a7` | тайминги `sgPluginsPerf` |
| `631e06d0b` | п. 3 — кэш метаданных (в сообщении коммита неточность: `ast` при попадании в кэш всё равно загружен через `dataclasses`, экономится сам разбор) |
| `f9d6fb52e` | пп. 4, 5 — один сброс import-кэшей на пачку, кэш SDK-модулей в Java |
| `faf8b30d0` | пп. 6–8 — кэши рефлексии в хуках, `find_class`, контроллер |
| `7a78e31aa` | п. 10 — без перезаписи настроек при том же значении |

Тесты: `TMessagesProj/src/test/python_sdk/test_engine_speedups.py` (хост, заглушка `java`),
весь набор — `cd TMessagesProj/src/test/python_sdk && python3 -m pytest -q`.

Что изменилось в поведении (крайние случаи):
- `find_class(имя)` теперь возвращает один и тот же объект для одного имени и помнит
  отсутствующие классы (загрузчик приложения неизменен; классы, созданные плагином через
  ClassProxy/dexmaker, и раньше через `find_class` не находились).
- `plugin_settings.set_setting` возвращает `True/False` (раньше `None`). Библиотека, которая
  правит `_settings_cache[...]` напрямую и потом зовёт `set_setting` с тем же значением, чтобы
  сохранить файл, теперь должна звать `_save_settings_to_file()` (zwylib так и делает).
