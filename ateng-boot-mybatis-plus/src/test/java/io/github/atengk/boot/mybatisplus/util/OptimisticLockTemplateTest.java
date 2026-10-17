package io.github.atengk.boot.mybatisplus.util;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 乐观锁重试模板单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("OptimisticLockTemplate 单元测试")
class OptimisticLockTemplateTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Account {
        private Long id;
        private Integer balance;
        private Integer version;
    }

    @Test
    @DisplayName("测试首次更新即成功无需重试")
    void testDirectSuccess() {
        Account account = new Account(1L, 100, 1);
        AtomicInteger loadCount = new AtomicInteger(0);
        AtomicInteger updateCount = new AtomicInteger(0);

        Account result = OptimisticLockTemplate.execute(
                () -> {
                    loadCount.incrementAndGet();
                    return account;
                },
                acc -> {
                    updateCount.incrementAndGet();
                    acc.setBalance(acc.getBalance() - 20);
                    return true; // 模拟更新成功
                }
        );

        assertThat(result).isNotNull();
        assertThat(result.getBalance()).isEqualTo(80);
        assertThat(loadCount.get()).isEqualTo(1);
        assertThat(updateCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试并发冲突自旋重试并在第 3 次成功")
    void testRetryUntilSuccess() {
        AtomicInteger versionHolder = new AtomicInteger(1);
        AtomicInteger attempts = new AtomicInteger(0);

        Account result = OptimisticLockTemplate.execute(
                () -> new Account(2L, 500, versionHolder.get()),
                acc -> {
                    int count = attempts.incrementAndGet();
                    if (count < 3) {
                        // 模拟并发更新导致版本递增，当前更新失败
                        versionHolder.incrementAndGet();
                        return false;
                    }
                    // 第 3 次成功
                    acc.setBalance(acc.getBalance() - 50);
                    return true;
                },
                5,
                Duration.ofMillis(10)
        );

        assertThat(result).isNotNull();
        assertThat(result.getBalance()).isEqualTo(450);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("测试超过最大重试次数抛出 OptimisticLockConflictException")
    void testExceedMaxAttemptsThrowsException() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> OptimisticLockTemplate.execute(
                () -> new Account(3L, 200, 1),
                acc -> {
                    attempts.incrementAndGet();
                    return false; // 始终模拟更新冲突
                },
                3,
                Duration.ofMillis(5)
        )).isInstanceOf(OptimisticLockTemplate.OptimisticLockConflictException.class)
          .hasMessageContaining("Optimistic lock conflict detected");

        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("测试加载实体为空时抛出参数异常")
    void testNullLoaderEntity() {
        assertThatThrownBy(() -> OptimisticLockTemplate.execute(
                () -> null,
                acc -> true
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("OptimisticLock loader returned null entity");
    }
}
