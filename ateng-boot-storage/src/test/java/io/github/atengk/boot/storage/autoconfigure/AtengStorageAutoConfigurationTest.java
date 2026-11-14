package io.github.atengk.boot.storage.autoconfigure;

import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.core.context.AsyncContextSnapshotProvider;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.storage.context.StorageContextHolder;
import io.github.atengk.boot.storage.controller.LocalStorageDownloadController;
import io.github.atengk.boot.storage.controller.LocalStorageUploadController;
import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengStorageAutoConfiguration 自动装配测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("AtengStorageAutoConfiguration 自动装配测试")
class AtengStorageAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengStorageAutoConfiguration.class));

    @Test
    @DisplayName("默认非 Web 配置下自动装配 StorageDriver 与 StorageTemplate Bean")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StorageDriver.class);
            assertThat(context).hasSingleBean(StorageOperations.class);
            assertThat(context).hasSingleBean(StorageTemplate.class);
            assertThat(context).hasSingleBean(StorageProperties.class);
            assertThat(context).hasSingleBean(StorageGuard.class);
            assertThat(context).doesNotHaveBean(LocalStorageDownloadController.class);
        });
    }

    @Test
    @DisplayName("Servlet Web 环境下自动装配 LocalStorageDownloadController 端点")
    void testWebAutoConfiguration() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengStorageAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(StorageDriver.class);
                    assertThat(context).hasSingleBean(StorageTemplate.class);
                    assertThat(context).hasSingleBean(LocalStorageDownloadController.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.storage.enabled=false 时禁用自动装配")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.storage.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(StorageDriver.class);
                    assertThat(context).doesNotHaveBean(StorageOperations.class);
                    assertThat(context).doesNotHaveBean(StorageTemplate.class);
                    assertThat(context).doesNotHaveBean(LocalStorageDownloadController.class);
                });
    }

    @Test
    @DisplayName("用户自定义 StorageDriver 时不会被自动装配覆盖")
    void testCustomDriverTakesPrecedence(@TempDir Path tempDir) {
        contextRunner
                .withUserConfiguration(CustomDriverConfig.class)
                .withPropertyValues("test.custom.dir=" + tempDir.toString())
                .run(context -> {
                    assertThat(context).hasSingleBean(StorageDriver.class);
                    StorageDriver driver = context.getBean(StorageDriver.class);
                    assertThat(driver).isInstanceOf(LocalDriver.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.storage.type=s3 时自动装配 S3Client、S3Presigner 与 S3Driver")
    void testS3AutoConfiguration() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.storage.type=s3",
                        "ateng.boot.storage.s3.bucket=test-bucket",
                        "ateng.boot.storage.s3.endpoint=http://127.0.0.1:9000",
                        "ateng.boot.storage.s3.access-key=minioadmin",
                        "ateng.boot.storage.s3.secret-key=minioadmin",
                        "ateng.boot.storage.s3.region=us-east-1"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(software.amazon.awssdk.services.s3.S3Client.class);
                    assertThat(context).hasSingleBean(software.amazon.awssdk.services.s3.presigner.S3Presigner.class);
                    assertThat(context).hasSingleBean(StorageDriver.class);
                    assertThat(context.getBean(StorageDriver.class)).isInstanceOf(io.github.atengk.boot.storage.driver.S3Driver.class);
                    assertThat(context).hasSingleBean(StorageTemplate.class);
                    assertThat(context).doesNotHaveBean(LocalStorageDownloadController.class);
                });
    }

    @Test
    @DisplayName("配置多源 sources 模式下自动装配 StorageDriverFactory、StorageAspect 与 StorageTaskDecorator")
    void testMultiSourceAutoConfiguration(@TempDir Path tempDir1, @TempDir Path tempDir2) {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.storage.primary=source1",
                        "ateng.boot.storage.sources.source1.type=local",
                        "ateng.boot.storage.sources.source1.local.base-path=" + tempDir1.toString(),
                        "ateng.boot.storage.sources.source2.type=local",
                        "ateng.boot.storage.sources.source2.local.base-path=" + tempDir2.toString()
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.storage.factory.StorageDriverFactory.class);
                    io.github.atengk.boot.storage.factory.StorageDriverFactory factory =
                            context.getBean(io.github.atengk.boot.storage.factory.StorageDriverFactory.class);
                    assertThat(factory.getPrimary()).isEqualTo("source1");
                    assertThat(factory.containsDriver("source1")).isTrue();
                    assertThat(factory.containsDriver("source2")).isTrue();

                    assertThat(context).hasSingleBean(StorageDriver.class);
                    assertThat(context).hasSingleBean(StorageTemplate.class);
                    assertThat(context).hasSingleBean(io.github.atengk.boot.storage.aspect.StorageAspect.class);
                    assertThat(context).hasSingleBean(io.github.atengk.boot.storage.context.StorageTaskDecorator.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.storage.max-chunk-size 时正确绑定 DataSize 并透传至 LocalDriver")
    void testMaxChunkSizeBindingAndPropagation(@TempDir Path tempDir) {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.storage.type=local",
                        "ateng.boot.storage.local.base-path=" + tempDir.toString(),
                        "ateng.boot.storage.max-chunk-size=25MB"
                )
                .run(context -> {
                    StorageProperties props = context.getBean(StorageProperties.class);
                    assertThat(props.getMaxChunkSize()).isEqualTo(org.springframework.util.unit.DataSize.ofMegabytes(25));
                    LocalDriver localDriver = (LocalDriver) context.getBean(StorageDriver.class);
                    assertThat(localDriver.getMaxChunkSizeBytes()).isEqualTo(25L * 1024 * 1024);
                });
    }

    @AfterEach
    void clearContextHolders() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        StorageContextHolder.clear();
    }

    @Test
    @DisplayName("AtengStorageAutoConfiguration 显式标注 @AutoConfigureBefore(AtengAsyncAutoConfiguration.class)")
    void testAutoConfigureBeforeAtengAsyncAutoConfiguration() {
        AutoConfigureBefore annotation = AtengStorageAutoConfiguration.class.getAnnotation(AutoConfigureBefore.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains(AtengAsyncAutoConfiguration.class);
    }

    @Test
    @DisplayName("联动 AtengAsyncAutoConfiguration 自动注册 AsyncContextSnapshotProvider 并实现五维上下文透传与彻底清理")
    void testFiveDimensionalAsyncContextPropagationWithCoreAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengAsyncAutoConfiguration.class, AtengStorageAutoConfiguration.class))
                .withPropertyValues(
                        "ateng.boot.async.core-pool-size=1",
                        "ateng.boot.async.max-pool-size=1"
                )
                .run(context -> {
                    assertThat(context).hasBean("storageAsyncContextSnapshotProvider");
                    assertThat(context.getBean(AsyncContextSnapshotProvider.class))
                            .isInstanceOf(StorageContextHolder.StorageAsyncContextSnapshotProvider.class);

                    record CapturedContext(
                            String traceId,
                            String userId,
                            String tenantId,
                            boolean ignoreTenant,
                            String storageSource
                    ) {}

                    Executor taskExecutor = context.getBean("taskExecutor", Executor.class);

                    MDC.put("traceId", "trace-5d-001");
                    UserContextHolder.setUserId("user-1001");
                    TenantContextHolder.setTenantId("tenant-2002");
                    TenantContextHolder.setIgnoreTenant(true);
                    StorageContextHolder.set("minio-secure");

                    CapturedContext propagated = CompletableFuture.supplyAsync(() -> new CapturedContext(
                            MDC.get("traceId"),
                            UserContextHolder.getUserId(),
                            TenantContextHolder.getTenantId(),
                            TenantContextHolder.isIgnoreTenant(),
                            StorageContextHolder.getDataSource()
                    ), taskExecutor).get(5, TimeUnit.SECONDS);

                    assertThat(propagated).isEqualTo(new CapturedContext(
                            "trace-5d-001", "user-1001", "tenant-2002", true, "minio-secure"));

                    // 清理父线程所有上下文后复用同一单线程，验证工作线程 ThreadLocal 被彻底清理无残留
                    MDC.clear();
                    UserContextHolder.clear();
                    TenantContextHolder.clear();
                    StorageContextHolder.clear();

                    CapturedContext afterCleanup = CompletableFuture.supplyAsync(() -> new CapturedContext(
                            MDC.get("traceId"),
                            UserContextHolder.getExplicit(),
                            TenantContextHolder.getExplicit(),
                            TenantContextHolder.isIgnoreTenant(),
                            StorageContextHolder.getDataSource()
                    ), taskExecutor).get(5, TimeUnit.SECONDS);

                    assertThat(afterCleanup).isEqualTo(new CapturedContext(null, null, null, false, null));
                });
    }

    @Test
    @DisplayName("混合多源主源为 S3 且包含命名 local 副源时自动挂载本地预签名下载与直传端点")
    void testMixedS3PrimaryWithLocalSecondaryMountsLocalEndpoints(@TempDir Path archiveDir) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengStorageAutoConfiguration.class))
                .withPropertyValues(
                        "ateng.boot.storage.type=s3",
                        "ateng.boot.storage.primary=s3Main",
                        "ateng.boot.storage.sources.s3Main.type=s3",
                        "ateng.boot.storage.sources.s3Main.s3.bucket=primary-bucket",
                        "ateng.boot.storage.sources.s3Main.s3.endpoint=http://127.0.0.1:9000",
                        "ateng.boot.storage.sources.s3Main.s3.access-key=minioadmin",
                        "ateng.boot.storage.sources.s3Main.s3.secret-key=minioadmin",
                        "ateng.boot.storage.sources.s3Main.s3.region=us-east-1",
                        "ateng.boot.storage.sources.localArchive.type=local",
                        "ateng.boot.storage.sources.localArchive.local.base-path=" + archiveDir,
                        "ateng.boot.storage.sources.localArchive.local.domain=http://localhost:8080",
                        "ateng.boot.storage.sources.localArchive.local.secret-key=archive-key-2026"
                )
                .run(context -> {
                    StorageProperties props = context.getBean(StorageProperties.class);
                    assertThat(props.getLocal().isEndpointEnabled()).isTrue();
                    assertThat(context).hasSingleBean(LocalStorageDownloadController.class);
                    assertThat(context).hasSingleBean(LocalStorageUploadController.class);
                    assertThat(context.getBean(StorageDriver.class))
                            .isInstanceOf(io.github.atengk.boot.storage.driver.S3Driver.class);
                });
    }

    @Test
    @DisplayName("纯 S3 单源或全 S3 多源在 Servlet Web 环境下不挂载本地预签名下载与直传端点")
    void testPureS3WebDoesNotMountLocalEndpoints() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengStorageAutoConfiguration.class))
                .withPropertyValues(
                        "ateng.boot.storage.type=s3",
                        "ateng.boot.storage.s3.bucket=test-bucket",
                        "ateng.boot.storage.s3.endpoint=http://127.0.0.1:9000",
                        "ateng.boot.storage.s3.access-key=minioadmin",
                        "ateng.boot.storage.s3.secret-key=minioadmin",
                        "ateng.boot.storage.s3.region=us-east-1"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LocalStorageDownloadController.class);
                    assertThat(context).doesNotHaveBean(LocalStorageUploadController.class);
                });

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengStorageAutoConfiguration.class))
                .withPropertyValues(
                        "ateng.boot.storage.primary=s3Main",
                        "ateng.boot.storage.sources.s3Main.type=s3",
                        "ateng.boot.storage.sources.s3Main.s3.bucket=bucket-a",
                        "ateng.boot.storage.sources.s3Main.s3.endpoint=http://127.0.0.1:9000",
                        "ateng.boot.storage.sources.s3Main.s3.access-key=minioadmin",
                        "ateng.boot.storage.sources.s3Main.s3.secret-key=minioadmin",
                        "ateng.boot.storage.sources.s3Main.s3.region=us-east-1",
                        "ateng.boot.storage.sources.s3Backup.type=s3",
                        "ateng.boot.storage.sources.s3Backup.s3.bucket=bucket-b",
                        "ateng.boot.storage.sources.s3Backup.s3.endpoint=http://127.0.0.1:9000",
                        "ateng.boot.storage.sources.s3Backup.s3.access-key=minioadmin",
                        "ateng.boot.storage.sources.s3Backup.s3.secret-key=minioadmin",
                        "ateng.boot.storage.sources.s3Backup.s3.region=us-east-1"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LocalStorageDownloadController.class);
                    assertThat(context).doesNotHaveBean(LocalStorageUploadController.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.storage.local.endpoint-enabled=false 时单源与混合多源均禁用本地端点装配")
    void testLocalEndpointDisabledSuppressesBothControllers(@TempDir Path archiveDir) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengStorageAutoConfiguration.class))
                .withPropertyValues(
                        "ateng.boot.storage.type=local",
                        "ateng.boot.storage.local.base-path=" + archiveDir,
                        "ateng.boot.storage.local.endpoint-enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LocalStorageDownloadController.class);
                    assertThat(context).doesNotHaveBean(LocalStorageUploadController.class);
                });

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengStorageAutoConfiguration.class))
                .withPropertyValues(
                        "ateng.boot.storage.primary=s3Main",
                        "ateng.boot.storage.local.endpoint-enabled=false",
                        "ateng.boot.storage.sources.s3Main.type=s3",
                        "ateng.boot.storage.sources.s3Main.s3.bucket=primary-bucket",
                        "ateng.boot.storage.sources.s3Main.s3.endpoint=http://127.0.0.1:9000",
                        "ateng.boot.storage.sources.s3Main.s3.access-key=minioadmin",
                        "ateng.boot.storage.sources.s3Main.s3.secret-key=minioadmin",
                        "ateng.boot.storage.sources.s3Main.s3.region=us-east-1",
                        "ateng.boot.storage.sources.localArchive.type=local",
                        "ateng.boot.storage.sources.localArchive.local.base-path=" + archiveDir
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LocalStorageDownloadController.class);
                    assertThat(context).doesNotHaveBean(LocalStorageUploadController.class);
                });
    }

    @Configuration
    static class CustomDriverConfig {
        @Bean
        public StorageDriver customStorageDriver() {
            return new LocalDriver(System.getProperty("java.io.tmpdir"));
        }
    }
}
