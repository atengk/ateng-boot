package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨模块接口治理契约零耦合内省定制器
 * <p>
 * 基于零编译期强依赖的注解反射内省机制，在 SpringDoc AST 构建期自动对齐安全与流量治理契约：
 * <ul>
 *     <li><b>{@code @SaIgnore} 免锁头</b>：自动清空公开接口的 {@code Operation.security}（方法显式声明 Sa-Token 鉴权注解覆盖时保留）；</li>
 *     <li><b>{@code @ApiSign} 验签请求头</b>：开启入站验签时防重补齐 {@code X-App-Id}、{@code X-Timestamp}、{@code X-Nonce} 与 {@code X-Signature}；</li>
 *     <li><b>治理契约说明块</b>：受 {@code ateng.boot.openapi.operation-governance-notes} 控制，为 8 类安全与流量注解幂等生成 Markdown 治理契约说明。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
public class OperationGovernanceCustomizer implements OperationCustomizer {

    private static final Logger log = LoggerFactory.getLogger(OperationGovernanceCustomizer.class);

    public static final String GOVERNANCE_TITLE = "**接口治理契约**：";

    private static final String SA_IGNORE = "cn.dev33.satoken.annotation.SaIgnore";
    private static final String SA_CHECK_LOGIN = "cn.dev33.satoken.annotation.SaCheckLogin";
    private static final String SA_CHECK_ROLE = "cn.dev33.satoken.annotation.SaCheckRole";
    private static final String SA_CHECK_PERMISSION = "cn.dev33.satoken.annotation.SaCheckPermission";

    private static final String WEB_IDEMPOTENT = "io.github.atengk.boot.web.idempotent.annotation.Idempotent";
    private static final String WEB_RATE_LIMITER = "io.github.atengk.boot.web.ratelimit.annotation.RateLimiter";
    private static final String WEB_API_SIGN = "io.github.atengk.boot.web.sign.annotation.ApiSign";
    private static final String WEB_IP_ACCESS_CONTROL = "io.github.atengk.boot.web.ip.annotation.IpAccessControl";
    private static final String WEB_API_VERSION = "io.github.atengk.boot.web.version.annotation.ApiVersion";

    private final OpenApiProperties properties;
    private final Map<String, Optional<Class<? extends Annotation>>> annotationClassCache = new ConcurrentHashMap<>(16);

    /**
     * 构造跨模块治理契约定制器
     *
     * @param properties OpenAPI 配置属性
     */
    public OperationGovernanceCustomizer(OpenApiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (operation == null || handlerMethod == null) {
            return operation;
        }

        List<String> notes = new ArrayList<>();

        // 1. 解析 Sa-Token 注解与 @SaIgnore 锁头豁免语义
        if (isSaIgnored(handlerMethod)) {
            operation.setSecurity(Collections.emptyList());
            notes.add("- **访问安全**: 免鉴权公开访问 (`@SaIgnore`)");
        } else {
            collectSaTokenNotes(handlerMethod, notes);
        }

        // 2. 解析 Web 运行时治理注解（并按需补全 @ApiSign 验签头）
        collectWebGovernanceNotes(operation, handlerMethod, notes);

        // 3. 开关开启时幂等追加标准化 Markdown 治理契约说明块
        if (properties.isOperationGovernanceNotes() && !notes.isEmpty()) {
            appendGovernanceNotes(operation, notes);
        }

        return operation;
    }

    private boolean isSaIgnored(HandlerMethod handlerMethod) {
        boolean hasSaIgnore = findAnnotationOnElement(handlerMethod.getMethod(), SA_IGNORE) != null
                || findAnnotationOnElement(handlerMethod.getBeanType(), SA_IGNORE) != null;
        if (!hasSaIgnore) {
            return false;
        }
        // 方法显式声明 Sa-Token 鉴权注解时按 Fail-Closed 语义覆盖免鉴权豁免
        return findAnnotationOnElement(handlerMethod.getMethod(), SA_CHECK_LOGIN) == null
                && findAnnotationOnElement(handlerMethod.getMethod(), SA_CHECK_ROLE) == null
                && findAnnotationOnElement(handlerMethod.getMethod(), SA_CHECK_PERMISSION) == null;
    }

    private void collectSaTokenNotes(HandlerMethod handlerMethod, List<String> notes) {
        Map<String, Object> loginAttrs = readAnnotationAttributes(handlerMethod, SA_CHECK_LOGIN);
        if (loginAttrs != null) {
            String type = strAttr(loginAttrs, "type");
            notes.add(StringUtils.hasText(type)
                    ? "- **访问安全**: 需要登录认证 (`@SaCheckLogin`, type=`" + type + "`)"
                    : "- **访问安全**: 需要登录认证 (`@SaCheckLogin`)");
        }

        Map<String, Object> roleAttrs = readAnnotationAttributes(handlerMethod, SA_CHECK_ROLE);
        if (roleAttrs != null) {
            notes.add("- **角色要求**: `" + arrayAttr(roleAttrs, "value") + "` (模式: `" + strAttr(roleAttrs, "mode") + "`)");
        }

        Map<String, Object> permAttrs = readAnnotationAttributes(handlerMethod, SA_CHECK_PERMISSION);
        if (permAttrs != null) {
            String orRole = arrayAttr(permAttrs, "orRole");
            StringBuilder line = new StringBuilder("- **权限要求**: `").append(arrayAttr(permAttrs, "value"))
                    .append("` (模式: `").append(strAttr(permAttrs, "mode")).append("`");
            if (StringUtils.hasText(orRole)) {
                line.append(", 或角色: `").append(orRole).append("`");
            }
            line.append(")");
            notes.add(line.toString());
        }
    }

    private void collectWebGovernanceNotes(Operation operation, HandlerMethod handlerMethod, List<String> notes) {
        Map<String, Object> idempotentAttrs = readAnnotationAttributes(handlerMethod, WEB_IDEMPOTENT);
        if (idempotentAttrs != null) {
            notes.add("- **防重幂等**: 锁定窗口 `" + idempotentAttrs.get("timeout") + " " + strAttr(idempotentAttrs, "timeUnit") + "` (`@Idempotent`)");
        }

        Map<String, Object> rateLimiterAttrs = readAnnotationAttributes(handlerMethod, WEB_RATE_LIMITER);
        if (rateLimiterAttrs != null) {
            notes.add("- **限流策略**: `" + rateLimiterAttrs.get("time") + " " + strAttr(rateLimiterAttrs, "timeUnit")
                    + "` 内最多允许 `" + rateLimiterAttrs.get("count") + "` 次 (维度: `" + strAttr(rateLimiterAttrs, "limitType") + "`)");
        }

        Map<String, Object> apiSignAttrs = readAnnotationAttributes(handlerMethod, WEB_API_SIGN);
        if (apiSignAttrs != null) {
            boolean verifyReq = Boolean.TRUE.equals(apiSignAttrs.get("verifyRequest"));
            boolean signResp = Boolean.TRUE.equals(apiSignAttrs.get("signResponse"));
            StringBuilder line = new StringBuilder("- **接口加签**: 入站验签=`").append(verifyReq)
                    .append("`, 出站加签=`").append(signResp)
                    .append("`, 算法=`").append(strAttr(apiSignAttrs, "algorithm")).append("`");
            Object expireSeconds = apiSignAttrs.get("expireSeconds");
            if (expireSeconds instanceof Number num && num.longValue() > 0) {
                line.append(", 防重放窗口=`").append(num.longValue()).append("s`");
            }
            notes.add(line.toString());

            if (verifyReq) {
                injectApiSignHeaders(operation);
            }
        }

        Map<String, Object> ipAttrs = readAnnotationAttributes(handlerMethod, WEB_IP_ACCESS_CONTROL);
        if (ipAttrs != null) {
            String rules = arrayAttr(ipAttrs, "rules");
            notes.add(StringUtils.hasText(rules)
                    ? "- **IP 访问控制**: `" + strAttr(ipAttrs, "mode") + "` (`" + rules + "`)"
                    : "- **IP 访问控制**: `" + strAttr(ipAttrs, "mode") + "`");
        }

        Map<String, Object> versionAttrs = readAnnotationAttributes(handlerMethod, WEB_API_VERSION);
        if (versionAttrs != null) {
            String value = strAttr(versionAttrs, "value");
            int number = versionAttrs.get("number") instanceof Number n ? n.intValue() : 0;
            String effectiveVersion = (number > 0 && "1".equals(value)) ? String.valueOf(number) : value;
            notes.add("- **API 版本**: `v" + effectiveVersion + "`");
        }
    }

    private void injectApiSignHeaders(Operation operation) {
        GlobalHeaderCustomizer.addHeaderIfNotPresent(
                operation, "X-App-Id", "API 签名应用标识 App ID", false, null, null);
        GlobalHeaderCustomizer.addHeaderIfNotPresent(
                operation, "X-Timestamp", "请求毫秒/秒级时间戳（防重放）", true, null, null);
        GlobalHeaderCustomizer.addHeaderIfNotPresent(
                operation, "X-Nonce", "单次请求唯一随机串（防重放）", true, null, null);
        GlobalHeaderCustomizer.addHeaderIfNotPresent(
                operation, "X-Signature", "请求体与规范化参数数字签名", true, null, null);
    }

    private void appendGovernanceNotes(Operation operation, List<String> notes) {
        String existingDesc = operation.getDescription();
        if (existingDesc != null && existingDesc.contains(GOVERNANCE_TITLE)) {
            return;
        }
        String block = GOVERNANCE_TITLE + "\n" + String.join("\n", notes);
        if (StringUtils.hasText(existingDesc)) {
            operation.setDescription(existingDesc.trim() + "\n\n" + block);
        } else {
            operation.setDescription(block);
        }
    }

    private Map<String, Object> readAnnotationAttributes(HandlerMethod handlerMethod, String className) {
        Annotation ann = findAnnotationOnElement(handlerMethod.getMethod(), className);
        if (ann == null) {
            ann = findAnnotationOnElement(handlerMethod.getBeanType(), className);
        }
        return ann != null ? AnnotationUtils.getAnnotationAttributes(ann) : null;
    }

    private Annotation findAnnotationOnElement(AnnotatedElement element, String className) {
        if (element == null) {
            return null;
        }
        Class<? extends Annotation> annClass = resolveAnnotationClass(className);
        return annClass != null ? AnnotatedElementUtils.findMergedAnnotation(element, annClass) : null;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Annotation> resolveAnnotationClass(String className) {
        Optional<Class<? extends Annotation>> cached = annotationClassCache.computeIfAbsent(className, key -> {
            ClassLoader classLoader = getClass().getClassLoader();
            if (!ClassUtils.isPresent(key, classLoader)) {
                return Optional.empty();
            }
            try {
                Class<?> resolved = ClassUtils.forName(key, classLoader);
                if (resolved.isAnnotation()) {
                    return Optional.of((Class<? extends Annotation>) resolved);
                }
            } catch (ClassNotFoundException | LinkageError ex) {
                log.debug("Optional governance annotation [{}] could not be loaded: {}", key, ex.getMessage());
            }
            return Optional.empty();
        });
        return cached.orElse(null);
    }

    private static String strAttr(Map<String, Object> attrs, String key) {
        Object val = attrs.get(key);
        return val != null ? String.valueOf(val) : "";
    }

    private static String arrayAttr(Map<String, Object> attrs, String key) {
        Object val = attrs.get(key);
        if (val instanceof String[] arr && arr.length > 0) {
            return Arrays.toString(arr);
        }
        return "";
    }
}
