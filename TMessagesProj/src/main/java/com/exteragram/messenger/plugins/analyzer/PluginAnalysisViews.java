package com.exteragram.messenger.plugins.analyzer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

import java.util.HashSet;
import java.util.List;

/** exteraGram 13.1.1 static analysis UI: the verdict card of the install sheet and the report sheet. */
public final class PluginAnalysisViews {

    private PluginAnalysisViews() {
    }

    static int levelColor(int level) {
        switch (level) {
            case PluginStaticAnalyzer.LEVEL_CRITICAL: return 0xFFE5484D;
            case PluginStaticAnalyzer.LEVEL_DANGER: return 0xFFF2762E;
            case PluginStaticAnalyzer.LEVEL_CAUTION: return 0xFFF5B800;
            default: return 0xFF34C759;
        }
    }

    static int levelTitle(int level) {
        switch (level) {
            case PluginStaticAnalyzer.LEVEL_CRITICAL: return R.string.PluginAnalysisGroupCritical;
            case PluginStaticAnalyzer.LEVEL_DANGER: return R.string.PluginAnalysisGroupDanger;
            case PluginStaticAnalyzer.LEVEL_CAUTION: return R.string.PluginAnalysisGroupCaution;
            default: return R.string.PluginAnalysisGroupInfo;
        }
    }

    static ImageView tile(Context context, int iconRes, int color, int sizeDp) {
        final ImageView tile = new ImageView(context);
        tile.setScaleType(ImageView.ScaleType.CENTER);
        tile.setImageResource(iconRes);
        tile.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(AndroidUtilities.dp(sizeDp * 0.28f));
        tile.setBackground(bg);
        return tile;
    }

    private static GradientDrawable card(Theme.ResourcesProvider rp) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, rp));
        bg.setCornerRadius(AndroidUtilities.dp(16));
        return bg;
    }

    /** The verdict card; the capability flags (one chip per category) sit inside it under the title row. */
    public static View createVerdictCard(Context context, PluginStaticAnalyzer.Report report, Theme.ResourcesProvider rp, Runnable onClick) {
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), AndroidUtilities.dp(2));

        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(card(rp));
        card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14));
        // Content height (no fixed row): with a large font the texts grow the row, the tile stays centred.
        final LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(AndroidUtilities.dp(36));
        final int level = report.error != null ? PluginStaticAnalyzer.LEVEL_CAUTION : report.maxLevel();
        final int titleRes, infoRes, icon;
        if (report.error != null) {
            titleRes = R.string.PluginAnalysisFailed; infoRes = errorRes(report.error); icon = R.drawable.plugin_analysis_question;
        } else if (level >= PluginStaticAnalyzer.LEVEL_DANGER) {
            titleRes = R.string.PluginAnalysisThreats; infoRes = levelTitle(level); icon = R.drawable.plugin_analysis_warning;
        } else if (level == PluginStaticAnalyzer.LEVEL_CAUTION) {
            titleRes = R.string.PluginAnalysisCaution; infoRes = R.string.PluginAnalysisCautionInfo; icon = R.drawable.plugin_analysis_warning;
        } else {
            titleRes = R.string.PluginAnalysisClean; infoRes = R.string.PluginAnalysisCleanInfo; icon = R.drawable.plugin_analysis_shield;
        }
        head.addView(tile(context, icon, levelColor(Math.max(level, 0)), 36), LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        title.setText(LocaleController.getString(titleRes));
        final int newCount = report.hasDiff ? report.newCount() : 0;
        if (newCount > 0) {
            // exteraGram: an update shows how many capabilities the new version adds
            final LinearLayout titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0, 6, 0));
            final TextView badge = new TextView(context);
            final int badgeColor = levelColor(Math.max(report.maxNewLevel(), PluginStaticAnalyzer.LEVEL_CAUTION));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            badge.setTypeface(AndroidUtilities.bold());
            badge.setTextColor(badgeColor);
            badge.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(1), AndroidUtilities.dp(6), AndroidUtilities.dp(1));
            final GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Theme.multAlpha(badgeColor, 0.15f));
            badgeBg.setCornerRadius(AndroidUtilities.dp(8));
            badge.setBackground(badgeBg);
            badge.setText(LocaleController.formatPluralString("PluginAnalysisNewBadge", newCount, newCount));
            titleRow.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            texts.addView(titleRow);
        } else {
            texts.addView(title);
        }
        final TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        info.setText(LocaleController.getString(infoRes));
        texts.addView(info);
        head.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 8, 0));
        final ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.msg_arrowright);
        chevron.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, rp), PorterDuff.Mode.SRC_IN));
        head.addView(chevron, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL));
        card.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final View flags = createFlagsRow(context, report, rp);
        if (flags != null) {
            card.addView(flags, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }
        card.setOnClickListener(v -> onClick.run());
        card.setForeground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), 0, Theme.getColor(Theme.key_listSelector, rp)));
        root.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return root;
    }

    /** Capability flags as a centered wrapping row: one chip per category, coloured by its worst finding; null when none. */
    public static View createFlagsRow(Context context, PluginStaticAnalyzer.Report report, Theme.ResourcesProvider rp) {
        if (report == null || report.statements.isEmpty()) return null;
        final java.util.LinkedHashMap<Integer, PluginStaticAnalyzer.Statement> worst = new java.util.LinkedHashMap<>();
        for (PluginStaticAnalyzer.Statement s : report.statements) {
            if (s.level < PluginStaticAnalyzer.LEVEL_CAUTION) continue;
            final int cat = categoryRes(s.id);
            final PluginStaticAnalyzer.Statement prev = worst.get(cat);
            if (prev == null || s.level > prev.level) worst.put(cat, s);
        }
        if (worst.isEmpty()) return null;
        final java.util.List<PluginStaticAnalyzer.Statement> list = new java.util.ArrayList<>(worst.values());
        java.util.Collections.sort(list, (x, y) -> Integer.compare(y.level, x.level)); // stable: worst first
        final FlowRow row = new FlowRow(context);
        int added = 0;
        for (PluginStaticAnalyzer.Statement s : list) {
            if (added++ >= 6) break;
            row.addView(chip(context, s, rp));
        }
        return row;
    }

    /** Minimal centered wrapping layout (ChipsLayout analogue). */
    private static final class FlowRow extends android.view.ViewGroup {
        private final int gap = AndroidUtilities.dp(8);
        private int[] rowWidths = new int[0];

        FlowRow(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            final int maxW = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
            final int n = getChildCount();
            rowWidths = new int[n + 1];
            int row = 0, x = 0, rowH = 0, total = 0;
            for (int i = 0; i < n; i++) {
                final View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                final int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
                if (x > 0 && x + gap + w > maxW) {
                    rowWidths[row++] = x;
                    total += rowH + gap;
                    x = 0;
                    rowH = 0;
                }
                x += (x > 0 ? gap : 0) + w;
                rowH = Math.max(rowH, h);
            }
            if (x > 0) {
                rowWidths[row] = x;
                total += rowH;
            }
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), total + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int row = 0, y = getPaddingTop(), rowH = 0, used = 0;
            int x = getPaddingLeft() + Math.max(0, (maxW - rowWidths[0]) / 2);
            for (int i = 0; i < getChildCount(); i++) {
                final View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                final int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
                if (used > 0 && used + gap + w > maxW) {
                    y += rowH + gap;
                    rowH = 0;
                    used = 0;
                    row++;
                    x = getPaddingLeft() + Math.max(0, (maxW - rowWidths[row]) / 2);
                }
                c.layout(x, y, x + w, y + h);
                x += w + gap;
                used += (used > 0 ? gap : 0) + w;
                rowH = Math.max(rowH, h);
            }
        }
    }

    static int errorRes(String error) {
        if ("too_large".equals(error)) return R.string.PluginAnalysisFailedTooLarge;
        return R.string.PluginAnalysisFailedError;
    }

    private static View chip(Context context, PluginStaticAnalyzer.Statement s, Theme.ResourcesProvider rp) {
        final int color = levelColor(s.level);
        final TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setTextColor(color);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(5), AndroidUtilities.dp(12), AndroidUtilities.dp(5));
        chip.setMinHeight(AndroidUtilities.dp(28));
        chip.setCompoundDrawablePadding(AndroidUtilities.dp(5));
        final android.graphics.drawable.Drawable icon = context.getResources().getDrawable(s.iconRes()).mutate();
        icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        icon.setBounds(0, 0, AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        chip.setCompoundDrawables(icon, null, null, null);
        chip.setText(LocaleController.getString(categoryRes(s.id)));
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.multAlpha(color, 0.12f));
        bg.setCornerRadius(AndroidUtilities.dp(14));
        chip.setBackground(bg);
        return chip;
    }

    static int categoryRes(String id) {
        switch (id) {
            case "dynamic_code": case "native_code": case "remote_code": case "remote_control": return R.string.PluginAnalysisCategoryCode;
            case "hidden_parts": return R.string.PluginAnalysisCategoryStealth;
            case "session_data": case "account_takeover": case "wallet_access": case "sensitive_requests": case "intercepts_requests": case "telegram_api": return R.string.PluginAnalysisCategoryAccount;
            case "messages_delete": case "messages_send": case "forward_messages": case "messages_read": case "contacts": case "notifications": return R.string.PluginAnalysisCategoryMessages;
            case "network": case "webview_bridge": return R.string.PluginAnalysisCategoryNetwork;
            case "files": return R.string.PluginAnalysisCategoryFiles;
            case "processes": case "intents": return R.string.PluginAnalysisCategoryDevice;
            case "java_hooks": case "ui_hooks": case "app_exit": case "app_settings": return R.string.PluginAnalysisCategoryApp;
        }
        return R.string.PluginAnalysisCategoryOther;
    }

    /** The report sheet: sections by severity, expandable statements with targets and source lines. */
    public static void showReport(Context context, PluginStaticAnalyzer.Report report, Theme.ResourcesProvider rp) {
        final BottomSheet sheet = new BottomSheet(context, false, rp);
        sheet.fixNavigationBar();
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16));

        final TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        header.setText(LocaleController.getString(R.string.PluginAnalysisReport));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 8));

        if (report.hasDiff) {
            // exteraGram AnalyzerDiff: what this version adds over the installed one comes first
            final List<PluginStaticAnalyzer.Statement> news = report.news();
            if (!news.isEmpty()) {
                final TextView section = new TextView(context);
                section.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                section.setTypeface(AndroidUtilities.bold());
                section.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
                section.setText(LocaleController.getString(R.string.PluginAnalysisNewInVersion));
                layout.addView(section, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 14, 22, 6));
                for (PluginStaticAnalyzer.Statement s : news) {
                    layout.addView(statementItem(context, s, rp, true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 0, 14, 6));
                }
            }
        }
        for (int level = PluginStaticAnalyzer.LEVEL_CRITICAL; level >= PluginStaticAnalyzer.LEVEL_INFO; level--) {
            final List<PluginStaticAnalyzer.Statement> list = report.ofLevel(level);
            if (list.isEmpty()) continue;
            final TextView section = new TextView(context);
            section.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            section.setTypeface(AndroidUtilities.bold());
            section.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
            section.setText(LocaleController.getString(levelTitle(level)));
            layout.addView(section, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 14, 22, 6));
            for (PluginStaticAnalyzer.Statement s : list) {
                layout.addView(statementItem(context, s, rp, false), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 0, 14, 6));
            }
        }
        if (report.error != null || report.truncated) {
            final TextView section = new TextView(context);
            section.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            section.setTypeface(AndroidUtilities.bold());
            section.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
            section.setText(LocaleController.getString(R.string.PluginAnalysisGroupLimits));
            layout.addView(section, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 14, 22, 6));
            final TextView limit = new TextView(context);
            limit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            limit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            limit.setText(report.error != null ? LocaleController.getString(errorRes(report.error)) : LocaleController.getString(R.string.PluginAnalysisLimitIncompleteInfo));
            layout.addView(limit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 0));
        }
        final TextView footer = new TextView(context);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        footer.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        footer.setText(LocaleController.getString(R.string.PluginAnalysisLimitations));
        layout.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 12, 22, 0));

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(layout);
        sheet.setCustomView(scroll);
        sheet.show();
    }

    private static View statementItem(Context context, PluginStaticAnalyzer.Statement s, Theme.ResourcesProvider rp, boolean onlyNews) {
        final LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setBackground(card(rp));
        item.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tile(context, s.iconRes(), levelColor(s.level), 30), LayoutHelper.createLinear(30, 30));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        name.setText(LocaleController.getString(s.nameRes()));
        texts.addView(name);
        if (s.descriptionRes() != 0) {
            final TextView desc = new TextView(context);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            desc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            desc.setText(LocaleController.getString(s.descriptionRes()));
            texts.addView(desc);
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        item.addView(row);

        final LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        // One code block per finding: the values (targets, files) and the source lines used to be two
        // separate blocks, and for bundled binaries the same paths landed in both (value + file-only evidence).
        final java.util.Collection<String> shownValues = onlyNews && !s.isNew ? s.newValues : s.values;
        final SpannableStringBuilder code = new SpannableStringBuilder();
        final HashSet<String> listed = new HashSet<>();
        for (String v : shownValues) {
            if (code.length() > 0) code.append('\n');
            code.append(v);
            listed.add(v);
        }
        final int lineNumberColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
        for (PluginStaticAnalyzer.Evidence e : s.evidence) {
            final boolean noCode = TextUtils.isEmpty(e.code);
            if (noCode && (e.file == null || listed.contains(e.file))) continue;
            if (code.length() > 0) code.append('\n');
            final int start = code.length();
            if (e.file != null) code.append(e.file).append(e.line > 0 ? ":" : "");
            if (e.line > 0) code.append(String.valueOf(e.line));
            if (noCode) {
                if (e.file != null) listed.add(e.file);
                continue;
            }
            code.append("  ");
            code.setSpan(new ForegroundColorSpan(lineNumberColor), start, code.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            code.append(e.code.length() > 160 ? e.code.substring(0, 160) + "\u2026" : e.code);
        }
        if (s.moreEvidence > 0) {
            if (code.length() > 0) code.append('\n');
            final int start = code.length();
            code.append(LocaleController.formatString(R.string.PluginAnalysisMorePlaces, s.moreEvidence));
            code.setSpan(new ForegroundColorSpan(lineNumberColor), start, code.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (code.length() > 0) {
            details.addView(new ExpandableCodeBlock(context, code, rp), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }
        if (s.unresolvedTargets) {
            final TextView unresolved = new TextView(context);
            unresolved.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            unresolved.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            unresolved.setText(LocaleController.getString(R.string.PluginAnalysisUnresolvedHooks));
            details.addView(unresolved, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }
        if (details.getChildCount() > 0) {
            item.addView(details);
        }
        return item;
    }

    /**
     * A single code block: collapsed to a few lines with a fade at the bottom, expands/collapses on tap
     * with a height animation (as Telegram's expandable quotes), long lines scroll horizontally.
     */
    private static final class ExpandableCodeBlock extends FrameLayout {
        private static final int COLLAPSED_LINES = 4;

        private final TextView text;
        private final ImageView chevron;
        private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int bgColor;
        private int fadeShaderHeight;
        private float progress;
        private boolean expanded;
        private boolean collapsible;
        private ValueAnimator animator;

        ExpandableCodeBlock(Context context, CharSequence code, Theme.ResourcesProvider rp) {
            super(context);
            final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
            bgColor = androidx.core.graphics.ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhite, rp), textColor, 0.05f);
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(bgColor);
            bg.setCornerRadius(AndroidUtilities.dp(8));
            setBackground(bg);
            setClipToOutline(true);
            setWillNotDraw(false);

            text = new TextView(context);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            text.setTextColor(textColor);
            text.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
            text.setText(code);
            final HorizontalScrollView hs = new HorizontalScrollView(context);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setFillViewport(true);
            hs.addView(text, new HorizontalScrollView.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            addView(hs, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));

            chevron = new ImageView(context);
            chevron.setScaleType(ImageView.ScaleType.CENTER);
            chevron.setImageResource(R.drawable.arrow_more);
            chevron.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, rp), PorterDuff.Mode.SRC_IN));
            addView(chevron, LayoutHelper.createFrame(28, 28, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 2, 2));

            final OnClickListener toggle = v -> {
                if (collapsible) setExpanded(!expanded);
            };
            setOnClickListener(toggle);
            text.setOnClickListener(toggle);
        }

        private void setExpanded(boolean value) {
            expanded = value;
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                chevron.setRotation(180f * progress);
                requestLayout();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator == animation) animator = null;
                }
            });
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.setDuration(320);
            animator.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            final int full = getMeasuredHeight();
            final int collapsed = text.getPaddingTop() + text.getPaddingBottom() + text.getLineHeight() * COLLAPSED_LINES;
            collapsible = full > collapsed + AndroidUtilities.dp(4);
            chevron.setVisibility(collapsible ? View.VISIBLE : View.GONE);
            final int height = collapsible ? Math.round(collapsed + (full - collapsed) * progress) : full;
            setMeasuredDimension(getMeasuredWidth(), height);
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            if (child == chevron && collapsible && progress < 1f) {
                // fade-out of the clipped lines, cross-faded away while expanding (drawn under the chevron)
                final int fadeH = AndroidUtilities.dp(36);
                if (fadeShaderHeight != fadeH) {
                    fadeShaderHeight = fadeH;
                    fadePaint.setShader(new LinearGradient(0, 0, 0, fadeH, bgColor & 0x00FFFFFF, bgColor, Shader.TileMode.CLAMP));
                }
                fadePaint.setAlpha(Math.round(255 * (1f - progress)));
                canvas.save();
                canvas.translate(0, getHeight() - fadeH);
                canvas.drawRect(0, 0, getWidth(), fadeH, fadePaint);
                canvas.restore();
            }
            return super.drawChild(canvas, child, drawingTime);
        }
    }

    private static TextView mono(Context context, Theme.ResourcesProvider rp) {
        final TextView t = new TextView(context);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp), 0.05f));
        bg.setCornerRadius(AndroidUtilities.dp(8));
        t.setBackground(bg);
        t.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        return t;
    }
}
