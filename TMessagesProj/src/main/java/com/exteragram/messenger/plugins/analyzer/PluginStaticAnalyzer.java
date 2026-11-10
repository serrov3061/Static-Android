package com.exteragram.messenger.plugins.analyzer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * exteraGram 13.1.1 "Static analysis" for plugins, StaticGram implementation.
 * <p>
 * exteraGram ships a closed native analyzer (libextera_analyzer.so in an isolated service). This one scans
 * the plugin's Python sources: a single .plugin/.py file, every source of an .elyx archive (read through the
 * Elyx installer itself, so the analyzer sees exactly what gets installed, password protected archives
 * included) or an installed Elyx directory. It resolves hook targets, finds network, file, process,
 * payment/account-security and message APIs, keeps the matching file/line and assigns the exteraGram
 * severity levels and statement ids. Like exteraGram it keeps a signed report cache keyed by the file's
 * SHA-256 ({@link AnalyzerCache}) and, for updates, reports what the new version adds over the installed one
 * ({@link #diff}).
 */
public final class PluginStaticAnalyzer {

    public static final int LEVEL_INFO = 0, LEVEL_CAUTION = 1, LEVEL_DANGER = 2, LEVEL_CRITICAL = 3;
    /** Bumped whenever the rules change: part of the cache build id. */
    public static final int ANALYZER_VERSION = 2;
    /** exteraGram PluginAnalyzer.MAX_FILE_BYTES. */
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_SOURCES_TOTAL = 32L * 1024 * 1024;
    private static final int MAX_FILES = 2000;
    private static final int MAX_ENTRIES = 20000;
    private static final int MAX_LINES_PER_STATEMENT = 8;

    /** Reads an Elyx archive through the installer (set by the Elyx engine): read_sources JSON. */
    public interface ArchiveReader {
        JSONObject read(String path) throws Exception;
    }

    public static volatile ArchiveReader archiveReader;

    public static final class Evidence {
        public final String file;
        public final int line;
        public final String code;

        Evidence(String file, int line, String code) {
            this.file = file;
            this.line = line;
            this.code = code;
        }
    }

    public static final class Statement {
        public final String id;
        public final int level;
        public final ArrayList<Evidence> evidence = new ArrayList<>();
        public final LinkedHashSet<String> values = new LinkedHashSet<>();
        public int moreEvidence;
        public boolean unresolvedTargets;
        /** Update diff: the statement is new in this version / values that are new in this version. */
        public boolean isNew;
        public final LinkedHashSet<String> newValues = new LinkedHashSet<>();

        Statement(String id, int level) {
            this.id = id;
            this.level = level;
        }

        public int nameRes() {
            return PluginStaticAnalyzer.nameRes(id);
        }

        public int descriptionRes() {
            return PluginStaticAnalyzer.descriptionRes(id);
        }

        public int iconRes() {
            return PluginStaticAnalyzer.iconRes(id);
        }

        public boolean hasNews() {
            return isNew || !newValues.isEmpty();
        }
    }

    public static final class Report {
        public final ArrayList<Statement> statements = new ArrayList<>();
        public String error;
        /** SHA-256 of the analyzed file (or the tree hash of an installed directory). */
        public String sha256;
        /** True when this report carries an update diff against the installed version. */
        public boolean hasDiff;
        /** Sources that were skipped (limits): the report may be incomplete. */
        public boolean truncated;

        public int maxLevel() {
            int max = -1;
            for (Statement s : statements) max = Math.max(max, s.level);
            return max;
        }

        public List<Statement> ofLevel(int level) {
            final ArrayList<Statement> out = new ArrayList<>();
            for (Statement s : statements) if (s.level == level) out.add(s);
            return out;
        }

        public List<Statement> news() {
            final ArrayList<Statement> out = new ArrayList<>();
            for (Statement s : statements) if (s.hasNews()) out.add(s);
            return out;
        }

        public int newCount() {
            int n = 0;
            for (Statement s : statements) if (s.hasNews()) n++;
            return n;
        }

        /** Highest level among what an update adds (-1 when it adds nothing). */
        public int maxNewLevel() {
            int max = -1;
            for (Statement s : statements) if (s.hasNews()) max = Math.max(max, s.level);
            return max;
        }

        public String toJson() {
            try {
                final JSONObject o = new JSONObject();
                o.put("v", ANALYZER_VERSION);
                if (error != null) o.put("error", error);
                if (truncated) o.put("truncated", true);
                final JSONArray arr = new JSONArray();
                for (Statement s : statements) {
                    final JSONObject so = new JSONObject();
                    so.put("id", s.id);
                    so.put("level", s.level);
                    if (s.moreEvidence > 0) so.put("more", s.moreEvidence);
                    if (s.unresolvedTargets) so.put("unresolved", true);
                    final JSONArray values = new JSONArray();
                    for (String v : s.values) values.put(v);
                    so.put("values", values);
                    final JSONArray ev = new JSONArray();
                    for (Evidence e : s.evidence) {
                        final JSONObject eo = new JSONObject();
                        if (e.file != null) eo.put("f", e.file);
                        eo.put("l", e.line);
                        eo.put("c", e.code);
                        ev.put(eo);
                    }
                    so.put("evidence", ev);
                    arr.put(so);
                }
                o.put("statements", arr);
                return o.toString();
            } catch (Exception e) {
                return null;
            }
        }

        public static Report fromJson(String json) {
            try {
                final JSONObject o = new JSONObject(json);
                if (o.optInt("v") != ANALYZER_VERSION) return null;
                final Report r = new Report();
                r.error = o.has("error") ? o.optString("error") : null;
                r.truncated = o.optBoolean("truncated");
                final JSONArray arr = o.optJSONArray("statements");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        final JSONObject so = arr.getJSONObject(i);
                        final Statement s = new Statement(so.getString("id"), so.getInt("level"));
                        s.moreEvidence = so.optInt("more");
                        s.unresolvedTargets = so.optBoolean("unresolved");
                        final JSONArray values = so.optJSONArray("values");
                        if (values != null) for (int j = 0; j < values.length(); j++) s.values.add(values.getString(j));
                        final JSONArray ev = so.optJSONArray("evidence");
                        if (ev != null) {
                            for (int j = 0; j < ev.length(); j++) {
                                final JSONObject eo = ev.getJSONObject(j);
                                s.evidence.add(new Evidence(eo.has("f") ? eo.optString("f") : null, eo.optInt("l"), eo.optString("c")));
                            }
                        }
                        r.statements.add(s);
                    }
                }
                return r;
            } catch (Exception e) {
                FileLog.e(e);
                return null;
            }
        }
    }

    private static final class Rule {
        final String id;
        final int level;
        final Pattern pattern;
        final int valueGroup;

        Rule(String id, int level, String regex, int valueGroup) {
            this.id = id;
            this.level = level;
            this.pattern = Pattern.compile(regex);
            this.valueGroup = valueGroup;
        }
    }

    private static final String SENSITIVE_TL = "sendStarsForm|sendPaymentForm|transferStarGift|withdrawStarGift|getStarGiftWithdrawalUrl|convertStarGift|upgradeStarGift|craftStarGift|deleteStarGift|sendPaidReaction|fulfillStarsSubscription|changeStarsSubscription|getStarsRevenueWithdrawalUrl|deleteAccount|resetAuthorizations?|resetWebAuthorizations?|acceptLoginToken|exportAuthorization|changePhone|updatePasswordSettings|resetPassword|editCreator|deleteChannel|setAuthorizationTTL|updateConnectedBot|sendTransfer|exportSecretPhrase";

    private static final Rule[] RULES = {
            new Rule("remote_control", LEVEL_CRITICAL, "(?i)\\b(websocket|socketio|ws://|wss://).{0,80}\\b(exec|eval|send_request|send_message)\\b", -1),
            new Rule("session_data", LEVEL_CRITICAL, "(?i)\\b(auth_?key|tgnet\\.dat|getAuthKey|native_getAuthKey|userconfing\\.xml|shared_prefs)\\b", 1),
            new Rule("account_takeover", LEVEL_CRITICAL, "\\b(acceptLoginToken|exportLoginToken|TL_auth_acceptLoginToken|exportAuthorization)\\b", 1),
            new Rule("wallet_access", LEVEL_CRITICAL, "\\b(exportSecretPhrase|GramWallet|WalletEngine|secret_?phrase|mnemonic)\\b", 1),
            new Rule("sensitive_requests", LEVEL_DANGER, "\\b(?:TL_)?(?:[a-z]+_)?(" + SENSITIVE_TL + ")\\b", 1),
            new Rule("dynamic_code", LEVEL_DANGER, "(?<![\\w.])(exec|eval|compile|__import__)\\s*\\(|\\bimportlib\\.(import_module|reload)\\b", 0),
            new Rule("native_code", LEVEL_DANGER, "\\b(ctypes|cffi|System\\.load(?:Library)?|loadLibrary|dlopen)\\b", 1),
            new Rule("processes", LEVEL_DANGER, "\\b(subprocess\\.\\w+|os\\.system|os\\.popen|Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder)\\b", 1),
            new Rule("hidden_parts", LEVEL_DANGER, "\\b(base64\\.b(?:64|32|85)decode|zlib\\.decompress|marshal\\.loads|codecs\\.decode)\\b", 1),
            new Rule("java_hooks", LEVEL_CAUTION, "\\b(hook_method|hook_all_methods|hook_all_constructors|hookMethod|XposedBridge\\.\\w+)\\s*\\(", 1),
            new Rule("intercepts_requests", LEVEL_CAUTION, "\\b(pre_request_hook|post_request_hook|on_send_message_hook|on_updates_hook|on_update_hook|add_hook)\\b", 1),
            new Rule("network", LEVEL_CAUTION, "\\b(requests\\.(?:get|post|put|delete|request|Session)|urllib\\.request|urlopen|http\\.client|socket\\.socket|aiohttp|httpx|OkHttpClient|HttpURLConnection)\\b", 1),
            new Rule("messages_delete", LEVEL_CAUTION, "\\b(deleteMessages|delete_messages|TL_messages_deleteMessages|deleteHistory)\\b", 1),
            new Rule("messages_send", LEVEL_CAUTION, "\\b(send_message|sendMessage|SendMessagesHelper|TL_messages_sendMessage)\\b", 1),
            new Rule("forward_messages", LEVEL_CAUTION, "\\b(forwardMessages|forward_messages|TL_messages_forwardMessages)\\b", 1),
            new Rule("messages_read", LEVEL_CAUTION, "\\b(getMessagesStorage|MessagesStorage|getHistory|TL_messages_getHistory|messageOwner)\\b", 1),
            new Rule("contacts", LEVEL_CAUTION, "\\b(ContactsController|contacts_getContacts|getContacts)\\b", 1),
            new Rule("app_exit", LEVEL_CAUTION, "\\b(os\\._exit|sys\\.exit|System\\.exit|killProcess)\\b", 1),
            new Rule("files", LEVEL_INFO, "(?<![\\w.])open\\s*\\(|\\b(os\\.remove|os\\.unlink|shutil\\.\\w+|os\\.listdir|os\\.walk|java\\.io\\.File|FileOutputStream)\\b", 1),
            new Rule("telegram_api", LEVEL_INFO, "\\b(send_request|sendRequest|ConnectionsManager)\\b", 1),
            new Rule("notifications", LEVEL_INFO, "\\b(NotificationManager|NotificationCompat|NotificationsController)\\b", 1),
            new Rule("intents", LEVEL_INFO, "\\b(startActivity|Intent\\s*\\(|sendBroadcast)\\b", 1),
            new Rule("webview_bridge", LEVEL_DANGER, "\\baddJavascriptInterface\\b", 0),
            new Rule("third_party_code", LEVEL_CAUTION, "^\\s*__requirements__\\s*=", 0),
            new Rule("app_settings", LEVEL_INFO, "\\b(SharedConfig|ExteraConfig|getSharedPreferences)\\b", 1),
    };

    private static final Pattern URL = Pattern.compile("https?://([A-Za-z0-9.-]+)");
    private static final Pattern FIND_CLASS = Pattern.compile("(?:find_class|jclass|findClass|forName)\\s*\\(\\s*[\"']([\\w.$]+)[\"']");
    private static final Pattern METHOD_NAME = Pattern.compile("(?:getDeclaredMethod|getMethod|get_method|find_method|hook_all_methods|findMethodExact)\\s*\\(\\s*(?:[\\w.]+\\s*,\\s*)?[\"'](\\w+)[\"']");

    private PluginStaticAnalyzer() {
    }

    private static final class Source {
        final String path;
        final String text;

        Source(String path, String text) {
            this.path = path;
            this.text = text;
        }
    }

    /** Everything the analyzer looks at: sources plus binary members and declared dependencies. */
    private static final class Input {
        final ArrayList<Source> sources = new ArrayList<>();
        final ArrayList<String> binaries = new ArrayList<>();
        final ArrayList<String> requirements = new ArrayList<>();
        boolean truncated;
    }

    // ---- entry points

    /** Analysis of a plugin file / archive / installed Elyx directory, no cache, no diff. */
    public static Report analyze(File file) {
        final Report report = new Report();
        try {
            if (file == null || !file.exists()) {
                report.error = "missing";
                return report;
            }
            if (file.isFile() && file.length() > MAX_FILE_BYTES) {
                report.error = "too_large";
                return report;
            }
            final Input input = new Input();
            final String error = collect(file, input);
            if (error != null) {
                report.error = error;
                return report;
            }
            run(input, report);
        } catch (Throwable e) {
            FileLog.e(e);
            report.error = "error";
        }
        return report;
    }

    /**
     * exteraGram PluginAnalyzer.run: the report of {@code file}; with {@code previous} (the installed
     * version of the plugin) the report also marks what the new version adds. The cache is used only
     * without a previous version, like in exteraGram.
     */
    public static Report analyze(File file, File previous, boolean useCache) {
        String sha = null;
        try {
            if (file != null && file.isFile() && file.length() <= MAX_FILE_BYTES) {
                sha = AnalyzerCache.sha256Hex(file);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (useCache && previous == null && sha != null) {
            final Report cached = AnalyzerCache.getInstance().getReport(sha);
            if (cached != null) {
                cached.sha256 = sha;
                return cached;
            }
        }
        final Report report = analyze(file);
        report.sha256 = sha;
        if (report.error == null && sha != null) {
            AnalyzerCache.getInstance().putReport(sha, report);
        }
        if (previous != null && previous.exists() && report.error == null) {
            final Report old = analyze(previous);
            if (old.error == null) {
                diff(report, old);
            }
        }
        return report;
    }

    /** exteraGram AnalyzerDiff: statements the old version did not have and values it did not use. */
    public static void diff(Report report, Report old) {
        final HashMap<String, Statement> before = new HashMap<>();
        for (Statement s : old.statements) before.put(s.id, s);
        for (Statement s : report.statements) {
            final Statement o = before.get(s.id);
            s.newValues.clear();
            if (o == null) {
                s.isNew = true;
            } else {
                s.isNew = false;
                for (String v : s.values) if (!o.values.contains(v)) s.newValues.add(v);
            }
        }
        report.hasDiff = true;
    }

    // ---- input collection

    private static boolean isSourceName(String name) {
        final String low = name.toLowerCase(Locale.ROOT);
        return low.endsWith(".py") || low.endsWith(".pyw") || low.endsWith(".pyi");
    }

    private static boolean isBinaryName(String name) {
        final String low = name.toLowerCase(Locale.ROOT);
        return low.endsWith(".so") || low.endsWith(".dex") || low.endsWith(".jar") || low.endsWith(".apk")
                || low.endsWith(".pyc") || low.endsWith(".pyo") || low.endsWith(".whl") || low.endsWith(".zip")
                || low.endsWith(".elyx") || low.endsWith(".bin") || low.endsWith(".dll") || low.endsWith(".dylib");
    }

    private static boolean isZip(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    /** null on success, otherwise the report error. */
    private static String collect(File file, Input input) throws IOException {
        if (file.isDirectory()) {
            return collectDirectory(file, input);
        }
        if (isZip(file)) {
            return collectArchive(file, input);
        }
        if (file.length() > MAX_SOURCE_BYTES) {
            return "too_large";
        }
        input.sources.add(new Source(null, new String(readLimited(new FileInputStream(file), MAX_SOURCE_BYTES), StandardCharsets.UTF_8)));
        return null;
    }

    /** An installed Elyx plugin: every source under its directory (no symlinks, no __pycache__). */
    private static String collectDirectory(File root, Input input) throws IOException {
        final String rootPath = root.getCanonicalPath() + File.separator;
        final ArrayList<File> stack = new ArrayList<>();
        stack.add(root);
        long total = 0;
        int seen = 0;
        while (!stack.isEmpty()) {
            final File dir = stack.remove(stack.size() - 1);
            final File[] children = dir.listFiles();
            if (children == null) continue;
            java.util.Arrays.sort(children);
            for (File child : children) {
                if (++seen > MAX_ENTRIES) {
                    input.truncated = true;
                    return null;
                }
                final String canonical = child.getCanonicalPath();
                if (!canonical.startsWith(rootPath)) continue; // symlink out of the plugin directory
                final String rel = canonical.substring(rootPath.length()).replace(File.separatorChar, '/');
                if (child.isDirectory()) {
                    if (!"__pycache__".equals(child.getName())) stack.add(child);
                    continue;
                }
                if (isSourceName(rel)) {
                    if (input.sources.size() >= MAX_FILES || child.length() > MAX_SOURCE_BYTES || total + child.length() > MAX_SOURCES_TOTAL) {
                        input.truncated = true;
                        continue;
                    }
                    total += child.length();
                    input.sources.add(new Source(rel, new String(readLimited(new FileInputStream(child), MAX_SOURCE_BYTES), StandardCharsets.UTF_8)));
                } else if (isBinaryName(rel)) {
                    input.binaries.add(rel);
                }
            }
        }
        return null;
    }

    /** "a/b.py" or null when the member would leave the archive root (zip-slip). */
    static String safeMemberPath(String name) {
        if (name == null || name.indexOf('\0') >= 0) return null;
        name = name.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) return null;
        final StringBuilder out = new StringBuilder();
        for (String part : name.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) return null;
            if (out.length() > 0) out.append('/');
            out.append(part);
        }
        return out.toString();
    }

    private static String collectArchive(File file, Input input) throws IOException {
        final ArchiveReader reader = archiveReader;
        if (reader != null) {
            try {
                final JSONObject result = reader.read(file.getAbsolutePath());
                if (result != null) {
                    if (!result.optBoolean("ok")) {
                        final String kind = result.optString("error_kind", "");
                        return "password_required".equals(kind) || "wrong_password".equals(kind) ? "archive_password" : "archive";
                    }
                    final JSONArray sources = result.optJSONArray("sources");
                    if (sources != null) {
                        for (int i = 0; i < sources.length(); i++) {
                            final JSONObject s = sources.getJSONObject(i);
                            input.sources.add(new Source(s.optString("path"), s.optString("text")));
                        }
                    }
                    final JSONArray binaries = result.optJSONArray("binaries");
                    if (binaries != null) for (int i = 0; i < binaries.length(); i++) input.binaries.add(binaries.getString(i));
                    final JSONArray requirements = result.optJSONArray("requirements");
                    if (requirements != null) for (int i = 0; i < requirements.length(); i++) input.requirements.add(requirements.getString(i));
                    final JSONArray wheels = result.optJSONArray("wheels");
                    if (wheels != null) for (int i = 0; i < wheels.length(); i++) input.requirements.add(wheels.getString(i));
                    input.truncated = result.optBoolean("truncated");
                    return null;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        // Elyx engine is not running: read the central directory (what Python's zipfile uses) ourselves.
        try (ZipFile zip = new ZipFile(file)) {
            long total = 0;
            int count = 0;
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) return "archive";
                final String rel = safeMemberPath(entry.getName());
                if (rel == null) return "archive_unsafe";
                if (entry.isDirectory() || rel.isEmpty()) continue;
                if (isSourceName(rel)) {
                    if (input.sources.size() >= MAX_FILES || total >= MAX_SOURCES_TOTAL) {
                        input.truncated = true;
                        continue;
                    }
                    final byte[] data = readLimited(zip.getInputStream(entry), MAX_SOURCE_BYTES + 1);
                    if (data.length > MAX_SOURCE_BYTES) {
                        input.truncated = true;
                        continue;
                    }
                    total += data.length;
                    input.sources.add(new Source(rel, new String(data, StandardCharsets.UTF_8)));
                } else if (isBinaryName(rel)) {
                    input.binaries.add(rel);
                }
            }
        } catch (Exception e) {
            // encrypted (password) archives cannot be read without the Elyx engine
            FileLog.e(e);
            return "archive";
        }
        return null;
    }

    /** Reads at most {@code limit} bytes (counts real bytes, so a zip bomb cannot blow up memory). */
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        try (InputStream stream = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while (out.size() < limit && (n = stream.read(buf, 0, Math.min(buf.length, limit - out.size()))) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ---- analysis

    private static final class Context {
        final Map<String, Statement> found = new LinkedHashMap<>();
        boolean hasNetwork, hasDynamic;
        int hookCalls, resolvedHooks;

        Statement statement(String id, int level) {
            Statement s = found.get(id);
            if (s == null) {
                s = new Statement(id, level);
                found.put(id, s);
            }
            return s;
        }

        void evidence(Statement s, String file, int line, String code) {
            if (s.evidence.size() < MAX_LINES_PER_STATEMENT) {
                s.evidence.add(new Evidence(file, line, code));
            } else {
                s.moreEvidence++;
            }
        }
    }

    private static void run(Input input, Report report) {
        final Context ctx = new Context();
        for (Source source : input.sources) {
            analyzeSource(source.path, source.text, ctx);
        }
        for (String binary : input.binaries) {
            final String low = binary.toLowerCase(Locale.ROOT);
            final String id;
            final int level;
            if (low.endsWith(".pyc") || low.endsWith(".pyo")) {
                id = "hidden_parts"; level = LEVEL_DANGER;
            } else if (low.endsWith(".whl")) {
                id = "third_party_code"; level = LEVEL_CAUTION;
            } else {
                id = "native_code"; level = LEVEL_DANGER;
            }
            final Statement s = ctx.statement(id, level);
            s.values.add(binary);
            ctx.evidence(s, binary, 0, "");
            if ("hidden_parts".equals(id)) ctx.hasDynamic = true;
        }
        if (!input.requirements.isEmpty()) {
            final Statement s = ctx.statement("third_party_code", LEVEL_CAUTION);
            s.values.addAll(input.requirements);
        }
        final Statement hooks = ctx.found.get("java_hooks");
        if (hooks != null) {
            hooks.unresolvedTargets = ctx.resolvedHooks < ctx.hookCalls;
            boolean ui = false;
            for (String v : hooks.values) if (v.contains("Activity") || v.contains("Cell") || v.contains("View")) ui = true;
            if (ui && !ctx.found.containsKey("ui_hooks")) {
                final Statement s = new Statement("ui_hooks", LEVEL_INFO);
                s.evidence.addAll(hooks.evidence);
                ctx.found.put("ui_hooks", s);
            }
        }
        if (ctx.hasNetwork && ctx.hasDynamic) {
            final Statement s = new Statement("remote_code", LEVEL_CRITICAL);
            final Statement d = ctx.found.containsKey("dynamic_code") ? ctx.found.get("dynamic_code") : ctx.found.get("hidden_parts");
            if (d != null) s.evidence.addAll(d.evidence);
            ctx.found.put("remote_code", s);
        }
        report.truncated = input.truncated;
        report.statements.addAll(ctx.found.values());
        report.statements.sort((a, b) -> b.level - a.level);
    }

    /** Kept for callers that analyze plain text. */
    static void analyze(String source, Report report) {
        final Input input = new Input();
        input.sources.add(new Source(null, source));
        run(input, report);
    }

    private static void analyzeSource(String file, String source, Context ctx) {
        final String[] lines = source.split("\n", -1);
        final ArrayList<String> classes = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            final String code = stripComment(line);
            if (code.trim().isEmpty()) continue;
            final Matcher fc = FIND_CLASS.matcher(code);
            while (fc.find()) classes.add(fc.group(1));
            for (Rule rule : RULES) {
                final Matcher m = rule.pattern.matcher(code);
                if (!m.find()) continue;
                final Statement s = ctx.statement(rule.id, rule.level);
                ctx.evidence(s, file, i + 1, line.trim());
                if ("network".equals(rule.id)) {
                    ctx.hasNetwork = true;
                } else if ("dynamic_code".equals(rule.id) || "hidden_parts".equals(rule.id)) {
                    ctx.hasDynamic = true;
                } else if ("sensitive_requests".equals(rule.id) && rule.valueGroup > 0) {
                    s.values.add(m.group(rule.valueGroup));
                } else if ("java_hooks".equals(rule.id)) {
                    ctx.hookCalls++;
                    final String target = resolveHookTarget(lines, i, classes);
                    if (target != null) {
                        s.values.add(target);
                        ctx.resolvedHooks++;
                    }
                }
            }
            final Matcher url = URL.matcher(code);
            while (url.find()) {
                Statement s = ctx.found.get("network");
                if (s == null) {
                    s = ctx.statement("network", LEVEL_CAUTION);
                    s.evidence.add(new Evidence(file, i + 1, line.trim()));
                }
                s.values.add(url.group(1).toLowerCase(Locale.ROOT));
                ctx.hasNetwork = true;
            }
        }
    }

    private static String stripComment(String line) {
        boolean single = false, dbl = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == '#' && !single && !dbl) return line.substring(0, i);
        }
        return line;
    }

    /** "MessagesController.deleteMessages" from find_class(...) and the method name near a hook call. */
    private static String resolveHookTarget(String[] lines, int index, ArrayList<String> classes) {
        String method = null;
        for (int j = index; j >= Math.max(0, index - 12) && method == null; j--) {
            final Matcher m = METHOD_NAME.matcher(lines[j]);
            if (m.find()) method = m.group(1);
        }
        String cls = null;
        for (int j = index; j >= Math.max(0, index - 20) && cls == null; j--) {
            final Matcher m = FIND_CLASS.matcher(lines[j]);
            if (m.find()) cls = m.group(1);
        }
        if (method == null) return null;
        if (cls == null && !classes.isEmpty()) cls = classes.get(classes.size() - 1);
        if (cls == null) return null;
        final int dot = cls.lastIndexOf('.');
        return (dot >= 0 ? cls.substring(dot + 1) : cls) + "." + method;
    }

    // ---- exteraGram 13.1.1 catalog: statement id -> name / description / icon

    static int nameRes(String id) {
        switch (id) {
            case "remote_control": return R.string.PluginAnalysisNameRemoteControl;
            case "session_data": return R.string.PluginAnalysisNameSessionData;
            case "account_takeover": return R.string.PluginAnalysisNameAccountTakeover;
            case "wallet_access": return R.string.PluginAnalysisNameWalletAccess;
            case "sensitive_requests": return R.string.PluginAnalysisNameSensitiveRequests;
            case "dynamic_code": return R.string.PluginAnalysisNameDynamicCode;
            case "native_code": return R.string.PluginAnalysisNameNativeCode;
            case "processes": return R.string.PluginAnalysisNameProcesses;
            case "hidden_parts": return R.string.PluginAnalysisNameHiddenParts;
            case "java_hooks": return R.string.PluginAnalysisNameJavaHooks;
            case "ui_hooks": return R.string.PluginAnalysisNameUiHooks;
            case "intercepts_requests": return R.string.PluginAnalysisNameInterceptsRequests;
            case "network": return R.string.PluginAnalysisNameNetwork;
            case "messages_delete": return R.string.PluginAnalysisNameMessagesDelete;
            case "messages_send": return R.string.PluginAnalysisNameMessagesSend;
            case "forward_messages": return R.string.PluginAnalysisNameForwardMessages;
            case "messages_read": return R.string.PluginAnalysisNameMessagesRead;
            case "contacts": return R.string.PluginAnalysisNameContacts;
            case "app_exit": return R.string.PluginAnalysisNameAppExit;
            case "files": return R.string.PluginAnalysisNameFiles;
            case "telegram_api": return R.string.PluginAnalysisNameTelegramApi;
            case "notifications": return R.string.Notifications;
            case "intents": return R.string.PluginAnalysisNameIntents;
            case "webview_bridge": return R.string.PluginAnalysisNameWebviewBridge;
            case "app_settings": return R.string.PluginAnalysisNameAppSettings;
            case "remote_code": return R.string.PluginAnalysisNameRemoteCode;
            case "third_party_code": return R.string.PluginAnalysisNameThirdPartyCode;
        }
        return R.string.PluginAnalysisCategoryOther;
    }

    static int descriptionRes(String id) {
        switch (id) {
            case "remote_control": return R.string.PluginAnalysisStatementRemoteControl;
            case "session_data": return R.string.PluginAnalysisStatementSessionData;
            case "account_takeover": return R.string.PluginAnalysisStatementAccountTakeover;
            case "wallet_access": return R.string.PluginAnalysisStatementWalletAccess;
            case "sensitive_requests": return R.string.PluginAnalysisStatementSensitiveRequests;
            case "dynamic_code": return R.string.PluginAnalysisStatementDynamicCode;
            case "native_code": return R.string.PluginAnalysisStatementNativeCode;
            case "processes": return R.string.PluginAnalysisStatementProcesses;
            case "hidden_parts": return R.string.PluginAnalysisStatementHiddenParts;
            case "java_hooks": return R.string.PluginAnalysisStatementJavaHooks;
            case "ui_hooks": return R.string.PluginAnalysisStatementUiHooks;
            case "intercepts_requests": return R.string.PluginAnalysisStatementInterceptsRequests;
            case "network": return R.string.PluginAnalysisStatementNetwork;
            case "messages_delete": return R.string.PluginAnalysisStatementMessagesDelete;
            case "messages_send": return R.string.PluginAnalysisStatementMessagesSend;
            case "forward_messages": return R.string.PluginAnalysisStatementForwardMessages;
            case "messages_read": return R.string.PluginAnalysisStatementMessagesRead;
            case "contacts": return R.string.PluginAnalysisStatementContacts;
            case "app_exit": return R.string.PluginAnalysisStatementAppExit;
            case "files": return R.string.PluginAnalysisStatementFiles;
            case "telegram_api": return R.string.PluginAnalysisStatementTelegramApi;
            case "notifications": return R.string.PluginAnalysisStatementNotifications;
            case "intents": return R.string.PluginAnalysisStatementIntents;
            case "webview_bridge": return R.string.PluginAnalysisStatementWebviewBridge;
            case "app_settings": return R.string.PluginAnalysisStatementAppSettings;
            case "remote_code": return R.string.PluginAnalysisStatementRemoteCode;
            case "third_party_code": return R.string.PluginAnalysisStatementThirdPartyCode;
        }
        return 0;
    }

    static int iconRes(String id) {
        switch (id) {
            case "remote_code": return R.drawable.plugin_analysis_remote_code;
            case "remote_control": return R.drawable.plugin_analysis_remote_control;
            case "account_takeover": return R.drawable.plugin_analysis_account_takeover;
            case "wallet_access": return R.drawable.plugin_analysis_wallet_access;
            case "session_data": case "sensitive_requests": case "intercepts_requests": case "telegram_api": return R.drawable.plugin_analysis_account;
            case "dynamic_code": case "native_code": case "third_party_code": return R.drawable.plugin_analysis_code;
            case "hidden_parts": return R.drawable.plugin_analysis_stealth;
            case "network": case "webview_bridge": return R.drawable.plugin_analysis_network;
            case "files": return R.drawable.plugin_analysis_files;
            case "processes": case "intents": return R.drawable.plugin_analysis_device;
            case "java_hooks": case "ui_hooks": case "app_exit": case "app_settings": return R.drawable.plugin_analysis_app;
            case "messages_delete": case "messages_send": case "forward_messages": case "messages_read": case "contacts": case "notifications": return R.drawable.plugin_analysis_messages;
        }
        return R.drawable.plugin_analysis_info;
    }
}
