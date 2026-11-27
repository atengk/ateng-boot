package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.core.util.Sm2Util;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Sm2Engine} 曲线参数、标量运算与跨模块互操作单元测试（含 GitHub #1183 回归验证）
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("Sm2Engine 256 位曲线阶常量、标量边界及 Sm2Util 互操作测试")
class Sm2EngineTest {

    @Test
    @DisplayName("#1183 回归: 曲线阶常量 N 必须为 GB/T 32918 标准 256 位")
    void shouldMatchStandard256BitOrderConstant() {
        assertThat(Sm2Engine.N.bitLength()).isEqualTo(256);
        assertThat(Sm2Engine.N.toString(16))
                .isEqualTo("fffffffeffffffffffffffffffffffff7203df6b21c6052b53bbf40939d54123");
        assertThat(Sm2Engine.N).isEqualTo(Sm2Util.N);
        assertThat(Sm2Engine.isOnCurve(Sm2Engine.G)).isTrue();
    }

    @Test
    @DisplayName("#1183 回归: 标量乘法边界 [1]G=G, [N]G=INFINITY, [N-1]G=-G, G+[N-1]G=INFINITY")
    void shouldSatisfyScalarMultiplicationBoundaries() {
        assertThat(Sm2Engine.multiply(BigInteger.ONE, Sm2Engine.G)).isEqualTo(Sm2Engine.G);
        assertThat(Sm2Engine.multiply(Sm2Engine.N, Sm2Engine.G).isInfinity()).isTrue();

        Sm2Engine.Point nMinusOneG = Sm2Engine.multiply(Sm2Engine.N.subtract(BigInteger.ONE), Sm2Engine.G);
        assertThat(Sm2Engine.isOnCurve(nMinusOneG)).isTrue();
        assertThat(nMinusOneG.getX()).isEqualTo(Sm2Engine.GX);
        assertThat(nMinusOneG.getY()).isEqualTo(Sm2Engine.P.subtract(Sm2Engine.GY));
        assertThat(Sm2Engine.add(Sm2Engine.G, nMinusOneG).isInfinity()).isTrue();
    }

    @Test
    @DisplayName("#1183 回归: 批量生成密钥对严格处于 [1, N-2] 且公钥在曲线上，并拒绝越界私钥")
    void shouldGenerateKeysInValidRangeAndRejectOutOfBoundsPrivateKeys() {
        BigInteger maxPrivate = Sm2Engine.N.subtract(BigInteger.TWO);
        for (int i = 0; i < 50; i++) {
            Sm2Engine.Sm2KeyPair keyPair = Sm2Engine.generateKeyPair();
            assertThat(keyPair.getPrivateKey()).isBetween(BigInteger.ONE, maxPrivate);
            assertThat(Sm2Engine.isOnCurve(keyPair.getPublicKey())).isTrue();
            assertThat(keyPair.getPrivateKeyHex()).hasSize(64);
            assertThat(keyPair.getPublicKeyHex()).hasSize(130).startsWith("04");
        }

        // 0、N-1、N、N+1 均不可作为合法私钥标量
        assertThatThrownBy(() -> Sm2Engine.parsePrivateKey("00".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sm2Engine.parsePrivateKey(Sm2Engine.N.subtract(BigInteger.ONE).toString(16)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sm2Engine.parsePrivateKey(Sm2Engine.N.toString(16)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sm2Engine.parsePrivateKey(Sm2Engine.N.add(BigInteger.ONE).toString(16)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sm2Engine.parsePrivateKey(
                java.util.Base64.getEncoder().encodeToString(
                        Sm2Engine.N.add(BigInteger.ONE).toByteArray())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("双向互操作: Sm2Engine 与 Core Sm2Util (C1C3C2 / ASN.1 DER) 无损互通")
    void shouldInteroperateWithCoreSm2Util() {
        Sm2Engine.Sm2KeyPair enginePair = Sm2Engine.generateKeyPair();
        byte[] plaintext = "sm2_engine_and_core_sm2_util_interop_2026".getBytes(StandardCharsets.UTF_8);

        // Sm2Engine 加密 -> Core Sm2Util 解密
        byte[] cipherFromEngine = Sm2Engine.encrypt(plaintext, enginePair.getPublicKey());
        byte[] derFromEngine = Sm2Engine.c1c3c2ToDer(cipherFromEngine);
        assertThat(Sm2Util.decrypt(cipherFromEngine, enginePair.getPrivateKey())).isEqualTo(plaintext);
        assertThat(Sm2Util.decrypt(derFromEngine, enginePair.getPrivateKey())).isEqualTo(plaintext);

        // Core Sm2Util 加密 -> Sm2Engine 解密
        Sm2Util.Sm2KeyPair corePair = Sm2Util.generateKeyPair();
        byte[] c1c3c2FromCore = Sm2Util.encrypt(plaintext, corePair.publicKey());
        byte[] derFromCore = Sm2Util.encryptToDer(plaintext, corePair.publicKey());
        assertThat(Sm2Engine.decrypt(c1c3c2FromCore, corePair.privateKey())).isEqualTo(plaintext);
        assertThat(Sm2Engine.decrypt(derFromCore, corePair.privateKey())).isEqualTo(plaintext);
    }
}
