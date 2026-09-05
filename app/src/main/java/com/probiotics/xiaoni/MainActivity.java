package com.probiotics.xiaoni;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
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

public class MainActivity extends Activity {
    private TextView rootStatus, gsiStatus, detailText;
    private LinearLayout logoCard;
    private static final int PICK_IMAGE = 10;
    private static final int PICK_ZIP = 20;
    private static final int PICK_REPLACEMENT = 30;
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
    private LinearLayout imageManagementPanel;
    private ProgressBar installProgress;
    private TextView installStage, installZipLabel;
    private Button confirmInstallButton;
    private Button[] installSizeButtons;
    private EditText customInstallSizeInput;
    private Uri pendingInstallZip;
    private int selectedInstallSize = 1;
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
        getWindow().setStatusBarColor(Color.rgb(246,247,251));
        getWindow().setNavigationBarColor(Color.rgb(246,247,251));
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
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(22));
        content.setBackgroundColor(Color.rgb(246,247,251));
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(dp(18), dp(14) + top, dp(18), dp(22) + bottom);
            return insets;
        });

         LinearLayout bar = new LinearLayout(this);
         bar.setGravity(Gravity.CENTER_VERTICAL);
         bar.setPadding(dp(14), 0, dp(10), 0);
         bar.setBackgroundResource(R.drawable.rounded_panel);
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

         LinearLayout operationBox = new LinearLayout(this);
         operationBox.setOrientation(LinearLayout.VERTICAL);
         operationBox.setPadding(dp(12), dp(10), dp(12), dp(12));
         operationBox.setBackgroundResource(R.drawable.operation_bg);
          LinearLayout sectionBar = new LinearLayout(this);
          sectionBar.setGravity(Gravity.CENTER_VERTICAL);
            Button about = new Button(this);
            about.setText(t("关于", "About"));
           about.setTextColor(Color.WHITE);
           about.setTextSize(12);
           about.setAllCaps(false);
           about.setMinHeight(0);
           about.setMinWidth(0);
           about.setPadding(dp(10), 0, dp(10), 0);
           about.setBackgroundResource(R.drawable.button_teal);
            about.setOnClickListener(v -> showAboutDialog());
             LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(dp(66), dp(32));
             aboutLp.setMargins(0, 0, dp(5), 0);
             sectionBar.addView(about, aboutLp);
            Button settings = new Button(this);
            settings.setText(t("设置", "Settings"));
            settings.setTextColor(Color.WHITE);
            settings.setTextSize(12);
            settings.setAllCaps(false);
            settings.setMinHeight(0);
            settings.setMinWidth(0);
            settings.setContentDescription(t("设置", "Settings"));
           settings.setBackgroundResource(R.drawable.button_teal);
           settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
            LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(58), dp(32));
           settingsLp.setMargins(dp(5), 0, dp(5), 0);
           sectionBar.addView(settings, settingsLp);
            Button more = new Button(this);
            more.setText(t("更多", "More"));
            more.setTextColor(Color.WHITE);
            more.setTextSize(12);
            more.setAllCaps(false);
            more.setMinHeight(0);
            more.setMinWidth(0);
           more.setContentDescription(t("更多功能", "More features"));
           more.setBackgroundResource(R.drawable.button_teal);
           more.setOnClickListener(v -> startActivity(new Intent(this, MoreActivity.class)));
            LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(dp(58), dp(32));
           moreLp.setMargins(0, 0, dp(5), 0);
           sectionBar.addView(more, moreLp);
          Switch keepScreen = new Switch(this);
           keepScreen.setText(t("保持亮屏", "Keep screen on"));
          keepScreen.setTextColor(Color.WHITE);
          keepScreen.setTextSize(12);
          keepScreen.setChecked(keepScreenOn);
          keepScreen.setOnCheckedChangeListener((button, checked) -> {
              keepScreenOn = checked;
              getPreferences(MODE_PRIVATE).edit().putBoolean("keep_screen_on", checked).apply();
              applyKeepScreenOn();
          });
          sectionBar.addView(keepScreen, new LinearLayout.LayoutParams(-2, dp(34)));
          operationBox.addView(sectionBar, new LinearLayout.LayoutParams(-1, dp(38)));
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
         operationBox.addView(list, new LinearLayout.LayoutParams(-1, -2));
         detailText = text(t("点击操作后，结果会显示在这里。", "Results will appear here after an action."), 13, Color.rgb(77, 87, 105));
        detailText.setGravity(Gravity.TOP);
        detailText.setPadding(dp(14), dp(12), dp(14), dp(12));
        detailText.setBackgroundResource(R.drawable.detail_bg);
         LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, dp(86));
         detailLp.setMargins(0, 0, 0, dp(12));
         content.addView(detailText, detailLp);
         installOptionsPanel = new LinearLayout(this);
         installOptionsPanel.setOrientation(LinearLayout.VERTICAL);
         installOptionsPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
         installOptionsPanel.setBackgroundResource(R.drawable.rounded_panel);
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
         customInstallSizeInput.setBackgroundResource(R.drawable.rounded_panel);
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
          operationBox.addView(imageManagementPanel, new LinearLayout.LayoutParams(-1, -2));
          LinearLayout.LayoutParams operationLp = new LinearLayout.LayoutParams(-1, -2);
           operationLp.setMargins(0, dp(12), 0, 0);
          content.addView(operationBox, operationLp);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
    }

    private void applyKeepScreenOn() {
        if (keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showAboutDialog() {
          String about = t("Dsu GSI管理器\n\n功能说明\n本应用的 GSI 安装流程参考并使用了 DSU-Sideloader 项目的相关方案。\n\n支持安装 DSU 镜像的 img 无损替换。\n支持 system、system_ext、product、vendor、odm、my_preload 等镜像。\n替换修改后的 img 镜像之后直接开机，无需重新过开机引导。直接开机使用修复 bug 后的 Dsu 系统。\n\n使用安卓系统：\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\n安装功能参考 DSU-Sideloader 项目：\nhttps://github.com/VegaBobo/DSU-Sideloader\n\n特别感谢酷安用户及 GitHub 用户 yangFenTuoZi 开发 Dsu 功能修改 img 无损替换功能。\n如有侵权，请联系作者，我们会及时删除相关内容。\n\n作者：小你可兰\n管理器版本：3.4.1",
                "Dsu GSI Manager\n\nFeatures\nThe GSI installation flow uses the DSU-Sideloader project approach.\n\nSupports lossless replacement of img files for installed DSU images.\nSupports system, system_ext, product, vendor, odm, my_preload and other images.\nThe device can boot directly after replacing a modified img image without repeating the setup wizard.\n\nAndroid system components:\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\nInstallation reference:\nhttps://github.com/VegaBobo/DSU-Sideloader\n\nSpecial thanks to Coolapk user and GitHub user yangFenTuoZi for developing the Dsu img lossless replacement feature.\nIf any content infringes your rights, please contact the author and it will be removed promptly.\n\nAuthor: Xiaonikelan\nManager version: 3.4.1");
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
             for (Button button : installSizeButtons) { button.setTextColor(Color.rgb(40, 50, 70)); button.setBackgroundResource(R.drawable.rounded_panel); }
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
        @Override protected void onActivityResult(int r,int c,Intent d){ super.onActivityResult(r,c,d); if(c!=RESULT_OK||d==null)return; Uri u=d.getData(); if(r==PICK_IMAGE){ String path=getPath(u,"logo.img"); if(!path.isEmpty()){ Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(path); if(bitmap!=null) { logoCard.setBackground(new RoundedCropDrawable(bitmap, dp(28))); logoCard.setClipToOutline(true); } } } else if(r==PICK_ZIP){ pendingInstallZip = u; installedZipName = displayName(u); getPreferences(MODE_PRIVATE).edit().putString("installed_zip_name", installedZipName).apply(); installZipLabel.setText(installedZipName); confirmInstallButton.setEnabled(true); } else if(r==PICK_REPLACEMENT && replacementPartition != null){ replaceImage(u, replacementPartition); } }
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
