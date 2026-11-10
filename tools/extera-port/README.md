# Инструменты переноса exteraGram → StaticGram

Скрипты, которыми получены Java-исходники `TMessagesProj/src/main/java/com/exteragram` этапа 1.
Пути внутри скриптов указывают на рабочую папку исполнителя (`$S`/`B`) — поправьте под себя.

## Надёжная декомпиляция

`/root/exterastatic-src/decompiled` неполная (JADX упал на ~150 классах exteraGram и пропустил ChatActivity,
DialogsActivity, LaunchActivity, ProfileActivity). Декомпиляция JADX напрямую из smali (`jadx in/*.smali`)
**ненадёжна**: путает тела вложенных анонимных классов. Рабочий путь:

```bash
cd /root/.cache/jadx-extera
# 1. smali -> dex: apktool-проект из decoded/apktool.yml + symlink'и smali*
java -jar apktool.jar b -j 8 -o aproj.apk aproj      # dex в aproj/build/apk/classes*.dex
# 2. dex -> java без подстановки констант (иначе JADX подменяет литералы чужими static final)
./bin/jadx -j 6 --show-bad-code --no-replace-consts -d full2 aproj/build/apk/classes*.dex
```

## Конвейер

- `regen.sh` — копирует классы из `derived.txt` (кроме `handwritten.txt`) из JADX-дерева и прогоняет:
  - `clean.py` — убирает Kotlin/R8-артефакты: аннотации `@Metadata` и т.п., восстанавливает enum,
    заменяет `$$ExternalSyntheticBUOutline`, `StringsKt.contains$default`, `postNotificationNameOnUIThread`,
    анонимные классы с `super(...)` в инициализаторе, неиспользуемые импорты;
  - `fixf0.py` — `this.f$0`/`this.this$0`/голый `this` в лямбдах R8 → `Outer.this` или параметр static-лямбды;
  - `postfix.py` + `postfix_rules.py` — точечные правки (FIX/SUB/METHOD), каждая с комментарием почему
    (восстановление по smali, заглушка до этапа N); `kprops.py` переводит Kotlin-DSL `SwitchGroup` в Java.
- `importres.py` (на sg) — переносит недостающие строки (en из `res/values`, ru из
  `assets/extera_locales/values-ru/extera.xml`) в `res/values*/strings_extera.xml` и нужные drawable.
- `genconfig*.py`, `gendelegates.py` — генерация полей/геттеров `ExteraConfig`/`DebugConfig` из Kotlin-делегатов.

Рукописные (не из JADX) классы: `config/*`, `ExteraConfig`, `ExteraServices`, `debug/DebugConfig`,
`backup/PreferencesUtils` (часть), `preferences/BasePreferencesActivity`, `SwitchGroup`, `utils/SettingsRegistry`,
`utils/IntentsController` (часть), `utils/network/RemoteUtils` (кэш), `utils/ui/ChatHeaderUiHelper` (часть),
`utils/ExteraCompat`, `utils/ui/PortPendingActivity` и заглушки будущих этапов.
