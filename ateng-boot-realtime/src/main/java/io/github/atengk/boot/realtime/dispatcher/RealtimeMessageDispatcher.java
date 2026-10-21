package io.github.atengk.boot.realtime.dispatcher;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.annotation.RealtimeAction;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端上行消息统一分发路由调度引擎
 * <p>
 * 容器启动完成后自动扫描所有 Spring Bean 中声明的 &#64;RealtimeAction 方法并建立映射，
 * 接收客户端上行消息时，优先反射路由执行目标方法，未命中时委托 RealtimeMessageListener SPI 兜底。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RealtimeMessageDispatcher implements SmartInitializingSingleton, ApplicationContextAware {

    private ApplicationContext applicationContext;
    private final ObjectProvider<RealtimeMessageListener> messageListeners;

    /**
     * 事件动作路由表: event -> ActionMethod
     */
    private final Map<String, ActionMethod> actionMap = new ConcurrentHashMap<>();

    public RealtimeMessageDispatcher(ObjectProvider<RealtimeMessageListener> messageListeners) {
        this.messageListeners = messageListeners;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (applicationContext == null) {
            return;
        }
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> clazz = bean.getClass();
                Method[] methods = clazz.getDeclaredMethods();
                for (Method method : methods) {
                    RealtimeAction action = AnnotationUtils.findAnnotation(method, RealtimeAction.class);
                    if (action != null) {
                        String event = action.value().trim();
                        if (actionMap.containsKey(event)) {
                            ActionMethod existing = actionMap.get(event);
                            log.warn("[Realtime] 检测到重复的 @RealtimeAction 事件绑定: event={}, 覆盖 [{}.{}] -> [{}.{}]",
                                    event, existing.bean.getClass().getSimpleName(), existing.method.getName(),
                                    clazz.getSimpleName(), method.getName());
                        }
                        method.setAccessible(true);
                        actionMap.put(event, new ActionMethod(bean, method));
                        log.debug("[Realtime] 注册上行 Action 路由: event={} -> {}.{}()",
                                event, clazz.getSimpleName(), method.getName());
                    }
                }
            } catch (Throwable t) {
                // 忽略非标准 Bean 获取异常
            }
        }
        log.info("[Realtime] 上行 Action 路由引擎初始化完成，共纳管 [{}] 个事件动作处理方法", actionMap.size());
    }

    /**
     * 编程式显式注册 Action 处理方法（主要用于测试与动态插件扩展）
     *
     * @param event  事件标识
     * @param bean   目标 Bean 实例
     * @param method 目标方法
     */
    public void registerAction(String event, Object bean, Method method) {
        if (event != null && bean != null && method != null) {
            method.setAccessible(true);
            actionMap.put(event.trim(), new ActionMethod(bean, method));
        }
    }

    /**
     * 调度分发上行消息
     *
     * @param session 发起端会话
     * @param message 实时消息
     * @return 是否成功被消费处理
     */
    public boolean dispatch(RealtimeSession session, RealtimeMessage<?> message) {
        if (session == null || message == null || message.getEvent() == null) {
            return false;
        }
        String event = message.getEvent().trim();

        // 1. 优先匹配 @RealtimeAction 注解处理方法
        ActionMethod actionMethod = actionMap.get(event);
        if (actionMethod != null) {
            try {
                Object[] args = resolveArguments(actionMethod.method, session, message);
                Object result = actionMethod.method.invoke(actionMethod.bean, args);
                // 若方法返回 RealtimeMessage 实例，则自动回执下发给当前会话
                if (result instanceof RealtimeMessage<?> replyMsg) {
                    session.sendMessage(replyMsg);
                }
                return true;
            } catch (Throwable t) {
                log.error("[Realtime] 执行 @RealtimeAction [{}] 异常: sessionKey={}, error={}",
                        event, session.getSessionKey(), t.getMessage(), t);
                return false;
            }
        }

        // 2. 未匹配到注解时，委托 RealtimeMessageListener SPI 兜底处理
        if (messageListeners != null) {
            boolean[] handled = {false};
            messageListeners.orderedStream().forEach(listener -> {
                try {
                    listener.onMessage(session, message);
                    handled[0] = true;
                } catch (Throwable t) {
                    log.warn("[Realtime] RealtimeMessageListener 执行异常: listener={}, error={}",
                            listener.getClass().getName(), t.getMessage());
                }
            });
            if (handled[0]) {
                return true;
            }
        }

        // 3. 既无注解也无监听器匹配
        log.debug("[Realtime] 未匹配到 Action 处理方法或监听器: event={}, sessionKey={}",
                event, session.getSessionKey());
        return false;
    }

    /**
     * 自适应解析目标方法的调用参数
     */
    private Object[] resolveArguments(Method method, RealtimeSession session, RealtimeMessage<?> message) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (RealtimeSession.class.isAssignableFrom(type)) {
                args[i] = session;
            } else if (SessionKey.class.isAssignableFrom(type)) {
                args[i] = session.getSessionKey();
            } else if (RealtimeMessage.class.isAssignableFrom(type)) {
                args[i] = message;
            } else {
                // 尝试将 message.getData() 注入为强类型入参
                Object data = message.getData();
                if (data != null) {
                    if (type.isInstance(data)) {
                        args[i] = data;
                    } else if (data instanceof String str && type == String.class) {
                        args[i] = str;
                    } else {
                        try {
                            args[i] = JsonUtil.parseObject(JsonUtil.toJsonString(data), type);
                        } catch (Exception e) {
                            log.warn("[Realtime] 业务载荷参数转换失败: method={}, targetType={}, error={}",
                                    method.getName(), type.getSimpleName(), e.getMessage());
                            args[i] = null;
                        }
                    }
                } else {
                    args[i] = null;
                }
            }
        }
        return args;
    }

    private static class ActionMethod {
        private final Object bean;
        private final Method method;

        public ActionMethod(Object bean, Method method) {
            this.bean = bean;
            this.method = method;
        }
    }
}
