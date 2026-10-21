package io.github.atengk.boot.realtime.annotation;

import java.lang.annotation.*;

/**
 * 声明式实时通信客户端上行事件动作处理注解
 * <p>
 * 标注在 Spring 受管 Bean 的方法上，将客户端发来的上行消息按事件标识（event）路由分发至对应方法执行。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RealtimeAction {

    /**
     * 绑定的业务事件标识名（如 "chat:send", "room:join", "order:sync"）
     *
     * @return 业务事件名
     */
    String value();
}
