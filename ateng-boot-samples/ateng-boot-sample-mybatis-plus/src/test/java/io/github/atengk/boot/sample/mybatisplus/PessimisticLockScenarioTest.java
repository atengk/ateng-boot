package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.mybatisplus.entity.TaskQueueEntity;
import io.github.atengk.boot.sample.mybatisplus.service.PessimisticLockScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 悲观并发锁与任务排队跳锁端到端集成测试
 * 验证：
 * 1. getByIdForUpdate 实体级排他加锁与状态流转
 * 2. 多线程并发基于 SKIP_LOCKED 抢占拉取任务，无死锁、无重叠、无冲突
 * 3. 单实体不存在时防御性返回 null
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@DisplayName("悲观排队锁与任务队列场景集成自验 (PessimisticLockScenario)")
class PessimisticLockScenarioTest {

    @Autowired
    private PessimisticLockScenarioService pessimisticLockScenarioService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanState() {
        UserContextHolder.clear();
        jdbcTemplate.execute("DELETE FROM task_queue");
    }

    @Test
    @DisplayName("1. 验证 getByIdForUpdate 悲观排他加锁检索与流转")
    void shouldLockSingleRowWithGetByIdForUpdate() {
        UserContextHolder.set("lock-operator");

        TaskQueueEntity entity = new TaskQueueEntity("DATA_SYNC_TASK", "PENDING", 0);
        pessimisticLockScenarioService.save(entity);
        Long taskId = entity.getId();
        assertThat(taskId).isNotNull();

        TaskQueueEntity updated = pessimisticLockScenarioService.processTaskWithLock(taskId);
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");

        TaskQueueEntity inDb = pessimisticLockScenarioService.getById(taskId);
        assertThat(inDb.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("2. 验证多线程并发下 SKIP_LOCKED 抢占任务队列零重叠消费")
    void shouldPollAndLockTasksConcurrentlyWithoutInterference() throws Exception {
        UserContextHolder.set("queue-producer");

        // 预置 6 条待处理任务
        List<TaskQueueEntity> initialTasks = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            initialTasks.add(new TaskQueueEntity("TASK_BATCH_" + i, "PENDING", 0));
        }
        pessimisticLockScenarioService.saveBatch(initialTasks);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        List<List<TaskQueueEntity>> workerResults = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 并发齐跑
                    List<TaskQueueEntity> polled = pessimisticLockScenarioService.pollAndLockTasks(3);
                    workerResults.add(polled);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // 统一发枪起跑
        startLatch.countDown();
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(workerResults).hasSize(2);

        List<TaskQueueEntity> list1 = workerResults.get(0);
        List<TaskQueueEntity> list2 = workerResults.get(1);

        assertThat(list1).hasSize(3);
        assertThat(list2).hasSize(3);

        // 验证两批任务无交集，零重叠抢占
        Set<Long> ids1 = new HashSet<>();
        list1.forEach(t -> ids1.add(t.getId()));

        Set<Long> ids2 = new HashSet<>();
        list2.forEach(t -> ids2.add(t.getId()));

        ids1.retainAll(ids2);
        assertThat(ids1).as("两个消费线程拉取的任务 ID 应当完全不相交").isEmpty();

        // 数据库中所有任务均已变为 PROCESSING
        long processingCount = pessimisticLockScenarioService.count();
        assertThat(processingCount).isEqualTo(6);
    }

    @Test
    @DisplayName("3. 验证单实体查询无结果时防御性返回 null")
    void shouldReturnNullDefensivelyWhenIdNotFound() {
        TaskQueueEntity entity = pessimisticLockScenarioService.getByIdForUpdate(999999999L);
        assertThat(entity).isNull();
    }
}
