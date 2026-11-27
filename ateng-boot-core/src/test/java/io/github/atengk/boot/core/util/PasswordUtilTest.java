package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PasswordUtil} 纯原生 PBKDF2 密码慢哈希与安全校验单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@DisplayName("PasswordUtil 密码慢哈希工具测试")
class PasswordUtilTest {

    @Test
    @DisplayName("相同明文多次哈希应生成不同随机盐密文，且均能精确匹配并拒绝错误口令")
    void shouldHashWithRandomSaltAndMatchPassword() {
        String raw = "Ateng#Boot2026!";

        String encoded1 = PasswordUtil.hash(raw);
        String encoded2 = PasswordUtil.hash(raw);

        assertNotNull(encoded1);
        assertNotNull(encoded2);
        assertNotEquals(encoded1, encoded2);
        assertTrue(encoded1.startsWith("$pbkdf2-sha256$310000$"));
        assertEquals(5, encoded1.split("\\$", -1).length);

        assertTrue(PasswordUtil.matches(raw, encoded1));
        assertTrue(PasswordUtil.matches(raw, encoded2));
        assertFalse(PasswordUtil.matches("WrongPassword!", encoded1));
    }

    @Test
    @DisplayName("有效口令应完整保留首尾空格而不被错误裁剪")
    void shouldPreserveLeadingAndTrailingWhitespaceInValidPassword() {
        String rawWithSpaces = "  AtengSecret2026  ";
        String encoded = PasswordUtil.hash(rawWithSpaces);

        assertTrue(PasswordUtil.matches(rawWithSpaces, encoded));
        assertFalse(PasswordUtil.matches("AtengSecret2026", encoded));
    }

    @Test
    @DisplayName("空口令、空白串及越界迭代次数在生成哈希时应快速失败并抛出 IllegalArgumentException")
    void shouldFailFastOnInvalidInputsWhenHashing() {
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash((CharSequence) null));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash(""));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash("   \t\n  "));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash("ValidPass123", PasswordUtil.MIN_ITERATIONS - 1));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash("ValidPass123", PasswordUtil.MAX_ITERATIONS + 1));
    }

    @Test
    @DisplayName("matches 面对空输入、畸形哈希串、未知前缀、非法 Base64 与防 DoS 越界迭代数应安全返回 false")
    void shouldReturnFalseSafelyForMalformedInputsInMatches() {
        String validHash = PasswordUtil.hash("Ateng#2026");

        // 空密码 / 空白密码 / 空哈希串
        assertFalse(PasswordUtil.matches((CharSequence) null, validHash));
        assertFalse(PasswordUtil.matches("", validHash));
        assertFalse(PasswordUtil.matches("   ", validHash));
        assertFalse(PasswordUtil.matches("Ateng#2026", null));
        assertFalse(PasswordUtil.matches("Ateng#2026", ""));
        assertFalse(PasswordUtil.matches("Ateng#2026", "   "));

        // 畸形分段与不支持算法前缀
        assertFalse(PasswordUtil.matches("Ateng#2026", "not-a-valid-hash"));
        assertFalse(PasswordUtil.matches("Ateng#2026", "$bcrypt$10$abc$def"));
        assertFalse(PasswordUtil.matches("Ateng#2026", "pbkdf2-sha256$310000$abc$def"));
        assertFalse(PasswordUtil.matches("Ateng#2026", "$pbkdf2-sha256$310000$onlySalt"));
        assertFalse(PasswordUtil.matches("Ateng#2026", "$pbkdf2-sha256$310000$a$b$extra"));

        // 非法迭代次数 / 防 DoS 越界迭代次数
        assertFalse(PasswordUtil.matches("Ateng#2026", "$pbkdf2-sha256$abc$aaaaaaaaaaaaaaaaaaaaaa$bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertFalse(PasswordUtil.matches("Ateng#2026", "$pbkdf2-sha256$999$aaaaaaaaaaaaaaaaaaaaaa$bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertFalse(PasswordUtil.matches("Ateng#2026", "$pbkdf2-sha256$2147483647$aaaaaaaaaaaaaaaaaaaaaa$bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));

        // 非法 Base64 字符或盐/摘要字节长度非法
        assertFalse(PasswordUtil.matches("Ateng#2026", "$pbkdf2-sha256$310000$!@#$invalid$bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertFalse(PasswordUtil.matches("Ateng#2026", "$pbkdf2-sha256$310000$$bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertFalse(PasswordUtil.matches("Ateng#2026", "$pbkdf2-sha256$310000$aaaaaaaaaaaaaaaaaaaaaa$YQ"));
    }

    @Test
    @DisplayName("应与独立标准 PBKDF2-HMAC-SHA256 测试向量精确匹配并支持迭代升级检测")
    void shouldMatchIndependentVectorAndDetectUpgradeNeeds() {
        // 独立由 OpenSSL PBKDF2-HMAC-SHA256 派生的已知测试向量（salt="0123456789abcdef", iterations=10000）
        String knownVector = "$pbkdf2-sha256$10000$MDEyMzQ1Njc4OWFiY2RlZg$PB3CtQIjS0w5PPhYdG0j1nUY1LovLby0w3leLCp-TUQ";

        assertTrue(PasswordUtil.matches("AtengTest#2026", knownVector));
        assertFalse(PasswordUtil.matches("AtengTest#2027", knownVector));

        // 迭代升级判定：低于默认 310_000 次需要升级
        assertTrue(PasswordUtil.needsUpgrade(knownVector));
        assertTrue(PasswordUtil.needsUpgrade(knownVector, 20_000));
        assertFalse(PasswordUtil.needsUpgrade(knownVector, 10_000));

        // 默认迭代次数生成的新哈希不需要升级；若目标提高则需要升级
        String currentHash = PasswordUtil.hash("AtengTest#2026");
        assertFalse(PasswordUtil.needsUpgrade(currentHash));
        assertTrue(PasswordUtil.needsUpgrade(currentHash, PasswordUtil.DEFAULT_ITERATIONS + 10_000));

        // 畸形或空串安全返回 false；非法 targetIterations 快速失败
        assertFalse(PasswordUtil.needsUpgrade(null));
        assertFalse(PasswordUtil.needsUpgrade(""));
        assertFalse(PasswordUtil.needsUpgrade("$bcrypt$10$abc$def"));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.needsUpgrade(currentHash, PasswordUtil.MIN_ITERATIONS - 1));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.needsUpgrade(currentHash, PasswordUtil.MAX_ITERATIONS + 1));
    }

    @Test
    @DisplayName("应支持 char[] 重载并不篡改调用方原始字符数组")
    void shouldSupportCharArrayOverloadsWithoutMutatingCallerArray() {
        char[] rawChars = "Ateng#Char2026".toCharArray();
        char[] snapshot = rawChars.clone();

        String encoded = PasswordUtil.hash(rawChars, 20_000);
        assertArrayEquals(snapshot, rawChars);
        assertTrue(PasswordUtil.matches(rawChars, encoded));
        assertTrue(PasswordUtil.matches("Ateng#Char2026", encoded));
        assertArrayEquals(snapshot, rawChars);

        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash((char[]) null));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash(new char[0]));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash("   ".toCharArray()));
        assertFalse(PasswordUtil.matches((char[]) null, encoded));
        assertFalse(PasswordUtil.matches("   ".toCharArray(), encoded));
    }

    @Test
    @DisplayName("高并发多线程调用 hash 与 matches 应保持线程安全且零交叉污染")
    void shouldBeThreadSafeUnderConcurrentAccess() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                final String password = "UserPass#" + i + "@2026";
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    String encoded = PasswordUtil.hash(password, 20_000);
                    return PasswordUtil.matches(password, encoded)
                            && !PasswordUtil.matches(password + "-wrong", encoded);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertTrue(future.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("constantTimeDummyCheck: 账号不存在防枚举伪校验恒定返回 false 且不篡改入参")
    void shouldExecuteConstantTimeDummyCheckSafely() {
        assertFalse(PasswordUtil.constantTimeDummyCheck("SomePassword#2026", 10_000));
        assertFalse(PasswordUtil.constantTimeDummyCheck((CharSequence) null, 10_000));
        assertFalse(PasswordUtil.constantTimeDummyCheck("", 10_000));
        assertFalse(PasswordUtil.constantTimeDummyCheck("   ", 10_000));

        char[] rawChars = "DummyPass#2026".toCharArray();
        char[] snapshot = rawChars.clone();
        assertFalse(PasswordUtil.constantTimeDummyCheck(rawChars, 10_000));
        assertArrayEquals(snapshot, rawChars);
        assertFalse(PasswordUtil.constantTimeDummyCheck((char[]) null, 10_000));
        assertFalse(PasswordUtil.constantTimeDummyCheck(new char[0], 10_000));

        // 默认迭代次数重载与越界参数校验
        assertFalse(PasswordUtil.constantTimeDummyCheck("DefaultIterPass#2026"));
        assertFalse(PasswordUtil.constantTimeDummyCheck("DefaultIterPass#2026".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordUtil.constantTimeDummyCheck("pass", PasswordUtil.MIN_ITERATIONS - 1));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordUtil.constantTimeDummyCheck("pass".toCharArray(), PasswordUtil.MAX_ITERATIONS + 1));
    }
}
