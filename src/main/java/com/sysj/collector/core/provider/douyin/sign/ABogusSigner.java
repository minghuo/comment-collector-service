package com.sysj.collector.core.provider.douyin.sign;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 抖音 Web 端 {@code a_bogus} 签名的纯算法实现。
 *
 * <p>逐行对照参考实现（Evil0ctal/Douyin_TikTok_Download_API 的
 * {@code src/dtk/signing/native/abogus.py}，逆向自 {@code bdms.js} v1.0.1.19-fix.01）：
 * 消息 = 六个 {@code a_bogus} 输入（query、body、UA 三段摘要）经 50 个标量槽位、噪声扩展、
 * 非标准 RC4 加密与自定义 base64 编码后得到。对拍见 {@code ABogusSignerTest} 的
 * golden vector（同一 query/body/UA/时间戳下与参考实现逐字符相等）。
 *
 * <h3>不可随意改动的常量</h3>
 * <ul>
 *   <li>{@link #ALPHABETS}：只可 reachable 两个字母表（s3 绕 UA、s4 编最终密文），大小写敏感；</li>
 *   <li>{@link #SALT}：追加在 query/body 之后参与摘要；</li>
 *   <li>{@link #FIELD_ORDER}：50 个标量写进 body 的固定置换，顺序即算法；</li>
 *   <li>{@link #PAGE_ID}/{@link #AID}：抖音页面自己的标识，与 query 里的 aid 必须一致；</li>
 *   <li>{@link #PAYLOAD_KEY}：单字节 RC4 密钥。</li>
 * </ul>
 *
 * <h3>噪声与可复现性</h3>
 * {@code a_bogus} 里有若干随机字节（版本块低字节、头部第二个噪声字节、body 分组噪声），
 * 它们不参与校验和，用任何随机源都"正确"；但其中两个字节其实是 SDK 的环境自述
 * （浏览器家族、SDK 探针状态），写成均匀随机反而是破绽，见 {@link #headerNoise} /
 * {@link #tripwireNoise}。构造器允许注入 {@link DoubleSupplier}，测试用它固定噪声，
 * 生产用 {@link ThreadLocalRandom}。
 *
 * <p>线程安全：实例不可变（随机源除外），一个实例可复用；推荐按身份（UA）缓存。
 */
public final class ABogusSigner {

    // ──────────────────────────────────────────────────────────────────────
    // 常量（与参考实现一一对应，勿改）
    // ──────────────────────────────────────────────────────────────────────

    /** s3 = 给 User-Agent 加密后做 base64 用；s4 = 编最终 a_bogus 用。 */
    public static final String ALPHABET_S3 =
            "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe";
    /** 见 {@link #ALPHABET_S3}。 */
    public static final String ALPHABET_S4 =
            "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe";

    /** 追加在 query 与 body 之后再摘要的盐。 */
    public static final String SALT = "dhzx";

    /** 头部明文两个字节，结构校验的第一道关。 */
    private static final int[] HEADER_MAGIC = {3, 82};

    /** SDK 版本 {@code 1.0.1.19-fix.01} 解析出的版本块 {@code [1,0,1,0]}。 */
    private static final int[] SDK_VERSION = {1, 0, 1, 0};

    /** 单字节 RC4 密钥。 */
    private static final byte PAYLOAD_KEY = (byte) 0xD3;

    /** 2024-07-24T16:00:00Z：L26 以"两周"为单位从此纪元起算。 */
    public static final long FORTNIGHT_EPOCH_MS = 1_721_836_800_000L;

    /** 抖音 Web 的页面 id（签名内携带，与 query 的 aid 不一致即为破绽）。 */
    public static final int PAGE_ID = 6241;
    /** 抖音 Web 的 aid。 */
    public static final int AID = 6383;

    /** 三个 body 字节稀释成四个载体字节时用的掩码（三者按位或为 0xFF，故可逆）。 */
    private static final int[] NOISE_MASKS = {0x91, 0x42, 0x2C};
    /** 与 {@link #NOISE_MASKS} 互补的载荷掩码。 */
    private static final int[] DATA_MASKS = {0x6E, 0xBD, 0xD3};

    /**
     * 50 个标量写进 body 的固定置换（槽位号，对应 LD 编号）。
     * <p>顺序照抄字节码，勿排序、勿去重。
     */
    private static final int[] FIELD_ORDER = {
            34, 44, 56, 61, 73, 29, 70, 45, 35, 49,
            38, 66, 51, 68, 28, 48, 64, 47, 30, 71,
            26, 55, 31, 69, 59, 40, 62, 63, 27, 72,
            41, 74, 57, 52, 42, 39, 33, 67, 53, 43,
            65, 46, 36, 24, 60, 32, 79, 80, 84, 85,
    };

    /** 环境探针位（SDK 读到的页面状态）。 */
    private static final int ENV_FLAGS = 1;
    /** 反自动化探针位集。 */
    private static final int DETECT_FLAGS = 14;
    /** navigator 相关位。 */
    private static final int NR_FLAGS = 0x21;
    /** {@code window.onwheelx._Ax} 存在且被冻结的健康状态。 */
    private static final int TRIPWIRE_LOCKED = 3;
    /** "本页签名次数少于 140 次"的分桶。 */
    private static final int CALL_BUCKET = 6;
    /** 探针自述位：三个 tripwire 全部存在且被冻结。 */
    private static final int TRIPWIRE_SET = 0xB2;
    private static final int TRIPWIRE_FREE = 0x4D;

    /** 没有指纹时的默认几何（Chrome + 1080p）。 */
    public static final String DEFAULT_BROWSER_INFO = "1920|947|1920|1032|1920|1032|1920|1080|Win32";

    /** 与 {@link #DEFAULT_BROWSER_INFO} 配套的 UA（Chrome/155，Windows 10）。 */
    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";

    private static final int TASKBAR_PX = 48;
    private static final int CHROME_PX = 85;

    /** 浏览器家族 → 头部噪声字节的基值（每族 40 宽）。 */
    private static final java.util.Map<String, Integer> HEADER_NOISE_BANDS = java.util.Map.of(
            "chrome", 0, "firefox", 40, "safari", 81, "edge", 125, "huawei", 170, "other", 210);

    /** 三段摘要分别落在哪三个槽位、取哪个摘要下标、第三个字节走哪组 canary。 */
    private record DigestChain(int slot0, int slot1, int slot2,
                               int index0, int index1,
                               int canaryOffset, int canarySentinel, int canaryFallback) {
    }

    private static final DigestChain QUERY_CHAIN =
            new DigestChain(48, 49, 51, 9, 18, 3, 11, 12);
    private static final DigestChain BODY_CHAIN =
            new DigestChain(52, 53, 55, 10, 19, 4, 8, 9);
    private static final DigestChain UA_CHAIN =
            new DigestChain(56, 57, 59, 11, 21, 5, 12, 13);

    // ──────────────────────────────────────────────────────────────────────

    private final String userAgent;
    private final String browserInfo;
    private final int pageId;
    private final int aid;
    private final DoubleSupplier noise;

    private ABogusSigner(String userAgent, String browserInfo, int pageId, int aid, DoubleSupplier noise) {
        this.userAgent = userAgent;
        this.browserInfo = browserInfo;
        this.pageId = pageId;
        this.aid = aid;
        this.noise = noise;
    }

    /** 常用构造：给定 UA，其余用抖音 Web 的默认值，随机源取线程本地随机数。 */
    public static ABogusSigner of(String userAgent) {
        return of(userAgent, DEFAULT_BROWSER_INFO, PAGE_ID, AID, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** 全参构造（测试注入固定噪声时用）。 */
    public static ABogusSigner of(String userAgent, String browserInfo, int pageId, int aid, DoubleSupplier noise) {
        return new ABogusSigner(userAgent, browserInfo, pageId, aid, noise);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 对外的签名入口
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 为一次 GET 计算 {@code a_bogus}。
     *
     * @param query 将要发送的 query 串（<b>不含</b> {@code a_bogus} 本身，顺序与最终发送一致）
     */
    public String sign(String query) {
        return sign(query, "", "");
    }

    /**
     * 为一次请求计算 {@code a_bogus}（取当前时间）。
     *
     * @param query       将发送的 query 串，不含 a_bogus
     * @param body        POST 的请求体；GET 传空串。<b>body 参与摘要</b>，签名用的 body 必须与实发一致
     * @param contentType 仅一条用途：{@code multipart/form-data} 时先把 body 清空再摘要（与 SDK 一致）
     */
    public String sign(String query, String body, String contentType) {
        return sign(query, body, contentType, System.currentTimeMillis());
    }

    /**
     * 为一次请求计算 {@code a_bogus}（指定毫秒时间戳，测试对拍用）。
     *
     * <p>时间戳必须不早于 {@link #FORTNIGHT_EPOCH_MS}；早于该纪元通常意味着调用方把秒当毫秒传了。
     *
     * @throws IllegalArgumentException 时间戳早于 a_bogus 纪元
     */
    public String sign(String query, String body, String contentType, long nowMs) {
        if (nowMs < FORTNIGHT_EPOCH_MS) {
            throw new IllegalArgumentException("clock is before the a_bogus epoch: " + nowMs);
        }
        String hashedBody = contentType != null && contentType.toLowerCase().contains("multipart/form-data")
                ? "" : (body == null ? "" : body);
        int[] fields = fields(query == null ? "" : query, hashedBody, nowMs);

        // 随机数的抽取顺序与参考实现严格一致（golden vector 靠它逐字符相等）：
        // 版本块第一组 → 探针噪声 → tripwire 噪声 → 版本块第二组 → 头部噪声 → 头部 → body 分组噪声。
        int[] version = concat(maskPair(new int[]{SDK_VERSION[0], SDK_VERSION[1]}, noise, null, null),
                maskPair(new int[]{SDK_VERSION[2], SDK_VERSION[3]}, noise,
                        probeNoise(noise), tripwireNoise(noise)));

        int checksum = 0;
        for (int value : version) {
            checksum ^= value;
        }
        for (int slot : FIELD_ORDER) {
            checksum ^= fields[slot];
        }

        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        for (int slot : FIELD_ORDER) {
            bodyBytes.write(fields[slot] & 0xFF);
        }
        byte[] info = jsBytes(browserInfo);
        bodyBytes.writeBytes(info);
        byte[] tail = jsBytes(((nowMs + 3) & 0xFF) + ",");
        bodyBytes.writeBytes(tail);
        bodyBytes.write(checksum & 0xFF);

        int[] header = maskPair(HEADER_MAGIC, noise, null, headerNoise(userAgent, noise));
        byte[] frame = expandNoise(bodyBytes.toByteArray(), noise);
        byte[] plain = concat(toBytes(version), frame);
        byte[] sealed = rc4(new byte[]{PAYLOAD_KEY}, plain);
        return encodeBase64(concat(toBytes(header), sealed), ALPHABET_S4);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 50 个标量
    // ──────────────────────────────────────────────────────────────────────

    /** 构造 50 个标量（按 LD 编号索引，25..85 有用值，其余为 0）。 */
    private int[] fields(String query, String body, long nowMs) {
        int[] queryDigest = digestOf(query);
        int[] bodyDigest = digestOf(body);
        int[] uaDigest = userAgentDigest(userAgent);

        long ink = nowMs - 1;
        long fortnights = (nowMs - FORTNIGHT_EPOCH_MS) / (1000L * 60 * 60 * 24 * 14);
        byte[] infoBytes = jsBytes(browserInfo);
        byte[] tailBytes = jsBytes(((nowMs + 3) & 0xFF) + ",");

        int[] fields = new int[86];
        fields[24] = 41;
        fields[26] = (int) fortnights;
        fields[27] = CALL_BUCKET;
        // SDK 初始化后经过的毫秒数（+3）：新页面签名的值很小，这里给一个诚实的小值
        fields[28] = 3;
        fields[35] = ENV_FLAGS & 0xFF;
        fields[36] = (ENV_FLAGS / 256) & 0xFF;
        fields[38] = NR_FLAGS & 0xFF;
        fields[39] = (NR_FLAGS >> 8) & 0xFF;
        fields[66] = TRIPWIRE_LOCKED;
        fields[79] = infoBytes.length & 0xFF;
        fields[80] = (infoBytes.length >> 8) & 0xFF;
        fields[84] = tailBytes.length & 0xFF;
        fields[85] = (tailBytes.length >> 8) & 0xFF;

        writeLittleEndian(fields, 29, nowMs, 6);
        writeLittleEndian(fields, 44, DETECT_FLAGS, 4);
        // NR_TAG 恒为 0，L40..43 保持 0
        writeLittleEndian(fields, 60, ink, 6);
        writeLittleEndian(fields, 67, pageId, 4);
        writeLittleEndian(fields, 71, aid, 4);

        writeChain(fields, QUERY_CHAIN, queryDigest);
        writeChain(fields, BODY_CHAIN, bodyDigest);
        writeChain(fields, UA_CHAIN, uaDigest);
        return fields;
    }

    private static void writeChain(int[] fields, DigestChain chain, int[] digest) {
        fields[chain.slot0()] = digest[chain.index0()];
        fields[chain.slot1()] = digest[chain.index1()];
        fields[chain.slot2()] = canary(digest,
                chain.canaryOffset(), chain.canarySentinel(), chain.canaryFallback());
    }

    /** 从 {@code offset} 起第一个不等于 {@code sentinel} 的摘要字节；都给不出时用 fallback。 */
    private static int canary(int[] digest, int offset, int sentinel, int fallback) {
        for (int i = offset; i < digest.length; i++) {
            if (digest[i] != sentinel) {
                return digest[i];
            }
        }
        return fallback;
    }

    private static void writeLittleEndian(int[] fields, int firstSlot, long value, int count) {
        for (int i = 0; i < count; i++) {
            fields[firstSlot + i] = (int) ((value >>> (8 * i)) & 0xFF);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 摘要链
    // ──────────────────────────────────────────────────────────────────────

    /** {@code SM3(SM3(text + SALT))}，返回 32 个字节值。 */
    static int[] digestOf(String text) {
        return Sm3.hashToBytes(Sm3.hashToBytes(text + SALT));
    }

    /**
     * UA 三段链的摘要：RC4（密钥由环境位构造）→ 自定义 base64（s3）→ 单次 SM3。
     *
     * <p>注意是<b>单次</b> SM3，与 query/body 的两层不同；且 RC4 的输入是 UTF-16 码元字节
     * （{@link #jsBytes}），不是 UTF-8 —— 中文 UA 上两者结果不同。
     */
    static int[] userAgentDigest(String userAgent) {
        byte[] key = {(byte) (ENV_FLAGS / 256), (byte) (ENV_FLAGS % 256), (byte) (DETECT_FLAGS % 256)};
        byte[] sealed = rc4(key, jsBytes(userAgent.trim()));
        return Sm3.hashToBytes(encodeBase64(sealed, ALPHABET_S3));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 原语
    // ──────────────────────────────────────────────────────────────────────

    /**
     * SDK 的"RC4"：S-box 以降序恒等初始化，密钥调度用乘法而非加法（故教科书写法搜不到单字节密钥），
     * 密钥流生成部分与 RC4 相同。
     */
    static byte[] rc4(byte[] key, byte[] data) {
        int[] box = new int[256];
        for (int i = 0; i < 256; i++) {
            box[255 - i] = i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j * box[i] + j + (key[i % key.length] & 0xFF)) % 256;
            int tmp = box[i];
            box[i] = box[j];
            box[j] = tmp;
        }
        byte[] out = new byte[data.length];
        int i = 0;
        j = 0;
        for (int index = 0; index < data.length; index++) {
            i = (i + 1) % 256;
            j = (j + box[i]) % 256;
            int tmp = box[i];
            box[i] = box[j];
            box[j] = tmp;
            out[index] = (byte) (data[index] ^ box[(box[i] + box[j]) % 256]);
        }
        return out;
    }

    /** 自定义字母表的 base64（六位分组与补位规则同标准 base64，仅数字表不同）。 */
    static String encodeBase64(byte[] data, String alphabet) {
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < data.length; offset += 3) {
            int remaining = Math.min(3, data.length - offset);
            int block = 0;
            for (int i = 0; i < 3; i++) {
                block = (block << 8) | (i < remaining ? data[offset + i] & 0xFF : 0);
            }
            for (int k = 0; k <= remaining; k++) {
                out.append(alphabet.charAt((block >>> (18 - 6 * k)) & 0x3F));
            }
        }
        while (out.length() % 4 != 0) {
            out.append('=');
        }
        return out.toString();
    }

    /** {@link #encodeBase64} 的逆运算；出现字母表外的字符时抛 {@link IllegalArgumentException}。 */
    static byte[] decodeBase64(String text, String alphabet) {
        String body = text == null ? "" : text.replaceAll("=+$", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int offset = 0; offset < body.length(); offset += 4) {
            String chunk = body.substring(offset, Math.min(body.length(), offset + 4));
            int block = 0;
            for (int i = 0; i < chunk.length(); i++) {
                int digit = alphabet.indexOf(chunk.charAt(i));
                if (digit < 0) {
                    throw new IllegalArgumentException("not a digit of this alphabet: " + chunk.charAt(i));
                }
                block = (block << 6) | digit;
            }
            block <<= 6 * (4 - chunk.length());
            int shifts = chunk.length() - 1;
            int[] shiftBy = {16, 8, 0};
            for (int i = 0; i < shifts; i++) {
                out.write((block >>> shiftBy[i]) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    /**
     * JS 字符串 → SDK 字节：按 UTF-16 码元，低于 U+0100 出一个字节，否则出两个大端字节。
     *
     * <p>不是 UTF-8：中文一字两字节（UTF-8 三字节），代理对出四字节。geometry 长度被校验和覆盖，
     * 用错编码会让整个签名失效（不只是某个字段脏）。
     */
    static byte[] jsBytes(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            if ((unit & 0xFF00) != 0) {
                out.write((unit >>> 8) & 0xFF);
            }
            out.write(unit & 0xFF);
        }
        return out.toByteArray();
    }

    /** 把两个字节铺到四个载体字节上（数据占一半、噪声占一半）。 */
    static int[] maskPair(int[] pair, DoubleSupplier noise, Integer low, Integer high) {
        int draw = (int) (noise.getAsDouble() * 65535);
        int lowByte = low == null ? (draw & 0xFF) : (low & 0xFF);
        int highByte = high == null ? ((draw >>> 8) & 0xFF) : (high & 0xFF);
        return new int[]{
                (lowByte & 0xAA) | (pair[0] & 0x55),
                (lowByte & 0x55) | (pair[0] & 0xAA),
                (highByte & 0xAA) | (pair[1] & 0x55),
                (highByte & 0x55) | (pair[1] & 0xAA),
        };
    }

    /** {@link #maskPair} 的逆运算。 */
    static int[] unmaskPair(byte[] carrier) {
        return new int[]{
                (carrier[0] & 0x55) | (carrier[1] & 0xAA),
                (carrier[2] & 0x55) | (carrier[3] & 0xAA),
        };
    }

    /** 三个 body 字节 + 一个噪声字节 → 四个载体字节（可逆，且末字节收拢前三者让出的位）。 */
    static byte[] expandNoise(byte[] body, DoubleSupplier noise) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int offset = 0; offset < body.length; offset += 3) {
            int remaining = Math.min(3, body.length - offset);
            if (remaining < 3) {
                out.write(body[offset] & 0xFF);
                if (remaining > 1 && body[offset + 1] != 0) {
                    out.write(body[offset + 1] & 0xFF);
                }
                continue;
            }
            int mask = ((int) (noise.getAsDouble() * 1000)) & 0xFF;
            for (int k = 0; k < 3; k++) {
                out.write((mask & NOISE_MASKS[k]) | (body[offset + k] & DATA_MASKS[k]));
            }
            out.write((body[offset] & NOISE_MASKS[0])
                    | (body[offset + 1] & NOISE_MASKS[1])
                    | (body[offset + 2] & NOISE_MASKS[2]));
        }
        return out.toByteArray();
    }

    /** {@link #expandNoise} 的逆运算。 */
    static byte[] collapseNoise(byte[] frame) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int offset = 0; offset < frame.length; offset += 4) {
            int remaining = Math.min(4, frame.length - offset);
            if (remaining < 4) {
                out.writeBytes(java.util.Arrays.copyOfRange(frame, offset, offset + remaining));
                continue;
            }
            for (int k = 0; k < 3; k++) {
                out.write((frame[offset + k] & DATA_MASKS[k]) | (frame[offset + 3] & NOISE_MASKS[k]));
            }
        }
        return out.toByteArray();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 环境自述噪声
    // ──────────────────────────────────────────────────────────────────────

    /** {@code fn143}：头部第二个噪声字节是浏览器家族报告，不是均匀随机。 */
    static int headerNoise(String userAgent, DoubleSupplier noise) {
        String family = familyOf(userAgent);
        int base = HEADER_NOISE_BANDS.getOrDefault(family, HEADER_NOISE_BANDS.get("other"));
        return (base + (int) (noise.getAsDouble() * 40)) & 0xFF;
    }

    /** 按 SDK 的判定顺序识别浏览器家族（Edge/Huawei 也自称 Chrome，Chrome 也自称 Safari）。 */
    static String familyOf(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.toLowerCase(java.util.Locale.ROOT);
        if (ua.contains("edg")) {
            return "edge";
        }
        for (String name : new String[]{"huawei", "firefox", "chrome", "safari"}) {
            if (ua.contains(name)) {
                return name;
            }
        }
        return "other";
    }

    /** {@code fn144}：>109 时强制为奇数，故 110..240 有一半不可达。 */
    static int probeNoise(DoubleSupplier noise) {
        int value = (int) (noise.getAsDouble() * 240);
        return value > 109 ? value + value % 2 + 1 : value;
    }

    /** {@code fn145}："三个 tripwire 都在且被冻结"，再叠真实噪声。 */
    static int tripwireNoise(DoubleSupplier noise) {
        return ((int) (noise.getAsDouble() * 255) & TRIPWIRE_FREE) | TRIPWIRE_SET;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 结构校验与解码（自检 / 测试 / 排查用，不参与签名）
    // ──────────────────────────────────────────────────────────────────────

    private static final java.util.Set<String> DECODE_PROBLEMS =
            java.util.Set.of("alphabet", "length not a multiple of four", "payload too short");

    /** 该问题是否意味着"根本不是 a_bogus"（区别于"内容与预期不符"）。 */
    public static boolean isDecodeProblem(String problem) {
        return problem != null && DECODE_PROBLEMS.contains(problem);
    }

    /**
     * 第一个导致 {@code value} 不是合法 {@code a_bogus} 的问题，全部通过返回 {@code null}。
     *
     * <p>这些检查都由算法固定，任何正确实现都一致（噪声字节不同不影响）。最后一项校验和覆盖了
     * 版本块与 50 个标量、且自身也在密文里，能通过它的签名只可能由同一套布局拼出来。
     */
    public static String structureError(String value) {
        if (value == null) {
            return "alphabet";
        }
        String body = value.replaceAll("=+$", "");
        if (body.isEmpty() || body.contains("=")) {
            return "alphabet";
        }
        for (int i = 0; i < body.length(); i++) {
            if (ALPHABET_S4.indexOf(body.charAt(i)) < 0) {
                return "alphabet";
            }
        }
        if (value.length() % 4 != 0) {
            return "length not a multiple of four";
        }
        byte[] payload;
        try {
            payload = decodeBase64(value, ALPHABET_S4);
        } catch (IllegalArgumentException e) {
            return "alphabet";
        }
        if (payload.length < 80) {
            return "payload too short";
        }
        if (!java.util.Arrays.equals(unmaskPair(java.util.Arrays.copyOfRange(payload, 0, 4)), HEADER_MAGIC)) {
            return "header magic";
        }
        byte[] plain = rc4(new byte[]{PAYLOAD_KEY}, java.util.Arrays.copyOfRange(payload, 4, payload.length));
        int[] version = new int[8];
        for (int i = 0; i < 8; i++) {
            version[i] = plain[i] & 0xFF;
        }
        if (!java.util.Arrays.equals(unmaskPair(toBytes(java.util.Arrays.copyOfRange(version, 0, 4))),
                new int[]{SDK_VERSION[0], SDK_VERSION[1]})) {
            return "sdk version";
        }
        if (!java.util.Arrays.equals(unmaskPair(toBytes(java.util.Arrays.copyOfRange(version, 4, 8))),
                new int[]{SDK_VERSION[2], SDK_VERSION[3]})) {
            return "sdk version";
        }
        byte[] unpacked = collapseNoise(java.util.Arrays.copyOfRange(plain, 8, plain.length));
        if (unpacked.length < FIELD_ORDER.length + 1) {
            return "frame too short";
        }
        int[] fields = new int[86];
        for (int i = 0; i < FIELD_ORDER.length; i++) {
            fields[FIELD_ORDER[i]] = unpacked[i] & 0xFF;
        }
        int infoLength = fields[79] | (fields[80] << 8);
        int tailLength = fields[84] | (fields[85] << 8);
        int end = FIELD_ORDER.length + infoLength + tailLength;
        if (end + 1 > unpacked.length) {
            return "declared lengths overrun the frame";
        }
        if (unpacked.length - (end + 1) > 2) {
            return "declared lengths leave a tail";
        }
        int checksum = 0;
        for (int b : version) {
            checksum ^= b;
        }
        for (int slot : FIELD_ORDER) {
            checksum ^= fields[slot];
        }
        if ((unpacked[end] & 0xFF) != checksum) {
            return "checksum";
        }
        return null;
    }

    /** 解出来的可读字段（诊断用）。 */
    public record Decoded(int[] headerMagic, int[] sdkVersion, long nowMs, long inkMs, int pageId, int aid,
                          int envFlags, int detectFlags, int callBucket, int tripwire,
                          String browserInfo, String tail, int[] fields) {
    }

    /**
     * 把一个 {@code a_bogus} 拆开（仅供诊断与测试）。
     *
     * @throws IllegalArgumentException 结构校验不通过
     */
    public static Decoded decode(String value) {
        String problem = structureError(value);
        if (problem != null) {
            throw new IllegalArgumentException("not a well-formed a_bogus: " + problem);
        }
        byte[] payload = decodeBase64(value, ALPHABET_S4);
        byte[] plain = rc4(new byte[]{PAYLOAD_KEY}, java.util.Arrays.copyOfRange(payload, 4, payload.length));
        byte[] unpacked = collapseNoise(java.util.Arrays.copyOfRange(plain, 8, plain.length));
        int[] fields = new int[86];
        for (int i = 0; i < FIELD_ORDER.length; i++) {
            fields[FIELD_ORDER[i]] = unpacked[i] & 0xFF;
        }
        int infoLength = fields[79] | (fields[80] << 8);
        int tailLength = fields[84] | (fields[85] << 8);
        int start = FIELD_ORDER.length;
        String info = new String(java.util.Arrays.copyOfRange(unpacked, start, start + infoLength),
                java.nio.charset.StandardCharsets.UTF_8);
        String tail = new String(java.util.Arrays.copyOfRange(unpacked, start + infoLength,
                start + infoLength + tailLength), java.nio.charset.StandardCharsets.UTF_8);
        return new Decoded(
                unmaskPair(java.util.Arrays.copyOfRange(payload, 0, 4)),
                concat(unmaskPair(java.util.Arrays.copyOfRange(plain, 0, 4)),
                        unmaskPair(java.util.Arrays.copyOfRange(plain, 4, 8))),
                littleEndian(fields, 29, 6),
                littleEndian(fields, 60, 6),
                (int) littleEndian(fields, 67, 4),
                (int) littleEndian(fields, 71, 4),
                fields[35],
                (int) littleEndian(fields, 44, 4),
                fields[27],
                fields[66],
                info,
                tail,
                fields);
    }

    private static long littleEndian(int[] fields, int firstSlot, int count) {
        long value = 0;
        for (int i = 0; i < count; i++) {
            value |= (long) fields[firstSlot + i] << (8 * i);
        }
        return value;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 几何
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 由一个屏幕尺寸推出一致的一组浏览器几何（窗口 ≤ 工作区 ≤ 屏幕，视口小于窗口）。
     *
     * <p>四个矩形不独立，随机拼出来的组合本身就是破绽；这里按固定 OS 任务栏与浏览器 chrome
     * 高度反推，得到与真实浏览器一致的一组值。
     */
    public static String browserInfoFromScreen(int width, int height, String platform) {
        int availHeight = Math.max(height - TASKBAR_PX, 1);
        return String.join("|",
                String.valueOf(width),
                String.valueOf(Math.max(availHeight - CHROME_PX, 1)),
                String.valueOf(width),
                String.valueOf(availHeight),
                String.valueOf(width),
                String.valueOf(availHeight),
                String.valueOf(width),
                String.valueOf(height),
                platform);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 小工具
    // ──────────────────────────────────────────────────────────────────────

    private static int[] concat(int[] first, int[] second) {
        int[] out = new int[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static byte[] toBytes(int[] values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) (values[i] & 0xFF);
        }
        return out;
    }
}
