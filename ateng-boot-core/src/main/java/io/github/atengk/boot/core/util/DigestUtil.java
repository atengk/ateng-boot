package io.github.atengk.boot.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 纯 JDK 原生消息摘要算法工具类
 * 基于 Zero-Bloat 原则，100% 依托 JDK 17 原生 MessageDigest 与 HexFormat 实现 SHA-256、SHA-512、SHA-1、MD5 与流式摘要，
 * 并内置纯原生国密 SM3 (GB/T 32905-2016) 增量状态机杂凑计算
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class DigestUtil {

    private static final String ALGORITHM_SHA256 = "SHA-256";
    private static final String ALGORITHM_SHA512 = "SHA-512";
    private static final String ALGORITHM_SHA1 = "SHA-1";
    private static final String ALGORITHM_MD5 = "MD5";
    private static final int STREAM_BUFFER_SIZE = 8192;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private DigestUtil() {
    }

    /**
     * 计算数据的 SHA-1 摘要字节数组
     *
     * @param data 输入数据字节数组
     * @return 20 字节 SHA-1 摘要
     */
    public static byte[] sha1(byte[] data) {
        return digest(ALGORITHM_SHA1, data != null ? data : EMPTY_BYTES);
    }

    /**
     * 计算字符串的 SHA-1 摘要字节数组（UTF-8）
     *
     * @param data 输入字符串
     * @return 20 字节 SHA-1 摘要
     */
    public static byte[] sha1(String data) {
        return sha1(data != null ? data.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES);
    }

    /**
     * 计算数据的 SHA-1 小写十六进制摘要字符串
     *
     * @param data 输入数据字节数组
     * @return 40 位小写十六进制摘要字符串
     */
    public static String sha1Hex(byte[] data) {
        return HexFormat.of().formatHex(sha1(data));
    }

    /**
     * 计算字符串的 SHA-1 小写十六进制摘要字符串（UTF-8）
     *
     * @param data 输入字符串
     * @return 40 位小写十六进制摘要字符串
     */
    public static String sha1Hex(String data) {
        return HexFormat.of().formatHex(sha1(data));
    }

    /**
     * 计算数据的 SHA-256 摘要字节数组
     *
     * @param data 输入数据字节数组
     * @return 32 字节 SHA-256 摘要
     */
    public static byte[] sha256(byte[] data) {
        return digest(ALGORITHM_SHA256, data != null ? data : EMPTY_BYTES);
    }

    /**
     * 计算字符串的 SHA-256 摘要字节数组（UTF-8）
     *
     * @param data 输入字符串
     * @return 32 字节 SHA-256 摘要
     */
    public static byte[] sha256(String data) {
        return sha256(data != null ? data.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES);
    }

    /**
     * 流式计算输入流的 SHA-256 摘要字节数组（不关闭传入流）
     *
     * @param inputStream 输入流（不能为 null）
     * @return 32 字节 SHA-256 摘要
     */
    public static byte[] sha256(InputStream inputStream) {
        return digestStream(ALGORITHM_SHA256, inputStream);
    }

    /**
     * 计算数据的 SHA-256 小写十六进制摘要字符串
     *
     * @param data 输入数据字节数组
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256(data));
    }

    /**
     * 计算字符串的 SHA-256 小写十六进制摘要字符串（UTF-8）
     *
     * @param data 输入字符串
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sha256Hex(String data) {
        return HexFormat.of().formatHex(sha256(data));
    }

    /**
     * 流式计算输入流的 SHA-256 小写十六进制摘要字符串（不关闭传入流）
     *
     * @param inputStream 输入流（不能为 null）
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sha256Hex(InputStream inputStream) {
        return HexFormat.of().formatHex(sha256(inputStream));
    }

    /**
     * 计算数据的 SHA-512 摘要字节数组
     *
     * @param data 输入数据字节数组
     * @return 64 字节 SHA-512 摘要
     */
    public static byte[] sha512(byte[] data) {
        return digest(ALGORITHM_SHA512, data != null ? data : EMPTY_BYTES);
    }

    /**
     * 计算字符串的 SHA-512 摘要字节数组（UTF-8）
     *
     * @param data 输入字符串
     * @return 64 字节 SHA-512 摘要
     */
    public static byte[] sha512(String data) {
        return sha512(data != null ? data.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES);
    }

    /**
     * 流式计算输入流的 SHA-512 摘要字节数组（不关闭传入流）
     *
     * @param inputStream 输入流（不能为 null）
     * @return 64 字节 SHA-512 摘要
     */
    public static byte[] sha512(InputStream inputStream) {
        return digestStream(ALGORITHM_SHA512, inputStream);
    }

    /**
     * 计算数据的 SHA-512 小写十六进制摘要字符串
     *
     * @param data 输入数据字节数组
     * @return 128 位小写十六进制摘要字符串
     */
    public static String sha512Hex(byte[] data) {
        return HexFormat.of().formatHex(sha512(data));
    }

    /**
     * 计算字符串的 SHA-512 小写十六进制摘要字符串（UTF-8）
     *
     * @param data 输入字符串
     * @return 128 位小写十六进制摘要字符串
     */
    public static String sha512Hex(String data) {
        return HexFormat.of().formatHex(sha512(data));
    }

    /**
     * 流式计算输入流的 SHA-512 小写十六进制摘要字符串（不关闭传入流）
     *
     * @param inputStream 输入流（不能为 null）
     * @return 128 位小写十六进制摘要字符串
     */
    public static String sha512Hex(InputStream inputStream) {
        return HexFormat.of().formatHex(sha512(inputStream));
    }

    /**
     * 计算数据的 MD5 摘要字节数组
     *
     * @param data 输入数据字节数组
     * @return 16 字节 MD5 摘要
     */
    public static byte[] md5(byte[] data) {
        return digest(ALGORITHM_MD5, data != null ? data : EMPTY_BYTES);
    }

    /**
     * 计算字符串的 MD5 摘要字节数组（UTF-8）
     *
     * @param data 输入字符串
     * @return 16 字节 MD5 摘要
     */
    public static byte[] md5(String data) {
        return md5(data != null ? data.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES);
    }

    /**
     * 计算数据的 MD5 小写十六进制摘要字符串
     *
     * @param data 输入数据字节数组
     * @return 32 位小写十六进制摘要字符串
     */
    public static String md5Hex(byte[] data) {
        return HexFormat.of().formatHex(md5(data));
    }

    /**
     * 计算字符串的 MD5 小写十六进制摘要字符串（UTF-8）
     *
     * @param data 输入字符串
     * @return 32 位小写十六进制摘要字符串
     */
    public static String md5Hex(String data) {
        return HexFormat.of().formatHex(md5(data));
    }

    // ==================== 国密 SM3 密码杂凑算法 (GB/T 32905-2016) ====================

    private static final int[] SM3_IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
    };

    /**
     * 计算数据的国密 SM3 摘要字节数组 (GB/T 32905-2016)
     *
     * @param data 输入数据字节数组
     * @return 32 字节 SM3 摘要
     */
    public static byte[] sm3(byte[] data) {
        Sm3State state = new Sm3State();
        if (data != null && data.length > 0) {
            state.update(data, 0, data.length);
        }
        return state.doFinal();
    }

    /**
     * 计算多段字节数组拼接后的国密 SM3 摘要（基于增量状态机，避免整块拼接内存拷贝）
     *
     * @param parts 多段字节数组
     * @return 32 字节 SM3 摘要
     */
    public static byte[] sm3(byte[]... parts) {
        Sm3State state = new Sm3State();
        if (parts != null) {
            for (byte[] part : parts) {
                if (part != null && part.length > 0) {
                    state.update(part, 0, part.length);
                }
            }
        }
        return state.doFinal();
    }

    /**
     * 计算字符串的国密 SM3 摘要字节数组（UTF-8）
     *
     * @param data 输入字符串
     * @return 32 字节 SM3 摘要
     */
    public static byte[] sm3(String data) {
        return sm3(data != null ? data.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES);
    }

    /**
     * 流式计算输入流的国密 SM3 摘要字节数组（不关闭传入流）
     *
     * @param inputStream 输入流（不能为 null）
     * @return 32 字节 SM3 摘要
     */
    public static byte[] sm3(InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("输入流 inputStream 不能为 null");
        }
        Sm3State state = new Sm3State();
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        try {
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (bytesRead > 0) {
                    state.update(buffer, 0, bytesRead);
                }
            }
            return state.doFinal();
        } catch (IOException e) {
            throw new UncheckedIOException("读取输入流计算 SM3 摘要失败", e);
        }
    }

    /**
     * 计算数据的国密 SM3 小写十六进制摘要字符串
     *
     * @param data 输入数据字节数组
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sm3Hex(byte[] data) {
        return HexFormat.of().formatHex(sm3(data));
    }

    /**
     * 计算字符串的国密 SM3 小写十六进制摘要字符串（UTF-8）
     *
     * @param data 输入字符串
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sm3Hex(String data) {
        return HexFormat.of().formatHex(sm3(data));
    }

    /**
     * 流式计算输入流的国密 SM3 小写十六进制摘要字符串（不关闭传入流）
     *
     * @param inputStream 输入流（不能为 null）
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sm3Hex(InputStream inputStream) {
        return HexFormat.of().formatHex(sm3(inputStream));
    }

    private static int sm3T(int j) {
        return (j < 16) ? 0x79cc4519 : 0x7a879d8a;
    }

    private static int sm3Ff(int j, int x, int y, int z) {
        return (j < 16) ? (x ^ y ^ z) : ((x & y) | (x & z) | (y & z));
    }

    private static int sm3Gg(int j, int x, int y, int z) {
        return (j < 16) ? (x ^ y ^ z) : ((x & y) | ((~x) & z));
    }

    private static int sm3P0(int x) {
        return x ^ Integer.rotateLeft(x, 9) ^ Integer.rotateLeft(x, 17);
    }

    private static int sm3P1(int x) {
        return x ^ Integer.rotateLeft(x, 15) ^ Integer.rotateLeft(x, 23);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(algorithm);
            return messageDigest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 环境不支持摘要算法", e);
        }
    }

    private static byte[] digestStream(String algorithm, InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("输入流 inputStream 不能为 null");
        }
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[STREAM_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (bytesRead > 0) {
                    messageDigest.update(buffer, 0, bytesRead);
                }
            }
            return messageDigest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 环境不支持摘要算法", e);
        } catch (IOException e) {
            throw new UncheckedIOException("读取输入流计算摘要失败", e);
        }
    }

    /**
     * 国密 SM3 增量杂凑状态机，消除大载荷与多段输入时的整块拷贝开销
     */
    private static final class Sm3State {

        private static final int BLOCK_SIZE = 64;

        private final byte[] block = new byte[BLOCK_SIZE];
        private final int[] v = SM3_IV.clone();
        private final int[] w = new int[68];
        private final int[] wPrime = new int[64];
        private int blockOffset;
        private long totalBytes;

        void update(byte[] input, int offset, int len) {
            totalBytes += len;
            int pos = offset;
            int remaining = len;

            if (blockOffset > 0) {
                int copy = Math.min(remaining, BLOCK_SIZE - blockOffset);
                System.arraycopy(input, pos, block, blockOffset, copy);
                blockOffset += copy;
                pos += copy;
                remaining -= copy;
                if (blockOffset == BLOCK_SIZE) {
                    compressBlock(block, 0);
                    blockOffset = 0;
                }
            }

            while (remaining >= BLOCK_SIZE) {
                compressBlock(input, pos);
                pos += BLOCK_SIZE;
                remaining -= BLOCK_SIZE;
            }

            if (remaining > 0) {
                System.arraycopy(input, pos, block, 0, remaining);
                blockOffset = remaining;
            }
        }

        byte[] doFinal() {
            long bitLen = totalBytes * 8L;
            block[blockOffset++] = (byte) 0x80;
            if (blockOffset > 56) {
                Arrays.fill(block, blockOffset, BLOCK_SIZE, (byte) 0);
                compressBlock(block, 0);
                blockOffset = 0;
            }
            Arrays.fill(block, blockOffset, 56, (byte) 0);
            for (int i = 0; i < 8; i++) {
                block[56 + i] = (byte) ((bitLen >>> (56 - i * 8)) & 0xff);
            }
            compressBlock(block, 0);

            byte[] result = new byte[32];
            for (int i = 0; i < 8; i++) {
                result[i * 4] = (byte) ((v[i] >>> 24) & 0xff);
                result[i * 4 + 1] = (byte) ((v[i] >>> 16) & 0xff);
                result[i * 4 + 2] = (byte) ((v[i] >>> 8) & 0xff);
                result[i * 4 + 3] = (byte) (v[i] & 0xff);
            }
            Arrays.fill(block, (byte) 0);
            Arrays.fill(w, 0);
            Arrays.fill(wPrime, 0);
            return result;
        }

        private void compressBlock(byte[] buf, int offset) {
            for (int i = 0; i < 16; i++) {
                int idx = offset + i * 4;
                w[i] = ((buf[idx] & 0xff) << 24)
                        | ((buf[idx + 1] & 0xff) << 16)
                        | ((buf[idx + 2] & 0xff) << 8)
                        | (buf[idx + 3] & 0xff);
            }
            for (int i = 16; i < 68; i++) {
                w[i] = sm3P1(w[i - 16] ^ w[i - 9] ^ Integer.rotateLeft(w[i - 3], 15))
                        ^ Integer.rotateLeft(w[i - 13], 7) ^ w[i - 6];
            }
            for (int i = 0; i < 64; i++) {
                wPrime[i] = w[i] ^ w[i + 4];
            }

            int a = v[0], b = v[1], c = v[2], d = v[3];
            int e = v[4], f = v[5], g = v[6], h = v[7];

            for (int j = 0; j < 64; j++) {
                int ss1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(sm3T(j), j % 32), 7);
                int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
                int tt1 = sm3Ff(j, a, b, c) + d + ss2 + wPrime[j];
                int tt2 = sm3Gg(j, e, f, g) + h + ss1 + w[j];
                d = c;
                c = Integer.rotateLeft(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = Integer.rotateLeft(f, 19);
                f = e;
                e = sm3P0(tt2);
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
    }
}
