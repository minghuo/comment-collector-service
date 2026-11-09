package com.sysj.collector.core.provider.xhs.sign;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 小红书 web 端签名（纯算，无浏览器）。
 *
 * <p>对齐 {@code xhshow}（ikenxuan/xhshow-ts）的两套格式：
 * <ul>
 *   <li>{@link #signXs} —— 传统 {@code XYS_}：144 字节 mns0301 载荷 + 自定义 base64；</li>
 *   <li>{@link #signXyw} —— 新 {@code XYW_}：AES-128-CBC；数据类接口（{@code user_posted} 等）
 *       会以 HTTP 406 拒绝 {@code XYS_}，账号历史必须用它。</li>
 * </ul>
 *
 * <h3>签名口径（与前端 seccore_signv2 一致）</h3>
 * <ul>
 *   <li>内容串 = {@code uri + "?" + k=v&...}（GET），value 里只有 {@code =} 替换为 {@code %3D}，
 *       逗号等其它字符原样保留；</li>
 *   <li>{@code dValue = MD5(内容串)}，{@code mValue = GET ? dValue : MD5(uri)}；</li>
 *   <li>{@code x3 = "mns0301_" + encodeX3(xor(payload))}；{@code x-s = "XYS_" + customBase64(JSON)}；</li>
 *   <li>{@code x-t = 秒级时间戳 * 1000}（毫秒字符串）；</li>
 *   <li>{@code x-s-common} 只在调用方提供 b1（浏览器 localStorage 的指纹）时生成 ——
 *       本实现不移植 10KB 指纹数据池，伪造的 b1 比不带更糟。</li>
 * </ul>
 *
 * <p>所有方法无状态；同一请求必须用同一组 {@code params} 生成 URL 与签名
 * （{@link #buildQuery(Map)} 与签名共用一套编码规则）。
 */
public final class XhsSign {

    static final String X3_PREFIX = "mns0301_";
    static final String XYS_PREFIX = "XYS_";
    static final String XYW_PREFIX = "XYW_";

    static final String SIGN_X0 = "4.4.3";
    static final String SIGN_X1 = "xhs-pc-web";
    static final String SIGN_X2 = "Windows";
    static final String APP_ID = "xhs-pc-web";

    static final String SIGN_SVN = "56";
    static final String SIGN_TYPE = "x2";
    static final String SIGN_VERSION = "1";
    static final String AES_KEY = "7cc4adla5ay0701v";
    static final String AES_IV = "4uzjr7mbsibcaldp";

    /**
     * XYW_ 载荷里的 19 位环境标志。第 11 位是 webdriver 位（真机 0、自动化 1），
     * 不随操作系统变化；照抄真机抓包值。
     */
    static final String XYW_ENV_FLAGS = "0|0|0|1|0|0|1|0|0|0|1|0|0|0|0|1|0|0|0";

    static final String STANDARD_BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    static final String CUSTOM_BASE64_ALPHABET =
            "ZmserbBoHQtNP+wOcza/LpngG8yJq42KWYj0DSfdikx3VT16IlUAFM97hECvuRX5";
    static final String X3_BASE64_ALPHABET =
            "MfgqrsbcyzPQRStuvC7mn501HIJBo2DEFTKdeNOwxWXYZap89+/A4UVLhijkl63G";

    /** 144 字节载荷的 XOR 密钥。 */
    static final String HEX_KEY =
            "71a302257793271ddd273bcee3e4b98d9d7935e1da33f5765e2ea8afb6dc77a51a499d23b67c20660025860cbf13d4540d92497f58686c574e508f46e1956344f39139bf4faf22a3eef120b79258145b2feb5193b6478669961298e79bedca646e1a693a926154a5a7a1bd1cf0dedb742f917a747a1e388b234f2277516db7116035439730fa61e9822a0eca7bff72d8";

    static final int PAYLOAD_LENGTH = 144;
    static final int A1_LENGTH = 52;
    static final int APP_ID_LENGTH = 10;

    static final int[] VERSION_BYTES = {121, 104, 96, 41};
    static final int[] A3_PREFIX = {2, 97, 51, 16};
    static final int[] ENV_TABLE = {115, 248, 83, 102, 103, 201, 181, 131, 99, 94, 4, 68, 250, 132, 21};
    static final int[] ENV_CHECKS_DEFAULT = {0, 1, 18, 1, 0, 0, 0, 2, 0, 0, 3, 0, 0, 0, 0};
    static final int[] HASH_IV = {1831565813, 461845907, (int) 2246822507L, (int) 3266489909L};

    /** x-s-common 的 x12 后半个分号值是「页面会话开始时刻」，进程启动时取一次即可。 */
    private static final long SESSION_START_MS = System.currentTimeMillis();

    private static final byte[] HEX_KEY_BYTES;
    private static final int[] CRC32_TABLE = new int[256];

    static {
        HEX_KEY_BYTES = hexToBytes(HEX_KEY);
        if (HEX_KEY_BYTES.length != PAYLOAD_LENGTH) {
            throw new IllegalStateException("HEX_KEY 必须是 144 字节，实际 " + HEX_KEY_BYTES.length);
        }
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            }
            CRC32_TABLE[i] = c;
        }
    }

    private XhsSign() {
    }

    // ──────────────────────────────────────────────────────────────────────
    // 对外入口
    // ──────────────────────────────────────────────────────────────────────

    /** 生成 {@code XYS_} 签名。 */
    public static String signXs(String uri, Map<String, String> params, String a1, long timestampSeconds) {
        return signXs(uri, params, a1, timestampSeconds, ThreadLocalRandom.current().nextInt());
    }

    static String signXs(String uri, Map<String, String> params, String a1, long timestampSeconds, int seed) {
        String content = contentString(uri, params);
        String dValue = md5Hex(content);
        String x3 = X3_PREFIX + x3Encode(buildPayload(dValue, dValue, a1, content, timestampSeconds, seed));
        String json = "{\"x0\":\"" + SIGN_X0 + "\",\"x1\":\"" + SIGN_X1 + "\",\"x2\":\"" + SIGN_X2
                + "\",\"x3\":\"" + x3 + "\",\"x4\":\"\",\"x5\":\"" + dValue + "\"}";
        return XYS_PREFIX + customBase64(json);
    }

    /** 生成 {@code XYW_} 签名（AES-128-CBC）。 */
    public static String signXyw(String uri, Map<String, String> params, String a1, long timestampSeconds) {
        String content = contentString(uri, params);
        String x1 = md5Hex("url=" + content);
        String timestampMs = String.valueOf(timestampSeconds * 1000L);
        String message = "x1=" + x1 + ";x2=" + XYW_ENV_FLAGS + ";x3=" + a1 + ";x4=" + timestampMs + ";";
        byte[] plain = pkcs7Pad(Base64.getEncoder().encode(message.getBytes(StandardCharsets.UTF_8)));
        byte[] cipherBytes = aes128Cbc(plain);
        String payloadHex = toHex(cipherBytes);
        String json = "{\"signSvn\":\"" + SIGN_SVN + "\",\"signType\":\"" + SIGN_TYPE
                + "\",\"appId\":\"" + APP_ID + "\",\"signVersion\":\"" + SIGN_VERSION
                + "\",\"payload\":\"" + payloadHex + "\"}";
        return XYW_PREFIX + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 {@code x-s-common}。
     *
     * <p>b1 缺失时按空串处理（x8 为空、x9=CRC32("")）；调用方应在 b1 为空时直接不发该头。
     *
     * @param a1       Cookie 里的 a1
     * @param webBuild Cookie 里的 webBuild（为空时用 6.56.3）
     * @param b1       浏览器 localStorage 的 b1（可空）
     */
    public static String signXsCommon(String a1, String webBuild, String b1) {
        String b1Value = b1 == null ? "" : b1;
        int x9 = crc32JsInt(b1Value);
        long now = System.currentTimeMillis();
        String json = "{\"s0\":5,\"s1\":\"\",\"x0\":\"1\",\"x1\":\"" + SIGN_X0 + "\",\"x2\":\"" + SIGN_X2
                + "\",\"x3\":\"" + SIGN_X1 + "\",\"x4\":\"" + defaultIfBlank(webBuild, "6.56.3")
                + "\",\"x5\":\"" + a1 + "\",\"x6\":\"\",\"x7\":\"\",\"x8\":\"" + b1Value
                + "\",\"x9\":" + x9 + ",\"x10\":0,\"x11\":\"normal\",\"x12\":\"" + now + ";" + SESSION_START_MS + "\"}";
        return customBase64(json);
    }

    /** {@code x-t}：毫秒时间戳字符串。 */
    public static String xt(long timestampSeconds) {
        return String.valueOf(timestampSeconds * 1000L);
    }

    /**
     * 组装 query 串（不含 {@code ?}）。与签名共用同一编码：只把 value 里的 {@code =} 换成
     * {@code %3D}，逗号等字符原样保留（小红书服务端按收到原串重算 MD5）。
     */
    public static String buildQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder query = new StringBuilder(256);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            query.append(entry.getKey()).append('=').append(value.replace("=", "%3D"));
        }
        return query.toString();
    }

    /** 便捷方法：有序参数表。 */
    public static Map<String, String> params() {
        return new LinkedHashMap<>();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 144 字节载荷
    // ──────────────────────────────────────────────────────────────────────

    static String contentString(String uri, Map<String, String> params) {
        String query = buildQuery(params);
        return query.isEmpty() ? uri : uri + "?" + query;
    }

    /**
     * mns0301 载荷（144 字节）。
     *
     * @param md5OfContent 内容串 MD5（hex）
     * @param md5OfPath    路径 MD5（GET 同内容串；POST 为 uri 的 MD5）
     * @param content      内容串（用于写入 UTF-8 字节长度）
     * @param seed         4 字节随机种子（测试可固定）
     */
    static byte[] buildPayload(String md5OfContent, String md5OfPath, String a1, String content,
                               long timestampSeconds, int seed) {
        int seedByte = seed & 0xFF;
        long tsMs = timestampSeconds * 1000L;
        byte[] tsBytes = leBytes(tsMs, 8);

        byte[] payload = new byte[PAYLOAD_LENGTH];
        int p = 0;
        for (int b : VERSION_BYTES) {
            payload[p++] = (byte) b;
        }
        p = writeLe(payload, p, seed & 0xFFFFFFFFL, 4);
        p = writeLe(payload, p, tsMs, 8);

        // 无会话态：页加载时刻略早于当前、序号/属性长度随机（前端同款行为）
        int timeOffset = 10 + ThreadLocalRandom.current().nextInt(41);
        p = writeLe(payload, p, (timestampSeconds - timeOffset) * 1000L, 8);
        p = writeLe(payload, p, 15 + ThreadLocalRandom.current().nextInt(36), 4);
        p = writeLe(payload, p, 1000 + ThreadLocalRandom.current().nextInt(201), 4);
        p = writeLe(payload, p, content.getBytes(StandardCharsets.UTF_8).length, 4);

        byte[] md5Bytes = hexToBytes(md5OfContent);
        for (int i = 0; i < 8; i++) {
            payload[p++] = (byte) (md5Bytes[i] ^ seedByte);
        }

        byte[] a1Bytes = a1 == null ? new byte[0] : a1.getBytes(StandardCharsets.UTF_8);
        payload[p++] = (byte) A1_LENGTH;
        for (int i = 0; i < A1_LENGTH; i++) {
            payload[p++] = i < a1Bytes.length ? a1Bytes[i] : 0;
        }

        byte[] appIdBytes = APP_ID.getBytes(StandardCharsets.UTF_8);
        payload[p++] = (byte) APP_ID_LENGTH;
        System.arraycopy(appIdBytes, 0, payload, p, Math.min(appIdBytes.length, APP_ID_LENGTH));
        p += APP_ID_LENGTH;

        payload[p++] = 1;
        payload[p++] = (byte) (seedByte ^ ENV_TABLE[0]);
        for (int i = 1; i < 15; i++) {
            payload[p++] = (byte) (ENV_TABLE[i] ^ ENV_CHECKS_DEFAULT[i]);
        }

        for (int b : A3_PREFIX) {
            payload[p++] = (byte) b;
        }
        byte[] md5PathBytes = hexToBytes(md5OfPath);
        byte[] hashInput = new byte[24];
        System.arraycopy(tsBytes, 0, hashInput, 0, 8);
        System.arraycopy(md5PathBytes, 0, hashInput, 8, 16);
        for (int b : customHashV2(hashInput)) {
            payload[p++] = (byte) (b ^ seedByte);
        }
        return payload;
    }

    /** 载荷每字节 XOR HEX_KEY。 */
    static byte[] xorKey(byte[] payload) {
        byte[] out = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            out[i] = (byte) (payload[i] ^ HEX_KEY_BYTES[i]);
        }
        return out;
    }

    /** x3：先标准 base64（带 = 填充），再按字母表替换。 */
    static String x3Encode(byte[] payload) {
        return translate(Base64.getEncoder().encodeToString(xorKey(payload)), X3_BASE64_ALPHABET);
    }

    /** 前端 CRC32 变体（x-s-common 的 x9）。 */
    static int crc32JsInt(String text) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < text.length(); i++) {
            crc = (crc >>> 8) ^ CRC32_TABLE[(crc ^ (text.charAt(i) & 0xFF)) & 0xFF];
        }
        long unsigned = (0xFFFFFFFFL ^ (crc & 0xFFFFFFFFL)) ^ 0xEDB88320L;
        return (int) (unsigned & 0xFFFFFFFFL);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部工具
    // ──────────────────────────────────────────────────────────────────────

    static String md5Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return toHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    /** customHashV2：输入长度必须是 8 的倍数，输出 16 字节。 */
    static byte[] customHashV2(byte[] input) {
        int s0 = HASH_IV[0];
        int s1 = HASH_IV[1];
        int s2 = HASH_IV[2];
        int s3 = HASH_IV[3];
        int length = input.length;

        s0 ^= length;
        s1 ^= length << 8;
        s2 ^= length << 16;
        s3 ^= length << 24;

        for (int i = 0; i < length / 8; i++) {
            int o = i * 8;
            int v0 = (input[o] & 0xFF) | ((input[o + 1] & 0xFF) << 8)
                    | ((input[o + 2] & 0xFF) << 16) | ((input[o + 3] & 0xFF) << 24);
            int v1 = (input[o + 4] & 0xFF) | ((input[o + 5] & 0xFF) << 8)
                    | ((input[o + 6] & 0xFF) << 16) | ((input[o + 7] & 0xFF) << 24);

            s0 = Integer.rotateLeft((int) (((s0 & 0xFFFFFFFFL) + (v0 & 0xFFFFFFFFL)) & 0xFFFFFFFFL) ^ s2, 7);
            s1 = Integer.rotateLeft((int) ((((v0 ^ s1) & 0xFFFFFFFFL) + (s3 & 0xFFFFFFFFL)) & 0xFFFFFFFFL), 11);
            s2 = Integer.rotateLeft((int) ((((s2 & 0xFFFFFFFFL) + (v1 & 0xFFFFFFFFL)) & 0xFFFFFFFFL) ^ s0), 13);
            s3 = Integer.rotateLeft((int) ((((s3 ^ v1) & 0xFFFFFFFFL) + (s1 & 0xFFFFFFFFL)) & 0xFFFFFFFFL), 17);
        }

        int t0 = s0 ^ length;
        int t1 = s1 ^ t0;
        int t2 = (int) (((s2 & 0xFFFFFFFFL) + (t1 & 0xFFFFFFFFL)) & 0xFFFFFFFFL);
        int t3 = s3 ^ t2;

        int r0 = Integer.rotateLeft(t0, 9);
        int r1 = Integer.rotateLeft(t1, 13);
        int r2 = Integer.rotateLeft(t2, 17);
        int r3 = Integer.rotateLeft(t3, 19);

        s0 = (int) (((r0 & 0xFFFFFFFFL) + (r2 & 0xFFFFFFFFL)) & 0xFFFFFFFFL);
        s1 = r1 ^ r3;
        s2 = (int) (((r2 & 0xFFFFFFFFL) + (s0 & 0xFFFFFFFFL)) & 0xFFFFFFFFL);
        s3 = r3 ^ s1;

        byte[] out = new byte[16];
        int p = 0;
        for (int value : new int[]{s0, s1, s2, s3}) {
            p = writeLe(out, p, value & 0xFFFFFFFFL, 4);
        }
        return out;
    }

    private static byte[] aes128Cbc(byte[] paddedPlain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(AES_IV.getBytes(StandardCharsets.UTF_8)));
            return cipher.doFinal(paddedPlain);
        } catch (Exception e) {
            throw new IllegalStateException("XYW AES 加密失败", e);
        }
    }

    private static byte[] pkcs7Pad(byte[] data) {
        int pad = 16 - data.length % 16;
        byte[] out = new byte[data.length + pad];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < out.length; i++) {
            out[i] = (byte) pad;
        }
        return out;
    }

    private static String translate(String standardBase64, String alphabet) {
        StringBuilder sb = new StringBuilder(standardBase64.length());
        for (int i = 0; i < standardBase64.length(); i++) {
            char c = standardBase64.charAt(i);
            int index = STANDARD_BASE64_ALPHABET.indexOf(c);
            sb.append(index >= 0 ? alphabet.charAt(index) : c);
        }
        return sb.toString();
    }

    static String customBase64(String text) {
        return translate(Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)),
                CUSTOM_BASE64_ALPHABET);
    }

    static String reverseCustomBase64(String text) {
        return reverseTranslate(text, CUSTOM_BASE64_ALPHABET);
    }

    static String reverseX3(String text) {
        return reverseTranslate(text, X3_BASE64_ALPHABET);
    }

    private static String reverseTranslate(String text, String alphabet) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int index = alphabet.indexOf(c);
            sb.append(index >= 0 ? STANDARD_BASE64_ALPHABET.charAt(index) : c);
        }
        return sb.toString();
    }

    private static int writeLe(byte[] out, int offset, long value, int length) {
        for (int i = 0; i < length; i++) {
            out[offset + i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return offset + length;
    }

    private static byte[] leBytes(long value, int length) {
        byte[] out = new byte[length];
        writeLe(out, 0, value, length);
        return out;
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException("非法 hex: " + hex);
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
