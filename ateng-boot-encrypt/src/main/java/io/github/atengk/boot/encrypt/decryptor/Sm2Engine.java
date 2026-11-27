package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.boot.encrypt.parser.MultilineSecretFormatter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 纯 JDK 原生国密 SM2 椭圆曲线密码引擎 (GB/T 32918-2016)
 * 曲线参数、雅可比坐标运算、密钥解析与 C1C3C2/DER 加解密全面委托至 Core {@link Sm2Util}，
 * 修正曲线阶常量 {@code N} 为标准 256 位参数，对外保留全部既有公共 API 与载体契约，零第三方依赖
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class Sm2Engine {

    /**
     * SM2 推荐 256 位椭圆曲线参数 (GB/T 32918.5-2017)
     */
    public static final BigInteger P = Sm2Util.P;
    public static final BigInteger A = Sm2Util.A;
    public static final BigInteger B = Sm2Util.B;
    public static final BigInteger N = Sm2Util.N;
    public static final BigInteger GX = Sm2Util.GX;
    public static final BigInteger GY = Sm2Util.GY;

    public static final Point G = new Point(GX, GY);
    public static final Point INFINITY = new Point();

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

        private Sm2Util.Sm2Point toSm2Point() {
            return infinity ? Sm2Util.Sm2Point.INFINITY : new Sm2Util.Sm2Point(x, y);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Point other)) return false;
            return infinity == other.infinity
                    && Objects.equals(x, other.x)
                    && Objects.equals(y, other.y);
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, infinity);
        }
    }

    private static Point fromSm2Point(Sm2Util.Sm2Point point) {
        if (point == null || point.isInfinity()) {
            return INFINITY;
        }
        return new Point(point.x(), point.y());
    }

    /**
     * 椭圆曲线点加法运算 (P1 + P2)
     */
    public static Point add(Point p1, Point p2) {
        if (p1 == null || p1.infinity) return p2;
        if (p2 == null || p2.infinity) return p1;
        return fromSm2Point(Sm2Util.add(p1.toSm2Point(), p2.toSm2Point()));
    }

    /**
     * 椭圆曲线标量乘法 (k * P)
     */
    public static Point multiply(BigInteger k, Point p) {
        if (k == null || k.signum() == 0 || p == null || p.infinity) {
            return INFINITY;
        }
        return fromSm2Point(Sm2Util.multiply(k, p.toSm2Point()));
    }

    /**
     * 校验点是否在 SM2 推荐曲线上 (y^2 = x^3 + Ax + B mod P)
     */
    public static boolean isOnCurve(Point point) {
        if (point == null || point.infinity) {
            return false;
        }
        return Sm2Util.isOnCurve(point.toSm2Point());
    }

    /**
     * SM2 密钥派生函数 KDF (基于 SM3 杂凑算法)
     */
    public static byte[] kdf(byte[] z, int klen) {
        return Sm2Util.kdf(z, klen);
    }

    /**
     * 执行 SM2 公钥加密 (GB/T 32918.4-2016，返回标准 C1C3C2 字节流)
     *
     * @param msg    明文字节流
     * @param pubKey 接收方公钥点
     * @return 65 字节 C1 + 32 字节 C3 + C2 的密文字节数组
     */
    public static byte[] encrypt(byte[] msg, Point pubKey) {
        if (pubKey == null || pubKey.infinity) {
            throw new IllegalArgumentException("SM2 加密公钥点不合法");
        }
        return Sm2Util.encrypt(msg, pubKey.toSm2Point());
    }

    /**
     * 执行 SM2 私钥解密 (自动智能识别 C1C3C2 与 ASN.1 DER 两种权威编码格式)
     *
     * @param cipher  密文字节流 (支持标准 C1C3C2 或 ASN.1 DER 编码)
     * @param privKey 私钥大整数
     * @return 还原后的明文字节流
     */
    public static byte[] decrypt(byte[] cipher, BigInteger privKey) {
        return Sm2Util.decrypt(cipher, privKey);
    }

    /**
     * 将 ASN.1 DER 编码的 SM2 密文结构化转译为国家权威标准 C1C3C2 原始拼接字节流
     *
     * @param der ASN.1 DER 密文字节流 (必须以 0x30 SEQUENCE 起始)
     * @return 标准 C1C3C2 密文字节流 (0x04 || X1 || Y1 || C3 || C2)
     */
    public static byte[] derToC1c3c2(byte[] der) {
        return Sm2Util.derToC1c3c2(der);
    }

    /**
     * 将标准 C1C3C2 密文封装为标准 ASN.1 DER 编码
     *
     * @param c1c3c2 标准 C1C3C2 密文字节流 (支持 0x04 开头的 65 字节 C1 或未带 0x04 的 64 字节 C1)
     * @return ASN.1 DER 编码的密文字节流 (以 0x30 起始)
     */
    public static byte[] c1c3c2ToDer(byte[] c1c3c2) {
        return Sm2Util.c1c3c2ToDer(c1c3c2);
    }

    /**
     * 将 C1C3C2 密文封装为标准 ASN.1 DER 编码 (保持向后二进制与源码兼容别名)
     *
     * @param c1c3c2 标准 C1C3C2 密文字节流
     * @return ASN.1 DER 编码的密文字节流
     */
    public static byte[] toAsn1Der(byte[] c1c3c2) {
        return Sm2Util.c1c3c2ToDer(c1c3c2);
    }

    /**
     * 生成 SM2 密钥对
     */
    public static Sm2KeyPair generateKeyPair() {
        Sm2Util.Sm2KeyPair keyPair = Sm2Util.generateKeyPair();
        return new Sm2KeyPair(keyPair.privateKey(), fromSm2Point(keyPair.publicKey()));
    }

    /**
     * 解析各种格式的 SM2 私钥（Hex、Base64、PKCS#8 PEM）并校验有效域 {@code [1, N-2]}
     */
    public static BigInteger parsePrivateKey(String privKeyStr) {
        if (privKeyStr == null || privKeyStr.trim().isEmpty()) {
            throw new IllegalArgumentException("SM2 私钥不能为空");
        }
        String normalized = MultilineSecretFormatter.isPem(privKeyStr)
                ? privKeyStr
                : MultilineSecretFormatter.cleanCiphertext(privKeyStr);
        return Sm2Util.parsePrivateKey(normalized);
    }

    /**
     * 解析各种格式的 SM2 公钥（Hex、Base64、X.509 PEM）
     */
    public static Point parsePublicKey(String pubKeyStr) {
        if (pubKeyStr == null || pubKeyStr.trim().isEmpty()) {
            throw new IllegalArgumentException("SM2 公钥不能为空");
        }
        String normalized = MultilineSecretFormatter.isPem(pubKeyStr)
                ? pubKeyStr
                : MultilineSecretFormatter.cleanCiphertext(pubKeyStr);
        return fromSm2Point(Sm2Util.parsePublicKey(normalized));
    }

    public static byte[] toFixedBytes(BigInteger bi, int len) {
        return Sm2Util.toFixedBytes(bi, len);
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
            byte[] dBytes = toFixedBytes(privateKey, 32);
            try {
                return HexFormat.of().formatHex(dBytes);
            } finally {
                Arrays.fill(dBytes, (byte) 0);
            }
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
