package io.github.atengk.boot.realtime.dispatcher;

import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.session.RealtimeSession;

/**
 * 客户端上行原始消息消费监听 SPI 扩展点
 * <p>
 * 当客户端上行消息未匹配到任何 &#64;RealtimeAction 注解方法时，
 * 分发引擎将回退委托给此监听器进行底层编程式消费。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@FunctionalInterface
public interface RealtimeMessageListener {

    /**
     * 接收并处理客户端上行原始消息
     *
     * @param session 上行发起端受管会话
     * @param message 实时业务消息对象
     */
    void onMessage(RealtimeSession session, RealtimeMessage<?> message);
}
