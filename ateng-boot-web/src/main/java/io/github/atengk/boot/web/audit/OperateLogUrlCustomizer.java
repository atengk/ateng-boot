package io.github.atengk.boot.web.audit;

/**
 * 操作审计请求 URL 轻量后置定制 SPI
 * 允许业务系统通过注册一个或多个 Spring Bean（支持 {@code @Order} 排序）对审计记录中的
 * {@code OperateLogModel.requestUrl} 实施自定义路径变量掩码、路径段替换或格式规整
 *
 * @author Ateng
 * @since 2026-11-26
 */
@FunctionalInterface
public interface OperateLogUrlCustomizer {

    /**
     * 对策略解析后的请求 URL 执行定制转换
     * 遵循 Fail-Safe 原则：若抛出异常或返回 {@code null}，切面将记录告警日志并安全保留既定 {@code resolvedUrl}
     *
     * @param context 操作审计 URL 定制上下文（包含原始 URI、匹配路由模板与当前解析结果）
     * @return 定制后的请求 URL；返回 {@code null} 时保留上游解析结果
     */
    String customize(OperateLogUrlContext context);
}
