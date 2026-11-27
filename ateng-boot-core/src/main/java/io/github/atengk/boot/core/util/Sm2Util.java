package io.github.atengk.boot.core.util;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 纯 JDK 17 原生国密 SM2 椭圆曲线密码原语工具类 (GB/T 32918-2016)
 * 采用标准 256 位推荐曲线参数（GB/T 32918.5-2017）与雅可比投影坐标高性能点乘，零第三方加密依赖，
 * 提供密钥对生成（拒绝采样确保私钥严格落于 {@code [1, n-2]}）、多格式密钥解析、
 * SM2/SM3 签验（兼容 ASN.1 DER 与 Raw 64B）及 {@code C1C3C2} / ASN.1 DER 双向加解密
 *
 * @author Ateng
 * @since 2026-11-27
 */
public final class Sm2Util {

    /**
     * SM2 推荐 256 位素数域椭圆曲线参数 (GB/T 32918.5-2017)
     */
    public static final BigInteger P = new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF", 16);
    public static final BigInteger A = new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC", 16);
    public static final BigInteger B = new BigInteger("28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93", 16);
    public static final BigInteger N = new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123", 16);
    public static final BigInteger GX = new BigInteger("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7", 16);
    public static final BigInteger GY = new BigInteger("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0", 16);

    /**
     * 椭圆曲线基点 G
     */
    public static final Sm2Point G = new Sm2Point(GX, GY);

    /**
     * GM/T 0009-2012 / RFC 8998 标准默认用户标识字符串 ("1234567812345678")
     */
    public static final String DEFAULT_USER_ID = "1234567812345678";

    private static final byte[] DEFAULT_USER_ID_BYTES = DEFAULT_USER_ID.getBytes(StandardCharsets.UTF_8);

    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger N_MINUS_TWO = N.subtract(BigInteger.TWO);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Sm2Util() {
    }

    /**
     * 获取 GM/T 0009-2012 / RFC 8998 标准默认用户标识字节数组防御性拷贝
     *
     * @return 默认 16 字节用户标识数组拷贝
     */
    public static byte[] defaultUserId() {
        return DEFAULT_USER_ID_BYTES.clone();
    }

    /**
     * SM2 椭圆曲线不可变仿射坐标点载体
     *
     * @param x 仿射横坐标（无穷远点时为 {@code null}）
     * @param y 仿射纵坐标（无穷远点时为 {@code null}）
     */
    public record Sm2Point(BigInteger x, BigInteger y) {

        /**
         * 椭圆曲线无穷远点（零元）
         */
        public static final Sm2Point INFINITY = new Sm2Point(null, null);

        public Sm2Point {
            if (x == null || y == null) {
                x = null;
                y = null;
            } else {
                x = x.mod(P);
                y = y.mod(P);
            }
        }

        /**
         * 判断当前点是否为无穷远点
         *
         * @return 为无穷远点返回 {@code true}
         */
        public boolean isInfinity() {
            return x == null || y == null;
        }

        /**
         * 导出标准 65 字节非压缩编码 ({@code 0x04 || X || Y})
         *
         * @return 65 字节非压缩坐标；无穷远点返回单字节 {@code {0x00}}
         */
        public byte[] toUncompressedBytes() {
            if (isInfinity()) {
                return new byte[]{0x00};
            }
            byte[] out = new byte[65];
            out[0] = 0x04;
            System.arraycopy(toBytes32(x), 0, out, 1, 32);
            System.arraycopy(toBytes32(y), 0, out, 33, 32);
            return out;
        }

        /**
         * 导出标准 33 字节压缩编码 ({@code 0x02/0x03 || X})
         *
         * @return 33 字节压缩坐标；无穷远点返回单字节 {@code {0x00}}
         */
        public byte[] toCompressedBytes() {
            if (isInfinity()) {
                return new byte[]{0x00};
            }
            byte[] out = new byte[33];
            out[0] = (byte) (y.testBit(0) ? 0x03 : 0x02);
            System.arraycopy(toBytes32(x), 0, out, 1, 32);
            return out;
        }

        /**
         * 导出 130 位小写非压缩十六进制公钥串
         *
         * @return 小写十六进制公钥字符串
         */
        public String toHex() {
            return HexFormat.of().formatHex(toUncompressedBytes());
        }
    }

    /**
     * 国密 SM2 不可变密钥对载体
     *
     * @param privateKey       32 字节私钥标量 {@code d in [1, n-2]}
     * @param publicKey        曲线公钥点 {@code Q = [d]G}
     * @param privateKeyHex    64 位小写十六进制私钥串
     * @param publicKeyHex     130 位小写非压缩十六进制公钥串
     * @param privateKeyBase64 32 字节原始私钥 Base64 串
     * @param publicKeyBase64  65 字节非压缩公钥 Base64 串
     */
    public record Sm2KeyPair(
            BigInteger privateKey,
            Sm2Point publicKey,
            String privateKeyHex,
            String publicKeyHex,
            String privateKeyBase64,
            String publicKeyBase64
    ) {
    }

    /**
     * 生成随机国密 SM2 密钥对（采用拒绝采样保证私钥严格处于 {@code [1, N-2]}）
     *
     * @return SM2 密钥对载体
     */
    public static Sm2KeyPair generateKeyPair() {
        BigInteger d;
        do {
            d = new BigInteger(256, SECURE_RANDOM);
        } while (!isValidPrivateKey(d));

        Sm2Point q = multiply(d, G);
        byte[] dBytes = toBytes32(d);
        byte[] pubBytes = q.toUncompressedBytes();
        try {
            String privateKeyHex = HexFormat.of().formatHex(dBytes);
            String publicKeyHex = HexFormat.of().formatHex(pubBytes);
            String privateKeyBase64 = Base64.getEncoder().encodeToString(dBytes);
            String publicKeyBase64 = Base64.getEncoder().encodeToString(pubBytes);
            return new Sm2KeyPair(d, q, privateKeyHex, publicKeyHex, privateKeyBase64, publicKeyBase64);
        } finally {
            Arrays.fill(dBytes, (byte) 0);
        }
    }

    /**
     * 校验私钥标量是否处于标准有效区间 {@code [1, N - 2]}
     *
     * @param privateKey 私钥标量
     * @return 合法返回 {@code true}
     */
    public static boolean isValidPrivateKey(BigInteger privateKey) {
        return privateKey != null
                && privateKey.compareTo(BigInteger.ONE) >= 0
                && privateKey.compareTo(N_MINUS_TWO) <= 0;
    }

    /**
     * 校验点是否位于 SM2 推荐椭圆曲线上 ({@code y^2 == x^3 + Ax + B mod P})
     *
     * @param point 待校验点
     * @return 位于曲线上且非无穷远点返回 {@code true}
     */
    public static boolean isOnCurve(Sm2Point point) {
        if (point == null || point.isInfinity()) {
            return false;
        }
        BigInteger lhs = point.y().multiply(point.y()).mod(P);
        BigInteger rhs = point.x().modPow(THREE, P).add(A.multiply(point.x())).add(B).mod(P);
        return lhs.equals(rhs);
    }

    /**
     * 椭圆曲线点加法 {@code P1 + P2}
     *
     * @param p1 点 1
     * @param p2 点 2
     * @return 求和结果点
     */
    public static Sm2Point add(Sm2Point p1, Sm2Point p2) {
        if (p1 == null || p1.isInfinity()) {
            return p2 != null ? p2 : Sm2Point.INFINITY;
        }
        if (p2 == null || p2.isInfinity()) {
            return p1;
        }
        JacobianPoint j1 = new JacobianPoint(p1.x(), p1.y(), BigInteger.ONE);
        return j1.addAffine(p2.x(), p2.y()).toAffine();
    }

    /**
     * 基于雅可比投影坐标系执行高效标量乘法 {@code [k]P}
     *
     * @param k 标量乘数
     * @param p 基点
     * @return 结果仿射坐标点
     */
    public static Sm2Point multiply(BigInteger k, Sm2Point p) {
        return multiplyJacobian(p, k).toAffine();
    }

    /**
     * 将 BigInteger 导出为定长无符号大端字节数组
     *
     * @param value 大整数
     * @param len   目标字节长度
     * @return 定长字节数组
     */
    public static byte[] toFixedBytes(BigInteger value, int len) {
        if (value == null) {
            throw new IllegalArgumentException("待转换数值不能为 null");
        }
        byte[] raw = value.toByteArray();
        if (raw.length == len) {
            return raw;
        }
        byte[] res = new byte[len];
        if (raw.length > len) {
            System.arraycopy(raw, raw.length - len, res, 0, len);
        } else {
            System.arraycopy(raw, 0, res, len - raw.length, raw.length);
        }
        return res;
    }

    /**
     * 将 BigInteger 导出为 32 字节定长无符号大端数组
     *
     * @param value 大整数
     * @return 32 字节数组
     */
    public static byte[] toBytes32(BigInteger value) {
        return toFixedBytes(value, 32);
    }

    /**
     * SM2 密钥派生函数 KDF（基于国密 SM3）
     *
     * @param z    共享坐标拼接字节 ({@code x2 || y2})
     * @param klen 目标派生字节长度
     * @return 派生密钥字节流
     */
    public static byte[] kdf(byte[] z, int klen) {
        if (z == null || klen < 0) {
            throw new IllegalArgumentException("SM2 KDF 参数非法");
        }
        byte[] key = new byte[klen];
        if (klen == 0) {
            return key;
        }
        byte[] counter = new byte[4];
        int ct = 1;
        int offset = 0;
        while (offset < klen) {
            counter[0] = (byte) (ct >>> 24);
            counter[1] = (byte) (ct >>> 16);
            counter[2] = (byte) (ct >>> 8);
            counter[3] = (byte) ct;
            byte[] h = DigestUtil.sm3(z, counter);
            try {
                int copyLen = Math.min(32, klen - offset);
                System.arraycopy(h, 0, key, offset, copyLen);
                offset += copyLen;
                ct++;
            } finally {
                Arrays.fill(h, (byte) 0);
            }
        }
        return key;
    }

    /**
     * 解析多格式 SM2 私钥（兼容 64 位 Hex、32 字节 Base64、PEM 及 PKCS#8 / SEC1 DER）
     *
     * @param privateKeyStr 私钥字符串
     * @return 经严格区间校验 {@code [1, N-2]} 的私钥标量
     * @throws IllegalArgumentException 格式非法或私钥标量越界时抛出
     */
    public static BigInteger parsePrivateKey(String privateKeyStr) {
        if (privateKeyStr == null || privateKeyStr.isBlank()) {
            throw new IllegalArgumentException("SM2 私钥不能为空");
        }
        String clean = cleanKey(privateKeyStr);
        BigInteger d = null;
        if (clean.length() == 64 && isHex(clean)) {
            d = new BigInteger(clean, 16);
        } else {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(clean);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("SM2 私钥格式非法，既非 64 位 Hex 也非合法 Base64", e);
            }
            try {
                if (bytes.length == 32) {
                    d = new BigInteger(1, bytes);
                } else {
                    for (int i = 0; i <= bytes.length - 34; i++) {
                        if (bytes[i] == 0x04 && bytes[i + 1] == 0x20) {
                            byte[] dBytes = new byte[32];
                            try {
                                System.arraycopy(bytes, i + 2, dBytes, 0, 32);
                                d = new BigInteger(1, dBytes);
                                break;
                            } finally {
                                Arrays.fill(dBytes, (byte) 0);
                            }
                        }
                    }
                    if (d == null) {
                        throw new IllegalArgumentException("不支持的 SM2 私钥字节长度: " + bytes.length);
                    }
                }
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
        if (!isValidPrivateKey(d)) {
            throw new IllegalArgumentException("SM2 私钥标量超出有效定义域 [1, n-2]");
        }
        return d;
    }

    /**
     * 解析多格式 SM2 公钥（兼容 130 位非压缩 Hex、128 位裸 Hex、66 位压缩 Hex、65/64/33B Base64、PEM 及 X.509 DER）
     *
     * @param publicKeyStr 公钥字符串
     * @return 校验过 {@link #isOnCurve} 的曲线公钥点
     * @throws IllegalArgumentException 格式非法或点不在曲线上时抛出
     */
    public static Sm2Point parsePublicKey(String publicKeyStr) {
        if (publicKeyStr == null || publicKeyStr.isBlank()) {
            throw new IllegalArgumentException("SM2 公钥不能为空");
        }
        String clean = cleanKey(publicKeyStr);
        Sm2Point point = null;
        if (isHex(clean)) {
            if (clean.length() == 130 && clean.startsWith("04")) {
                BigInteger x = new BigInteger(clean.substring(2, 66), 16);
                BigInteger y = new BigInteger(clean.substring(66, 130), 16);
                point = new Sm2Point(x, y);
            } else if (clean.length() == 128) {
                BigInteger x = new BigInteger(clean.substring(0, 64), 16);
                BigInteger y = new BigInteger(clean.substring(64, 128), 16);
                point = new Sm2Point(x, y);
            } else if (clean.length() == 66 && (clean.startsWith("02") || clean.startsWith("03"))) {
                point = decompressPoint(clean);
            }
        }

        if (point == null) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(clean);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("SM2 公钥格式非法", e);
            }
            if (bytes.length == 65 && bytes[0] == 0x04) {
                point = readUncompressedPoint(bytes, 1);
            } else if (bytes.length == 64) {
                point = readUncompressedPoint(bytes, 0);
            } else if (bytes.length == 33 && (bytes[0] == 0x02 || bytes[0] == 0x03)) {
                point = decompressPoint(HexFormat.of().formatHex(bytes));
            } else {
                for (int i = 0; i <= bytes.length - 65; i++) {
                    if (bytes[i] == 0x04) {
                        Sm2Point candidate = readUncompressedPoint(bytes, i + 1);
                        if (isOnCurve(candidate)) {
                            point = candidate;
                            break;
                        }
                    }
                }
            }
        }

        if (point == null || !isOnCurve(point)) {
            throw new IllegalArgumentException("SM2 公钥格式非法或点不在标准椭圆曲线上");
        }
        return point;
    }

    /**
     * 将 SM2 公钥点编码为 PEM 格式字符串
     *
     * @param publicKey SM2 公钥点
     * @return PEM 公钥；入参为 {@code null} 时返回 {@code null}
     */
    public static String encodePublicKeyToPem(Sm2Point publicKey) {
        if (publicKey == null) {
            return null;
        }
        if (!isOnCurve(publicKey)) {
            throw new IllegalArgumentException("SM2 公钥点不在标准椭圆曲线上");
        }
        String base64 = Base64.getEncoder().encodeToString(publicKey.toUncompressedBytes());
        return formatAsPem("PUBLIC KEY", base64);
    }

    /**
     * 将 SM2 私钥标量编码为 PEM 格式字符串
     *
     * @param privateKey 私钥标量
     * @return PEM 私钥；入参为 {@code null} 时返回 {@code null}
     */
    public static String encodePrivateKeyToPem(BigInteger privateKey) {
        if (privateKey == null) {
            return null;
        }
        if (!isValidPrivateKey(privateKey)) {
            throw new IllegalArgumentException("SM2 私钥标量超出有效定义域 [1, n-2]");
        }
        byte[] dBytes = toBytes32(privateKey);
        try {
            String base64 = Base64.getEncoder().encodeToString(dBytes);
            return formatAsPem("PRIVATE KEY", base64);
        } finally {
            Arrays.fill(dBytes, (byte) 0);
        }
    }

    // ==================== SM2 公钥加解密 (GB/T 32918.4-2016) ====================

    /**
     * 执行 SM2 公钥加密（输出国标标准 {@code C1C3C2}：{@code 0x04 || X1 || Y1 || C3 || C2}）
     *
     * @param plaintext 非空明文字节数组
     * @param publicKey 接收方 SM2 公钥点
     * @return {@code C1C3C2} 密文字节数组
     * @throws IllegalArgumentException 明文为空或公钥不在曲线上时抛出
     */
    public static byte[] encrypt(byte[] plaintext, Sm2Point publicKey) {
        if (plaintext == null || plaintext.length == 0) {
            throw new IllegalArgumentException("SM2 加密明文不能为空");
        }
        if (publicKey == null || !isOnCurve(publicKey)) {
            throw new IllegalArgumentException("SM2 加密公钥点不在标准椭圆曲线上");
        }

        int klen = plaintext.length;
        byte[] x2 = null;
        byte[] y2 = null;
        byte[] z = null;
        byte[] t = null;
        try {
            while (true) {
                BigInteger k = randomEncryptScalar();
                Sm2Point c1Point = multiply(k, G);
                Sm2Point sPoint = multiply(k, publicKey);
                if (sPoint.isInfinity()) {
                    continue;
                }

                x2 = toBytes32(sPoint.x());
                y2 = toBytes32(sPoint.y());
                z = new byte[64];
                System.arraycopy(x2, 0, z, 0, 32);
                System.arraycopy(y2, 0, z, 32, 32);

                t = kdf(z, klen);
                if (isAllZero(t)) {
                    clearBuffers(x2, y2, z, t);
                    continue;
                }

                byte[] c2 = new byte[klen];
                for (int i = 0; i < klen; i++) {
                    c2[i] = (byte) (plaintext[i] ^ t[i]);
                }
                byte[] c3 = DigestUtil.sm3(x2, plaintext, y2);

                byte[] x1 = toBytes32(c1Point.x());
                byte[] y1 = toBytes32(c1Point.y());
                byte[] result = new byte[65 + 32 + klen];
                result[0] = 0x04;
                System.arraycopy(x1, 0, result, 1, 32);
                System.arraycopy(y1, 0, result, 33, 32);
                System.arraycopy(c3, 0, result, 65, 32);
                System.arraycopy(c2, 0, result, 97, klen);
                return result;
            }
        } finally {
            clearBuffers(x2, y2, z, t);
        }
    }

    /**
     * 执行 SM2 公钥加密（输出标准 {@code C1C3C2}）
     *
     * @param plaintext    非空明文字节数组
     * @param publicKeyStr Hex / Base64 / PEM 公钥字符串
     * @return {@code C1C3C2} 密文字节数组
     */
    public static byte[] encrypt(byte[] plaintext, String publicKeyStr) {
        return encrypt(plaintext, parsePublicKey(publicKeyStr));
    }

    /**
     * 执行 SM2 公钥加密并返回 Base64 编码的 {@code C1C3C2} 密文字符串
     *
     * @param plaintext    非空 UTF-8 明文字符串
     * @param publicKeyStr Hex / Base64 / PEM 公钥字符串
     * @return Base64 编码密文
     */
    public static String encryptBase64(String plaintext, String publicKeyStr) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("SM2 加密明文不能为空");
        }
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] cipher = encrypt(plainBytes, parsePublicKey(publicKeyStr));
            return Base64.getEncoder().encodeToString(cipher);
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }
    }

    /**
     * 执行 SM2 公钥加密并封装为 ASN.1 DER 编码密文字节数组
     *
     * @param plaintext 非空明文字节数组
     * @param publicKey 接收方 SM2 公钥点
     * @return ASN.1 DER 编码密文字节数组
     */
    public static byte[] encryptToDer(byte[] plaintext, Sm2Point publicKey) {
        return c1c3c2ToDer(encrypt(plaintext, publicKey));
    }

    /**
     * 执行 SM2 公钥加密并返回 Base64 编码的 ASN.1 DER 密文字符串
     *
     * @param plaintext    非空 UTF-8 明文字符串
     * @param publicKeyStr Hex / Base64 / PEM 公钥字符串
     * @return Base64 编码 ASN.1 DER 密文
     */
    public static String encryptToDerBase64(String plaintext, String publicKeyStr) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("SM2 加密明文不能为空");
        }
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] der = encryptToDer(plainBytes, parsePublicKey(publicKeyStr));
            return Base64.getEncoder().encodeToString(der);
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }
    }

    /**
     * 执行 SM2 私钥解密（自动兼容 {@code C1C3C2} 带/不带 {@code 0x04} 头与 ASN.1 DER 双格式）
     *
     * @param ciphertext 密文字节数组
     * @param privateKey 解密私钥标量 {@code d in [1, N-2]}
     * @return 还原后的明文字节数组
     * @throws IllegalArgumentException 密文/私钥格式或范围非法、或 {@code C1} 不在曲线上时抛出
     * @throws IllegalStateException    {@code C3} 摘要认证校验失败时抛出
     */
    public static byte[] decrypt(byte[] ciphertext, BigInteger privateKey) {
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("SM2 解密密文不能为空");
        }
        if (!isValidPrivateKey(privateKey)) {
            throw new IllegalArgumentException("SM2 私钥标量超出有效定义域 [1, n-2]");
        }
        if (ciphertext[0] == 0x30) {
            byte[] c1c3c2;
            try {
                c1c3c2 = derToC1c3c2(ciphertext);
            } catch (IllegalArgumentException derEx) {
                return decryptC1C3C2(ciphertext, privateKey);
            }
            return decryptC1C3C2(c1c3c2, privateKey);
        }
        return decryptC1C3C2(ciphertext, privateKey);
    }

    /**
     * 执行 SM2 私钥解密
     *
     * @param ciphertext    密文字节数组
     * @param privateKeyStr Hex / Base64 / PEM / PKCS#8 私钥字符串
     * @return 还原后的明文字节数组
     */
    public static byte[] decrypt(byte[] ciphertext, String privateKeyStr) {
        return decrypt(ciphertext, parsePrivateKey(privateKeyStr));
    }

    /**
     * 解密 Base64 编码的 SM2 密文字符串（兼容 {@code C1C3C2} 与 ASN.1 DER）
     *
     * @param ciphertextBase64 Base64 编码密文
     * @param privateKeyStr    Hex / Base64 / PEM / PKCS#8 私钥字符串
     * @return UTF-8 明文字符串
     */
    public static String decryptBase64(String ciphertextBase64, String privateKeyStr) {
        if (ciphertextBase64 == null || ciphertextBase64.isBlank()) {
            throw new IllegalArgumentException("SM2 Base64 密文不能为空");
        }
        byte[] cipherBytes;
        try {
            cipherBytes = Base64.getDecoder().decode(ciphertextBase64.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SM2 密文 Base64 格式非法", e);
        }
        byte[] plainBytes = decrypt(cipherBytes, parsePrivateKey(privateKeyStr));
        try {
            return new String(plainBytes, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }
    }

    private static byte[] decryptC1C3C2(byte[] ciphertext, BigInteger privateKey) {
        int offset = ciphertext[0] == 0x04 ? 1 : 0;
        int c1Len = offset == 1 ? 65 : 64;
        if (ciphertext.length <= c1Len + 32) {
            throw new IllegalArgumentException("SM2 密文长度非法，不足以包含完整的 C1/C3/C2");
        }

        Sm2Point c1 = readUncompressedPoint(ciphertext, offset);
        if (!isOnCurve(c1)) {
            if (offset == 1 && ciphertext.length > 64 + 32) {
                Sm2Point altC1 = readUncompressedPoint(ciphertext, 0);
                if (isOnCurve(altC1)) {
                    c1 = altC1;
                    c1Len = 64;
                } else {
                    throw new IllegalArgumentException("SM2 密文 C1 曲线点不合法 (NotInCurve)");
                }
            } else {
                throw new IllegalArgumentException("SM2 密文 C1 曲线点不合法 (NotInCurve)");
            }
        }

        Sm2Point s = multiply(privateKey, c1);
        if (s.isInfinity()) {
            throw new IllegalArgumentException("SM2 解密点落在无穷远点");
        }

        byte[] x2 = toBytes32(s.x());
        byte[] y2 = toBytes32(s.y());
        byte[] z = new byte[64];
        byte[] t = null;
        byte[] m = null;
        byte[] expectedC3 = null;
        try {
            System.arraycopy(x2, 0, z, 0, 32);
            System.arraycopy(y2, 0, z, 32, 32);

            byte[] c3 = new byte[32];
            System.arraycopy(ciphertext, c1Len, c3, 0, 32);

            int c2Len = ciphertext.length - c1Len - 32;
            t = kdf(z, c2Len);
            if (isAllZero(t)) {
                throw new IllegalStateException("SM2 KDF 派生密钥流异常");
            }
            m = new byte[c2Len];
            for (int i = 0; i < c2Len; i++) {
                m[i] = (byte) (ciphertext[c1Len + 32 + i] ^ t[i]);
            }

            expectedC3 = DigestUtil.sm3(x2, m, y2);
            if (!MessageDigest.isEqual(c3, expectedC3)) {
                throw new IllegalStateException("SM2 消息摘要 MAC (C3) 校验失败");
            }
            return m.clone();
        } finally {
            clearBuffers(x2, y2, z, t);
            clearBuffers(m, expectedC3);
        }
    }

    /**
     * 将 ASN.1 DER 编码密文转译为国家权威标准的 {@code C1C3C2} 字节流 ({@code 0x04 || X1 || Y1 || C3 || C2})
     *
     * @param der ASN.1 DER 密文字节流 (必须以 {@code 0x30} 起始)
     * @return 标准 {@code C1C3C2} 密文字节流
     * @throws IllegalArgumentException DER 格式损坏或字段结构非法时抛出
     */
    public static byte[] derToC1c3c2(byte[] der) {
        if (der == null || der.length < 8) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 密文字节流长度非法，至少需要 8 字节");
        }
        if (der[0] != 0x30) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 格式非法: 必须以 0x30 (SEQUENCE) 起始，实际为: 0x"
                    + Integer.toHexString(der[0] & 0xff));
        }

        int[] cursor = new int[]{1};
        int seqLen = readDerLength(der, cursor);
        if (seqLen > der.length - cursor[0]) {
            throw new IllegalArgumentException("ASN.1 SEQUENCE 声明长度 (" + seqLen + ") 超出实际数据流长度 (" + (der.length - cursor[0]) + ")");
        }

        int seqEnd = cursor[0] + seqLen;
        byte[] x1 = readDerField(der, cursor, seqEnd, (byte) 0x02, "x1", "INTEGER");
        byte[] y1 = readDerField(der, cursor, seqEnd, (byte) 0x02, "y1", "INTEGER");
        byte[] c3 = readDerField(der, cursor, seqEnd, (byte) 0x04, "c3", "OCTET STRING");
        byte[] c2 = readDerField(der, cursor, seqEnd, (byte) 0x04, "c2", "OCTET STRING");
        if (cursor[0] != seqEnd || c3.length != 32) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 密文字段结构非法");
        }

        byte[] assembled = new byte[65 + 32 + c2.length];
        assembled[0] = 0x04;
        System.arraycopy(toBytes32(new BigInteger(1, x1)), 0, assembled, 1, 32);
        System.arraycopy(toBytes32(new BigInteger(1, y1)), 0, assembled, 33, 32);
        System.arraycopy(c3, 0, assembled, 65, 32);
        System.arraycopy(c2, 0, assembled, 97, c2.length);
        return assembled;
    }

    /**
     * 将标准 {@code C1C3C2} 密文封装为标准 ASN.1 DER 编码（完整支持多字节长度编码，突破 64KB 限制）
     *
     * @param c1c3c2 标准 {@code C1C3C2} 密文字节流 (兼容 65 字节带 {@code 0x04} 头或 64 字节裸 {@code C1})
     * @return ASN.1 DER 编码密文字节流
     * @throws IllegalArgumentException 长度非法或已为 ASN.1 DER 时抛出
     */
    public static byte[] c1c3c2ToDer(byte[] c1c3c2) {
        if (c1c3c2 == null) {
            throw new IllegalArgumentException("SM2 C1C3C2 密文数组不能为空");
        }
        if (c1c3c2.length > 0 && c1c3c2[0] == 0x30) {
            throw new IllegalArgumentException("入参密文已为 ASN.1 DER 格式 (0x30 起始)，无需重复转译");
        }
        int offset = (c1c3c2.length > 0 && c1c3c2[0] == 0x04) ? 1 : 0;
        int c1Len = (offset == 1) ? 65 : 64;
        int minLen = c1Len + 32;
        if (c1c3c2.length < minLen) {
            throw new IllegalArgumentException("SM2 C1C3C2 密文长度非法，不足以包含 C1 与 C3 (期望至少 "
                    + minLen + " 字节，实际 " + c1c3c2.length + " 字节)");
        }

        byte[] x1 = Arrays.copyOfRange(c1c3c2, offset, offset + 32);
        byte[] y1 = Arrays.copyOfRange(c1c3c2, offset + 32, offset + 64);
        byte[] c3 = Arrays.copyOfRange(c1c3c2, c1Len, c1Len + 32);
        byte[] c2 = Arrays.copyOfRange(c1c3c2, c1Len + 32, c1c3c2.length);

        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        writeDerInteger(inner, new BigInteger(1, x1));
        writeDerInteger(inner, new BigInteger(1, y1));
        writeDerOctetString(inner, c3);
        writeDerOctetString(inner, c2);

        byte[] content = inner.toByteArray();
        ByteArrayOutputStream seq = new ByteArrayOutputStream(content.length + 6);
        seq.write(0x30);
        writeDerLength(seq, content.length);
        seq.write(content, 0, content.length);
        return seq.toByteArray();
    }

    // ==================== SM2/SM3 数字签名与验签 (GB/T 32918.2-2016) ====================

    /**
     * 使用默认 {@link #DEFAULT_USER_ID} 执行 SM2/SM3 数字签名（默认输出标准 ASN.1 DER 字节数组）
     *
     * @param data       待签名数据
     * @param privateKey 私钥标量 {@code d in [1, N-2]}
     * @return ASN.1 DER 编码签名字节数组
     */
    public static byte[] sign(byte[] data, BigInteger privateKey) {
        return sign(data, privateKey, DEFAULT_USER_ID_BYTES);
    }

    /**
     * 使用自定义 {@code userId} 执行 SM2/SM3 数字签名（默认输出标准 ASN.1 DER 字节数组）
     *
     * @param data       待签名数据
     * @param privateKey 私钥标量 {@code d in [1, N-2]}
     * @param userId     用户标识字节数组（为空时回退至 {@link #DEFAULT_USER_ID}）
     * @return ASN.1 DER 编码签名字节数组
     */
    public static byte[] sign(byte[] data, BigInteger privateKey, byte[] userId) {
        BigInteger[] rs = signComponents(data, privateKey, userId);
        return encodeSignatureDer(rs[0], rs[1]);
    }

    /**
     * 使用默认 {@link #DEFAULT_USER_ID} 执行 SM2/SM3 数字签名并输出裸 64 字节 {@code r || s}
     *
     * @param data       待签名数据
     * @param privateKey 私钥标量 {@code d in [1, N-2]}
     * @return 64 字节裸签名 ({@code r || s})
     */
    public static byte[] signRaw(byte[] data, BigInteger privateKey) {
        return signRaw(data, privateKey, DEFAULT_USER_ID_BYTES);
    }

    /**
     * 使用自定义 {@code userId} 执行 SM2/SM3 数字签名并输出裸 64 字节 {@code r || s}
     *
     * @param data       待签名数据
     * @param privateKey 私钥标量 {@code d in [1, N-2]}
     * @param userId     用户标识字节数组（为空时回退至 {@link #DEFAULT_USER_ID}）
     * @return 64 字节裸签名 ({@code r || s})
     */
    public static byte[] signRaw(byte[] data, BigInteger privateKey, byte[] userId) {
        BigInteger[] rs = signComponents(data, privateKey, userId);
        byte[] raw = new byte[64];
        System.arraycopy(toBytes32(rs[0]), 0, raw, 0, 32);
        System.arraycopy(toBytes32(rs[1]), 0, raw, 32, 32);
        return raw;
    }

    /**
     * 使用默认 {@link #DEFAULT_USER_ID} 执行 SM2/SM3 数字签名并返回 Base64(DER) 字符串
     *
     * @param data          待签名文本（UTF-8）
     * @param privateKeyStr Hex / Base64 / PEM / PKCS#8 私钥字符串
     * @return Base64 编码 ASN.1 DER 签名
     */
    public static String signBase64(String data, String privateKeyStr) {
        return signBase64(data, privateKeyStr, DEFAULT_USER_ID_BYTES);
    }

    /**
     * 使用自定义 {@code userId} 执行 SM2/SM3 数字签名并返回 Base64(DER) 字符串
     *
     * @param data          待签名文本（UTF-8）
     * @param privateKeyStr Hex / Base64 / PEM / PKCS#8 私钥字符串
     * @param userId        用户标识字节数组（为空时回退至 {@link #DEFAULT_USER_ID}）
     * @return Base64 编码 ASN.1 DER 签名
     */
    public static String signBase64(String data, String privateKeyStr, byte[] userId) {
        if (data == null) {
            throw new IllegalArgumentException("SM2 待签名数据不能为 null");
        }
        byte[] der = sign(data.getBytes(StandardCharsets.UTF_8), parsePrivateKey(privateKeyStr), userId);
        return Base64.getEncoder().encodeToString(der);
    }

    /**
     * 使用默认 {@link #DEFAULT_USER_ID} 执行 SM2/SM3 验签（自动兼容 ASN.1 DER 与 Raw 64B 签名）
     *
     * @param data      原文数据
     * @param signature 签名字节数组（ASN.1 DER 或裸 64 字节）
     * @param publicKey 公钥点
     * @return 验签通过返回 {@code true}；输入非法或验签失败安全返回 {@code false}
     */
    public static boolean verify(byte[] data, byte[] signature, Sm2Point publicKey) {
        return verify(data, signature, publicKey, DEFAULT_USER_ID_BYTES);
    }

    /**
     * 使用自定义 {@code userId} 执行 SM2/SM3 验签（自动兼容 ASN.1 DER 与 Raw 64B 签名）
     *
     * @param data      原文数据
     * @param signature 签名字节数组（ASN.1 DER 或裸 64 字节）
     * @param publicKey 公钥点
     * @param userId    用户标识字节数组（为空时回退至 {@link #DEFAULT_USER_ID}）
     * @return 验签通过返回 {@code true}；输入非法或验签失败安全返回 {@code false}
     */
    public static boolean verify(byte[] data, byte[] signature, Sm2Point publicKey, byte[] userId) {
        if (data == null || signature == null || signature.length < 8 || !isOnCurve(publicKey)) {
            return false;
        }
        try {
            BigInteger[] rs = decodeSignatureDerOrRaw(signature);
            BigInteger r = rs[0];
            BigInteger s = rs[1];

            if (r.compareTo(BigInteger.ONE) < 0 || r.compareTo(N) >= 0) {
                return false;
            }
            if (s.compareTo(BigInteger.ONE) < 0 || s.compareTo(N) >= 0) {
                return false;
            }

            byte[] effectiveUserId = (userId != null && userId.length > 0) ? userId : DEFAULT_USER_ID_BYTES;
            byte[] za = computeZa(effectiveUserId, publicKey);
            byte[] eBytes = DigestUtil.sm3(za, data);
            BigInteger e = new BigInteger(1, eBytes);

            BigInteger t = r.add(s).mod(N);
            if (t.signum() == 0) {
                return false;
            }

            JacobianPoint sG = multiplyJacobian(G, s);
            JacobianPoint tP = multiplyJacobian(publicKey, t);
            JacobianPoint ptJ = sG.add(tP);
            if (ptJ.isInfinity()) {
                return false;
            }
            Sm2Point pt = ptJ.toAffine();
            BigInteger computedR = e.add(pt.x()).mod(N);
            return computedR.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 使用默认 {@link #DEFAULT_USER_ID} 对 Base64 或 Hex 签名字符串执行 SM2/SM3 验签
     *
     * @param data         原文文本（UTF-8）
     * @param signature    Base64 或 Hex 格式签名串（兼容 ASN.1 DER 与 Raw 64B）
     * @param publicKeyStr Hex / Base64 / PEM / X.509 公钥字符串
     * @return 验签通过返回 {@code true}；输入非法或验签失败安全返回 {@code false}
     */
    public static boolean verifyBase64(String data, String signature, String publicKeyStr) {
        return verifyBase64(data, signature, publicKeyStr, DEFAULT_USER_ID_BYTES);
    }

    /**
     * 使用自定义 {@code userId} 对 Base64 或 Hex 签名字符串执行 SM2/SM3 验签
     *
     * @param data         原文文本（UTF-8）
     * @param signature    Base64 或 Hex 格式签名串（兼容 ASN.1 DER 与 Raw 64B）
     * @param publicKeyStr Hex / Base64 / PEM / X.509 公钥字符串
     * @param userId       用户标识字节数组（为空时回退至 {@link #DEFAULT_USER_ID}）
     * @return 验签通过返回 {@code true}；输入非法或验签失败安全返回 {@code false}
     */
    public static boolean verifyBase64(String data, String signature, String publicKeyStr, byte[] userId) {
        if (data == null || signature == null || signature.isBlank()
                || publicKeyStr == null || publicKeyStr.isBlank()) {
            return false;
        }
        try {
            Sm2Point publicKey = parsePublicKey(publicKeyStr);
            byte[] sigBytes = decodeSignatureString(signature);
            return verify(data.getBytes(StandardCharsets.UTF_8), sigBytes, publicKey, userId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 将 ASN.1 DER 编码的 SM2 签名无损转换为裸 64 字节 ({@code r || s})
     *
     * @param derSignature ASN.1 DER 签名字节数组
     * @return 64 字节裸签名
     */
    public static byte[] derSignatureToRaw(byte[] derSignature) {
        BigInteger[] rs = decodeSignatureDer(derSignature);
        byte[] raw = new byte[64];
        System.arraycopy(toBytes32(rs[0]), 0, raw, 0, 32);
        System.arraycopy(toBytes32(rs[1]), 0, raw, 32, 32);
        return raw;
    }

    /**
     * 将裸 64 字节 ({@code r || s}) SM2 签名无损封装为标准 ASN.1 DER 编码
     *
     * @param rawSignature 64 字节裸签名
     * @return ASN.1 DER 编码签名
     */
    public static byte[] rawSignatureToDer(byte[] rawSignature) {
        if (rawSignature == null || rawSignature.length != 64) {
            throw new IllegalArgumentException("SM2 裸签名字节数组长度必须为 64 字节");
        }
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(rawSignature, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(rawSignature, 32, 64));
        return encodeSignatureDer(r, s);
    }

    /**
     * 计算 SM2 签名预处理哈希 {@code Z_A = SM3(ENTLA || userId || a || b || xG || yG || xA || yA)}
     *
     * @param userId    用户标识
     * @param publicKey 公钥点
     * @return 32 字节 {@code Z_A}
     */
    public static byte[] computeZa(byte[] userId, Sm2Point publicKey) {
        byte[] effectiveUserId = (userId != null && userId.length > 0) ? userId : DEFAULT_USER_ID_BYTES;
        int entla = effectiveUserId.length * 8;
        byte[] entlaBytes = new byte[]{(byte) ((entla >>> 8) & 0xFF), (byte) (entla & 0xFF)};
        return DigestUtil.sm3(
                entlaBytes,
                effectiveUserId,
                toBytes32(A),
                toBytes32(B),
                toBytes32(GX),
                toBytes32(GY),
                toBytes32(publicKey.x()),
                toBytes32(publicKey.y())
        );
    }

    private static BigInteger[] signComponents(byte[] data, BigInteger privateKey, byte[] userId) {
        if (data == null) {
            throw new IllegalArgumentException("SM2 待签名数据不能为 null");
        }
        if (!isValidPrivateKey(privateKey)) {
            throw new IllegalArgumentException("SM2 私钥标量超出有效定义域 [1, n-2]");
        }
        byte[] effectiveUserId = (userId != null && userId.length > 0) ? userId : DEFAULT_USER_ID_BYTES;
        Sm2Point pubKey = multiply(privateKey, G);
        byte[] za = computeZa(effectiveUserId, pubKey);
        byte[] eBytes = DigestUtil.sm3(za, data);
        BigInteger e = new BigInteger(1, eBytes);

        BigInteger dPlus1Inv = BigInteger.ONE.add(privateKey).modInverse(N);
        BigInteger k;
        BigInteger r;
        BigInteger s;
        do {
            k = randomEncryptScalar();
            Sm2Point kG = multiply(k, G);
            r = e.add(kG.x()).mod(N);
            if (r.signum() == 0 || r.add(k).equals(N)) {
                s = BigInteger.ZERO;
                continue;
            }
            s = dPlus1Inv.multiply(k.subtract(r.multiply(privateKey))).mod(N);
        } while (s.signum() == 0);
        return new BigInteger[]{r, s};
    }

    private static BigInteger randomEncryptScalar() {
        BigInteger k;
        do {
            k = new BigInteger(256, SECURE_RANDOM);
        } while (k.compareTo(BigInteger.ONE) < 0 || k.compareTo(N) >= 0);
        return k;
    }

    private static byte[] decodeSignatureString(String signature) {
        String clean = cleanKey(signature);
        if (isHex(clean) && (clean.length() == 128 || clean.startsWith("30"))) {
            return HexFormat.of().parseHex(clean);
        }
        return Base64.getDecoder().decode(clean);
    }

    private static BigInteger[] decodeSignatureDerOrRaw(byte[] bytes) {
        if (bytes[0] == 0x30) {
            return decodeSignatureDer(bytes);
        }
        if (bytes.length == 64) {
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(bytes, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(bytes, 32, 64));
            return new BigInteger[]{r, s};
        }
        throw new IllegalArgumentException("不支持的 SM2 签名编码格式");
    }

    private static BigInteger[] decodeSignatureDer(byte[] bytes) {
        if (bytes == null || bytes.length < 8 || bytes[0] != 0x30) {
            throw new IllegalArgumentException("非法的 ASN.1 DER 签名格式");
        }
        int[] cursor = new int[]{1};
        int seqLen = readDerLength(bytes, cursor);
        if (cursor[0] + seqLen != bytes.length) {
            throw new IllegalArgumentException("非法的 ASN.1 DER 签名: 序列长度不匹配");
        }
        int seqEnd = bytes.length;
        byte[] rb = readDerField(bytes, cursor, seqEnd, (byte) 0x02, "r", "INTEGER");
        byte[] sb = readDerField(bytes, cursor, seqEnd, (byte) 0x02, "s", "INTEGER");
        if (cursor[0] != bytes.length) {
            throw new IllegalArgumentException("非法的 ASN.1 DER 签名: 包含非法尾部冗余字节");
        }
        return new BigInteger[]{new BigInteger(1, rb), new BigInteger(1, sb)};
    }

    private static byte[] encodeSignatureDer(BigInteger r, BigInteger s) {
        ByteArrayOutputStream inner = new ByteArrayOutputStream(72);
        writeDerInteger(inner, r);
        writeDerInteger(inner, s);
        byte[] content = inner.toByteArray();
        ByteArrayOutputStream seq = new ByteArrayOutputStream(content.length + 3);
        seq.write(0x30);
        writeDerLength(seq, content.length);
        seq.write(content, 0, content.length);
        return seq.toByteArray();
    }

    private static Sm2Point decompressPoint(String hex) {
        int yTilde = Integer.parseInt(hex.substring(0, 2), 16) & 1;
        BigInteger x = new BigInteger(hex.substring(2), 16);
        BigInteger alpha = x.modPow(THREE, P).add(A.multiply(x)).add(B).mod(P);
        BigInteger pPlus1Div4 = P.add(BigInteger.ONE).shiftRight(2);
        BigInteger y = alpha.modPow(pPlus1Div4, P);
        if (y.testBit(0) != (yTilde == 1)) {
            y = P.subtract(y);
        }
        return new Sm2Point(x, y);
    }

    private static Sm2Point readUncompressedPoint(byte[] bytes, int offset) {
        byte[] xb = Arrays.copyOfRange(bytes, offset, offset + 32);
        byte[] yb = Arrays.copyOfRange(bytes, offset + 32, offset + 64);
        return new Sm2Point(new BigInteger(1, xb), new BigInteger(1, yb));
    }

    private static byte[] readDerField(byte[] der, int[] cursor, int seqEnd, byte expectedTag,
                                       String fieldName, String typeDesc) {
        int idx = cursor[0];
        if (idx >= seqEnd || der[idx++] != expectedTag) {
            String tagHex = (expectedTag < 0x10 ? "0" : "") + Integer.toHexString(expectedTag & 0xff);
            throw new IllegalArgumentException("ASN.1 解析错误: 缺少 " + fieldName + " " + typeDesc + " 标记 (0x" + tagHex + ")");
        }
        cursor[0] = idx;
        int len = readDerLength(der, cursor);
        if (len > seqEnd - cursor[0]) {
            throw new IllegalArgumentException("ASN.1 " + fieldName + " 字段长度溢出");
        }
        byte[] value = Arrays.copyOfRange(der, cursor[0], cursor[0] + len);
        cursor[0] += len;
        return value;
    }

    private static int readDerLength(byte[] der, int[] cursor) {
        if (cursor[0] >= der.length) {
            throw new IllegalArgumentException("ASN.1 解析错误: 长度字段偏移量超出数组边界");
        }
        int b = der[cursor[0]++] & 0xff;
        if ((b & 0x80) == 0) {
            return b;
        }
        int numBytes = b & 0x7f;
        if (numBytes == 0 || numBytes > 4) {
            throw new IllegalArgumentException("ASN.1 解析错误: 不支持的多字节长度 (numBytes=" + numBytes + ")");
        }
        if (cursor[0] + numBytes > der.length) {
            throw new IllegalArgumentException("ASN.1 解析错误: 长度字段字节不足");
        }
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | (der[cursor[0]++] & 0xff);
        }
        if (length < 0) {
            throw new IllegalArgumentException("ASN.1 解析错误: 长度溢出负数: " + length);
        }
        return length;
    }

    private static void writeDerLength(ByteArrayOutputStream out, int len) {
        if (len < 128) {
            out.write(len);
        } else if (len <= 0xff) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xffff) {
            out.write(0x82);
            out.write(len >>> 8);
            out.write(len & 0xff);
        } else if (len <= 0xffffff) {
            out.write(0x83);
            out.write((len >>> 16) & 0xff);
            out.write((len >>> 8) & 0xff);
            out.write(len & 0xff);
        } else {
            out.write(0x84);
            out.write((len >>> 24) & 0xff);
            out.write((len >>> 16) & 0xff);
            out.write((len >>> 8) & 0xff);
            out.write(len & 0xff);
        }
    }

    private static void writeDerInteger(ByteArrayOutputStream out, BigInteger bi) {
        byte[] b = bi.toByteArray();
        out.write(0x02);
        writeDerLength(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeDerOctetString(ByteArrayOutputStream out, byte[] b) {
        out.write(0x04);
        writeDerLength(out, b.length);
        out.write(b, 0, b.length);
    }

    private static String cleanKey(String key) {
        return key
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN EC PUBLIC KEY-----", "")
                .replace("-----END EC PUBLIC KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN EC PRIVATE KEY-----", "")
                .replace("-----END EC PRIVATE KEY-----", "")
                .replace("-----BEGIN SM2 PRIVATE KEY-----", "")
                .replace("-----END SM2 PRIVATE KEY-----", "")
                .replace("-----BEGIN SM2 PUBLIC KEY-----", "")
                .replace("-----END SM2 PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
    }

    private static String formatAsPem(String type, String base64) {
        StringBuilder sb = new StringBuilder(base64.length() + 64);
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            int end = Math.min(i + 64, base64.length());
            sb.append(base64, i, end).append('\n');
        }
        sb.append("-----END ").append(type).append("-----");
        return sb.toString();
    }

    private static boolean isHex(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void clearBuffers(byte[]... buffers) {
        if (buffers == null) {
            return;
        }
        for (byte[] buf : buffers) {
            if (buf != null) {
                Arrays.fill(buf, (byte) 0);
            }
        }
    }

    // ==================== 雅可比投影坐标系点乘实现 ====================

    private static JacobianPoint multiplyJacobian(Sm2Point p, BigInteger k) {
        if (p == null || p.isInfinity() || k == null || k.signum() == 0) {
            return JacobianPoint.INFINITY;
        }
        BigInteger scalar = k.mod(N);
        if (scalar.signum() == 0) {
            return JacobianPoint.INFINITY;
        }
        JacobianPoint result = JacobianPoint.INFINITY;
        int bitLength = scalar.bitLength();
        for (int i = bitLength - 1; i >= 0; i--) {
            result = result.doublePoint();
            if (scalar.testBit(i)) {
                result = result.addAffine(p.x(), p.y());
            }
        }
        return result;
    }

    private static final class JacobianPoint {

        private static final JacobianPoint INFINITY = new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);

        private final BigInteger x;
        private final BigInteger y;
        private final BigInteger z;

        private JacobianPoint(BigInteger x, BigInteger y, BigInteger z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private boolean isInfinity() {
            return z == null || z.signum() == 0;
        }

        private Sm2Point toAffine() {
            if (isInfinity()) {
                return Sm2Point.INFINITY;
            }
            BigInteger zInv = z.modInverse(P);
            BigInteger zInv2 = zInv.multiply(zInv).mod(P);
            BigInteger zInv3 = zInv2.multiply(zInv).mod(P);
            return new Sm2Point(x.multiply(zInv2).mod(P), y.multiply(zInv3).mod(P));
        }

        private JacobianPoint doublePoint() {
            if (isInfinity() || y.signum() == 0) {
                return INFINITY;
            }
            BigInteger a1 = x.multiply(x).mod(P);
            BigInteger b1 = y.multiply(y).mod(P);
            BigInteger c1 = b1.multiply(b1).mod(P);
            BigInteger d1 = x.add(b1);
            d1 = d1.multiply(d1).subtract(a1).subtract(c1).shiftLeft(1).mod(P);

            BigInteger z2 = z.multiply(z).mod(P);
            BigInteger z4 = z2.multiply(z2).mod(P);
            BigInteger e1 = a1.multiply(THREE).add(A.multiply(z4)).mod(P);

            BigInteger x3 = e1.multiply(e1).subtract(d1.shiftLeft(1)).mod(P);
            BigInteger y3 = e1.multiply(d1.subtract(x3)).subtract(c1.shiftLeft(3)).mod(P);
            BigInteger z3 = y.multiply(z).shiftLeft(1).mod(P);
            return new JacobianPoint(x3, y3, z3);
        }

        private JacobianPoint add(JacobianPoint p2) {
            if (isInfinity()) {
                return p2;
            }
            if (p2.isInfinity()) {
                return this;
            }

            BigInteger z1Sq = z.multiply(z).mod(P);
            BigInteger z2Sq = p2.z.multiply(p2.z).mod(P);
            BigInteger u1 = x.multiply(z2Sq).mod(P);
            BigInteger u2 = p2.x.multiply(z1Sq).mod(P);
            BigInteger s1 = y.multiply(z2Sq).multiply(p2.z).mod(P);
            BigInteger s2 = p2.y.multiply(z1Sq).multiply(z).mod(P);

            BigInteger h = u2.subtract(u1).mod(P);
            BigInteger r = s2.subtract(s1).mod(P);

            if (h.signum() == 0) {
                return (r.signum() == 0) ? doublePoint() : INFINITY;
            }

            BigInteger h2 = h.multiply(h).mod(P);
            BigInteger h3 = h.multiply(h2).mod(P);
            BigInteger v = u1.multiply(h2).mod(P);

            BigInteger x3 = r.multiply(r).subtract(h3).subtract(v.shiftLeft(1)).mod(P);
            BigInteger y3 = r.multiply(v.subtract(x3)).subtract(s1.multiply(h3)).mod(P);
            BigInteger z3 = z.multiply(p2.z).multiply(h).mod(P);
            return new JacobianPoint(x3, y3, z3);
        }

        private JacobianPoint addAffine(BigInteger x2, BigInteger y2) {
            if (isInfinity()) {
                return new JacobianPoint(x2, y2, BigInteger.ONE);
            }
            if (x2 == null || y2 == null) {
                return this;
            }

            BigInteger z1Sq = z.multiply(z).mod(P);
            BigInteger u2 = x2.multiply(z1Sq).mod(P);
            BigInteger s2 = y2.multiply(z1Sq).multiply(z).mod(P);

            BigInteger h = u2.subtract(x).mod(P);
            BigInteger r = s2.subtract(y).mod(P);

            if (h.signum() == 0) {
                return (r.signum() == 0) ? doublePoint() : INFINITY;
            }

            BigInteger h2 = h.multiply(h).mod(P);
            BigInteger h3 = h.multiply(h2).mod(P);
            BigInteger v = x.multiply(h2).mod(P);

            BigInteger x3 = r.multiply(r).subtract(h3).subtract(v.shiftLeft(1)).mod(P);
            BigInteger y3 = r.multiply(v.subtract(x3)).subtract(y.multiply(h3)).mod(P);
            BigInteger z3 = z.multiply(h).mod(P);
            return new JacobianPoint(x3, y3, z3);
        }
    }
}
