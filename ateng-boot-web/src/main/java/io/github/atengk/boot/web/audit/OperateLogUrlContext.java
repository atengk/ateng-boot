package io.github.atengk.boot.web.audit;

import io.github.atengk.boot.web.annotation.OperateLog;
import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.Method;

/**
 * 操作审计请求 URL 定制上下文
 * 向 {@link OperateLogUrlCustomizer} SPI 提供本次请求的完整路由解析状态与策略快照
 *
 * @param request             当前 HTTP 请求对象
 * @param method              被拦截的目标控制器或业务方法（具体实现方法）
 * @param operateLog          生效的 {@link OperateLog} 注解实例
 * @param effectiveStrategy   经四阶优先级裁决后最终生效的 URL 采集策略（不会为 {@link OperateLogUrlStrategy#DEFAULT}）
 * @param rawUri              原始请求路径（{@code request.getRequestURI()}）
 * @param bestMatchingPattern Spring MVC 解析出的路由模板（{@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}，未命中时为 {@code null}）
 * @param resolvedUrl         前序内置策略或上游定制器处理后的当前 URL 结果
 * @author Ateng
 * @since 2026-11-26
 */
public record OperateLogUrlContext(
        HttpServletRequest request,
        Method method,
        OperateLog operateLog,
        OperateLogUrlStrategy effectiveStrategy,
        String rawUri,
        String bestMatchingPattern,
        String resolvedUrl
) {
}
