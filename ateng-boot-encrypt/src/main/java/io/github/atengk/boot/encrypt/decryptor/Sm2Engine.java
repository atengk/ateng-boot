package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.parser.MultilineSecretFormatter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 纯 JDK 原生国密 SM2 椭圆曲线密码引擎 (GB/T 32918-2016)
 * 100% 依托原生 BigInteger 与纯 Java 数学逻辑实现，零 Bouncy Castle 外部依赖
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class Sm2Engine {

    // SM2 推荐 256 位椭圆曲线参数 (GB/T 32918.4-2016)
    public static final BigInteger P = new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF", 16);
    public static final BigInteger A = new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC", 16);
    public static final BigInteger B = new BigInteger("28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93", 16);
    public static final BigInteger N = new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123", 16);
    public static final BigInteger GX = new BigInteger("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7", 16);
    public static final BigInteger GY = new BigInteger("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0", 16);

    public static final Point G = new Point(GX, GY);
    public static final Point INFINITY = new Point();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Sm2Engine() {
    }

    /**
     * SM2 椭圆曲线仿射坐标点
     */
    @Getter
    public static class Point {
        private final BigInteger x;
        private final BigInteger y;
        private final boolean infinity;

        public Point(BigInteger x, BigInteger y) {
            this.x = x != null ? x.mod(P) : BigInteger.ZERO;
            this.y = y != null ? y.mod(P) : BigInteger.ZERO;
            this.infinity = false;
        }

        private Point() {
            this.x = BigInteger.ZERO;
            this.y = BigInteger.ZERO;
            this.infinity = true;
        }

        public byte[] toUncompressedBytes() {
            if (infinity) {
                return new byte[]{0x00};
            }
            byte[] out = new byte[65];
            out[0] = 0x04;
            System.arraycopy(toFixedBytes(x, 32), 0, out, 1, 32);
            System.arraycopy(toFixedBytes(y, 32), 0, out, 33, 32);
            return out;
        }

        public String toHex() {
            return HexFormat.of().formatHex(toUncompressedBytes());
        }
    }

    /**
     * 椭圆曲线点加法运算 (P1 + P2)
     */
    public static Point add(Point p1, Point p2) {
        if (p1 == null || p1.infinity) return p2;
        if (p2 == null || p2.infinity) return p1;

        if (p1.x.equals(p2.x)) {
            if (p1.y.equals(p2.y) && !p1.y.equals(BigInteger.ZERO)) {
                // 倍点运算：lambda = (3*x1^2 + A) / (2*y1) mod P
                BigInteger num = p1.x.multiply(p1.x).multiply(BigInteger.valueOf(3)).add(A).mod(P);
                BigInteger den = p1.y.shiftLeft(1).modInverse(P);
                BigInteger lambda = num.multiply(den).mod(P);
                BigInteger x3 = lambda.multiply(lambda).subtract(p1.x.shiftLeft(1)).mod(P);
                BigInteger y3 = lambda.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P);
                return new Point(x3, y3);
            }
            return INFINITY;
        }

        // 两不同点加法：lambda = (y2 - y1) / (x2 - x1) mod P
        BigInteger num = p2.y.subtract(p1.y).mod(P);
        BigInteger den = p2.x.subtract(p1.x).mod(P).modInverse(P);
        BigInteger lambda = num.multiply(den).mod(P);
        BigInteger x3 = lambda.multiply(lambda).subtract(p1.x).subtract(p2.x).mod(P);
        BigInteger y3 = lambda.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P);
        return new Point(x3, y3);
    }

    /**
     * 椭圆曲线标量乘法 (k * P)
     */
    public static Point multiply(BigInteger k, Point p) {
        if (k == null || k.signum() == 0 || p == null || p.infinity) {
            return INFINITY;
        }
        BigInteger scalar = k.mod(N);
        Point result = INFINITY;
        Point current = p;
        for (int i = 0; i < scalar.bitLength(); i++) {
            if (scalar.testBit(i)) {
                result = add(result, current);
            }
            current = add(current, current);
        }
        return result;
    }

    /**
     * 校验点是否在 SM2 推荐曲线上 (y^2 = x^3 + Ax + B mod P)
     */
    public static boolean isOnCurve(Point point) {
        if (point == null || point.infinity) {
            return false;
        }
        BigInteger lhs = point.y.multiply(point.y).mod(P);
        BigInteger rhs = point.x.pow(3).add(A.multiply(point.x)).add(B).mod(P);
        return lhs.equals(rhs);
    }

    /**
     * SM2 密钥派生函数 KDF (基于 SM3 杂凑算法)
     */
    public static byte[] kdf(byte[] z, int klen) {
        byte[] key = new byte[klen];
        int ct = 1;
        int offset = 0;
        byte[] buf = new byte[z.length + 4];
        System.arraycopy(z, 0, buf, 0, z.length);

        try {
            while (offset < klen) {
                buf[z.length] = (byte) (ct >>> 24);
                buf[z.length + 1] = (byte) (ct >>> 16);
                buf[z.length + 2] = (byte) (ct >>> 8);
                buf[z.length + 3] = (byte) ct;
                byte[] h = Sm3Digest.digest(buf);
                int copyLen = Math.min(32, klen - offset);
                System.arraycopy(h, 0, key, offset, copyLen);
                Arrays.fill(h, (byte) 0);
                offset += copyLen;
                ct++;
            }
            return key;
        } finally {
            Arrays.fill(buf, (byte) 0);
        }
    }

    /**
     * 执行 SM2 公钥加密 (GB/T 32918.4-2016，返回标准 C1C3C2 字节流)
     *
     * @param msg    明文字节流
     * @param pubKey 接收方公钥点
     * @return 65 字节 C1 + 32 字节 C3 + C2 的密文字节数组
     */
    public static byte[] encrypt(byte[] msg, Point pubKey) {
        if (msg == null || pubKey == null || pubKey.infinity) {
            throw new IllegalArgumentException("SM2 加密明文或公钥非法");
        }
        int klen = msg.length;
        byte[] x2 = null;
        byte[] y2 = null;
        byte[] z = null;
        byte[] t = null;

        try {
            while (true) {
                // 1. 产生随机数 k in [1, N-1]
                BigInteger k = new BigInteger(256, RANDOM).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE);

                // 2. 计算 C1 = [k]G = (x1, y1)
                Point c1Point = multiply(k, G);

                // 3. 计算 [k]P = (x2, y2)
                Point sPoint = multiply(k, pubKey);
                if (sPoint.infinity) {
                    continue;
                }

                x2 = toFixedBytes(sPoint.x, 32);
                y2 = toFixedBytes(sPoint.y, 32);
                z = new byte[64];
                System.arraycopy(x2, 0, z, 0, 32);
                System.arraycopy(y2, 0, z, 32, 32);

                // 4. 计算 t = KDF(x2 || y2, klen)
                t = kdf(z, klen);
                boolean allZero = true;
                for (byte b : t) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) {
                    continue;
                }

                // 5. C2 = M ^ t
                byte[] c2 = new byte[klen];
                for (int i = 0; i < klen; i++) {
                    c2[i] = (byte) (msg[i] ^ t[i]);
                }

                // 6. C3 = SM3(x2 || M || y2)
                byte[] c3 = Sm3Digest.digest(x2, msg, y2);

                // 7. 组装 C1 (04 || x1 || y1: 65 字节) || C3 (32 字节) || C2
                byte[] x1 = toFixedBytes(c1Point.x, 32);
                byte[] y1 = toFixedBytes(c1Point.y, 32);
                byte[] result = new byte[65 + 32 + klen];
                result[0] = 0x04;
                System.arraycopy(x1, 0, result, 1, 32);
                System.arraycopy(y1, 0, result, 33, 32);
                System.arraycopy(c3, 0, result, 65, 32);
                System.arraycopy(c2, 0, result, 97, klen);

                return result;
            }
        } finally {
            if (x2 != null) Arrays.fill(x2, (byte) 0);
            if (y2 != null) Arrays.fill(y2, (byte) 0);
            if (z != null) Arrays.fill(z, (byte) 0);
            if (t != null) Arrays.fill(t, (byte) 0);
        }
    }

    /**
     * 执行 SM2 私钥解密 (自动兼容标准 C1C3C2 与 ASN.1 DER 编码密文)
     *
     * @param ciphertext 密文字节流
     * @param privKey    解密私钥标量 d
     * @return 还原后的明文字节流
     */
    public static byte[] decrypt(byte[] ciphertext, BigInteger privKey) {
        if (ciphertext == null || privKey == null || privKey.signum() <= 0) {
            throw new IllegalArgumentException("SM2 解密入参密文或私钥非法");
        }

        // 兼容 ASN.1 DER 编码密文 (以 0x30 开头)
        if (ciphertext.length > 0 && ciphertext[0] == 0x30) {
            return decryptAsn1Der(ciphertext, privKey);
        }

        return decryptC1C3C2(ciphertext, privKey);
    }

    private static byte[] decryptC1C3C2(byte[] ciphertext, BigInteger privKey) {
        int offset = 0;
        int c1Len = 64;
        if (ciphertext[0] == 0x04) {
            offset = 1;
            c1Len = 65;
        }
        if (ciphertext.length < c1Len + 32) {
            throw new IllegalArgumentException("SM2 密文长度非法，不足以包含 C1 与 C3");
        }

        byte[] x1 = new byte[32];
        byte[] y1 = new byte[32];
        System.arraycopy(ciphertext, offset, x1, 0, 32);
        System.arraycopy(ciphertext, offset + 32, y1, 0, 32);
        Point c1 = new Point(new BigInteger(1, x1), new BigInteger(1, y1));

        if (!isOnCurve(c1)) {
            throw new IllegalArgumentException("SM2 密文 C1 曲线点不合法 (NotInCurve)");
        }

        Point s = multiply(privKey, c1);
        if (s.infinity) {
            throw new IllegalArgumentException("SM2 解密点落在无穷远点");
        }

        byte[] x2 = toFixedBytes(s.x, 32);
        byte[] y2 = toFixedBytes(s.y, 32);
        byte[] z = new byte[64];
        System.arraycopy(x2, 0, z, 0, 32);
        System.arraycopy(y2, 0, z, 32, 32);

        byte[] c3 = new byte[32];
        System.arraycopy(ciphertext, c1Len, c3, 0, 32);

        int c2Len = ciphertext.length - c1Len - 32;
        byte[] c2 = new byte[c2Len];
        System.arraycopy(ciphertext, c1Len + 32, c2, 0, c2Len);

        byte[] t = kdf(z, c2Len);
        byte[] m = new byte[c2Len];
        for (int i = 0; i < c2Len; i++) {
            m[i] = (byte) (c2[i] ^ t[i]);
        }

        byte[] expectedC3 = Sm3Digest.digest(x2, m, y2);
        try {
            if (!Arrays.equals(c3, expectedC3)) {
                throw new IllegalStateException("SM2 消息摘要 MAC (C3) 校验失败，密文已被篡改或私钥不匹配");
            }
            byte[] result = new byte[m.length];
            System.arraycopy(m, 0, result, 0, m.length);
            return result;
        } finally {
            Arrays.fill(x2, (byte) 0);
            Arrays.fill(y2, (byte) 0);
            Arrays.fill(z, (byte) 0);
            Arrays.fill(t, (byte) 0);
            Arrays.fill(m, (byte) 0);
            Arrays.fill(expectedC3, (byte) 0);
        }
    }

    private static byte[] decryptAsn1Der(byte[] derBytes, BigInteger privKey) {
        try {
            // 解析 SEQUENCE { x INTEGER, y INTEGER, hash OCTET STRING, cipher OCTET STRING }
            int idx = 1;
            int seqLen = readDerLength(derBytes, idx);
            idx += getLengthBytesCount(derBytes, idx);

            // x1
            if (derBytes[idx++] != 0x02) throw new IllegalArgumentException("ASN.1 解析错误: 缺少 x1 INTEGER");
            int xLen = readDerLength(derBytes, idx);
            idx += getLengthBytesCount(derBytes, idx);
            byte[] x1 = new byte[xLen];
            System.arraycopy(derBytes, idx, x1, 0, xLen);
            idx += xLen;

            // y1
            if (derBytes[idx++] != 0x02) throw new IllegalArgumentException("ASN.1 解析错误: 缺少 y1 INTEGER");
            int yLen = readDerLength(derBytes, idx);
            idx += getLengthBytesCount(derBytes, idx);
            byte[] y1 = new byte[yLen];
            System.arraycopy(derBytes, idx, y1, 0, yLen);
            idx += yLen;

            // c3
            if (derBytes[idx++] != 0x04) throw new IllegalArgumentException("ASN.1 解析错误: 缺少 c3 OCTET STRING");
            int c3Len = readDerLength(derBytes, idx);
            idx += getLengthBytesCount(derBytes, idx);
            byte[] c3 = new byte[c3Len];
            System.arraycopy(derBytes, idx, c3, 0, c3Len);
            idx += c3Len;

            // c2
            if (derBytes[idx++] != 0x04) throw new IllegalArgumentException("ASN.1 解析错误: 缺少 c2 OCTET STRING");
            int c2Len = readDerLength(derBytes, idx);
            idx += getLengthBytesCount(derBytes, idx);
            byte[] c2 = new byte[c2Len];
            System.arraycopy(derBytes, idx, c2, 0, c2Len);

            // 拼装标准 C1C3C2
            Point c1 = new Point(new BigInteger(1, x1), new BigInteger(1, y1));
            byte[] assembled = new byte[65 + 32 + c2.length];
            assembled[0] = 0x04;
            System.arraycopy(toFixedBytes(c1.x, 32), 0, assembled, 1, 32);
            System.arraycopy(toFixedBytes(c1.y, 32), 0, assembled, 33, 32);
            System.arraycopy(c3, 0, assembled, 65, 32);
            System.arraycopy(c2, 0, assembled, 97, c2.length);

            return decryptC1C3C2(assembled, privKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 密文解析失败: " + e.getMessage(), e);
        }
    }

    private static int readDerLength(byte[] bytes, int offset) {
        int b = bytes[offset] & 0xff;
        if ((b & 0x80) == 0) {
            return b;
        }
        int numBytes = b & 0x7f;
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | (bytes[offset + 1 + i] & 0xff);
        }
        return length;
    }

    private static int getLengthBytesCount(byte[] bytes, int offset) {
        int b = bytes[offset] & 0xff;
        if ((b & 0x80) == 0) {
            return 1;
        }
        return 1 + (b & 0x7f);
    }

    /**
     * 将 C1C3C2 密文封装为标准 ASN.1 DER 编码
     */
    public static byte[] toAsn1Der(byte[] c1c3c2) {
        int offset = (c1c3c2[0] == 0x04) ? 1 : 0;
        byte[] x1 = new byte[32];
        byte[] y1 = new byte[32];
        System.arraycopy(c1c3c2, offset, x1, 0, 32);
        System.arraycopy(c1c3c2, offset + 32, y1, 0, 32);

        int c1Len = (offset == 1) ? 65 : 64;
        byte[] c3 = new byte[32];
        System.arraycopy(c1c3c2, c1Len, c3, 0, 32);

        int c2Len = c1c3c2.length - c1Len - 32;
        byte[] c2 = new byte[c2Len];
        System.arraycopy(c1c3c2, c1Len + 32, c2, 0, c2Len);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeDerInteger(baos, new BigInteger(1, x1));
        writeDerInteger(baos, new BigInteger(1, y1));
        writeDerOctetString(baos, c3);
        writeDerOctetString(baos, c2);

        byte[] content = baos.toByteArray();
        ByteArrayOutputStream seq = new ByteArrayOutputStream();
        seq.write(0x30);
        writeDerLength(seq, content.length);
        seq.write(content, 0, content.length);
        return seq.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int len) {
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write(len >>> 8);
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

    /**
     * 生成 SM2 密钥对
     */
    public static Sm2KeyPair generateKeyPair() {
        BigInteger d = new BigInteger(256, RANDOM).mod(N.subtract(BigInteger.TWO)).add(BigInteger.ONE);
        Point q = multiply(d, G);
        return new Sm2KeyPair(d, q);
    }

    /**
     * 解析各种格式的 SM2 私钥（Hex、Base64、PKCS#8 PEM）
     */
    public static BigInteger parsePrivateKey(String privKeyStr) {
        if (privKeyStr == null || privKeyStr.trim().isEmpty()) {
            throw new IllegalArgumentException("SM2 私钥不能为空");
        }
        String clean = privKeyStr
                .replace("-----BEGIN EC PRIVATE KEY-----", "")
                .replace("-----END EC PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "");
        clean = MultilineSecretFormatter.cleanCiphertext(clean);

        // 1. 若为 64 位纯 16 进制字符串
        if (clean.length() == 64 && clean.matches("^[0-9a-fA-F]{64}$")) {
            return new BigInteger(clean, 16);
        }

        // 2. 若为 Base64 编码 (32 字节或 PKCS#8 DER)
        byte[] bytes = Base64.getDecoder().decode(clean);
        try {
            if (bytes.length == 32) {
                return new BigInteger(1, bytes);
            }
            // 兼容 PKCS#8 DER 结构提取 OCTET STRING (32 字节私钥)
            for (int i = 0; i < bytes.length - 34; i++) {
                if (bytes[i] == 0x04 && (bytes[i + 1] & 0xff) == 32) {
                    byte[] dBytes = new byte[32];
                    try {
                        System.arraycopy(bytes, i + 2, dBytes, 0, 32);
                        return new BigInteger(1, dBytes);
                    } finally {
                        Arrays.fill(dBytes, (byte) 0);
                    }
                }
            }
            // 保底提取末尾 32 字节
            if (bytes.length > 32) {
                byte[] dBytes = new byte[32];
                try {
                    System.arraycopy(bytes, bytes.length - 32, dBytes, 0, 32);
                    return new BigInteger(1, dBytes);
                } finally {
                    Arrays.fill(dBytes, (byte) 0);
                }
            }
            return new BigInteger(1, bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * 解析各种格式的 SM2 公钥（Hex、Base64、X.509 PEM）
     */
    public static Point parsePublicKey(String pubKeyStr) {
        if (pubKeyStr == null || pubKeyStr.trim().isEmpty()) {
            throw new IllegalArgumentException("SM2 公钥不能为空");
        }
        String clean = pubKeyStr
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "");
        clean = MultilineSecretFormatter.cleanCiphertext(clean);

        // 1. 16 进制字符串
        if (clean.matches("^[0-9a-fA-F]+$")) {
            if (clean.length() == 130 && clean.startsWith("04")) {
                clean = clean.substring(2);
            }
            if (clean.length() == 128) {
                BigInteger x = new BigInteger(clean.substring(0, 64), 16);
                BigInteger y = new BigInteger(clean.substring(64, 128), 16);
                return new Point(x, y);
            }
        }

        // 2. Base64 字符串
        byte[] bytes = Base64.getDecoder().decode(clean);
        int offset = 0;
        if (bytes.length == 65 && bytes[0] == 0x04) {
            offset = 1;
        } else if (bytes.length > 65) {
            // 兼容 X.509 DER 结构定位 0x04 开头的 65 字节非压缩公钥
            for (int i = 0; i <= bytes.length - 65; i++) {
                if (bytes[i] == 0x04) {
                    offset = i + 1;
                    break;
                }
            }
        }

        byte[] xBytes = new byte[32];
        byte[] yBytes = new byte[32];
        System.arraycopy(bytes, offset, xBytes, 0, 32);
        System.arraycopy(bytes, offset + 32, yBytes, 0, 32);
        return new Point(new BigInteger(1, xBytes), new BigInteger(1, yBytes));
    }

    public static byte[] toFixedBytes(BigInteger bi, int len) {
        byte[] raw = bi.toByteArray();
        if (raw.length == len) return raw;
        byte[] res = new byte[len];
        if (raw.length > len) {
            System.arraycopy(raw, raw.length - len, res, 0, len);
        } else {
            System.arraycopy(raw, 0, res, len - raw.length, raw.length);
        }
        return res;
    }

    /**
     * SM2 密钥对载体
     */
    @Getter
    @RequiredArgsConstructor
    public static class Sm2KeyPair {
        private final BigInteger privateKey;
        private final Point publicKey;

        public String getPrivateKeyHex() {
            return HexFormat.of().formatHex(toFixedBytes(privateKey, 32));
        }

        public String getPublicKeyHex() {
            return publicKey.toHex();
        }

        public String privateKeyHex() {
            return getPrivateKeyHex();
        }

        public String publicKeyHex() {
            return getPublicKeyHex();
        }
    }
}
