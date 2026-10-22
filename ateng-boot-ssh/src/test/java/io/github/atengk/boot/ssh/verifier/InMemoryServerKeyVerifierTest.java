package io.github.atengk.boot.ssh.verifier;

import io.github.atengk.boot.ssh.enums.HostKeyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryServerKeyVerifier 纯内存服务端主机指纹校验器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("InMemoryServerKeyVerifier 纯内存指纹校验器单元测试")
class InMemoryServerKeyVerifierTest {

    @Test
    @DisplayName("测试 LENIENT 宽容自适应模式：首次信任记录内存，后续同公钥放行，公钥变更阻断")
    void testLenientPolicyBehavior() throws GeneralSecurityException {
        InMemoryServerKeyVerifier verifier = new InMemoryServerKeyVerifier(HostKeyPolicy.LENIENT);
        InetSocketAddress address = new InetSocketAddress("192.168.1.10", 22);

        KeyPair initialKeyPair = generateRsaKeyPair();
        KeyPair alteredKeyPair = generateRsaKeyPair();

        // 1. 首次连接：自动信任并记录
        boolean firstAttempt = verifier.verifyServerKey(null, address, initialKeyPair.getPublic());
        assertThat(firstAttempt).isTrue();
        assertThat(verifier.getKnownKeys()).hasSize(1);
        assertThat(verifier.getKnownKey("192.168.1.10:22")).isEqualTo(initialKeyPair.getPublic());

        // 2. 第二次连接（相同公钥）：校验通过
        boolean secondAttempt = verifier.verifyServerKey(null, address, initialKeyPair.getPublic());
        assertThat(secondAttempt).isTrue();

        // 3. 第三次连接（服务端公钥发生变更）：拦截阻断防中间人攻击
        boolean mitmAttempt = verifier.verifyServerKey(null, address, alteredKeyPair.getPublic());
        assertThat(mitmAttempt).isFalse();
    }

    @Test
    @DisplayName("测试 STRICT 严格校验模式：仅放行预先在内存名单中的指纹，未登记一律阻断")
    void testStrictPolicyBehavior() throws GeneralSecurityException {
        InMemoryServerKeyVerifier verifier = new InMemoryServerKeyVerifier(HostKeyPolicy.STRICT);
        InetSocketAddress address = new InetSocketAddress("10.0.0.5", 2222);

        KeyPair registeredKey = generateRsaKeyPair();
        KeyPair unregisteredKey = generateRsaKeyPair();

        // 未预注册时直接阻断
        boolean denied = verifier.verifyServerKey(null, address, registeredKey.getPublic());
        assertThat(denied).isFalse();

        // 手动预注册白名单
        verifier.registerKnownKey("10.0.0.5", 2222, registeredKey.getPublic());

        // 已预注册且匹配：通过
        boolean approved = verifier.verifyServerKey(null, address, registeredKey.getPublic());
        assertThat(approved).isTrue();

        // 已预注册但服务端返回不匹配公钥：阻断
        boolean mismatch = verifier.verifyServerKey(null, address, unregisteredKey.getPublic());
        assertThat(mismatch).isFalse();
    }

    @Test
    @DisplayName("测试 IGNORE 全局忽略模式：无条件放行任意服务端主机公钥")
    void testIgnorePolicyBehavior() throws GeneralSecurityException {
        InMemoryServerKeyVerifier verifier = new InMemoryServerKeyVerifier(HostKeyPolicy.IGNORE);
        InetSocketAddress address = new InetSocketAddress("unknown-host", 22);
        KeyPair keyPair = generateRsaKeyPair();

        boolean result = verifier.verifyServerKey(null, address, keyPair.getPublic());
        assertThat(result).isTrue();
        assertThat(verifier.getKnownKeys()).isEmpty(); // IGNORE 模式不消耗内存记录指纹
    }

    @Test
    @DisplayName("测试防卫空指针与清空指纹缓存")
    void testDefensiveAndClear() throws GeneralSecurityException {
        InMemoryServerKeyVerifier verifier = new InMemoryServerKeyVerifier();
        KeyPair keyPair = generateRsaKeyPair();

        // 服务端公钥为 null 时安全返回 false
        assertThat(verifier.verifyServerKey(null, new InetSocketAddress("1.1.1.1", 22), null)).isFalse();

        verifier.registerKnownKey("1.1.1.1:22", keyPair.getPublic());
        assertThat(verifier.getKnownKeys()).hasSize(1);

        verifier.clear();
        assertThat(verifier.getKnownKeys()).isEmpty();
    }

    private static KeyPair generateRsaKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
