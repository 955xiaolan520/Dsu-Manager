package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VivoActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Device> devices = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private JSONObject catalog;
    private Spinner category;
    private EditText search;
    private EditText versionInput;
    private EditText pdInput;
    private EditText vInput;
    private Spinner androidVersion;
    private Spinner packageType;
    private EditText serialInput;
    private Spinner verbose;
    private LinearLayout manualPanel;
    private TextView status;
    private LinearLayout deviceResults;
    private LinearLayout updateResults;
    private ScrollView pageScroll;

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        loadCatalog();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(22));
        root.setBackgroundResource(R.drawable.liquid_backdrop);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(16), dp(12) + insets.getSystemWindowInsetTop(), dp(16),
                    dp(22) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LiquidGlassPanel header = new LiquidGlassPanel(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(5), 0, dp(8), 0);
        Button back = glassButton("<", 20);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView title = label("vivo OTA 更新中心", 23, 0xff142037);
        title.setTypeface(null, 1);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(header, margins(-1, 56, 0, 0, 12));

        TextView source = label("vivo / iQOO · OriginOS 官方系统更新", 12, 0xff596579);
        source.setPadding(dp(13), dp(9), dp(13), dp(9));
        source.setBackgroundResource(R.drawable.liquid_glass_panel);
        root.addView(source, margins(-1, -2, 0, 0, 10));

        category = optionSpinner("设备分类");
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                filterDevices();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(wrapSpinner(category), margins(-1, 52, 0, 6, 0));

        search = input("搜索机型或设备代号，例如 vivo X100 / PD2324");
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterDevices(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        root.addView(search, margins(-1, 50, 0, 10, 0));

        Button manual = glassButton("手动输入 PD / V 查询", 13);
        manual.setOnClickListener(v -> {
            manualPanel.setVisibility(manualPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (manualPanel.getVisibility() == View.VISIBLE) enableManualMode();
        });
        root.addView(manual, margins(-1, 44, 0, 0, 10));
        manualPanel = new LinearLayout(this);
        manualPanel.setOrientation(LinearLayout.VERTICAL);
        pdInput = input("PD 设备型号，例如 PD2547");
        vInput = input("V 软件型号，例如 V2547DA");
        manualPanel.addView(pdInput, margins(-1, 50, 0, 0, 8));
        manualPanel.addView(vInput, margins(-1, 50, 0, 0, 8));
        Button manualQuery = glassButton("查询手动输入的设备", 13);
        manualQuery.setOnClickListener(v -> query(new Device("手动设备", pdInput.getText().toString().trim(),
                vInput.getText().toString().trim(), "手动输入")));
        manualPanel.addView(manualQuery, margins(-1, 44, 0, 0, 10));
        manualPanel.setVisibility(View.GONE);
        root.addView(manualPanel, margins(-1, -2, 0, 0, 0));

        versionInput = input("系统版本，例如 16.1.21.17.W10");
        root.addView(versionInput, margins(-1, 50, 0, 10, 0));
        androidVersion = optionSpinner("安卓版本");
        androidVersion.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Android 16", "Android 15", "Android 14", "Android 13", "Android 12"}));
        root.addView(wrapSpinner(androidVersion), margins(-1, 52, 0, 8, 0));
        packageType = optionSpinner("包类型");
        packageType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Official (Normal)", "Trial (尝鲜包)", "Beta (公测)", "Alpha (内测)"}));
        root.addView(wrapSpinner(packageType), margins(-1, 52, 0, 8, 0));
        serialInput = input("序列号，可留空，默认 A0000000000000A");
        root.addView(serialInput, margins(-1, 50, 0, 8, 0));
        verbose = optionSpinner("详细日志");
        verbose.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"关闭详细日志", "开启详细日志"}));
        root.addView(wrapSpinner(verbose), margins(-1, 52, 0, 10, 0));

        status = label("正在加载 vivo 设备分类...", 13, 0xff596579);
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        root.addView(status, margins(-1, -2, 0, 8, 0));

        deviceResults = new LinearLayout(this);
        deviceResults.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceResults, new LinearLayout.LayoutParams(-1, -2));
        updateResults = new LinearLayout(this);
        updateResults.setOrientation(LinearLayout.VERTICAL);
        root.addView(updateResults, new LinearLayout.LayoutParams(-1, -2));

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.addView(root);
        setContentView(pageScroll);
    }

    private void loadCatalog() {
        executor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getAssets().open("vivo_devices.txt"), StandardCharsets.UTF_8))) {
                JSONObject loaded = new JSONObject();
                List<String> loadedCategories = new ArrayList<>();
                String currentCategory = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    java.util.regex.Matcher title = java.util.regex.Pattern.compile("<title>([^<]+)</title>").matcher(line);
                    if (title.find() && !title.group(1).trim().isEmpty()) {
                        currentCategory = title.group(1).trim().replaceFirst("^[Vv][Ii][Vv][Oo] ", "");
                        loadedCategories.add(currentCategory);
                        loaded.put(currentCategory, new org.json.JSONArray());
                    }
                    java.util.regex.Matcher option = java.util.regex.Pattern.compile(
                            "<option value=\\\"model_([^\\\"]+)\\\">([^<]+)</option>").matcher(line);
                    if (option.find() && currentCategory != null) {
                        String value = option.group(1).trim();
                        String[] parts = value.split("_", 2);
                        if (parts.length == 2) {
                            loaded.getJSONArray(currentCategory).put(new JSONObject()
                                    .put("model", option.group(2).trim())
                                    .put("codename", parts[0])
                                    .put("model_sw_ver", parts[1]));
                        }
                    }
                }
                runOnUiThread(() -> {
                    catalog = loaded;
                    categories.clear();
                    categories.addAll(loadedCategories);
                    category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categories));
                    status.setText("请选择分类，或使用搜索定位机型");
                    filterDevices();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("设备分类读取失败: " + error.getMessage()));
            }
        });
    }

    private void filterDevices() {
        if (catalog == null || deviceResults == null) return;
        devices.clear();
        deviceResults.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int selected = category == null ? 0 : category.getSelectedItemPosition();
        if (selected < 0 || selected >= categories.size()) return;
        String selectedCategory = categories.get(selected);
        try {
            org.json.JSONArray values = catalog.getJSONArray(selectedCategory);
            int shown = 0;
            for (int i = 0; i < values.length() && shown < 50; i++) {
                Device device = new Device(values.getJSONObject(i), selectedCategory);
                String haystack = (device.model + " " + device.codename + " " + device.swVer).toLowerCase(Locale.ROOT);
                if (!query.isEmpty() && !haystack.contains(query)) continue;
                devices.add(device);
                addDeviceCard(device);
                shown++;
            }
            status.setText((query.isEmpty() ? selectedCategory + " · " : "搜索结果 · ") + shown + " 个机型，点击机型查询更新");
            if (shown == 0) status.setText("当前分类没有匹配的机型");
        } catch (Exception error) {
            status.setText("设备分类解析失败: " + error.getMessage());
        }
    }

    private void addDeviceCard(Device device) {
        LiquidGlassPanel card = new LiquidGlassPanel(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(11));
        TextView name = label(device.model, 16, 0xff1f3155);
        name.setTypeface(null, 1);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));
        card.addView(label("设备代号: " + device.codename + "  ·  " + device.swVer, 12, 0xff65738b),
                margins(-1, -2, 0, 7, 0));
        Button query = glassButton("查询此设备的最新版本", 13);
        query.setOnClickListener(v -> selectDevice(device));
        card.addView(query, new LinearLayout.LayoutParams(-1, dp(44)));
        deviceResults.addView(card, margins(-1, -2, 0, 8, 0));
    }

    private void selectDevice(Device device) {
        pdInput.setText(device.codename);
        vInput.setText(device.swVer);
        manualPanel.setVisibility(View.VISIBLE);
        pdInput.setEnabled(false);
        vInput.setEnabled(false);
        query(device);
    }

    private void enableManualMode() {
        pdInput.setEnabled(true);
        vInput.setEnabled(true);
        pdInput.setText("");
        vInput.setText("");
        pdInput.requestFocus();
        status.setText("请输入 PD 和 V 设备型号，再输入系统版本查询");
    }

    private void query(Device device) {
        String version = versionInput.getText().toString().trim();
        String rawPd = pdInput.getText().toString();
        String rawV = vInput.getText().toString();
        if (rawPd.trim().isEmpty() || rawV.trim().isEmpty()) {
            status.setText("请先选择机型，或手动填写 PD 和 V");
            return;
        }
        String pd = normalizePd(rawPd);
        String v = normalizeV(rawV);
        pdInput.setText(pd);
        vInput.setText(v);
        if (version.isEmpty()) {
            status.setText("请先输入当前版本");
            versionInput.requestFocus();
            return;
        }
        status.setText("正在查询 " + device.model + " 的官方更新...");
        updateResults.removeAllViews();
        executor.execute(() -> {
            try {
                int android = 16 - androidVersion.getSelectedItemPosition();
                boolean full = packageType.getSelectedItemPosition() == 0;
                String serial = serialInput.getText().toString().trim();
                if (serial.isEmpty()) serial = "A0000000000000A";
                VivoOtaClient.QueryChannel channel = VivoOtaClient.QueryChannel.values()[packageType.getSelectedItemPosition()];
                VivoOtaClient.VivoResult result = new VivoOtaClient(this).query(pd, v, version, android, true, full,
                        serial, verbose.getSelectedItemPosition() == 1, channel);
                runOnUiThread(() -> render(device, result));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("查询失败: " + error.getMessage()));
            }
        });
    }

    private String normalizePd(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("PD") ? normalized : "PD" + normalized;
    }

    private String normalizeV(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("V") ? normalized : "V" + normalized;
    }

    private void render(Device device, VivoOtaClient.VivoResult result) {
        updateResults.removeAllViews();
        boolean available = !result.downloadUrl.isEmpty();
        status.setText(available ? "查询成功，发现可用更新" : "查询完成，当前没有可用更新");
        LiquidGlassPanel card = new LiquidGlassPanel(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        TextView heading = label(device.model, 18, 0xff1b3157);
        heading.setTypeface(null, 1);
        card.addView(heading, margins(-1, -2, 0, 7, 0));
        addInfo(card, "版本", result.version);
        addInfo(card, "文件", result.filename);
        addInfo(card, "大小", result.size);
        addInfo(card, "安全补丁", result.securityPatch);
        addInfo(card, "更新时间", result.updateTime);
        addInfo(card, "MD5", result.md5);
        if (available) {
            Button download = glassButton("下载此更新包", 13);
            download.setOnClickListener(v -> startDownload(result.downloadUrl, result.filename));
            card.addView(download, margins(-1, 44, 0, 0, 8));
        }
        updateResults.addView(card, margins(-1, -2, 0, 14, 0));
        pageScroll.post(() -> pageScroll.smoothScrollTo(0, updateResults.getTop()));
    }

    private void addInfo(LinearLayout card, String key, String value) {
        TextView row = label(key + "  " + (value.isEmpty() ? "未知" : value), 13, 0xff596579);
        row.setPadding(0, dp(2), 0, dp(2));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void startDownload(String url, String name) {
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DsuManager");
        if (!directory.exists()) directory.mkdirs();
        String safeName = (name == null || name.isEmpty() ? "vivo-update.zip" : name)
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        File output = new File(directory, safeName);
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_ADDRESS, url)
                .putExtra(DownloadService.EXTRA_OUTPUT, output.getAbsolutePath())
                .putExtra(DownloadService.EXTRA_PACKAGE, "vivo OTA");
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        status.setText("下载已开始: " + output.getName());
    }

    private Spinner optionSpinner(String prompt) {
        Spinner spinner = new Spinner(this);
        spinner.setPrompt(prompt);
        spinner.setBackgroundColor(Color.TRANSPARENT);
        return spinner;
    }

    private LinearLayout wrapSpinner(Spinner spinner) {
        LiquidGlassPanel shell = new LiquidGlassPanel(this);
        shell.setGravity(Gravity.CENTER_VERTICAL);
        shell.setPadding(dp(10), 0, dp(10), 0);
        shell.addView(spinner, new LinearLayout.LayoutParams(0, dp(48), 1));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_dropdown_arrow);
        arrow.setContentDescription("展开选项");
        shell.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(48)));
        return shell;
    }

    private EditText input(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextSize(14);
        input.setTextColor(0xff20375b);
        input.setHintTextColor(0xff718096);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackgroundResource(R.drawable.liquid_glass_panel);
        return input;
    }

    private Button glassButton(String text, int size) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(size);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setTextColor(0xff20375b);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackgroundResource(R.drawable.liquid_glass_panel);
        return button;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(true);
        return view;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        params.setMargins(dp(left), dp(top), 0, dp(bottom));
        return params;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static final class Device {
        final String model;
        final String codename;
        final String swVer;
        final String category;

        Device(JSONObject item, String category) {
            this.model = item.optString("model", "未知机型");
            this.codename = item.optString("codename", "");
            this.swVer = item.optString("model_sw_ver", "");
            this.category = category;
        }

        Device(String model, String codename, String swVer, String category) {
            this.model = model;
            this.codename = codename;
            this.swVer = swVer;
            this.category = category;
        }
    }
}
