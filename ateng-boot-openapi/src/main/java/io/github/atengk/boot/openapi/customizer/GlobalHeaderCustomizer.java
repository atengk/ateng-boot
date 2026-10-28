package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Objects;

/**
 * 全局请求头自动化注入定制器
 * <p>
 * 在 SpringDoc AST 构建期，自动向每一个 HTTP Operation 追加跨切面通用请求头：
 * <ul>
 *     <li>{@code X-Trace-Id}：全链路追踪 ID（默认开启，可配置关闭）</li>
 *     <li>{@code X-Tenant-Id}：多租户标识 ID（默认开启，可配置关闭）</li>
 * </ul>
 * 避免业务开发人员在每个 Controller 重复声明模板化注解。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class GlobalHeaderCustomizer implements OperationCustomizer {

    private static final String PARAMETER_IN_HEADER = "header";

    private final OpenApiProperties properties;

    /**
     * 构造全局请求头定制器
     *
     * @param properties OpenAPI 配置属性
     */
    public GlobalHeaderCustomizer(OpenApiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (operation == null) {
            return null;
        }

        OpenApiProperties.Headers headers = properties.getHeaders();
        if (headers == null) {
            return operation;
        }

        // 1. 全链路追踪 Trace-Id 请求头注入
        if (headers.isTraceId()) {
            addHeaderIfNotPresent(operation, MessageHeaderConstants.TRACE_ID, "全链路追踪 Trace ID");
        }

        // 2. 多租户 Tenant-Id 请求头注入
        if (headers.isTenantId()) {
            addHeaderIfNotPresent(operation, MessageHeaderConstants.TENANT_ID, "多租户标识 Tenant ID");
        }

        return operation;
    }

    /**
     * 防重防冲突：当 Operation 不存在同名 Header 参数时才执行追加
     *
     * @param operation   当前接口 Operation
     * @param headerName  请求头名称
     * @param description 描述说明
     */
    private void addHeaderIfNotPresent(Operation operation, String headerName, String description) {
        List<Parameter> parameters = operation.getParameters();
        if (parameters != null) {
            boolean exists = parameters.stream().anyMatch(param ->
                    PARAMETER_IN_HEADER.equalsIgnoreCase(param.getIn())
                            && headerName.equalsIgnoreCase(param.getName())
            );
            if (exists) {
                return;
            }
        }

        Parameter headerParam = new Parameter()
                .in(PARAMETER_IN_HEADER)
                .name(headerName)
                .description(description)
                .required(false)
                .schema(new StringSchema());

        operation.addParametersItem(headerParam);
    }
}
