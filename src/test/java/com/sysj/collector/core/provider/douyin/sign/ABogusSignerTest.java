package com.sysj.collector.core.provider.douyin.sign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * a_bogus 纯算实现的对拍回归。
 *
 * <p>判据来自 {@code src/test/resources/douyin/a_bogus_vectors.json}：6 个用例的期望值由外部参考实现
 * （{@code Evil0ctal/Douyin_TikTok_Download_API} 的 {@code abogus.py}）在<b>固定噪声序列</b>下一次性生成，
 * 之后<b>固化为静态测试资源</b> —— 构建、测试、运行全程<b>不需要 Python</b>（也不需要 Node/浏览器）。
 * 覆盖 Chrome/Edge 两套 UA、一级/二级评论查询串、中文、POST body 参与摘要、multipart 清空 body。
 * Java 端逐字符相等才算通过 —— 噪声是唯一的随机源，固定它以后整条链路（SM3、RC4、自定义 base64、
 * 掩码、slot 布局、校验和）任一处偏差都会让字符串不同。
 *
 * <p>只有在平台算法漂移、需要与新的参考实现对拍时，才重新生成该 JSON（一次性外部动作，产物入库）。
 *
 * <p>噪声序列与参考实现 {@code SeqRng} 完全相同（64 个 double 循环取值），抽取顺序也必须一致：
 * 版本块第一组 → 探针噪声 → tripwire 噪声 → 版本块第二组 → 头部噪声 → 头部 → body 分组噪声。
 */
class ABogusSignerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与参考实现同构的可复现噪声源。 */
    private static final class SeqNoise implements DoubleSupplier {
        private final double[] values;
        private int index;

        private SeqNoise(double[] values) {
            this.values = values;
        }

        @Override
        public double getAsDouble() {
            return values[index++ % values.length];
        }
    }

    private static JsonNode vectors() throws IOException {
        try (InputStream in = ABogusSignerTest.class.getResourceAsStream("/douyin/a_bogus_vectors.json")) {
            assertNotNull(in, "缺少测试资源 a_bogus_vectors.json");
            return MAPPER.readTree(in);
        }
    }

    @Test
    @DisplayName("6 个 golden vector 逐字符相等，且结构自校验通过、解码字段一致")
    void matchesReferenceImplementation() throws IOException {
        JsonNode root = vectors();
        double[] noise = new double[root.get("noise").size()];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = root.get("noise").get(i).asDouble();
        }

        JsonNode vectorList = root.get("vectors");
        assertTrue(vectorList.size() >= 6, "golden vector 不应少于 6 组");
        for (JsonNode vector : vectorList) {
            String name = vector.get("name").asText();
            assertEquals(true, vector.get("structureError").isNull(), name);

            ABogusSigner signer = ABogusSigner.of(vector.get("ua").asText(),
                    vector.get("browserInfo").asText(), ABogusSigner.PAGE_ID, ABogusSigner.AID,
                    new SeqNoise(noise));
            String actual = signer.sign(vector.get("query").asText(), vector.get("body").asText(),
                    vector.get("contentType").asText(), vector.get("nowMs").asLong());

            assertEquals(vector.get("aBogus").asText(), actual, name + " 的 a_bogus 与参考实现不一致");
            assertNull(ABogusSigner.structureError(actual), name + " 结构自校验失败");

            ABogusSigner.Decoded decoded = ABogusSigner.decode(actual);
            JsonNode expected = vector.get("decoded");
            assertEquals(vector.get("nowMs").asLong(), decoded.nowMs(), name);
            assertEquals(expected.get("pageId").asInt(), decoded.pageId(), name);
            assertEquals(expected.get("aid").asInt(), decoded.aid(), name);
            assertEquals(vector.get("browserInfo").asText(), decoded.browserInfo(), name);
            assertEquals(expected.get("tail").asText(), decoded.tail(), name);
            assertArrayEquals(new int[]{3, 82}, decoded.headerMagic(), name);
            assertArrayEquals(new int[]{1, 0, 1, 0}, decoded.sdkVersion(), name);
        }
    }

    @Test
    @DisplayName("噪声之外无其它随机源：同一噪声重复签名结果完全相同")
    void isDeterministicUnderFixedNoise() {
        double[] noise = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};
        ABogusSigner first = ABogusSigner.of(ABogusSigner.DEFAULT_USER_AGENT,
                ABogusSigner.DEFAULT_BROWSER_INFO, ABogusSigner.PAGE_ID, ABogusSigner.AID, new SeqNoise(noise));
        ABogusSigner second = ABogusSigner.of(ABogusSigner.DEFAULT_USER_AGENT,
                ABogusSigner.DEFAULT_BROWSER_INFO, ABogusSigner.PAGE_ID, ABogusSigner.AID, new SeqNoise(noise));
        assertEquals(first.sign("aid=6383&aweme_id=7695724801243024683", "", "", 1793064266000L),
                second.sign("aid=6383&aweme_id=7695724801243024683", "", "", 1793064266000L));
    }

    @Test
    @DisplayName("篡改任意一个字符都会破坏校验和；非字母表字符报 alphabet")
    void detectsTampering() {
        double[] noise = {0.31, 0.41, 0.59, 0.26, 0.53};
        String value = ABogusSigner.of(ABogusSigner.DEFAULT_USER_AGENT).sign(
                "aid=6383&aweme_id=7695724801243024683", "", "", 1793064266000L);
        assertNull(ABogusSigner.structureError(value));

        char original = value.charAt(20);
        char replacement = original == 'A' ? 'B' : 'A';
        String tampered = value.substring(0, 20) + replacement + value.substring(21);
        assertNotNull(ABogusSigner.structureError(tampered));

        assertThrows(IllegalArgumentException.class, () -> ABogusSigner.decode(tampered));
        assertEquals("alphabet", ABogusSigner.structureError("!!not-base64!!"));
        assertTrue(ABogusSigner.isDecodeProblem(ABogusSigner.structureError("abc")));
        assertFalse(ABogusSigner.isDecodeProblem(null));
    }

    @Test
    @DisplayName("时间戳早于 a_bogus 纪元直接拒绝（防把秒当毫秒）")
    void rejectsClockBeforeEpoch() {
        ABogusSigner signer = ABogusSigner.of(ABogusSigner.DEFAULT_USER_AGENT);
        assertThrows(IllegalArgumentException.class,
                () -> signer.sign("aid=6383", "", "", 1_790_000_000L));
    }

    @Test
    @DisplayName("multipart 表单先清空 body 再摘要（与 SDK 行为一致）")
    void blanksMultipartBody() {
        double[] noise = {0.7, 0.3, 0.9, 0.1};
        String plain = ABogusSigner.of(ABogusSigner.DEFAULT_USER_AGENT, ABogusSigner.DEFAULT_BROWSER_INFO,
                ABogusSigner.PAGE_ID, ABogusSigner.AID, new SeqNoise(noise))
                .sign("aid=6383", "", "", 1793064266000L);
        String multipart = ABogusSigner.of(ABogusSigner.DEFAULT_USER_AGENT, ABogusSigner.DEFAULT_BROWSER_INFO,
                ABogusSigner.PAGE_ID, ABogusSigner.AID, new SeqNoise(noise))
                .sign("aid=6383", "a=1&b=2", "multipart/form-data; boundary=x", 1793064266000L);
        assertEquals(plain, multipart);
    }
}
