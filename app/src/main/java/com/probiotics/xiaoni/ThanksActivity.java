package com.probiotics.xiaoni;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 特别鸣谢页（v3.30.23：并入原「关于 Dsu 管理器」鸣谢内容）。
 * 内容对齐原版 DNA 工具箱 thanks 声明：鸣谢不分先后，如有遗忘望提醒。
 */
public final class ThanksActivity extends BaseActivity {

    /** 鸣谢条目：图标 / 标题 / 描述 / 链接（空 = 无） */
    private static final String[][] CREDITS = {
            {"🛠", "TIK工具箱",
                    "部分代码来自于TIK2源码，在此感谢", "https://gitee.com/yeliqin666/TIK"},
            {"🎭", "magiskboot",
                    "镜像解包/打包核心工具", "https://github.com/topjohnwu/Magisk"},
            {"🔄", "sdat2img and img2sdat",
                    "transfer.list 数据转换工具", "https://github.com/xpirt"},
            {"🗜", "erofs-extract",
                    "EROFS 镜像提取工具", "https://github.com/sekaiacg/erofs-extract"},
            {"🧬", "DNA",
                    "使用了@温柔的慈悲大佬的DNA工具箱名字，向大佬致敬！", "https://gitee.com/sharpeter/DNA"},
            {"⚡", "DNA-Android | 酷安：tao1996",
                    "最开始构建 DNA 软件的佬", "https://www.coolapk.com/u/1128537"},
            {"🔧", "酷安：@相见即是缘",
                    "感谢大佬一直维护的 DNA 工具，DNA 打包功能移植自其 20260530 版本", "https://www.coolapk.com/u/1614257"},
            {"📱", "搞机助手",
                    "搞机助手原作者@情非得已c，提取了搞机助手部分代码文件使用！", ""},
            {"🐍", "affggh",
                    "改用@affggh大佬的fspatch.py修补权限文件以及github开源的工具", "https://github.com/affggh/fspatch"},
            {"📦", "DSU-Sideloader",
                    "本应用的 GSI 安装流程参考并使用了 DSU-Sideloader 项目的相关方案", "https://github.com/VegaBobo/DSU-Sideloader"},
            {"🌟", "yangFenTuoZi",
                    "感谢开发 Dsu 功能 img 无损替换功能", "https://www.coolapk.com/u/15035178"},
            {"📊", "DevCheck by flar2",
                    "设备检测功能使用了 DevCheck 的 native 库和 SoC 数据库，在此特别感谢", "https://play.google.com/store/apps/details?id=flar2.devcheck"},
            {"🔬", "SoC Database",
                    "处理器品牌 Logo 和 SoC 信息数据库来自 DevCheck 项目", ""},
            {"👤", "作者：小你可兰",
                    "Dsu GSI 管理器 v" + BuildConfig.VERSION_NAME, ""},
    };

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private String t(String zh, String en) {
        return getResources().getConfiguration().locale.getLanguage().startsWith("zh") ? zh : en;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        Haptics.onTouch(getWindow().getDecorView(), e);
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);

        int sb = 0;
        int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (rid > 0) sb = getResources().getDimensionPixelSize(rid);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xffd7e3f0, 0xff8fa8c4}));
        root.setPadding(0, sb + dp(8), 0, 0);

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(24));
        page.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // ---- 标题栏 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this, null, 0);
        back.setText("‹");
        back.setTextSize(26);
        back.setAllCaps(false);
        back.setTextColor(0xff0f1e36);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable bkBg = new android.graphics.drawable.GradientDrawable();
        bkBg.setColor(0x59FFFFFF);
        bkBg.setCornerRadius(dp(23));
        bkBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        back.setBackground(bkBg);
        back.setStateListAnimator(null);
        back.setOnClickListener(v -> { Haptics.perform(v); finish(); overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out); });
        bar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView title = new TextView(this);
        title.setText(t("特别鸣谢", "Credits"));
        title.setTextSize(19);
        title.setTypeface(null, 1);
        title.setTextColor(0xff0f1e36);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.bottomMargin = dp(12);
        content.addView(bar, barLp);

        // ---- 顶部说明卡 ----
        LinearLayout headCard = new LinearLayout(this);
        headCard.setOrientation(LinearLayout.VERTICAL);
        headCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable hb = new android.graphics.drawable.GradientDrawable();
        hb.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        hb.setColors(new int[]{0xFF35A8C4, 0xFF0E7D95});
        hb.setCornerRadius(dp(20));
        hb.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        headCard.setBackground(hb);
        TextView hIcon = new TextView(this);
        hIcon.setText("❤");
        hIcon.setTextSize(24);
        hIcon.setGravity(Gravity.CENTER);
        headCard.addView(hIcon, new LinearLayout.LayoutParams(-1, -2));
        TextView hTitle = new TextView(this);
        hTitle.setText(t("特别鸣谢，不分先后，如有遗忘望提醒", "Special thanks, in no particular order"));
        hTitle.setTextSize(14.5f);
        hTitle.setTypeface(null, 1);
        hTitle.setTextColor(0xffffffff);
        hTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(-1, -2);
        htLp.topMargin = dp(6);
        headCard.addView(hTitle, htLp);
        TextView hDesc = new TextView(this);
        hDesc.setText(t("本软件的诞生离不开这些开源项目与开发者们的贡献", "This app exists thanks to these open-source projects and developers"));
        hDesc.setTextSize(12f);
        hDesc.setTextColor(0xccffffff);
        hDesc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hdLp = new LinearLayout.LayoutParams(-1, -2);
        hdLp.topMargin = dp(4);
        headCard.addView(hDesc, hdLp);
        content.addView(headCard, new LinearLayout.LayoutParams(-1, -2));

        // ---- 鸣谢列表 ----
        for (final String[] c : CREDITS) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(12), dp(11), dp(10), dp(11));
            android.graphics.drawable.GradientDrawable cb = new android.graphics.drawable.GradientDrawable();
            cb.setColor(0x33FFFFFF);
            cb.setCornerRadius(dp(18));
            cb.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
            card.setBackground(cb);
            // 图标徽章
            FrameLayout icon = new FrameLayout(this);
            android.graphics.drawable.GradientDrawable ib = new android.graphics.drawable.GradientDrawable();
            ib.setCornerRadius(dp(17));
            ib.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
            ib.setColors(new int[]{0xFF8E6FC7, 0xFF35A8C4});
            icon.setBackground(ib);
            TextView ie = new TextView(this);
            ie.setText(c[0]);
            ie.setTextSize(17);
            ie.setGravity(Gravity.CENTER);
            icon.addView(ie, new FrameLayout.LayoutParams(-1, -1));
            card.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            // 标题 + 描述
            LinearLayout textBox = new LinearLayout(this);
            textBox.setOrientation(LinearLayout.VERTICAL);
            textBox.setPadding(dp(11), 0, dp(6), 0);
            TextView name = new TextView(this);
            name.setText(c[1]);
            name.setTextSize(13f);
            name.setTypeface(null, 1);
            name.setTextColor(0xff17334f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            textBox.addView(name, new LinearLayout.LayoutParams(-1, -2));
            if (c[2] != null && !c[2].isEmpty()) {
                TextView desc = new TextView(this);
                desc.setText(c[2]);
                desc.setTextSize(11f);
                desc.setTextColor(0xff5a6b82);
                LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, -2);
                dLp.topMargin = dp(2);
                textBox.addView(desc, dLp);
            }
            card.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1f));
            // 链接按钮
            if (c[3] != null && !c[3].isEmpty()) {
                Button link = new Button(this, null, 0);
                link.setText("↗");
                link.setTextSize(14);
                link.setAllCaps(false);
                link.setTextColor(0xff0f5c4f);
                link.setMinWidth(0);
                link.setMinHeight(0);
                link.setGravity(Gravity.CENTER);
                link.setPadding(0, 0, 0, 0);
                android.graphics.drawable.GradientDrawable lb = new android.graphics.drawable.GradientDrawable();
                lb.setColor(0x73eafff5);
                lb.setCornerRadius(dp(15));
                lb.setStroke(Math.max(1, dp(1)), 0x802f9c8f);
                link.setBackground(lb);
                link.setStateListAnimator(null);
                link.setOnClickListener(v -> {
                    Haptics.perform(v);
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(c[3])));
                    } catch (Exception e) {
                        Toast.makeText(this, t("无应用可打开链接", "No app to open link"), Toast.LENGTH_SHORT).show();
                    }
                });
                card.addView(link, new LinearLayout.LayoutParams(dp(30), dp(30)));
            }
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, -2);
            cLp.topMargin = dp(10);
            content.addView(card, cLp);
        }

        // ---- 底部寄语 ----
        TextView foot = new TextView(this);
        foot.setText(t("开源让世界更美好\n谨向所有为中文搞机社区贡献过代码、教程与时间的人们致敬", "Open source makes the world better"));
        foot.setTextSize(12f);
        foot.setTextColor(0xff5a6b82);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(-1, -2);
        fLp.topMargin = dp(18);
        content.addView(foot, fLp);
    }
}
