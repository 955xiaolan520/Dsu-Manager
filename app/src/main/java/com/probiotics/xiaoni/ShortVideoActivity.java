package com.probiotics.xiaoni;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.core.view.WindowCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Short-video resolver backed by the user-approved public API. */
public class ShortVideoActivity extends BaseActivity {
    private static final String API_BASE = "https://api.bugpk.com/api/short_videos";
    private static final String USER_AGENT = "DsuManager/" + BuildConfig.VERSION_NAME + " (Android)";
    private static final int MAX_FILENAME_UTF8_BYTES = 96;
    private static final Pattern KNOWN_SHARE_LINK = Pattern.compile(
            "(?i)((?:https?://)?(?:[a-z0-9-]+\\.)*(?:v\\.douyin\\.com|iesdouyin\\.com|douyin\\.com|v\\.kuaishou\\.com|kuaishou\\.com|gifshow\\.com|b23\\.tv|bilibili\\.com|tiktok\\.com|youtu\\.be|youtube\\.com|xhslink\\.com|xiaohongshu\\.com|weibo\\.com|weibo\\.cn|ixigua\\.com|xigua\\.com|acfun\\.cn|zhihu\\.com|haokan\\.com|weishi\\.qq\\.com|meipai\\.com|pearvideo\\.com)(?:/[^\\s<>\\\"'，。！？,;]*)?)");
    private static final Pattern ANY_HTTP_LINK = Pattern.compile("(?i)https?://[^\\s<>\\\"'，。！？]+" );

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imageWorker = Executors.newFixedThreadPool(3);
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build();

    private EditText linkInput;
    private Button parseButton;
    private Button downloadButton;
    private Button manageDownloadsButton;
    private Button previewButton;
    private ProgressBar progressBar;
    private LinearLayout statusCard;
    private TextView statusView;
    private LinearLayout resultCard;
    private LinearLayout resultImageGallery;
    private TextView resultTitleView;
    private TextView resultAuthorView;
    private TextView resultMetaView;
    private TextView expandTitleView;
    private TextView imageSelectionInfo;
    private TextView imageSelectAllButton;
    private final List<TextView> imageSelectionBadges = new ArrayList<>();
    private final LinkedHashSet<Integer> selectedImageIndexes = new LinkedHashSet<>();
    private VideoInfo currentVideo;
    private String currentShareUrl = "";
    private boolean english;
    private boolean titleExpanded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int languageMode = getSharedPreferences("settings", MODE_PRIVATE).getInt("language_mode", 0);
        english = languageMode == 2 || (languageMode == 0
                && Locale.getDefault().getLanguage().equals("en"));
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildPage());
    }

    private String t(String zh, String en) { return english ? en : zh; }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private GradientDrawable rounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) drawable.setStroke(Math.max(1, dp(1)), strokeColor);
        return drawable;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private Button actionButton(String value, int start, int end) {
        Button button = new Button(this, null, 0);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{start, end});
        background.setCornerRadius(dp(17));
        background.setStroke(Math.max(1, dp(1)), 0x9cffffff);
        button.setBackground(background);
        return button;
    }

    private View buildPage() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new CornerAccentDrawable(
                0xffdce7ee, 0xff8299aa,
                new int[]{0xff3296a8, 0xff6e78bd, 0xffca8953, 0xff4d9c7c},
                new float[][]{{0.04f, 0.02f}, {0.97f, 0.18f}, {0.96f, 0.98f}, {0.03f, 0.84f}},
                new int[]{dp(280), dp(250), dp(300), dp(220)}));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout headerSlot = new LinearLayout(this);
        headerSlot.setOrientation(LinearLayout.VERTICAL);
        headerSlot.setPadding(dp(16), dp(8), dp(16), dp(5));
        headerSlot.addView(buildHeader(), lp(-1, -2));
        shell.addView(headerSlot, lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(5), dp(16), dp(22));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            androidx.core.graphics.Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            headerSlot.setPadding(dp(16), bars.top + dp(8), dp(16), dp(5));
            shell.setPadding(0, 0, 0, Math.max(bars.bottom, ime.bottom));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
        TextView steps = label(t("① 粘贴分享文案  →  ② 自动提取链接  →  ③ 解析并下载",
                "① Paste share text  →  ② Extract link  →  ③ Resolve and download"),
                12, 0xff287f87, true);
        steps.setGravity(Gravity.CENTER);
        steps.setPadding(dp(8), dp(10), dp(8), dp(10));
        steps.setBackground(rounded(0x30ffffff, 0xbaffffff, 15));
        LinearLayout.LayoutParams stepsLp = lp(-1, -2);
        stepsLp.topMargin = dp(10);
        content.addView(steps, stepsLp);

        content.addView(buildPrivacyCard(), topMargin(lp(-1, -2), 10));
        content.addView(buildInputCard(), topMargin(lp(-1, -2), 12));

        parseButton = actionButton(t("开始解析", "Resolve video"), 0xff247e96, 0xff24658e);
        LinearLayout.LayoutParams parseLp = lp(-1, dp(48));
        parseLp.topMargin = dp(12);
        content.addView(parseButton, parseLp);
        parseButton.setOnClickListener(v -> confirmThirdPartyAndParse());

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = lp(-1, dp(4));
        progressLp.topMargin = dp(9);
        content.addView(progressBar, progressLp);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusCard.setBackground(rounded(0x36ffffff, 0x88ffffff, 15));
        statusView = label(t("粘贴分享文案或链接即可自动识别。", "Paste a share text or link to detect it automatically."),
                12, 0xff36566b, false);
        statusView.setMaxLines(4);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setLineSpacing(dp(2), 1.0f);
        statusCard.addView(statusView, lp(-1, -2));
        LinearLayout.LayoutParams statusLp = lp(-1, -2);
        statusLp.topMargin = dp(9);
        content.addView(statusCard, statusLp);

        resultCard = buildResultCard();
        resultCard.setVisibility(View.GONE);
        content.addView(resultCard, topMargin(lp(-1, -2), 11));

        downloadButton = actionButton(t("↓  加入 Dsu 下载队列", "↓  Add to Dsu download queue"),
                0xff168477, 0xff14738d);
        downloadButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams downloadLp = lp(-1, dp(48));
        downloadLp.topMargin = dp(10);
        content.addView(downloadButton, downloadLp);
        downloadButton.setOnClickListener(v -> {
            VideoInfo video = currentVideo;
            if (video != null && video.isImagePost) enqueueImageDownloads(video);
            else enqueueDownload();
        });

        previewButton = actionButton(t("▶  应用内预览", "▶  Preview in app"), 0xff477eae, 0xff765fa8);
        previewButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewLp = lp(-1, dp(46));
        previewLp.topMargin = dp(8);
        content.addView(previewButton, previewLp);
        previewButton.setOnClickListener(v -> openInternalPreview());

        manageDownloadsButton = actionButton(t("▣  打开 Dsu 下载管理", "▣  Open Dsu Download Manager"),
                0xff247e96, 0xff245b84);
        manageDownloadsButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams manageLp = lp(-1, dp(46));
        manageLp.topMargin = dp(8);
        content.addView(manageDownloadsButton, manageLp);
        manageDownloadsButton.setOnClickListener(v -> openDownloadManager());

        LinearLayout supportedCard = new LinearLayout(this);
        supportedCard.setOrientation(LinearLayout.VERTICAL);
        supportedCard.setPadding(dp(13), dp(11), dp(13), dp(11));
        supportedCard.setBackground(rounded(0x25ffffff, 0x88ffffff, 17));
        TextView supportedTitle = label(t("接口支持的平台", "Platforms reported by the API"), 12, 0xff244c62, true);
        supportedCard.addView(supportedTitle, lp(-1, dp(20)));
        TextView supportedText = label(t("抖音 · 快手 · TikTok 等；哔哩哔哩和 YouTube 当前暂不可用。",
                "Douyin · Kuaishou · TikTok and more; Bilibili and YouTube are currently unavailable."),
                11, 0xff4f6a80, false);
        supportedText.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams supportedTextLp = lp(-1, -2);
        supportedTextLp.topMargin = dp(3);
        supportedCard.addView(supportedText, supportedTextLp);
        content.addView(supportedCard, topMargin(lp(-1, -2), 12));
        content.addView(buildSourceCard(), topMargin(lp(-1, -2), 10));
        return root;
    }

    private View buildSourceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(12));
        card.setBackground(rounded(0x28ffffff, 0x9cffffff, 18));

        TextView title = label(t("解析服务与源码来源", "Parser service & source references"),
                12, 0xff244c62, true);
        card.addView(title, lp(-1, -2));
        TextView service = label(t("当前使用 api.bugpk.com 第三方接口；B站和 YouTube 暂时无法解析。",
                "Uses the api.bugpk.com third-party API; Bilibili and YouTube are currently unavailable."),
                11, 0xff4f6a80, false);
        service.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams serviceLp = lp(-1, -2);
        serviceLp.topMargin = dp(4);
        card.addView(service, serviceLp);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chipsLp = lp(-1, -2);
        chipsLp.topMargin = dp(8);
        card.addView(chips, chipsLp);
        TextView apiChip = sourceChip("API", 0xffd9f4f2, 0xff176c72);
        TextView repoOne = sourceChip("开源参考 01", 0xffeee2ff, 0xff68449a);
        TextView repoTwo = sourceChip("开源参考 02", 0xffffe3d6, 0xffa34e31);
        chips.addView(apiChip);
        LinearLayout.LayoutParams repoOneLp = lp(-2, -2);
        repoOneLp.leftMargin = dp(6);
        chips.addView(repoOne, repoOneLp);
        LinearLayout.LayoutParams repoTwoLp = lp(-2, -2);
        repoTwoLp.leftMargin = dp(6);
        chips.addView(repoTwo, repoTwoLp);

        TextView references = label("wu529778790/parse.shenzjd.com\njiuhunwl/short_videos", 10, 0xff31566b, true);
        references.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams refsLp = lp(-1, -2);
        refsLp.topMargin = dp(7);
        card.addView(references, refsLp);
        TextView note = label(t("说明：以上仓库用于解析实现参考；本应用调用第三方 API，不包含或托管其服务端源码。点击仓库名称可查看原项目。",
                "The repositories are implementation references. This app calls the third-party API and does not bundle or host its server code. Tap to view the projects."),
                10, 0xff61768a, false);
        note.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams noteLp = lp(-1, -2);
        noteLp.topMargin = dp(5);
        card.addView(note, noteLp);

        View.OnClickListener repo1Click = v -> openExternal("https://github.com/wu529778790/parse.shenzjd.com");
        View.OnClickListener repo2Click = v -> openExternal("https://github.com/jiuhunwl/short_videos");
        repoOne.setOnClickListener(repo1Click);
        repoTwo.setOnClickListener(repo2Click);
        references.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(t("开源参考项目", "Open-source reference projects"))
                .setItems(new String[]{"wu529778790/parse.shenzjd.com", "jiuhunwl/short_videos"},
                        (dialog, which) -> openExternal(which == 0
                                ? "https://github.com/wu529778790/parse.shenzjd.com"
                                : "https://github.com/jiuhunwl/short_videos"))
                .show());
        return card;
    }

    private TextView sourceChip(String text, int background, int foreground) {
        TextView chip = label(text, 9, foreground, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(5), dp(8), dp(5));
        chip.setBackground(rounded(background, 0, 10));
        return chip;
    }

    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Throwable ignored) { setStatus(t("无法打开源码链接", "Could not open source link"), true); }
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(8), dp(12), dp(8));
        header.setBackground(rounded(0x32ffffff, 0xcaffffff, 22));

        TextView back = label("‹", 30, 0xff17334f, false);
        back.setGravity(Gravity.CENTER);
        back.setBackground(rounded(0x22ffffff, 0x88ffffff, 15));
        back.setOnClickListener(v -> finish());
        header.addView(back, lp(dp(42), dp(42)));

        View accent = new View(this);
        GradientDrawable accentBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff35a8c4, 0xff176c72});
        accentBg.setCornerRadius(dp(3));
        accent.setBackground(accentBg);
        LinearLayout.LayoutParams accentLp = lp(dp(3), dp(34));
        accentLp.setMargins(dp(10), 0, dp(10), 0);
        header.addView(accent, accentLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(t("短视频无水印下载", "Short-video download"), 17, 0xff17334f, true);
        titles.addView(title, lp(-1, -2));
        TextView subtitle = label(t("Dsu Manager · 分享链接解析", "Dsu Manager · Share-link resolver"),
                11, 0xff4f6a80, false);
        LinearLayout.LayoutParams subtitleLp = lp(-1, -2);
        subtitleLp.topMargin = dp(2);
        titles.addView(subtitle, subtitleLp);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView badge = label("VIDEO", 10, 0xff176c72, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(6), dp(9), dp(6));
        badge.setBackground(rounded(0x24ffffff, 0xbaffffff, 13));
        header.addView(badge, lp(-2, -2));
        return header;
    }

    private View buildPrivacyCard() {
        LinearLayout privacy = new LinearLayout(this);
        privacy.setOrientation(LinearLayout.VERTICAL);
        privacy.setPadding(dp(13), dp(11), dp(13), dp(11));
        privacy.setBackground(rounded(0xffffefbd, 0xffffd66e, 17));
        TextView title = label(t("解析方式与隐私提示", "Resolver & privacy"), 13, 0xff62430b, true);
        privacy.addView(title, lp(-1, dp(21)));
        TextView body = label(t(
                "确认后只会发送识别出的链接到 api.bugpk.com，不会发送整段分享文案。哔哩哔哩和 YouTube 当前无法通过此接口解析，尝试后仍可能失败。",
                "After confirmation, only the extracted link is sent to api.bugpk.com, not the full share text. Bilibili and YouTube are currently not supported by this API and may still fail."),
                11, 0xff604a20, false);
        body.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams bodyLp = lp(-1, -2);
        bodyLp.topMargin = dp(3);
        privacy.addView(body, bodyLp);
        return privacy;
    }

    private View buildInputCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        card.setBackground(rounded(0x35ffffff, 0xd1ffffff, 22));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = label("↗", 18, 0xff176c72, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(0x37ffffff, 0xaaffffff, 12));
        heading.addView(icon, lp(dp(32), dp(32)));
        LinearLayout headingText = new LinearLayout(this);
        headingText.setOrientation(LinearLayout.VERTICAL);
        headingText.setPadding(dp(8), 0, 0, 0);
        TextView title = label(t("分享内容", "Share content"), 14, 0xff17334f, true);
        headingText.addView(title, lp(-1, -2));
        TextView hint = label(t("粘贴链接或整段抖音/快手分享文案", "Paste a link or full platform share text"),
                10, 0xff587186, false);
        headingText.addView(hint, lp(-1, -2));
        heading.addView(headingText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = label(t("清空", "Clear"), 12, 0xff3e6477, true);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(dp(9), dp(7), dp(9), dp(7));
        clear.setBackground(rounded(0x28ffffff, 0x7affffff, 13));
        clear.setOnClickListener(v -> {
            linkInput.setText("");
            currentVideo = null;
            resultCard.setVisibility(View.GONE);
            resultImageGallery.setVisibility(View.GONE);
            downloadButton.setVisibility(View.GONE);
            previewButton.setVisibility(View.GONE);
            manageDownloadsButton.setVisibility(View.GONE);
            setStatus(t("已清空。粘贴分享文案或链接即可自动识别。", "Cleared. Paste share text or a link to detect it."), false);
        });
        heading.addView(clear, lp(-2, -2));
        card.addView(heading, lp(-1, -2));

        linkInput = new EditText(this);
        linkInput.setTextColor(0xff17334f);
        linkInput.setHintTextColor(0xff6f8497);
        linkInput.setHint(t("例：复制打开抖音，看看【作者】的作品…… https://v.douyin.com/xxxx/ ...",
                "Example: share text containing https://v.douyin.com/xxxx/"));
        linkInput.setTextSize(13);
        linkInput.setGravity(Gravity.TOP | Gravity.START);
        linkInput.setPadding(dp(11), dp(10), dp(11), dp(10));
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        linkInput.setMinLines(2);
        linkInput.setMaxLines(4);
        linkInput.setBackground(rounded(0x25ffffff, 0x91ffffff, 15));
        LinearLayout.LayoutParams inputLp = lp(-1, -2);
        inputLp.topMargin = dp(10);
        card.addView(linkInput, inputLp);

        Button pasteButton = actionButton(t("从剪贴板粘贴并提取链接", "Paste and extract link"), 0xff428fa3, 0xff2e728f);
        LinearLayout.LayoutParams pasteLp = lp(-1, dp(40));
        pasteLp.topMargin = dp(9);
        card.addView(pasteButton, pasteLp);
        pasteButton.setOnClickListener(v -> pasteAndExtract());
        return card;
    }

    private LinearLayout buildResultCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(12));
        card.setBackground(rounded(0x48ffffff, 0xddffffff, 21));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);
        TextView icon = label("▶", 15, Color.WHITE, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(0xff287f87, 0xaaffffff, 13));
        top.addView(icon, lp(dp(35), dp(35)));

        LinearLayout textGroup = new LinearLayout(this);
        textGroup.setOrientation(LinearLayout.VERTICAL);
        textGroup.setPadding(dp(9), 0, 0, 0);
        resultTitleView = label("", 14, 0xff142f45, true);
        resultTitleView.setMaxLines(3);
        resultTitleView.setEllipsize(TextUtils.TruncateAt.END);
        resultTitleView.setLineSpacing(dp(1), 1.0f);
        textGroup.addView(resultTitleView, lp(-1, -2));
        resultAuthorView = label("", 11, 0xff526d82, false);
        LinearLayout.LayoutParams authorLp = lp(-1, -2);
        authorLp.topMargin = dp(4);
        textGroup.addView(resultAuthorView, authorLp);
        top.addView(textGroup, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(top, lp(-1, -2));

        resultMetaView = label("", 11, 0xff31566b, true);
        resultMetaView.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams metaLp = lp(-1, -2);
        metaLp.topMargin = dp(10);
        card.addView(resultMetaView, metaLp);

        resultImageGallery = new LinearLayout(this);
        resultImageGallery.setOrientation(LinearLayout.VERTICAL);
        resultImageGallery.setVisibility(View.GONE);
        LinearLayout.LayoutParams galleryLp = lp(-1, -2);
        galleryLp.topMargin = dp(9);
        card.addView(resultImageGallery, galleryLp);

        expandTitleView = label("", 11, 0xff247e96, true);
        expandTitleView.setPadding(0, dp(8), 0, dp(2));
        expandTitleView.setVisibility(View.GONE);
        expandTitleView.setOnClickListener(v -> toggleFullTitle());
        card.addView(expandTitleView, lp(-1, -2));
        return card;
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams lp, int marginDp) {
        lp.topMargin = dp(marginDp);
        return lp;
    }

    private void pasteAndExtract() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                setStatus(t("剪贴板没有内容", "Clipboard is empty"), true);
                return;
            }
            CharSequence copied = clip.getItemAt(0).coerceToText(this);
            if (copied == null) return;
            String original = copied.toString().trim();
            String extracted = extractShareUrl(original);
            if (extracted.isEmpty()) {
                linkInput.setText(original);
                setStatus(t("未在剪贴板文案中识别到链接。请确认文案包含抖音、快手、B站等分享域名。",
                        "No supported share URL found in clipboard text. Check that it contains a supported platform domain."), true);
                return;
            }
            linkInput.setText(extracted);
            setStatus(t("已从分享文案中提取并规范化链接，可直接解析。", "Share link extracted and normalized. Ready to resolve."), false);
        } catch (Throwable e) {
            setStatus(t("读取剪贴板失败", "Could not read clipboard"), true);
        }
    }

    private void confirmThirdPartyAndParse() {
        String raw = linkInput == null ? "" : linkInput.getText().toString().trim();
        String url = extractShareUrl(raw);
        if (url.isEmpty()) {
            setStatus(t("未找到可识别的视频分享链接。可粘贴整段分享文案，应用会自动提取其中的链接。",
                    "No supported video share URL found. Paste the full share text and the app will extract its link."), true);
            return;
        }
        if (!raw.equals(url)) linkInput.setText(url);
        hideKeyboard();
        showResolveConfirmDialog(url);
    }

    private void showResolveConfirmDialog(String url) {
        final boolean knownUnavailable = isApiKnownUnavailablePlatform(url);
        final android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xffeffaff, 0xffdceafa, 0xfff3e8ff});
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), 0xd8ffffff);
        card.setBackground(bg);

        LinearLayout capsule = new LinearLayout(this);
        capsule.setGravity(Gravity.CENTER);
        capsule.setPadding(dp(10), dp(6), dp(10), dp(6));
        capsule.setBackground(rounded(0x247d98bd, 0x887d98bd, 15));
        TextView capsuleText = label(t("LINK LAB  ·  安全提示", "LINK LAB  ·  PRIVACY CHECK"),
                10, 0xff35617e, true);
        capsule.addView(capsuleText, lp(-2, -2));
        LinearLayout.LayoutParams capsuleLp = lp(-2, -2);
        capsuleLp.gravity = Gravity.START;
        card.addView(capsule, capsuleLp);

        TextView title = label(t("准备开始解析？", "Ready to resolve?"), 21, 0xff17334f, true);
        LinearLayout.LayoutParams titleLp = lp(-1, -2);
        titleLp.topMargin = dp(12);
        card.addView(title, titleLp);
        TextView description = label(knownUnavailable
                        ? t("哔哩哔哩和 YouTube 当前无法通过此解析接口使用；继续后仍可能解析失败。只会发送识别出的链接，不会发送整段文案。",
                                "Bilibili and YouTube are currently unavailable through this parser and may still fail. Only the extracted link is sent, not the full share text.")
                        : t("只发送识别出的分享链接，不会发送整段分享文案。", "Only the extracted share link is sent—not the surrounding share text."),
                13, 0xff4c647a, false);
        LinearLayout.LayoutParams descLp = lp(-1, -2);
        descLp.topMargin = dp(5);
        card.addView(description, descLp);

        TextView host = label(knownUnavailable
                ? "api.bugpk.com · B站 / YouTube 当前不可用"
                : "api.bugpk.com · 第三方解析", 12, 0xff345c7b, true);
        host.setPadding(dp(11), dp(10), dp(11), dp(10));
        host.setBackground(rounded(0x20ffffff, 0x8effffff, 13));
        LinearLayout.LayoutParams hostLp = lp(-1, -2);
        hostLp.topMargin = dp(12);
        card.addView(host, hostLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = lp(-1, dp(46));
        buttonsLp.topMargin = dp(16);
        Button cancel = actionButton(t("先不解析", "Not now"), 0xff8297a8, 0xff687f93);
        Button proceed = actionButton(knownUnavailable
                ? t("仍然尝试  →", "Try anyway  →") : t("继续解析  →", "Resolve  →"), 0xff23899a, 0xff476ab4);
        LinearLayout.LayoutParams each = new LinearLayout.LayoutParams(0, -1, 1f);
        buttons.addView(cancel, each);
        LinearLayout.LayoutParams proceedLp = new LinearLayout.LayoutParams(0, -1, 1f);
        proceedLp.leftMargin = dp(9);
        buttons.addView(proceed, proceedLp);
        card.addView(buttons, buttonsLp);
        cancel.setOnClickListener(v -> dialog.dismiss());
        proceed.setOnClickListener(v -> { dialog.dismiss(); parseLink(url); });

        dialog.setContentView(card);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.dimAmount = 0.58f;
            dialog.getWindow().setAttributes(params);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(40), -2);
        }
    }

    private void openDownloadManager() {
        try {
            Intent intent = new Intent(this, DownloadManagerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable error) {
            setStatus(t("无法打开 Dsu 下载管理：", "Could not open Dsu Download Manager: ")
                    + (error.getMessage() == null ? "" : error.getMessage()), true);
        }
    }

    private void openInternalPreview() {
        VideoInfo video = currentVideo;
        if (video == null) return;
        Intent intent;
        if (video.isImagePost && !video.imageUrls.isEmpty()) {
            intent = new Intent(this, ShortVideoImagesActivity.class)
                    .putStringArrayListExtra(ShortVideoImagesActivity.EXTRA_IMAGES, new ArrayList<>(video.imageUrls))
                    .putExtra(ShortVideoImagesActivity.EXTRA_INDEX, 0)
                    .putExtra(ShortVideoImagesActivity.EXTRA_TITLE, video.title)
                    .putExtra(ShortVideoImagesActivity.EXTRA_REFERER, video.shareUrl);
        } else {
            intent = new Intent(this, ShortVideoPreviewActivity.class)
                    .putExtra(ShortVideoPreviewActivity.EXTRA_VIDEO_URL, video.mediaUrl)
                    .putExtra(ShortVideoPreviewActivity.EXTRA_COVER_URL, video.coverUrl)
                    .putExtra(ShortVideoPreviewActivity.EXTRA_TITLE, video.title)
                    .putExtra(ShortVideoPreviewActivity.EXTRA_REFERER, video.shareUrl);
        }
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void parseLink(String shareUrl) {
        currentShareUrl = shareUrl;
        currentVideo = null;
        titleExpanded = false;
        selectedImageIndexes.clear();
        imageSelectionBadges.clear();
        downloadButton.setVisibility(View.GONE);
        previewButton.setVisibility(View.GONE);
        manageDownloadsButton.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        resultImageGallery.setVisibility(View.GONE);
        parseButton.setEnabled(false);
        parseButton.setAlpha(0.65f);
        progressBar.setVisibility(View.VISIBLE);
        setStatus(t("正在请求第三方解析服务…", "Contacting the third-party parser…"), false);

        worker.execute(() -> {
            try {
                HttpUrl.Builder urlBuilder = HttpUrl.get(API_BASE).newBuilder()
                        .addQueryParameter("url", shareUrl);
                String platform = detectPlatform(shareUrl);
                if (!platform.isEmpty()) urlBuilder.addQueryParameter("platform", platform);
                Request request = new Request.Builder()
                        .url(urlBuilder.build())
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build();

                int httpStatus;
                String payload;
                try (Response response = http.newCall(request).execute()) {
                    httpStatus = response.code();
                    ResponseBody body = response.body();
                    payload = body == null ? "" : body.string();
                }

                JSONObject responseJson;
                try {
                    responseJson = new JSONObject(payload);
                } catch (Exception badJson) {
                    String message = t("解析接口返回了非 JSON 数据（HTTP ", "Parser returned non-JSON data (HTTP ")
                            + httpStatus + ")";
                    runOnUiThread(() -> finishParseFailure(message));
                    return;
                }

                JSONObject data = responseJson.optJSONObject("data");
                JSONObject mediaData = data == null ? responseJson : data;
                String mediaUrl = findMediaUrl(mediaData);
                if (mediaUrl.isEmpty() && data != null) mediaUrl = findMediaUrl(responseJson);
                List<String> imageUrls = findImageUrls(mediaData);
                if (imageUrls.isEmpty() && data != null) imageUrls = findImageUrls(responseJson);
                String message = readText(responseJson, "msg", "message", "error");
                int apiCode = responseJson.optInt("code", httpStatus);
                if (httpStatus < 200 || httpStatus >= 300) {
                    String error = message.isEmpty() ? "HTTP " + httpStatus : "HTTP " + httpStatus + " · " + message;
                    runOnUiThread(() -> finishParseFailure(error));
                    return;
                }
                if (mediaUrl.isEmpty() && imageUrls.isEmpty()) {
                    String error = explainApiError(apiCode, message, data, detectPlatform(shareUrl));
                    runOnUiThread(() -> finishParseFailure(error));
                    return;
                }

                VideoInfo video = decodeVideo(responseJson, data, mediaUrl, shareUrl);
                video.imageUrls.addAll(imageUrls);
                video.isImagePost = "image".equalsIgnoreCase(video.mediaType)
                        || (video.mediaUrl.isEmpty() && !video.imageUrls.isEmpty());
                if (video.isImagePost && video.imageUrls.isEmpty() && isHttpUrl(video.coverUrl)) {
                    video.imageUrls.add(video.coverUrl);
                }
                if (video.isImagePost && video.mediaUrl.isEmpty() && !video.imageUrls.isEmpty()) {
                    video.coverUrl = video.imageUrls.get(0);
                }
                String successMessage = message;
                runOnUiThread(() -> finishParseSuccess(video, successMessage));
            } catch (Exception e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (error.length() > 180) error = error.substring(0, 180) + "…";
                String finalError = t("解析请求失败：", "Parse request failed: ") + error;
                runOnUiThread(() -> finishParseFailure(finalError));
            }
        });
    }

    private VideoInfo decodeVideo(JSONObject root, JSONObject data, String mediaUrl, String shareUrl) {
        JSONObject source = data == null ? root : data;
        VideoInfo video = new VideoInfo();
        video.mediaUrl = mediaUrl;
        video.shareUrl = shareUrl;
        video.mediaType = readText(source, "type", "media_type", "mediaType");
        video.coverUrl = readText(source, "cover", "cover_url", "coverUrl");
        video.title = firstNonBlank(readText(source, "title", "desc", "caption", "name"), t("短视频", "Short video"));
        video.author = readAuthor(source);
        video.platform = readText(root, "platform");
        video.quality = readText(source, "quality");
        video.width = source.optInt("width", 0);
        video.height = source.optInt("height", 0);
        video.sizeLabel = readText(source, "size_label", "sizeLabel");
        if (video.sizeLabel.isEmpty()) video.sizeLabel = formatBytes(source.optLong("size", 0L));
        video.duration = formatDuration(source.optDouble("duration", 0.0));
        video.backupQualities = readBackupQualities(source.optJSONArray("video_backup"));
        video.apiMessage = readText(root, "msg", "message");
        return video;
    }

    private boolean isApiKnownUnavailablePlatform(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("b23.tv") || host.endsWith(".bilibili.com") || host.equals("bilibili.com")
                    || host.equals("youtu.be") || host.endsWith(".youtube.com") || host.equals("youtube.com");
        } catch (Exception ignored) { return false; }
    }

    private void finishParseSuccess(VideoInfo video, String message) {
        if (isFinishing() || isDestroyed()) return;
        progressBar.setVisibility(View.GONE);
        parseButton.setEnabled(true);
        parseButton.setAlpha(1f);
        currentVideo = video;
        selectedImageIndexes.clear();
        for (int i = 0; i < Math.min(video.imageUrls.size(), 30); i++) selectedImageIndexes.add(i);
        imageSelectionBadges.clear();
        resultTitleView.setText(video.title);
        resultAuthorView.setText((video.platform.isEmpty() ? "" : video.platform.toUpperCase(Locale.ROOT) + "  ·  ")
                + (video.author.isEmpty() ? t("解析成功", "Resolved") : t("作者：", "Author: ") + video.author));
        resultMetaView.setText(buildMetadata(video));
        renderImageGallery(video);
        boolean hasLongTitle = video.title.codePointCount(0, video.title.length()) > 96;
        expandTitleView.setVisibility(hasLongTitle ? View.VISIBLE : View.GONE);
        titleExpanded = false;
        updateTitleExpansion();
        resultCard.setVisibility(View.VISIBLE);
        downloadButton.setVisibility(video.isImagePost || !video.mediaUrl.isEmpty() ? View.VISIBLE : View.GONE);
        previewButton.setVisibility(video.isImagePost || !video.mediaUrl.isEmpty() ? View.VISIBLE : View.GONE);
        if (video.isImagePost) {
            previewButton.setText(t("▧  查看图片图集（" + video.imageUrls.size() + " 张）",
                    "▧  View image gallery (" + video.imageUrls.size() + ")"));
            updateImageSelectionUi();
        } else {
            downloadButton.setText(t("↓  加入 Dsu 下载队列", "↓  Add to Dsu download queue"));
            previewButton.setText(t("▶  应用内预览", "▶  Preview in app"));
        }
        String status = video.isImagePost
                ? t("图文解析成功 · " + video.imageUrls.size() + " 张图片", "Image post resolved · " + video.imageUrls.size() + " images")
                : t("解析成功", "Resolved");
        if (!message.isEmpty() && !message.equalsIgnoreCase("success")) status += " · " + message;
        setStatus(status, false);
    }

    private String buildMetadata(VideoInfo video) {
        List<String> values = new ArrayList<>();
        if (video.quality.equalsIgnoreCase("original")) values.add(t("接口标注：原画", "API quality: original"));
        else if (!video.quality.isEmpty()) values.add(t("接口画质：", "API quality: ") + video.quality);
        if (video.width > 0 && video.height > 0) {
            String resolution = video.width + " × " + video.height;
            if (Math.max(video.width, video.height) >= 3840) resolution = "4K · " + resolution;
            values.add(resolution);
        }
        if (!video.sizeLabel.isEmpty()) values.add(video.sizeLabel);
        if (!video.duration.isEmpty()) values.add(video.duration);
        if (video.isImagePost && !video.imageUrls.isEmpty()) {
            values.add(t("图片作品 · " + video.imageUrls.size() + " 张", "Image post · " + video.imageUrls.size() + " images"));
        }
        String line = TextUtils.join("  ·  ", values);
        if (!video.backupQualities.isEmpty()) {
            line += (line.isEmpty() ? "" : "\n") + t("备用清晰度：", "Alternates: ") + video.backupQualities;
        }
        return line.isEmpty() ? t("接口未提供画质/文件大小信息。", "The API returned no quality or file-size metadata.") : line;
    }

    private void updateTitleExpansion() {
        if (resultTitleView == null || currentVideo == null) return;
        if (titleExpanded) {
            resultTitleView.setMaxLines(Integer.MAX_VALUE);
            resultTitleView.setEllipsize(null);
            expandTitleView.setText(t("收起完整作品文案  ↑", "Collapse description  ↑"));
        } else {
            resultTitleView.setMaxLines(3);
            resultTitleView.setEllipsize(TextUtils.TruncateAt.END);
            expandTitleView.setText(t("展开完整作品文案  ↓", "Expand full description  ↓"));
        }
    }

    private void toggleFullTitle() {
        titleExpanded = !titleExpanded;
        updateTitleExpansion();
    }

    private void finishParseFailure(String message) {
        if (isFinishing() || isDestroyed()) return;
        progressBar.setVisibility(View.GONE);
        parseButton.setEnabled(true);
        parseButton.setAlpha(1f);
        currentVideo = null;
        selectedImageIndexes.clear();
        imageSelectionBadges.clear();
        resultCard.setVisibility(View.GONE);
        resultImageGallery.setVisibility(View.GONE);
        downloadButton.setVisibility(View.GONE);
        previewButton.setVisibility(View.GONE);
        manageDownloadsButton.setVisibility(View.GONE);
        setStatus(message, true);
    }

    private void setStatus(String message, boolean error) {
        if (statusView == null) return;
        statusView.setText(message);
        statusView.setTextColor(error ? 0xff9d2638 : 0xff36566b);
        statusCard.setBackground(rounded(error ? 0x45ffe4e8 : 0x36ffffff,
                error ? 0xb5e6a0a9 : 0x88ffffff, 15));
    }

    private String explainApiError(int code, String message, JSONObject data, String platform) {
        String detail = message == null ? "" : message.trim();
        if ("youtube".equals(platform) && code == 501) {
            detail = t("YouTube 链接已识别，但当前解析接口返回“需签名流或受限”，没有给出可用视频地址。不是链接格式错误；当前版本尚未接入本地备用解析器。",
                    "The YouTube link was recognized, but the configured parser returned no media URL because streams are signed or restricted. This is not a URL-format error; this version has no local fallback extractor.");
        }
        else if ("bilibili".equals(platform) && (code == 0 || code == -1)) {
            detail = t("B 站链接已识别，但当前解析接口返回 code=" + code + "，未给出视频地址。不是 APP 链接格式错误；当前版本尚未接入本地备用解析器。",
                    "The Bilibili link was recognized, but the configured parser returned code=" + code + " without a media URL. This is not a URL-format error; this version has no local fallback extractor.");
        }
        else if (code == 501) detail = t("该平台未返回可用的视频直链（接口 code=501）。", "No direct media URL was returned (API code 501).");
        else if (code == 400 && detail.isEmpty()) detail = t("链接格式无效或接口无法识别平台。", "Invalid link or unsupported platform.");
        else if (code == 401 || code == 403) detail = t("解析接口拒绝了请求（code=" + code + "）。", "The parser rejected the request (code=" + code + ").");
        else if (code == 429) detail = t("解析请求过于频繁，请稍后再试（code=429）。", "Too many requests; try again later (code 429).");
        else if (detail.isEmpty()) detail = t("接口没有返回视频直链。", "The API returned no video URL.");

        JSONArray supported = data == null ? null : data.optJSONArray("supported");
        if (supported != null && supported.length() > 0) {
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < supported.length() && i < 8; i++) {
                JSONObject item = supported.optJSONObject(i);
                if (item != null) {
                    String label = readText(item, "label", "id");
                    if (!label.isEmpty()) labels.add(label);
                }
            }
            if (!labels.isEmpty()) detail += "\n" + t("接口支持：", "Supported: ") + TextUtils.join("、", labels);
        }
        return t("接口返回 " + code + " · " + detail, "API returned " + code + " · " + detail);
    }

    private boolean isHttpUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Extract a supported share URL from a full copied caption, then canonicalize it to HTTPS. */
    private String extractShareUrl(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        Matcher known = KNOWN_SHARE_LINK.matcher(text);
        while (known.find()) {
            String candidate = normalizeShareUrl(known.group(1));
            if (!candidate.isEmpty()) return candidate;
        }
        Matcher generic = ANY_HTTP_LINK.matcher(text);
        while (generic.find()) {
            String candidate = normalizeShareUrl(generic.group());
            if (!candidate.isEmpty()) return candidate;
        }
        return "";
    }

    private String normalizeShareUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        while (!value.isEmpty() && "<([{\"'“‘".indexOf(value.charAt(0)) >= 0) value = value.substring(1);
        while (!value.isEmpty() && ">)]}\"'”’.,，。；;：:！!？?、".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("//")) value = "https:" + value;
        else if (value.matches("(?i)^http://.*")) value = value.replaceFirst("(?i)^http://", "https://");
        else if (!value.matches("(?i)^https://.*")) value = "https://" + value;
        return isHttpUrl(value) ? value : "";
    }

    private String detectPlatform(String value) {
        try {
            String host = Uri.parse(value).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            if (host.contains("douyin") || host.contains("iesdouyin")) return "douyin";
            if (host.contains("kuaishou") || host.contains("gifshow")) return "kuaishou";
            if (host.equals("b23.tv") || host.contains("bilibili")) return "bilibili";
            if (host.contains("tiktok")) return "tiktok";
            if (host.equals("youtu.be") || host.contains("youtube")) return "youtube";
            if (host.contains("xiaohongshu") || host.equals("xhslink.com")) return "xhs";
            if (host.contains("weibo")) return "weibo";
            if (host.contains("ixigua") || host.contains("xigua")) return "xigua";
            if (host.contains("acfun")) return "acfun";
            if (host.contains("zhihu")) return "zhihu";
            if (host.contains("haokan")) return "haokan";
            if (host.contains("weishi")) return "weishi";
            if (host.contains("meipai")) return "meipai";
            if (host.contains("pearvideo")) return "lishipin";
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private List<String> findImageUrls(JSONObject object) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        collectImageFields(object, urls, 0);
        return new ArrayList<>(urls);
    }

    private void collectImageFields(Object node, Set<String> urls, int depth) {
        if (node == null || depth > 7 || urls.size() >= 30) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (java.util.Iterator<String> it = object.keys(); it.hasNext();) {
                String key = it.next();
                Object value = object.opt(key);
                String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                boolean imageField = normalized.equals("images") || normalized.equals("image")
                        || normalized.equals("imageurl") || normalized.equals("imageurls") || normalized.equals("imagelist")
                        || normalized.equals("imagelisturl") || normalized.equals("imageurllist")
                        || normalized.equals("pics") || normalized.equals("picurls")
                        || normalized.equals("picurl") || normalized.equals("imgurl")
                        || normalized.equals("photos") || normalized.equals("photo")
                        || normalized.equals("photourl") || normalized.equals("photourls") || normalized.equals("pictures")
                        || normalized.equals("picturelist") || normalized.equals("imglist")
                        || normalized.equals("originimages") || normalized.equals("imageslist");
                if (imageField) collectImageValues(value, urls, depth + 1);
                else if (value instanceof JSONObject || value instanceof JSONArray) collectImageFields(value, urls, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < 30; i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) collectImageFields(child, urls, depth + 1);
            }
        }
    }

    private void collectImageValues(Object value, Set<String> urls, int depth) {
        if (value == null || depth > 7 || urls.size() >= 30) return;
        if (value instanceof String) {
            String url = ((String) value).trim();
            if (url.startsWith("//")) url = "https:" + url;
            if (isHttpUrl(url)) urls.add(url);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && urls.size() < 30; i++) collectImageValues(array.opt(i), urls, depth + 1);
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String[] urlKeys = {"url", "src", "image_url", "imageUrl", "pic_url", "picUrl", "photo_url", "photoUrl", "origin_url", "originUrl", "large"};
            for (String key : urlKeys) {
                String url = readText(object, key);
                if (url.startsWith("//")) url = "https:" + url;
                if (isHttpUrl(url)) urls.add(url);
            }
            collectImageFields(object, urls, depth + 1);
        }
    }

    private void renderImageGallery(VideoInfo video) {
        if (resultImageGallery == null) return;
        resultImageGallery.removeAllViews();
        imageSelectionBadges.clear();
        if (video.imageUrls.isEmpty()) {
            resultImageGallery.setVisibility(View.GONE);
            return;
        }
        resultImageGallery.setVisibility(View.VISIBLE);
        LinearLayout selectionHeader = new LinearLayout(this);
        selectionHeader.setOrientation(LinearLayout.HORIZONTAL);
        selectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        imageSelectionInfo = label("", 11, 0xff31566b, true);
        selectionHeader.addView(imageSelectionInfo, new LinearLayout.LayoutParams(0, dp(28), 1f));
        imageSelectAllButton = label("", 10, 0xff197b83, true);
        imageSelectAllButton.setGravity(Gravity.CENTER);
        imageSelectAllButton.setPadding(dp(10), dp(5), dp(10), dp(5));
        imageSelectAllButton.setBackground(rounded(0x27ffffff, 0x7792b1bd, 14));
        selectionHeader.addView(imageSelectAllButton, lp(-2, dp(28)));
        imageSelectAllButton.setOnClickListener(v -> {
            int total = Math.min(video.imageUrls.size(), 30);
            if (selectedImageIndexes.size() == total) selectedImageIndexes.clear();
            else {
                selectedImageIndexes.clear();
                for (int i = 0; i < total; i++) selectedImageIndexes.add(i);
            }
            updateImageSelectionUi();
        });
        resultImageGallery.addView(selectionHeader, lp(-1, dp(28)));
        TextView selectionHint = label(t("点按缩略图选择/取消；查看大图请点下方“查看图片图集”。",
                "Tap thumbnails to select/deselect; use View image gallery below to preview."), 9, 0xff617b8f, false);
        resultImageGallery.addView(selectionHint, topMargin(lp(-1, -2), 2));
        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        strip.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        LinearLayout.LayoutParams stripLp = lp(-1, dp(104));
        stripLp.topMargin = dp(5);
        resultImageGallery.addView(strip, stripLp);
        int count = Math.min(video.imageUrls.size(), 30);
        for (int index = 0; index < count; index++) {
            String url = video.imageUrls.get(index);
            FrameLayout tile = new FrameLayout(this);
            tile.setBackground(rounded(0xffdce5ed, 0x99ffffff, 13));
            tile.setClipToOutline(true);
            LinearLayout.LayoutParams tileLp = lp(dp(104), dp(98));
            if (index > 0) tileLp.leftMargin = dp(7);
            row.addView(tile, tileLp);
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setTag(url);
            tile.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            TextView number = label((index + 1) + "/" + count, 9, Color.WHITE, true);
            number.setGravity(Gravity.CENTER);
            number.setPadding(dp(5), dp(2), dp(5), dp(2));
            number.setBackground(rounded(0xb5263546, 0, 8));
            FrameLayout.LayoutParams numberLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            numberLp.setMargins(0, 0, dp(5), dp(5));
            tile.addView(number, numberLp);
            TextView selection = label("✓", 12, Color.WHITE, true);
            selection.setGravity(Gravity.CENTER);
            selection.setBackground(rounded(0xff168477, 0xe6ffffff, 10));
            FrameLayout.LayoutParams selectionLp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
            selectionLp.setMargins(0, dp(5), dp(5), 0);
            tile.addView(selection, selectionLp);
            imageSelectionBadges.add(selection);
            final int selected = index;
            tile.setOnClickListener(v -> toggleImageSelection(selected));
            imageWorker.execute(() -> loadImageThumbnail(url, thumb, video.shareUrl));
        }
    }

    private void toggleImageSelection(int index) {
        if (selectedImageIndexes.contains(index)) selectedImageIndexes.remove(index);
        else selectedImageIndexes.add(index);
        updateImageSelectionUi();
    }

    private void updateImageSelectionUi() {
        VideoInfo video = currentVideo;
        if (video == null || !video.isImagePost) return;
        int total = Math.min(video.imageUrls.size(), 30);
        int selected = selectedImageIndexes.size();
        if (imageSelectionInfo != null) imageSelectionInfo.setText(t(
                "图片作品 · " + total + " 张 · 已选 " + selected + " 张",
                "Images · " + total + " · Selected " + selected));
        if (imageSelectAllButton != null) imageSelectAllButton.setText(selected == total
                ? t("取消全选", "Clear all") : t("全选", "Select all"));
        for (int i = 0; i < imageSelectionBadges.size(); i++) {
            boolean checked = selectedImageIndexes.contains(i);
            TextView badge = imageSelectionBadges.get(i);
            badge.setText(checked ? "✓" : "○");
            badge.setBackground(rounded(checked ? 0xff168477 : 0x9a263546, 0xe6ffffff, 10));
        }
        if (downloadButton != null) {
            downloadButton.setEnabled(selected > 0);
            downloadButton.setAlpha(selected > 0 ? 1f : 0.55f);
            boolean all = total > 0 && selected == total;
            downloadButton.setText(all
                    ? t("↓  下载全部图片（" + total + " 张）", "↓  Download all images (" + total + ")")
                    : t("↓  下载选中图片（" + selected + " 张）", "↓  Download selected (" + selected + ")"));
        }
    }

    private void loadImageThumbnail(String url, ImageView target, String referer) {
        try {
            Request.Builder builder = new Request.Builder().url(url).header("User-Agent", USER_AGENT);
            if (isHttpUrl(referer)) builder.header("Referer", referer);
            try (Response response = http.newCall(builder.get().build()).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) return;
                byte[] bytes = body.bytes();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / sample > dp(420)) sample *= 2;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) return;
                runOnUiThread(() -> {
                    if (!isDestroyed() && url.equals(target.getTag())) target.setImageBitmap(bitmap);
                });
            }
        } catch (Exception ignored) { }
    }

    private void openImageGallery(int index) {
        VideoInfo video = currentVideo;
        if (video == null || video.imageUrls.isEmpty()) return;
        Intent intent = new Intent(this, ShortVideoImagesActivity.class)
                .putStringArrayListExtra(ShortVideoImagesActivity.EXTRA_IMAGES, new ArrayList<>(video.imageUrls))
                .putExtra(ShortVideoImagesActivity.EXTRA_INDEX, index)
                .putExtra(ShortVideoImagesActivity.EXTRA_TITLE, video.title)
                .putExtra(ShortVideoImagesActivity.EXTRA_REFERER, video.shareUrl);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private String findMediaUrl(JSONObject object) {
        if (object == null) return "";
        String[] directKeys = {"url", "video_url", "videoUrl", "play_url", "playUrl", "photoUrl", "download_url", "downloadUrl"};
        for (String key : directKeys) {
            Object value = object.opt(key);
            if (value instanceof String && isHttpUrl((String) value)) return ((String) value).trim();
        }
        String[] nestedKeys = {"videos", "video_backup", "video", "media", "videoList"};
        for (String key : nestedKeys) {
            Object value = object.opt(key);
            if (value instanceof JSONObject) {
                String found = findMediaUrl((JSONObject) value);
                if (!found.isEmpty()) return found;
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.opt(i);
                    String found = item instanceof JSONObject ? findMediaUrl((JSONObject) item)
                            : item instanceof String && isHttpUrl((String) item) ? ((String) item).trim() : "";
                    if (!found.isEmpty()) return found;
                }
            }
        }
        return "";
    }

    private String readText(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) return ((String) value).trim();
        }
        return "";
    }

    private String readAuthor(JSONObject data) {
        if (data == null) return "";
        String direct = readText(data, "author", "authorName", "author_name");
        if (!direct.isEmpty()) return direct;
        JSONObject author = data.optJSONObject("author");
        if (author == null) author = data.optJSONObject("user");
        return readText(author, "name", "nickname", "uname", "authorName");
    }

    private String readBackupQualities(JSONArray backups) {
        if (backups == null || backups.length() == 0) return "";
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < backups.length() && i < 3; i++) {
            JSONObject item = backups.optJSONObject(i);
            if (item == null) continue;
            String label = firstNonBlank(readText(item, "quality"), readText(item, "label"));
            if (!label.isEmpty()) labels.add(label);
        }
        return TextUtils.join("、", labels);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.trim().isEmpty() ? (second == null ? "" : second.trim()) : first.trim();
    }

    private String formatDuration(double secondsValue) {
        if (secondsValue <= 0 || Double.isNaN(secondsValue) || Double.isInfinite(secondsValue)) return "";
        long seconds = Math.round(secondsValue);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remain = seconds % 60;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remain)
                : String.format(Locale.US, "%d:%02d", minutes, remain);
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.2f MB", bytes / (1024d * 1024d));
        if (bytes >= 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        return bytes + " B";
    }

    private void enqueueDownload() {
        VideoInfo video = currentVideo;
        if (video == null || video.mediaUrl.isEmpty() || !isHttpUrl(video.mediaUrl)) {
            setStatus(t("没有有效的视频直链，请重新解析。", "No valid video URL; please resolve again."), true);
            return;
        }
        if (video.mediaUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            setStatus(t("该链接是 HLS/m3u8 流，Dsu 下载管理通道暂不支持。", "This is an HLS/m3u8 stream; the Dsu download queue does not support it yet."), true);
            return;
        }
        String extension = mediaExtension(Uri.parse(video.mediaUrl));
        String baseName = truncateUtf8(sanitizeFileName(video.title), MAX_FILENAME_UTF8_BYTES);
        if (baseName.isEmpty()) baseName = "DsuVideo";
        String unique = "_" + (System.currentTimeMillis() % 100000000L);
        String fileName = baseName + unique + extension;
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        if (isHttpUrl(video.shareUrl)) headers.put("Referer", video.shareUrl);
        boolean accepted = ShortVideoQueue.enqueue(this, video.mediaUrl, fileName, video.platform, headers);
        if (accepted) {
            manageDownloadsButton.setVisibility(View.VISIBLE);
            setStatus(t("已加入 Dsu 下载管理：/storage/emulated/0/Download/DsuManager/短视频解析/", "Added to Dsu Download Manager: Download/DsuManager/Short Videos/") + fileName
                    + t("\n点击下方按钮可直接查看进度、暂停或继续。", "\nUse the button below to view progress, pause, or resume."), false);
            Toast.makeText(this, t("已加入 Dsu 下载队列", "Added to Dsu download queue"), Toast.LENGTH_SHORT).show();
        } else {
            setStatus(t("提交 Dsu 下载管理失败。请检查下载目录授权、存储权限或下载服务状态后重试。",
                    "Could not submit to Dsu Download Manager. Check storage access and service status, then retry."), true);
        }
    }

    private void enqueueImageDownloads(VideoInfo video) {
        if (video == null || video.imageUrls.isEmpty()) {
            setStatus(t("没有可下载的图片直链，请重新解析。", "No image URLs to download; please resolve again."), true);
            return;
        }
        List<Integer> chosen = new ArrayList<>(selectedImageIndexes);
        if (chosen.isEmpty()) {
            setStatus(t("请先选择至少一张图片，或点击“全选”。", "Select at least one image or tap Select all."), true);
            return;
        }
        String base = truncateUtf8(sanitizeFileName(video.title), 80);
        if (base.isEmpty()) base = "DsuImages";
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        if (isHttpUrl(video.shareUrl)) headers.put("Referer", video.shareUrl);

        int accepted = 0;
        int failed = 0;
        for (int i : chosen) {
            if (i < 0 || i >= video.imageUrls.size()) { failed++; continue; }
            String url = video.imageUrls.get(i);
            if (!isHttpUrl(url)) { failed++; continue; }
            String fileName = base + String.format(Locale.US, "_%02d", i + 1) + imageExtension(url);
            if (ShortVideoQueue.enqueue(this, url, fileName, video.platform, headers)) accepted++;
            else failed++;
        }
        if (accepted > 0) {
            manageDownloadsButton.setVisibility(View.VISIBLE);
            String status = t("已提交 " + accepted + " 张图片到 Dsu 下载管理：/storage/emulated/0/Download/DsuManager/短视频解析/",
                    accepted + " images added to Dsu Download Manager: Download/DsuManager/Short Videos/");
            if (failed > 0) status += t("\n另有 " + failed + " 张提交失败。", "\n" + failed + " failed to submit.");
            status += t("\n点击下方按钮查看进度。", "\nUse the button below to view progress.");
            setStatus(status, failed > 0);
            Toast.makeText(this, t("图片已加入 Dsu 下载队列", "Images added to Dsu download queue"), Toast.LENGTH_SHORT).show();
        } else {
            setStatus(t("图片下载任务提交失败，请检查 Dsu 下载服务状态。", "Could not submit image download tasks; check Dsu Download Manager."), true);
        }
    }

    private String imageExtension(String url) {
        try {
            String path = Uri.parse(url).getLastPathSegment();
            if (path != null) {
                int dot = path.lastIndexOf('.');
                if (dot >= 0) {
                    String ext = path.substring(dot).toLowerCase(Locale.ROOT);
                    if (ext.matches("\\.(jpg|jpeg|png|webp|gif|avif|heic)")) return ext;
                }
            }
        } catch (Throwable ignored) { }
        return ".jpg";
    }

    private String mediaExtension(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String suffix = path.substring(dot).toLowerCase(Locale.ROOT);
                if (suffix.matches("\\.(mp4|m4v|mov|webm|mkv)")) return suffix;
            }
        }
        return ".mp4";
    }

    private String sanitizeFileName(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").replaceAll("^[. ]+|[. ]+$", "").trim();
    }

    private String truncateUtf8(String value, int maxBytes) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > maxBytes) break;
            out.appendCodePoint(codePoint);
            used += size;
            i += Character.charCount(codePoint);
        }
        return out.toString().replaceAll("[. ]+$", "").trim();
    }

    private String truncateCodePoints(String value, int maxPoints) {
        if (value == null) return "";
        int points = value.codePointCount(0, value.length());
        if (points <= maxPoints) return value;
        int end = value.offsetByCodePoints(0, maxPoints);
        return value.substring(0, end) + "…";
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && linkInput != null) imm.hideSoftInputFromWindow(linkInput.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        imageWorker.shutdownNow();
        super.onDestroy();
    }

    private static final class VideoInfo {
        String mediaUrl = "";
        String shareUrl = "";
        String coverUrl = "";
        String title = "";
        String author = "";
        String platform = "";
        String quality = "";
        String sizeLabel = "";
        String duration = "";
        String backupQualities = "";
        String apiMessage = "";
        String mediaType = "";
        boolean isImagePost;
        final List<String> imageUrls = new ArrayList<>();
        int width;
        int height;
    }
}
