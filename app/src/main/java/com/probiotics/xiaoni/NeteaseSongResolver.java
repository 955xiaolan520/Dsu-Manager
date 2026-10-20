package com.probiotics.xiaoni;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Local, no-login NetEase Cloud Music song resolver adapted from
 * jiuhunwl/short_videos api/wyy.py. It only returns a URL supplied by NetEase;
 * it does not synthesize links or bypass paid-content checks.
 */
public final class NeteaseSongResolver {
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 "
            + "NeteaseMusicDesktop/2.10.2.200154";
    private static final String COOKIE = "os=pc; appver=8.9.75; osver=; deviceId=pyncm!; MUSIC_U=";
    private static final String PLAYER_URL = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1";
    private static final String PLAYER_PATH = "/api/song/enhance/player/url/v1";
    private static final String SONG_DETAIL_URL = "https://interface3.music.163.com/api/v3/song/detail";
    private static final String AES_KEY = "e82ckenh8dichen8";
    private static final Pattern QUERY_ID = Pattern.compile("(?:^|[?&#])(?:id|ids)=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_ID = Pattern.compile("/song/(\\d+)(?:[/?#]|$)", Pattern.CASE_INSENSITIVE);

    private NeteaseSongResolver() { }

    public static Result resolve(OkHttpClient client, String shareUrl) throws IOException {
        String resolvedUrl = resolveShareUrl(client, shareUrl);
        String songId = extractSongId(resolvedUrl);
        if (songId.isEmpty()) {
            throw new IOException("无法从网易云分享链接中提取歌曲 ID；请复制完整的歌曲分享链接。");
        }

        JSONObject songInfo = requestSongInfo(client, songId);
        JSONObject playerResponse = requestPlayerUrl(client, songId);
        String lyrics = requestLyrics(client, songId);
        JSONArray data = playerResponse.optJSONArray("data");
        JSONObject playerInfo = data != null && data.length() > 0 ? data.optJSONObject(0) : null;
        String audioUrl = playerInfo == null ? "" : safeString(playerInfo.optString("url", ""));
        if (audioUrl.isEmpty() || "null".equalsIgnoreCase(audioUrl)) {
            int fee = songInfo.optInt("fee", -1);
            int itemCode = playerInfo == null ? playerResponse.optInt("code", -1)
                    : playerInfo.optInt("code", playerResponse.optInt("code", -1));
            if (fee == 1) {
                throw new IOException("网易云将这首歌标记为付费曲目；无登录状态下官方接口没有返回可用音频直链。请在网易云音乐客户端确认播放权限。");
            }
            throw new IOException("网易云播放接口未提供免登录音频直链（code=" + itemCode + "）；未生成或伪造下载地址。");
        }
        if (audioUrl.startsWith("http://")) audioUrl = "https://" + audioUrl.substring("http://".length());
        if (!isHttpUrl(audioUrl)) throw new IOException("网易云播放接口返回的音频地址无效。");

        Result result = new Result();
        result.songId = songId;
        result.audioUrl = audioUrl;
        result.title = firstNonBlank(songInfo.optString("name", ""), "网易云歌曲 " + songId);
        result.artist = readArtists(songInfo.optJSONArray("ar"));
        result.lyrics = lyrics;
        JSONObject album = songInfo.optJSONObject("al");
        if (album == null) album = songInfo.optJSONObject("album");
        result.coverUrl = album == null ? "" : safeString(album.optString("picUrl", album.optString("picUrl", "")));
        if (result.coverUrl.startsWith("http://")) result.coverUrl = "https://" + result.coverUrl.substring("http://".length());
        result.durationSeconds = songInfo.optDouble("dt", 0d) / 1000d;
        if (result.durationSeconds <= 0d && playerInfo != null) {
            result.durationSeconds = playerInfo.optDouble("time", 0d) / 1000d;
        }
        long bytes = playerInfo == null ? 0L : playerInfo.optLong("size", 0L);
        result.sizeLabel = formatBytes(bytes);
        result.quality = playerInfo == null ? "" : safeString(playerInfo.optString("level", ""));
        return result;
    }

    private static String resolveShareUrl(OkHttpClient client, String shareUrl) throws IOException {
        HttpUrl parsed = HttpUrl.parse(shareUrl);
        if (parsed == null) throw new IOException("网易云分享链接格式无效。");
        String host = parsed.host().toLowerCase(Locale.ROOT);
        if (!host.equals("163cn.tv")) return shareUrl;

        Request request = new Request.Builder().url(parsed)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "https://music.163.com/")
                .get().build();
        try (Response response = client.newCall(request).execute()) {
            return response.request().url().toString();
        }
    }

    private static String extractSongId(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed != null) {
            String id = firstNonBlank(parsed.queryParameter("id"), parsed.queryParameter("ids"));
            if (id.matches("\\d+")) return id;
            String fragment = parsed.fragment();
            if (fragment != null) {
                Matcher fragmentMatcher = QUERY_ID.matcher(fragment);
                if (fragmentMatcher.find()) return fragmentMatcher.group(1);
            }
        }
        Matcher queryMatcher = QUERY_ID.matcher(value);
        if (queryMatcher.find()) return queryMatcher.group(1);
        Matcher pathMatcher = PATH_ID.matcher(value);
        return pathMatcher.find() ? pathMatcher.group(1) : "";
    }

    private static JSONObject requestSongInfo(OkHttpClient client, String songId) throws IOException {
        String idsJson = "[{\"id\": " + JSONObject.quote(songId) + ", \"v\": 0}]";
        Request request = new Request.Builder().url(SONG_DETAIL_URL)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "https://music.163.com/")
                .post(new FormBody.Builder().add("c", idsJson).build())
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return new JSONObject();
            JSONObject root = new JSONObject(body.string());
            JSONArray songs = root.optJSONArray("songs");
            return songs != null && songs.length() > 0 && songs.optJSONObject(0) != null
                    ? songs.optJSONObject(0) : new JSONObject();
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    private static String requestLyrics(OkHttpClient client, String songId) {
        Request request = new Request.Builder().url("https://interface3.music.163.com/api/song/lyric")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", COOKIE)
                .post(new FormBody.Builder()
                        .add("id", songId)
                        .add("cp", "false")
                        .add("tv", "0")
                        .add("lv", "0")
                        .add("rv", "0")
                        .add("kv", "0")
                        .add("yv", "0")
                        .add("ytv", "0")
                        .add("yrv", "0")
                        .build())
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return "";
            JSONObject root = new JSONObject(body.string());
            JSONObject lrc = root.optJSONObject("lrc");
            String text = lrc == null ? "" : safeString(lrc.optString("lyric", ""));
            if (!text.isEmpty()) return text;
            JSONObject translated = root.optJSONObject("tlyric");
            return translated == null ? "" : safeString(translated.optString("lyric", ""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JSONObject requestPlayerUrl(OkHttpClient client, String songId) throws IOException {
        String requestId = String.valueOf(20_000_000 + (int) (Math.random() * 10_000_000));
        String headerJson = "{\"os\": \"pc\", \"appver\": \"\", \"osver\": \"\", "
                + "\"deviceId\": \"pyncm!\", \"requestId\": " + JSONObject.quote(requestId) + "}";
        String payloadJson = "{\"ids\": [" + JSONObject.quote(songId)
                + "], \"level\": \"standard\", \"encodeType\": \"flac\", \"header\": "
                + JSONObject.quote(headerJson) + "}";
        String digest = md5Hex("nobody" + PLAYER_PATH + "use" + payloadJson + "md5forencrypt");
        String plain = PLAYER_PATH + "-36cd479b6b5-" + payloadJson + "-36cd479b6b5-" + digest;
        String encrypted = encryptEapi(plain);

        Request request = new Request.Builder().url(PLAYER_URL)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "")
                .header("Cookie", COOKIE)
                .post(new FormBody.Builder().add("params", encrypted).build())
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("请求网易云播放接口失败（HTTP " + response.code() + "）。");
            }
            return new JSONObject(body.string());
        } catch (org.json.JSONException e) {
            throw new IOException("网易云播放接口返回的数据格式无法识别。", e);
        }
    }

    private static String encryptEapi(String plain) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(encrypted.length * 2);
            for (byte value : encrypted) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("网易云请求参数加密失败。", e);
        }
    }

    private static String md5Hex(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("网易云请求签名计算失败。", e);
        }
    }

    private static String readArtists(JSONArray artists) {
        if (artists == null || artists.length() == 0) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            String name = safeString(artist.optString("name", ""));
            if (name.isEmpty()) continue;
            if (result.length() > 0) result.append(" / ");
            result.append(name);
        }
        return result.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0L) return "";
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.2f MB", bytes / (1024d * 1024d));
        return String.format(Locale.US, "%.0f KB", bytes / 1024d);
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.trim().isEmpty() ? (second == null ? "" : second.trim()) : first.trim();
    }

    private static String safeString(String value) {
        return value == null || "null".equalsIgnoreCase(value) ? "" : value.trim();
    }

    private static boolean isHttpUrl(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        return parsed != null && ("https".equals(parsed.scheme()) || "http".equals(parsed.scheme()));
    }

    public static final class Result {
        public String songId = "";
        public String audioUrl = "";
        public String title = "";
        public String artist = "";
        public String coverUrl = "";
        public String sizeLabel = "";
        public String quality = "";
        public String lyrics = "";
        public double durationSeconds;
    }
}
