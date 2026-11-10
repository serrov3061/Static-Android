package com.exteragram.messenger.utils;

import android.text.SpannableString;
import android.text.TextUtils;
import com.exteragram.messenger.components.PreformattedScrollView;
import com.exteragram.messenger.plugins.PluginsConstants;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.telegram.utils.code.highlight.CodeHighlighting;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.tl.TL_iv;

// exteraGram: detects text/code files that Instant View should show as highlighted preformatted blocks.
public abstract class MarkdownUtils {
    private static final String EMPTY = "";
    private static final String[] MARKDOWN_TEXT_EXTENSIONS = {"txt", "text"};
    private static final String[] MARKDOWN_MIME_PREFIXES = {"text/plain", "text/x-diff", "text/x-patch", "text/csv", "text/xml", "text/yaml", "text/x-yaml", "text/css", "text/javascript", "application/json", "application/ld+json", "application/json5", "application/xml", "application/yaml", "application/x-yaml", "application/javascript", "application/x-javascript", "application/x-sh"};
    private static final HashMap<String, String> PREFORMATTED_EXTENSION_LANGUAGES = new HashMap<>();
    private static final HashMap<String, String> PREFORMATTED_FILENAMES = new HashMap<>();

    static {
        addLanguage("plain", "log");
        addLanguage("diff", "diff", "patch");
        addLanguage("json", "json", "webmanifest");
        addLanguage("json5", "json5");
        addLanguage("xml", "xml", "rss", "atom");
        addLanguage("svg", "svg");
        addLanguage("html", "html", "htm", "xhtml");
        addLanguage("css", "css");
        addLanguage("scss", "scss");
        addLanguage("sass", "sass");
        addLanguage("less", "less");
        addLanguage("javascript", "js", "mjs", "cjs");
        addLanguage("jsx", "jsx");
        addLanguage("typescript", "ts");
        addLanguage("tsx", "tsx");
        addLanguage("java", "java");
        addLanguage("kotlin", "kt", "kts");
        addLanguage("gradle", "gradle");
        addLanguage("groovy", "groovy");
        addLanguage(PluginsConstants.PYTHON, "py", "pyw", "plugin");
        addLanguage("bash", "sh", "bash", "zsh", "fish", "shell");
        addLanguage("powershell", "ps1", "psm1", "psd1");
        addLanguage("batch", "bat", "cmd");
        addLanguage("sql", "sql");
        addLanguage("yaml", "yaml", "yml");
        addLanguage("ini", "ini", "toml", "properties", "props", "conf", "cfg", "config", "env", "dotenv");
        addLanguage("csv", "csv", "tsv");
        addLanguage("docker", "dockerfile");
        addLanguage("makefile", "make", "mk", "mak");
        addLanguage("cmake", "cmake");
        addLanguage("go", "go");
        addLanguage("rust", "rs");
        addLanguage("swift", "swift");
        addLanguage("dart", "dart");
        addLanguage("php", "php", "phtml");
        addLanguage("ruby", "rb", "gemspec");
        addLanguage("c", "c");
        addLanguage("cpp", "h", "hh", "hpp", "hxx", "cpp", "cc", "cxx");
        addLanguage("csharp", "cs");
        addLanguage("fsharp", "fs", "fsx");
        addLanguage("visual-basic", "vb", "vba");
        addLanguage("lua", "lua");
        addLanguage("perl", "pl", "pm");
        addLanguage("r", "r");
        addLanguage("scala", "scala");
        addLanguage("haskell", "hs");
        addLanguage("elixir", "ex", "exs");
        addLanguage("erlang", "erl", "hrl");
        addLanguage("protobuf", "proto", "protobuf");
        addLanguage("graphql", "graphql", "gql");
        addLanguage("glsl", "glsl", "vert", "frag", "geom", "comp");
        addLanguage("http", "http");
        addFilename("docker", "Dockerfile");
        addFilename("makefile", "Makefile", "GNUmakefile");
        addFilename("cmake", "CMakeLists.txt");
        addFilename("git", ".gitignore", ".gitattributes", ".gitmodules");
        addFilename("docker", ".dockerignore");
        addFilename("ini", ".editorconfig", ".env");
    }

    public static boolean isTheme(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        String documentFileName = FileLoader.getDocumentFileName(messageObject.getDocument());
        return !TextUtils.isEmpty(documentFileName) && documentFileName.toLowerCase(Locale.ROOT).endsWith("attheme");
    }

    public static boolean isExteraMarkdown(MessageObject messageObject) {
        if (messageObject == null || isTheme(messageObject)) {
            return false;
        }
        return isExteraMarkdownExtension(messageObject.getExtension()) || isExteraMarkdownMime(messageObject.getMimeType()) || !TextUtils.isEmpty(getPreformattedLanguage(FileLoader.getDocumentFileName(messageObject.getDocument()), messageObject.getExtension(), messageObject.getMimeType()));
    }

    public static boolean isExteraMarkdownExtension(String ext) {
        if (isMarkdownTextExtension(ext)) {
            return true;
        }
        return !TextUtils.isEmpty(getPreformattedLanguage(null, ext, null));
    }

    public static boolean isExteraMarkdownMime(String mime) {
        if (TextUtils.isEmpty(mime)) {
            return false;
        }
        String lower = mime.toLowerCase(Locale.ROOT);
        for (String prefix : MARKDOWN_MIME_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static String getPreformattedLanguage(String fileName, String ext, String mime) {
        String byFileName = getPreformattedLanguageByFileName(fileName);
        if (!TextUtils.isEmpty(byFileName)) {
            return byFileName;
        }
        String normalizedExt = normalizeExtension(ext);
        if (TextUtils.isEmpty(normalizedExt)) {
            normalizedExt = getExtensionFromFileName(fileName);
        }
        String language = PREFORMATTED_EXTENSION_LANGUAGES.get(normalizedExt);
        if (!TextUtils.isEmpty(language)) {
            return language;
        }
        if (TextUtils.isEmpty(mime)) {
            return EMPTY;
        }
        String lower = mime.toLowerCase(Locale.ROOT);
        if (lower.startsWith("text/x-diff") || lower.startsWith("text/x-patch")) {
            return "diff";
        }
        if (lower.startsWith("text/csv")) {
            return "csv";
        }
        if (lower.startsWith("text/xml") || lower.startsWith("application/xml")) {
            return "xml";
        }
        if (lower.startsWith("application/json5")) {
            return "json5";
        }
        if (lower.startsWith("application/json") || lower.startsWith("application/ld+json")) {
            return "json";
        }
        if (lower.startsWith("text/yaml") || lower.startsWith("text/x-yaml") || lower.startsWith("application/yaml") || lower.startsWith("application/x-yaml")) {
            return "yaml";
        }
        if (lower.startsWith("text/css")) {
            return "css";
        }
        if (lower.startsWith("text/javascript") || lower.startsWith("application/javascript") || lower.startsWith("application/x-javascript")) {
            return "javascript";
        }
        if (lower.startsWith("application/x-sh")) {
            return "bash";
        }
        return EMPTY;
    }

    public static void appendPreformattedBlocks(List<TL_iv.PageBlock> list, String text, String language, int maxChunk) {
        int chunkSize = Math.max(1, maxChunk);
        if (TextUtils.isEmpty(text)) {
            TL_iv.pageBlockPreformatted empty = new TL_iv.pageBlockPreformatted();
            empty.text = plain(EMPTY);
            empty.language = language == null ? EMPTY : language;
            list.add(empty);
            return;
        }
        if (language == null) {
            language = EMPTY;
        }
        HighlightSource source = new HighlightSource(text, language);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end - 1);
                if (lastNewline > start) {
                    end = lastNewline + 1;
                }
            }
            list.add(new PreformattedChunk(source, start, end));
            start = end;
        }
    }

    public static final class PreformattedChunk extends TL_iv.pageBlockPreformatted {
        private final int end;
        private CharSequence highlightedText;
        public final boolean joinsNext;
        public final boolean joinsPrevious;
        private final String plainText;
        public final PreformattedScrollView.Group scrollGroup;
        private final HighlightSource source;
        private final int start;

        private PreformattedChunk(HighlightSource source, int start, int end) {
            this.source = source;
            this.start = start;
            this.end = end;
            this.plainText = source.text.substring(start, end);
            this.joinsPrevious = start > 0;
            this.joinsNext = end < source.text.length();
            this.scrollGroup = source.scrollGroup;
            this.text = MarkdownUtils.plain(plainText);
            this.language = source.language;
        }

        public CharSequence getHighlightedText() {
            if (this.highlightedText == null) {
                SpannableString highlighted = this.source.getHighlighted();
                if (highlighted instanceof CodeHighlighting.LockedSpannableString && !((CodeHighlighting.LockedSpannableString) highlighted).ready) {
                    return this.plainText;
                }
                this.highlightedText = highlighted.subSequence(this.start, this.end);
            }
            return this.highlightedText;
        }
    }

    public static final class HighlightSource {
        private SpannableString highlighted;
        final String language;
        final PreformattedScrollView.Group scrollGroup = new PreformattedScrollView.Group();
        final String text;

        public HighlightSource(String text, String language) {
            this.text = text;
            this.language = language;
        }

        public SpannableString getHighlighted() {
            if (this.highlighted == null) {
                this.highlighted = CodeHighlighting.getHighlighted(this.text, this.language);
            }
            return this.highlighted;
        }
    }

    private static void addLanguage(String language, String... extensions) {
        for (String ext : extensions) {
            PREFORMATTED_EXTENSION_LANGUAGES.put(ext, language);
        }
    }

    private static void addFilename(String language, String... names) {
        for (String name : names) {
            PREFORMATTED_FILENAMES.put(name.toLowerCase(Locale.ROOT), language);
        }
    }

    private static boolean isMarkdownTextExtension(String ext) {
        String normalized = normalizeExtension(ext);
        for (String known : MARKDOWN_TEXT_EXTENSIONS) {
            if (known.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String getPreformattedLanguageByFileName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return EMPTY;
        }
        String baseName = getBaseName(fileName).toLowerCase(Locale.ROOT);
        String language = PREFORMATTED_FILENAMES.get(baseName);
        if (!TextUtils.isEmpty(language)) {
            return language;
        }
        if (baseName.startsWith("dockerfile.")) {
            return "docker";
        }
        if (baseName.startsWith("makefile.")) {
            return "makefile";
        }
        return baseName.startsWith(".env.") ? "ini" : EMPTY;
    }

    private static String getExtensionFromFileName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return EMPTY;
        }
        String baseName = getBaseName(fileName);
        int dot = baseName.lastIndexOf('.');
        if (dot > 0 && dot < baseName.length() - 1) {
            return normalizeExtension(baseName.substring(dot + 1));
        }
        return EMPTY;
    }

    private static String getBaseName(String path) {
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return sep >= 0 ? path.substring(sep + 1) : path;
    }

    private static String normalizeExtension(String ext) {
        if (TextUtils.isEmpty(ext)) {
            return EMPTY;
        }
        String trimmed = ext.trim();
        while (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static TL_iv.RichText plain(String text) {
        TL_iv.textPlain textPlain = new TL_iv.textPlain();
        textPlain.text = text == null ? EMPTY : text;
        return textPlain;
    }
}
