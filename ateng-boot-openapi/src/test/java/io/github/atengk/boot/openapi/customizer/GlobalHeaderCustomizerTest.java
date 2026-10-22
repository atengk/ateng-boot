package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局请求头自动化注入定制器测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("全局请求头自动化注入定制器测试")
class GlobalHeaderCustomizerTest {

    @Test
    @DisplayName("测试默认配置下自动注入 X-Trace-Id 与 X-Tenant-Id")
    void testDefaultHeadersInjection() {
        OpenApiProperties properties = new OpenApiProperties();
        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);

        Operation operation = new Operation();
        Operation customized = customizer.customize(operation, null);

        assertThat(customized).isSameAs(operation);
        List<Parameter> parameters = customized.getParameters();
        assertThat(parameters).isNotNull().hasSize(2);

        Parameter traceParam = parameters.stream()
                .filter(p -> MessageHeaderConstants.TRACE_ID.equalsIgnoreCase(p.getName()))
                .findFirst()
                .orElse(null);
        assertThat(traceParam).isNotNull();
        assertThat(traceParam.getIn()).isEqualTo("header");
        assertThat(traceParam.getRequired()).isFalse();
        assertThat(traceParam.getDescription()).contains("Trace ID");

        Parameter tenantParam = parameters.stream()
                .filter(p -> MessageHeaderConstants.TENANT_ID.equalsIgnoreCase(p.getName()))
                .findFirst()
                .orElse(null);
        assertThat(tenantParam).isNotNull();
        assertThat(tenantParam.getIn()).isEqualTo("header");
        assertThat(tenantParam.getRequired()).isFalse();
        assertThat(tenantParam.getDescription()).contains("Tenant ID");
    }

    @Test
    @DisplayName("测试禁用 traceId 仅注入 tenantId")
    void testTraceIdDisabled() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.getHeaders().setTraceId(false);
        properties.getHeaders().setTenantId(true);

        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);
        Operation operation = new Operation();
        customizer.customize(operation, null);

        List<Parameter> parameters = operation.getParameters();
        assertThat(parameters).hasSize(1);
        assertThat(parameters.get(0).getName()).isEqualTo(MessageHeaderConstants.TENANT_ID);
    }

    @Test
    @DisplayName("测试禁用 tenantId 仅注入 traceId")
    void testTenantIdDisabled() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.getHeaders().setTraceId(true);
        properties.getHeaders().setTenantId(false);

        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);
        Operation operation = new Operation();
        customizer.customize(operation, null);

        List<Parameter> parameters = operation.getParameters();
        assertThat(parameters).hasSize(1);
        assertThat(parameters.get(0).getName()).isEqualTo(MessageHeaderConstants.TRACE_ID);
    }

    @Test
    @DisplayName("测试同时禁用 traceId 与 tenantId 时不注入任何参数")
    void testAllHeadersDisabled() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.getHeaders().setTraceId(false);
        properties.getHeaders().setTenantId(false);

        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);
        Operation operation = new Operation();
        customizer.customize(operation, null);

        assertThat(operation.getParameters()).isNull();
    }

    @Test
    @DisplayName("测试当已存在同名 Header 参数时防重跳过注入")
    void testIdempotentExistingParameter() {
        OpenApiProperties properties = new OpenApiProperties();
        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);

        Operation operation = new Operation();
        operation.addParametersItem(new Parameter()
                .in("header")
                .name(MessageHeaderConstants.TRACE_ID)
                .description("Custom Trace"));

        customizer.customize(operation, null);

        List<Parameter> parameters = operation.getParameters();
        assertThat(parameters).hasSize(2); // 1 个原有 trace + 1 个新注入 tenant

        Parameter existingTrace = parameters.stream()
                .filter(p -> MessageHeaderConstants.TRACE_ID.equalsIgnoreCase(p.getName()))
                .findFirst()
                .orElse(null);
        assertThat(existingTrace).isNotNull();
        assertThat(existingTrace.getDescription()).isEqualTo("Custom Trace");
    }

    @Test
    @DisplayName("测试空安全防御保护 (Null Safety)")
    void testNullSafety() {
        OpenApiProperties properties = new OpenApiProperties();
        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);

        assertThat(customizer.customize(null, null)).isNull();

        properties.setHeaders(null);
        Operation op = new Operation();
        assertThat(customizer.customize(op, null)).isSameAs(op);
    }
}
