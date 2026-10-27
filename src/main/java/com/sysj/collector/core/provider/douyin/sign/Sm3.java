package com.sysj.collector.core.provider.douyin.sign;

/**
 * SM3（GB/T 32905-2016 / GM/T 0004-2012）纯 Java 实现。
 *
 * <p>抖音的 {@code a_bogus} 用 SM3 做摘要（参考实现 {@code dtk.signing.native.sm3}），
 * 而本项目 {@code pom.xml} 里没有 BouncyCastle，也不希望为了一次签名引入密码库依赖，
 * 故按国标直接实现：256 位分组、64 轮压缩、Ⅳ 常量与 T_j 轮常量逐条对照标准。
 *
 * <p>线程安全：本类无状态，全部方法为静态方法，可任意并发调用。
 *
 * <h3>为什么不用 JDK 自带摘要</h3>
 * JDK 的 {@code MessageDigest} 没有 SM3（SunJCE 不提供），OpenSSL 后端在 Windows 上也不稳定，
 * 因此这里自带实现，行为完全可复现（对拍见 {@code Sm3Test} 与 {@code ABogusSignerTest}）。
 */
public final class Sm3 {

    /** 初始向量 IV：取 SM3 标准 4.1 节。 */
    private static final int[] IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e,
    };

    /** 轮常量：j ∈ [0,15] 用 0x79cc4519，j ∈ [16,63] 用 0x7a879d8a。 */
    private static final int T_J_LOW = 0x79cc4519;
    private static final int T_J_HIGH = 0x7a879d8a;

    private Sm3() {
    }

    /**
     * SM3 摘要。
     *
     * @param message 原文（按 UTF-8 编码）
     * @return 32 字节摘要（每次返回新数组）
     */
    public static byte[] hash(String message) {
        return hash(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * SM3 摘要。
     *
     * @param message 原文
     * @return 32 字节摘要（每次返回新数组）
     */
    public static byte[] hash(byte[] message) {
        int[] state = IV.clone();
        byte[] padded = pad(message);
        for (int offset = 0; offset < padded.length; offset += 64) {
            compress(state, padded, offset);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (state[i] >>> 24);
            out[i * 4 + 1] = (byte) (state[i] >>> 16);
            out[i * 4 + 2] = (byte) (state[i] >>> 8);
            out[i * 4 + 3] = (byte) state[i];
        }
        return out;
    }

    /**
     * SM3 摘要，输出为 32 个 {@code 0..255} 的整数。
     *
     * <p>与参考实现 {@code sm3_to_array()} 的返回形态一致，供 a_bogus 的摘要链直接取字节。
     */
    public static int[] hashToBytes(String message) {
        return toByteValues(hash(message));
    }

    /** 见 {@link #hashToBytes(String)}。 */
    public static int[] hashToBytes(byte[] message) {
        return toByteValues(hash(message));
    }

    /**
     * 对一段"已经是摘要"的 32 个字节值再做一次 SM3（a_bogus 的 {@code SM3(SM3(x))} 两层写法）。
     */
    public static int[] hashToBytes(int[] digestBytes) {
        byte[] raw = new byte[digestBytes.length];
        for (int i = 0; i < digestBytes.length; i++) {
            raw[i] = (byte) (digestBytes[i] & 0xFF);
        }
        return toByteValues(hash(raw));
    }

    private static int[] toByteValues(byte[] digest) {
        int[] values = new int[digest.length];
        for (int i = 0; i < digest.length; i++) {
            values[i] = digest[i] & 0xFF;
        }
        return values;
    }

    /** 填充：高位补 1、补 0 至 mod 64 = 56，末尾 8 字节大端写入比特长度。 */
    private static byte[] pad(byte[] message) {
        int length = message.length;
        long bitLength = (long) length * 8;
        int paddedLength = (length + 8) / 64 * 64 + 64;
        byte[] padded = new byte[paddedLength];
        System.arraycopy(message, 0, padded, 0, length);
        padded[length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            padded[paddedLength - 1 - i] = (byte) (bitLength >>> (8 * i));
        }
        return padded;
    }

    /** 单个 512 位分组的压缩函数 CF。 */
    private static void compress(int[] state, byte[] block, int offset) {
        int[] w = new int[68];
        int[] w1 = new int[64];
        for (int j = 0; j < 16; j++) {
            int base = offset + j * 4;
            w[j] = ((block[base] & 0xFF) << 24)
                    | ((block[base + 1] & 0xFF) << 16)
                    | ((block[base + 2] & 0xFF) << 8)
                    | (block[base + 3] & 0xFF);
        }
        for (int j = 16; j < 68; j++) {
            w[j] = p1(w[j - 16] ^ w[j - 9] ^ Integer.rotateLeft(w[j - 3], 15))
                    ^ Integer.rotateLeft(w[j - 13], 7)
                    ^ w[j - 6];
        }
        for (int j = 0; j < 64; j++) {
            w1[j] = w[j] ^ w[j + 4];
        }

        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int e = state[4];
        int f = state[5];
        int g = state[6];
        int h = state[7];
        for (int j = 0; j < 64; j++) {
            int t = j < 16 ? T_J_LOW : T_J_HIGH;
            int ss1 = Integer.rotateLeft(
                    Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(t, j), 7);
            int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
            int tt1 = ff(a, b, c, j) + d + ss2 + w1[j];
            int tt2 = gg(e, f, g, j) + h + ss1 + w[j];
            d = c;
            c = Integer.rotateLeft(b, 9);
            b = a;
            a = tt1;
            h = g;
            g = Integer.rotateLeft(f, 19);
            f = e;
            e = p0(tt2);
        }
        state[0] ^= a;
        state[1] ^= b;
        state[2] ^= c;
        state[3] ^= d;
        state[4] ^= e;
        state[5] ^= f;
        state[6] ^= g;
        state[7] ^= h;
    }

    /** 布尔函数 FF_j。 */
    private static int ff(int x, int y, int z, int j) {
        return j < 16 ? (x ^ y ^ z) : ((x & y) | (x & z) | (y & z));
    }

    /** 布尔函数 GG_j。 */
    private static int gg(int x, int y, int z, int j) {
        return j < 16 ? (x ^ y ^ z) : ((x & y) | (~x & z));
    }

    /** 置换函数 P0。 */
    private static int p0(int x) {
        return x ^ Integer.rotateLeft(x, 9) ^ Integer.rotateLeft(x, 17);
    }

    /** 置换函数 P1。 */
    private static int p1(int x) {
        return x ^ Integer.rotateLeft(x, 15) ^ Integer.rotateLeft(x, 23);
    }
}
