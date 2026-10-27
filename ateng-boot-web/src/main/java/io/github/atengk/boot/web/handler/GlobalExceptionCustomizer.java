package io.github.atengk.boot.web.handler;

import io.github.atengk.boot.core.model.Result;
import org.springframework.core.Ordered;

/**
 * 全局异常转译扩展点 SPI
 * 允许业务微服务在标准 {@link GlobalExceptionHandler} 默认规则执行前，前置自适应转译特定领域异常
 *
 * @author Ateng
 * @since 2026-10-27
 */
@FunctionalInterface
public interface GlobalExceptionCustomizer extends Ordered {

    /**
     * 尝试将异常转译为自定义统一 Result 契约
     * 若返回 null，表示当前转译器不处理该异常，交由责任链下游或默认处理器继续处理
     *
     * @param throwable 待处理的异常对象
     * @return 自定义 Result 响应结构；返回 null 表示跳过当前定制器
     */
    Result<?> customize(Throwable throwable);

    @Override
    default int getOrder() {
        return 0;
    }
}
