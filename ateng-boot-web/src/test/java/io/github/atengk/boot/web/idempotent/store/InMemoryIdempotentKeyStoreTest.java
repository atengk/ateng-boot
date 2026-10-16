package io.github.atengk.boot.web.idempotent.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单机内存幂等存储引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("单机内存幂等存储引擎测试")
class InMemoryIdempotentKeyStoreTest {

    @Test
    @DisplayName("测试基础加锁与TTL过期失效")
    void shouldAcquireAndExpireLock() throws InterruptedException {
        InMemoryIdempotentKeyStore store = new InMemoryIdempotentKeyStore(100, Duration.ofSeconds(60));
        String key = "test:basic:1001";

        // 1. 首次加锁成功
        boolean firstLock = store.tryLock(key, 200, TimeUnit.MILLISECONDS);
        assertThat(firstLock).isTrue();

        // 2. 在 TTL 窗口内重复加锁失败
        boolean secondLock = store.tryLock(key, 200, TimeUnit.MILLISECONDS);
        assertThat(secondLock).isFalse();

        // 3. 等待超时后，锁应自动失效并允许重新加锁
        Thread.sleep(250);
        boolean thirdLock = store.tryLock(key, 200, TimeUnit.MILLISECONDS);
        assertThat(thirdLock).isTrue();
    }

    @Test
    @DisplayName("测试主动释放锁后可立即重试")
    void shouldUnlockAndAllowImmediateRetry() {
        InMemoryIdempotentKeyStore store = new InMemoryIdempotentKeyStore(100, Duration.ofSeconds(60));
        String key = "test:unlock:2001";

        assertThat(store.tryLock(key, 10, TimeUnit.SECONDS)).isTrue();
        assertThat(store.tryLock(key, 10, TimeUnit.SECONDS)).isFalse();

        // 模拟业务异常主动释放锁
        store.unlock(key);

        // 释放后应立即允许加锁
        assertThat(store.tryLock(key, 10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("高并发争用测试：多线程抢占同一Key只能有恰好1个成功")
    void shouldEnsureOnlyOneThreadSucceedsUnderHighConcurrency() throws InterruptedException {
        InMemoryIdempotentKeyStore store = new InMemoryIdempotentKeyStore(1000, Duration.ofSeconds(60));
        String key = "test:concurrent:3001";
        int threadCount = 50;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    if (store.tryLock(key, 5, TimeUnit.SECONDS)) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("测试容量软上限防爆与过期淘汰")
    void shouldEnforceCapacityLimitAndEvictExpiredKeys() throws InterruptedException {
        // 设置极小容量上限为 5
        InMemoryIdempotentKeyStore store = new InMemoryIdempotentKeyStore(5, Duration.ofSeconds(60));

        // 写入 5 个短时过期的 Key
        for (int i = 1; i <= 5; i++) {
            assertThat(store.tryLock("key:" + i, 100, TimeUnit.MILLISECONDS)).isTrue();
        }

        // 等待其过期
        Thread.sleep(150);

        // 写入第 6 个 Key，应触发淘汰并成功写入
        assertThat(store.tryLock("key:6", 5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("测试全量键未过期时按剩余TTL升序淘汰溢出键 (Soft LRU Guard)")
    void shouldEvictEarliestExpiringKeyWhenAllKeysNotExpired() {
        // 容量上限设为 3
        InMemoryIdempotentKeyStore store = new InMemoryIdempotentKeyStore(3, Duration.ofSeconds(60));

        // 写入 3 个未过期键，设置不同的 TTL (10s, 20s, 30s)
        assertThat(store.tryLock("guard:key:1", 10, TimeUnit.SECONDS)).isTrue();
        assertThat(store.tryLock("guard:key:2", 20, TimeUnit.SECONDS)).isTrue();
        assertThat(store.tryLock("guard:key:3", 30, TimeUnit.SECONDS)).isTrue();

        // 写入第 4 个未过期键，此时未到过期时间，应触发 Soft LRU Guard 淘汰最早过期的 guard:key:1
        assertThat(store.tryLock("guard:key:4", 50, TimeUnit.SECONDS)).isTrue();

        // 验证 guard:key:1 已被淘汰（重新抢占成功）
        assertThat(store.tryLock("guard:key:1", 10, TimeUnit.SECONDS)).isTrue();

        // 验证剩余较长 TTL 的 guard:key:2 和 guard:key:3 依然保持占用（无法加锁）
        assertThat(store.tryLock("guard:key:2", 20, TimeUnit.SECONDS)).isFalse();
        assertThat(store.tryLock("guard:key:3", 30, TimeUnit.SECONDS)).isFalse();
    }
}
