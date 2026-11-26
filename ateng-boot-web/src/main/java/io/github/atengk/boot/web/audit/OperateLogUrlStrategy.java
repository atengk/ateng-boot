package io.github.atengk.boot.web.audit;

/**
 * 操作审计请求 URL 采集策略枚举
 * 控制 {@link io.github.atengk.boot.web.aspect.OperateLogAspect} 写入审计模型的请求路径格式，
 * 用于在原始 URI 与 Spring MVC 路由模板之间按需切换，防止路径参数中的敏感句柄明文落库
 *
 * @author Ateng
 * @since 2026-11-26
 */
public enum OperateLogUrlStrategy {

    /**
     * 跟随全局配置或路径规则（仅用于 {@link io.github.atengk.boot.web.annotation.OperateLog} 注解缺省值；
     * 若全局配置同样为 DEFAULT 时安全回退为 {@link #RAW_URI}）
     */
    DEFAULT,

    /**
     * 原始请求 URI（默认策略，直接采集 {@code HttpServletRequest#getRequestURI()}，100% 保持向后兼容）
     */
    RAW_URI,

    /**
     * Spring MVC 路由模板模式（优先读取 {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}，
     * 输出如 {@code /api/sessions/{sessionHandle}}；属性缺失或空白时安全回退为 {@link #RAW_URI}）
     */
    BEST_MATCHING_PATTERN
}
