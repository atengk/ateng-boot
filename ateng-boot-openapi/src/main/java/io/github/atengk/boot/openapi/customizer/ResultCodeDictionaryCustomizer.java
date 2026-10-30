package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.github.atengk.boot.openapi.spi.ResultCodeProvider;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.OrderComparator;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 系统通用与业务扩展状态码全景字典注入定制器
 * <p>
 * 自动在 OpenAPI 元数据的 {@link Info#getDescription()} 尾部追加 Markdown 格式的状态码全景字典：
 * 1. 系统核心底座基于 Core 模块 {@link CommonResultCode} 规范渲染通用状态码字典；
 * 2. 动态聚合 Spring 容器中的所有 {@link ResultCodeProvider} 扩展实现，自动追加领域专属业务状态码字典。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class ResultCodeDictionaryCustomizer implements OpenApiCustomizer {

    public static final String DICTIONARY_TITLE = "### 通用业务响应状态码字典 (Common Result Codes)";
    public static final String DEFAULT_CUSTOM_TITLE = "### 业务响应状态码字典 (Business Result Codes)";

    private final OpenApiProperties properties;
    private final List<ResultCodeProvider> providers;

    /**
     * 构造状态码字典定制器（仅包含通用状态码）
     *
     * @param properties OpenAPI 配置属性
     */
    public ResultCodeDictionaryCustomizer(OpenApiProperties properties) {
        this(properties, Collections.emptyList());
    }

    /**
     * 构造状态码字典定制器（包含业务扩展 Provider）
     *
     * @param properties OpenAPI 配置属性
     * @param providers  业务状态码扩展 Provider 列表
     */
    public ResultCodeDictionaryCustomizer(OpenApiProperties properties, List<ResultCodeProvider> providers) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.providers = (providers != null) ? new ArrayList<>(providers) : new ArrayList<>();
        OrderComparator.sort(this.providers);
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

        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(currentDesc)) {
            sb.append(currentDesc).append("\n\n");
        }

        // 1. 构建系统通用状态码字典
        sb.append(buildResultCodeTable());

        // 2. 动态追加业务扩展状态码字典
        if (providers != null && !providers.isEmpty()) {
            for (ResultCodeProvider provider : providers) {
                if (provider == null) {
                    continue;
                }
                Collection<? extends IResultCode> resultCodes = provider.getResultCodes();
                if (resultCodes == null || resultCodes.isEmpty()) {
                    continue;
                }
                String customTable = buildCustomResultCodeTable(provider, resultCodes);
                if (StringUtils.hasText(customTable)) {
                    sb.append("\n\n").append(customTable);
                }
            }
        }

        info.setDescription(sb.toString().trim());
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
     * 构建 Markdown 格式的系统通用状态码全景字典表格
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

    /**
     * 构建 Markdown 格式的业务扩展状态码全景字典表格
     *
     * @param provider    业务状态码 Provider
     * @param resultCodes 业务状态码集合
     * @return Markdown 表格字符串
     */
    public static String buildCustomResultCodeTable(ResultCodeProvider provider, Collection<? extends IResultCode> resultCodes) {
        if (resultCodes == null || resultCodes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String title = (provider != null && StringUtils.hasText(provider.getTitle()))
                ? provider.getTitle().trim()
                : DEFAULT_CUSTOM_TITLE;
        if (!title.startsWith("#")) {
            title = "### " + title;
        }
        sb.append(title).append("\n\n");
        sb.append("| 状态码 (code) | 语义说明 (msg) | 场景说明 |\n");
        sb.append("| :--- | :--- | :--- |\n");

        for (IResultCode rc : resultCodes) {
            if (rc == null || rc.getCode() == null) {
                continue;
            }
            String scenario = null;
            if (provider != null) {
                scenario = provider.resolveScenario(rc);
            }
            if (!StringUtils.hasText(scenario)) {
                scenario = rc.getMsg();
            }
            sb.append("| `").append(rc.getCode()).append("` | ")
                    .append(rc.getMsg() != null ? rc.getMsg() : "").append(" | ")
                    .append(scenario != null ? scenario : "").append(" |\n");
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
            case NOT_ACCEPTABLE -> "服务端无法生成客户端 Accept 头所要求的响应媒体格式";
            case PAYLOAD_TOO_LARGE -> "上传文件或请求载荷体积超出系统限制阈值";
            case UNSUPPORTED_MEDIA_TYPE -> "服务端不支持客户端请求提交的 Content-Type 媒体类型";
            case TOO_MANY_REQUESTS -> "访问过于频繁，触发接口限流熔断防护";
            case INTERNAL_SERVER_ERROR -> "系统内部发生未捕获异常或服务繁忙";
            case SERVICE_UNAVAILABLE -> "服务端过载或异步任务超时，暂时无法处理请求";
            case REPEAT_SUBMIT -> "短时间内重复提交请求，被防重幂等切面拦截";
            default -> rc.getMsg();
        };
    }
}
