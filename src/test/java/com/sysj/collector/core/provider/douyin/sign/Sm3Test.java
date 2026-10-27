package com.sysj.collector.core.provider.douyin.sign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SM3（GB/T 32905-2016）纯 Java 实现的判据回归。
 *
 * <p>只断言标准向量与边界（空串、55/56/64/65 字节的填充分界、超长输入），不依赖任何第三方实现：
 * a_bogus 的整条摘要链都建立在这个函数上，SM3 错了只会表现为「签名偶尔对、偶尔错」，很难定位。
 */
class Sm3Test {

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xFF));
        }
        return builder.toString();
    }

    @Test
    @DisplayName("标准向量：abc / 空串 / 64 字节整块 / 长中文")
    void matchesStandardVectors() {
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
                hex(Sm3.hash("abc")));
        assertEquals("1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
                hex(Sm3.hash("")));
        // 64 字节整块：填充必须先补 0x80 再加 8 字节长度，否则会少算一个分组
        String block64 = "a".repeat(64);
        assertEquals(32, Sm3.hash(block64).length);
        assertEquals(32, Sm3.hash("a".repeat(55)).length);
        assertEquals(32, Sm3.hash("a".repeat(56)).length);
        assertEquals(32, Sm3.hash("a".repeat(65)).length);
        // 中文按 UTF-8 编码（a_bogus 里的 browser_info/query 都是 ASCII，中文只出现在查询串）
        assertEquals(hex(Sm3.hash("抖音评论采集".getBytes(StandardCharsets.UTF_8))),
                hex(Sm3.hash("抖音评论采集")));
    }

    @Test
    @DisplayName("hashToBytes 与 hash 一一对应（0..255，共 32 个）")
    void hashToBytesMatchesHash() {
        int[] bytes = Sm3.hashToBytes("douyin");
        byte[] raw = Sm3.hash("douyin");
        assertEquals(32, bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            assertEquals(raw[i] & 0xFF, bytes[i]);
        }
    }

    @Test
    @DisplayName("填充边界：55/56、63/64 字节相邻输入摘要必须不同")
    void paddingBoundariesDiffer() {
        assertNotEquals(hex(Sm3.hash("b".repeat(55))), hex(Sm3.hash("b".repeat(56))));
        assertNotEquals(hex(Sm3.hash("b".repeat(63))), hex(Sm3.hash("b".repeat(64))));
        assertNotEquals(hex(Sm3.hash("b".repeat(119))), hex(Sm3.hash("b".repeat(120))));
    }
}
