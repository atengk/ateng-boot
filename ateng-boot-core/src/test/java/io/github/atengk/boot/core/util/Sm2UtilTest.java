package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Sm2Util} 纯 JDK 原生国密 SM2 椭圆曲线密码原语单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("Sm2Util 国密 SM2 曲线原语、签验与加解密测试")
class Sm2UtilTest {

    @Test
    @DisplayName("GB/T 32918 标准 256 位曲线参数、雅可比点乘与标量边界校验")
    void shouldMatchStandardCurveParametersAndScalarBoundaries() {
        assertEquals(256, Sm2Util.N.bitLength());
        assertEquals("fffffffeffffffffffffffffffffffff7203df6b21c6052b53bbf40939d54123", Sm2Util.N.toString(16));
        assertTrue(Sm2Util.isOnCurve(Sm2Util.G));

        // 标量边界: [1]G == G, [N]G == INFINITY, [N-1]G == -G, G + [N-1]G == INFINITY
        Sm2Util.Sm2Point oneG = Sm2Util.multiply(BigInteger.ONE, Sm2Util.G);
        assertEquals(Sm2Util.G, oneG);

        Sm2Util.Sm2Point nG = Sm2Util.multiply(Sm2Util.N, Sm2Util.G);
        assertTrue(nG.isInfinity());

        Sm2Util.Sm2Point nMinus1G = Sm2Util.multiply(Sm2Util.N.subtract(BigInteger.ONE), Sm2Util.G);
        assertTrue(Sm2Util.isOnCurve(nMinus1G));
        assertEquals(Sm2Util.GX, nMinus1G.x());
        assertEquals(Sm2Util.P.subtract(Sm2Util.GY), nMinus1G.y());

        assertTrue(Sm2Util.add(Sm2Util.G, nMinus1G).isInfinity());
        assertTrue(Sm2Util.multiply(BigInteger.ZERO, Sm2Util.G).isInfinity());
        assertEquals(Sm2Util.G, Sm2Util.add(Sm2Util.G, Sm2Util.Sm2Point.INFINITY));
    }

    @Test
    @DisplayName("批量密钥生成严格落在 [1, N-2] 且公钥位于曲线上，并兼容多格式公私钥解析")
    void shouldGenerateValidKeyPairsAndParseAllFormats() {
        BigInteger maxPrivate = Sm2Util.N.subtract(BigInteger.TWO);
        for (int i = 0; i < 50; i++) {
            Sm2Util.Sm2KeyPair kp = Sm2Util.generateKeyPair();
            assertTrue(kp.privateKey().compareTo(BigInteger.ONE) >= 0);
            assertTrue(kp.privateKey().compareTo(maxPrivate) <= 0);
            assertTrue(Sm2Util.isValidPrivateKey(kp.privateKey()));
            assertTrue(Sm2Util.isOnCurve(kp.publicKey()));
            assertEquals(64, kp.privateKeyHex().length());
            assertEquals(130, kp.publicKeyHex().length());
            assertTrue(kp.publicKeyHex().startsWith("04"));
        }

        Sm2Util.Sm2KeyPair kp = Sm2Util.generateKeyPair();
        String pubPem = Sm2Util.encodePublicKeyToPem(kp.publicKey());
        String priPem = Sm2Util.encodePrivateKeyToPem(kp.privateKey());
        String rawHex128 = kp.publicKeyHex().substring(2);
        String compressedHex66 = HexUtil.encodeHex(kp.publicKey().toCompressedBytes());
        String compressedB64 = Base64.getEncoder().encodeToString(kp.publicKey().toCompressedBytes());

        assertEquals(kp.publicKey(), Sm2Util.parsePublicKey(kp.publicKeyHex()));
        assertEquals(kp.publicKey(), Sm2Util.parsePublicKey(rawHex128));
        assertEquals(kp.publicKey(), Sm2Util.parsePublicKey(compressedHex66));
        assertEquals(kp.publicKey(), Sm2Util.parsePublicKey(kp.publicKeyBase64()));
        assertEquals(kp.publicKey(), Sm2Util.parsePublicKey(compressedB64));
        assertEquals(kp.publicKey(), Sm2Util.parsePublicKey(pubPem));

        assertEquals(kp.privateKey(), Sm2Util.parsePrivateKey(kp.privateKeyHex()));
        assertEquals(kp.privateKey(), Sm2Util.parsePrivateKey(kp.privateKeyBase64()));
        assertEquals(kp.privateKey(), Sm2Util.parsePrivateKey(priPem));

        // 越界私钥与不在曲线上的公钥点必须拒绝
        assertFalse(Sm2Util.isValidPrivateKey(BigInteger.ZERO));
        assertFalse(Sm2Util.isValidPrivateKey(Sm2Util.N.subtract(BigInteger.ONE)));
        assertFalse(Sm2Util.isValidPrivateKey(Sm2Util.N));
        assertThrows(IllegalArgumentException.class,
                () -> Sm2Util.parsePrivateKey(Sm2Util.N.toString(16)));
        assertThrows(IllegalArgumentException.class,
                () -> Sm2Util.parsePublicKey("04" + "00".repeat(64)));
    }

    @Test
    @DisplayName("SM2 加解密：支持 C1C3C2 (65B/64B) 与 ASN.1 DER 双向兼容、大报文 DER 长度及空明文防卫")
    void shouldEncryptDecryptAndConvertFormatsWithGuards() {
        Sm2Util.Sm2KeyPair kp = Sm2Util.generateKeyPair();
        String plain = "国密 SM2 机密传输载荷：merchantId=M8801&secret=TopSecret#2026";
        byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);

        // 1. 默认 C1C3C2 往返
        byte[] c1c3c2 = Sm2Util.encrypt(plainBytes, kp.publicKey());
        assertEquals((byte) 0x04, c1c3c2[0]);
        assertEquals(65 + 32 + plainBytes.length, c1c3c2.length);
        assertArrayEquals(plainBytes, Sm2Util.decrypt(c1c3c2, kp.privateKey()));

        // 2. 无 0x04 裸 64B C1 兼容解密
        byte[] rawWithout04 = new byte[c1c3c2.length - 1];
        System.arraycopy(c1c3c2, 1, rawWithout04, 0, rawWithout04.length);
        assertArrayEquals(plainBytes, Sm2Util.decrypt(rawWithout04, kp.privateKey()));

        // 3. ASN.1 DER 双向转译与解密
        byte[] der = Sm2Util.c1c3c2ToDer(c1c3c2);
        assertEquals((byte) 0x30, der[0]);
        assertArrayEquals(c1c3c2, Sm2Util.derToC1c3c2(der));
        assertArrayEquals(plainBytes, Sm2Util.decrypt(der, kp.privateKey()));

        String derB64 = Sm2Util.encryptToDerBase64(plain, kp.publicKeyHex());
        assertEquals(plain, Sm2Util.decryptBase64(derB64, kp.privateKeyHex()));

        // 4. 超过 65535 字节的大报文 DER 编码与无损解码（0x83 三字节长度验证）
        byte[] largePlain = new byte[70_000];
        for (int i = 0; i < largePlain.length; i++) {
            largePlain[i] = (byte) (i * 31 + 7);
        }
        byte[] largeC1c3c2 = Sm2Util.encrypt(largePlain, kp.publicKey());
        byte[] largeDer = Sm2Util.c1c3c2ToDer(largeC1c3c2);
        assertEquals((byte) 0x83, largeDer[1]);
        assertArrayEquals(largeC1c3c2, Sm2Util.derToC1c3c2(largeDer));
        assertArrayEquals(largePlain, Sm2Util.decrypt(largeDer, kp.privateKey()));

        // 5. 空明文与非法私钥快速失败，C3 篡改抛出脱敏 IllegalStateException
        assertThrows(IllegalArgumentException.class, () -> Sm2Util.encrypt((byte[]) null, kp.publicKey()));
        assertThrows(IllegalArgumentException.class, () -> Sm2Util.encrypt(new byte[0], kp.publicKey()));
        assertThrows(IllegalArgumentException.class, () -> Sm2Util.encryptBase64("", kp.publicKeyHex()));
        assertThrows(IllegalArgumentException.class, () -> Sm2Util.decrypt(c1c3c2, BigInteger.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Sm2Util.decrypt(c1c3c2, Sm2Util.N));

        byte[] tamperedC3 = c1c3c2.clone();
        tamperedC3[70] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> Sm2Util.decrypt(tamperedC3, kp.privateKey()));

        // DER 篡改密文应直接失败而不能错判
        byte[] tamperedDer = der.clone();
        tamperedDer[tamperedDer.length - 1] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> Sm2Util.decrypt(tamperedDer, kp.privateKey()));
    }

    @Test
    @DisplayName("SM2/SM3 签验：DER 与 Raw 64B 双格式互转、自定义 userId 隔离与防篡改")
    void shouldSignVerifyDerAndRawWithCustomUserId() {
        Sm2Util.Sm2KeyPair kp = Sm2Util.generateKeyPair();
        Sm2Util.Sm2KeyPair otherKp = Sm2Util.generateKeyPair();
        String data = "POST\n/api/gm/transfer\n1794052800000\nnonce-sm2\n{\"amt\":100}";
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);

        // 1. 默认 DER 签名与验签
        byte[] derSig = Sm2Util.sign(dataBytes, kp.privateKey());
        assertEquals((byte) 0x30, derSig[0]);
        assertTrue(Sm2Util.verify(dataBytes, derSig, kp.publicKey()));
        assertFalse(Sm2Util.verify((data + "!").getBytes(StandardCharsets.UTF_8), derSig, kp.publicKey()));
        assertFalse(Sm2Util.verify(dataBytes, derSig, otherKp.publicKey()));

        // 2. DER <-> Raw 64B 互转验签
        byte[] raw64 = Sm2Util.derSignatureToRaw(derSig);
        assertEquals(64, raw64.length);
        assertTrue(Sm2Util.verify(dataBytes, raw64, kp.publicKey()));
        byte[] rebuiltDer = Sm2Util.rawSignatureToDer(raw64);
        assertTrue(Sm2Util.verify(dataBytes, rebuiltDer, kp.publicKey()));

        byte[] rawDirect = Sm2Util.signRaw(dataBytes, kp.privateKey());
        assertEquals(64, rawDirect.length);
        assertTrue(Sm2Util.verify(dataBytes, rawDirect, kp.publicKey()));

        // 3. Base64 字符串门面与自定义 userId 域隔离
        String sigB64 = Sm2Util.signBase64(data, kp.privateKeyHex());
        assertTrue(Sm2Util.verifyBase64(data, sigB64, kp.publicKeyHex()));

        byte[] uidA = "USER_ALPHA".getBytes(StandardCharsets.UTF_8);
        byte[] uidB = "USER_BETA".getBytes(StandardCharsets.UTF_8);
        String sigUidA = Sm2Util.signBase64(data, kp.privateKeyHex(), uidA);
        assertTrue(Sm2Util.verifyBase64(data, sigUidA, kp.publicKeyHex(), uidA));
        assertFalse(Sm2Util.verifyBase64(data, sigUidA, kp.publicKeyHex(), uidB));
        assertFalse(Sm2Util.verifyBase64(data, sigUidA, kp.publicKeyHex()));

        // 4. 空入参与异常边界
        assertThrows(IllegalArgumentException.class, () -> Sm2Util.sign(null, kp.privateKey()));
        assertThrows(IllegalArgumentException.class, () -> Sm2Util.sign(dataBytes, BigInteger.ZERO));
        assertFalse(Sm2Util.verify(null, derSig, kp.publicKey()));
        assertFalse(Sm2Util.verify(dataBytes, null, kp.publicKey()));
        assertFalse(Sm2Util.verify(dataBytes, derSig, Sm2Util.Sm2Point.INFINITY));
        assertFalse(Sm2Util.verifyBase64(data, sigB64, "invalid-pub-key"));
    }
}
