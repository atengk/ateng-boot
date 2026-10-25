package io.github.atengk.boot.encrypt.decryptor;

import java.nio.charset.StandardCharsets;

/**
 * 纯 JDK 原生国密 SM3 密码杂凑算法实现 (GB/T 32905-2016)
 * 100% 依托原生位运算与数学逻辑，零 Bouncy Castle 外部依赖
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class Sm3Digest {

    private static final int[] IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
    };

    private Sm3Digest() {
    }

    private static int t(int j) {
        return (j < 16) ? 0x79cc4519 : 0x7a879d8a;
    }

    private static int ff(int j, int x, int y, int z) {
        return (j < 16) ? (x ^ y ^ z) : ((x & y) | (x & z) | (y & z));
    }

    private static int gg(int j, int x, int y, int z) {
        return (j < 16) ? (x ^ y ^ z) : ((x & y) | ((~x) & z));
    }

    private static int p0(int x) {
        return x ^ Integer.rotateLeft(x, 9) ^ Integer.rotateLeft(x, 17);
    }

    private static int p1(int x) {
        return x ^ Integer.rotateLeft(x, 15) ^ Integer.rotateLeft(x, 23);
    }

    /**
     * 计算单字节数组的 32 字节 SM3 杂凑摘要
     *
     * @param msg 输入字节数组
     * @return 32 字节哈希摘要
     */
    public static byte[] digest(byte[] msg) {
        byte[] input = (msg != null) ? msg : new byte[0];
        long bitLen = (long) input.length * 8;
        int rem = input.length % 64;
        int padLen = (rem < 56) ? (56 - rem) : (120 - rem);
        byte[] padded = new byte[input.length + padLen + 8];
        System.arraycopy(input, 0, padded, 0, input.length);
        padded[input.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) ((bitLen >>> (i * 8)) & 0xff);
        }

        int[] v = IV.clone();
        int[] w = new int[68];
        int[] wPrime = new int[64];

        for (int offset = 0; offset < padded.length; offset += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = ((padded[offset + i * 4] & 0xff) << 24)
                        | ((padded[offset + i * 4 + 1] & 0xff) << 16)
                        | ((padded[offset + i * 4 + 2] & 0xff) << 8)
                        | (padded[offset + i * 4 + 3] & 0xff);
            }
            for (int i = 16; i < 68; i++) {
                w[i] = p1(w[i - 16] ^ w[i - 9] ^ Integer.rotateLeft(w[i - 3], 15))
                        ^ Integer.rotateLeft(w[i - 13], 7) ^ w[i - 6];
            }
            for (int i = 0; i < 64; i++) {
                wPrime[i] = w[i] ^ w[i + 4];
            }

            int a = v[0], b = v[1], c = v[2], d = v[3];
            int e = v[4], f = v[5], g = v[6], h = v[7];

            for (int j = 0; j < 64; j++) {
                int ss1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(t(j), j % 32), 7);
                int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
                int tt1 = ff(j, a, b, c) + d + ss2 + wPrime[j];
                int tt2 = gg(j, e, f, g) + h + ss1 + w[j];
                d = c;
                c = Integer.rotateLeft(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = Integer.rotateLeft(f, 19);
                f = e;
                e = p0(tt2);
            }

            v[0] ^= a;
            v[1] ^= b;
            v[2] ^= c;
            v[3] ^= d;
            v[4] ^= e;
            v[5] ^= f;
            v[6] ^= g;
            v[7] ^= h;
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (v[i] >>> 24);
            out[i * 4 + 1] = (byte) (v[i] >>> 16);
            out[i * 4 + 2] = (byte) (v[i] >>> 8);
            out[i * 4 + 3] = (byte) v[i];
        }
        return out;
    }

    /**
     * 计算多段字节数组拼接后的 SM3 杂凑摘要（避免外部多余内存分配）
     *
     * @param parts 多段字节数组
     * @return 32 字节哈希摘要
     */
    public static byte[] digest(byte[]... parts) {
        if (parts == null || parts.length == 0) {
            return digest(new byte[0]);
        }
        int totalLen = 0;
        for (byte[] p : parts) {
            if (p != null) {
                totalLen += p.length;
            }
        }
        byte[] combined = new byte[totalLen];
        int pos = 0;
        for (byte[] p : parts) {
            if (p != null) {
                System.arraycopy(p, 0, combined, pos, p.length);
                pos += p.length;
            }
        }
        return digest(combined);
    }

    /**
     * 计算字符串的 SM3 杂凑摘要
     *
     * @param str 输入字符串 (UTF-8)
     * @return 32 字节哈希摘要
     */
    public static byte[] digest(String str) {
        return digest(str != null ? str.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }
}
