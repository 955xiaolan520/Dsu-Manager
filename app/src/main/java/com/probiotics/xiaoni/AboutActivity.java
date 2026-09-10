package com.probiotics.xiaoni;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class AboutActivity extends Activity {
    
    private static final String VERSION = "3.5.8";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置状态栏样式
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xfff5f7fa);
        
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(dp(16), dp(16) + bars.top, dp(16), dp(16) + bars.bottom);
            } else {
                view.setPadding(dp(16), dp(16) + insets.getSystemWindowInsetTop(), dp(16), dp(16) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        
        // 返回按钮
        Button back = new Button(this);
        back.setText("←");
        back.setTextSize(32);
        back.setTextColor(0xff20375b);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setPadding(0, 0, 0, 0);
        back.setOnClickListener(v -> {
            finish();
            overridePendingTransition(R.anim.explode_in, R.anim.explode_out);
        });
        root.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        
        // 标题
        TextView title = createTextView("关于 Dsu 管理器", 24, 0xff20375b, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(16);
        titleLp.bottomMargin = dp(24);
        root.addView(title, titleLp);
        
        // 版本信息卡片
        LiquidGlassPanel versionCard = new LiquidGlassPanel(this);
        versionCard.setOrientation(LinearLayout.VERTICAL);
        versionCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        
        TextView appName = createTextView("Dsu GSI 管理器", 18, 0xff20375b, true);
        versionCard.addView(appName, margins(-1, -2, 0, 0, 12));
        
        TextView versionText = createTextView("版本：" + BuildConfig.VERSION_NAME, 14, 0xff596579, false);
        versionCard.addView(versionText, margins(-1, -2, 0, 0, 0));
        
        root.addView(versionCard, margins(-1, -2, 0, 0, 16));
        
        // 功能说明卡片
        LiquidGlassPanel featureCard = new LiquidGlassPanel(this);
        featureCard.setOrientation(LinearLayout.VERTICAL);
        featureCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        
        TextView featureTitle = createTextView("功能说明", 18, 0xff20375b, true);
        featureCard.addView(featureTitle, margins(-1, -2, 0, 0, 12));
        
        String featureText = "本应用的 GSI 安装流程参考并使用了 DSU-Sideloader 项目的相关方案。\n\n" +
                "• 支持安装 DSU 镜像的 img 无损替换\n" +
                "• 支持 system、system_ext、product、vendor、odm、my_preload 等镜像\n" +
                "• 替换修改后的 img 镜像之后直接开机，无需重新过开机引导\n" +
                "• 直接开机使用修复 bug 后的 Dsu 系统\n\n" +
                "使用安卓系统组件：\n" +
                "• /system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n" +
                "• /system/bin/gsi_tool\n" +
                "• /system/bin/gsid";
        
        TextView featureContent = createTextView(featureText, 14, 0xff334b66, false);
        featureContent.setLineSpacing(dp(4), 1f);
        featureCard.addView(featureContent, margins(-1, -2, 0, 0, 0));
        
        root.addView(featureCard, margins(-1, -2, 0, 0, 16));
        
        // 致谢卡片
        LiquidGlassPanel thanksCard = new LiquidGlassPanel(this);
        thanksCard.setOrientation(LinearLayout.VERTICAL);
        thanksCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        
        TextView thanksTitle = createTextView("特别感谢", 18, 0xff20375b, true);
        thanksCard.addView(thanksTitle, margins(-1, -2, 0, 0, 12));
        
        String thanksText = "安装功能参考 DSU-Sideloader 项目：\n" +
                "https://github.com/VegaBobo/DSU-Sideloader\n\n" +
                "液态玻璃效果参考：\n" +
                "https://github.com/YotpoLtd/ClarabridgeChat-android-distribution\n\n" +
                "特别感谢酷安用户及 GitHub 用户 yangFenTuoZi 开发 Dsu 功能修改 img 无损替换功能。\n\n" +
                "如有侵权，请联系作者，我们会及时删除相关内容。";
        
        TextView thanksContent = createTextView(thanksText, 14, 0xff334b66, false);
        thanksContent.setLineSpacing(dp(4), 1f);
        thanksContent.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        thanksContent.setMovementMethod(LinkMovementMethod.getInstance());
        thanksCard.addView(thanksContent, margins(-1, -2, 0, 0, 0));
        
        root.addView(thanksCard, margins(-1, -2, 0, 0, 16));
        
        // 作者信息卡片
        LiquidGlassPanel authorCard = new LiquidGlassPanel(this);
        authorCard.setOrientation(LinearLayout.VERTICAL);
        authorCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        
        TextView authorTitle = createTextView("作者信息", 18, 0xff20375b, true);
        authorCard.addView(authorTitle, margins(-1, -2, 0, 0, 12));
        
        TextView authorContent = createTextView("作者：小你可兰", 14, 0xff334b66, false);
        authorCard.addView(authorContent, margins(-1, -2, 0, 0, 0));
        
        root.addView(authorCard, margins(-1, -2, 0, 0, 16));
        
        scrollView.addView(root);
        setContentView(scrollView);
    }
    
    @Override public void onBackPressed() {
        finish();
        overridePendingTransition(R.anim.flip_in, R.anim.flip_out);
    }
    
    private TextView createTextView(String text, int textSize, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(textSize);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }
    
    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }
    
    private LinearLayout.LayoutParams margins(int width, int height, int top, int start, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.topMargin = dp(top);
        lp.setMarginStart(dp(start));
        lp.bottomMargin = dp(bottom);
        return lp;
    }
    
    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : dp(24);
    }
}
