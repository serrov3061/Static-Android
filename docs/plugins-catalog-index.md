# Plugin catalog index — contract for @PluginsBot

The client plugin catalog (port of exteraGram 13.1.1 `plugins/catalog/*`, code in
`TMessagesProj/src/main/java/com/exteragram/messenger/plugins/catalog/`) reads plugin repositories
as JSON **indexes**. exteraGram calls them "Git repositories"; the format is the same for our
built-in catalog and for any third-party repository a user adds. This document describes exactly
what `GitPluginRepository` accepts, so `/opt/staticplugins` can generate the index.

## 1. Where the client looks

| What | Value |
|---|---|
| Built-in repository URL | app config key **`sg_plugins_catalog_url`** (string, must be `https://…`). Missing / empty / not HTTPS → `https://plugins.staticgram.top/catalog/index.json` |
| Built-in repository channel | app config key **`sg_plugins_catalog_channel`** (username, default `StaticPlugins`): the client resolves the channel and shows its avatar in the catalog header and in the repositories list (puzzle icon until it is resolved) |
| Built-in repository id | `-1` (local id, "Verified" section, cannot be removed) |
| Trust | Files from the built-in repository open the install sheet as a **trusted source** (no "Unknown sources" gate). Files from user-added repositories are **unknown sources** (same as exteraGram). Both always go through PluginGuard (server check `sg_plugin_check_url`, local blocklist) and the static analysis in `InstallPluginBottomSheet`. |
| Refresh | when a catalog screen opens, at most once per 10 min per repository; "Refresh" in the menu forces it |
| HTTP client | OkHttp, 10 s connect/read/write timeouts, follows redirects; `response.request().url()` (the final URL after redirects) is the **base URL** for relative `file` links |

When the config URL changes, the client drops the cached built-in repository (entries + ETag) and
loads the new URL.

User-added repositories (`GitPluginRepository.normalizeUrl`):
* `github.com/owner/repo` → `https://raw.githubusercontent.com/owner/repo/HEAD/extera-plugins.json`
* `github.com/owner/repo/tree/<ref>[/<path>]` → `raw.githubusercontent.com/owner/repo/<ref>/<path>/extera-plugins.json`, then jsDelivr `cdn.jsdelivr.net/gh/owner/repo@<ref>/<path>/extera-plugins.json` as fallback
* `github.com/owner/repo/blob/<ref>/<path>/extera-plugins.json` → same as `/tree/<ref>/<path>`
* any other `https://…/<name>.json` URL is used as is (exteraGram accepts only `…/extera-plugins.json`; StaticGram any `.json`)
* plain `http://` and URLs with whitespace are rejected

Share links: `https://staticgram.top/staticPluginRepo?git=<url-encoded index URL>` (also
`sg://staticPluginRepo?git=…` and exteraGram's `https://t.me/exteraPluginRepo?git=…`).

## 2. HTTP behaviour

Request: `GET <index url>`, header `If-None-Match: <etag>` when the client has an ETag **for that same URL**.

| Response | Client |
|---|---|
| `304 Not Modified` | `NotModified`: keeps the cached plugins, only bumps the sync time |
| `2xx` with a body ≤ **1 MiB** (1 048 576 bytes) | parsed; `ETag` response header is stored (send a strong or weak ETag — any string, echoed back verbatim) |
| `2xx` with a body > 1 MiB | `Failed` |
| any other status / network error / invalid JSON | `Failed` (cached plugins stay; "Couldn't refresh the catalog") |
| JSON with `"schema"` **> 1** | `Unsupported` ("This repository needs a newer version of StaticGram"); nothing cached is changed |
| JSON with `"schema"` < 1 or missing, no `"name"`, or no `"plugins"` array | `Failed` |

Please serve the index with `Content-Type: application/json; charset=utf-8` and an `ETag`
(e.g. sha256 of the body). Encoding must be UTF-8.

## 3. Index format (schema 1)

Top level object:

| Field | Type | Required | Notes |
|---|---|---|---|
| `schema` | int | **yes** | must be `1` |
| `name` | string | **yes** | repository title (trimmed, non-empty) — shown in the list and the catalog header. For us: `"Каталог StaticGram"` |
| `homepage` | string | no | HTTPS URL; "Open Repository" in the menu. Non-HTTPS is ignored. For us: `https://t.me/StaticPlugins` equivalent on our network, e.g. `https://staticgram.top/StaticPlugins` |
| `channel` | string | no | Telegram channel username (`StaticPlugins`, `@StaticPlugins` or `https://t.me/StaticPlugins`, 4-32 chars): the client shows that channel avatar for the repository. Ignored for the built-in repository (it uses `sg_plugins_catalog_channel`); invalid values are ignored |
| `plugins` | array of objects | **yes** | see below; invalid items are skipped silently |

"String" fields are read only when the JSON value is a string; the value is trimmed and an empty
string counts as absent. Unknown fields are ignored everywhere.

Plugin object:

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | **yes** | plugin id (`__id__` of the plugin). Must be unique in the index — later duplicates are dropped. Must equal the id inside the file (checked for `.plugin` when the Python engine is running; mismatch = download refused) |
| `name` | string | **yes** | display name |
| `version` | string | **yes** | must equal `__version__` of the file (same check as `id`). Update detection compares the first `\d+(\.\d+)*` of this value with the installed plugin's version |
| `file` | string | **yes** | URL of the file, absolute or relative to the index URL (resolved like a browser link). The resolved URL must be **HTTPS**. Its **last path segment is the file name** the client saves and installs — it must end with a supported extension: `.plugin` (Python), `.elyx` or `.eaf` (Elyx; the client looks only at the part after the last dot, so `.elyx.zip` is NOT accepted here); other files are hidden. The name must not be `.`/`..` or contain `/`, `\`, NUL |
| `sha256` | string | **yes** | 64 **lowercase** hex chars, SHA-256 of the exact file bytes |
| `size` | int | **yes** | exact file size in bytes, `> 0` and `≤ 16 MiB` (16 777 216; StaticGram limit = PluginGuard limit; exteraGram has none). The download is aborted when it exceeds `size`, and rejected unless both size and SHA-256 match |
| `description` | string | no | 2 lines in the cell; Markdown-ish formatting (`**bold**`, links, `@username`) is rendered by `LocaleUtils.fullyFormatText`. Missing → "No description provided." |
| `author` | string | no | shown under the description, `@usernames` become links. Missing → "Unknown Author" |
| `icon` | string | no | `"<stickerSetShortName>/<index>"`, regex `^[a-zA-Z][a-zA-Z0-9_]*/\d+$` (same as `__icon__` of plugins): the sticker at `index` (0-based) of that set **on our network**. Anything else → default plugin icon |
| `category` | string **or** array of strings | no | category ids, max **3** (extra values dropped, duplicates removed, unknown ids ignored, case-insensitive). Ids: see §4 |
| `updated_at` | string | no | ISO-8601 with offset, e.g. `"2026-11-08T12:00:00Z"` / `"2026-11-08T15:00:00+03:00"` (parsed by `OffsetDateTime.parse`). Drives "Recently Updated" order and, on the first sync, the "Newest" order. Unparseable/missing → time of the sync |
| `app_version` | string | no | stored (min app version, like `__min_version__`), not shown yet |
| `sdk_version` | string | no | stored, not shown yet |
| `requirements` | string **or** array of strings | no | stored (joined with `", "`), not shown yet |
| `downloads` | int ≥ 0 | no | **StaticGram extension** (exteraGram has no counter for Git repos). When any plugin of the repository has `downloads > 0`, the "Most Viewed" sort appears and orders by it |

There are **no** `banner`, `links`, `changelog` or `channel` fields in schema 1 — the client ignores
them. (The "links" in the cell are links inside `description`/`author`.) Banners stay in the
channel posts.

One object = one plugin = its current version. Keep only the latest accepted version per `id` in the
index (the client would show the newest by `updated_at` anyway).

### What the client derives

* entry id = `id.hashCode()` (Java), +1 on collision; local file id = first 8 bytes of
  `sha256("<repositoryId>:<id>:<sha256>")` with the sign bit set.
* A changed `sha256` for the same `id` counts as a new version: "N new versions found" after Refresh,
  and the plugin moves to **Updates** when the parsed `version` is greater than the installed one.
* **NEW** badge: plugin not installed and first seen (or its `updated_at` on the first sync) within the last 24 h.
* Downloads are cached in `cache/plugin_catalog/<sha256>/<file name>`, so a changed file must change `sha256`.

## 4. Categories

The 8 ids are the same as `pluginsbot/categories.py`:

| id | RU title (client) | bot title |
|---|---|---|
| `appearance` | Оформление | Внешний вид |
| `chats` | Чаты | Чаты |
| `media` | Медиа | Медиа |
| `privacy` | Приватность | Приватность |
| `tools` | Инструменты | Инструменты |
| `integrations` | Интеграции | Интеграции |
| `fun` | Развлечения | Развлечения |
| `libraries` | Библиотеки | Библиотеки |

Send `plugins.categories` (TEXT[]) as `category` in canonical order, first 3.
Category tabs appear only for categories that at least one plugin has.

## 5. Mapping from the @PluginsBot database

| index field | source (`plugins` table) |
|---|---|
| `id` | `plugin_id` |
| `name` | `name` |
| `version` | `version` |
| `description` | `description` (RU) |
| `author` | `author` |
| `icon` | `icon` (only if it matches `set/index`) |
| `category` | `categories` |
| `app_version` | `min_version` |
| `sdk_version` | `sdk_version` |
| `sha256` / `size` | `sha256` / `file_size` (must be of the file actually served) |
| `file` | the published file under `https://plugins.staticgram.top/…`, file name = `file_name` (must keep the extension) |
| `updated_at` | `published_at` (ISO-8601, UTC `Z`) |
| `downloads` | optional counter, if the bot has one |

Include only `status='published'` rows, not removed, `file_ext` in `.plugin/.elyx/.eaf`,
`file_size ≤ 16 MiB`. Generate the file name per version (or per sha256 directory) so a cached
CDN copy can never serve old bytes for a new `sha256`, e.g.
`https://plugins.staticgram.top/f/<sha256>/<file_name>`.

## 6. Full example

```json
{
  "schema": 1,
  "name": "Каталог StaticGram",
  "homepage": "https://staticgram.top/StaticPlugins",
  "plugins": [
    {
      "id": "message_translator",
      "name": "Переводчик сообщений",
      "version": "1.4.2",
      "file": "https://plugins.staticgram.top/f/3f7a0c5e1b2d4c6f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f/message_translator.plugin",
      "sha256": "3f7a0c5e1b2d4c6f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f",
      "size": 18342,
      "description": "Переводит входящие сообщения по долгому нажатию. Настройки — в **Плагины → Переводчик**.",
      "author": "@example_dev",
      "icon": "exteraPluginsAdaptive/12",
      "category": ["chats", "tools"],
      "updated_at": "2026-11-07T18:20:00Z",
      "app_version": "12.10.6",
      "sdk_version": "1.4.5.7",
      "requirements": ["requests"],
      "downloads": 412
    },
    {
      "id": "liquid_theme",
      "name": "Liquid Theme",
      "version": "2.0",
      "file": "f/9b1c.../liquid_theme.elyx",
      "sha256": "9b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c",
      "size": 204800,
      "description": "Тема с эффектом жидкого стекла.",
      "author": "Static Team",
      "category": "appearance",
      "updated_at": "2026-11-08T09:00:00+03:00"
    }
  ]
}
```

(The second `file` is relative: with the index at `https://plugins.staticgram.top/catalog/index.json`
it resolves to `https://plugins.staticgram.top/catalog/f/9b1c.../liquid_theme.elyx`.)

## 7. Checklist for the generator

1. `schema: 1`, `name`, `plugins` present; body ≤ 1 MiB (≈ 2–3k plugins).
2. `sha256` lowercase, `size` exact, both computed from the served bytes.
3. File URL HTTPS, last segment = real file name with extension.
4. `id`/`version` equal to the plugin's `__id__`/`__version__`.
5. `ETag` header + `304` on `If-None-Match`.
6. Never reuse a file URL for different bytes.
7. Push `sg_plugins_catalog_url` via app config only when the URL is live (otherwise the default URL is used).

## 8. Sources (several channel repositories) — StaticGram extension

Next to index.json the built-in repository publishes sources.json (URL = index URL with the last
segment replaced: .../catalog/sources.json; missing/404/invalid -> client shows just the single
built-in repository as before).

{"schema":1,"name":"StaticPlugins","updated_at":"ISO-8601","sources":[
  {"id":"s12","title":"RnDev","channel":"RnPlugins","plugins":48,
   "index":"sources/s12.json","updated_at":"ISO-8601"}]}

* schema must be 1. sources: array, already sorted by `plugins` desc (then title); client keeps the order.
* id: string, stable ("s"+number), unique. title: string, required, display name.
* channel: public username without "@", or null. Used for the avatar (resolve the username on our network)
  and for the "open channel" action. null -> default avatar.
* plugins: int >= 1, number of plugins in that source's index (shown as the counter).
* index: URL of the source index, absolute or relative to the sources.json URL (resolved like a browser
  link, HTTPS only). The file has the same format as index.json (section 2-3): schema, name (= source title),
  optional channel, optional homepage, plugins[]. Relative plugin `file` links in it are relative to
  THAT file's URL (e.g. "../files/<sha256>/<name>.plugin").
* updated_at: ISO-8601 (latest plugin update in the source); use it to skip refetching.
* Plugin objects in index.json may carry an optional string "source": "s<id>" (the source the plugin
  belongs to); clients that do not know it ignore it. Plugins without a source have no such field.
* Each source index is a separate repository entry in "Verified" (own ETag/cache, refresh at most once
  per 10 min like the main index); trust level = built-in (same host/files as the main catalog).
  A plugin that appears in several indexes is deduplicated by id (the main index has every plugin once).
* Unknown fields are ignored everywhere, as in index.json.

Client behaviour (StaticGram Android, PluginCatalogController):

* sources.json is fetched together with every sync of the built-in index (own ETag, at most once per
  10 min). 404/410, invalid JSON, `schema` != 1 -> all source repositories are removed (single built-in
  repository); network errors / 5xx keep the current list.
* Source entries with an index on another host than sources.json, a bad `id`, no `title` or `plugins` < 1
  are skipped; plugin files of a source index must be on the same host too.
* Repository id = -(2^40) - <number of the id>; removed from sources.json -> removed on the client. User
  repositories are never touched.
* The aggregated StaticPlugins entry is hidden from "Verified" when every plugin of index.json has a
  `source` that is in sources.json (exteraGram shows only channel repositories there); otherwise it is
  shown first, above the sources.
* A source whose `updated_at` equals the one its index was last synced at is not refetched.
