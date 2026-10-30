package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.openapi.annotation.IgnoreGlobalHeaders;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
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

    static class SampleController {
        public void normalMethod() {
        }

        @IgnoreGlobalHeaders
        public void ignoreAllMethod() {
        }

        @IgnoreGlobalHeaders({"X-Tenant-Id"})
        public void ignoreTenantMethod() {
        }
    }

    @IgnoreGlobalHeaders
    static class IgnoreAllClassController {
        public void classIgnoredMethod() {
        }
    }

    @IgnoreGlobalHeaders({"X-App-Version"})
    static class IgnoreSpecificClassController {
        public void classSpecificIgnoredMethod() {
        }
    }

    @IgnoreGlobalHeaders({"X-Tenant-Id"})
    static class IgnoreMergedController {
        @IgnoreGlobalHeaders({"X-App-Version"})
        public void mergedMethod() {
        }
    }

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
    @DisplayName("测试注入自定义全局请求头 (Headers.custom)")
    void testCustomHeadersInjection() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.getHeaders().getCustom().add(new OpenApiProperties.HeaderParam(
                "X-App-Version", "客户端应用版本号", true, "1.0.0"));
        properties.getHeaders().getCustom().add(new OpenApiProperties.HeaderParam(
                "X-Client-Platform", "客户端平台", false, "iOS"));

        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);
        Operation operation = new Operation();
        customizer.customize(operation, null);

        List<Parameter> parameters = operation.getParameters();
        assertThat(parameters).hasSize(4); // traceId, tenantId, 2 custom headers

        Parameter versionParam = parameters.stream()
                .filter(p -> "X-App-Version".equalsIgnoreCase(p.getName()))
                .findFirst()
                .orElse(null);
        assertThat(versionParam).isNotNull();
        assertThat(versionParam.getIn()).isEqualTo("header");
        assertThat(versionParam.getRequired()).isTrue();
        assertThat(versionParam.getDescription()).isEqualTo("客户端应用版本号");
        assertThat(versionParam.getSchema().getDefault()).isEqualTo("1.0.0");
        assertThat(versionParam.getSchema().getExample()).isEqualTo("1.0.0");

        Parameter platformParam = parameters.stream()
                .filter(p -> "X-Client-Platform".equalsIgnoreCase(p.getName()))
                .findFirst()
                .orElse(null);
        assertThat(platformParam).isNotNull();
        assertThat(platformParam.getRequired()).isFalse();
        assertThat(platformParam.getDescription()).isEqualTo("客户端平台");
    }

    @Test
    @DisplayName("测试方法标注 @IgnoreGlobalHeaders 全量豁免跳过所有全局请求头注入")
    void testIgnoreAllHeadersOnMethod() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        properties.getHeaders().getCustom().add(new OpenApiProperties.HeaderParam("X-App-Version", "版本号", false, "1.0.0"));
        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);

        SampleController controller = new SampleController();
        Method method = SampleController.class.getMethod("ignoreAllMethod");
        HandlerMethod handlerMethod = new HandlerMethod(controller, method);

        Operation operation = new Operation();
        customizer.customize(operation, handlerMethod);

        assertThat(operation.getParameters()).isNull();
    }

    @Test
    @DisplayName("测试方法标注 @IgnoreGlobalHeaders({\"X-Tenant-Id\"}) 精准豁免指定请求头")
    void testIgnoreSpecificHeaderOnMethod() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        properties.getHeaders().getCustom().add(new OpenApiProperties.HeaderParam("X-App-Version", "版本号", false, "1.0.0"));
        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);

        SampleController controller = new SampleController();
        Method method = SampleController.class.getMethod("ignoreTenantMethod");
        HandlerMethod handlerMethod = new HandlerMethod(controller, method);

        Operation operation = new Operation();
        customizer.customize(operation, handlerMethod);

        List<Parameter> parameters = operation.getParameters();
        assertThat(parameters).hasSize(2);

        // X-Tenant-Id 必须被忽略
        assertThat(parameters).noneMatch(p -> MessageHeaderConstants.TENANT_ID.equalsIgnoreCase(p.getName()));

        // X-Trace-Id 与 X-App-Version 正常保留
        assertThat(parameters).anyMatch(p -> MessageHeaderConstants.TRACE_ID.equalsIgnoreCase(p.getName()));
        assertThat(parameters).anyMatch(p -> "X-App-Version".equalsIgnoreCase(p.getName()));
    }

    @Test
    @DisplayName("测试类标注 @IgnoreGlobalHeaders 全量豁免当前控制器所有方法")
    void testIgnoreAllHeadersOnClass() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);

        IgnoreAllClassController controller = new IgnoreAllClassController();
        Method method = IgnoreAllClassController.class.getMethod("classIgnoredMethod");
        HandlerMethod handlerMethod = new HandlerMethod(controller, method);

        Operation operation = new Operation();
        customizer.customize(operation, handlerMethod);

        assertThat(operation.getParameters()).isNull();
    }

    @Test
    @DisplayName("测试类标注 @IgnoreGlobalHeaders({\"X-App-Version\"}) 精准豁免当前控制器对应请求头")
    void testIgnoreSpecificHeaderOnClass() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        properties.getHeaders().getCustom().add(new OpenApiProperties.HeaderParam("X-App-Version", "版本号", false, "1.0.0"));
        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);

        IgnoreSpecificClassController controller = new IgnoreSpecificClassController();
        Method method = IgnoreSpecificClassController.class.getMethod("classSpecificIgnoredMethod");
        HandlerMethod handlerMethod = new HandlerMethod(controller, method);

        Operation operation = new Operation();
        customizer.customize(operation, handlerMethod);

        List<Parameter> parameters = operation.getParameters();
        assertThat(parameters).hasSize(2); // traceId + tenantId (X-App-Version 被豁免)
        assertThat(parameters).noneMatch(p -> "X-App-Version".equalsIgnoreCase(p.getName()));
        assertThat(parameters).anyMatch(p -> MessageHeaderConstants.TRACE_ID.equalsIgnoreCase(p.getName()));
        assertThat(parameters).anyMatch(p -> MessageHeaderConstants.TENANT_ID.equalsIgnoreCase(p.getName()));
    }

    @Test
    @DisplayName("测试类标注与方法标注 @IgnoreGlobalHeaders 并集继承合并")
    void testIgnoreMergedClassAndMethod() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        properties.getHeaders().getCustom().add(new OpenApiProperties.HeaderParam("X-App-Version", "版本号", false, "1.0.0"));
        GlobalHeaderCustomizer customizer = new GlobalHeaderCustomizer(properties);

        IgnoreMergedController controller = new IgnoreMergedController();
        Method method = IgnoreMergedController.class.getMethod("mergedMethod");
        HandlerMethod handlerMethod = new HandlerMethod(controller, method);

        Operation operation = new Operation();
        customizer.customize(operation, handlerMethod);

        List<Parameter> parameters = operation.getParameters();
        assertThat(parameters).hasSize(1);
        // 类上的 X-Tenant-Id 和方法上的 X-App-Version 均被豁免，仅保留 X-Trace-Id
        assertThat(parameters).noneMatch(p -> MessageHeaderConstants.TENANT_ID.equalsIgnoreCase(p.getName()));
        assertThat(parameters).noneMatch(p -> "X-App-Version".equalsIgnoreCase(p.getName()));
        assertThat(parameters).anyMatch(p -> MessageHeaderConstants.TRACE_ID.equalsIgnoreCase(p.getName()));
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
