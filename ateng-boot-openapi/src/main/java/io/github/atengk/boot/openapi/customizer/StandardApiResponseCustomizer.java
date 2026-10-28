package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

import java.util.Objects;

/**
 * 通用标准 HTTP 响应说明定制器
 * <p>
 * 自动为所有 HTTP Operation 补全标准通用响应语义说明：
 * <ul>
 *     <li>{@code 200}：操作成功 (HTTP 200 OK)</li>
 *     <li>{@code 400}：参数校验失败 (HTTP 400 Bad Request)</li>
 *     <li>{@code 401}：未授权或凭证已过期 (HTTP 401 Unauthorized)</li>
 *     <li>{@code 500}：系统繁忙或内部错误 (HTTP 500 Internal Server Error)</li>
 * </ul>
 * 防御性保留开发者已显式声明的自定义响应说明，不强制覆盖。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class StandardApiResponseCustomizer implements OperationCustomizer {

    private final OpenApiProperties properties;

    /**
     * 构造标准 HTTP 响应定制器
     *
     * @param properties OpenAPI 配置属性
     */
    public StandardApiResponseCustomizer(OpenApiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (operation == null) {
            return null;
        }
        if (!properties.isStandardResponses()) {
            return operation;
        }

        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }

        addResponseIfNotPresent(responses, "200", "操作成功 (HTTP 200 OK)");
        addResponseIfNotPresent(responses, "400", "参数校验失败 (HTTP 400 Bad Request)");
        addResponseIfNotPresent(responses, "401", "未授权或凭证已过期 (HTTP 401 Unauthorized)");
        addResponseIfNotPresent(responses, "500", "系统繁忙或内部错误 (HTTP 500 Internal Server Error)");

        return operation;
    }

    /**
     * 防重防覆盖补全响应说明
     */
    private void addResponseIfNotPresent(ApiResponses responses, String statusCode, String description) {
        if (!responses.containsKey(statusCode)) {
            responses.addApiResponse(statusCode, new ApiResponse().description(description));
        }
    }
}
