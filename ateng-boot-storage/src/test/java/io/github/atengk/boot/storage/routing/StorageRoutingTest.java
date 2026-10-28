package io.github.atengk.boot.storage.routing;

import io.github.atengk.boot.storage.annotation.Storage;
import io.github.atengk.boot.storage.aspect.StorageAspect;
import io.github.atengk.boot.storage.context.StorageContextHolder;
import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态多存储源路由与切面、异步四维上下文透传单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("动态多源路由与切面及异步透传测试")
class StorageRoutingTest {

    @TempDir
    Path primaryDir;

    @TempDir
    Path secondaryDir;

    private LocalDriver primaryDriver;
    private LocalDriver secondaryDriver;
    private StorageDriverFactory factory;
    private StorageTemplate storageTemplate;

    @BeforeEach
    void setUp() {
        primaryDriver = new LocalDriver(primaryDir.toString());
        secondaryDriver = new LocalDriver(secondaryDir.toString());

        factory = new StorageDriverFactory("primary");
        factory.registerDriver("primary", primaryDriver);
        factory.registerDriver("secondary", secondaryDriver);

        storageTemplate = new StorageTemplate(primaryDriver, factory);
        StorageContextHolder.clear();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        StorageContextHolder.clear();
        MDC.clear();
        factory.destroy();
    }

    @Test
    @DisplayName("编程式 use(sourceKey) 切源实现多驱动物理隔离")
    void testProgrammaticRoutingIsolation() throws Exception {
        String filename = "data/hello.txt";
        byte[] content1 = "Content for primary".getBytes(StandardCharsets.UTF_8);
        byte[] content2 = "Content for secondary".getBytes(StandardCharsets.UTF_8);

        // 1. 默认向 primary 写入
        storageTemplate.upload(filename, new ByteArrayInputStream(content1));
        assertTrue(primaryDriver.exists(filename));
        assertFalse(secondaryDriver.exists(filename));

        // 2. 编程式切源向 secondary 写入
        storageTemplate.use("secondary").upload(filename, new ByteArrayInputStream(content2));
        assertTrue(secondaryDriver.exists(filename));

        // 3. 读取并验证内容隔离
        try (InputStream in1 = storageTemplate.download(filename)) {
            assertEquals("Content for primary", new String(in1.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in2 = storageTemplate.use("secondary").download(filename)) {
            assertEquals("Content for secondary", new String(in2.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("use 切源获取不存在的存储源时抛出 STORAGE_SOURCE_NOT_FOUND")
    void testUseNonExistentSource() {
        StorageException ex = assertThrows(StorageException.class, () -> storageTemplate.use("unknown-source"));
        assertEquals(StorageResultCode.STORAGE_SOURCE_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("上下文 StorageContextHolder 线程动态切源生效")
    void testContextHolderRouting() {
        String path = "context/test.txt";
        byte[] content = "Context routing".getBytes(StandardCharsets.UTF_8);

        // 默认写入 primary
        storageTemplate.upload(path, new ByteArrayInputStream(content));
        assertTrue(primaryDriver.exists(path));
        assertFalse(secondaryDriver.exists(path));

        // 切换上下文到 secondary
        StorageContextHolder.set("secondary");
        try {
            assertTrue(storageTemplate.exists(path) == false);
            storageTemplate.upload(path, new ByteArrayInputStream(content));
            assertTrue(secondaryDriver.exists(path));
        } finally {
            StorageContextHolder.clear();
        }

        // 清理后回退至 primary
        assertNull(StorageContextHolder.get());
        assertTrue(storageTemplate.exists(path));
    }

    @Test
    @DisplayName("声明式 @Storage 注解切面方法级与类级自动切源并严格恢复上下文")
    void testDeclarativeAspectRouting() {
        MockStorageService target = new MockStorageService(storageTemplate);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new StorageAspect());
        MockStorageService proxy = proxyFactory.getProxy();

        assertNull(StorageContextHolder.get());

        // 1. 调用方法级注解的方法（路由至 secondary）
        proxy.saveToSecondary("aspect/secondary.txt");
        assertTrue(secondaryDriver.exists("aspect/secondary.txt"));
        assertFalse(primaryDriver.exists("aspect/secondary.txt"));
        // 验证执行后上下文严格恢复为 null
        assertNull(StorageContextHolder.get());

        // 2. 调用普通方法（保持 primary）
        proxy.saveToPrimary("aspect/primary.txt");
        assertTrue(primaryDriver.exists("aspect/primary.txt"));
        assertFalse(secondaryDriver.exists("aspect/primary.txt"));
        assertNull(StorageContextHolder.get());
    }

    @Test
    @DisplayName("跨线程异步透传：StorageContextHolder 与 Core 四维上下文联动并在任务结束后自恢复")
    void testAsyncContextPropagation() throws ExecutionException, InterruptedException {
        StorageContextHolder.set("secondary");
        MDC.put("traceId", "trace-storage-12345");

        AtomicReference<String> workerStorageContext = new AtomicReference<>();
        AtomicReference<String> workerMdc = new AtomicReference<>();

        // 使用 StorageContextHolder.wrap 包装异步 Runnable
        Runnable asyncTask = StorageContextHolder.wrap(() -> {
            workerStorageContext.set(StorageContextHolder.get());
            workerMdc.set(MDC.get("traceId"));
        });

        // 提交到另外一个独立线程执行
        CompletableFuture<Void> future = CompletableFuture.runAsync(asyncTask);
        future.get();

        // 验证异步子线程精准还原了调用方父线程的存储上下文与 MDC 链路追踪标识
        assertEquals("secondary", workerStorageContext.get());
        assertEquals("trace-storage-12345", workerMdc.get());

        // 验证父线程现场保持完好
        assertEquals("secondary", StorageContextHolder.get());
        assertEquals("trace-storage-12345", MDC.get("traceId"));
    }

    @Test
    @DisplayName("StorageDriverFactory 单例注册表生命周期与优雅释放自验")
    void testDriverFactoryLifecycle() {
        assertTrue(factory.containsDriver("primary"));
        assertTrue(factory.containsDriver("secondary"));
        assertEquals("primary", factory.getPrimary());

        StorageDriver found = factory.getDriver("secondary");
        assertNotNull(found);
        assertEquals(secondaryDriver, found);

        // 测试调用 destroy 释放资源
        factory.destroy();
        assertTrue(factory.getAllDrivers().isEmpty());
    }

    /**
     * 用于切面测试的业务桩类
     */
    static class MockStorageService {
        private final StorageOperations storageTemplate;

        public MockStorageService(StorageOperations storageTemplate) {
            this.storageTemplate = storageTemplate;
        }

        @Storage("secondary")
        public void saveToSecondary(String path) {
            assertEquals("secondary", StorageContextHolder.get());
            byte[] bytes = "Secondary Content".getBytes(StandardCharsets.UTF_8);
            storageTemplate.upload(path, new ByteArrayInputStream(bytes));
        }

        public void saveToPrimary(String path) {
            assertNull(StorageContextHolder.get());
            byte[] bytes = "Primary Content".getBytes(StandardCharsets.UTF_8);
            storageTemplate.upload(path, new ByteArrayInputStream(bytes));
        }
    }
}
