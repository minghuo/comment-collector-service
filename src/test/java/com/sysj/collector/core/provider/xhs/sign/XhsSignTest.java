package com.sysj.collector.core.provider.xhs.sign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小红书 x-s 纯算签名单测。
 *
 * <p>载荷偏移断言对齐 xhshow 的 {@code tests/xys-v2.test.ts}：
 * 版本 4B / seed 4B / 时间戳 8B / 内容长度 4B / MD5 8B（XOR seed）/
 * a1 长度+52B / appId 长度+10B / env 16B / a3 前缀 4B / dsf 16B。
 * 固定 {@code seed=0x12345678}、{@code ts}，其余随机项（pageLoadTs/sequence/windowProps）
 * 不参与断言。
 */
@DisplayName("小红书 x-s 纯算签名")
class XhsSignTest {

    private static final String A1 = "a".repeat(52);
    private static final long TS = 1794104314L;
    private static final int SEED = 0x12345678;
    private static final String URI = "/api/sns/web/v2/comment/page";

    private static Map<String, String> params() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("note_id", "6aeaf8fc000000000e00e2c4");
        params.put("cursor", "");
        params.put("top_comment_id", "");
        params.put("image_formats", "jpg,webp,avif");
        params.put("xsec_token", "ABrTwl6trOin-6jripTiNrZ0cBOcSHzU68Au8C77rgx3w%3D");
        return params;
    }

    private static String xysJson(String xs) {
        assertTrue(xs.startsWith(XhsSign.XYS_PREFIX), "XYS_ 前缀");
        return new String(Base64.getDecoder().decode(XhsSign.reverseCustomBase64(
                xs.substring(XhsSign.XYS_PREFIX.length()))), StandardCharsets.UTF_8);
    }

    private static byte[] payloadFromX3(String x3) {
        assertTrue(x3.startsWith(XhsSign.X3_PREFIX), "mns0301_ 前缀");
        byte[] encoded = Base64.getDecoder().decode(
                XhsSign.reverseX3(x3.substring(XhsSign.X3_PREFIX.length())));
        return XhsSign.xorKey(encoded);
    }

    @Test
    @DisplayName("144 字节载荷：版本/seed/时间戳/内容长度/MD5/env/dsf 偏移")
    void payloadLayoutMatchesReference() {
        Map<String, String> params = params();
        String content = XhsSign.contentString(URI, params);
        String md5 = XhsSign.md5Hex(content);
        byte[] md5Bytes = XhsSign.hexToBytes(md5);

        String xs = XhsSign.signXs(URI, params, A1, TS, SEED);
        String json = xysJson(xs);
        String x3 = json.substring(json.indexOf(XhsSign.X3_PREFIX), json.indexOf("\",\"x4\""));
        byte[] p = payloadFromX3(x3);

        assertEquals(144, p.length, "载荷长度");
        assertEquals(121, p[0] & 0xFF);
        assertEquals(104, p[1] & 0xFF);
        assertEquals(96, p[2] & 0xFF);
        assertEquals(41, p[3] & 0xFF);

        int seedByte = p[4] & 0xFF;
        assertEquals(SEED & 0xFF, seedByte, "seed 低字节");
        assertEquals(TS * 1000L, readLe(p, 8, 8), "时间戳 8B LE");
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, readLe(p, 32, 4), "内容 UTF-8 长度");
        for (int i = 0; i < 8; i++) {
            assertEquals(md5Bytes[i] & 0xFF, (p[36 + i] & 0xFF) ^ seedByte, "md5[" + i + "] ^ seed");
        }

        assertEquals(52, p[44] & 0xFF, "a1 长度字节");
        for (int i = 0; i < 52; i++) {
            assertEquals('a', p[45 + i] & 0xFF, "a1[" + i + "]");
        }
        assertEquals(10, p[97] & 0xFF, "appId 长度字节");
        assertEquals("xhs-pc-web", new String(p, 98, 10, StandardCharsets.UTF_8));

        assertEquals(1, p[108] & 0xFF);
        assertEquals(seedByte ^ 115, p[109] & 0xFF);
        int[] envTail = {249, 65, 103, 103, 201, 181, 129, 99, 94, 7, 68, 250, 132, 21};
        for (int i = 0; i < envTail.length; i++) {
            assertEquals(envTail[i], p[110 + i] & 0xFF, "env[" + (i + 2) + "]");
        }

        assertEquals(2, p[124] & 0xFF);
        assertEquals(97, p[125] & 0xFF);
        assertEquals(51, p[126] & 0xFF);
        assertEquals(16, p[127] & 0xFF);

        byte[] hashInput = new byte[24];
        System.arraycopy(p, 8, hashInput, 0, 8);
        System.arraycopy(md5Bytes, 0, hashInput, 8, 16);
        byte[] dsf = XhsSign.customHashV2(hashInput);
        for (int i = 0; i < 16; i++) {
            assertEquals(dsf[i] & 0xFF, (p[128 + i] & 0xFF) ^ seedByte, "dsf[" + i + "]");
        }
    }

    @Test
    @DisplayName("x-s JSON：x0..x3、x4 空、x5=内容 MD5")
    void xysJsonCarriesContentMd5() {
        Map<String, String> params = params();
        String content = XhsSign.contentString(URI, params);
        String json = xysJson(XhsSign.signXs(URI, params, A1, TS, SEED));

        assertTrue(json.startsWith("{\"x0\":\"4.4.3\",\"x1\":\"xhs-pc-web\",\"x2\":\"Windows\""));
        assertTrue(json.contains("\"x4\":\"\""));
        assertTrue(json.contains("\"x5\":\"" + XhsSign.md5Hex(content) + "\""));
    }

    @Test
    @DisplayName("XYW_：AES 解密后是 x1..x4 四项 message")
    void xywDecryptsToMessage() throws Exception {
        Map<String, String> params = params();
        String content = XhsSign.contentString(URI, params);
        String xyw = XhsSign.signXyw(URI, params, A1, TS);

        assertTrue(xyw.startsWith(XhsSign.XYW_PREFIX));
        String json = new String(Base64.getDecoder().decode(
                xyw.substring(XhsSign.XYW_PREFIX.length())), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"signSvn\":\"56\""));
        assertTrue(json.contains("\"signType\":\"x2\""));
        assertTrue(json.contains("\"appId\":\"xhs-pc-web\""));
        assertTrue(json.contains("\"signVersion\":\"1\""));

        byte[] cipherBytes = XhsSign.hexToBytes(json.split("\"payload\":\"")[1].split("\"")[0]);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(XhsSign.AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(XhsSign.AES_IV.getBytes(StandardCharsets.UTF_8)));
        byte[] plain = cipher.doFinal(cipherBytes);
        int pad = plain[plain.length - 1] & 0xFF;
        String base64 = new String(plain, 0, plain.length - pad, StandardCharsets.US_ASCII);

        String message = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        assertEquals("x1=" + XhsSign.md5Hex("url=" + content)
                + ";x2=" + XhsSign.XYW_ENV_FLAGS
                + ";x3=" + A1 + ";x4=" + TS * 1000L + ";", message);
    }

    @Test
    @DisplayName("x-s-common：customBase64(JSON)，x4=webBuild、x5=a1、x8=b1、x9=CRC32(b1)")
    void xsCommonFields() {
        String xsc = XhsSign.signXsCommon(A1, "6.60.2", "b1-demo");
        String json = new String(Base64.getDecoder().decode(XhsSign.reverseCustomBase64(xsc)),
                StandardCharsets.UTF_8);

        assertTrue(json.startsWith("{\"s0\":5,\"s1\":\"\""));
        assertTrue(json.contains("\"x4\":\"6.60.2\""));
        assertTrue(json.contains("\"x5\":\"" + A1 + "\""));
        assertTrue(json.contains("\"x8\":\"b1-demo\""));
        assertTrue(json.contains("\"x9\":" + XhsSign.crc32JsInt("b1-demo")));
        assertTrue(json.contains("\"x10\":0"));
        assertTrue(json.contains("\"x11\":\"normal\""));
    }

    @Test
    @DisplayName("buildQuery：只转义 value 里的 =，逗号保留，空参数为空串")
    void buildQueryEncoding() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a", "1");
        params.put("token", "ab%3Dcd");
        params.put("formats", "jpg,webp,avif");
        params.put("blank", null);

        assertEquals("a=1&token=ab%3Dcd&formats=jpg,webp,avif&blank=", XhsSign.buildQuery(params));
        assertEquals("", XhsSign.buildQuery(new LinkedHashMap<>()));
        assertEquals(URI, XhsSign.contentString(URI, new LinkedHashMap<>()));
        assertEquals(URI + "?a=1", XhsSign.contentString(URI, Map.of("a", "1")));
    }

    private static long readLe(byte[] bytes, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value |= (bytes[offset + i] & 0xFFL) << (8 * i);
        }
        return value;
    }
}
