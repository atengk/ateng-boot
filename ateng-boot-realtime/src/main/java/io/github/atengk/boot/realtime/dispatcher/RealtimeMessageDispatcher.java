package io.github.atengk.boot.realtime.dispatcher;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.realtime.annotation.RealtimeAction;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ObjectProvider<RealtimeActionInterceptor> actionInterceptors;

    /**
     * 事件动作路由表: event -> ActionMethod
     */
    private final Map<String, ActionMethod> actionMap = new ConcurrentHashMap<>();

    public RealtimeMessageDispatcher(ObjectProvider<RealtimeMessageListener> messageListeners) {
        this(messageListeners, null);
    }

    public RealtimeMessageDispatcher(ObjectProvider<RealtimeMessageListener> messageListeners,
                                     ObjectProvider<RealtimeActionInterceptor> actionInterceptors) {
        this.messageListeners = messageListeners;
        this.actionInterceptors = actionInterceptors;
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
                log.debug("[Realtime] 探测或扫描 Bean [{}] 失败: {}", beanName, t.getMessage());
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
            List<RealtimeActionInterceptor> interceptors = resolveInterceptors();
            int interceptorIndex = -1;
            Object actionResult = null;
            Throwable dispatchedException = null;
            try {
                // 1.1 绑定四维调度上下文（MDC / User / Tenant）
                bindDispatchContext(session, message);

                // 1.2 解析强类型参数并执行 JSR-303 前置 Fail-Fast 校验
                Object[] args = resolveArguments(actionMethod.method, session, message);

                // 1.3 按序执行 RealtimeActionInterceptor.preHandle 责任链
                for (int i = 0; i < interceptors.size(); i++) {
                    RealtimeActionInterceptor interceptor = interceptors.get(i);
                    if (!interceptor.preHandle(session, message, actionMethod.bean, actionMethod.method, args)) {
                        log.debug("[Realtime] @RealtimeAction [{}] 被前置拦截器中止: interceptor={}, sessionKey={}",
                                event, interceptor.getClass().getSimpleName(), session.getSessionKey());
                        return true;
                    }
                    interceptorIndex = i;
                }

                // 1.4 反射执行目标动作方法并处理双向 Request-Response 应答
                actionResult = actionMethod.method.invoke(actionMethod.bean, args);
                handleActionReply(session, message, event, actionResult);
                return true;
            } catch (Throwable t) {
                Throwable cause = (t instanceof InvocationTargetException ite && ite.getCause() != null)
                        ? ite.getCause()
                        : t;
                dispatchedException = cause;
                Result<Void> failResult = translateFailureResult(cause);
                if (failResult != null) {
                    log.warn("[Realtime] 执行 @RealtimeAction [{}] 校验或业务拦截: sessionKey={}, code={}, msg={}",
                            event, session.getSessionKey(), failResult.getCode(), failResult.getMsg());
                    handleActionReply(session, message, event, failResult);
                    return true;
                }
                log.error("[Realtime] 执行 @RealtimeAction [{}] 异常: sessionKey={}, error={}",
                        event, session.getSessionKey(), cause.getMessage(), cause);
                return false;
            } finally {
                // 先于上下文清理逆序触发 afterCompletion，保障拦截器收尾可读到完整上下文；随后确定性清空防污染
                try {
                    triggerAfterCompletion(interceptors, interceptorIndex, session, message, actionMethod, actionResult, dispatchedException);
                } finally {
                    clearDispatchContext();
                }
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
                            listener.getClass().getName(), t.getMessage(), t);
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
                // 尝试将 message.getData() 注入为强类型入参并执行 JSR-303 前置 Fail-Fast 校验
                Object data = message.getData();
                Object resolvedArg = null;
                if (data != null) {
                    if (type.isInstance(data)) {
                        resolvedArg = data;
                    } else if (data instanceof String str && type == String.class) {
                        resolvedArg = str;
                    } else {
                        try {
                            resolvedArg = JsonUtil.parseObject(JsonUtil.toJsonString(data), type);
                        } catch (Exception e) {
                            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                                    "请求载荷格式非法，无法解析为 [" + type.getSimpleName() + "]: " + e.getMessage(), e);
                        }
                    }
                }
                if (resolvedArg != null && (isCustomDtoType(type) || parameters[i].isAnnotationPresent(Valid.class))) {
                    ValidatorUtil.validate(resolvedArg);
                } else if (resolvedArg == null) {
                    NotNull notNullAnno = parameters[i].getAnnotation(NotNull.class);
                    if (notNullAnno != null) {
                        String customMsg = notNullAnno.message();
                        if (customMsg != null && !customMsg.isBlank() && !customMsg.startsWith("{")) {
                            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), customMsg);
                        }
                        ValidatorUtil.validate(null);
                    } else if (parameters[i].isAnnotationPresent(Valid.class)) {
                        ValidatorUtil.validate(null);
                    }
                }
                args[i] = resolvedArg;
            }
        }
        return args;
    }

    private boolean isCustomDtoType(Class<?> type) {
        if (BeanUtils.isSimpleValueType(type) || type.isArray()
                || Map.class.isAssignableFrom(type)
                || Collection.class.isAssignableFrom(type)) {
            return false;
        }
        String className = type.getName();
        return !className.startsWith("java.")
                && !className.startsWith("jakarta.")
                && !className.startsWith("org.springframework.");
    }

    private Result<Void> translateFailureResult(Throwable cause) {
        if (cause instanceof BusinessException be) {
            Integer code = be.getCode() != null ? be.getCode()
                    : (be.getResultCode() != null ? be.getResultCode().getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
            String msg = be.getMsg() != null ? be.getMsg()
                    : (be.getMessage() != null ? be.getMessage() : "Business error");
            return Result.failure(code, msg);
        }
        if (cause instanceof ConstraintViolationException cve) {
            Set<ConstraintViolation<?>> violations = cve.getConstraintViolations();
            String msg = (violations != null && !violations.isEmpty())
                    ? violations.iterator().next().getMessage()
                    : cve.getMessage();
            return Result.failure(CommonResultCode.BAD_REQUEST.getCode(),
                    msg != null && !msg.isBlank() ? msg : CommonResultCode.BAD_REQUEST.getMsg());
        }
        if (cause instanceof IllegalArgumentException iae) {
            String msg = iae.getMessage();
            return Result.failure(CommonResultCode.BAD_REQUEST.getCode(),
                    msg != null && !msg.isBlank() ? msg : CommonResultCode.BAD_REQUEST.getMsg());
        }
        return null;
    }

    /**
     * 处理 @RealtimeAction 执行结果的双向应答回发
     *
     * @param session        发起端会话
     * @param requestMessage 原始上行消息
     * @param event          触发的业务事件
     * @param result         方法返回值或异常转译结果
     */
    private void handleActionReply(RealtimeSession session, RealtimeMessage<?> requestMessage, String event, Object result) {
        if (result == null) {
            return;
        }
        try {
            if (result instanceof RealtimeMessage<?> replyMsg) {
                if (requestMessage.getId() != null) {
                    replyMsg.setId(requestMessage.getId());
                } else if (replyMsg.getId() == null) {
                    replyMsg.setId(IdUtil.nextIdStr());
                }
                if (replyMsg.getEvent() == null) {
                    replyMsg.setEvent(event + ":reply");
                }
                if (replyMsg.getTraceId() == null) {
                    replyMsg.setTraceId(MDC.get("traceId"));
                }
                if (replyMsg.getTenantId() == null) {
                    replyMsg.setTenantId(TenantContextHolder.getTenantId());
                }
                if (replyMsg.getTimestamp() == null) {
                    replyMsg.setTimestamp(System.currentTimeMillis());
                }
                session.sendMessage(replyMsg);
            } else {
                String replyEvent = event + ":reply";
                String requestId = requestMessage.getId();
                RealtimeMessage<Object> replyMsg = RealtimeMessage.of(requestId, replyEvent, result);
                session.sendMessage(replyMsg);
            }
        } catch (Throwable t) {
            log.error("[Realtime] 回发 @RealtimeAction 应答消息失败: event={}, sessionKey={}, error={}",
                    event, session.getSessionKey(), t.getMessage(), t);
        }
    }

    private List<RealtimeActionInterceptor> resolveInterceptors() {
        if (actionInterceptors == null) {
            return Collections.emptyList();
        }
        return actionInterceptors.orderedStream().toList();
    }

    private void bindDispatchContext(RealtimeSession session, RealtimeMessage<?> message) {
        String traceId = (message.getTraceId() != null && !message.getTraceId().isBlank())
                ? message.getTraceId()
                : null;
        if (traceId == null && session.getAttributes() != null) {
            Object attrTrace = session.getAttributes().get("traceId");
            if (attrTrace instanceof String str && !str.isBlank()) {
                traceId = str;
            }
        }
        if (traceId == null) {
            traceId = IdUtil.fastSimpleUUID();
        }
        MDC.put("traceId", traceId);

        if (session.getSessionKey() != null && session.getSessionKey().getUserId() != null) {
            UserContextHolder.set(session.getSessionKey().getUserId());
        }

        if (session.getTenantId() != null) {
            TenantContextHolder.setTenantId(session.getTenantId());
        } else if (message.getTenantId() != null && !message.getTenantId().isBlank()) {
            TenantContextHolder.setTenantId(message.getTenantId());
        }
    }

    private void triggerAfterCompletion(List<RealtimeActionInterceptor> interceptors,
                                        int interceptorIndex,
                                        RealtimeSession session,
                                        RealtimeMessage<?> message,
                                        ActionMethod actionMethod,
                                        Object actionResult,
                                        Throwable dispatchedException) {
        for (int i = interceptorIndex; i >= 0; i--) {
            RealtimeActionInterceptor interceptor = interceptors.get(i);
            try {
                interceptor.afterCompletion(
                        session, message, actionMethod.bean, actionMethod.method, actionResult, dispatchedException);
            } catch (Throwable ex) {
                log.warn("[Realtime] RealtimeActionInterceptor.afterCompletion 执行异常: interceptor={}, error={}",
                        interceptor.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        }
    }

    private void clearDispatchContext() {
        MDC.remove("traceId");
        UserContextHolder.clear();
        TenantContextHolder.clear();
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
