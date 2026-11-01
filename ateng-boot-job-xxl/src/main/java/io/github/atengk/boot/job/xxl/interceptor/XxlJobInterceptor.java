package io.github.atengk.boot.job.xxl.interceptor;

/**
 * XXL-Job 任务执行切面拦截器 SPI
 *
 * <p>为分布式受管任务提供三阶段切面扩展点（前置拦截判定、执行后置处理与终态清理），
 * 适用于审计日志登记、OpenTelemetry 全链路追踪跨度挂载、执行权限与节点灰度过滤等企业级横切关注点。
 * 多个拦截器可通过 Spring {@link org.springframework.core.annotation.Order} 注解或 {@link org.springframework.core.Ordered} 接口定义执行顺序。</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public interface XxlJobInterceptor {

    /**
     * 任务执行前置拦截判定
     *
     * <p>在任务执行前调用。若返回 {@code true}，则继续执行链中的下一个拦截器或目标任务方法；
     * 若返回 {@code false}，则中断后续拦截器与目标任务方法的执行，框架将自动记录
     * {@code [INTERCEPTED]} 拦截日志并向调度中心返回 200 SUCCESS 成功状态，防止运维告警风暴。</p>
     *
     * @param context 任务执行上下文元数据
     * @return true 继续执行；false 优雅拦截并终止
     * @throws Exception 拦截异常
     */
    default boolean preHandle(XxlJobExecutionContext context) throws Exception {
        return true;
    }

    /**
     * 任务执行后置处理
     *
     * <p>在目标任务方法成功执行完成后（未抛出异常时）调用。
     * 可用于捕获任务执行返回值、记录成功度量指标或填充上下文扩展属性。</p>
     *
     * @param context 任务执行上下文元数据
     * @param result  目标任务执行返回结果（void 方法为 null）
     * @throws Exception 处理异常
     */
    default void postHandle(XxlJobExecutionContext context, Object result) throws Exception {
    }

    /**
     * 任务执行完成终态清理
     *
     * <p>在任务生命周期结束时（无论目标方法成功执行完成还是抛出异常）无条件在 {@code finally} 块中调用。
     * 仅当当前拦截器的 {@link #preHandle} 执行且返回 {@code true} 时才会触发。
     * 可用于资源绝对释放、分布式上下文还原、监控跨度闭环等。</p>
     *
     * @param context   任务执行上下文元数据
     * @param throwable 任务执行抛出的异常（执行成功时为 null）
     * @throws Exception 清理异常（框架将自动捕获记录，不掩盖原始业务异常）
     */
    default void afterCompletion(XxlJobExecutionContext context, Throwable throwable) throws Exception {
    }
}
