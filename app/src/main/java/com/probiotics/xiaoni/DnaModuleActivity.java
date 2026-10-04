package com.probiotics.xiaoni;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNA 插件管理页（v3.28.11：弹窗 → 独立二级页面，重新设计 UI）。
 * 逻辑对齐原版 modun.sh / dna.xml：
 * - 导入：dna unzip <file.zip2> <module目录>（仅识别 .zip2，suffix="zip2"）
 * - 执行：插件内 sh 脚本；$MODDIR=插件自身目录，$DNA_PRO/$DNA_DRO 由 DnaTools.run 注入当前工程
 * - 删除：rm -rf 插件目录（原版 project.sh sub）
 * 插件作用于当前工程分解后的文件：执行前校验已选工程，执行时 cd $DNA_DRO。
 */
public final class DnaModuleActivity extends BaseActivity {

    private static final int PICK_MODULE = 3001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private LinearLayout moduleList;        // 插件卡片容器
    private TextView emptyView;             // 空状态卡片
    private TextView projectBadge;          // 作用工程徽章行
    private File expandedModule = null;     // 当前展开的插件目录

    // 内嵌控制台（执行插件时展开）
    private LinearLayout consoleCard;
    private TextView consoleTitle;
    private TextView consoleText;
    private ScrollView consoleScroll;
    private Button consoleStop;
    private ScrollView pageScroll;   // 外层页面滚动（执行时自动滚到控制台）

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String t(String zh, String en) {
        return getResources().getConfiguration().locale.getLanguage().startsWith("zh") ? zh : en;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 插件安装目录（沿用 v3.28.8 路径，已导入插件无需重装） */
    private File moduleRoot() {
        File dir = new File(getFilesDir(), "dna-module");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        buildUi();
        refreshModules();
    }

    private void buildUi() {
        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) statusBarHeight = getResources().getDimensionPixelSize(resourceId);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xffd7e3f0, 0xff8fa8c4}));
        root.setPadding(0, statusBarHeight + dp(10), 0, 0);

        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setBackgroundColor(0x00000000);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(16));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        // ---- 标题栏：圆返回键 + 标题 + PDNA 徽章 + 导入按钮 ----
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setAllCaps(false);
        back.setTypeface(null, 1);
        back.setTextColor(0xff0f1e36);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, 0);
        GradientDrawable backBg = new GradientDrawable();
        backBg.setShape(GradientDrawable.OVAL);
        backBg.setColor(0x66ffffff);
        backBg.setStroke(Math.max(1, dp(1)), 0x99ffffff);
        back.setBackground(backBg);
        back.setStateListAnimator(null);
        back.setOnClickListener(v -> {
            Haptics.perform(v);
            finish();
            overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
        });
        titleBar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView title = new TextView(this);
        title.setText(t("DNA 插件", "DNA Modules"));
        title.setTextSize(19);
        title.setTextColor(0xff0f1e36);
        title.setTypeface(null, 1);
        title.setPadding(dp(12), 0, 0, 0);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        // v3.28.12：右上角改为带文字的胶囊按钮（明确"导入插件"入口），替代含义不清的 ＋ 圆钮
        Button importTop = new Button(this);
        importTop.setText("＋ " + t("导入插件", "Import"));
        importTop.setTextSize(13f);
        importTop.setAllCaps(false);
        importTop.setTypeface(null, 1);
        importTop.setTextColor(0xff0f5c4f);
        importTop.setMinWidth(0);
        importTop.setMinHeight(0);
        importTop.setGravity(Gravity.CENTER);
        importTop.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable importBg = new GradientDrawable();
        importBg.setCornerRadius(dp(21));
        importBg.setColor(0x73eafff5);
        importBg.setStroke(Math.max(1, dp(1)), 0x802f9c8f);
        importTop.setBackground(importBg);
        importTop.setStateListAnimator(null);
        importTop.setOnClickListener(v -> pickModule());
        titleBar.addView(importTop, new LinearLayout.LayoutParams(-2, dp(42)));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.bottomMargin = dp(12);
        content.addView(titleBar, titleLp);

        // ---- 作用工程横幅（v3.28.12：整卡可点击切换工程，与 DNA 工作台同款选择器）----
        LinearLayout projCard = new LinearLayout(this);
        projCard.setOrientation(LinearLayout.VERTICAL);
        projCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable projBg = new GradientDrawable();
        projBg.setColor(0x33FFFFFF);
        projBg.setCornerRadius(dp(18));
        projBg.setStroke(Math.max(1, dp(1)), 0x668E6FC7);
        projCard.setBackground(projBg);
        LinearLayout projRow = new LinearLayout(this);
        projRow.setOrientation(LinearLayout.HORIZONTAL);
        projRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout projText = new LinearLayout(this);
        projText.setOrientation(LinearLayout.VERTICAL);
        TextView projLabel = new TextView(this);
        projLabel.setText("🧬  " + t("作用工程（点击切换）", "Acts on project (tap to switch)"));
        projLabel.setTextSize(11.5f);
        projLabel.setTextColor(0xff5a6b82);
        projText.addView(projLabel, new LinearLayout.LayoutParams(-1, -2));
        projectBadge = new TextView(this);
        projectBadge.setTextSize(13f);
        projectBadge.setTypeface(null, 1);
        projectBadge.setSingleLine(true);
        projectBadge.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        projectBadge.setPadding(0, dp(4), 0, 0);
        projText.addView(projectBadge, new LinearLayout.LayoutParams(-1, -2));
        projRow.addView(projText, new LinearLayout.LayoutParams(0, -2, 1f));
        Button switchProject = new Button(this);
        switchProject.setText("⌄");
        switchProject.setAllCaps(false);
        switchProject.setTextSize(18);
        switchProject.setTypeface(null, 1);
        switchProject.setTextColor(0xff172b4d);
        GradientDrawable switchBg = new GradientDrawable();
        switchBg.setShape(GradientDrawable.OVAL);
        switchBg.setColor(0x59FFFFFF);
        switchBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        switchProject.setBackground(switchBg);
        switchProject.setStateListAnimator(null);
        switchProject.setMinWidth(0);
        switchProject.setMinHeight(0);
        switchProject.setGravity(Gravity.CENTER);
        switchProject.setPadding(0, 0, 0, 0);
        switchProject.setOnClickListener(v -> {
            Haptics.perform(v);
            showProjectPicker();
        });
        projRow.addView(switchProject, new LinearLayout.LayoutParams(dp(42), dp(42)));
        projCard.addView(projRow, new LinearLayout.LayoutParams(-1, -2));
        TextView projHint = new TextView(this);
        projHint.setText(t("插件将作用于该工程分解后的文件（$DNA_DRO），执行时自动切换工作目录",
                "Plugins act on this project's extracted files ($DNA_DRO)"));
        projHint.setTextSize(10.5f);
        projHint.setTextColor(0xff5a6b82);
        projHint.setPadding(0, dp(5), 0, 0);
        projCard.addView(projHint, new LinearLayout.LayoutParams(-1, -2));
        projCard.setOnClickListener(v -> {
            Haptics.perform(v);
            showProjectPicker();
        });
        LinearLayout.LayoutParams projLp = new LinearLayout.LayoutParams(-1, -2);
        projLp.bottomMargin = dp(12);
        content.addView(projCard, projLp);

        // ---- 插件列表区 ----
        moduleList = new LinearLayout(this);
        moduleList.setOrientation(LinearLayout.VERTICAL);
        content.addView(moduleList, new LinearLayout.LayoutParams(-1, -2));

        // v3.28.12：删除底部重复的导入大按钮（右上角"＋ 导入插件"已是唯一入口），仅保留小字说明
        TextView footNote = new TextView(this);
        footNote.setText(t("仅识别 .zip2 结尾的插件包 · 插件内的 sh 脚本点击即执行",
                "Only .zip2 packs · tap a sh script inside to run"));
        footNote.setTextSize(10.5f);
        footNote.setTextColor(0xff5a6b82);
        footNote.setGravity(Gravity.CENTER);
        footNote.setPadding(0, dp(10), 0, dp(6));
        content.addView(footNote, new LinearLayout.LayoutParams(-1, -2));

        // ---- 内嵌控制台卡片（执行插件时展开）----
        consoleCard = new LinearLayout(this);
        consoleCard.setOrientation(LinearLayout.VERTICAL);
        consoleCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        consoleCard.setVisibility(View.GONE);
        GradientDrawable consoleBg = new GradientDrawable();
        // v3.30.28：控制台换浅色磨砂底（对齐解压ROM页面，弃用黑色终端风）
        consoleBg.setColor(0xEdf4f8fc);
        consoleBg.setCornerRadius(dp(18));
        consoleBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
        consoleCard.setBackground(consoleBg);
        LinearLayout consoleHead = new LinearLayout(this);
        consoleHead.setOrientation(LinearLayout.HORIZONTAL);
        consoleHead.setGravity(Gravity.CENTER_VERTICAL);
        consoleTitle = new TextView(this);
        consoleTitle.setTextSize(13.5f);
        consoleTitle.setTypeface(null, 1);
        consoleTitle.setTextColor(0xff17334f);
        consoleTitle.setSingleLine(true);
        consoleTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        consoleHead.addView(consoleTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        Button copyConsole = new Button(this, null, 0);
        copyConsole.setText("⧉ " + t("复制", "Copy"));
        copyConsole.setAllCaps(false);
        copyConsole.setTextSize(11.5f);
        copyConsole.setTypeface(null, 1);
        copyConsole.setTextColor(0xff1f7d72);
        copyConsole.setMinWidth(0);
        copyConsole.setMinHeight(0);
        copyConsole.setGravity(Gravity.CENTER);
        copyConsole.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable copyBg = new GradientDrawable();
        copyBg.setColor(0x55FFFFFF);
        copyBg.setCornerRadius(dp(15));
        copyBg.setStroke(Math.max(1, dp(1)), 0x55FFFFFF);
        copyConsole.setBackground(copyBg);
        copyConsole.setStateListAnimator(null);
        copyConsole.setOnClickListener(v -> {
            Haptics.perform(v);
            CharSequence text = consoleText.getText();
            if (text.length() == 0) { toast(t("暂无日志", "Nothing to copy")); return; }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DNA module log", text));
            toast(t("已复制全部日志", "Log copied"));
        });
        consoleHead.addView(copyConsole, new LinearLayout.LayoutParams(-2, dp(30)));
        consoleHead.setPadding(0, 0, 0, dp(8));
        consoleCard.addView(consoleHead, new LinearLayout.LayoutParams(-1, -2));
        // v3.41.11：BoundedScrollView（OTG 触摸模型，整条祖先链独占）替换裸 ScrollView
        consoleScroll = new DnaActivity.BoundedScrollView(this, 0);
        consoleText = new TextView(this);
        consoleText.setTypeface(Typeface.MONOSPACE);
        consoleText.setTextSize(11.5f);
        consoleText.setTextColor(0xff2c3e57);
        consoleText.setPadding(dp(6), dp(6), dp(6), dp(6));
        // v3.28.12：长行自动换行（此前超宽行被横向截断"显示不完全"）
        consoleText.setHorizontallyScrolling(false);
        consoleScroll.addView(consoleText, new ScrollView.LayoutParams(-1, -2));
        // v3.28.12：高度 220 → 340dp（显示更多日志）
        consoleCard.addView(consoleScroll, new LinearLayout.LayoutParams(-1, dp(340)));
        consoleStop = new Button(this, null, 0);
        consoleStop.setText(t("■ 停止执行", "■ Stop"));
        consoleStop.setAllCaps(false);
        consoleStop.setTextSize(13f);
        consoleStop.setTypeface(null, 1);
        consoleStop.setTextColor(0xffa33b3b);
        consoleStop.setGravity(Gravity.CENTER);
        consoleStop.setPadding(0, 0, 0, 0);
        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setColor(0x33a33b3b);
        stopBg.setCornerRadius(dp(16));
        stopBg.setStroke(Math.max(1, dp(1)), 0x66a33b3b);
        consoleStop.setBackground(stopBg);
        consoleStop.setStateListAnimator(null);
        consoleStop.setOnClickListener(v -> {
            Haptics.perform(v);
            if (running.get()) cancelFlag.set(true);
            else consoleCard.setVisibility(View.GONE);
        });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(-1, dp(42));
        stopLp.topMargin = dp(8);
        consoleCard.addView(consoleStop, stopLp);
        content.addView(consoleCard, new LinearLayout.LayoutParams(-1, -2));

        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    /** 选择 .zip2 插件包（v3.30.15：内置文件浏览器替换系统 SAF；后缀在导入时严格校验） */
    private void pickModule() {
        Haptics.perform(runButtonStub());
        FileBrowserDialog.show(this, t("导入插件（.zip2）", "Import module (.zip2)"),
                new String[]{".zip2"}, DnaTools.WORK_ROOT, path -> importModule(path));
    }

    private View runButtonStub() {
        return findViewById(android.R.id.content);
    }

    // ============ 插件列表 ============

    private void refreshModules() {
        moduleList.removeAllViews();
        String project = DnaTools.currentProject(this);
        if (project != null) {
            projectBadge.setText(project);
            projectBadge.setTextColor(0xff1d6f42);
        } else {
            projectBadge.setText(t("⚠ 未选择工程 —— 执行插件前请先在 DNA 页切换工程",
                    "⚠ No project — switch to one on the DNA page before running"));
            projectBadge.setTextColor(0xffC03A2B);
        }

        File[] modules = moduleRoot().listFiles(File::isDirectory);
        if (modules == null || modules.length == 0) {
            emptyView = new TextView(this);
            emptyView.setText("🧩  " + t("暂无插件\n点右上角 ＋ 或下方按钮导入 .zip2 插件包",
                    "No modules yet\nImport a .zip2 pack via ＋ or the button below"));
            emptyView.setTextSize(13f);
            emptyView.setTextColor(0xff5a6b82);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(dp(10), dp(30), dp(10), dp(30));
            GradientDrawable emptyBg = new GradientDrawable();
            emptyBg.setColor(0x26FFFFFF);
            emptyBg.setCornerRadius(dp(18));
            emptyBg.setStroke(Math.max(1, dp(1)), 0x59FFFFFF);
            emptyView.setBackground(emptyBg);
            moduleList.addView(emptyView, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        Arrays.sort(modules, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File mod : modules) {
            moduleList.addView(buildModuleCard(mod), cardLp());
        }
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        return lp;
    }

    // ============ 工程切换（v3.28.12：与 DNA 工作台同款选择器） ============

    /** 弹出工程选择器：切换后插件直接作用于新工程的分解产物（$DNA_PRO / $DNA_DRO 联动） */
    private void showProjectPicker() {
        java.util.List<String> projects = DnaTools.listProjects();
        String current = DnaTools.currentProject(this);
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        panel.setBackground(bg);
        TextView title = new TextView(this);
        title.setText(t("选择工程", "Select project"));
        title.setTextSize(16);
        title.setTypeface(null, 1);
        title.setTextColor(0xff17334f);
        title.setPadding(0, 0, 0, dp(10));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        ScrollView listScroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        if (projects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(t("暂无工程，请先在 DNA 页新建工程", "No projects yet. Create one on DNA page"));
            empty.setTextSize(13);
            empty.setTextColor(0xff5a6b82);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (final String name : projects) {
            boolean isCur = name.equals(current);
            TextView row = new TextView(this);
            row.setText((isCur ? "● " : "○ ") + name);
            row.setTextSize(14);
            row.setTextColor(isCur ? 0xff14524b : 0xff17334f);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(13), dp(12), dp(13));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(isCur ? 0x332f9c8f : 0x22FFFFFF);
            rowBg.setCornerRadius(dp(14));
            rowBg.setStroke(Math.max(1, dp(1)), isCur ? 0x662f9c8f : 0x33FFFFFF);
            row.setBackground(rowBg);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.bottomMargin = dp(6);
            list.addView(row, rowLp);
            row.setOnClickListener(v -> {
                Haptics.perform(v);
                DnaTools.setCurrentProject(this, name);
                dialog.dismiss();
                toast(t("已切换工程", "Project switched") + ": " + name);
                refreshModules();
            });
        }
        panel.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button close = new Button(this);
        close.setText(t("关闭", "Close"));
        close.setAllCaps(false);
        close.setTextColor(0xff172b4d);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, 0, 0, 0);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(0x59FFFFFF);
        closeBg.setCornerRadius(dp(16));
        closeBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        close.setBackground(closeBg);
        close.setStateListAnimator(null);
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(close, new LinearLayout.LayoutParams(-1, dp(44)));
        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(440)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        dialog.show();
        dialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.94f), dp(440));
    }

    /** 单个插件卡片：图标块 + 名称 + 脚本数徽章；点击展开脚本列表与删除 */
    private LinearLayout buildModuleCard(File mod) {
        List<File> scripts = new ArrayList<>();
        collectShScripts(mod, scripts, 0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        card.setBackground(bg);

        // 头部：图标块 + 名称/路径 + 脚本数
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout icon = new LinearLayout(this);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable iconBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF8E6FC7, 0xFF2E86AB});
        iconBg.setCornerRadius(dp(14));
        icon.setBackground(iconBg);
        TextView iconText = new TextView(this);
        iconText.setText("🧩");
        iconText.setTextSize(18);
        icon.addView(iconText, new LinearLayout.LayoutParams(-2, -2));
        head.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        nameBox.setPadding(dp(12), 0, dp(8), 0);
        TextView name = new TextView(this);
        name.setText(mod.getName());
        name.setTextSize(15f);
        name.setTypeface(null, 1);
        name.setTextColor(0xff17334f);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nameBox.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = new TextView(this);
        sub.setText("$MODDIR=" + mod.getAbsolutePath());
        sub.setTextSize(10f);
        sub.setTextColor(0xff5a6b82);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nameBox.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        head.addView(nameBox, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView count = new TextView(this);
        count.setText(scripts.size() + " sh");
        count.setTextSize(11f);
        count.setTypeface(null, 1);
        count.setTextColor(0xff1f7d72);
        count.setGravity(Gravity.CENTER);
        GradientDrawable countBg = new GradientDrawable();
        countBg.setCornerRadius(dp(12));
        countBg.setColor(0x59eafff5);
        countBg.setStroke(Math.max(1, dp(1)), 0x662f9c8f);
        count.setBackground(countBg);
        head.addView(count, new LinearLayout.LayoutParams(dp(52), dp(26)));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // 展开区：脚本行 + 删除按钮（仅当前展开的插件显示）
        if (mod.equals(expandedModule)) {
            View divider = new View(this);
            divider.setBackgroundColor(0x33173E5C);
            LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
            dvLp.topMargin = dp(10);
            dvLp.bottomMargin = dp(8);
            card.addView(divider, dvLp);

            if (scripts.isEmpty()) {
                TextView none = new TextView(this);
                none.setText(t("插件内没有 sh 脚本", "No sh scripts in this module"));
                none.setTextSize(12f);
                none.setTextColor(0xff5a6b82);
                none.setPadding(dp(4), dp(6), dp(4), dp(6));
                card.addView(none, new LinearLayout.LayoutParams(-1, -2));
            }
            for (File sh : scripts) {
                final String rel = sh.getAbsolutePath().replace(mod.getAbsolutePath() + "/", "");
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setColor(0x4035A8C4);
                rowBg.setCornerRadius(dp(14));
                row.setBackground(rowBg);
                TextView run = new TextView(this);
                run.setText("▶");
                run.setTextSize(14);
                run.setTextColor(0xff1d6f42);
                run.setTypeface(null, 1);
                row.addView(run, new LinearLayout.LayoutParams(-2, -2));
                TextView scriptName = new TextView(this);
                scriptName.setText(rel);
                scriptName.setTextSize(12.5f);
                scriptName.setTextColor(0xff17334f);
                scriptName.setSingleLine(true);
                scriptName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                scriptName.setPadding(dp(10), 0, 0, 0);
                row.addView(scriptName, new LinearLayout.LayoutParams(0, -2, 1f));
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.bottomMargin = dp(6);
                card.addView(row, rowLp);
                row.setOnClickListener(v -> {
                    Haptics.perform(v);
                    runScript(mod, rel, sh);
                });
            }
            Button delete = new Button(this, null, 0);
            delete.setText("🗑  " + t("删除插件", "Delete module"));
            delete.setAllCaps(false);
            delete.setTextSize(13f);
            delete.setTypeface(null, 1);
            delete.setTextColor(Color.WHITE);
            delete.setGravity(Gravity.CENTER);
            delete.setPadding(0, 0, 0, 0);
            delete.setBackgroundResource(R.drawable.button_red);
            delete.setStateListAnimator(null);
            delete.setOnClickListener(v -> {
                Haptics.perform(v);
                RootShell.exec("rm -rf " + DnaTools.quote(mod.getAbsolutePath()), 30000, null);
                if (mod.equals(expandedModule)) expandedModule = null;
                toast(t("已删除插件", "Module deleted"));
                refreshModules();
            });
            card.addView(delete, new LinearLayout.LayoutParams(-1, dp(42)));
        }

        // 整卡点击：展开 / 收起
        card.setOnClickListener(v -> {
            Haptics.perform(v);
            expandedModule = mod.equals(expandedModule) ? null : mod;
            refreshModules();
        });
        return card;
    }

    /** 递归收集插件内 sh 脚本（最多两层，避免大目录卡顿） */
    private void collectShScripts(File dir, List<File> out, int depth) {
        if (depth > 2) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isFile() && f.getName().endsWith(".sh")) out.add(f);
            else if (f.isDirectory()) collectShScripts(f, out, depth + 1);
        }
    }

    // ============ 执行 ============

    /** 执行插件脚本：校验工程 → 控制台流式输出（$MODDIR 注入 + cd $DNA_DRO） */
    private void runScript(File mod, String rel, File sh) {
        if (running.get()) { toast(t("正在执行其他脚本，请先停止", "A script is running, stop it first")); return; }
        String project = DnaTools.currentProject(this);
        if (project == null) {
            toast(t("请先在 DNA 页切换到要作用的工程", "Switch to a project on the DNA page first"));
            return;
        }
        running.set(true);
        cancelFlag.set(false);
        consoleCard.setVisibility(View.VISIBLE);
        consoleTitle.setText("▶ " + mod.getName() + " · " + rel);
        consoleText.setText("");
        consoleStop.setText(t("■ 停止执行", "■ Stop"));
        // v3.28.12：执行时自动把页面滚到控制台（此前控制台在页尾，用户看不到已开始执行）
        pageScroll.post(() -> pageScroll.smoothScrollTo(0, consoleCard.getBottom() + dp(8)));
        final String command = "export MODDIR=" + DnaTools.quote(mod.getAbsolutePath())
                + "; cd \"$DNA_DRO\" 2>/dev/null || true; sh " + DnaTools.quote(sh.getAbsolutePath());
        mainHandler.post(() -> consoleText.append("$ sh " + rel + "\n"));
        new Thread(() -> {
            DnaTools.Result result = DnaTools.run(this, command,
                    line -> {
                        mainHandler.post(() -> {
                            consoleText.append(line + "\n");
                            consoleScroll.post(() -> consoleScroll.fullScroll(ScrollView.FOCUS_DOWN));
                        });
                        return kotlin.Unit.INSTANCE;
                    },
                    cancelFlag::get);
            mainHandler.post(() -> {
                running.set(false);
                consoleText.append((result.getSuccess() ? "\n✓ " : "\n✗ ") + result.getMessage() + "\n");
                consoleStop.setText(t("收起控制台", "Hide console"));
                toast(result.getSuccess() ? t("插件执行完成", "Module finished") : t("执行失败", "Failed"));
            });
        }, "dna-module-run").start();
    }

    // ============ 导入 ============

    /** 导入插件（对齐原版 dna.xml：dna unzip $file $module目录；仅识别 .zip2；
     *  v3.30.15：真实绝对路径 root 直接解压，无需复制缓存） */
    private void importModule(String path) {
        String name = new File(path).getName();
        if (name == null || !name.toLowerCase(Locale.US).endsWith(".zip2")) {
            toast(t("仅支持 .zip2 结尾的插件包", "Only .zip2 plugin packs are supported")
                    + (name == null ? "" : ": " + name));
            return;
        }
        toast(t("正在导入插件 ...", "Importing module..."));
        new Thread(() -> {
            DnaTools.Result r = DnaTools.run(this,
                    "dna unzip " + DnaTools.quote(path) + " "
                            + DnaTools.quote(moduleRoot().getAbsolutePath()));
            mainHandler.post(() -> {
                toast(r.getSuccess() ? t("插件已导入", "Module imported")
                        : t("导入失败", "Import failed") + ": " + r.getMessage());
                refreshModules();
            });
        }, "dna-module-import").start();
    }
}
