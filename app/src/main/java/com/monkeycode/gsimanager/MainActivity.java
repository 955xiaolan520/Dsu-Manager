package com.probiotics.tianming;

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
import java.util.LinkedHashMap;
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
    private LinearLayout installPanel;
    private LinearLayout imageManagementPanel;
    private ProgressBar installProgress;
    private TextView installStage;
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
          TextView title = text(t("Dsu 管理器", "Dsu Manager"), 28, Color.rgb(20,29,55));
        title.setTypeface(null, 1);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
         rootStatus = text(t("ROOT 检测中", "Checking ROOT"), 13, Color.WHITE);
        rootStatus.setGravity(Gravity.CENTER);
         rootStatus.setPadding(0, 0, 0, 0);
         rootStatus.setBackgroundResource(R.drawable.root_status_bg);
        rootStatus.setOnClickListener(v -> refreshRootStatus());
        bar.addView(rootStatus, new LinearLayout.LayoutParams(-2, dp(36)));
        content.addView(bar);

        logoCard = new LinearLayout(this);
        logoCard.setOrientation(LinearLayout.VERTICAL);
        logoCard.setPadding(dp(22), dp(18), dp(22), dp(16));
        logoCard.setBackgroundResource(R.drawable.logo_bg);
        logoCard.setClipToOutline(true);
        logoCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(63));
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
        TextView logoTitle = text("GSI STATUS", 13, 0xFFDDE8FF);
        logoTitle.setTypeface(null, 1);
        logoCard.addView(logoTitle);
         gsiStatus = text(rootAuthorized ? t("正在读取动态系统状态...", "Reading Dynamic System status...")
                 : t("需要 ROOT 权限", "ROOT access required"), 21, Color.WHITE);
        gsiStatus.setTypeface(null, 1);
        logoCard.addView(gsiStatus, new LinearLayout.LayoutParams(-1, dp(52)));
         TextView hint = text(t("点击卡片更换背景图片", "Tap to change background image"), 12, 0xB8FFFFFF);
        logoCard.addView(hint);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(330), dp(126));
        cardLp.setMargins(0, dp(14), 0, dp(16));
        cardLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(logoCard, cardLp);

         LinearLayout operationBox = new LinearLayout(this);
         operationBox.setOrientation(LinearLayout.VERTICAL);
         operationBox.setPadding(dp(12), dp(10), dp(12), dp(12));
         operationBox.setBackgroundResource(R.drawable.operation_bg);
          LinearLayout sectionBar = new LinearLayout(this);
          sectionBar.setGravity(Gravity.CENTER_VERTICAL);
           TextView section = text(t("操作中心", "Action center"), 15, Color.WHITE);
          section.setTypeface(null, 1);
           sectionBar.addView(section, new LinearLayout.LayoutParams(0, dp(34), 1));
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
            sectionBar.addView(about, new LinearLayout.LayoutParams(dp(66), dp(32)));
           ImageButton settings = new ImageButton(this);
           settings.setImageResource(android.R.drawable.ic_menu_manage);
           settings.setColorFilter(Color.WHITE);
            settings.setContentDescription(t("设置", "Settings"));
           settings.setBackgroundResource(R.drawable.button_teal);
           settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
           LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(38), dp(32));
           settingsLp.setMargins(dp(5), 0, dp(5), 0);
           sectionBar.addView(settings, settingsLp);
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
                  ? new String[]{"Check GSI status", "Install GSI", "Reboot to DSU", "Remove installed GSI", "Installed GSI details", "Manage installed images"}
                  : new String[]{"检测 GSI 状态", "安装 GSI", "重启到 DSU", "撤销已安装 GSI", "已安装 GSI 信息", "管理已安装镜像"};
         int[] backgrounds = {R.drawable.button_blue, R.drawable.button_green, R.drawable.button_orange, R.drawable.button_purple, R.drawable.button_teal, R.drawable.button_blue};
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
        installPanel = new LinearLayout(this);
        installPanel.setOrientation(LinearLayout.VERTICAL);
        installPanel.setPadding(dp(14), dp(8), dp(14), dp(8));
         installPanel.setBackgroundResource(R.drawable.progress_bg);
         installStage = text(t("安装进度", "Installation progress"), 13, Color.rgb(40, 50, 70));
        installPanel.addView(installStage, new LinearLayout.LayoutParams(-1, dp(26)));
        installProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        installProgress.setMax(100);
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
        String about = t("Dsu GSI管理器\n\n功能说明\n本应用的 GSI 安装流程参考并使用了 DSU-Sideloader 项目的相关方案。\n\n感谢开源项目作者：VegaBobo\n项目地址：https://github.com/955xiaolan520/Dsu-Manager\n\n作者：小你可兰\n管理器版本：3.1.1",
                "Dsu GSI Manager\n\nFeatures\nThe GSI installation flow is based on the DSU-Sideloader project.\n\nThanks to the open-source project author: VegaBobo\nProject: https://github.com/955xiaolan520/Dsu-Manager\n\nAuthor: Xiaonikelan\nManager version: 3.1.1");
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
                 detailText.setText("GSI: " + localizedStatus(status));
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
                  installDialog();
                  return;
              case 2:
                  confirm(t("重启到 DSU", "Reboot to DSU"), t("设备将重启到已安装的 Dynamic System。", "The device will reboot into the installed Dynamic System."), this::bootDsu);
                  return;
              case 3:
                  confirm(t("撤销 GSI", "Remove GSI"), t("将清理当前已安装的动态系统。", "The installed Dynamic System will be removed."), this::wipeDsu);
                  return;
              case 4:
                  showInfo();
                  return;
              case 5:
                  if (imageManagementPanel.getVisibility() == View.VISIBLE) imageManagementPanel.setVisibility(View.GONE); else showImageManagement();
                  return;
              default:
          }
      }
      private void installDialog(){
          final String[] sizes=english ? new String[]{"8 GB","16 GB","32 GB","64 GB","Custom size"} : new String[]{"8 GB","16 GB","32 GB","64 GB","自定义容量"};
          AlertDialog d=new AlertDialog.Builder(this).setTitle(t("安装 GSI", "Install GSI"))
                 .setSingleChoiceItems(sizes,1,(dialog, which) -> {
                     if (which == 4) {
                         dialog.dismiss();
                         customSize();
                     }
                 })
                  .setPositiveButton(t("选择 ZIP 安装包", "Choose ZIP package"),null)
                  .setNegativeButton(t("取消", "Cancel"),null).create();
         d.setOnShowListener(x -> d.getButton(-1).setOnClickListener(v -> {
             int selected=d.getListView().getCheckedItemPosition();
             d.dismiss();
             if(selected == 4) customSize(); else chooseZip(sizes[selected]);
         }));
         d.show();
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
    private void chooseZip(String size){
        pendingSizeLabel = size;
        userdataSizeBytes = parseSizeBytes(size);
        chooseZip();
    }
    private void chooseZip(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("application/zip"); i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/octet-stream"}); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_ZIP); }
    private void chooseImage(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_IMAGE); }
       @Override protected void onActivityResult(int r,int c,Intent d){ super.onActivityResult(r,c,d); if(c!=RESULT_OK||d==null)return; Uri u=d.getData(); if(r==PICK_IMAGE){ String path=getPath(u,"logo.img"); if(!path.isEmpty()){ Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(path); if(bitmap!=null) { logoCard.setBackground(new RoundedCropDrawable(bitmap, dp(63))); logoCard.setClipToOutline(true); } } } else if(r==PICK_ZIP){ installedZipName = displayName(u); getPreferences(MODE_PRIVATE).edit().putString("installed_zip_name", installedZipName).apply(); installWithDsuSideloaderFlow(u); } else if(r==PICK_REPLACEMENT && replacementPartition != null){ replaceImage(u, replacementPartition); } }
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
              if (!service.startInstallation(DSU_SLOT)) return t("Dynamic System 拒绝开始安装，请检查系统 Dynamic System 权限", "Dynamic System rejected the installation. Check Dynamic System permissions.");
            started = true;
             Map<String, ZipEntry> partitions = new LinkedHashMap<>();
             java.util.Enumeration<? extends ZipEntry> imageEntries = zip.entries();
             while (imageEntries.hasMoreElements()) {
                 ZipEntry candidate = imageEntries.nextElement();
                 String name = new File(candidate.getName()).getName();
                 if (!candidate.isDirectory() && name.endsWith(".img")) {
                     String partition = name.substring(0, name.length() - 4);
                     if (partition.matches("[A-Za-z0-9_]+") && !partition.equals("userdata") && !partitions.containsKey(partition)) {
                         partitions.put(partition, candidate);
                     }
                 }
             }
             boolean wroteImage = false;
             for (Map.Entry<String, ZipEntry> partition : partitions.entrySet()) {
                 ZipEntry entry = partition.getValue();
                 if (entry == null || entry.isDirectory()) continue;
                 String fileName = entry.getName();
                 String partitionName = partition.getKey();
                wroteImage = true;
                long size = entry.getSize();
                 if (size < 0) return t("无法确定 " + fileName + " 的镜像大小", "Unable to determine image size: " + fileName);
                 int status = service.createPartition(partitionName, size, true);
                 if (status != 0) return t("创建分区失败: " + partitionName + " (" + status + ")", "Failed to create partition: " + partitionName + " (" + status + ")");
                 runOnUiThread(() -> showInstallProgress(t("正在写入 " + partitionName, "Writing " + partitionName), 50));
                 if (!streamEntry(zip.getInputStream(entry), service, partitionName, size)) return t("写入镜像失败: " + fileName, "Failed to write image: " + fileName);
                 if (!service.closePartition()) return t("关闭分区失败: " + partitionName, "Failed to close partition: " + partitionName);
            }
             if (!wroteImage) return t("ZIP 中没有可用的 GSI img 镜像", "The ZIP contains no usable GSI .img images");
             runOnUiThread(() -> showInstallProgress(t("正在创建 userdata", "Creating userdata"), 88));
              if (service.createPartition("userdata", userdataSizeBytes, false) != 0) return t("创建 userdata 分区失败", "Failed to create userdata partition");
             if (!service.closePartition()) return t("关闭 userdata 分区失败", "Failed to close userdata partition");
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
            ParcelFileDescriptor fd;
            try {
                fd = sharedMemoryFd(memory);
            } catch (Exception error) {
                throw new IOException("设备不支持共享内存文件描述符: " + error.getMessage(), error);
            }
            if (!service.setAshmem(fd, bufferSize)) { fd.close(); return false; }
            ByteBuffer mapped = memory.mapReadWrite();
            byte[] buffer = new byte[1024 * 1024];
            int count;
            long written = 0;
            while ((count = input.read(buffer)) != -1) {
                mapped.position(0);
                mapped.put(buffer, 0, count);
                if (!service.submitFromAshmem(count)) { fd.close(); return false; }
                written += count;
                int progress = 50 + (int) Math.min(35, totalSize > 0 ? written * 35 / totalSize : 0);
                 runOnUiThread(() -> showInstallProgress(t("正在写入 " + partition, "Writing " + partition), progress));
            }
            memory.unmap(mapped);
            fd.close();
            return true;
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
            CommandResult result = runPrivilegedCommand("/system/bin/gsi_tool", "wipe");
            runOnUiThread(() -> {
                 if (result.exitCode == 0) {
                    installPanel.setVisibility(View.GONE);
                    installProgress.setProgress(0);
                     installStage.setText(t("安装进度", "Installation progress"));
                     detailText.setText(t("DSU 已撤销", "DSU removed"));
                     gsiStatus.setText(t("未安装", "Not installed"));
                     toast(t("DSU 已撤销", "DSU removed"));
                } else {
                    String message = result.output.isEmpty() ? t("撤销 DSU 失败", "Failed to remove DSU") : t("撤销 DSU 失败: ", "Failed to remove DSU: ") + result.output;
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
             String[] parts = line.split("\\|", 4);
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
            replace.setOnClickListener(v -> chooseReplacement(name, parts[2]));
            row.addView(replace, new LinearLayout.LayoutParams(dp(76), dp(42)));
             imageManagementPanel.addView(row);
         }
         if (!hasImage) {
             imageManagementPanel.addView(text(t("当前没有可管理的镜像文件\n", "No manageable image files are available\n") + raw, 12, Color.rgb(77, 87, 105)));
         }
         TextView hint = text(t("替换前请确保设备未运行 DSU。替换完成后点击“重启到 DSU”。", "Make sure DSU is not running before replacement. Tap \"Reboot to DSU\" after replacement."), 12, Color.rgb(110, 118, 135));
        imageManagementPanel.addView(hint, new LinearLayout.LayoutParams(-1, dp(42)));
    }
    private String formatBytes(long bytes){
         if (bytes < 0) return t("大小读取失败", "Size unavailable");
         if (bytes == 0) return t("大小未知", "Unknown size");
        if (bytes >= 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824d);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576d);
    }
    private void chooseReplacement(String partition, String imagePath){
        replacementPartition = partition;
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
          showInstallProgress(t("正在准备替换 " + partition, "Preparing to replace " + partition), 5);
         new Thread(() -> {
             String path = getPath(source, "replace-" + partition + ".img");
             boolean success = false;
              runOnUiThread(() -> showInstallProgress(t("正在复制 " + partition + " 镜像", "Copying " + partition + " image"), 35));
             try { if (!path.isEmpty() && privilegedService != null) success = privilegedService.replaceDsuImage(replacementImagePath, path); }
             catch (Exception ignored) { }
              String message = success ? t(partition + ".img 替换完成，请点击“重启到 DSU”使其生效", partition + ".img replacement complete. Tap \"Reboot to DSU\" to apply it.")
                      : t("替换 " + partition + ".img 失败：请确认 DSU 未运行且系统允许访问 DSU 镜像目录", "Failed to replace " + partition + ".img. Make sure DSU is stopped and the DSU image directory is accessible.");
             if (!path.isEmpty()) new File(path).delete();
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
    private void showInfo(){
         detailText.setText(t("正在读取已安装 GSI 信息...", "Reading installed GSI information..."));
          new Thread(() -> { String result; try { result=runPrivilegedResult("/system/bin/gsi_tool","status"); } catch(Exception e){ result=t("读取失败: ", "Read failed: ")+e.getMessage(); } final String output=(result==null||result.trim().isEmpty()?t("当前没有可用的 GSI 状态信息", "No GSI status information is available"):formatGsiStatus(result))+"\n"+t("安装包: ", "Package: ")+installedZipName; runOnUiThread(() -> { detailText.setText(t("GSI 状态\n", "GSI status\n")+output); new AlertDialog.Builder(this).setTitle(t("已安装 GSI 系统信息", "Installed GSI information")).setMessage(output).setPositiveButton(t("确定", "OK"),null).show(); }); }).start();
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
