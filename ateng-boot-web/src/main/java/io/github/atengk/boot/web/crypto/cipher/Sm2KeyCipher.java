package io.github.atengk.boot.web.crypto.cipher;

import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 纯 JDK 17 原生国密 SM2 非对称加解密引擎 (GB/T 32918.4-2016)
 * 曲线参数、密钥格式解析（Hex/Base64/PEM/PKCS#8）与标量点乘统一复用 {@link Sm2Signer} 原语，
 * 加密输出标准 C1C3C2 (04||X1||Y1||C3||C2)，解密兼容 C1C3C2 与 ASN.1 DER 双格式，零 Bouncy Castle 依赖
 *
 * @author Ateng
 * @since 2026-11-27
 */
final class Sm2KeyCipher {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Sm2KeyCipher() {
    }

    static Sm2Signer.ECPoint parsePublicKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("SM2 公钥不能为空");
        }
        Sm2Signer.ECPoint pt = Sm2Signer.parsePublicKey(key);
        if (!Sm2Signer.isOnCurve(pt)) {
            throw new IllegalArgumentException("SM2 公钥点不在标准椭圆曲线上");
        }
        return pt;
    }

    static BigInteger parsePrivateKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("SM2 私钥不能为空");
        }
        BigInteger d = Sm2Signer.parsePrivateKey(key);
        if (d.compareTo(BigInteger.ONE) < 0 || d.compareTo(Sm2Signer.N.subtract(BigInteger.TWO)) > 0) {
            throw new IllegalArgumentException("SM2 私钥标量超出有效定义域 [1, n-2]");
        }
        return d;
    }

    static byte[] encrypt(byte[] plaintext, Sm2Signer.ECPoint pubKey) {
        if (plaintext == null || plaintext.length == 0 || pubKey == null || !Sm2Signer.isOnCurve(pubKey)) {
            throw new IllegalArgumentException("SM2 加密明文或公钥非法");
        }
        int klen = plaintext.length;
        byte[] x2 = null;
        byte[] y2 = null;
        byte[] z = null;
        byte[] t = null;
        try {
            while (true) {
                BigInteger k = randomScalar();
                Sm2Signer.ECPoint c1Point = Sm2Signer.scalarMultiply(Sm2Signer.G, k);
                Sm2Signer.ECPoint sPoint = Sm2Signer.scalarMultiply(pubKey, k);
                if (sPoint.isInfinity()) {
                    continue;
                }
                x2 = Sm2Signer.toBytes32(sPoint.x);
                y2 = Sm2Signer.toBytes32(sPoint.y);
                z = new byte[64];
                System.arraycopy(x2, 0, z, 0, 32);
                System.arraycopy(y2, 0, z, 32, 32);

                t = kdf(z, klen);
                if (isAllZero(t)) {
                    continue;
                }
                byte[] c2 = new byte[klen];
                for (int i = 0; i < klen; i++) {
                    c2[i] = (byte) (plaintext[i] ^ t[i]);
                }
                byte[] c3 = DigestUtil.sm3(x2, plaintext, y2);
                byte[] x1 = Sm2Signer.toBytes32(c1Point.x);
                byte[] y1 = Sm2Signer.toBytes32(c1Point.y);
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

    static byte[] decrypt(byte[] ciphertext, BigInteger privKey) {
        if (ciphertext == null || ciphertext.length == 0 || privKey == null) {
            throw new IllegalArgumentException("SM2 解密入参密文或私钥非法");
        }
        if (ciphertext[0] == 0x30) {
            try {
                return decryptC1C3C2(derToC1c3c2(ciphertext), privKey);
            } catch (IllegalArgumentException e) {
                return decryptC1C3C2(ciphertext, privKey);
            }
        }
        return decryptC1C3C2(ciphertext, privKey);
    }

    private static byte[] decryptC1C3C2(byte[] ciphertext, BigInteger privKey) {
        int offset = ciphertext[0] == 0x04 ? 1 : 0;
        int c1Len = offset == 1 ? 65 : 64;
        if (ciphertext.length <= c1Len + 32) {
            throw new IllegalArgumentException("SM2 密文长度非法，不足以包含 C1/C3/C2");
        }

        Sm2Signer.ECPoint c1 = readUncompressedPoint(ciphertext, offset);
        if (!Sm2Signer.isOnCurve(c1)) {
            if (offset == 1 && ciphertext.length > 64 + 32) {
                Sm2Signer.ECPoint alt = readUncompressedPoint(ciphertext, 0);
                if (Sm2Signer.isOnCurve(alt)) {
                    c1 = alt;
                    c1Len = 64;
                } else {
                    throw new IllegalArgumentException("SM2 密文 C1 曲线点不合法");
                }
            } else {
                throw new IllegalArgumentException("SM2 密文 C1 曲线点不合法");
            }
        }

        Sm2Signer.ECPoint s = Sm2Signer.scalarMultiply(c1, privKey);
        if (s.isInfinity()) {
            throw new IllegalArgumentException("SM2 解密椭圆曲线点落在无穷远点");
        }

        byte[] x2 = Sm2Signer.toBytes32(s.x);
        byte[] y2 = Sm2Signer.toBytes32(s.y);
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
                throw new IllegalArgumentException("SM2 KDF 派生密钥全零异常");
            }
            m = new byte[c2Len];
            for (int i = 0; i < c2Len; i++) {
                m[i] = (byte) (ciphertext[c1Len + 32 + i] ^ t[i]);
            }

            expectedC3 = DigestUtil.sm3(x2, m, y2);
            if (!MessageDigest.isEqual(c3, expectedC3)) {
                throw new IllegalArgumentException("SM2 密文摘要 C3 校验失败");
            }
            return m.clone();
        } finally {
            Arrays.fill(x2, (byte) 0);
            Arrays.fill(y2, (byte) 0);
            Arrays.fill(z, (byte) 0);
            if (t != null) Arrays.fill(t, (byte) 0);
            if (m != null) Arrays.fill(m, (byte) 0);
            if (expectedC3 != null) Arrays.fill(expectedC3, (byte) 0);
        }
    }

    private static byte[] derToC1c3c2(byte[] der) {
        if (der.length < 8 || der[0] != 0x30) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 密文格式非法");
        }
        int[] cursor = new int[]{1};
        int seqLen = readDerLength(der, cursor);
        if (cursor[0] + seqLen != der.length) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 序列长度不匹配");
        }
        byte[] x1 = readDerField(der, cursor, (byte) 0x02);
        byte[] y1 = readDerField(der, cursor, (byte) 0x02);
        byte[] c3 = readDerField(der, cursor, (byte) 0x04);
        byte[] c2 = readDerField(der, cursor, (byte) 0x04);
        if (cursor[0] != der.length || c3.length != 32 || c2.length == 0) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 字段结构非法");
        }
        byte[] assembled = new byte[65 + 32 + c2.length];
        assembled[0] = 0x04;
        System.arraycopy(Sm2Signer.toBytes32(new BigInteger(1, x1)), 0, assembled, 1, 32);
        System.arraycopy(Sm2Signer.toBytes32(new BigInteger(1, y1)), 0, assembled, 33, 32);
        System.arraycopy(c3, 0, assembled, 65, 32);
        System.arraycopy(c2, 0, assembled, 97, c2.length);
        return assembled;
    }

    private static byte[] readDerField(byte[] der, int[] cursor, byte expectedTag) {
        if (cursor[0] >= der.length || der[cursor[0]++] != expectedTag) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 标签不匹配");
        }
        int len = readDerLength(der, cursor);
        if (len < 0 || cursor[0] + len > der.length) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 字段长度越界");
        }
        byte[] val = Arrays.copyOfRange(der, cursor[0], cursor[0] + len);
        cursor[0] += len;
        return val;
    }

    private static int readDerLength(byte[] der, int[] cursor) {
        if (cursor[0] >= der.length) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 长度缺失");
        }
        int b = der[cursor[0]++] & 0xff;
        if ((b & 0x80) == 0) {
            return b;
        }
        int numBytes = b & 0x7f;
        if (numBytes == 0 || numBytes > 4 || cursor[0] + numBytes > der.length) {
            throw new IllegalArgumentException("SM2 ASN.1 DER 长度字节非法");
        }
        int len = 0;
        for (int i = 0; i < numBytes; i++) {
            len = (len << 8) | (der[cursor[0]++] & 0xff);
        }
        return len;
    }

    private static byte[] kdf(byte[] z, int klen) {
        byte[] key = new byte[klen];
        byte[] buf = new byte[z.length + 4];
        System.arraycopy(z, 0, buf, 0, z.length);
        int ct = 1;
        int offset = 0;
        try {
            while (offset < klen) {
                buf[z.length] = (byte) (ct >>> 24);
                buf[z.length + 1] = (byte) (ct >>> 16);
                buf[z.length + 2] = (byte) (ct >>> 8);
                buf[z.length + 3] = (byte) ct;
                byte[] h = DigestUtil.sm3(buf);
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

    private static BigInteger randomScalar() {
        BigInteger k;
        do {
            k = new BigInteger(256, SECURE_RANDOM);
        } while (k.compareTo(BigInteger.ONE) < 0 || k.compareTo(Sm2Signer.N) >= 0);
        return k;
    }

    private static Sm2Signer.ECPoint readUncompressedPoint(byte[] bytes, int offset) {
        byte[] xb = new byte[32];
        byte[] yb = new byte[32];
        System.arraycopy(bytes, offset, xb, 0, 32);
        System.arraycopy(bytes, offset + 32, yb, 0, 32);
        return new Sm2Signer.ECPoint(new BigInteger(1, xb), new BigInteger(1, yb));
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
