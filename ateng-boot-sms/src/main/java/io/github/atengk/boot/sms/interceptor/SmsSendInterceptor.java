package io.github.atengk.boot.sms.interceptor;

import io.github.atengk.boot.sms.model.SmsResult;
import org.springframework.core.Ordered;

/**
 * 统一短信出站发送拦截器 SPI
 * <p>
 * 支持按 {@link Ordered} 优先级排序织入发信全链路，在物理投递前执行阻断校验、请求重写或沙箱模拟，
 * 并在发送完成或发生异常时回调后置处理钩子。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public interface SmsSendInterceptor extends Ordered {

    /**
     * 发信前置拦截钩子（按 {@link #getOrder()} 升序执行）
     *
     * @param context 发送上下文（允许改写请求、渠道或注入短路结果）
     * @return true 表示放行继续执行；false 表示短路结束物理发信
     */
    default boolean preSend(SmsSendContext context) {
        return true;
    }

    /**
     * 发信后置成功钩子（按 {@link #getOrder()} 逆序执行）
     *
     * @param context 发送上下文
     * @param result  单发或批量代表性执行结果
     */
    default void postSend(SmsSendContext context, SmsResult result) {
    }

    /**
     * 发信异常回调钩子（按 {@link #getOrder()} 逆序执行）
     *
     * @param context 发送上下文
     * @param error   抛出的异常对象
     */
    default void onError(SmsSendContext context, Throwable error) {
    }

    /**
     * 拦截器执行优先级，数值越小越先执行，默认 0
     *
     * @return 优先级数值
     */
    @Override
    default int getOrder() {
        return 0;
    }
}
