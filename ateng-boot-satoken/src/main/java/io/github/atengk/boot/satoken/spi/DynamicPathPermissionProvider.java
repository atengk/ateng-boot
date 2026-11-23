package io.github.atengk.boot.satoken.spi;

import io.github.atengk.boot.satoken.model.PathPermissionRule;
import org.springframework.util.PathMatcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 数据库驱动的动态 URL 路径权限匹配扩展提供者 SPI
 * <p>
 * 允许外部业务模块、管理后台（如 RBAC 动态权限管理系统）或微服务网关，动态从数据库或缓存加载
 * URL 路径模式 (AntPath)、HTTP Method 与所需权限码的对应关系。
 * 当容器中注册了此 SPI Bean 时，{@link io.github.atengk.boot.satoken.interceptor.AtengSaTokenInterceptor}
 * 在每次未命中静态白名单的请求鉴权链路中，将自动调用此 SPI 进行动态路径权限匹配判定。
 * 若未注册此 SPI Bean，拦截器短路跳过，保持零损耗。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FunctionalInterface
public interface DynamicPathPermissionProvider {

    /**
     * 获取全量动态路径权限规则列表
     * <p>
     * 规则按返回列表的顺序依次匹配，一旦命中首个匹配规则（路径模式与 HTTP 方法均相符），
     * 拦截器将校验当前登录人是否持有该规则指定的权限码。
     * 若未持有，将立即抛出 {@link cn.dev33.satoken.exception.NotPermissionException}。
     * 若返回空集合或 null，则表示该路径无动态权限约束直接放行。
     * </p>
     *
     * @return 路径权限规则列表（有序）
     */
    List<PathPermissionRule> getPathPermissionRules();

    /**
     * 根据请求路径与 HTTP 方法，在规则列表中执行匹配并返回首个命中的权限规则
     * <p>
     * 默认实现遍历 {@link #getPathPermissionRules()}，依据 {@link PathPermissionRule#matches} 顺序匹配。
     * 若外部业务系统有特殊的 RadixTree 树形索引、Redis 缓存或编程式优化，亦可直接重写此方法。
     * </p>
     *
     * @param pathMatcher 路径匹配器（AntPathMatcher）
     * @param requestPath 当前请求 URL 路径
     * @param httpMethod  当前 HTTP 请求方法（GET, POST 等）
     * @return 首个命中的路径权限规则；若无匹配规则则返回 null，表示无动态权限约束
     */
    default PathPermissionRule matchRule(PathMatcher pathMatcher, String requestPath, String httpMethod) {
        List<PathPermissionRule> rules = getPathPermissionRules();
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        for (PathPermissionRule rule : rules) {
            if (rule != null && rule.matches(pathMatcher, requestPath, httpMethod)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 刷新/重新加载动态权限规则（可选扩展点，供配置变更、数据库更新或定时刷新时回调）
     */
    default void refresh() {
    }

    /**
     * 便捷静态构造：基于固定规则列表创建 Provider 实例
     *
     * @param rules 路径权限规则列表
     * @return DynamicPathPermissionProvider 实例
     */
    static DynamicPathPermissionProvider of(List<PathPermissionRule> rules) {
        return () -> rules != null ? rules : Collections.emptyList();
    }

    /**
     * 便捷静态构造：基于固定规则可变参数创建 Provider 实例
     *
     * @param rules 路径权限规则数组
     * @return DynamicPathPermissionProvider 实例
     */
    static DynamicPathPermissionProvider of(PathPermissionRule... rules) {
        return () -> rules != null ? Arrays.asList(rules) : Collections.emptyList();
    }
}
