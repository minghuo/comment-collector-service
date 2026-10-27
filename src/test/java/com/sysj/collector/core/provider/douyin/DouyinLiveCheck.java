package com.sysj.collector.core.provider.douyin;

import com.sysj.collector.core.provider.douyin.sign.ABogusSigner;
import com.sysj.collector.core.provider.douyin.sign.DouyinCookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抖音评论接口的**线上**对照（默认跳过，需显式开启）。
 *
 * <pre>
 * mvn -o test -Dtest=DouyinLiveCheck -Ddouyin.live=true \
 *     -Ddouyin.liveFile=C:/Users/bewilder/dyres/live.json
 * </pre>
 *
 * <p>{@code live.json} 由操作者自己准备（含 {@code cookie}/{@code uifid}/{@code referer}/{@code userAgent}/
 * {@code awemeId}），**不入库**。本用例只发一次请求：cookie 的登录态是外部输入，签名链路是这里要验证的
 * 那一半 —— 返回 {@code status_code=0} 且有 {@code comments} 即说明 a_bogus 计算、cookie 默认值补全、
 * 请求头拼装三者都对。
 */
@EnabledIfSystemProperty(named = "douyin.live", matches = "true")
class DouyinLiveCheck {

    @Test
    void commentListReturnsData() throws Exception {
        Path file = Path.of(System.getProperty("douyin.liveFile", "live.json"));
        String raw = Files.readString(file, StandardCharsets.UTF_8);

        String cookieText = stringField(raw, "cookie");
        String uifid = stringField(raw, "uifid");
        String referer = stringField(raw, "referer");
        String userAgent = stringField(raw, "userAgent");
        String awemeId = stringField(raw, "awemeId");

        DouyinCookie cookie = DouyinCookie.parse(cookieText).withDefaults();
        String query = DouyinLocalCrawlerProvider.buildQuery(awemeId, null, 0, 10, cookie);
        String aBogus = ABogusSigner.of(userAgent).sign(query);
        String url = DouyinLocalCrawlerProvider.COMMENT_LIST_API + "?" + query
                + "&a_bogus=" + URLEncoder.encode(aBogus, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("accept", "application/json, text/plain, */*")
                .header("accept-language", "zh-CN,zh;q=0.9")
                .header("referer", referer == null ? "https://www.douyin.com/" : referer)
                .header("user-agent", userAgent == null ? ABogusSigner.DEFAULT_USER_AGENT : userAgent)
                .header("cookie", cookieText)
                .header("uifid", uifid == null ? "" : uifid)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        String body = response.body();
        System.out.println("[douyin-live] status=" + response.statusCode() + " bytes=" + body.length());
        System.out.println("[douyin-live] body=" + body.substring(0, Math.min(240, body.length()))
                .replace("\n", " "));

        assertEquals(200, response.statusCode(), "HTTP 状态应为 200");
        assertTrue(body.contains("\"status_code\":0"), "应返回 status_code=0：" + body.substring(0, Math.min(200, body.length())));
        assertTrue(body.contains("\"comments\""), "应包含 comments 数组");
    }

    /** 从 live.json 取出字符串字段（扁平结构，避免为一条线上用例引入完整 JSON 解析）。 */
    private static String stringField(String json, String name) {
        String key = "\"" + name + "\"";
        int at = json.indexOf(key);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + key.length());
        int quote = json.indexOf('"', colon + 1);
        int end = quote + 1;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') {
                break;
            }
            end++;
        }
        return json.substring(quote + 1, end)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
