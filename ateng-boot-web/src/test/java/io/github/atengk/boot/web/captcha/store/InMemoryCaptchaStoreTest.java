package io.github.atengk.boot.web.captcha.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单机内存图形验证码存储单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@DisplayName("InMemoryCaptchaStore 单元测试")
class InMemoryCaptchaStoreTest {

    private InMemoryCaptchaStore store = new InMemoryCaptchaStore();

    @AfterEach
    void tearDown() {
        store.destroy();
    }

    @Test
    @DisplayName("保存验证码后首次 getAndDelete 返回答案，二次读取返回 null 实现严格单次消费")
    void shouldConsumeOnceOnGetAndDelete() {
        store.save("captcha:k1", "AB12", Duration.ofMinutes(2));

        String first = store.getAndDelete("captcha:k1");
        String second = store.getAndDelete("captcha:k1");

        assertThat(first).isEqualTo("AB12");
        assertThat(second).isNull();
    }

    @Test
    @DisplayName("验证码过期后 getAndDelete 返回 null 并自动剔除")
    void shouldReturnNullWhenExpired() throws InterruptedException {
        store.save("captcha:exp", "9988", Duration.ofMillis(30));
        Thread.sleep(60);

        String value = store.getAndDelete("captcha:exp");

        assertThat(value).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("主动 delete 后 getAndDelete 返回 null")
    void shouldDeleteExplicitly() {
        store.save("captcha:del", "XYZ9", Duration.ofMinutes(2));
        store.delete("captcha:del");

        assertThat(store.getAndDelete("captcha:del")).isNull();
    }

    @Test
    @DisplayName("超出内存容量软上限时触发过期清理与最老元素淘汰")
    void shouldEvictOldestWhenExceedingMaxCapacity() {
        store.destroy();
        store = new InMemoryCaptchaStore(10, Duration.ofSeconds(60), null);

        for (int i = 0; i < 15; i++) {
            store.save("captcha:cap:" + i, String.valueOf(i), Duration.ofMinutes(5));
        }

        assertThat(store.size()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("高并发线程同时消费同一 captchaKey 时仅有唯一线程命中答案")
    void shouldAllowOnlySingleWinnerUnderConcurrentGetAndDelete() throws Exception {
        store.save("captcha:race", "WIN1", Duration.ofMinutes(2));
        int threads = 16;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger winCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    startGate.await();
                    String val = store.getAndDelete("captcha:race");
                    if ("WIN1".equals(val)) {
                        winCount.incrementAndGet();
                    }
                    return val;
                });
            }
            List<Future<String>> futures = new ArrayList<>(threads);
            for (Callable<String> task : tasks) {
                futures.add(pool.submit(task));
            }
            startGate.countDown();
            for (Future<String> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(winCount.get()).isEqualTo(1);
        assertThat(store.getAndDelete("captcha:race")).isNull();
    }

    @Test
    @DisplayName("非法空参数或非正数 TTL 触发快速失败")
    void shouldFailFastOnInvalidArguments() {
        assertThatThrownBy(() -> store.save(" ", "1234", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save("captcha:k", " ", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save("captcha:k", "1234", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.getAndDelete(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
