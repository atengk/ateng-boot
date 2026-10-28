package io.github.atengk.boot.satoken.interceptor;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.same.SaSameUtil;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.satoken.autoconfigure.AtengSaTokenAutoConfiguration;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * SameTokenClientHttpRequestInterceptor 单元测试与自动装配验证
 * 覆盖：
 * 1. RestTemplate 发起 HTTP 请求时自动提取并注入标准 SA-SAME-TOKEN 请求头
 * 2. 自定义 Same-Token 请求头名称注入
 * 3. sameToken.enabled=false 时静默放行不注入
 * 4. 显式已存在的请求头防御性保留（不强制覆盖）
 * 5. 注入的 Token 与底层 SaSameUtil.checkToken() 协同通过
 * 6. Spring Boot 自动装配与 auto-inject-client 开关验证
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("SameTokenClientHttpRequestInterceptor 零信任客户端拦截器测试")
class SameTokenClientHttpRequestInterceptorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSaTokenAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        // 重置 SaManager 配置与线程上下文，遵循三项铁律
        SaManager.setConfig(new SaTokenConfig());
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.setTenantProvider(null);
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("1. 基础注入：使用 RestTemplate 发起调用时自动携带 SA-SAME-TOKEN 并在服务端校验协同通过")
    void testInterceptInjectsDefaultSameToken() {
        SaTokenProperties properties = new SaTokenProperties();
        SameTokenClientHttpRequestInterceptor interceptor = new SameTokenClientHttpRequestInterceptor(properties);

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(interceptor);

        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);
        String expectedSameToken = SaSameUtil.getToken();

        mockServer.expect(requestTo("http://internal-service/api/users"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("SA-SAME-TOKEN", expectedSameToken))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":200,\"data\":\"ok\"}"));

        String response = restTemplate.getForObject("http://internal-service/api/users", String.class);
        assertThat(response).contains("\"code\":200");
        mockServer.verify();

        // 验证协同：提取的 Token 通过 SaSameUtil 服务端校验
        assertThatCode(() -> SaSameUtil.checkToken(expectedSameToken)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2. 自定义请求头名称：正确注入配置的 headerName")
    void testInterceptWithCustomHeaderName() {
        SaTokenProperties properties = new SaTokenProperties();
        properties.getSameToken().setHeaderName("X-Custom-Same-Token");

        SameTokenClientHttpRequestInterceptor interceptor = new SameTokenClientHttpRequestInterceptor(properties);
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(interceptor);

        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);
        String expectedSameToken = SaSameUtil.getToken();

        mockServer.expect(requestTo("http://internal-service/api/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Custom-Same-Token", expectedSameToken))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":200}"));

        restTemplate.postForObject("http://internal-service/api/orders", null, String.class);
        mockServer.verify();
    }

    @Test
    @DisplayName("3. 开关关闭时：sameToken.enabled=false 不注入请求头")
    void testInterceptWhenDisabled() {
        SaTokenProperties properties = new SaTokenProperties();
        properties.getSameToken().setEnabled(false);

        SameTokenClientHttpRequestInterceptor interceptor = new SameTokenClientHttpRequestInterceptor(properties);
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(interceptor);

        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);

        mockServer.expect(requestTo("http://internal-service/api/check"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("SA-SAME-TOKEN"))
                .andRespond(withStatus(HttpStatus.OK));

        restTemplate.getForObject("http://internal-service/api/check", String.class);
        mockServer.verify();
    }

    @Test
    @DisplayName("4. 防御性保留：当请求已经显式指定了 Same-Token 请求头时，不强制覆盖")
    void testPreservesExistingHeaderIfPresent() {
        SameTokenClientHttpRequestInterceptor interceptor = new SameTokenClientHttpRequestInterceptor();
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(interceptor);

        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);

        mockServer.expect(requestTo("http://internal-service/api/override"))
                .andExpect(header("SA-SAME-TOKEN", "explicitly-passed-token"))
                .andRespond(withStatus(HttpStatus.OK));

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("SA-SAME-TOKEN", "explicitly-passed-token");
        org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(headers);

        restTemplate.exchange("http://internal-service/api/override", HttpMethod.GET, entity, String.class);
        mockServer.verify();
    }

    @Test
    @DisplayName("5. 自动装配验证：默认情况下成功装配 Interceptor 与 RestClient/RestTemplate Customizer")
    void testAutoConfigurationDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SameTokenClientHttpRequestInterceptor.class);
            assertThat(context).hasBean("sameTokenRestClientCustomizer");
            assertThat(context).hasBean("sameTokenRestTemplateCustomizer");

            // 验证 RestTemplateCustomizer 执行注入
            RestTemplateCustomizer templateCustomizer = context.getBean("sameTokenRestTemplateCustomizer", RestTemplateCustomizer.class);
            RestTemplate template = new RestTemplate();
            templateCustomizer.customize(template);
            assertThat(template.getInterceptors())
                    .anyMatch(i -> i instanceof SameTokenClientHttpRequestInterceptor);

            // 验证 RestClientCustomizer 执行注入
            RestClientCustomizer clientCustomizer = context.getBean("sameTokenRestClientCustomizer", RestClientCustomizer.class);
            RestClient.Builder builder = RestClient.builder();
            clientCustomizer.customize(builder);
            // 构建不抛异常
            assertThat(builder.build()).isNotNull();
        });
    }

    @Test
    @DisplayName("6. 自动装配开关：auto-inject-client=false 时不装配 Customizer，enabled=false 时完全不装配")
    void testAutoConfigurationSwitches() {
        // 当 auto-inject-client=false 时
        contextRunner
                .withPropertyValues("ateng.boot.satoken.same-token.auto-inject-client=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SameTokenClientHttpRequestInterceptor.class);
                    assertThat(context).doesNotHaveBean("sameTokenRestClientCustomizer");
                    assertThat(context).doesNotHaveBean("sameTokenRestTemplateCustomizer");
                });

        // 当 same-token.enabled=false 时
        contextRunner
                .withPropertyValues("ateng.boot.satoken.same-token.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SameTokenClientHttpRequestInterceptor.class);
                    assertThat(context).doesNotHaveBean("sameTokenRestClientCustomizer");
                    assertThat(context).doesNotHaveBean("sameTokenRestTemplateCustomizer");
                });
    }

    private static org.springframework.test.web.client.RequestMatcher headerDoesNotExist(String headerName) {
        return request -> assertThat(request.getHeaders().containsKey(headerName))
                .as("Request header '%s' should not exist", headerName)
                .isFalse();
    }
}
