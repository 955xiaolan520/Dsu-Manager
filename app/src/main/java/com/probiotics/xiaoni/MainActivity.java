package com.probiotics.xiaoni;

import android.app.*;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.*;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.view.WindowManager;
import com.topjohnwu.superuser.ipc.RootService;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.io.*;
import java.net.HttpURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private TextView rootStatus, gsiStatus, detailText;
    private LinearLayout logoCard;
    private static final int PICK_IMAGE = 10;
    private static final int PICK_ZIP = 20;
    private static final int PICK_REPLACEMENT = 30;
    private static final int PICK_ROOTFS = 40;
    private String replacementPartition;
    private String replacementBackingImage;
    private String replacementSlot;
    private String replacementImagePath;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingLogoClick;
    private String installedZipName = "未记录 ZIP 名称";
    private long userdataSizeBytes = 16L * 1024L * 1024L * 1024L;
    private String pendingSizeLabel = "16 GB";
    private boolean keepScreenOn;
    private boolean rootServiceBound;
    private boolean english;
    private int languageMode;
    private boolean rootAuthorized;
    private boolean rootCheckInProgress;
    private Button[] actionButtons;
    private static final String DSU_SLOT = "dsu";
    private LinearLayout installPanel, installOptionsPanel;
    private FrameLayout pageHost;
    private ScrollView homeScroll;
    private FrameLayout bottomNavigation;
    private LinearLayout bottomNavigationItems;
    private View liquidIndicator;
    private int liquidIndicatorLeft = -1;
    private TextView embeddedUpdateStatus, embeddedReleaseNotes, embeddedDownloadHint;
    private Button embeddedDownloadButton;
    private int currentTab;
    private LinearLayout imageManagementPanel;
    private ProgressBar installProgress;
    private TextView installStage, installZipLabel;
    private Button confirmInstallButton;
    private Button[] installSizeButtons;
    private EditText customInstallSizeInput;
    private Uri pendingInstallZip;
    private int selectedInstallSize = 1;
    private final java.util.concurrent.ExecutorService moreWorker = java.util.concurrent.Executors.newFixedThreadPool(2);
    private IPrivilegedService privilegedService;
    private final ServiceConnection rootConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            privilegedService = IPrivilegedService.Stub.asInterface(service);
            rootServiceBound = true;
            refreshRootStatus();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            privilegedService = null;
            rootServiceBound = false;
            setRootAuthorized(false);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        languageMode = getSharedPreferences("settings", MODE_PRIVATE).getInt("language_mode", 0);
        english = languageMode == 2 || (languageMode == 0 && Locale.getDefault().getLanguage().equals("en"));
         installedZipName = getPreferences(MODE_PRIVATE).getString("installed_zip_name", "未记录 ZIP 名称");
         keepScreenOn = getPreferences(MODE_PRIVATE).getBoolean("keep_screen_on", false);
         applyKeepScreenOn();
        getWindow().setStatusBarColor(Color.rgb(168, 191, 208));
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        buildUi();
        bindRootService();
        refreshRootStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        int selectedLanguage = getSharedPreferences("settings", MODE_PRIVATE).getInt("language_mode", 0);
        if (selectedLanguage != languageMode) {
            languageMode = selectedLanguage;
            english = languageMode == 2 || (languageMode == 0 && Locale.getDefault().getLanguage().equals("en"));
            buildUi();
            if (rootAuthorized) refreshStatus();
        }
        bindRootService();
        if (!rootAuthorized) refreshRootStatus();
    }

    @Override protected void onPause() {
        super.onPause();
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private String t(String chinese, String englishText) { return english ? englishText : chinese; }

    private void updateActionButtons() {
        if (actionButtons == null) return;
        for (Button button : actionButtons) {
            button.setEnabled(rootAuthorized);
            button.setAlpha(rootAuthorized ? 1f : 0.45f);
        }
    }

    private void setRootAuthorized(boolean authorized) {
        rootAuthorized = authorized;
        if (rootStatus != null) {
            rootStatus.setText(authorized ? t("ROOT 已授权", "ROOT granted") : t("ROOT 失败", "ROOT unavailable"));
            rootStatus.setBackgroundResource(authorized ? R.drawable.root_status_bg : R.drawable.button_red);
        }
        updateActionButtons();
        if (authorized && detailText != null) refreshStatus();
    }

    private void bindRootService() {
        if (rootServiceBound || privilegedService != null) return;
        rootServiceBound = true;
        RootService.bind(new Intent(this, PrivilegedRootService.class), rootConnection);
        mainHandler.postDelayed(() -> {
            if (privilegedService == null) rootServiceBound = false;
        }, 1000);
    }

     private void buildUi() {
         currentTab = 0;
         LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
         content.setPadding(dp(18), dp(14), dp(18), dp(96));
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
             view.setPadding(dp(18), dp(14) + top, dp(18), dp(96) + bottom);
            return insets;
        });

         LinearLayout bar = new LinearLayout(this);
         bar.setGravity(Gravity.CENTER_VERTICAL);
         bar.setPadding(dp(14), 0, dp(10), 0);
         bar.setBackgroundResource(R.drawable.liquid_glass_panel);
          TextView title = text(t("Dsu 管理器", "Dsu Manager"), 28, Color.rgb(20,29,55));
        title.setTypeface(null, 1);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
         rootStatus = text(t("ROOT 检测中", "Checking ROOT"), 13, Color.WHITE);
        rootStatus.setGravity(Gravity.CENTER);
         rootStatus.setPadding(0, 0, 0, 0);
         rootStatus.setBackgroundResource(R.drawable.root_status_bg);
        rootStatus.setOnClickListener(v -> refreshRootStatus());
        bar.addView(rootStatus, new LinearLayout.LayoutParams(-2, dp(36)));
         LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(58));
         content.addView(bar, barLp);

        logoCard = new LinearLayout(this);
        logoCard.setOrientation(LinearLayout.VERTICAL);
        logoCard.setPadding(dp(28), dp(22), dp(28), dp(20));
        logoCard.setBackgroundResource(R.drawable.logo_bg);
        logoCard.setClipToOutline(true);
        logoCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28));
            }
        });
         logoCard.setOnTouchListener((view, event) -> {
             if (event.getAction() != MotionEvent.ACTION_UP) return true;
             if (pendingLogoClick != null) {
                 mainHandler.removeCallbacks(pendingLogoClick);
                 pendingLogoClick = null;
                  logoCard.setBackgroundResource(R.drawable.logo_bg);
                  toast(t("已恢复默认背景图", "Default background restored"));
             } else {
                 pendingLogoClick = () -> { pendingLogoClick = null; chooseImage(); };
                 mainHandler.postDelayed(pendingLogoClick, 280);
             }
             return true;
         });
         gsiStatus = text(rootAuthorized ? t("正在读取动态系统状态...", "Reading Dynamic System status...")
                  : t("需要 ROOT 权限", "ROOT access required"), 21, Color.WHITE);
         gsiStatus.setTypeface(null, 1);
         logoCard.addView(gsiStatus, new LinearLayout.LayoutParams(-1, dp(52)));
         TextView hint = text(t("点击卡片更换背景图片", "Tap to change background image"), 12, 0xB8FFFFFF);
         logoCard.addView(hint);
         FrameLayout logoMark = new FrameLayout(this);
         TextView logoDepth = text("小你", 32, 0x66101A44);
         logoDepth.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
         logoDepth.setTypeface(null, 1);
         logoDepth.setTranslationX(dp(4));
         logoDepth.setTranslationY(dp(5));
         logoMark.addView(logoDepth, new FrameLayout.LayoutParams(-1, dp(50)));
         TextView logoExtrusion = text("小你", 32, 0xFF183B91);
         logoExtrusion.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
         logoExtrusion.setTypeface(null, 1);
         logoExtrusion.setTranslationX(dp(2));
         logoExtrusion.setTranslationY(dp(2));
         logoMark.addView(logoExtrusion, new FrameLayout.LayoutParams(-1, dp(50)));
         TextView logoFace = text("小你", 32, Color.WHITE);
         logoFace.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
         logoFace.setTypeface(null, 1);
         logoFace.setShadowLayer(dp(2), 0, dp(1), 0xCC07142E);
         logoMark.addView(logoFace, new FrameLayout.LayoutParams(-1, dp(50)));
         LinearLayout.LayoutParams logoMarkLp = new LinearLayout.LayoutParams(-1, dp(50));
         logoMarkLp.gravity = Gravity.RIGHT;
         logoCard.addView(logoMark, logoMarkLp);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, dp(170));
        cardLp.setMargins(0, dp(14), 0, dp(16));
        cardLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(logoCard, cardLp);

         LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
          String[] labels = english
                  ? new String[]{"Check GSI status", "Install GSI", "Reboot to DSU", "Remove installed GSI", "Manage installed images"}
                  : new String[]{"检测 GSI 状态", "安装 GSI", "重启到 DSU", "撤销已安装 GSI", "管理已安装镜像"};
           int[] backgrounds = {R.drawable.button_blue, R.drawable.button_green, R.drawable.button_orange, R.drawable.button_purple, R.drawable.button_blue};
         actionButtons = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextColor(Color.WHITE);
            button.setTextSize(14);
            button.setAllCaps(false);
            button.setMinHeight(0);
            button.setMinWidth(0);
            button.setPadding(dp(6), 0, dp(6), 0);
            button.setBackgroundResource(backgrounds[i]);
             final int actionIndex = i;
             button.setOnClickListener(v -> action(actionIndex));
             actionButtons[i] = button;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58));
            lp.setMargins(0, dp(3), 0, dp(3));
             list.addView(button, lp);
         }
         updateActionButtons();
          detailText = text(t("点击操作后，结果会显示在这里。", "Results will appear here after an action."), 13, Color.rgb(77, 87, 105));
         detailText.setGravity(Gravity.TOP);
         detailText.setPadding(dp(14), dp(4), dp(14), dp(4));
         detailText.setBackgroundColor(Color.TRANSPARENT);
          LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, dp(40));
         detailLp.setMargins(0, 0, 0, dp(12));
         content.addView(detailText, detailLp);
         installOptionsPanel = new LinearLayout(this);
         installOptionsPanel.setOrientation(LinearLayout.VERTICAL);
         installOptionsPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
          installOptionsPanel.setBackgroundResource(R.drawable.liquid_glass_panel);
         TextView installTitle = text(t("安装 GSI 参数", "Install GSI options"), 15, Color.rgb(20, 29, 55));
         installTitle.setTypeface(null, 1);
         installOptionsPanel.addView(installTitle, new LinearLayout.LayoutParams(-1, dp(34)));
         LinearLayout sizeRow = new LinearLayout(this);
         sizeRow.setOrientation(LinearLayout.HORIZONTAL);
         installSizeButtons = new Button[4];
         String[] installSizes = {"8 GB", "16 GB", "32 GB", "64 GB"};
         for (int i = 0; i < installSizes.length; i++) {
             final int sizeIndex = i;
             Button sizeButton = new Button(this);
             installSizeButtons[i] = sizeButton;
             sizeButton.setText(installSizes[i]);
             sizeButton.setTextSize(12);
             sizeButton.setAllCaps(false);
             sizeButton.setMinWidth(0);
             sizeButton.setMinHeight(0);
             sizeButton.setPadding(0, 0, 0, 0);
             sizeButton.setOnClickListener(v -> selectInstallSize(sizeIndex, installSizes[sizeIndex]));
             LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(0, dp(44), 1);
             if (i > 0) sizeLp.setMargins(dp(5), 0, 0, 0);
             sizeRow.addView(sizeButton, sizeLp);
         }
         installOptionsPanel.addView(sizeRow, new LinearLayout.LayoutParams(-1, dp(44)));
         customInstallSizeInput = new EditText(this);
         customInstallSizeInput.setHint(t("自定义容量 GB", "Custom size GB"));
         customInstallSizeInput.setSingleLine(true);
         customInstallSizeInput.setTextSize(13);
         customInstallSizeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
          customInstallSizeInput.setBackgroundResource(R.drawable.liquid_glass_panel);
         Button customSizeButton = new Button(this);
         customSizeButton.setText(t("使用自定义", "Use custom"));
         customSizeButton.setTextSize(12);
         customSizeButton.setAllCaps(false);
         customSizeButton.setMinWidth(0);
         customSizeButton.setMinHeight(0);
         customSizeButton.setTextColor(Color.WHITE);
         customSizeButton.setBackgroundResource(R.drawable.button_teal);
         customSizeButton.setOnClickListener(v -> applyCustomInstallSize());
         LinearLayout customRow = new LinearLayout(this);
         customRow.setGravity(Gravity.CENTER_VERTICAL);
         customRow.addView(customInstallSizeInput, new LinearLayout.LayoutParams(0, dp(46), 1));
         LinearLayout.LayoutParams customButtonLp = new LinearLayout.LayoutParams(dp(108), dp(44));
         customButtonLp.setMargins(dp(6), 0, 0, 0);
         customRow.addView(customSizeButton, customButtonLp);
         LinearLayout.LayoutParams customRowLp = new LinearLayout.LayoutParams(-1, dp(52));
         customRowLp.setMargins(0, dp(8), 0, 0);
         installOptionsPanel.addView(customRow, customRowLp);
         LinearLayout zipRow = new LinearLayout(this);
         zipRow.setGravity(Gravity.CENTER_VERTICAL);
         Button chooseZipButton = new Button(this);
         chooseZipButton.setText(t("选择 ZIP", "Choose ZIP"));
         chooseZipButton.setTextSize(12);
         chooseZipButton.setAllCaps(false);
         chooseZipButton.setMinWidth(0);
         chooseZipButton.setMinHeight(0);
         chooseZipButton.setTextColor(Color.WHITE);
         chooseZipButton.setBackgroundResource(R.drawable.button_blue);
         chooseZipButton.setOnClickListener(v -> chooseZip());
         zipRow.addView(chooseZipButton, new LinearLayout.LayoutParams(dp(112), dp(44)));
         installZipLabel = text(t("尚未选择 ZIP", "No ZIP selected"), 12, Color.rgb(77, 87, 105));
         installZipLabel.setSingleLine(true);
         installZipLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
         LinearLayout.LayoutParams zipLabelLp = new LinearLayout.LayoutParams(0, dp(44), 1);
         zipLabelLp.setMargins(dp(8), 0, 0, 0);
         zipRow.addView(installZipLabel, zipLabelLp);
         installOptionsPanel.addView(zipRow, new LinearLayout.LayoutParams(-1, dp(48)));
         confirmInstallButton = new Button(this);
         confirmInstallButton.setText(t("确定安装 ZIP", "Install ZIP"));
         confirmInstallButton.setTextSize(13);
         confirmInstallButton.setAllCaps(false);
         confirmInstallButton.setMinHeight(0);
         confirmInstallButton.setTextColor(Color.WHITE);
         confirmInstallButton.setEnabled(false);
         confirmInstallButton.setBackgroundResource(R.drawable.button_green);
         confirmInstallButton.setOnClickListener(v -> confirmInstallZip());
         LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(-1, dp(46));
         confirmLp.setMargins(0, dp(8), 0, 0);
         installOptionsPanel.addView(confirmInstallButton, confirmLp);
         installOptionsPanel.setVisibility(View.GONE);
         content.addView(installOptionsPanel, new LinearLayout.LayoutParams(-1, -2));
         selectInstallSize(1, "16 GB");

         installPanel = new LinearLayout(this);
        installPanel.setOrientation(LinearLayout.VERTICAL);
        installPanel.setPadding(dp(14), dp(8), dp(14), dp(8));
         installPanel.setBackgroundResource(R.drawable.progress_bg);
         installStage = text(t("安装进度", "Installation progress"), 13, Color.rgb(40, 50, 70));
        installPanel.addView(installStage, new LinearLayout.LayoutParams(-1, dp(26)));
        installProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
         installProgress.setMax(100);
         installProgress.setProgressDrawable(getDrawable(R.drawable.progress_bar));
        installPanel.addView(installProgress, new LinearLayout.LayoutParams(-1, dp(8)));
        installPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(52));
        progressLp.setMargins(0, dp(8), 0, 0);
         content.addView(installPanel, progressLp);
         imageManagementPanel = new LinearLayout(this);
         imageManagementPanel.setOrientation(LinearLayout.VERTICAL);
         imageManagementPanel.setPadding(dp(14), dp(10), dp(14), dp(10));
          imageManagementPanel.setBackgroundResource(R.drawable.image_management_bg);
          imageManagementPanel.setVisibility(View.GONE);
          LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
          listLp.setMargins(0, 0, 0, dp(12));
          content.addView(list, listLp);
          content.addView(imageManagementPanel, new LinearLayout.LayoutParams(-1, -2));
          homeScroll = new ScrollView(this);
          homeScroll.setFillViewport(true);
          homeScroll.setBackgroundColor(Color.TRANSPARENT);
          homeScroll.addView(content);
          pageHost = new FrameLayout(this);
          FrameLayout.LayoutParams homeParams = new FrameLayout.LayoutParams(-1, -1);
          pageHost.addView(homeScroll, homeParams);
         FrameLayout root = new FrameLayout(this);
          root.setBackgroundResource(R.drawable.liquid_backdrop);
          root.setOnApplyWindowInsetsListener((view, insets) -> {
              view.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
              if (bottomNavigation != null) {
                 ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) bottomNavigation.getLayoutParams();
                 margins.bottomMargin = dp(14) + insets.getSystemWindowInsetBottom();
                 bottomNavigation.setLayoutParams(margins);
             }
             return insets;
         });
         bottomNavigation = buildBottomNavigation();
         root.addView(pageHost, new FrameLayout.LayoutParams(-1, -1));
         root.addView(bottomNavigation, bottomNavigationParams());
         root.post(() -> applySystemInsets(root, root.getRootWindowInsets()));
         setContentView(root);
     }

      private FrameLayout.LayoutParams bottomNavigationParams() {
          FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM);
          params.setMargins(dp(30), 0, dp(30), dp(16));
         return params;
     }

      private FrameLayout buildBottomNavigation() {
          FrameLayout navigation = new FrameLayout(this);
         navigation.setClipChildren(false);
         navigation.setClipToPadding(false);
          navigation.setPadding(dp(2), dp(2), dp(2), dp(2));
           navigation.setBackgroundResource(R.drawable.navigation_glass_bg);
          navigation.setElevation(0f);
          LinearLayout items = new LinearLayout(this);
         items.setGravity(Gravity.CENTER);
         items.setClipChildren(false);
         items.setClipToPadding(false);
          navigation.addView(items, new FrameLayout.LayoutParams(-1, -1));
          bottomNavigationItems = items;
         addNavigationItem(items, t("首页", "Home"), 0, v -> selectTab(0));
         addNavigationItem(items, t("设置", "Settings"), 1, v -> selectTab(1));
         addNavigationItem(items, t("关于", "About"), 2, v -> selectTab(2));
           addNavigationItem(items, t("终端", "Terminal"), 3, v -> selectTab(3));
          liquidIndicator = new LiquidGlassIndicator(this);
          liquidIndicator.setElevation(dp(4));
          navigation.addView(liquidIndicator, new FrameLayout.LayoutParams(dp(62), dp(48)));
         navigation.post(() -> moveLiquidIndicator(0, false));
         return navigation;
     }

     private void addNavigationItem(LinearLayout navigation, String label, int tab, View.OnClickListener listener) {
         TextView item = new TextView(this);
         item.setText(label);
         item.setTextSize(12);
         item.setGravity(Gravity.CENTER);
         item.setSingleLine(true);
         item.setIncludeFontPadding(false);
         item.setEllipsize(null);
           item.setTextColor(tab == 0 ? 0xff17334f : 0xfff8fbff);
          item.setShadowLayer(dp(2), 0, dp(1), tab == 0 ? 0x55ffffff : 0x66233c50);
         item.setPadding(0, 0, 0, 0);
          item.setBackground(null);
          item.setTag(tab);
          item.setStateListAnimator(null);
          item.setOnTouchListener((view, event) -> {
              if (event.getAction() == MotionEvent.ACTION_DOWN) {
                  pressLiquidIndicator(true);
              } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                  pressLiquidIndicator(false);
              }
              return false;
          });
          item.setOnClickListener(listener);
         LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(64), 1);
          itemParams.setMargins(dp(2), 0, dp(2), 0);
         navigation.addView(item, itemParams);
     }

     private Drawable glassBackground(boolean selected) {
         GradientDrawable background = new GradientDrawable();
         background.setColor(selected ? 0xff243b73 : 0x66ffffff);
         background.setCornerRadius(dp(24));
         background.setStroke(dp(1), selected ? 0x66243b73 : 0x99ffffff);
         return background;
     }

      private void pressLiquidIndicator(boolean pressed) {
          if (liquidIndicator == null) return;
          liquidIndicator.animate().cancel();
          liquidIndicator.animate()
                  .scaleX(pressed ? 1.16f : 1f)
                  .scaleY(pressed ? 1.16f : 1f)
                  .setDuration(pressed ? 110 : 260)
                  .setInterpolator(pressed
                          ? new android.view.animation.DecelerateInterpolator()
                          : new android.view.animation.OvershootInterpolator(1.4f))
                  .start();
          if (liquidIndicator instanceof LiquidGlassIndicator) {
              ((LiquidGlassIndicator) liquidIndicator).setLiquidPressed(pressed);
          }
      }

     private void scrollToTop() {
         if (homeScroll != null) homeScroll.smoothScrollTo(0, 0);
     }

      private void selectTab(int tab) {
           if (tab == currentTab) {
               if (tab == 0) scrollToTop();
               if (tab == 3) refreshMorePage();
               return;
           }
           View next = tab == 0 ? homeScroll : tab == 1 ? buildSettingsPage() : tab == 2 ? buildAboutPage() : buildMorePage();
         if (tab != 0) {
             ScrollView pageScroll = new ScrollView(this);
             pageScroll.setFillViewport(true);
             pageScroll.addView(next);
             next = pageScroll;
         }
         pageHost.removeAllViews();
         FrameLayout.LayoutParams nextParams = new FrameLayout.LayoutParams(-1, -1);
          pageHost.addView(next, nextParams);
         next.setAlpha(0f);
         next.setTranslationY(dp(18));
         next.animate().alpha(1f).translationY(0f).setDuration(360).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
         animateNavigation(tab);
          currentTab = tab;
      }

      private void refreshMorePage() {
          if (pageHost == null) return;
          pageHost.removeAllViews();
          ScrollView pageScroll = new ScrollView(this);
          pageScroll.setFillViewport(true);
          pageScroll.addView(buildMorePage());
          pageHost.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));
      }

      private void animateNavigation(int selectedTab) {
         if (bottomNavigation == null) return;
         moveLiquidIndicator(selectedTab, true);
           ViewGroup items = bottomNavigationItems;
         for (int i = 0; i < items.getChildCount(); i++) {
              View item = items.getChildAt(i);
              boolean selected = i == selectedTab;
                  if (item instanceof TextView) {
                      TextView label = (TextView) item;
                      label.setTextColor(selected ? 0xff17334f : 0xfff8fbff);
                      label.setShadowLayer(dp(2), 0, dp(1), selected ? 0x66ffffff : 0x66233c50);
                  }
          }
     }

      private void moveLiquidIndicator(int selectedTab, boolean animated) {
         if (liquidIndicator == null || bottomNavigation == null) return;
          ViewGroup items = bottomNavigationItems;
          if (items.getChildCount() == 0) return;
          View target = items.getChildAt(selectedTab);
           int indicatorHeight = dp(48);
           int indicatorWidth = dp(62);
           int targetLeft = items.getLeft() + target.getLeft() + (target.getWidth() - indicatorWidth) / 2;
         FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) liquidIndicator.getLayoutParams();
          params.width = indicatorWidth;
          params.height = indicatorHeight;
            params.topMargin = dp(8);
         if (liquidIndicatorLeft < 0 || !animated) {
             liquidIndicatorLeft = targetLeft;
             params.leftMargin = targetLeft;
             liquidIndicator.setTranslationX(0f);
             liquidIndicator.setLayoutParams(params);
             return;
         }
         int previousLeft = liquidIndicatorLeft;
         params.leftMargin = previousLeft;
         liquidIndicator.setTranslationX(0f);
         liquidIndicator.setLayoutParams(params);
         liquidIndicatorLeft = targetLeft;
           liquidIndicator.setPivotX(indicatorWidth / 2f);
           liquidIndicator.setPivotY(indicatorHeight / 2f);
          ValueAnimator flow = ValueAnimator.ofFloat(0f, 1f);
          flow.setDuration(560);
          flow.setInterpolator(new android.view.animation.PathInterpolator(.16f, .84f, .22f, 1f));
          flow.addUpdateListener(animation -> {
              float progress = (Float) animation.getAnimatedValue();
              float move = targetLeft - previousLeft;
               float stretch = progress < .38f ? progress / .38f : 1f - (progress - .38f) / .62f;
               float settle = progress < .72f ? 0f : (progress - .72f) / .28f;
               liquidIndicator.setTranslationX(move * progress);
               liquidIndicator.setScaleX(1f + .34f * Math.max(0f, stretch) - .06f * settle);
               liquidIndicator.setScaleY(1f + .20f * Math.max(0f, stretch) - .08f * settle);
          });
          flow.addListener(new android.animation.AnimatorListenerAdapter() {
              @Override public void onAnimationEnd(android.animation.Animator animation) {
                  FrameLayout.LayoutParams settled = (FrameLayout.LayoutParams) liquidIndicator.getLayoutParams();
                  settled.leftMargin = targetLeft;
                  liquidIndicator.setTranslationX(0f);
                  liquidIndicator.setScaleX(1f);
                  liquidIndicator.setScaleY(1f);
                  liquidIndicator.setLayoutParams(settled);
              }
          });
          flow.start();
     }

     private void applySystemInsets(View root, android.view.WindowInsets insets) {
         if (insets == null) return;
         int top = insets.getSystemWindowInsetTop();
         int bottom = insets.getSystemWindowInsetBottom();
         root.setPadding(0, top, 0, 0);
         if (bottomNavigation != null) {
             ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) bottomNavigation.getLayoutParams();
             margins.bottomMargin = dp(14) + bottom;
             bottomNavigation.setLayoutParams(margins);
         }
     }

     private LinearLayout page(String title) {
         LinearLayout page = new LinearLayout(this);
         page.setOrientation(LinearLayout.VERTICAL);
         page.setPadding(dp(18), dp(14), dp(18), dp(96));
          page.setBackgroundResource(R.drawable.liquid_backdrop);
         page.setOnApplyWindowInsetsListener((view, insets) -> {
             view.setPadding(dp(18), dp(14) + insets.getSystemWindowInsetTop(), dp(18), dp(96) + insets.getSystemWindowInsetBottom());
             return insets;
         });
          TextView heading = text(title, 28, Color.WHITE);
         heading.setTypeface(null, 1);
         heading.setPadding(dp(14), 0, dp(14), 0);
         page.addView(heading, new LinearLayout.LayoutParams(-1, dp(58)));
         return page;
      }

      private LinearLayout buildMorePage() {
          LinearLayout content = new LinearLayout(this);
          content.setOrientation(LinearLayout.VERTICAL);
          content.setPadding(dp(20), dp(18), dp(20), dp(32));
           content.setBackgroundResource(R.drawable.liquid_backdrop);

          TextView title = new TextView(this);
          title.setText("更多功能");
          title.setTextSize(24);
           title.setTextColor(Color.WHITE);
          title.setTypeface(null, 1);
          title.setGravity(Gravity.CENTER_VERTICAL);
          title.setPadding(dp(12), 0, 0, 0);
           title.setBackgroundResource(R.drawable.liquid_glass_panel);
          content.addView(title, new LinearLayout.LayoutParams(-1, dp(58)));

          TextView intro = new TextView(this);
          intro.setText("工具箱\n扩展设备能力，独立管理 Linux 用户空间");
          intro.setTextSize(15);
           intro.setTextColor(0xffd8e7f4);
          intro.setPadding(dp(8), dp(22), 0, dp(12));
          content.addView(intro);
          content.addView(moreActionCard(android.R.drawable.ic_menu_manage, "ARM64 Linux 终端", "在线云端下载，支持 ROOT chroot 运行", 0xff198a9b,
                  () -> startActivity(new Intent(this, LinuxTerminalActivity.class))));
          content.addView(moreActionCard(android.R.drawable.ic_menu_upload, "本地安装 rootfs", "导入 .tar.gz 或 .tar.xz 压缩包作为本地终端环境", 0xff7651b5,
                  this::pickMoreRootfs));

          boolean found = false;
          for (LinuxImages.Image image : LinuxImages.ALL) {
              if (!LinuxImages.hasUsableShell(LinuxImages.environment(this, image))) continue;
              found = true;
              content.addView(moreEnvironmentCard(image));
          }
          TextView tip = new TextView(this);
          tip.setText(found ? "已安装环境可以直接进入终端。环境管理和软件包操作会在对应终端内完成。" : "云端镜像下载完成后，已安装环境会显示在这里。\n点击入口即可进入终端。");
          tip.setTextSize(13);
           tip.setTextColor(0xffb7c8d8);
          tip.setPadding(dp(8), dp(16), dp(8), 0);
          content.addView(tip);
          return content;
      }

      private LinearLayout moreActionCard(int icon, String heading, String subtitle, int color, Runnable action) {
          LinearLayout card = new LinearLayout(this);
          card.setGravity(Gravity.CENTER_VERTICAL);
          card.setPadding(dp(16), dp(13), dp(14), dp(13));
           card.setBackgroundResource(R.drawable.liquid_glass_panel);
          ImageView mark = new ImageView(this);
          mark.setImageResource(icon);
          mark.setColorFilter(Color.WHITE);
          mark.setPadding(dp(8), dp(8), dp(8), dp(8));
          mark.setBackgroundResource(color == 0xff7651b5 ? R.drawable.icon_tile_purple : R.drawable.icon_tile_teal);
          card.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));
          LinearLayout words = new LinearLayout(this);
          words.setOrientation(LinearLayout.VERTICAL);
          words.setPadding(dp(16), 0, dp(8), 0);
          TextView name = new TextView(this);
          name.setText(heading);
          name.setTextSize(16);
          name.setTextColor(0xff182b54);
          name.setTypeface(null, 1);
          words.addView(name, new LinearLayout.LayoutParams(-1, dp(32)));
          TextView detail = new TextView(this);
          detail.setText(subtitle);
          detail.setTextSize(12);
          detail.setTextColor(0xff5d6b84);
          words.addView(detail, new LinearLayout.LayoutParams(-1, dp(42)));
          card.addView(words, new LinearLayout.LayoutParams(0, dp(78), 1));
          TextView arrow = new TextView(this);
          arrow.setText("›");
          arrow.setTextSize(28);
          arrow.setTextColor(color);
          arrow.setGravity(Gravity.CENTER);
          card.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(56)));
          card.setOnClickListener(v -> action.run());
          LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(106));
          params.setMargins(0, dp(12), 0, 0);
          card.setLayoutParams(params);
          return card;
      }

      private LinearLayout moreEnvironmentCard(LinuxImages.Image image) {
          LinearLayout card = new LinearLayout(this);
          card.setOrientation(LinearLayout.VERTICAL);
           card.setPadding(dp(12), dp(14), dp(12), dp(14));
           card.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout heading = new LinearLayout(this);
          heading.setGravity(Gravity.CENTER_VERTICAL);
          LinearLayout nameColumn = new LinearLayout(this);
          nameColumn.setOrientation(LinearLayout.VERTICAL);
          TextView name = new TextView(this);
          name.setText(image.name);
          name.setTextSize(18);
          name.setTextColor(0xff14233f);
          name.setTypeface(null, 1);
          nameColumn.addView(name, new LinearLayout.LayoutParams(-1, dp(30)));
          TextView status = new TextView(this);
          status.setText("已安装 · 可直接进入终端");
          status.setTextSize(13);
          status.setTextColor(0xff61708a);
          nameColumn.addView(status, new LinearLayout.LayoutParams(-1, dp(26)));
          heading.addView(nameColumn, new LinearLayout.LayoutParams(-1, dp(56)));
          card.addView(heading);
          LinearLayout actions = new LinearLayout(this);
          actions.setGravity(Gravity.CENTER_VERTICAL);
          Button enter = moreButton("进入终端", R.drawable.button_green, Color.WHITE);
          enter.setOnClickListener(v -> openMoreEnvironment(image));
          actions.addView(enter, new LinearLayout.LayoutParams(0, dp(44), 1));
          Button remove = moreButton("移除环境", R.drawable.remove_pill, 0xffc62828);
          remove.setOnClickListener(v -> confirmMoreRemove(image));
          LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(44), 1);
          removeParams.setMargins(dp(10), 0, 0, 0);
          actions.addView(remove, removeParams);
          LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(44));
          actionsParams.setMargins(0, dp(12), 0, 0);
          card.addView(actions, actionsParams);
          LinearLayout storage = new LinearLayout(this);
          storage.setGravity(Gravity.CENTER_VERTICAL);
          TextView usage = new TextView(this);
          usage.setText("正在计算存储占用...");
          usage.setTextSize(12);
          usage.setTextColor(0xff52617b);
           usage.setMaxLines(2);
           storage.addView(usage, new LinearLayout.LayoutParams(0, -2, 1));
          Button clear = moreButton("清理系统数据", R.drawable.remove_pill, 0xffc62828);
          clear.setOnClickListener(v -> confirmMoreClear(image));
           storage.addView(clear, new LinearLayout.LayoutParams(0, dp(44), .95f));
          Button archive = moreButton("清理下载包", R.drawable.remove_pill, 0xff7651b5);
          archive.setOnClickListener(v -> confirmMoreArchive(image));
           LinearLayout.LayoutParams archiveParams = new LinearLayout.LayoutParams(0, dp(44), .95f);
          archiveParams.setMargins(dp(6), 0, 0, 0);
          storage.addView(archive, archiveParams);
           LinearLayout.LayoutParams storageParams = new LinearLayout.LayoutParams(-1, -2);
          storageParams.setMargins(0, dp(12), 0, 0);
          card.addView(storage, storageParams);
          LinearLayout packages = new LinearLayout(this);
          packages.setGravity(Gravity.CENTER_VERTICAL);
          TextView packageCount = new TextView(this);
          packageCount.setText("正在读取用户安装包...");
          packageCount.setTextSize(12);
          packageCount.setTextColor(0xff52617b);
           packageCount.setMaxLines(2);
           packages.addView(packageCount, new LinearLayout.LayoutParams(0, -2, 1));
          Button packageButton = moreButton("查看并卸载", R.drawable.remove_pill, 0xff7651b5);
          packageButton.setOnClickListener(v -> showMorePackages(image));
           packages.addView(packageButton, new LinearLayout.LayoutParams(0, dp(44), .95f));
           LinearLayout.LayoutParams packagesParams = new LinearLayout.LayoutParams(-1, -2);
          packagesParams.setMargins(0, dp(10), 0, 0);
          card.addView(packages, packagesParams);
          moreWorker.execute(() -> {
              String size = "系统数据 " + formatBytes(LinuxImages.storageBytes(LinuxImages.environment(this, image))) + " · 下载包 " + formatBytes(LinuxImages.storageBytes(LinuxImages.archive(this, image)));
              java.util.List<String> packagesFound = morePackages(image);
              runOnUiThread(() -> { usage.setText(size); packageCount.setText("用户安装包：" + packagesFound.size() + " 个"); });
          });
          LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
          params.setMargins(0, dp(14), 0, 0);
          card.setLayoutParams(params);
          return card;
      }

      private Button moreButton(String value, int background, int color) {
          Button button = new Button(this);
          button.setText(value);
          button.setTextSize(11);
          button.setAllCaps(false);
          button.setTextColor(color);
          button.setMinWidth(0);
          button.setMinHeight(0);
          button.setBackgroundResource(background);
          return button;
      }

      private java.util.List<String> morePackages(LinuxImages.Image image) {
          java.util.List<String> result = new java.util.ArrayList<>();
          java.io.File record = new java.io.File(LinuxImages.environment(this, image), "var/lib/linux-dsu/user-packages");
          try { for (String line : java.nio.file.Files.readAllLines(record.toPath(), java.nio.charset.StandardCharsets.UTF_8)) { String value = line.trim(); if (!value.isEmpty() && !result.contains(value)) result.add(value); } } catch (Exception ignored) { }
          return result;
      }

      private void showMorePackages(LinuxImages.Image image) {
          moreWorker.execute(() -> { java.util.List<String> packages = morePackages(image); runOnUiThread(() -> { String message = packages.isEmpty() ? "没有检测到用户安装包。" : android.text.TextUtils.join("\n", packages); new AlertDialog.Builder(this).setTitle(image.name + " 用户安装包").setMessage(message).setPositiveButton("关闭", null).show(); }); });
      }

      private void confirmMoreClear(LinuxImages.Image image) {
          new AlertDialog.Builder(this).setTitle("清理系统数据").setMessage("会清空已解压系统数据，保留下载包，后续可以从下载包恢复。").setNegativeButton("取消", null).setPositiveButton("清理", (dialog, which) -> moreWorker.execute(() -> { removeMoreFiles(LinuxImages.environment(this, image), null); runOnUiThread(() -> { if (currentTab == 3) selectTab(3); }); })).show();
      }

      private void confirmMoreArchive(LinuxImages.Image image) {
          new AlertDialog.Builder(this).setTitle("清理下载包").setMessage("会删除下载压缩包，已安装系统数据会保留。").setNegativeButton("取消", null).setPositiveButton("清理", (dialog, which) -> moreWorker.execute(() -> { deleteMoreTree(LinuxImages.archive(this, image)); runOnUiThread(() -> { if (currentTab == 3) selectTab(3); }); })).show();
      }

      private void confirmMoreRemove(LinuxImages.Image image) {
          new AlertDialog.Builder(this).setTitle("移除终端环境").setMessage("将删除 " + image.name + " 的全部系统数据和下载包。").setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) -> moreWorker.execute(() -> {
              LinuxTerminalActivity.stopRunningEnvironment(image.id());
              String root = LinuxImages.environment(this, image).getAbsolutePath().replace("'", "'\\''");
              String archive = LinuxImages.archive(this, image).getAbsolutePath().replace("'", "'\\''");
              try {
                  java.lang.Process process = new ProcessBuilder("/system/bin/su", "-c", "rm -rf '" + root + "' '" + archive + "'").start();
                  process.waitFor();
              } catch (Exception ignored) { }
              runOnUiThread(() -> { if (currentTab == 3) selectTab(3); Toast.makeText(this, "环境已移除", Toast.LENGTH_SHORT).show(); });
          })).show();
      }

      private void removeMoreFiles(java.io.File root, java.io.File archive) {
          if (root != null) deleteMoreTree(root);
          if (archive != null) deleteMoreTree(archive);
      }

      private boolean deleteMoreTree(java.io.File file) {
          if (file == null || !file.exists()) return true;
          if (file.isDirectory()) { java.io.File[] children = file.listFiles(); if (children != null) for (java.io.File child : children) deleteMoreTree(child); }
          try { return java.nio.file.Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { return false; }
      }

      private void openMoreEnvironment(LinuxImages.Image image) {
          Intent intent = new Intent(this, LinuxTerminalActivity.terminalActivity(image.id()));
          intent.putExtra("open_image", image.id());
          intent.putExtra("open_terminal", true);
          intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
          startActivity(intent);
      }

      private void pickMoreRootfs() {
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          intent.setType("*/*");
          intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/gzip", "application/x-gzip", "application/x-xz", "application/octet-stream"});
          startActivityForResult(intent, PICK_ROOTFS);
      }

      private LinearLayout buildSettingsPage() {
         LinearLayout page = page(t("设置", "Settings"));
         LinearLayout card = new LinearLayout(this);
         card.setOrientation(LinearLayout.VERTICAL);
         card.setPadding(dp(14), dp(10), dp(14), dp(10));
          card.setBackgroundResource(R.drawable.liquid_glass_panel);
         TextView language = text(t("语言", "Language"), 16, Color.rgb(20, 29, 55));
         language.setTypeface(null, 1);
         card.addView(language, new LinearLayout.LayoutParams(-1, dp(38)));
         RadioGroup group = new RadioGroup(this);
         group.setOrientation(RadioGroup.VERTICAL);
         group.setPadding(dp(8), dp(4), dp(8), dp(4));
          group.setBackgroundResource(R.drawable.liquid_glass_panel);
         RadioButton system = new RadioButton(this);
         system.setId(100);
         system.setText(t("系统语言", "Use system language"));
         RadioButton chinese = new RadioButton(this);
         chinese.setId(101);
         chinese.setText("中文");
         RadioButton englishButton = new RadioButton(this);
         englishButton.setId(102);
         englishButton.setText("English");
         system.setTextSize(14);
         chinese.setTextSize(14);
         englishButton.setTextSize(14);
         group.addView(system, new RadioGroup.LayoutParams(-1, dp(48)));
         group.addView(chinese, new RadioGroup.LayoutParams(-1, dp(48)));
         group.addView(englishButton, new RadioGroup.LayoutParams(-1, dp(48)));
         group.check(languageMode == 2 ? 102 : languageMode == 1 ? 101 : 100);
         system.setOnClickListener(v -> saveLanguage(0));
         chinese.setOnClickListener(v -> saveLanguage(1));
         englishButton.setOnClickListener(v -> saveLanguage(2));
          card.addView(group, new LinearLayout.LayoutParams(-1, dp(160)));
           LinearLayout.LayoutParams languageParams = new LinearLayout.LayoutParams(-1, -2);
           languageParams.setMargins(0, 0, 0, dp(18));
           page.addView(card, languageParams);
          LinearLayout screenCard = new LinearLayout(this);
          screenCard.setGravity(Gravity.CENTER_VERTICAL);
          screenCard.setPadding(dp(14), dp(6), dp(10), dp(6));
          screenCard.setBackgroundResource(R.drawable.liquid_glass_panel);
          Switch keepScreen = new Switch(this);
          keepScreen.setText(t("保持亮屏", "Keep screen on"));
          keepScreen.setTextColor(Color.rgb(20, 29, 55));
          keepScreen.setTextSize(14);
          keepScreen.setChecked(keepScreenOn);
          keepScreen.setOnCheckedChangeListener((button, checked) -> {
              keepScreenOn = checked;
              getPreferences(MODE_PRIVATE).edit().putBoolean("keep_screen_on", checked).apply();
              applyKeepScreenOn();
          });
          screenCard.addView(keepScreen, new LinearLayout.LayoutParams(-1, dp(48)));
          LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(-1, dp(60));
          screenParams.setMargins(0, 0, 0, dp(18));
          page.addView(screenCard, screenParams);
         TextView updateTitle = text(t("更新", "Updates"), 16, Color.rgb(35, 126, 91));
         updateTitle.setTypeface(null, 1);
         updateTitle.setPadding(dp(14), 0, dp(14), 0);
         updateTitle.setBackgroundResource(R.drawable.settings_update_title);
         LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(38));
         titleParams.setMargins(0, dp(18), 0, dp(8));
         page.addView(updateTitle, titleParams);
         embeddedUpdateStatus = text(t("点击检查最新版本。", "Tap check for the latest release."), 13, Color.rgb(80, 88, 105));
         embeddedUpdateStatus.setPadding(dp(14), dp(10), dp(14), dp(10));
          embeddedUpdateStatus.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(56));
          statusParams.setMargins(0, 0, 0, dp(10));
          page.addView(embeddedUpdateStatus, statusParams);
         embeddedReleaseNotes = text("", 13, Color.rgb(80, 88, 105));
         embeddedReleaseNotes.setGravity(Gravity.TOP | Gravity.START);
         embeddedReleaseNotes.setPadding(dp(14), dp(10), dp(14), dp(10));
          embeddedReleaseNotes.setBackgroundResource(R.drawable.liquid_glass_panel);
         embeddedReleaseNotes.setVisibility(View.GONE);
          LinearLayout.LayoutParams notesParams = new LinearLayout.LayoutParams(-1, dp(180));
          notesParams.setMargins(0, 0, 0, dp(10));
          page.addView(embeddedReleaseNotes, notesParams);
         Button check = new Button(this);
         check.setText(t("检查更新", "Check for updates"));
         check.setAllCaps(false);
          check.setBackgroundResource(R.drawable.liquid_glass_panel);
         check.setOnClickListener(v -> checkEmbeddedUpdates());
          LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, dp(52));
          checkParams.setMargins(0, 0, 0, dp(10));
          page.addView(check, checkParams);
         embeddedDownloadButton = new Button(this);
         embeddedDownloadButton.setText(t("下载最新 APK", "Download latest APK"));
         embeddedDownloadButton.setAllCaps(false);
         embeddedDownloadButton.setTextColor(Color.WHITE);
         embeddedDownloadButton.setBackgroundResource(R.drawable.button_green);
         embeddedDownloadButton.setVisibility(View.GONE);
          LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(-1, dp(52));
          downloadParams.setMargins(0, 0, 0, dp(10));
          page.addView(embeddedDownloadButton, downloadParams);
         embeddedDownloadHint = text(t("下载地址是 GitHub，网页打不开时可以使用魔法下载最新版。", "Download address: GitHub. Use a proxy or VPN when GitHub cannot be opened."), 12, Color.rgb(80, 88, 105));
         embeddedDownloadHint.setPadding(dp(14), dp(8), dp(14), dp(8));
          embeddedDownloadHint.setBackgroundResource(R.drawable.liquid_glass_panel);
         embeddedDownloadHint.setVisibility(View.GONE);
          LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, dp(58));
          hintParams.setMargins(0, 0, 0, dp(10));
          page.addView(embeddedDownloadHint, hintParams);
         TextView thanksTitle = text(t("感谢", "Acknowledgements"), 16, Color.rgb(181, 103, 39));
         thanksTitle.setTypeface(null, 1);
         thanksTitle.setPadding(dp(14), 0, dp(14), 0);
         thanksTitle.setBackgroundResource(R.drawable.settings_thanks_title);
         LinearLayout.LayoutParams thanksTitleParams = new LinearLayout.LayoutParams(-1, dp(38));
         thanksTitleParams.setMargins(0, dp(18), 0, dp(8));
         page.addView(thanksTitle, thanksTitleParams);
          TextView thanks = text(t("感谢酷安用户及 GitHub 用户 yangFenTuoZi 开发 Dsu 功能修改 img 无损替换功能。\n如有侵权，请联系作者，我们会及时删除相关内容。", "Thanks to Coolapk user and GitHub user yangFenTuoZi for developing the Dsu img lossless replacement feature.\nIf any content infringes your rights, please contact the author and it will be removed promptly."), 14, Color.rgb(80, 88, 105));
          thanks.setPadding(dp(14), dp(12), dp(14), dp(12));
           thanks.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams thanksParams = new LinearLayout.LayoutParams(-1, dp(92));
          thanksParams.setMargins(0, 0, 0, dp(10));
          page.addView(thanks, thanksParams);
          TextView version = text(t("Dsu 管理器 3.5.5", "Dsu Manager 3.5.5"), 13, Color.rgb(110, 118, 135));
          version.setPadding(dp(14), 0, dp(14), 0);
           version.setBackgroundResource(R.drawable.liquid_glass_panel);
          page.addView(version, new LinearLayout.LayoutParams(-1, dp(46)));
         return page;
      }

      private void checkEmbeddedUpdates() {
          embeddedUpdateStatus.setText(t("正在检查更新...", "Checking for updates..."));
          embeddedReleaseNotes.setVisibility(View.GONE);
          embeddedDownloadButton.setVisibility(View.GONE);
          embeddedDownloadHint.setVisibility(View.GONE);
          new Thread(() -> {
              HttpURLConnection connection = null;
              try {
                  connection = (HttpURLConnection) new java.net.URL("https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest").openConnection();
                  connection.setConnectTimeout(10000);
                  connection.setReadTimeout(10000);
                  connection.setRequestProperty("Accept", "application/vnd.github+json");
                  StringBuilder body = new StringBuilder();
                  try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                      String line;
                      while ((line = reader.readLine()) != null) body.append(line);
                  }
                  JSONObject release = new JSONObject(body.toString());
                  String version = release.optString("tag_name", "").replaceFirst("^v", "");
                  String notes = release.optString("body", "").replace("\\r\\n", "\n").replace("\\n", "\n").trim();
                  String url = release.optString("html_url", "https://github.com/955xiaolan520/Dsu-Manager/releases");
                  JSONArray assets = release.optJSONArray("assets");
                  if (assets != null) for (int i = 0; i < assets.length(); i++) {
                      JSONObject asset = assets.optJSONObject(i);
                      if (asset != null && asset.optString("name", "").endsWith(".apk") && !asset.optString("name", "").contains("debug")) {
                          url = asset.optString("browser_download_url", url);
                          break;
                      }
                  }
                  final String finalVersion = version;
                  final String finalNotes = notes;
                  final String finalUrl = url;
                  mainHandler.post(() -> {
                      boolean newer = isVersionNewer(finalVersion, "3.5.5");
                      embeddedUpdateStatus.setText(newer ? t("发现新版本: " + finalVersion, "New version available: " + finalVersion) : t("当前已是最新版本: 3.5.5", "You are using the latest version: 3.5.5"));
                      embeddedReleaseNotes.setText(t("更新内容:\n", "Release notes:\n") + (finalNotes.isEmpty() ? t("暂无更新说明。", "No release notes.") : finalNotes));
                      embeddedReleaseNotes.setVisibility(View.VISIBLE);
                      if (newer) {
                          embeddedDownloadButton.setVisibility(View.VISIBLE);
                          embeddedDownloadHint.setVisibility(View.VISIBLE);
                          embeddedDownloadButton.setOnClickListener(v -> downloadEmbeddedApk(finalUrl));
                      }
                  });
              } catch (Exception error) {
                  mainHandler.post(() -> embeddedUpdateStatus.setText(t("检查更新失败: ", "Update check failed: ") + error.getMessage()));
              } finally {
                  if (connection != null) connection.disconnect();
              }
          }).start();
      }

      private boolean isVersionNewer(String candidate, String current) {
          try {
              String[] a = candidate.split("\\."), b = current.split("\\.");
              for (int i = 0; i < Math.max(a.length, b.length); i++) {
                  int left = i < a.length ? Integer.parseInt(a[i]) : 0;
                  int right = i < b.length ? Integer.parseInt(b[i]) : 0;
                  if (left != right) return left > right;
              }
          } catch (NumberFormatException ignored) { }
          return false;
      }

      private void downloadEmbeddedApk(String url) {
          embeddedDownloadButton.setEnabled(false);
          embeddedDownloadButton.setText(t("正在下载...", "Downloading..."));
          new Thread(() -> {
              try {
                  HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
                  connection.setConnectTimeout(15000);
                  connection.setReadTimeout(30000);
                  connection.setInstanceFollowRedirects(true);
                  File apk = new File(getCacheDir(), "dsu-manager-update.apk");
                  try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(apk)) {
                      byte[] buffer = new byte[8192];
                      int count;
                      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                  }
                  Uri apkUri = UpdateFileProvider.getUriForFile(this, apk);
                  mainHandler.post(() -> {
                      embeddedDownloadButton.setEnabled(true);
                      embeddedDownloadButton.setText(t("安装已下载 APK", "Install downloaded APK"));
                      embeddedDownloadButton.setOnClickListener(v -> installApk(apkUri));
                      installApk(apkUri);
                  });
                  connection.disconnect();
              } catch (Exception error) {
                  mainHandler.post(() -> {
                      embeddedDownloadButton.setEnabled(true);
                      embeddedDownloadButton.setText(t("下载最新 APK", "Download latest APK"));
                      embeddedUpdateStatus.setText(t("下载失败: ", "Download failed: ") + error.getMessage());
                  });
              }
          }).start();
      }

      private void installApk(Uri apkUri) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
              Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
              startActivity(settings);
              return;
          }
          Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
          intent.setData(apkUri);
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          startActivity(intent);
      }

     private void saveLanguage(int mode) {
         getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("language_mode", mode).apply();
         languageMode = mode;
         english = mode == 2;
         buildUi();
     }

      private LinearLayout buildAboutPage() {
         LinearLayout page = page(t("关于 Dsu 管理器", "About Dsu Manager"));
         TextView about = text(t("Dsu GSI管理器\n\n功能说明\n本应用的 GSI 安装流程参考并使用了 DSU-Sideloader 项目的相关方案。\n\n支持安装 DSU 镜像的 img 无损替换。\n支持 system、system_ext、product、vendor、odm、my_preload 等镜像。\n替换修改后的 img 镜像之后直接开机，无需重新过开机引导。直接开机使用修复 bug 后的 Dsu 系统。\n\n使用安卓系统：\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\n安装功能参考 DSU-Sideloader 项目：\nhttps://github.com/VegaBobo/DSU-Sideloader\n\n特别感谢酷安用户及 GitHub 用户 yangFenTuoZi 开发 Dsu 功能修改 img 无损替换功能。\n如有侵权，请联系作者，我们会及时删除相关内容。\n\n作者：小你可兰\n管理器版本：3.5.5", "Dsu GSI Manager\n\nFeatures\nThe GSI installation flow uses the DSU-Sideloader project approach.\n\nSupports lossless replacement of img files for installed DSU images.\nSupports system, system_ext, product, vendor, odm, my_preload and other images.\nThe device can boot directly after replacing a modified img image without repeating the setup wizard.\n\nAndroid system components:\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\nInstallation reference:\nhttps://github.com/VegaBobo/DSU-Sideloader\n\nSpecial thanks to Coolapk user and GitHub user yangFenTuoZi for developing the Dsu img lossless replacement feature.\nIf any content infringes your rights, please contact the author and it will be removed promptly.\n\nAuthor: Xiaonikelan\nManager version: 3.5.5"), 15, Color.rgb(53, 66, 94));
         about.setGravity(Gravity.TOP);
         about.setPadding(dp(18), dp(18), dp(18), dp(18));
          about.setBackgroundResource(R.drawable.liquid_glass_panel);
         page.addView(about, new LinearLayout.LayoutParams(-1, -2));
         return page;
     }

    private void applyKeepScreenOn() {
        if (keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showAboutDialog() {
           String about = t("Dsu GSI管理器\n\n功能说明\n本应用的 GSI 安装流程参考并使用了 DSU-Sideloader 项目的相关方案。\n\n支持安装 DSU 镜像的 img 无损替换。\n支持 system、system_ext、product、vendor、odm、my_preload 等镜像。\n替换修改后的 img 镜像之后直接开机，无需重新过开机引导。直接开机使用修复 bug 后的 Dsu 系统。\n\n使用安卓系统：\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\n安装功能参考 DSU-Sideloader 项目：\nhttps://github.com/VegaBobo/DSU-Sideloader\n\n特别感谢酷安用户及 GitHub 用户 yangFenTuoZi 开发 Dsu 功能修改 img 无损替换功能。\n如有侵权，请联系作者，我们会及时删除相关内容。\n\n作者：小你可兰\n管理器版本：3.5.5",
                "Dsu GSI Manager\n\nFeatures\nThe GSI installation flow uses the DSU-Sideloader project approach.\n\nSupports lossless replacement of img files for installed DSU images.\nSupports system, system_ext, product, vendor, odm, my_preload and other images.\nThe device can boot directly after replacing a modified img image without repeating the setup wizard.\n\nAndroid system components:\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\nInstallation reference:\nhttps://github.com/VegaBobo/DSU-Sideloader\n\nSpecial thanks to Coolapk user and GitHub user yangFenTuoZi for developing the Dsu img lossless replacement feature.\nIf any content infringes your rights, please contact the author and it will be removed promptly.\n\nAuthor: Xiaonikelan\nManager version: 3.5.5");
        new AlertDialog.Builder(this)
                .setTitle(t("关于 Dsu 管理器", "About Dsu Manager"))
                .setMessage(about)
                .setPositiveButton(t("确定", "OK"), null)
                .show();
    }

    private void refreshStatus(){
         if (!rootAuthorized) {
             gsiStatus.setText(t("需要 ROOT 权限", "ROOT access required"));
             detailText.setText(t("请先授予 ROOT 权限后使用操作中心。", "Grant ROOT access before using the action center."));
             return;
         }
         detailText.setText(t("正在读取 GSI 状态...", "Reading GSI status..."));
        new Thread(() -> {
            String raw = runPrivilegedResult("/system/bin/gsi_tool", "status");
            String status = formatGsiStatus(raw);
            runOnUiThread(() -> {
                gsiStatus.setText(status);
                 if (status.equals("已安装，等待启动")) {
                     showInstalledGsiSummary();
                 } else {
                     detailText.setText(t("GSI: ", "GSI: ") + localizedStatus(status));
                 }
            });
        }).start();
    }
     private void refreshRootStatus(){
         if (rootAuthorized || rootCheckInProgress) return;
         rootCheckInProgress = true;
         rootStatus.setText(t("ROOT 检测中", "Checking ROOT"));
        new Thread(() -> {
            RootResult root = checkRoot();
            runOnUiThread(() -> {
                 rootCheckInProgress = false;
                 setRootAuthorized(root.authorized);
            });
        }).start();
    }
    private static class RootResult { final boolean authorized; final String message; RootResult(boolean ok, String text){ authorized=ok; message=text; } }
     private RootResult checkRoot(){
         if (privilegedService != null) return new RootResult(true, t("ROOT 已授权", "ROOT granted"));
         CommandResult result = runCommand("/system/bin/su", "-c", "id");
        String output = result.output.trim();
         if(result.exitCode == 0 && output.contains("uid=0")) return new RootResult(true, t("ROOT 已授权", "ROOT granted"));
         return new RootResult(false, t("ROOT 检测失败", "ROOT check failed"));
    }
     private String localizedStatus(String status) {
         if (!english) return status;
         if (status.equals("未安装")) return "Not installed";
         if (status.equals("空闲（未运行 GSI）")) return "Idle (GSI not running)";
         if (status.equals("运行中")) return "Running";
         if (status.equals("已安装，等待启动")) return "Installed, waiting to boot";
         return status;
     }
     private String formatGsiStatus(String raw){
         if(raw == null || raw.trim().isEmpty()) return "未安装";
        String[] lines = raw.trim().split("\\r?\\n");
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.matches("\\[\\d+\\].*")) continue;
            if(value.equalsIgnoreCase("normal")) return "空闲（未运行 GSI）";
            if(value.equalsIgnoreCase("running")) return "运行中";
            if(value.equalsIgnoreCase("installed")) return "已安装，等待启动";
        }
        return "未安装";
    }
    private static class CommandResult { final String output; final int exitCode; CommandResult(String text, int code){ output=text; exitCode=code; } }
    private CommandResult runCommand(String... cmd){
        try {
            java.lang.Process p=Runtime.getRuntime().exec(cmd);
            BufferedReader out=new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader err=new BufferedReader(new InputStreamReader(p.getErrorStream()));
            StringBuilder b=new StringBuilder(); String line;
            while((line=out.readLine())!=null)b.append(line).append('\n');
            while((line=err.readLine())!=null)b.append(line).append('\n');
            int code=p.waitFor(); return new CommandResult(b.toString().trim(),code);
        } catch(Exception e){ return new CommandResult(e.getMessage()==null?"命令执行失败":e.getMessage(),-1); }
    }
    private String run(String... cmd){ return runCommand(cmd).output; }
      private void action(int index){
          if (!rootAuthorized) {
              toast(t("请先授予 ROOT 权限", "Grant ROOT access first"));
              return;
          }
          switch (index) {
              case 0:
                  refreshStatus();
                  toast(t("已刷新 GSI 状态", "GSI status refreshed"));
                  return;
               case 1:
                   installOptionsPanel.setVisibility(installOptionsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                   return;
              case 2:
                  confirm(t("重启到 DSU", "Reboot to DSU"), t("设备将重启到已安装的 Dynamic System。", "The device will reboot into the installed Dynamic System."), this::bootDsu);
                  return;
              case 3:
                  confirm(t("撤销 GSI", "Remove GSI"), t("将清理当前已安装的动态系统。", "The installed Dynamic System will be removed."), this::wipeDsu);
                  return;
               case 4:
                   if (imageManagementPanel.getVisibility() == View.VISIBLE) imageManagementPanel.setVisibility(View.GONE); else showImageManagement();
                   return;
              default:
          }
      }
    private void customSize(){
        EditText input = new EditText(this);
         input.setHint(t("例如 24 或 24.5", "For example, 24 or 24.5"));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, dp(56)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                 .setTitle(t("自定义 userdata 容量", "Custom userdata size"))
                .setView(box)
                 .setPositiveButton(t("选择 GSI 安装包", "Choose GSI package"), null)
                 .setNegativeButton(t("取消", "Cancel"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = input.getText().toString().trim();
                try {
                     double gb = Double.parseDouble(value.replace(',', '.'));
                    if (gb <= 0 || gb > 128) throw new NumberFormatException();
                    dialog.dismiss();
                     userdataSizeBytes = Math.round(gb * 1024d * 1024d * 1024d);
                     pendingSizeLabel = value + " GB";
                     chooseZip();
                } catch (NumberFormatException error) {
                     input.setError(t("请输入 0 到 128 之间的容量", "Enter a size between 0 and 128"));
                }
            });
        });
        dialog.show();
        input.postDelayed(() -> {
            input.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }
     private void chooseZip(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("application/zip"); i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/octet-stream"}); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_ZIP); }
     private void selectInstallSize(int index, String label){
         selectedInstallSize = index;
         pendingSizeLabel = label;
         userdataSizeBytes = parseSizeBytes(label);
         for (int i = 0; i < installSizeButtons.length; i++) {
             installSizeButtons[i].setTextColor(i == index ? Color.WHITE : Color.rgb(40, 50, 70));
             installSizeButtons[i].setBackgroundResource(i == index ? R.drawable.button_teal : R.drawable.rounded_panel);
         }
     }
     private void applyCustomInstallSize(){
         try {
             double gb = Double.parseDouble(customInstallSizeInput.getText().toString().trim().replace(',', '.'));
             if (gb <= 0 || gb > 128) throw new NumberFormatException();
             selectedInstallSize = -1;
             pendingSizeLabel = customInstallSizeInput.getText().toString().trim() + " GB";
             userdataSizeBytes = Math.round(gb * 1024d * 1024d * 1024d);
              for (Button button : installSizeButtons) { button.setTextColor(Color.rgb(40, 50, 70)); button.setBackgroundResource(R.drawable.liquid_glass_panel); }
         } catch (NumberFormatException error) {
             customInstallSizeInput.setError(t("请输入 0 到 128 之间的容量", "Enter a size between 0 and 128"));
         }
     }
     private void confirmInstallZip(){
         if (pendingInstallZip == null) { toast(t("请先选择 ZIP 安装包", "Choose a ZIP package first")); return; }
         installOptionsPanel.setVisibility(View.GONE);
         installWithDsuSideloaderFlow(pendingInstallZip);
     }
     private void chooseImage(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_IMAGE); }
        @Override protected void onActivityResult(int r,int c,Intent d){ super.onActivityResult(r,c,d); if(c!=RESULT_OK||d==null)return; Uri u=d.getData(); if(r==PICK_IMAGE){ String path=getPath(u,"logo.img"); if(!path.isEmpty()){ Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(path); if(bitmap!=null) { logoCard.setBackground(new RoundedCropDrawable(bitmap, dp(28))); logoCard.setClipToOutline(true); } } } else if(r==PICK_ZIP){ pendingInstallZip = u; installedZipName = displayName(u); getPreferences(MODE_PRIVATE).edit().putString("installed_zip_name", installedZipName).apply(); installZipLabel.setText(installedZipName); confirmInstallButton.setEnabled(true); } else if(r==PICK_REPLACEMENT && replacementPartition != null){ replaceImage(u, replacementPartition); } else if(r==PICK_ROOTFS){ Intent intent = new Intent(this, LinuxTerminalActivity.class); intent.putExtra("local_install", true); intent.setData(u); startActivity(intent); } }
     private String displayName(Uri uri){
         try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
             if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
         } catch (Exception ignored) { }
         return uri.getLastPathSegment() == null ? t("未命名 ZIP", "Unnamed ZIP") : uri.getLastPathSegment();
     }
    private String getPath(Uri u,String name){ try { InputStream in=getContentResolver().openInputStream(u); File f=new File(getCacheDir(),name); FileOutputStream out=new FileOutputStream(f); byte[] b=new byte[8192]; int n; while((n=in.read(b))>0)out.write(b,0,n); in.close();out.close();return f.getAbsolutePath(); }catch(Exception e){return "";} }
    private void installWithDsuSideloaderFlow(Uri source){
         showInstallProgress(t("正在解析 GSI 安装包", "Parsing GSI package"), 0);
        new Thread(() -> {
            String path = "";
            try {
                path = copyInstallZip(source);
                if (path.isEmpty()) {
                     runOnUiThread(() -> finishProgress(t("无法读取 GSI 安装包", "Unable to read the GSI package")));
                    return;
                }
                runOnUiThread(() -> {
                     showInstallProgress(t("正在读取 GSI 镜像", "Reading GSI images"), 20);
                      detailText.setText(t("正在将 ZIP 内的 img 镜像直接写入 DSU\nuserdata: ", "Writing ZIP images directly to DSU\nuserdata: ") + pendingSizeLabel);
                });
                String result = installZipThroughRootService(path);
                runOnUiThread(() -> finishProgress(result));
             } catch(Exception e){ runOnUiThread(() -> finishProgress(t("安装准备失败: ", "Installation preparation failed: ")+e.getMessage())); }
            finally { if (!path.isEmpty()) new File(path).delete(); }
        }).start();
    }
    private String copyInstallZip(Uri source) {
        File target = new File(getCacheDir(), "dsu-install.zip");
        try (InputStream input = getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) return "";
            byte[] buffer = new byte[1024 * 1024];
            int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                 runOnUiThread(() -> showInstallProgress(t("正在解析 GSI 安装包", "Parsing GSI package"), 10));
                }
            return target.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }
    private String installZipThroughRootService(String path) {
        IPrivilegedService service = privilegedService;
         if (service == null) return t("ROOT Installer 尚未连接，请先完成 ROOT 授权后重试", "ROOT installer is not connected. Grant ROOT access and try again.");
        boolean started = false;
        boolean completed = false;
         try (ZipFile zip = new ZipFile(path)) {
              runOnUiThread(() -> showInstallProgress(t("正在创建 Dynamic System", "Creating Dynamic System"), 35));
              String cleanupError = service.cleanupDsuBackingImages();
              if (cleanupError != null && !cleanupError.isEmpty()) return t("清理旧 DSU 镜像失败: " + cleanupError, "Failed to clean old DSU images: " + cleanupError);
               if (!service.startInstallation(DSU_SLOT)) return t("Dynamic System 拒绝开始安装，请检查系统 Dynamic System 权限", "Dynamic System rejected the installation. Check Dynamic System permissions.");
            started = true;
              List<ZipEntry> imageEntries = new ArrayList<>();
              java.util.Enumeration<? extends ZipEntry> zipEntries = zip.entries();
              while (zipEntries.hasMoreElements()) {
                  ZipEntry candidate = zipEntries.nextElement();
                  String name = new File(candidate.getName()).getName();
                  if (!candidate.isDirectory() && name.toLowerCase(Locale.US).endsWith(".img")) {
                      String partition = name.substring(0, name.length() - 4).toLowerCase(Locale.US);
                      if (partition.matches("[A-Za-z0-9_-]+") && !partition.isEmpty()) imageEntries.add(candidate);
                  }
              }
              boolean wroteImage = false;
              java.util.HashSet<String> partitionNames = new java.util.HashSet<>();
              for (ZipEntry entry : imageEntries) {
                  String fileName = new File(entry.getName()).getName();
                  String partitionName = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.US);
                  if (!partitionNames.add(partitionName)) return t("ZIP 中存在重复分区镜像: " + fileName, "The ZIP contains a duplicate partition image: " + fileName);
                  File extracted = File.createTempFile("dsu-image-", ".img", getCacheDir());
                  try {
                      try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(extracted)) {
                          byte[] buffer = new byte[1024 * 1024];
                          int count;
                          while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                      }
                      long size = extracted.length();
                      if (size <= 0) return t("镜像为空: " + fileName, "The image is empty: " + fileName);
                      wroteImage = true;
                      boolean userdata = partitionName.equalsIgnoreCase("userdata");
                      long partitionSize = userdata ? Math.max(userdataSizeBytes, size) : size;
                      int status = service.createPartition(partitionName, partitionSize, !userdata);
                  if (status != 0) return t("创建分区失败: " + partitionName + " (" + status + ")", "Failed to create partition: " + partitionName + " (" + status + ")");
                  runOnUiThread(() -> showInstallProgress(t("正在写入 " + partitionName, "Writing " + partitionName), 50));
                      try (InputStream input = new FileInputStream(extracted)) {
                          if (!streamEntry(input, service, partitionName, size)) return t("写入镜像失败: " + fileName, "Failed to write image: " + fileName);
                      }
                  if (!service.closePartition()) return t("关闭分区失败: " + partitionName, "Failed to close partition: " + partitionName);
                  } finally {
                      if (extracted.exists()) extracted.delete();
                  }
             }
              if (!wroteImage) return t("ZIP 中没有可用的 GSI img 镜像", "The ZIP contains no usable GSI .img images");
              if (!partitionNames.contains("userdata")) {
                  runOnUiThread(() -> showInstallProgress(t("正在创建 userdata", "Creating userdata"), 88));
                  if (service.createPartition("userdata", userdataSizeBytes, false) != 0) return t("创建 userdata 分区失败", "Failed to create userdata partition");
                  if (!service.closePartition()) return t("关闭 userdata 分区失败", "Failed to close userdata partition");
              }
             runOnUiThread(() -> showInstallProgress(t("正在完成安装", "Finishing installation"), 94));
             if (!service.finishInstallation()) return t("Dynamic System 未能完成安装", "Dynamic System could not finish installation");
              if (!service.setEnable(true, false)) return t("GSI 已安装，但启用 DSU 失败", "GSI installed, but DSU could not be enabled");
             completed = true;
              return t("GSI 已安装并启用 DSU，请点击“重启到 DSU”进入系统", "GSI installed and DSU enabled. Tap \"Reboot to DSU\" to enter the system.");
        } catch (Exception e) {
             return t("安装失败: ", "Installation failed: ") + e.getMessage();
        } finally {
            if (started && !completed) try { service.abort(); } catch (Exception ignored) { }
        }
    }
     private boolean streamEntry(InputStream input, IPrivilegedService service, String partition, long totalSize) throws Exception {
         final int bufferSize = 4 * 1024 * 1024;
          try (SharedMemory memory = SharedMemory.create("tianming-dsu", bufferSize)) {
              try (ParcelFileDescriptor fd = sharedMemoryFd(memory)) {
                  if (!service.setAshmem(fd, bufferSize)) return false;
                  ByteBuffer mapped = memory.mapReadWrite();
                  try {
                      byte[] buffer = new byte[1024 * 1024];
                      int count;
                      long written = 0;
                      while ((count = input.read(buffer)) != -1) {
                          mapped.position(0);
                          mapped.put(buffer, 0, count);
                          if (!service.submitFromAshmem(count)) return false;
                          written += count;
                          int progress = 50 + (int) Math.min(35, totalSize > 0 ? written * 35 / totalSize : 0);
                          runOnUiThread(() -> showInstallProgress(t("正在写入 " + partition, "Writing " + partition), progress));
                      }
                      return true;
                  } finally {
                      memory.unmap(mapped);
                  }
              }
          }
     }
    private ParcelFileDescriptor sharedMemoryFd(SharedMemory memory) throws Exception {
        try {
            return (ParcelFileDescriptor) SharedMemory.class.getMethod("getFdDup").invoke(memory);
        } catch (NoSuchMethodException ignored) {
            int fd = (Integer) SharedMemory.class.getDeclaredMethod("getFd").invoke(memory);
            return ParcelFileDescriptor.fromFd(fd);
        }
    }
    private long parseSizeBytes(String size){
        try {
            double gigabytes = Double.parseDouble(size.replace("GB", "").trim());
            if (gigabytes > 0 && gigabytes <= 128) {
                return (long) (gigabytes * 1024d * 1024d * 1024d);
            }
        } catch (Exception ignored) { }
        return 16L * 1024L * 1024L * 1024L;
    }
    private void showInstallProgress(String stage, int progress) {
        if (installPanel == null) return;
        installPanel.setVisibility(View.VISIBLE);
        installStage.setText(stage);
        installProgress.setProgress(Math.max(0, Math.min(100, progress)));
    }
    private void finishProgress(String message){
             boolean success=message.contains("已安装并启用") || message.contains("DSU 已启动") || message.contains("替换完成")
                  || message.contains("installed and DSU enabled") || message.contains("replacement complete");
         boolean replacement=message.contains("替换完成") || message.contains("替换 ") || message.contains("replacement complete") || message.contains("replacement failed");
         showInstallProgress(replacement ? (success ? t("替换完成", "Replacement complete") : t("替换失败", "Replacement failed"))
                 : (success ? t("安装完成", "Installation complete") : t("安装失败", "Installation failed")), success?100:0);
          detailText.setText(message);
          toast(message);
          if (success && !replacement) {
              gsiStatus.setText(t("已安装，等待启动", "Installed, waiting to boot"));
              showInstalledGsiSummary();
          }
    }
    private CommandResult runPrivilegedCommand(String... args){
        StringBuilder command=new StringBuilder(); for(String arg:args) command.append(quote(arg)).append(' ');
        return runCommand("/system/bin/su","-c",command.toString().trim());
    }
    private String runPrivilegedResult(String... args){ StringBuilder command=new StringBuilder(); for(String arg:args) command.append(quote(arg)).append(' '); return run("/system/bin/su","-c",command.toString().trim()); }
    private String quote(String value){ return "'"+value.replace("'","'\\''")+"'"; }
     private void runPrivileged(String... args){ String result=runPrivilegedResult(args); toast(result.isEmpty()?"操作已发送":result); }
     private void wipeDsu(){
         new Thread(() -> {
             String cleanupBeforeError = "";
             try {
                 if (privilegedService != null) cleanupBeforeError = privilegedService.cleanupDsuBackingImages();
             } catch (Exception exception) {
                 cleanupBeforeError = exception.getMessage() == null ? exception.toString() : exception.getMessage();
             }
             boolean removed = false;
             try {
                 if (privilegedService != null) removed = privilegedService.remove();
             } catch (Exception ignored) { }
             CommandResult result = runPrivilegedCommand("/system/bin/gsi_tool", "wipe");
             boolean success = (removed || result.exitCode == 0)
                     && cleanupBeforeError.isEmpty();
             final String cleanupFailure = cleanupBeforeError;
             runOnUiThread(() -> {
                 if (success) {
                     installPanel.setVisibility(View.GONE);
                     installProgress.setProgress(0);
                      installStage.setText(t("安装进度", "Installation progress"));
                      detailText.setText(t("GSI 状态\n未安装", "GSI status\nNot installed"));
                      gsiStatus.setText(t("未安装", "Not installed"));
                     toast(t("DSU 已撤销", "DSU removed"));
                 } else {
                     String detail = cleanupFailure.isEmpty() ? result.output : cleanupFailure;
                     String message = detail.isEmpty() ? t("撤销 DSU 失败", "Failed to remove DSU") : t("撤销 DSU 失败: ", "Failed to remove DSU: ") + detail;
                    detailText.setText(message);
                    toast(message);
                }
            });
        }).start();
    }
    private void showImageManagement(){
        imageManagementPanel.setVisibility(View.VISIBLE);
        imageManagementPanel.removeAllViews();
        TextView loading = text(t("正在读取已安装镜像...", "Reading installed images..."), 13, Color.rgb(77, 87, 105));
        imageManagementPanel.addView(loading, new LinearLayout.LayoutParams(-1, dp(42)));
        new Thread(() -> {
            String raw = "";
            try { if (privilegedService != null) raw = privilegedService.listDsuImages(); }
            catch (Exception ignored) { }
            String result = raw;
            runOnUiThread(() -> renderImageManagement(result));
        }).start();
    }
    private void renderImageManagement(String raw){
        imageManagementPanel.removeAllViews();
        TextView title = text(t("已安装镜像", "Installed images"), 15, Color.rgb(20, 29, 55));
        title.setTypeface(null, 1);
        imageManagementPanel.addView(title, new LinearLayout.LayoutParams(-1, dp(34)));
         if (raw == null || raw.trim().isEmpty()) {
             imageManagementPanel.addView(text(t("无法读取 DSU 镜像目录，或当前设备未提供可访问的镜像文件。", "Unable to read the DSU image directory, or no accessible image files are available."), 13, Color.rgb(77, 87, 105)));
             return;
         }
         if (raw.startsWith("EMPTY|")) {
             String[] empty = raw.split("\\|", 3);
             imageManagementPanel.addView(text(empty.length == 3 ? empty[1] + "\n" + t("目录: ", "Directory: ") + empty[2] : t("暂无已安装镜像", "No installed images"), 13, Color.rgb(77, 87, 105)));
             return;
         }
         boolean hasImage = false;
         for (String line : raw.split("\\r?\\n")) {
              String[] parts = line.split("\\|", -1);
            if (parts.length == 2 && parts[0].equals("ROOT_OK")) {
                imageManagementPanel.addView(text(parts[1], 13, Color.rgb(77, 87, 105)));
                continue;
            }
             if (parts.length < 3) continue;
             hasImage = true;
             String name = parts[0];
            long bytes;
            try { bytes = Long.parseLong(parts[1]); } catch (NumberFormatException e) { bytes = -1; }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
             String sizeText = parts.length > 3 ? parts[3] : formatBytes(bytes);
             TextView label = text(name + "\n" + t("大小: ", "Size: ") + sizeText, 13, Color.rgb(40, 50, 70));
            row.addView(label, new LinearLayout.LayoutParams(0, dp(58), 1));
            Button replace = new Button(this);
             replace.setText(t("替换", "Replace"));
            replace.setTextSize(12);
            replace.setAllCaps(false);
             replace.setMinHeight(0);
             replace.setMinWidth(0);
                String backingImage = parts.length > 4 ? parts[4] : name;
                String backingSlot = parts.length > 5 ? parts[5] : "";
                replace.setOnClickListener(v -> chooseReplacement(name, backingImage, backingSlot, parts[2]));
            row.addView(replace, new LinearLayout.LayoutParams(dp(76), dp(42)));
             imageManagementPanel.addView(row);
         }
         if (!hasImage) {
             imageManagementPanel.addView(text(t("当前没有可管理的镜像文件\n", "No manageable image files are available\n") + raw, 12, Color.rgb(77, 87, 105)));
         }
        TextView hint = text(t("替换完成后点击“重启到 DSU”使更新后的分区生效。", "Tap \"Reboot to DSU\" after replacement to apply the updated partition."), 12, Color.rgb(110, 118, 135));
        imageManagementPanel.addView(hint, new LinearLayout.LayoutParams(-1, dp(42)));
    }
    private String formatBytes(long bytes){
         if (bytes < 0) return t("大小读取失败", "Size unavailable");
         if (bytes == 0) return t("大小未知", "Unknown size");
        if (bytes >= 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824d);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576d);
    }
      private void chooseReplacement(String targetName, String backingImage, String backingSlot, String imagePath){
          replacementPartition = targetName;
          replacementBackingImage = backingImage;
          replacementSlot = backingSlot;
         replacementImagePath = imagePath;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_REPLACEMENT);
    }
       private void replaceImage(Uri source, String partition){
          String selectedName = displayName(source).toLowerCase(java.util.Locale.US);
          if (!selectedName.endsWith(".img") && !selectedName.endsWith(".raw")) {
               toast(t("请选择 .img 或 .raw 镜像文件", "Choose an .img or .raw image file"));
              return;
          }
           String targetPartition = partition;
           String targetBackingImage = replacementBackingImage == null
                   ? targetPartition : replacementBackingImage;
           String targetSlot = replacementSlot == null || replacementSlot.isEmpty()
                   ? DSU_SLOT : replacementSlot;
          String sourcePartition = partitionBaseName(selectedName);
          String expectedPartition = partitionBaseName(targetPartition);
          if (!sourcePartition.equals(expectedPartition)) {
              toast(t("镜像分区名不匹配：需要 " + expectedPartition + ".img，实际为 " + selectedName,
                      "Partition name mismatch: expected " + expectedPartition + ".img, got " + selectedName));
              return;
          }
         showInstallProgress(t("正在准备替换 " + targetPartition, "Preparing to replace " + targetPartition), 5);
           new Thread(() -> {
               ParcelFileDescriptor fd = null;
               String error = "";
               boolean success = false;
               runOnUiThread(() -> showInstallProgress(t("正在复制 " + targetPartition + " 镜像", "Copying " + targetPartition + " image"), 35));
                try {
                    fd = getContentResolver().openFileDescriptor(source, "r");
                    long size = replacementSize(source, fd);
                    if (fd == null) error = "无法打开镜像文件";
                    else if (privilegedService != null) {
                        error = privilegedService.replaceDsuBackingImage(targetSlot, targetBackingImage, fd, size, true);
                        success = error != null && error.isEmpty();
                    } else error = "ROOT service unavailable";
                } catch (Exception exception) { error = exception.getMessage() == null ? exception.toString() : exception.getMessage(); }
               finally { if (fd != null) try { fd.close(); } catch (Exception ignored) { } }
               final String operationError = error;
                String message = success ? t("替换 " + targetPartition + " 完成，请点击“重启到 DSU”使其生效", "Replacement of " + targetPartition + " complete. Tap \"Reboot to DSU\" to apply it.")
                        : t("替换 " + targetPartition + " 失败：" + operationError, "Failed to replace " + targetPartition + ": " + operationError);
             boolean result = success;
             runOnUiThread(() -> {
                  showInstallProgress(result ? t("替换完成", "Replacement complete") : t("替换失败", "Replacement failed"), result ? 100 : 0);
                 detailText.setText(message);
                 toast(message);
                 if (result) {
                     showImageManagement();
                     mainHandler.postDelayed(() -> installPanel.setVisibility(View.GONE), 1200);
                 }
             });
          }).start();
      }
      private String partitionBaseName(String name) {
          String normalized = name == null ? "" : name.toLowerCase(java.util.Locale.US);
          if (normalized.endsWith(".img") || normalized.endsWith(".raw"))
              normalized = normalized.substring(0, normalized.lastIndexOf('.'));
          if (normalized.endsWith("_gsi"))
              normalized = normalized.substring(0, normalized.length() - 4);
          return normalized;
      }
     private void showInstalledGsiSummary() {
         detailText.setText(t("GSI 状态\n已安装，等待启动\n安装包: ", "GSI status\nInstalled, waiting to boot\nPackage: ") + installedZipName);
     }
     private long replacementSize(Uri uri, ParcelFileDescriptor fd) {
         try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
             if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
         } catch (Exception ignored) { }
         try { return fd == null ? -1 : fd.getStatSize(); } catch (Exception ignored) { return -1; }
     }
    private void bootDsu(){
        IPrivilegedService service = privilegedService;
         if (service == null) { toast(t("ROOT Installer 尚未连接", "ROOT installer is not connected")); return; }
        new Thread(() -> {
            try {
                if (!service.isInstalled()) {
                     runOnUiThread(() -> toast(t("启动 DSU 失败：当前没有已安装的 DSU", "Unable to boot DSU: no DSU is installed")));
                    return;
                }
                if (!service.setEnable(true, true)) {
                     runOnUiThread(() -> toast(t("启动 DSU 失败：无法设置 DSU 启动状态", "Unable to boot DSU: could not enable DSU")));
                    return;
                }
                if (!service.boot()) {
                     runOnUiThread(() -> toast(t("启动 DSU 失败：系统拒绝重启到 DSU", "Unable to boot DSU: system rejected reboot")));
                    return;
                }
            } catch (Exception e) {
                 runOnUiThread(() -> toast(t("启动 DSU 失败: ", "Unable to boot DSU: ") + e.getMessage()));
                return;
            }
             runOnUiThread(() -> toast(t("设备正在重启到 DSU", "Device is rebooting to DSU")));
        }).start();
    }
      private static final class RoundedCropDrawable extends Drawable {
         private final Bitmap bitmap;
         private final float radius;
         private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
         RoundedCropDrawable(Bitmap bitmap, float radius){ this.bitmap = bitmap; this.radius = radius; }
         @Override public void draw(Canvas canvas){
             RectF bounds = new RectF(getBounds());
             float scale = Math.max(bounds.width() / bitmap.getWidth(), bounds.height() / bitmap.getHeight());
             float width = bitmap.getWidth() * scale;
             float height = bitmap.getHeight() * scale;
             float left = bounds.left + (bounds.width() - width) / 2f;
             float top = bounds.top + (bounds.height() - height) / 2f;
             BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
             android.graphics.Matrix matrix = new android.graphics.Matrix();
             matrix.setScale(scale, scale);
             matrix.postTranslate(left, top);
             shader.setLocalMatrix(matrix);
             paint.setShader(shader);
             canvas.drawRoundRect(bounds, radius, radius, paint);
             paint.setShader(null);
         }
         @Override public void setAlpha(int alpha){ paint.setAlpha(alpha); }
         @Override public void setColorFilter(android.graphics.ColorFilter filter){ paint.setColorFilter(filter); }
         @Override public int getOpacity(){ return android.graphics.PixelFormat.TRANSLUCENT; }
     }
     private void confirm(String t,String m,final Runnable r){ new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("继续",(d,w)->r.run()).setNegativeButton("取消",null).show(); }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
}
