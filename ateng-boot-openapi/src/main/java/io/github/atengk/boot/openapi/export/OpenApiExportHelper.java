package io.github.atengk.boot.openapi.export;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.api.OpenApiResourceNotFoundException;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * OpenAPI 契约离线导出标准化门面助手
 * <p>
 * 提供开箱即用的静态与 Spring 容器受管双模门面支持：
 * <ul>
 *     <li>支持将指定业务分组或全量默认分组的 OpenAPI 规范导出为格式化的 JSON / YAML 字符串、byte[] 或写入指定输出流；</li>
 *     <li>深度联动 SpringDoc WebMvc 运行时管线，亦可直接针对内存 {@link OpenAPI} 实体独立导出；</li>
 *     <li>完美支撑单元测试契约断言、本地代码生成与 CI/CD 自动化构建提取契约。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class OpenApiExportHelper {

    private static volatile OpenApiExportHelper instance;

    private final ObjectProvider<MultipleOpenApiWebMvcResource> multipleOpenApiResourceProvider;
    private final ObjectProvider<OpenApiWebMvcResource> openApiResourceProvider;
    private final ObjectProvider<OpenAPI> openApiProvider;
    private final List<GroupedOpenApi> groupedOpenApis;

    /**
     * 构造 OpenAPI 离线导出门面 Bean
     *
     * @param multipleOpenApiResourceProvider 多业务分组资源提供者
     * @param openApiResourceProvider         默认单分组资源提供者
     * @param openApiProvider                 OpenAPI 基础对象提供者
     * @param groupedOpenApis                 动态业务分组列表
     */
    public OpenApiExportHelper(
            ObjectProvider<MultipleOpenApiWebMvcResource> multipleOpenApiResourceProvider,
            ObjectProvider<OpenApiWebMvcResource> openApiResourceProvider,
            ObjectProvider<OpenAPI> openApiProvider,
            List<GroupedOpenApi> groupedOpenApis) {
        this.multipleOpenApiResourceProvider = multipleOpenApiResourceProvider;
        this.openApiResourceProvider = openApiResourceProvider;
        this.openApiProvider = openApiProvider;
        this.groupedOpenApis = (groupedOpenApis != null) ? groupedOpenApis : Collections.emptyList();
        instance = this;
    }

    // ==========================================
    // 静态双模门面方法 (Static Facade API)
    // ==========================================

    /**
     * 获取全局受管单例实例
     *
     * @return OpenApiExportHelper 受管单例
     * @throws IllegalStateException 若未在 Spring 容器中装配
     */
    public static OpenApiExportHelper getInstance() {
        OpenApiExportHelper current = instance;
        if (current == null) {
            throw new IllegalStateException("OpenApiExportHelper has not been initialized by the Spring container. "
                    + "If running outside Spring, use static methods that accept an OpenAPI instance directly.");
        }
        return current;
    }

    /**
     * 显式设置全局单例（主要用于测试桩注入）
     *
     * @param helper 导出门面实例
     */
    public static void setInstance(OpenApiExportHelper helper) {
        instance = helper;
    }

    /**
     * 导出默认全量分组的 OpenAPI JSON 字符串
     *
     * @return 格式化的 OpenAPI JSON 规范字符串
     */
    public static String exportJson() {
        return exportJson((String) null);
    }

    /**
     * 导出指定分组的 OpenAPI JSON 字符串
     *
     * @param group 业务分组名称（为 null 或空时回退为默认分组）
     * @return 格式化的 OpenAPI JSON 规范字符串
     */
    public static String exportJson(String group) {
        return getInstance().exportJsonString(group);
    }

    /**
     * 导出默认全量分组的 OpenAPI YAML 字符串
     *
     * @return 格式化的 OpenAPI YAML 规范字符串
     */
    public static String exportYaml() {
        return exportYaml((String) null);
    }

    /**
     * 导出指定分组的 OpenAPI YAML 字符串
     *
     * @param group 业务分组名称（为 null 或空时回退为默认分组）
     * @return 格式化的 OpenAPI YAML 规范字符串
     */
    public static String exportYaml(String group) {
        return getInstance().exportYamlString(group);
    }

    /**
     * 导出默认分组规范至指定输出流
     *
     * @param format 目标格式 (JSON / YAML)
     * @param out    目标输出流
     * @throws IOException 写入流异常
     */
    public static void export(OpenApiFormat format, OutputStream out) throws IOException {
        export((String) null, format, out);
    }

    /**
     * 导出指定分组规范至指定输出流
     *
     * @param group  业务分组名称（为 null 或空时回退为默认分组）
     * @param format 目标格式 (JSON / YAML)
     * @param out    目标输出流
     * @throws IOException 写入流异常
     */
    public static void export(String group, OpenApiFormat format, OutputStream out) throws IOException {
        getInstance().exportStream(group, format, out);
    }

    // ==========================================
    // 独立脱机导出方法 (Standalone OpenAPI Model API)
    // ==========================================

    /**
     * 将指定的 OpenAPI 对象导出为标准格式化 JSON 字符串
     *
     * @param openApi OpenAPI 契约对象
     * @return 格式化 JSON 字符串
     */
    public static String exportJson(OpenAPI openApi) {
        if (openApi == null) {
            throw new IllegalArgumentException("openApi must not be null");
        }
        return Json.pretty(openApi);
    }

    /**
     * 将指定的 OpenAPI 对象导出为标准格式化 YAML 字符串
     *
     * @param openApi OpenAPI 契约对象
     * @return 格式化 YAML 字符串
     */
    public static String exportYaml(OpenAPI openApi) {
        if (openApi == null) {
            throw new IllegalArgumentException("openApi must not be null");
        }
        return Yaml.pretty(openApi);
    }

    /**
     * 将指定的 OpenAPI 对象导出为对应格式的字节数组
     *
     * @param openApi OpenAPI 契约对象
     * @param format  目标格式
     * @return 契约字节数组
     */
    public static byte[] exportBytes(OpenAPI openApi, OpenApiFormat format) {
        if (openApi == null) {
            throw new IllegalArgumentException("openApi must not be null");
        }
        Objects.requireNonNull(format, "format must not be null");
        String content = (format == OpenApiFormat.YAML) ? exportYaml(openApi) : exportJson(openApi);
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 将指定的 OpenAPI 对象流式写入输出流
     *
     * @param openApi OpenAPI 契约对象
     * @param format  目标格式
     * @param out     目标输出流
     * @throws IOException 写入流异常
     */
    public static void export(OpenAPI openApi, OpenApiFormat format, OutputStream out) throws IOException {
        if (openApi == null) {
            throw new IllegalArgumentException("openApi must not be null");
        }
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        byte[] bytes = exportBytes(openApi, format);
        out.write(bytes);
        out.flush();
    }

    // ==========================================
    // 实例受管导出方法 (Instance Managed API)
    // ==========================================

    /**
     * 导出指定分组的 JSON 规范字符串
     *
     * @param group 业务分组名称
     * @return JSON 字符串
     */
    public String exportJsonString(String group) {
        return new String(exportBytes(group, OpenApiFormat.JSON, null), StandardCharsets.UTF_8);
    }

    /**
     * 导出指定分组的 YAML 规范字符串
     *
     * @param group 业务分组名称
     * @return YAML 字符串
     */
    public String exportYamlString(String group) {
        return new String(exportBytes(group, OpenApiFormat.YAML, null), StandardCharsets.UTF_8);
    }

    /**
     * 导出指定分组规范至目标输出流
     *
     * @param group  业务分组名称
     * @param format 目标格式
     * @param out    目标输出流
     * @throws IOException 写入异常
     */
    public void exportStream(String group, OpenApiFormat format, OutputStream out) throws IOException {
        exportStream(group, format, out, null);
    }

    /**
     * 导出指定分组规范至目标输出流（支持显式注入 HTTP 请求）
     *
     * @param group   业务分组名称
     * @param format  目标格式
     * @param out     目标输出流
     * @param request 客户端 HTTP 请求（可选）
     * @throws IOException 写入异常
     */
    public void exportStream(String group, OpenApiFormat format, OutputStream out, HttpServletRequest request) throws IOException {
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        byte[] bytes = exportBytes(group, format, request);
        out.write(bytes);
        out.flush();
    }

    /**
     * 导出指定分组规范为字节数组
     *
     * @param group   业务分组名称
     * @param format  目标格式
     * @param request 客户端 HTTP 请求（可选）
     * @return 格式化字节数组
     */
    public byte[] exportBytes(String group, OpenApiFormat format, HttpServletRequest request) {
        Objects.requireNonNull(format, "format must not be null");
        HttpServletRequest effectiveRequest = resolveCurrentRequest(request);
        String targetGroup = StringUtils.hasText(group) ? group.trim() : resolveDefaultGroup();

        // 1. 若配置了多业务分组资源 (MultipleOpenApiWebMvcResource)
        if (multipleOpenApiResourceProvider != null) {
            MultipleOpenApiWebMvcResource multipleResource = multipleOpenApiResourceProvider.getIfAvailable();
            if (multipleResource != null) {
                try {
                    return (format == OpenApiFormat.YAML)
                            ? multipleResource.openapiYaml(effectiveRequest, "/v3/api-docs", targetGroup, Locale.getDefault())
                            : multipleResource.openapiJson(effectiveRequest, "/v3/api-docs", targetGroup, Locale.getDefault());
                } catch (OpenApiResourceNotFoundException e) {
                    throw new IllegalArgumentException("OpenAPI group not found: " + targetGroup, e);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to export OpenAPI specification for group: " + targetGroup, e);
                }
            }
        }

        // 2. 若仅存在单分组资源 (OpenApiWebMvcResource)
        if (openApiResourceProvider != null) {
            OpenApiWebMvcResource singleResource = openApiResourceProvider.getIfAvailable();
            if (singleResource != null) {
                try {
                    return (format == OpenApiFormat.YAML)
                            ? singleResource.openapiYaml(effectiveRequest, "/v3/api-docs", Locale.getDefault())
                            : singleResource.openapiJson(effectiveRequest, "/v3/api-docs", Locale.getDefault());
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to export OpenAPI specification", e);
                }
            }
        }

        // 3. 兜底回退：若容器中存在直接的 OpenAPI Bean
        if (openApiProvider != null) {
            OpenAPI openApi = openApiProvider.getIfAvailable();
            if (openApi != null) {
                return exportBytes(openApi, format);
            }
        }

        throw new IllegalStateException("No OpenAPI resources or OpenAPI bean available in the application context.");
    }

    /**
     * 计算并解析默认业务分组名称
     */
    private String resolveDefaultGroup() {
        if (groupedOpenApis != null && !groupedOpenApis.isEmpty()) {
            for (GroupedOpenApi g : groupedOpenApis) {
                if ("default".equalsIgnoreCase(g.getGroup())) {
                    return g.getGroup();
                }
            }
            return groupedOpenApis.get(0).getGroup();
        }
        return "default";
    }

    /**
     * 解析当前环境下的 HTTP 请求（若非 Web 环境则动态创建纯净桩以避免 SpringDoc 计算 BaseUrl 时 NPE）
     */
    private HttpServletRequest resolveCurrentRequest(HttpServletRequest explicitRequest) {
        if (explicitRequest != null) {
            return explicitRequest;
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest currentRequest = servletAttrs.getRequest();
            if (currentRequest != null) {
                return currentRequest;
            }
        }
        return createDummyRequest();
    }

    /**
     * 基于 JDK 动态代理构建轻量级只读 HttpServletRequest 桩对象
     */
    private static HttpServletRequest createDummyRequest() {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMethod" -> "GET";
                    case "getRequestURL" -> new StringBuffer("http://localhost/v3/api-docs");
                    case "getRequestURI" -> "/v3/api-docs";
                    case "getContextPath", "getQueryString", "getPathInfo" -> "";
                    case "getServletPath" -> "/v3/api-docs";
                    case "getServerName" -> "localhost";
                    case "getServerPort" -> 8080;
                    case "getScheme" -> "http";
                    case "getHeaderNames", "getParameterNames", "getAttributeNames" -> Collections.emptyEnumeration();
                    case "getHeader", "getAttribute", "getParameter" -> null;
                    case "getHeaders" -> Collections.emptyEnumeration();
                    case "getLocales" -> Collections.enumeration(Collections.singleton(Locale.getDefault()));
                    case "getLocale" -> Locale.getDefault();
                    default -> defaultReturnValue(method.getReturnType());
                }
        );
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0.0f;
        if (returnType == double.class) return 0.0d;
        if (returnType == char.class) return '\0';
        return null;
    }
}
