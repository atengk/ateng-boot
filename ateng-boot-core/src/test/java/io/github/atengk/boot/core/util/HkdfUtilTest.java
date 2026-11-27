package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HkdfUtil} RFC 5869 密钥派生原语单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("HkdfUtil RFC 5869 密钥派生与域隔离测试")
class HkdfUtilTest {

    @Test
    @DisplayName("RFC 5869 Test Case 1: HKDF-SHA256 基础向量精确匹配")
    void shouldMatchRfc5869TestCase1() {
        byte[] ikm = HexUtil.decodeHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HexUtil.decodeHex("000102030405060708090a0b0c");
        byte[] info = HexUtil.decodeHex("f0f1f2f3f4f5f6f7f8f9");
        int length = 42;

        byte[] prk = HkdfUtil.extract(salt, ikm);
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", HexUtil.encodeHex(prk));

        byte[] okm = HkdfUtil.expand(prk, info, length);
        String expectedOkm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865";
        assertEquals(expectedOkm, HexUtil.encodeHex(okm));
        assertArrayEquals(okm, HkdfUtil.deriveKey(ikm, salt, info, length));
    }

    @Test
    @DisplayName("RFC 5869 Test Case 2: HKDF-SHA256 长输入与多分组输出精确匹配")
    void shouldMatchRfc5869TestCase2() {
        byte[] ikm = HexUtil.decodeHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
                        + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
                        + "404142434445464748494a4b4c4d4e4f");
        byte[] salt = HexUtil.decodeHex(
                "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
                        + "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
                        + "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
        byte[] info = HexUtil.decodeHex(
                "b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf"
                        + "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef"
                        + "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
        int length = 82;

        byte[] prk = HkdfUtil.extract(salt, ikm);
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", HexUtil.encodeHex(prk));

        byte[] okm = HkdfUtil.deriveKey(ikm, salt, info, length);
        String expectedOkm = "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c"
                + "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71"
                + "cc30c58179ec3e87c14c01d5c1f3434f1d87";
        assertEquals(expectedOkm, HexUtil.encodeHex(okm));
    }

    @Test
    @DisplayName("RFC 5869 Test Case 3: HKDF-SHA256 空盐与空 info 零盐填充规则精确匹配")
    void shouldMatchRfc5869TestCase3() {
        byte[] ikm = HexUtil.decodeHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        int length = 42;

        byte[] prkEmptySalt = HkdfUtil.extract(new byte[0], ikm);
        byte[] prkNullSalt = HkdfUtil.extract(null, ikm);
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", HexUtil.encodeHex(prkEmptySalt));
        assertArrayEquals(prkEmptySalt, prkNullSalt);

        String expectedOkm = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8";
        assertEquals(expectedOkm, HexUtil.encodeHex(HkdfUtil.deriveKey(ikm, null, (byte[]) null, length)));
        assertEquals(expectedOkm, HexUtil.encodeHex(HkdfUtil.deriveKey(ikm, new byte[0], "", length)));
    }

    @Test
    @DisplayName("国密 HKDF-SM3 域隔离派生与零盐默认行为验证")
    void shouldDeriveDomainIsolatedKeysWithHkdfSm3() {
        byte[] masterIkm = "ateng-master-key-material-2026".getBytes(StandardCharsets.UTF_8);
        byte[] salt = "tenant-001-salt".getBytes(StandardCharsets.UTF_8);

        byte[] encKey = HkdfUtil.deriveKeySm3(masterIkm, salt, "aes-enc-key", 16);
        byte[] macKey = HkdfUtil.deriveKeySm3(masterIkm, salt, "hmac-mac-key", 32);
        byte[] encKeyRepeat = HkdfUtil.deriveKeySm3(masterIkm, salt, "aes-enc-key", 16);

        assertEquals(16, encKey.length);
        assertEquals(32, macKey.length);
        assertArrayEquals(encKey, encKeyRepeat);
        assertFalse(CryptoUtil.constantTimeEquals(encKey, java.util.Arrays.copyOf(macKey, 16)));

        // 空盐与显式 32 字节 0x00 盐在 HKDF-SM3 下必须严格一致
        assertArrayEquals(
                HkdfUtil.extractSm3(null, masterIkm),
                HkdfUtil.extractSm3(new byte[32], masterIkm)
        );
        byte[] prk = HkdfUtil.extractSm3(salt, masterIkm);
        assertArrayEquals(encKey, HkdfUtil.expandSm3(prk, "aes-enc-key", 16));
    }

    @Test
    @DisplayName("边界与非法长度防御：拦截空 IKM、短 PRK 与越界目标长度")
    void shouldRejectInvalidInputsAndOutOfRangeLengths() {
        byte[] validIkm = new byte[]{1, 2, 3, 4};
        byte[] validPrk = new byte[32];
        int maxLength = 255 * 32;

        // 最小与最大合法边界 (1 与 8160)
        assertEquals(1, HkdfUtil.deriveKey(validIkm, null, "min", 1).length);
        assertEquals(maxLength, HkdfUtil.expand(validPrk, "max", maxLength).length);
        assertEquals(maxLength, HkdfUtil.expandSm3(validPrk, "max-sm3", maxLength).length);

        // 非法参数拒绝
        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.extract(null, null));
        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.extract(null, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.extractSm3(null, new byte[0]));

        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.expand(null, "a", 16));
        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.expand(new byte[31], "a", 16));
        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.expandSm3(new byte[31], "a", 16));

        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.deriveKey(validIkm, null, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.deriveKey(validIkm, null, "a", -1));
        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.deriveKey(validIkm, null, "a", maxLength + 1));
        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.deriveKeySm3(validIkm, null, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> HkdfUtil.deriveKeySm3(validIkm, null, "a", maxLength + 1));
    }
}
