package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 系统通用状态码全景字典注入定制器
 * <p>
 * 自动在 OpenAPI 元数据的 {@link Info#getDescription()} 尾部追加 Markdown 格式的系统状态码全景字典，
 * 基于 Core 底座 {@link CommonResultCode} 规范，消除前后端错误码歧义与沟通成本。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class ResultCodeDictionaryCustomizer implements OpenApiCustomizer {

    public static final String DICTIONARY_TITLE = "### 通用业务响应状态码字典 (Common Result Codes)";

    private final OpenApiProperties properties;

    /**
     * 构造状态码字典定制器
     *
     * @param properties OpenAPI 配置属性
     */
    public ResultCodeDictionaryCustomizer(OpenApiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null) {
            return;
        }
        if (!properties.isShowResultCodes()) {
            return;
        }

        Info info = openApi.getInfo();
        if (info == null) {
            info = new Info();
            openApi.setInfo(info);
        }

        String currentDesc = info.getDescription();
        if (currentDesc != null && currentDesc.contains(DICTIONARY_TITLE)) {
            return; // 防重幂等
        }

        String markdownTable = buildResultCodeTable();
        if (StringUtils.hasText(currentDesc)) {
            info.setDescription(currentDesc + "\n\n" + markdownTable);
        } else {
            info.setDescription(markdownTable);
        }
    }

    /**
     * 兼容美式拼写别名方法
     *
     * @param openApi 全局 OpenAPI 对象
     */
    public void customize(OpenAPI openApi) {
        customise(openApi);
    }

    /**
     * 构建 Markdown 格式的状态码全景字典表格
     *
     * @return Markdown 表格字符串
     */
    public static String buildResultCodeTable() {
        StringBuilder sb = new StringBuilder();
        sb.append(DICTIONARY_TITLE).append("\n\n");
        sb.append("| 状态码 (code) | 语义说明 (msg) | 场景说明 |\n");
        sb.append("| :--- | :--- | :--- |\n");

        for (CommonResultCode rc : CommonResultCode.values()) {
            sb.append("| `").append(rc.getCode()).append("` | ")
                    .append(rc.getMsg()).append(" | ")
                    .append(resolveScenario(rc)).append(" |\n");
        }
        return sb.toString().trim();
    }

    private static String resolveScenario(CommonResultCode rc) {
        return switch (rc) {
            case SUCCESS -> "请求正常处理完成";
            case BAD_REQUEST -> "入参缺失、格式错误或 JSR-303 参数校验未通过";
            case UNAUTHORIZED -> "未携带认证令牌或 Token 已失效/过期";
            case FORBIDDEN -> "当前登录主体缺少对应权限或角色，访问受限";
            case NOT_FOUND -> "请求路径错误或目标数据未命中";
            case METHOD_NOT_ALLOWED -> "HTTP 请求方法不支持 (如 GET/POST 错用)";
            case TOO_MANY_REQUESTS -> "访问过于频繁，触发接口限流熔断防护";
            case INTERNAL_SERVER_ERROR -> "系统内部发生未捕获异常或服务繁忙";
            case REPEAT_SUBMIT -> "短时间内重复提交请求，被防重幂等切面拦截";
        };
    }
}
