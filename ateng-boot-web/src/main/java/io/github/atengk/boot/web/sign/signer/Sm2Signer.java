package io.github.atengk.boot.web.sign.signer;

import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 基于纯 JDK 17 原生数学库的国密 SM2 (GB/T 32918.2-2016) / SM3 非对称数字签名器
 * 底层曲线计算、密钥解析与 SM2/SM3 签验原语统一委托至 {@link Sm2Util}，对外保持完整既有公共 API 兼容，零 Bouncy Castle 外部依赖
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class Sm2Signer implements ApiSigner {

    /**
     * SM2 推荐椭圆曲线参数 (GB/T 32918.5-2017)
     */
    public static final BigInteger P = Sm2Util.P;
    public static final BigInteger A = Sm2Util.A;
    public static final BigInteger B = Sm2Util.B;
    public static final BigInteger N = Sm2Util.N;
    public static final BigInteger GX = Sm2Util.GX;
    public static final BigInteger GY = Sm2Util.GY;

    /**
     * 基点 G (仿射坐标)
     */
    public static final ECPoint G = new ECPoint(GX, GY);

    /**
     * GM/T 0009-2012 / RFC 8998 标准默认用户标识 (16 字节 ASCII "1234567812345678")
     */
    public static final byte[] DEFAULT_USER_ID = Sm2Util.defaultUserId();

    private final byte[] userId;

    /**
     * 默认构造函数，使用标准用户标识 "1234567812345678"
     */
    public Sm2Signer() {
        this(Sm2Util.defaultUserId());
    }

    /**
     * 自定义用户标识构造函数
     *
     * @param userId 签名者用户标识字节数组
     */
    public Sm2Signer(byte[] userId) {
        this.userId = (userId != null && userId.length > 0) ? userId.clone() : Sm2Util.defaultUserId();
    }

    /**
     * 自定义用户标识构造函数
     *
     * @param userId 签名者用户标识字符串
     */
    public Sm2Signer(String userId) {
        this(StringUtils.hasText(userId) ? userId.getBytes(StandardCharsets.UTF_8) : Sm2Util.defaultUserId());
    }

    @Override
    public String sign(String data, String privateKeyPem) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(privateKeyPem)) {
            throw new IllegalArgumentException("待签名数据与 SM2 私钥不能为空");
        }
        try {
            return Sm2Util.signBase64(data, privateKeyPem, this.userId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SM2 数据加签失败", e);
        }
    }

    @Override
    public boolean verify(String data, String signature, String publicKeyPem) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signature) || !StringUtils.hasText(publicKeyPem)) {
            return false;
        }
        return Sm2Util.verifyBase64(data, signature, publicKeyPem, this.userId);
    }

    /**
     * 生成全新的随机国密 SM2 密钥对
     *
     * @return 包含 Hex 与 Base64 格式的 Sm2KeyPair
     */
    public static Sm2KeyPair generateKeyPair() {
        Sm2Util.Sm2KeyPair keyPair = Sm2Util.generateKeyPair();
        return new Sm2KeyPair(
                keyPair.privateKeyHex(),
                keyPair.publicKeyHex(),
                keyPair.privateKeyBase64(),
                keyPair.publicKeyBase64()
        );
    }

    /**
     * 国密 SM2 密钥对数据载体
     *
     * @param privateKeyHex    32 字节私钥 64 位十六进制字符串
     * @param publicKeyHex     65 字节非压缩公钥 (04 + X + Y) 130 位十六进制字符串
     * @param privateKeyBase64 32 字节私钥 Base64 编码字符串
     * @param publicKeyBase64  65 字节非压缩公钥 Base64 编码字符串
     */
    public record Sm2KeyPair(String privateKeyHex, String publicKeyHex, String privateKeyBase64, String publicKeyBase64) {}

    /**
     * 解析 SM2 私钥（兼容 Hex、Base64、PEM 与 PKCS#8 DER）
     *
     * @param key 私钥字符串
     * @return 私钥标量
     */
    public static BigInteger parsePrivateKey(String key) {
        return Sm2Util.parsePrivateKey(key);
    }

    /**
     * 解析 SM2 公钥（兼容 130 位/128 位 Hex、66 位压缩 Hex、Base64、PEM 与 X.509 DER）
     *
     * @param key 公钥字符串
     * @return 仿射坐标系公钥点
     */
    public static ECPoint parsePublicKey(String key) {
        Sm2Util.Sm2Point point = Sm2Util.parsePublicKey(key);
        return new ECPoint(point.x(), point.y());
    }

    /**
     * 将大整数转换导出为 32 字节无符号大端数组
     *
     * @param n 大整数
     * @return 32 字节定长数组
     */
    public static byte[] toBytes32(BigInteger n) {
        return Sm2Util.toBytes32(n);
    }

    /**
     * 校验仿射坐标点是否位于 SM2 推荐椭圆曲线上
     *
     * @param pt 待校验点
     * @return 在曲线上且非无穷远点返回 {@code true}
     */
    public static boolean isOnCurve(ECPoint pt) {
        if (pt == null || pt.isInfinity()) {
            return false;
        }
        return Sm2Util.isOnCurve(new Sm2Util.Sm2Point(pt.x, pt.y));
    }

    /**
     * 仿射坐标系椭圆曲线点
     */
    public static class ECPoint {
        public static final ECPoint INFINITY = new ECPoint(null, null);

        public final BigInteger x;
        public final BigInteger y;

        public ECPoint(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }

        public boolean isInfinity() {
            return this == INFINITY || x == null || y == null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ECPoint ecPoint)) return false;
            return Objects.equals(x, ecPoint.x) && Objects.equals(y, ecPoint.y);
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }

    /**
     * 对 SM2 椭圆曲线点执行标量乘法 {@code [k]P} 并返回仿射坐标点
     *
     * @param p 椭圆曲线点
     * @param k 标量
     * @return 仿射坐标点结果
     */
    public static ECPoint scalarMultiply(ECPoint p, BigInteger k) {
        if (p == null || p.isInfinity() || k == null) {
            return ECPoint.INFINITY;
        }
        Sm2Util.Sm2Point result = Sm2Util.multiply(k, new Sm2Util.Sm2Point(p.x, p.y));
        return result.isInfinity() ? ECPoint.INFINITY : new ECPoint(result.x(), result.y());
    }
}
