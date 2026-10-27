package io.github.atengk.boot.web.sign.signer;

import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 基于纯 JDK 17 原生数学库的国密 SM2 (GB/T 32918.2-2016) / SM3 非对称数字签名器
 * 严格分离公钥验签与私钥加签，零 Bouncy Castle 外部依赖，采用雅可比投影坐标系实现高性能点乘，
 * 智能兼容 Hex 十六进制、Base64 与 PEM 格式密钥，签名结果通过 X-Signature 以标准 Base64 (ASN.1 DER / Raw) 格式传输
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class Sm2Signer implements ApiSigner {

    /**
     * SM2 推荐椭圆曲线参数 (GB/T 32918.5-2017)
     */
    public static final BigInteger P = new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF", 16);
    public static final BigInteger A = new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC", 16);
    public static final BigInteger B = new BigInteger("28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93", 16);
    public static final BigInteger N = new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123", 16);
    public static final BigInteger GX = new BigInteger("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7", 16);
    public static final BigInteger GY = new BigInteger("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0", 16);

    /**
     * 基点 G (仿射坐标)
     */
    public static final ECPoint G = new ECPoint(GX, GY);

    /**
     * GM/T 0009-2012 / RFC 8998 标准默认用户标识 (16 字节 ASCII "1234567812345678")
     */
    public static final byte[] DEFAULT_USER_ID = "1234567812345678".getBytes(StandardCharsets.UTF_8);

    private final byte[] userId;

    /**
     * 默认构造函数，使用标准用户标识 "1234567812345678"
     */
    public Sm2Signer() {
        this(DEFAULT_USER_ID);
    }

    /**
     * 自定义用户标识构造函数
     *
     * @param userId 签名者用户标识字节数组
     */
    public Sm2Signer(byte[] userId) {
        this.userId = (userId != null && userId.length > 0) ? userId.clone() : DEFAULT_USER_ID;
    }

    /**
     * 自定义用户标识构造函数
     *
     * @param userId 签名者用户标识字符串
     */
    public Sm2Signer(String userId) {
        this(StringUtils.hasText(userId) ? userId.getBytes(StandardCharsets.UTF_8) : DEFAULT_USER_ID);
    }

    @Override
    public String sign(String data, String privateKeyPem) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(privateKeyPem)) {
            throw new IllegalArgumentException("待签名数据与 SM2 私钥不能为空");
        }
        try {
            BigInteger d = parsePrivateKey(privateKeyPem);
            // 校验私钥有效区间 [1, n-2]
            if (d.compareTo(BigInteger.ONE) < 0 || d.compareTo(N.subtract(BigInteger.TWO)) > 0) {
                throw new IllegalArgumentException("SM2 私钥标量超出有效定义域 [1, n-2]");
            }

            // 从私钥导出对应公钥点 PA = [d]G 用于计算 ZA 杂凑
            ECPoint pubKey = multiply(G, d).toAffine();
            byte[] za = computeZa(this.userId, pubKey);

            // e = SM3(ZA || M)
            byte[] msgBytes = data.getBytes(StandardCharsets.UTF_8);
            byte[] eBytes = DigestUtil.sm3(za, msgBytes);
            BigInteger e = new BigInteger(1, eBytes);

            SecureRandom random = new SecureRandom();
            BigInteger k;
            BigInteger r;
            BigInteger s = BigInteger.ZERO;

            do {
                do {
                    k = new BigInteger(256, random);
                } while (k.compareTo(BigInteger.ONE) < 0 || k.compareTo(N) >= 0);

                // [k]G = (x1, y1)
                ECPoint kG = multiply(G, k).toAffine();

                // r = (e + x1) mod n
                r = e.add(kG.x).mod(N);
                if (r.signum() == 0 || r.add(k).equals(N)) {
                    continue;
                }

                // s = ((1 + d)^-1 * (k - r * d)) mod n
                BigInteger dPlus1Inv = BigInteger.ONE.add(d).modInverse(N);
                s = dPlus1Inv.multiply(k.subtract(r.multiply(d))).mod(N);
            } while (s == null || s.signum() == 0);

            // 编码为标准 ASN.1 DER 序列并以 Base64 输出
            byte[] der = encodeDer(r, s);
            return Base64.getEncoder().encodeToString(der);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SM2 数据加签失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verify(String data, String signature, String publicKeyPem) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signature) || !StringUtils.hasText(publicKeyPem)) {
            return false;
        }
        try {
            ECPoint pubKey = parsePublicKey(publicKeyPem);
            if (pubKey.isInfinity() || !isOnCurve(pubKey)) {
                return false;
            }

            // 解码签名串为 (r, s)
            byte[] sigBytes = decodeSignatureBytes(signature);
            BigInteger[] rs = decodeDerOrRaw(sigBytes);
            BigInteger r = rs[0];
            BigInteger s = rs[1];

            // 1. 校验 r, s 是否处于 [1, n-1]
            if (r.compareTo(BigInteger.ONE) < 0 || r.compareTo(N) >= 0) {
                return false;
            }
            if (s.compareTo(BigInteger.ONE) < 0 || s.compareTo(N) >= 0) {
                return false;
            }

            // 2. e = SM3(ZA || M)
            byte[] za = computeZa(this.userId, pubKey);
            byte[] msgBytes = data.getBytes(StandardCharsets.UTF_8);
            byte[] eBytes = DigestUtil.sm3(za, msgBytes);
            BigInteger e = new BigInteger(1, eBytes);

            // 3. t = (r + s) mod n. 若 t == 0 则验签失败
            BigInteger t = r.add(s).mod(N);
            if (t.signum() == 0) {
                return false;
            }

            // 4. 计算点 (x1, y1) = [s]G + [t]PA (雅可比点加)
            JacobianPoint sG = multiply(G, s);
            JacobianPoint tP = multiply(pubKey, t);
            JacobianPoint ptJ = sG.add(tP);
            if (ptJ.isInfinity()) {
                return false;
            }
            ECPoint pt = ptJ.toAffine();

            // 5. R = (e + x1) mod n
            BigInteger computedR = e.add(pt.x).mod(N);
            return computedR.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成全新的随机国密 SM2 密钥对
     *
     * @return 包含 Hex 与 Base64 格式的 Sm2KeyPair
     */
    public static Sm2KeyPair generateKeyPair() {
        SecureRandom random = new SecureRandom();
        BigInteger d;
        do {
            d = new BigInteger(256, random);
        } while (d.compareTo(BigInteger.ONE) < 0 || d.compareTo(N.subtract(BigInteger.TWO)) > 0);

        ECPoint pubKey = multiply(G, d).toAffine();

        byte[] dBytes = toBytes32(d);
        byte[] pubBytes = new byte[65];
        pubBytes[0] = 0x04;
        System.arraycopy(toBytes32(pubKey.x), 0, pubBytes, 1, 32);
        System.arraycopy(toBytes32(pubKey.y), 0, pubBytes, 33, 32);

        String privateKeyHex = HexFormat.of().formatHex(dBytes);
        String publicKeyHex = HexFormat.of().formatHex(pubBytes);
        String privateKeyBase64 = Base64.getEncoder().encodeToString(dBytes);
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pubBytes);

        return new Sm2KeyPair(privateKeyHex, publicKeyHex, privateKeyBase64, publicKeyBase64);
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

    // ================================= 内部解析与椭圆曲线数学 =================================

    private static BigInteger parsePrivateKey(String key) {
        String clean = cleanKey(key);
        // 1. 64 位 Hex 私钥
        if (clean.length() == 64 && isHex(clean)) {
            return new BigInteger(clean, 16);
        }
        // 2. Base64 编码私钥 (32 字节原始私钥或 PKCS#8 格式)
        try {
            byte[] bytes = Base64.getDecoder().decode(clean);
            if (bytes.length == 32) {
                return new BigInteger(1, bytes);
            }
            // 扫描 PKCS#8 / SEC1 内部 32 字节 OCTET STRING (0x04 0x20 [32 字节])
            for (int i = 0; i <= bytes.length - 34; i++) {
                if (bytes[i] == 0x04 && bytes[i + 1] == 0x20) {
                    byte[] dBytes = new byte[32];
                    System.arraycopy(bytes, i + 2, dBytes, 0, 32);
                    return new BigInteger(1, dBytes);
                }
            }
            throw new IllegalArgumentException("不支持的 SM2 私钥字节长度: " + bytes.length);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("SM2 私钥解析失败: " + e.getMessage(), e);
        }
    }

    private static ECPoint parsePublicKey(String key) {
        String clean = cleanKey(key);
        // 1. Hex 格式公钥 (130 位非压缩 04 + X + Y、128 位裸 X + Y 或 66 位压缩格式 02/03 + X)
        if (isHex(clean)) {
            if (clean.length() == 130 && clean.startsWith("04")) {
                BigInteger x = new BigInteger(clean.substring(2, 66), 16);
                BigInteger y = new BigInteger(clean.substring(66, 130), 16);
                return new ECPoint(x, y);
            }
            if (clean.length() == 128) {
                BigInteger x = new BigInteger(clean.substring(0, 64), 16);
                BigInteger y = new BigInteger(clean.substring(64, 128), 16);
                return new ECPoint(x, y);
            }
            if (clean.length() == 66 && (clean.startsWith("02") || clean.startsWith("03"))) {
                return decompressPoint(clean);
            }
        }
        // 2. Base64 格式公钥
        try {
            byte[] bytes = Base64.getDecoder().decode(clean);
            if (bytes.length == 65 && bytes[0] == 0x04) {
                byte[] xb = new byte[32];
                byte[] yb = new byte[32];
                System.arraycopy(bytes, 1, xb, 0, 32);
                System.arraycopy(bytes, 33, yb, 0, 32);
                return new ECPoint(new BigInteger(1, xb), new BigInteger(1, yb));
            }
            if (bytes.length == 64) {
                byte[] xb = new byte[32];
                byte[] yb = new byte[32];
                System.arraycopy(bytes, 0, xb, 0, 32);
                System.arraycopy(bytes, 32, yb, 0, 32);
                return new ECPoint(new BigInteger(1, xb), new BigInteger(1, yb));
            }
            if (bytes.length == 33 && (bytes[0] == 0x02 || bytes[0] == 0x03)) {
                return decompressPoint(HexFormat.of().formatHex(bytes));
            }
            // 扫描 X.509 SubjectPublicKeyInfo 内部 65 字节非压缩公钥 (0x04 [32 字节 X] [32 字节 Y])
            for (int i = 0; i <= bytes.length - 65; i++) {
                if (bytes[i] == 0x04) {
                    byte[] xb = new byte[32];
                    byte[] yb = new byte[32];
                    System.arraycopy(bytes, i + 1, xb, 0, 32);
                    System.arraycopy(bytes, i + 33, yb, 0, 32);
                    ECPoint pt = new ECPoint(new BigInteger(1, xb), new BigInteger(1, yb));
                    if (isOnCurve(pt)) {
                        return pt;
                    }
                }
            }
            throw new IllegalArgumentException("不支持的 SM2 公钥字节长度: " + bytes.length);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("SM2 公钥解析失败: " + e.getMessage(), e);
        }
    }

    private static ECPoint decompressPoint(String hex) {
        int yTilde = Integer.parseInt(hex.substring(0, 2), 16) & 1;
        BigInteger x = new BigInteger(hex.substring(2), 16);
        // y^2 = x^3 + a*x + b mod P
        BigInteger alpha = x.modPow(BigInteger.valueOf(3), P).add(A.multiply(x)).add(B).mod(P);
        // P 恒满足 P = 3 (mod 4)，因此平方根直接为 alpha^((P+1)/4) mod P
        BigInteger pPlus1Div4 = P.add(BigInteger.ONE).shiftRight(2);
        BigInteger y = alpha.modPow(pPlus1Div4, P);
        if (y.testBit(0) != (yTilde == 1)) {
            y = P.subtract(y);
        }
        return new ECPoint(x, y);
    }

    private static String cleanKey(String key) {
        return key.replace("-----BEGIN PUBLIC KEY-----", "")
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

    private static boolean isHex(String s) {
        if (!StringUtils.hasText(s)) {
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

    private static byte[] decodeSignatureBytes(String signature) {
        String clean = cleanKey(signature);
        if (isHex(clean) && (clean.length() == 128 || clean.startsWith("30"))) {
            return HexFormat.of().parseHex(clean);
        }
        return Base64.getDecoder().decode(clean);
    }

    private static BigInteger[] decodeDerOrRaw(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            throw new IllegalArgumentException("非法签名载荷长度");
        }
        // 1. ASN.1 DER 编码 (0x30 SEQUENCE)
        if (bytes[0] == 0x30) {
            int pos = 1;
            int seqLen = bytes[pos++] & 0xFF;
            if ((seqLen & 0x80) != 0) {
                int numBytes = seqLen & 0x7F;
                seqLen = 0;
                for (int i = 0; i < numBytes; i++) {
                    seqLen = (seqLen << 8) | (bytes[pos++] & 0xFF);
                }
            }
            if (bytes[pos++] != 0x02) {
                throw new IllegalArgumentException("非法的 ASN.1 DER 签名: 期望整数 r");
            }
            int rLen = bytes[pos++] & 0xFF;
            byte[] rb = Arrays.copyOfRange(bytes, pos, pos + rLen);
            pos += rLen;

            if (bytes[pos++] != 0x02) {
                throw new IllegalArgumentException("非法的 ASN.1 DER 签名: 期望整数 s");
            }
            int sLen = bytes[pos++] & 0xFF;
            byte[] sb = Arrays.copyOfRange(bytes, pos, pos + sLen);

            return new BigInteger[]{new BigInteger(1, rb), new BigInteger(1, sb)};
        }
        // 2. 裸 64 字节 r || s
        if (bytes.length == 64) {
            byte[] rb = Arrays.copyOfRange(bytes, 0, 32);
            byte[] sb = Arrays.copyOfRange(bytes, 32, 64);
            return new BigInteger[]{new BigInteger(1, rb), new BigInteger(1, sb)};
        }
        throw new IllegalArgumentException("不支持的 SM2 签名编码格式");
    }

    private static byte[] encodeDer(BigInteger r, BigInteger s) {
        byte[] rb = r.toByteArray();
        byte[] sb = s.toByteArray();
        int len = 2 + rb.length + 2 + sb.length;
        byte[] der;
        int pos = 0;
        if (len < 128) {
            der = new byte[2 + len];
            der[pos++] = 0x30;
            der[pos++] = (byte) len;
        } else {
            der = new byte[3 + len];
            der[pos++] = 0x30;
            der[pos++] = (byte) 0x81;
            der[pos++] = (byte) len;
        }
        der[pos++] = 0x02;
        der[pos++] = (byte) rb.length;
        System.arraycopy(rb, 0, der, pos, rb.length);
        pos += rb.length;
        der[pos++] = 0x02;
        der[pos++] = (byte) sb.length;
        System.arraycopy(sb, 0, der, pos, sb.length);
        return der;
    }

    private static byte[] toBytes32(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length == 32) {
            return b;
        }
        byte[] res = new byte[32];
        if (b.length > 32) {
            System.arraycopy(b, b.length - 32, res, 0, 32);
        } else {
            System.arraycopy(b, 0, res, 32 - b.length, b.length);
        }
        return res;
    }

    private static byte[] computeZa(byte[] userId, ECPoint pubKey) {
        int entla = userId.length * 8;
        byte[] entlaBytes = new byte[]{(byte) ((entla >>> 8) & 0xFF), (byte) (entla & 0xFF)};
        byte[] aBytes = toBytes32(A);
        byte[] bBytes = toBytes32(B);
        byte[] gxBytes = toBytes32(GX);
        byte[] gyBytes = toBytes32(GY);
        byte[] xBytes = toBytes32(pubKey.x);
        byte[] yBytes = toBytes32(pubKey.y);

        byte[] all = new byte[2 + userId.length + 32 * 6];
        int pos = 0;
        System.arraycopy(entlaBytes, 0, all, pos, 2); pos += 2;
        System.arraycopy(userId, 0, all, pos, userId.length); pos += userId.length;
        System.arraycopy(aBytes, 0, all, pos, 32); pos += 32;
        System.arraycopy(bBytes, 0, all, pos, 32); pos += 32;
        System.arraycopy(gxBytes, 0, all, pos, 32); pos += 32;
        System.arraycopy(gyBytes, 0, all, pos, 32); pos += 32;
        System.arraycopy(xBytes, 0, all, pos, 32); pos += 32;
        System.arraycopy(yBytes, 0, all, pos, 32);

        return DigestUtil.sm3(all);
    }

    public static boolean isOnCurve(ECPoint pt) {
        if (pt == null || pt.isInfinity()) {
            return false;
        }
        BigInteger left = pt.y.multiply(pt.y).mod(P);
        BigInteger right = pt.x.modPow(BigInteger.valueOf(3), P).add(A.multiply(pt.x)).add(B).mod(P);
        return left.equals(right);
    }

    // ================================= 雅可比投影坐标系点乘 =================================

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
     * 雅可比投影坐标点 (X, Y, Z)，对应仿射坐标 (X / Z^2, Y / Z^3)
     */
    public static class JacobianPoint {
        public static final JacobianPoint INFINITY = new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);

        public final BigInteger x;
        public final BigInteger y;
        public final BigInteger z;

        public JacobianPoint(BigInteger x, BigInteger y, BigInteger z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public boolean isInfinity() {
            return z == null || z.signum() == 0;
        }

        public ECPoint toAffine() {
            if (isInfinity()) {
                return ECPoint.INFINITY;
            }
            BigInteger zInv = z.modInverse(P);
            BigInteger zInv2 = zInv.multiply(zInv).mod(P);
            BigInteger zInv3 = zInv2.multiply(zInv).mod(P);
            return new ECPoint(x.multiply(zInv2).mod(P), y.multiply(zInv3).mod(P));
        }

        public JacobianPoint doublePoint() {
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
            BigInteger e1 = a1.multiply(BigInteger.valueOf(3)).add(A.multiply(z4)).mod(P);

            BigInteger x3 = e1.multiply(e1).subtract(d1.shiftLeft(1)).mod(P);
            BigInteger y3 = e1.multiply(d1.subtract(x3)).subtract(c1.shiftLeft(3)).mod(P);
            BigInteger z3 = y.multiply(z).shiftLeft(1).mod(P);

            return new JacobianPoint(x3, y3, z3);
        }

        public JacobianPoint add(JacobianPoint p2) {
            if (isInfinity()) return p2;
            if (p2.isInfinity()) return this;

            BigInteger z1_2 = z.multiply(z).mod(P);
            BigInteger z2_2 = p2.z.multiply(p2.z).mod(P);
            BigInteger u1 = x.multiply(z2_2).mod(P);
            BigInteger u2 = p2.x.multiply(z1_2).mod(P);
            BigInteger s1 = y.multiply(z2_2).multiply(p2.z).mod(P);
            BigInteger s2 = p2.y.multiply(z1_2).multiply(z).mod(P);

            BigInteger h = u2.subtract(u1).mod(P);
            BigInteger r = s2.subtract(s1).mod(P);

            if (h.signum() == 0) {
                if (r.signum() == 0) {
                    return doublePoint();
                }
                return INFINITY;
            }

            BigInteger h2 = h.multiply(h).mod(P);
            BigInteger h3 = h.multiply(h2).mod(P);
            BigInteger v = u1.multiply(h2).mod(P);

            BigInteger x3 = r.multiply(r).subtract(h3).subtract(v.shiftLeft(1)).mod(P);
            BigInteger y3 = r.multiply(v.subtract(x3)).subtract(s1.multiply(h3)).mod(P);
            BigInteger z3 = z.multiply(p2.z).multiply(h).mod(P);

            return new JacobianPoint(x3, y3, z3);
        }

        public JacobianPoint addAffine(BigInteger x2, BigInteger y2) {
            if (isInfinity()) return new JacobianPoint(x2, y2, BigInteger.ONE);
            if (x2 == null || y2 == null) return this;

            BigInteger z1_2 = z.multiply(z).mod(P);
            BigInteger u2 = x2.multiply(z1_2).mod(P);
            BigInteger s2 = y2.multiply(z1_2).multiply(z).mod(P);

            BigInteger h = u2.subtract(x).mod(P);
            BigInteger r = s2.subtract(y).mod(P);

            if (h.signum() == 0) {
                if (r.signum() == 0) {
                    return doublePoint();
                }
                return INFINITY;
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

    private static JacobianPoint multiply(ECPoint p, BigInteger k) {
        if (p == null || p.isInfinity() || k == null || k.signum() == 0) {
            return JacobianPoint.INFINITY;
        }
        k = k.mod(N);
        if (k.signum() == 0) {
            return JacobianPoint.INFINITY;
        }

        JacobianPoint result = JacobianPoint.INFINITY;
        int bitLength = k.bitLength();
        for (int i = bitLength - 1; i >= 0; i--) {
            result = result.doublePoint();
            if (k.testBit(i)) {
                result = result.addAffine(p.x, p.y);
            }
        }
        return result;
    }
}
