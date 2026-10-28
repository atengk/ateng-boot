package io.github.atengk.boot.satoken.model;

import cn.dev33.satoken.annotation.SaMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.util.PathMatcher;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态 URL 路径权限规则模型
 * <p>
 * 封装 URL 路径模式 (AntPath 表达式)、HTTP 请求方法 (GET, POST 等)、所需权限码集合及校验模式 (AND / OR)，
 * 专供数据库驱动或配置中心动态权限匹配管道 (DynamicPathPermissionProvider) 使用。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class PathPermissionRule implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * URL 路径匹配规则（支持 AntPath 通配符，如 /api/v1/users/**）
     */
    private String pathPattern;

    /**
     * HTTP 请求方法（GET, POST, PUT, DELETE 等；为空、* 或 ALL 表示不限方法）
     */
    private String httpMethod;

    /**
     * 访问该路径所需的权限码集合
     */
    @Builder.Default
    private Set<String> permissions = Collections.emptySet();

    /**
     * 校验模式：AND（需满足全部权限码）或 OR（满足任一权限码即可），默认 OR
     */
    @Builder.Default
    private SaMode mode = SaMode.OR;

    /**
     * 判断指定请求路径与 HTTP 方法是否命中当前规则
     *
     * @param pathMatcher   Spring 路径匹配器
     * @param requestPath   当前请求 URL 路径
     * @param requestMethod 当前 HTTP 请求方法
     * @return true 表示命中该规则
     */
    public boolean matches(PathMatcher pathMatcher, String requestPath, String requestMethod) {
        if (pathMatcher == null || requestPath == null || pathPattern == null || pathPattern.isBlank()) {
            return false;
        }

        // 1. HTTP Method 匹配
        if (httpMethod != null && !httpMethod.isBlank()) {
            String method = httpMethod.trim();
            if (!"*".equals(method) && !"ALL".equalsIgnoreCase(method)) {
                if (requestMethod == null) {
                    return false;
                }
                String reqMethod = requestMethod.trim();
                if (method.contains(",")) {
                    boolean matched = false;
                    for (String m : method.split(",")) {
                        if (m.trim().equalsIgnoreCase(reqMethod)) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        return false;
                    }
                } else if (!method.equalsIgnoreCase(reqMethod)) {
                    return false;
                }
            }
        }

        // 2. AntPath 模式匹配
        return pathMatcher.match(pathPattern.trim(), requestPath.trim());
    }

    /**
     * 便捷构造方法：不限 HTTP 方法，单权限码
     *
     * @param pathPattern 路径模式
     * @param permission  所需权限码
     * @return 路径权限规则实例
     */
    public static PathPermissionRule of(String pathPattern, String permission) {
        return of(pathPattern, null, SaMode.OR, permission != null && !permission.isBlank() ? Set.of(permission.trim()) : Collections.emptySet());
    }

    /**
     * 便捷构造方法：指定 HTTP 方法，单权限码
     *
     * @param pathPattern 路径模式
     * @param httpMethod  HTTP 方法
     * @param permission  所需权限码
     * @return 路径权限规则实例
     */
    public static PathPermissionRule of(String pathPattern, String httpMethod, String permission) {
        return of(pathPattern, httpMethod, SaMode.OR, permission != null && !permission.isBlank() ? Set.of(permission.trim()) : Collections.emptySet());
    }

    /**
     * 便捷构造方法：指定 HTTP 方法，多权限码（默认 OR 模式）
     *
     * @param pathPattern 路径模式
     * @param httpMethod  HTTP 方法
     * @param permissions 所需权限码列表
     * @return 路径权限规则实例
     */
    public static PathPermissionRule of(String pathPattern, String httpMethod, String... permissions) {
        Set<String> set = (permissions != null && permissions.length > 0)
                ? Arrays.stream(permissions).filter(p -> p != null && !p.isBlank()).map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new))
                : Collections.emptySet();
        return of(pathPattern, httpMethod, SaMode.OR, set);
    }

    /**
     * 便捷构造方法：指定 HTTP 方法、校验模式与多权限码
     *
     * @param pathPattern 路径模式
     * @param httpMethod  HTTP 方法
     * @param mode        权限校验模式 (AND / OR)
     * @param permissions 所需权限码列表
     * @return 路径权限规则实例
     */
    public static PathPermissionRule of(String pathPattern, String httpMethod, SaMode mode, String... permissions) {
        Set<String> set = (permissions != null && permissions.length > 0)
                ? Arrays.stream(permissions).filter(p -> p != null && !p.isBlank()).map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new))
                : Collections.emptySet();
        return of(pathPattern, httpMethod, mode, set);
    }

    /**
     * 便捷构造方法：指定 HTTP 方法与权限码集合（默认 OR 模式）
     *
     * @param pathPattern 路径模式
     * @param httpMethod  HTTP 方法
     * @param permissions 权限码集合
     * @return 路径权限规则实例
     */
    public static PathPermissionRule of(String pathPattern, String httpMethod, Collection<String> permissions) {
        return of(pathPattern, httpMethod, SaMode.OR, permissions);
    }

    /**
     * 便捷构造方法：全参工厂方法
     *
     * @param pathPattern 路径模式
     * @param httpMethod  HTTP 方法
     * @param mode        权限校验模式
     * @param permissions 权限码集合
     * @return 路径权限规则实例
     */
    public static PathPermissionRule of(String pathPattern, String httpMethod, SaMode mode, Collection<String> permissions) {
        Set<String> set = (permissions != null && !permissions.isEmpty())
                ? permissions.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new))
                : Collections.emptySet();
        return PathPermissionRule.builder()
                .pathPattern(pathPattern)
                .httpMethod(httpMethod)
                .permissions(Collections.unmodifiableSet(set))
                .mode(mode != null ? mode : SaMode.OR)
                .build();
    }
}
