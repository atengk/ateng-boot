package io.github.atengk.boot.job.xxl.cleaner;

import com.xxl.job.core.log.XxlJobFileAppender;
import io.github.atengk.boot.job.xxl.autoconfigure.AtengXxlJobAutoConfiguration;
import io.github.atengk.boot.job.xxl.executor.AtengXxlJobSpringExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产加固型 XXL-Job 日志清理辅助器单元测试
 *
 * <p>验证工业级防崩特性：严格日期正则匹配、隐性系统文件忽略、非法目录防御与受管生命周期</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@DisplayName("XXL-Job 日志清理辅助器测试")
class AtengJobLogFileCleanHelperTest {

    private AtengJobLogFileCleanHelper cleanHelper;

    @BeforeEach
    void setUp() {
        cleanHelper = new AtengJobLogFileCleanHelper();
    }

    @AfterEach
    void tearDown() {
        if (cleanHelper != null) {
            cleanHelper.stop();
        }
    }

    @Test
    @DisplayName("清理测试：正确清理过期日期目录，并保留未过期目录")
    void testCleanExpiredAndPreserveUnexpired(@TempDir Path tempDir) throws IOException {
        LocalDate referenceDate = LocalDate.of(2026, 11, 1);
        long retentionDays = 30; // 30天前为 2026-10-02

        // 1. 创建过期目录 2026-09-01 (距今约 61 天，应被清理)
        Path expiredDir = Files.createDirectory(tempDir.resolve("2026-09-01"));
        Files.writeString(expiredDir.resolve("1001.log"), "sample log content 1001");
        Files.writeString(expiredDir.resolve("1002.log"), "sample log content 1002");

        // 2. 创建未过期目录 2026-10-20 (距今 12 天，应保留)
        Path unexpiredDir = Files.createDirectory(tempDir.resolve("2026-10-20"));
        Files.writeString(unexpiredDir.resolve("2001.log"), "sample log content 2001");

        // 3. 创建当天目录 2026-11-01 (应保留)
        Path todayDir = Files.createDirectory(tempDir.resolve("2026-11-01"));
        Files.writeString(todayDir.resolve("3001.log"), "sample log content 3001");

        // 执行清理
        int deletedCount = cleanHelper.clean(tempDir.toString(), retentionDays, referenceDate);

        // 断言
        assertEquals(1, deletedCount, "应恰好删除 1 个过期目录");
        assertFalse(Files.exists(expiredDir), "过期目录应被递归彻底删除");
        assertTrue(Files.exists(unexpiredDir), "未过期目录应被安全保留");
        assertTrue(Files.exists(todayDir), "当天目录应被安全保留");
    }

    @Test
    @DisplayName("防崩测试：包含带连字符的非日期目录与宿主隐性系统文件时零异常安全跳过")
    void testDefensiveIgnoreNonDateAndHiddenArtifacts(@TempDir Path tempDir) throws IOException {
        LocalDate referenceDate = LocalDate.of(2026, 11, 1);
        long retentionDays = 30;

        // 1. 创建官方源码易崩溃的带连字符非日期目录（如 CI sentinel 目录、普通临时目录）
        Path sentinelDir = Files.createDirectory(tempDir.resolve("system-commandline-sentinel-files"));
        Files.writeString(sentinelDir.resolve("sentinel.tmp"), "sentinel");

        Path nonDateDir = Files.createDirectory(tempDir.resolve("custom-job-temp-folder"));
        Files.writeString(nonDateDir.resolve("data.txt"), "data");

        // 2. 创建宿主操作系统特殊文件与隐藏文件
        Path desktopIni = Files.createFile(tempDir.resolve("desktop.ini"));
        Path dsStoreDir = Files.createDirectory(tempDir.resolve(".DS_Store"));
        Path hiddenDir = Files.createDirectory(tempDir.resolve(".hidden-cache"));

        // 3. 创建正则匹配但实际非法的异常日期目录 (如 2026-99-99)
        Path illegalDateDir = Files.createDirectory(tempDir.resolve("2026-99-99"));
        Files.writeString(illegalDateDir.resolve("error.log"), "error");

        // 4. 创建一个合法的过期目录确保正常清理流程仍运转
        Path expiredDir = Files.createDirectory(tempDir.resolve("2026-08-15"));
        Files.writeString(expiredDir.resolve("old.log"), "old log");

        // 执行清理，断言绝不抛出任何 ParseException 或未捕获异常
        int deleted = assertDoesNotThrow(() ->
                cleanHelper.clean(tempDir.toString(), retentionDays, referenceDate)
        );

        assertEquals(1, deleted, "应仅删除合法的过期目录");
        assertFalse(Files.exists(expiredDir), "合法过期目录应被清理");
        assertTrue(Files.exists(sentinelDir), "带连字符的非日期目录必须完好保留且不引发异常");
        assertTrue(Files.exists(nonDateDir), "普通非日期目录必须完好保留");
        assertTrue(Files.exists(desktopIni), "系统特殊文件必须完好保留");
        assertTrue(Files.exists(dsStoreDir), "隐藏文件必须完好保留");
        assertTrue(Files.exists(hiddenDir), "隐藏目录必须完好保留");
        assertTrue(Files.exists(illegalDateDir), "非法日期目录必须被安全跳过而不崩溃");
    }

    @Test
    @DisplayName("边界测试：空路径、无效保留天数与不存在目录安全防御")
    void testBoundaryConditions(@TempDir Path tempDir) {
        assertEquals(0, cleanHelper.clean(null, 30));
        assertEquals(0, cleanHelper.clean("", 30));
        assertEquals(0, cleanHelper.clean("   ", 30));
        assertEquals(0, cleanHelper.clean(tempDir.resolve("non_existing_dir").toString(), 30));
        assertEquals(0, cleanHelper.clean(tempDir.toString(), 0));
        assertEquals(0, cleanHelper.clean(tempDir.toString(), -10));
    }

    @Test
    @DisplayName("生命周期测试：start 与 stop 平滑启停与状态切换")
    void testLifecycleStartAndStop() {
        assertFalse(cleanHelper.isRunning(), "初始状态应未运行");

        // start 时若 retentionDays < 1 则不启动
        cleanHelper.start(0);
        assertFalse(cleanHelper.isRunning(), "retentionDays <= 0 时不应启动");

        // 正常启动
        cleanHelper.start(7);
        assertTrue(cleanHelper.isRunning(), "有效天数启动后应处于运行态");

        // 重复 start 应幂等无害
        cleanHelper.start(7);
        assertTrue(cleanHelper.isRunning(), "重复启动应保持运行态");

        // stop 停止
        cleanHelper.stop();
        assertFalse(cleanHelper.isRunning(), "停止后应处于停止态");

        // 重复 stop 幂等
        assertDoesNotThrow(() -> cleanHelper.stop());
    }

    @Test
    @DisplayName("受管调度托管测试：传入外部 ThreadPoolTaskScheduler 执行清理并 stop 后外部调度器保持活跃")
    void testExternalTaskSchedulerLifecycleWithoutMisclosing(@TempDir Path tempDir) throws Exception {
        ThreadPoolTaskScheduler externalScheduler = new ThreadPoolTaskScheduler();
        externalScheduler.setPoolSize(2);
        externalScheduler.setThreadNamePrefix("external-test-scheduler-");
        externalScheduler.initialize();

        String previousLogPath = XxlJobFileAppender.getLogPath();
        AtengJobLogFileCleanHelper helper = new AtengJobLogFileCleanHelper(externalScheduler);
        try {
            XxlJobFileAppender.initLogPath(tempDir.toString());
            Path expiredDir = Files.createDirectory(tempDir.resolve("2020-01-01"));
            Files.writeString(expiredDir.resolve("old.log"), "expired content");

            helper.start(7);
            assertThat(helper.isRunning()).isTrue();

            // 轮询等待调度触发清理过期目录（最多 3 秒）
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (Files.exists(expiredDir) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(Files.exists(expiredDir)).isFalse();

            // stop 仅取消自身调度任务，外部共享调度器必须保持活跃不被误关
            helper.stop();
            assertThat(helper.isRunning()).isFalse();
            assertThat(externalScheduler.getScheduledExecutor().isShutdown()).isFalse();

            // 二次基于同一外部调度器重启动应正常工作
            helper.start(7);
            assertThat(helper.isRunning()).isTrue();
            helper.stop();
            assertThat(externalScheduler.getScheduledExecutor().isShutdown()).isFalse();
        } finally {
            helper.stop();
            if (previousLogPath != null) {
                XxlJobFileAppender.initLogPath(previousLogPath);
            }
            externalScheduler.shutdown();
        }
    }

    @Test
    @DisplayName("受管调度托管测试：传入外部 ScheduledExecutorService 执行清理并 stop 后不被误关")
    void testExternalScheduledExecutorServiceLifecycleWithoutMisclosing(@TempDir Path tempDir) throws Exception {
        ScheduledThreadPoolExecutor externalExecutor = new ScheduledThreadPoolExecutor(1);
        String previousLogPath = XxlJobFileAppender.getLogPath();
        AtengJobLogFileCleanHelper helper = new AtengJobLogFileCleanHelper((ScheduledExecutorService) externalExecutor);
        try {
            XxlJobFileAppender.initLogPath(tempDir.toString());
            Path expiredDir = Files.createDirectory(tempDir.resolve("2019-12-31"));
            Files.writeString(expiredDir.resolve("old.log"), "old");

            helper.start(7);
            assertThat(helper.isRunning()).isTrue();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (Files.exists(expiredDir) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(Files.exists(expiredDir)).isFalse();

            helper.stop();
            assertThat(helper.isRunning()).isFalse();
            assertThat(externalExecutor.isShutdown()).isFalse();
        } finally {
            helper.stop();
            if (previousLogPath != null) {
                XxlJobFileAppender.initLogPath(previousLogPath);
            }
            externalExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发压测：@TempDir 下多线程交错触发 AtengJobLogFileCleanHelper start/stop/clean 无死锁无状态错乱")
    void testConcurrentStartStopAndCleanUnderTempDir(@TempDir Path tempDir) throws Exception {
        ThreadPoolTaskExecutor callerPool = new ThreadPoolTaskExecutor();
        callerPool.setCorePoolSize(8);
        callerPool.setMaxPoolSize(8);
        callerPool.setThreadNamePrefix("clean-concurrency-");
        callerPool.initialize();

        String previousLogPath = XxlJobFileAppender.getLogPath();
        AtengJobLogFileCleanHelper concurrentHelper = new AtengJobLogFileCleanHelper();
        try {
            XxlJobFileAppender.initLogPath(tempDir.toString());
            for (int i = 1; i <= 10; i++) {
                Path oldDir = Files.createDirectory(tempDir.resolve(String.format("2020-01-%02d", i)));
                Files.writeString(oldDir.resolve("job.log"), "old");
            }

            CountDownLatch gate = new CountDownLatch(1);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final int idx = i;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    if (idx % 3 == 0) {
                        concurrentHelper.start(7);
                    } else if (idx % 3 == 1) {
                        concurrentHelper.clean(tempDir.toString(), 7);
                    } else {
                        concurrentHelper.stop();
                    }
                }, callerPool));
            }
            gate.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
            concurrentHelper.stop();
            assertThat(concurrentHelper.isRunning()).isFalse();
        } finally {
            concurrentHelper.stop();
            if (previousLogPath != null) {
                XxlJobFileAppender.initLogPath(previousLogPath);
            }
            callerPool.shutdown();
        }
    }

    @Test
    @DisplayName("自动装配传递测试：AtengXxlJobAutoConfiguration 将容器 ThreadPoolTaskScheduler 传递至执行器")
    void testAutoConfigurationPassesManagedTaskScheduler(@TempDir Path tempDir) throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengXxlJobAutoConfiguration.class))
                .withUserConfiguration(ExternalSchedulerConfig.class)
                .withPropertyValues(
                        "ateng.boot.job.xxl.admin-addresses=http://127.0.0.1:8080/xxl-job-admin",
                        "ateng.boot.job.xxl.port=0",
                        "ateng.boot.job.xxl.log-path=" + tempDir.toAbsolutePath().toString().replace("\\", "/")
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("xxlJobExecutor");
                    AtengXxlJobSpringExecutor executor = context.getBean(AtengXxlJobSpringExecutor.class);
                    ThreadPoolTaskScheduler expectedScheduler = context.getBean(ThreadPoolTaskScheduler.class);
                    assertThat(executor.getTaskScheduler()).isSameAs(expectedScheduler);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ExternalSchedulerConfig {
        @Bean(destroyMethod = "shutdown")
        public ThreadPoolTaskScheduler customTaskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("spring-managed-scheduler-");
            scheduler.initialize();
            return scheduler;
        }
    }

}
