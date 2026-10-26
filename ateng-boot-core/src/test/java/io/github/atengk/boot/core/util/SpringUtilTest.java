package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpringUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("SpringUtil 容器门面测试")
class SpringUtilTest {

    @Configuration
    static class TestConfig {
        @Bean
        public String sampleBean() {
            return "hello-spring-util";
        }

        @Bean
        public Integer portBean() {
            return 8080;
        }
    }

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        SpringUtil.clear();
        context = new AnnotationConfigApplicationContext();
        context.register(TestConfig.class);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", Map.of(
                        "server.port", "9090",
                        "app.name", "ateng-boot"
                ))
        );
        context.refresh();
        SpringUtil.setContext(context);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        SpringUtil.clear();
    }

    @Test
    @DisplayName("getBean: 按类型与名称获取 Bean 实例")
    void shouldGetBeanByTypeAndName() {
        String bean1 = SpringUtil.getBean(String.class);
        assertEquals("hello-spring-util", bean1);

        String bean2 = SpringUtil.getBean("sampleBean", String.class);
        assertEquals("hello-spring-util", bean2);

        Object bean3 = SpringUtil.getBean("portBean");
        assertEquals(8080, bean3);

        Map<String, String> beansOfType = SpringUtil.getBeansOfType(String.class);
        assertTrue(beansOfType.containsKey("sampleBean"));
    }

    @Test
    @DisplayName("getProperty: 读取环境配置属性")
    void shouldGetPropertiesFromEnvironment() {
        assertEquals("9090", SpringUtil.getProperty("server.port"));
        assertEquals(9090, SpringUtil.getProperty("server.port", Integer.class));
        assertEquals("ateng-boot", SpringUtil.getProperty("app.name"));

        assertEquals("default-value", SpringUtil.getProperty("non.exist.key", "default-value"));
        assertEquals(100, SpringUtil.getProperty("non.exist.num", Integer.class, 100));
    }

    @Test
    @DisplayName("publishEvent: 支持发布应用事件与 POJO 载荷事件")
    void shouldPublishApplicationEvent() {
        AtomicReference<String> received = new AtomicReference<>();
        context.addApplicationListener(event -> {
            if (event instanceof org.springframework.context.PayloadApplicationEvent<?> payloadEvent) {
                if (payloadEvent.getPayload() instanceof String msg) {
                    received.set(msg);
                }
            }
        });

        SpringUtil.publishEvent("test-event-payload");
        assertEquals("test-event-payload", received.get());
    }

    @Test
    @DisplayName("环境探针与动态单例 Bean 注册验证")
    void shouldProbeEnvironmentAndRegisterSingletonBean() {
        // 配置 active profile 为 dev
        context.getEnvironment().setActiveProfiles("dev");

        assertTrue(SpringUtil.isDev());
        assertFalse(SpringUtil.isProd());
        assertFalse(SpringUtil.isTest());

        assertEquals("9090", String.valueOf(SpringUtil.getServerPort()));
        assertEquals("", SpringUtil.getApplicationName());

        assertTrue(SpringUtil.containsBean("sampleBean"));
        assertFalse(SpringUtil.containsBean("nonExistentBean"));

        // 注入 beanFactory 验证 registerBean
        SpringUtil springUtil = new SpringUtil();
        springUtil.postProcessBeanFactory(context.getBeanFactory());

        SpringUtil.registerBean("dynamicService", "dynamic-service-instance");
        assertTrue(SpringUtil.containsBean("dynamicService"));
        assertEquals("dynamic-service-instance", SpringUtil.getBean("dynamicService"));
    }

    @Test
    @DisplayName("未初始化防御：在未注入 Context 时抛出清晰异常")
    void shouldThrowWhenNotInitialized() {
        SpringUtil.clear();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> SpringUtil.getBean(String.class));
        assertTrue(ex.getMessage().contains("ApplicationContext 尚未初始化"));
    }
}
