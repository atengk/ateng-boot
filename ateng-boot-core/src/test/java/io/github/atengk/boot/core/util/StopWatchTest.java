package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.model.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务耗时秒表 StopWatch 单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("高精度耗时秒表 StopWatch 测试")
class StopWatchTest {

    @Test
    @DisplayName("多分段任务计时与统计报表验证")
    void shouldTrackMultipleTaskSplits() throws Exception {
        StopWatch sw = new StopWatch("order-pipeline");
        assertThat(sw.getId()).isEqualTo("order-pipeline");
        assertThat(sw.isRunning()).isFalse();

        // 任务 1
        sw.start("validation");
        assertThat(sw.isRunning()).isTrue();
        assertThat(sw.currentTaskName()).isEqualTo("validation");
        Thread.sleep(30);
        sw.stop();
        assertThat(sw.isRunning()).isFalse();
        assertThat(sw.getLastTaskTimeMillis()).isGreaterThanOrEqualTo(25L);

        // 任务 2
        sw.start("persistence");
        Thread.sleep(50);
        sw.stop();

        assertThat(sw.getTaskCount()).isEqualTo(2);
        assertThat(sw.getTotalTimeMillis()).isGreaterThanOrEqualTo(75L);
        assertThat(sw.getTotalTimeSeconds()).isGreaterThan(0.05);

        StopWatch.TaskInfo[] tasks = sw.getTaskInfo();
        assertThat(tasks).hasSize(2);
        assertThat(tasks[0].taskName()).isEqualTo("validation");
        assertThat(tasks[1].taskName()).isEqualTo("persistence");

        String pretty = sw.prettyPrint();
        assertThat(pretty).contains("order-pipeline").contains("validation").contains("persistence");
    }

    @Test
    @DisplayName("状态异常校验与防御")
    void shouldThrowExceptionOnIllegalStates() {
        StopWatch sw = new StopWatch();

        // 未启动即调用 stop 抛出异常
        assertThatThrownBy(sw::stop)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未启动");

        // 未记录任何任务即获取最后一次耗时抛出异常
        assertThatThrownBy(sw::getLastTaskTimeNanos)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未记录任何任务耗时");

        sw.start("taskA");
        // 尚未 stop 即再次 start 抛出异常
        assertThatThrownBy(() -> sw.start("taskB"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("秒表已在运行中");
        sw.stop();
    }

    @Test
    @DisplayName("静态函数式耗时测量门面验证")
    void shouldMeasureClosures() {
        long elapsedMillis = StopWatch.measureMillis(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
        });
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(15L);

        long elapsedNanos = StopWatch.measureNanos(() -> {});
        assertThat(elapsedNanos).isGreaterThanOrEqualTo(0L);

        Pair<String, Long> result = StopWatch.measure(() -> {
            try {
                Thread.sleep(15);
            } catch (InterruptedException ignored) {
            }
            return "computed-value";
        });
        assertThat(result.getLeft()).isEqualTo("computed-value");
        assertThat(result.getRight()).isGreaterThanOrEqualTo(10L);

        Pair<Integer, Long> nanosResult = StopWatch.measureNanos(() -> 42);
        assertThat(nanosResult.getLeft()).isEqualTo(42);
        assertThat(nanosResult.getRight()).isGreaterThan(0L);
    }
}
