package io.github.atengk.boot.excel.async;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.excel.async.storage.LocalFileSystemStorage;
import io.github.atengk.boot.excel.autoconfigure.AtengExcelAutoConfiguration;
import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分布式 Redis 异步导出任务中心单元测试
 * 覆盖正常提交与进度感知、失败异常捕获、任务主动取消、执行租约超时探测自恢复与自动装配自适应降级
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("Redis 分布式异步导出任务中心测试")
class RedisAsyncExportTaskServiceTest {

    @TempDir
    Path tempDir;

    private final Map<String, String> redisDataStore = new ConcurrentHashMap<>();
    private StringRedisTemplate mockRedisTemplate;
    private ValueOperations<String, String> mockValueOperations;

    private RedisAsyncExportTaskService taskService;
    private LocalFileSystemStorage fileStorage;
    private ExcelProperties properties;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisDataStore.clear();

        mockRedisTemplate = mock(StringRedisTemplate.class);
        mockValueOperations = mock(ValueOperations.class);

        when(mockRedisTemplate.opsForValue()).thenReturn(mockValueOperations);

        // 模拟 Redis 的 get 与 set(key, value, timeout)
        when(mockValueOperations.get(anyString())).thenAnswer(inv -> redisDataStore.get(inv.getArgument(0, String.class)));

        doAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            String value = inv.getArgument(1, String.class);
            redisDataStore.put(key, value);
            return null;
        }).when(mockValueOperations).set(anyString(), anyString(), any(Duration.class));

        properties = new ExcelProperties();
        properties.getAsyncExport().setTempDir(tempDir.toString());
        properties.getAsyncExport().setCorePoolSize(2);
        properties.getAsyncExport().setMaxPoolSize(4);
        properties.getAsyncExport().setQueueCapacity(20);
        properties.getAsyncExport().setLeaseTimeout(Duration.ofMinutes(10));

        fileStorage = new LocalFileSystemStorage(properties);
        taskService = new RedisAsyncExportTaskService(properties, mockRedisTemplate, fileStorage);
    }

    @AfterEach
    void tearDown() {
        if (taskService != null) {
            taskService.destroy();
        }
        redisDataStore.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试正常异步导出：验证状态机流转、进度刷新、Redis 数据存储与文件生成")
    void testNormalRedisExportWorkflow() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        String taskId = taskService.submit("用户清单异步导出", out -> {
            DemoUser user = DemoUser.builder().id(101L).username("张三").age(25).build();
            ExcelUtil.export(out, Collections.singletonList(user), DemoUser.class);
            latch.countDown();
        });

        assertThat(taskId).isNotBlank();
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

        // 轮询等待任务标记为 SUCCESS
        long deadline = System.currentTimeMillis() + 8000;
        ExportTask task = taskService.getTask(taskId);
        while ((task == null || task.getStatus() == ExportTaskStatus.PENDING || task.getStatus() == ExportTaskStatus.PROCESSING)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            task = taskService.getTask(taskId);
        }

        assertThat(task).isNotNull();
        assertThat(task.getStatus()).isEqualTo(ExportTaskStatus.SUCCESS);
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getFileSize()).isNotNull().isGreaterThan(0L);
        assertThat(task.getDownloadUrl()).contains("/api/excel/async-download?taskId=" + taskId);
        assertThat(task.getStorageKey()).isNotBlank();

        // 验证从持久化存储介质读取文件并断言 Excel 内容
        try (InputStream in = fileStorage.getInputStream(taskId, task.getStorageKey())) {
            assertThat(in).isNotNull();
            List<DemoUser> users = ExcelUtil.read(in, DemoUser.class);
            assertThat(users).hasSize(1);
            assertThat(users.get(0).getUsername()).isEqualTo("张三");
        }
    }

    @Test
    @DisplayName("测试任务执行异常捕获：正确记录失败状态与错误描述")
    void testFailedRedisExportWorkflow() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        String taskId = taskService.submit("失败任务仿真", out -> {
            latch.countDown();
            throw new RuntimeException("模拟数据库查询超时异常");
        });

        assertThat(taskId).isNotBlank();
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

        long deadline = System.currentTimeMillis() + 8000;
        ExportTask task = taskService.getTask(taskId);
        while ((task == null || task.getStatus() == ExportTaskStatus.PENDING || task.getStatus() == ExportTaskStatus.PROCESSING)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            task = taskService.getTask(taskId);
        }

        assertThat(task).isNotNull();
        assertThat(task.getStatus()).isEqualTo(ExportTaskStatus.FAILED);
        assertThat(task.getErrorMsg()).contains("模拟数据库查询超时异常");
    }

    @Test
    @DisplayName("测试主动取消任务：状态置为 CANCELLED 并触发文件清理")
    void testCancelRedisExport() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch blockLatch = new CountDownLatch(1);

        String taskId = taskService.submit("可取消任务", out -> {
            startLatch.countDown();
            blockLatch.await(5, TimeUnit.SECONDS);
        });

        assertThat(startLatch.await(5, TimeUnit.SECONDS)).isTrue();

        boolean cancelled = taskService.cancel(taskId);
        assertThat(cancelled).isTrue();

        blockLatch.countDown();

        ExportTask task = taskService.getTask(taskId);
        assertThat(task).isNotNull();
        assertThat(task.getStatus()).isEqualTo(ExportTaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("测试执行节点宕机租约超时探测：PROCESSING 任务超期查询时自动流转为 FAILED")
    void testLeaseTimeoutDeadTaskDetection() {
        String taskId = "dead-task-999";
        // 模拟一个处于 PROCESSING 状态、但租约时间在 10 分钟前的死任务（模拟 Pod 宕机被 kill）
        ExportTask deadTask = ExportTask.builder()
                .taskId(taskId)
                .taskName("宕机残留任务")
                .status(ExportTaskStatus.PROCESSING)
                .progress(45)
                .createTime(LocalDateTime.now().minusMinutes(20))
                .leaseExpireTime(LocalDateTime.now().minusMinutes(5)) // 已超期 5 分钟
                .build();

        // 写入模拟 Redis
        String key = properties.getAsyncExport().getRedis().getKeyPrefix() + taskId;
        redisDataStore.put(key, JsonUtil.toJsonString(deadTask));

        // 外部节点调用 getTask 查询该任务
        ExportTask inspectedTask = taskService.getTask(taskId);

        assertThat(inspectedTask).isNotNull();
        // 应被租约守卫自动标记为 FAILED
        assertThat(inspectedTask.getStatus()).isEqualTo(ExportTaskStatus.FAILED);
        assertThat(inspectedTask.getErrorMsg()).contains("租约超期");
        assertThat(inspectedTask.getFinishTime()).isNotNull();

        // 验证 Redis 中存储的数据也已被原子同步为 FAILED
        String updatedJson = redisDataStore.get(key);
        ExportTask savedTask = JsonUtil.parseObject(updatedJson, ExportTask.class);
        assertThat(savedTask.getStatus()).isEqualTo(ExportTaskStatus.FAILED);
    }

    @Test
    @DisplayName("测试条件装配自适应降级：有 Redis 时装配 Redis 任务服务，无 Redis 时装配内存服务")
    void testAutoConfigurationAdaptation() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengExcelAutoConfiguration.class));

        // 1. 无 Redis 环境：装配内存实现 InMemoryAsyncExportTaskService
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AsyncExportTaskService.class);
            assertThat(context).hasSingleBean(InMemoryAsyncExportTaskService.class);
            assertThat(context).doesNotHaveBean(RedisAsyncExportTaskService.class);
        });

        // 2. 存在 Redis 环境：自适应升级装配 RedisAsyncExportTaskService
        contextRunner.withBean(StringRedisTemplate.class, () -> mockRedisTemplate)
                .run(context -> {
                    assertThat(context).hasSingleBean(AsyncExportTaskService.class);
                    assertThat(context).hasSingleBean(RedisAsyncExportTaskService.class);
                    assertThat(context).doesNotHaveBean(InMemoryAsyncExportTaskService.class);
                });
    }
}
