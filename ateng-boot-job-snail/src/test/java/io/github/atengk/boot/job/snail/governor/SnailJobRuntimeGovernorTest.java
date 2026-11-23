package io.github.atengk.boot.job.snail.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnailJobRuntimeGovernor 节点级任务运行时动态治理总线单元测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
class SnailJobRuntimeGovernorTest {

    private SnailJobRuntimeGovernor governor;

    @BeforeEach
    void setUp() {
        governor = new SnailJobRuntimeGovernor();
    }

    @Test
    @DisplayName("测试普通任务暂停与恢复状态转换及对称性")
    void testPauseAndResume() {
        assertThat(governor.isPaused("orderJob")).isFalse();
        assertThat(governor.getPausedCount()).isEqualTo(0);

        // 首次暂停
        boolean firstPause = governor.pause("orderJob");
        assertThat(firstPause).isTrue();
        assertThat(governor.isPaused("orderJob")).isTrue();
        assertThat(governor.getPausedCount()).isEqualTo(1);

        // 重复暂停返回 false
        boolean secondPause = governor.pause("orderJob");
        assertThat(secondPause).isFalse();
        assertThat(governor.isPaused("orderJob")).isTrue();

        // 恢复执行
        boolean firstResume = governor.resume("orderJob");
        assertThat(firstResume).isTrue();
        assertThat(governor.isPaused("orderJob")).isFalse();
        assertThat(governor.getPausedCount()).isEqualTo(0);

        // 重复恢复返回 false
        boolean secondResume = governor.resume("orderJob");
        assertThat(secondResume).isFalse();
        assertThat(governor.isPaused("orderJob")).isFalse();
    }

    @Test
    @DisplayName("测试切片任务冒号分级感知 (暂停根任务自动同步暂停其所有切片)")
    void testSliceHierarchyPause() {
        governor.pause("batchJob");

        // 根任务自身为暂停
        assertThat(governor.isPaused("batchJob")).isTrue();

        // 其切片子任务自动感知暂停
        assertThat(governor.isPaused("batchJob:map")).isTrue();
        assertThat(governor.isPaused("batchJob:reduce")).isTrue();
        assertThat(governor.isPaused("batchJob:slice1")).isTrue();

        // 其他任务不受影响
        assertThat(governor.isPaused("otherJob")).isFalse();
        assertThat(governor.isPaused("otherJob:slice1")).isFalse();

        // 恢复根任务
        governor.resume("batchJob");
        assertThat(governor.isPaused("batchJob")).isFalse();
        assertThat(governor.isPaused("batchJob:map")).isFalse();
        assertThat(governor.isPaused("batchJob:reduce")).isFalse();
    }

    @Test
    @DisplayName("测试仅暂停特定单个切片任务而不影响根任务及其他切片")
    void testIndividualSlicePause() {
        governor.pause("batchJob:slice1");

        assertThat(governor.isPaused("batchJob:slice1")).isTrue();
        assertThat(governor.isPaused("batchJob")).isFalse();
        assertThat(governor.isPaused("batchJob:slice2")).isFalse();

        governor.resume("batchJob:slice1");
        assertThat(governor.isPaused("batchJob:slice1")).isFalse();
    }

    @Test
    @DisplayName("测试获取不可变暂停集合与对称全量恢复 (resumeAll)")
    void testResumeAllAndGetPausedHandlers() {
        governor.pause("job1");
        governor.pause("job2");
        governor.pause("job3");

        Set<String> paused = governor.getPausedHandlers();
        assertThat(paused).containsExactlyInAnyOrder("job1", "job2", "job3");
        assertThat(governor.getPausedCount()).isEqualTo(3);

        int resumed = governor.resumeAll();
        assertThat(resumed).isEqualTo(3);
        assertThat(governor.getPausedCount()).isEqualTo(0);
        assertThat(governor.getPausedHandlers()).isEmpty();
        assertThat(governor.isPaused("job1")).isFalse();
        assertThat(governor.isPaused("job2")).isFalse();
        assertThat(governor.isPaused("job3")).isFalse();

        // 二次调用 resumeAll 返回 0
        assertThat(governor.resumeAll()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试空值与空白字符输入边界防御")
    void testEmptyAndNullHandling() {
        assertThat(governor.pause(null)).isFalse();
        assertThat(governor.pause("")).isFalse();
        assertThat(governor.pause("   ")).isFalse();

        assertThat(governor.resume(null)).isFalse();
        assertThat(governor.resume("")).isFalse();

        assertThat(governor.isPaused(null)).isFalse();
        assertThat(governor.isPaused("")).isFalse();
        assertThat(governor.isPaused("   ")).isFalse();
    }
}
