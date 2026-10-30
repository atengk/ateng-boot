package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.openapi.annotation.IgnoreGlobalHeaders;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局请求头自动化注入定制器
 * <p>
 * 在 SpringDoc AST 构建期，自动向每一个 HTTP Operation 追加跨切面通用请求头：
 * <ul>
 *     <li>{@code X-Trace-Id}：全链路追踪 ID（默认开启，可配置关闭）</li>
 *     <li>{@code X-Tenant-Id}：多租户标识 ID（默认开启，可配置关闭）</li>
 *     <li>{@code custom}：配置文件中声明的自定义全局请求头列表</li>
 * </ul>
 * 同时支持 {@link IgnoreGlobalHeaders} 声明式豁免（全量豁免或根据请求头名称精细化豁免）。
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

        // 1. 扫描 @IgnoreGlobalHeaders 豁免配置
        IgnoreGlobalHeaders ignoreAnnotation = resolveIgnoreAnnotation(handlerMethod);
        if (ignoreAnnotation != null) {
            // 若 value 为空，代表全量豁免所有全局请求头
            if (ignoreAnnotation.value().length == 0) {
                return operation;
            }
        }

        Set<String> ignoredHeaders = resolveIgnoredHeaders(ignoreAnnotation);

        // 2. 全链路追踪 Trace-Id 请求头注入
        if (headers.isTraceId() && !isHeaderIgnored(MessageHeaderConstants.TRACE_ID, ignoredHeaders)) {
            addHeaderIfNotPresent(operation, MessageHeaderConstants.TRACE_ID, "全链路追踪 Trace ID", false, null);
        }

        // 3. 多租户 Tenant-Id 请求头注入
        if (headers.isTenantId() && !isHeaderIgnored(MessageHeaderConstants.TENANT_ID, ignoredHeaders)) {
            addHeaderIfNotPresent(operation, MessageHeaderConstants.TENANT_ID, "多租户标识 Tenant ID", false, null);
        }

        // 4. 自定义全局请求头注入
        List<OpenApiProperties.HeaderParam> customHeaders = headers.getCustom();
        if (!CollectionUtils.isEmpty(customHeaders)) {
            for (OpenApiProperties.HeaderParam param : customHeaders) {
                if (param != null && StringUtils.hasText(param.getName()) && !isHeaderIgnored(param.getName(), ignoredHeaders)) {
                    addHeaderIfNotPresent(operation, param.getName(), param.getDescription(), param.isRequired(), param.getDefaultValue());
                }
            }
        }

        return operation;
    }

    /**
     * 解析方法或类上的 @IgnoreGlobalHeaders 注解（优先方法级别）
     *
     * @param handlerMethod 当前请求对应的控制器方法
     * @return 豁免注解，未声明时返回 null
     */
    private IgnoreGlobalHeaders resolveIgnoreAnnotation(HandlerMethod handlerMethod) {
        if (handlerMethod == null) {
            return null;
        }
        IgnoreGlobalHeaders annotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), IgnoreGlobalHeaders.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), IgnoreGlobalHeaders.class);
        }
        return annotation;
    }

    /**
     * 提取忽略的请求头小写名称集合
     *
     * @param annotation 豁免注解
     * @return 请求头名称小写集合
     */
    private Set<String> resolveIgnoredHeaders(IgnoreGlobalHeaders annotation) {
        if (annotation == null || annotation.value().length == 0) {
            return Collections.emptySet();
        }
        return Arrays.stream(annotation.value())
                .filter(StringUtils::hasText)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /**
     * 判断指定请求头是否被显式豁免
     *
     * @param headerName     请求头名称
     * @param ignoredHeaders 豁免集合
     * @return true 表示豁免，不注入
     */
    private boolean isHeaderIgnored(String headerName, Set<String> ignoredHeaders) {
        if (CollectionUtils.isEmpty(ignoredHeaders) || !StringUtils.hasText(headerName)) {
            return false;
        }
        return ignoredHeaders.contains(headerName.toLowerCase(Locale.ROOT));
    }

    /**
     * 防重防冲突：当 Operation 不存在同名 Header 参数时才执行追加（默认非必填、无默认值）
     *
     * @param operation   当前接口 Operation
     * @param headerName  请求头名称
     * @param description 描述说明
     */
    private void addHeaderIfNotPresent(Operation operation, String headerName, String description) {
        addHeaderIfNotPresent(operation, headerName, description, false, null);
    }

    /**
     * 防重防冲突：当 Operation 不存在同名 Header 参数时才执行追加
     *
     * @param operation    当前接口 Operation
     * @param headerName   请求头名称
     * @param description  描述说明
     * @param required     是否必填
     * @param defaultValue 默认值或示例值
     */
    private void addHeaderIfNotPresent(Operation operation, String headerName, String description, boolean required, String defaultValue) {
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

        StringSchema schema = new StringSchema();
        if (StringUtils.hasText(defaultValue)) {
            schema.setDefault(defaultValue);
            schema.setExample(defaultValue);
        }

        Parameter headerParam = new Parameter()
                .in(PARAMETER_IN_HEADER)
                .name(headerName)
                .description(description)
                .required(required)
                .schema(schema);

        operation.addParametersItem(headerParam);
    }
}
