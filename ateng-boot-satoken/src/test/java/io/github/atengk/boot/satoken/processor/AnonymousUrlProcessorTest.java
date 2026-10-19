package io.github.atengk.boot.satoken.processor;

import io.github.atengk.boot.satoken.annotation.Anonymous;
import io.github.atengk.boot.satoken.interceptor.AtengSaTokenInterceptor;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.spi.SaTokenWhitelistProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * AnonymousUrlProcessor 匿名公开访问与动态白名单处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("AnonymousUrlProcessor 匿名公开访问与动态白名单处理器单元测试")
class AnonymousUrlProcessorTest {

    @ParameterizedTest(name = "转译规则: \"{0}\" -> \"{1}\"")
    @CsvSource({
            "'/api/public/ping', '/api/public/ping'",
            "'/users/{id}', '/users/*'",
            "'/users/{userId}/books/{bookId}', '/users/*/books/*'",
            "'/orders/{orderId:[0-9]+}', '/orders/*'",
            "'/files/{*filePath}', '/files/**'",
            "'api/test', '/api/test'",
            "'', ''",
            "'  ', ''"
    })
    @DisplayName("1. 占位符转译：验证 Spring MVC 模式转译为 AntPath 规则")
    void testConvertToAntPath(String input, String expected) {
        String result = AnonymousUrlProcessor.convertToAntPath(input);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("2. 边界防卫：null 入参安全转译为空字符串")
    void testConvertToAntPathNull() {
        assertThat(AnonymousUrlProcessor.convertToAntPath(null)).isEmpty();
    }

    @Test
    @DisplayName("3. 扫描提取：覆盖类级 @Anonymous、方法级 @Anonymous 与方法级覆盖类级 (@Anonymous(false))")
    void testScanAnonymousUrlsWithHierarchy() throws Exception {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(applicationContext.getBeansOfType(RequestMappingHandlerMapping.class))
                .thenReturn(Map.of("requestMappingHandlerMapping", mapping));

        // 构造三组 HandlerMethod
        Method publicMethod = SampleHierarchyController.class.getMethod("publicAction");
        HandlerMethod hm1 = new HandlerMethod(new SampleHierarchyController(), publicMethod);
        RequestMappingInfo rmi1 = RequestMappingInfo.paths("/api/sample/public/{id}").build();

        Method overrideMethod = SampleHierarchyController.class.getMethod("securedAction");
        HandlerMethod hm2 = new HandlerMethod(new SampleHierarchyController(), overrideMethod);
        RequestMappingInfo rmi2 = RequestMappingInfo.paths("/api/sample/secured").build();

        Method plainMethod = PlainController.class.getMethod("standaloneAnonymous");
        HandlerMethod hm3 = new HandlerMethod(new PlainController(), plainMethod);
        RequestMappingInfo rmi3 = RequestMappingInfo.paths("/api/plain/anon/{code}").build();

        Method nonAnonMethod = PlainController.class.getMethod("normalAction");
        HandlerMethod hm4 = new HandlerMethod(new PlainController(), nonAnonMethod);
        RequestMappingInfo rmi4 = RequestMappingInfo.paths("/api/plain/normal").build();

        when(mapping.getHandlerMethods()).thenReturn(Map.of(
                rmi1, hm1,
                rmi2, hm2,
                rmi3, hm3,
                rmi4, hm4
        ));

        AnonymousUrlProcessor processor = new AnonymousUrlProcessor(applicationContext, new SaTokenProperties(), new AtengSaTokenInterceptor(), null);
        Set<String> urls = processor.scanAnonymousUrls();

        // 验证：
        // 1. /api/sample/public/{id} 转译为 /api/sample/public/* (类级 @Anonymous 继承)
        assertThat(urls).contains("/api/sample/public/*");
        // 2. /api/plain/anon/{code} 转译为 /api/plain/anon/* (方法级 @Anonymous)
        assertThat(urls).contains("/api/plain/anon/*");
        // 3. /api/sample/secured 标注 @Anonymous(false)，不可出现在白名单中
        assertThat(urls).doesNotContain("/api/sample/secured");
        // 4. /api/plain/normal 无注解，不可出现在白名单中
        assertThat(urls).doesNotContain("/api/plain/normal");
    }

    @Test
    @DisplayName("4. 多源白名单汇聚：YAML 静态配置 + SPI 动态提供者 + @Anonymous 自动注入拦截器")
    void testProcessFullMergeAndInject() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansOfType(RequestMappingHandlerMapping.class))
                .thenReturn(Map.of());

        SaTokenProperties properties = new SaTokenProperties();
        properties.setWhiteList(List.of("/doc.html", "/favicon.ico"));
        properties.getInterceptor().setExcludes(List.of("/static/**", "/doc.html"));

        SaTokenWhitelistProvider provider = () -> Set.of("/custom/spi/**", "/doc.html");
        @SuppressWarnings("unchecked")
        ObjectProvider<SaTokenWhitelistProvider> providerObjectProvider = mock(ObjectProvider.class);
        when(providerObjectProvider.iterator()).thenReturn(List.of(provider).iterator());

        AtengSaTokenInterceptor interceptor = new AtengSaTokenInterceptor();

        AnonymousUrlProcessor processor = new AnonymousUrlProcessor(applicationContext, properties, interceptor, providerObjectProvider);
        processor.process();

        Set<String> finalWhitelist = interceptor.getWhitelist();
        assertThat(finalWhitelist).contains(
                "/doc.html",
                "/favicon.ico",
                "/static/**",
                "/custom/spi/**"
        );
        // 验证去重
        assertThat(finalWhitelist.stream().filter(p -> p.equals("/doc.html")).count()).isEqualTo(1);
    }

    // --- 测试桩控制器 ---

    @RestController
    @RequestMapping("/api/sample")
    @Anonymous
    static class SampleHierarchyController {

        @GetMapping("/public/{id}")
        public String publicAction() {
            return "ok";
        }

        @GetMapping("/secured")
        @Anonymous(false)
        public String securedAction() {
            return "secured";
        }
    }

    @RestController
    @RequestMapping("/api/plain")
    static class PlainController {

        @GetMapping("/anon/{code}")
        @Anonymous
        public String standaloneAnonymous() {
            return "anon";
        }

        @GetMapping("/normal")
        public String normalAction() {
            return "normal";
        }
    }
}
