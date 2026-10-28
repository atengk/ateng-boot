package io.github.atengk.boot.http.registrar;

import io.github.atengk.boot.http.annotation.EnableHttpInterfaces;
import io.github.atengk.boot.http.registrar.errorfixture.BlankNameClient;
import io.github.atengk.boot.http.registrar.fixture.SampleMetaClient;
import io.github.atengk.boot.http.registrar.fixture.SampleUserClient;
import io.github.atengk.boot.http.registrar.fixture.UnannotatedInterface;
import io.github.atengk.boot.http.registrar.fixture.explicit.SampleExplicitClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 声明式 HTTP 客户端注解与轻量扫描器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class HttpInterfaceRegistrarTest {

    private AnnotationConfigApplicationContext context;
    private MockWebServer mockWebServer;

    @AfterEach
    void tearDown() throws IOException {
        if (context != null) {
            context.close();
            context = null;
        }
        if (mockWebServer != null) {
            mockWebServer.shutdown();
            mockWebServer = null;
        }
    }

    @Test
    @DisplayName("测试指定 basePackages 路径自动扫描并注册 @HttpClient 接口")
    void testBasePackagesScanning() {
        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("test.user-service.url", "http://localhost:8080");
        context.setEnvironment(env);
        context.register(ScanConfig.class);
        context.refresh();

        // 1. 验证标注 @HttpClient 的接口被正确注册为 Bean
        assertTrue(context.containsBean("sampleUserClient"), "容器应包含 sampleUserClient Bean");
        SampleUserClient userClient = context.getBean(SampleUserClient.class);
        assertNotNull(userClient, "应成功获取 SampleUserClient 代理实例");

        // 2. 验证元注解派生的 @CustomClient 也被正确扫描并注册，但 @interface 本身不被注册
        assertTrue(context.containsBean("sampleMetaClient"), "容器应包含 sampleMetaClient Bean");
        assertFalse(context.containsBean("customClient"), "注解 @interface 自身不应被注册为 HTTP 客户端 Bean");
        SampleMetaClient metaClient = context.getBean(SampleMetaClient.class);
        assertNotNull(metaClient, "应成功获取 SampleMetaClient 代理实例");

        // 3. 验证未标注 @HttpClient 的普通接口被过滤
        assertFalse(context.containsBean("unannotatedInterface"), "普通接口不应被注册为 HTTP 客户端 Bean");
    }

    @Test
    @DisplayName("测试通过 clients 显式声明导入特定接口")
    void testExplicitClientsConfiguration() {
        context = new AnnotationConfigApplicationContext();
        context.register(ExplicitClientsConfig.class);
        context.refresh();

        assertTrue(context.containsBean("sampleExplicitClient"), "容器应包含显式声明的 sampleExplicitClient Bean");
        SampleExplicitClient explicitClient = context.getBean(SampleExplicitClient.class);
        assertNotNull(explicitClient, "应成功获取 SampleExplicitClient 代理实例");

        // 验证未在 clients 中的接口不会被扫描注入
        assertFalse(context.containsBean("sampleUserClient"), "未显式指定的接口不应被注册");
    }

    @Test
    @DisplayName("测试 HttpInterfaceFactoryBean 直接构造与 MockWebServer 端到端代理通信")
    void testFactoryBeanDirectlyWithMockWebServer() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // 模拟远端服务返回
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":1001,\"name\":\"Ateng\"}"));

        // 构造 HttpInterfaceFactoryBean
        HttpInterfaceFactoryBean<SampleUserClient> factoryBean = new HttpInterfaceFactoryBean<>();
        factoryBean.setInterfaceType(SampleUserClient.class);
        factoryBean.setClientName("user-service");
        factoryBean.setUrl(mockWebServer.url("/").toString());
        factoryBean.setApplicationContext(new StaticApplicationContext());
        factoryBean.afterPropertiesSet();

        // 验证 FactoryBean 基本契约
        assertEquals(SampleUserClient.class, factoryBean.getObjectType());
        assertTrue(factoryBean.isSingleton());

        SampleUserClient client = factoryBean.getObject();
        assertNotNull(client, "生成的代理对象不能为空");

        // 执行代理调用
        String result = client.getUserById(1001L);
        assertNotNull(result);
        assertTrue(result.contains("1001"));

        // 校验远端 MockWebServer 收到的实际请求
        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals("GET", recordedRequest.getMethod());
        assertEquals("/api/users/1001", recordedRequest.getPath());
    }

    @Test
    @DisplayName("测试空 clientName 注解配置触发 Fail-Fast 断言抛错")
    void testEdgeCaseBlankNameThrows() {
        context = new AnnotationConfigApplicationContext();
        context.register(BlankNameConfig.class);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> context.refresh(),
                "未声明 clientName 的接口在注册扫描期应触发 Fail-Fast 异常");
        assertTrue(exception.getMessage().contains("must not be blank"));
    }

    @Test
    @DisplayName("测试非接口类型传入 FactoryBean 抛出异常")
    void testEdgeCaseNonInterfaceFactoryBeanThrows() {
        HttpInterfaceFactoryBean<Object> factoryBean = new HttpInterfaceFactoryBean<>();
        @SuppressWarnings({"rawtypes", "unchecked"})
        Class<Object> nonInterfaceClass = (Class) String.class;
        factoryBean.setInterfaceType(nonInterfaceClass);
        factoryBean.setClientName("test-client");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                factoryBean::afterPropertiesSet,
                "非接口类型必须抛出 IllegalArgumentException");
        assertTrue(exception.getMessage().contains("must be an interface"));
    }

    @Configuration
    @EnableHttpInterfaces(basePackages = "io.github.atengk.boot.http.registrar.fixture")
    static class ScanConfig {
    }

    @Configuration
    @EnableHttpInterfaces(clients = SampleExplicitClient.class)
    static class ExplicitClientsConfig {
    }

    @Configuration
    @EnableHttpInterfaces(clients = BlankNameClient.class)
    static class BlankNameConfig {
    }

}
