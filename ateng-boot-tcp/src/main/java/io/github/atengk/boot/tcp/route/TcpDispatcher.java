package io.github.atengk.boot.tcp.route;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.tcp.annotation.TcpDeviceId;
import io.github.atengk.boot.tcp.annotation.TcpHeader;
import io.github.atengk.boot.tcp.annotation.TcpListener;
import io.github.atengk.boot.tcp.annotation.TcpMapping;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.spi.TcpMessageHandler;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * TCP 双轨消息路由分发与线程池异步隔离引擎
 * <p>
 * 统一管理声明式 {@link TcpListener} / {@link TcpMapping} 与函数式 {@link TcpMessageHandler} SPI，
 * 并全量派发至受管线程池异步隔离执行，彻底杜绝慢业务阻塞 Netty EventLoop。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpDispatcher implements ApplicationContextAware, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TcpDispatcher.class);
    private static final String MDC_TRACE_ID = "traceId";

    private final Map<String, MappingMethodMetadata> mappingRegistry = new ConcurrentHashMap<>();
    private final Map<String, SpiHandlerMetadata> spiRegistry = new ConcurrentHashMap<>();

    private ApplicationContext applicationContext;
    private final TcpRouteKeyExtractor routeKeyExtractor;
    private final Executor businessExecutor;

    public TcpDispatcher(@Nullable TcpRouteKeyExtractor routeKeyExtractor,
                         @Nullable Executor businessExecutor) {
        this(null, routeKeyExtractor, businessExecutor);
    }

    public TcpDispatcher(@Nullable ApplicationContext applicationContext,
                         @Nullable TcpRouteKeyExtractor routeKeyExtractor,
                         @Nullable Executor businessExecutor) {
        this.applicationContext = applicationContext;
        this.routeKeyExtractor = routeKeyExtractor != null ? routeKeyExtractor : new JsonRouteKeyExtractor();
        this.businessExecutor = businessExecutor;
    }

    /**
     * 获取当前分发器绑定的业务异步执行器（可能为 null，表示尚未注入外部受管线程池）
     *
     * @return 业务异步执行器
     */
    @Nullable
    public Executor getBusinessExecutor() {
        return this.businessExecutor;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        if (applicationContext != null) {
            initMappingMethods();
            initSpiHandlers();
        }
    }

    /**
     * 运行期编程式注册自定义 SPI 消息处理器（同命令字覆盖刷新）
     *
     * @param handler 函数式消息处理器
     */
    public void registerHandler(TcpMessageHandler<?> handler) {
        if (handler == null) {
            throw new IllegalArgumentException("TcpMessageHandler 不能为空");
        }
        String command = handler.getCommand();
        if (!StringUtils.hasText(command)) {
            throw new IllegalArgumentException("TcpMessageHandler 命令字不能为空");
        }
        String normalizedCommand = command.trim();
        Class<?> payloadType = ResolvableType.forClass(handler.getClass())
                .as(TcpMessageHandler.class)
                .getGeneric(0)
                .resolve(Object.class);
        spiRegistry.put(normalizedCommand, new SpiHandlerMetadata(handler, payloadType));
        log.info("[TCP-DISPATCHER] 注册 SPI 路由: command='{}' -> {}", normalizedCommand, handler.getClass().getName());
    }

    /**
     * 运行期编程式注销指定命令字的 SPI 消息处理器
     *
     * @param command 业务命令字
     * @return true 表示成功移除既有处理器；false 表示命令字为空或未注册
     */
    public boolean unregisterHandler(String command) {
        if (!StringUtils.hasText(command)) {
            return false;
        }
        String normalizedCommand = command.trim();
        boolean removed = spiRegistry.remove(normalizedCommand) != null;
        if (removed) {
            log.info("[TCP-DISPATCHER] 注销 SPI 路由: command='{}'", normalizedCommand);
        }
        return removed;
    }

    /**
     * 获取当前已注册的全部命令字集合（合并声明式注解与 SPI 处理器路由）
     *
     * @return 只读命令字集合（非 null 不可变集合，无匹配时返回空集合）
     */
    public Set<String> getRegisteredCommands() {
        Set<String> commands = new TreeSet<>();
        commands.addAll(mappingRegistry.keySet());
        commands.addAll(spiRegistry.keySet());
        return Collections.unmodifiableSet(commands);
    }

    /**
     * 分发入站 TCP 报文
     *
     * @param session 当前客户端连接会话
     * @param packet  入站数据包
     */
    public void dispatch(TcpSession session, TcpPacket packet) {
        if (session == null || packet == null) {
            return;
        }

        // 1. 提取路由命令字
        String command = routeKeyExtractor.extract(packet);
        if (!StringUtils.hasText(command)) {
            command = packet.getCommand();
        }
        if (!StringUtils.hasText(command)) {
            log.debug("[TCP-DISPATCHER] 报文无法识别命令字，会话={}, 丢弃或透传", session.getSessionId());
            return;
        }

        Map<String, Object> jsonMap = extractJsonMap(packet);

        // 自动提取关联流水号与设备标识（若原包未显式设定）
        if (!StringUtils.hasText(packet.getRequestId())) {
            String extractedReqId = TcpRequestCorrelationManager.DEFAULT_EXTRACTOR.extract(packet);
            if (StringUtils.hasText(extractedReqId)) {
                packet.setRequestId(extractedReqId);
            }
        }
        if (!StringUtils.hasText(packet.getDeviceId())) {
            String extractedDevId = extractDeviceId(jsonMap);
            if (StringUtils.hasText(extractedDevId)) {
                packet.setDeviceId(extractedDevId);
            }
        }

        final String finalCommand = command.trim();
        final MappingMethodMetadata mappingMeta = mappingRegistry.get(finalCommand);
        final SpiHandlerMetadata spiMeta = spiRegistry.get(finalCommand);

        if (mappingMeta == null && spiMeta == null) {
            log.debug("[TCP-DISPATCHER] 未找到匹配的命令字处理器: command={}, 会话={}", finalCommand, session.getSessionId());
            return;
        }

        // 2. 在 Netty I/O 线程无污染前提下，提取并冻结四维上下文头快照
        final Map<String, Object> contextHeaders = extractInboundContextHeaders(session, packet, jsonMap);

        Runnable task = () -> MessageContextPropagator.runInContext(contextHeaders, () -> {
            try {
                if (mappingMeta != null) {
                    invokeMappingMethod(session, packet, finalCommand, mappingMeta, jsonMap);
                }
                if (spiMeta != null) {
                    invokeSpiHandler(session, packet, spiMeta);
                }
            } catch (Throwable ex) {
                Throwable cause = unwrapInvocationException(ex);
                log.error("[TCP-DISPATCHER] 业务处理异常: command={}, requestId={}, 会话={}",
                        finalCommand, packet.getRequestId(), session.getSessionId(), cause);
                sendErrorResponseIfRequested(session, packet, finalCommand, cause);
            }
        });

        // 3. 异步派发至受管线程池隔离执行
        Executor targetExecutor = this.businessExecutor != null ? this.businessExecutor : ForkJoinPool.commonPool();
        targetExecutor.execute(task);
    }

    /**
     * 反射调用 @TcpMapping 方法
     */
    private void invokeMappingMethod(TcpSession session,
                                     TcpPacket packet,
                                     String command,
                                     MappingMethodMetadata meta,
                                     @Nullable Map<String, Object> jsonMap) throws Exception {
        Object[] args = resolveArguments(session, packet, meta.bean, meta.method, jsonMap);
        Object result = meta.method.invoke(meta.bean, args);

        // 如果处理方法有返回值，自动发回响应（支持带 requestId 的 RPC 回执或普通双向业务回包）
        if (result != null) {
            TcpPacket responsePacket;
            if (result instanceof TcpPacket p) {
                responsePacket = p;
                if (!StringUtils.hasText(responsePacket.getRequestId()) && StringUtils.hasText(packet.getRequestId())) {
                    responsePacket.setRequestId(packet.getRequestId());
                }
            } else {
                try {
                    String json = JsonUtil.toJsonString(result);
                    if (json != null && json.trim().startsWith("{")) {
                        com.fasterxml.jackson.databind.node.ObjectNode node =
                                (com.fasterxml.jackson.databind.node.ObjectNode) JsonUtil.parseObject(json, com.fasterxml.jackson.databind.JsonNode.class);
                        if (!node.has("requestId") && StringUtils.hasText(packet.getRequestId())) {
                            node.put("requestId", packet.getRequestId());
                        }
                        String respCommand = node.has("command") ? node.get("command").asText() : command;
                        if (!node.has("command") && StringUtils.hasText(command)) {
                            node.put("command", command);
                        }
                        responsePacket = TcpPacket.of(respCommand, packet.getRequestId(), TcpPayload.of(node.toString()));
                    } else {
                        Map<String, Object> respMap = new LinkedHashMap<>();
                        if (StringUtils.hasText(packet.getRequestId())) {
                            respMap.put("requestId", packet.getRequestId());
                        }
                        respMap.put("command", command);
                        respMap.put("data", result);
                        responsePacket = TcpPacket.of(command, packet.getRequestId(), TcpPayload.fromJson(respMap));
                    }
                } catch (Exception ex) {
                    responsePacket = TcpPacket.ofJson(command, result);
                    responsePacket.setRequestId(packet.getRequestId());
                }
            }
            if (StringUtils.hasText(packet.getDeviceId())) {
                responsePacket.setDeviceId(packet.getDeviceId());
            }

            applyDelimiterSymmetry(packet, responsePacket);
            session.send(responsePacket);
        }
    }

    private void sendErrorResponseIfRequested(TcpSession session, TcpPacket requestPacket, String command, Throwable cause) {
        if (!StringUtils.hasText(requestPacket.getRequestId()) || !session.isActive()) {
            return;
        }
        String errorMessage = StringUtils.hasText(cause.getMessage())
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("requestId", requestPacket.getRequestId());
        errorBody.put("command", command);
        errorBody.put("status", "ERROR");
        errorBody.put("message", errorMessage);

        TcpPacket errorPacket = TcpPacket.of(command, requestPacket.getRequestId(), TcpPayload.fromJson(errorBody));
        if (StringUtils.hasText(requestPacket.getDeviceId())) {
            errorPacket.setDeviceId(requestPacket.getDeviceId());
        }
        applyDelimiterSymmetry(requestPacket, errorPacket);
        try {
            session.send(errorPacket);
        } catch (Exception sendEx) {
            log.warn("[TCP-DISPATCHER] 回写 RPC 快速错误回执帧失败: command={}, requestId={}, 会话={}",
                    command, requestPacket.getRequestId(), session.getSessionId(), sendEx);
        }
    }

    private static void applyDelimiterSymmetry(TcpPacket requestPacket, TcpPacket responsePacket) {
        if (requestPacket.getPayload() == null || responsePacket.getPayload() == null) {
            return;
        }
        String reqText = requestPacket.getPayload().asString();
        if (reqText.endsWith("\r\n")) {
            responsePacket.setPayload(TcpPayload.of(responsePacket.getPayload().asString() + "\r\n"));
        } else if (reqText.endsWith("\n")) {
            responsePacket.setPayload(TcpPayload.of(responsePacket.getPayload().asString() + "\n"));
        }
    }

    private static Throwable unwrapInvocationException(Throwable ex) {
        if (ex instanceof InvocationTargetException ite && ite.getTargetException() != null) {
            return ite.getTargetException();
        }
        return ex;
    }

    private Map<String, Object> extractInboundContextHeaders(TcpSession session,
                                                             TcpPacket packet,
                                                             @Nullable Map<String, Object> jsonMap) {
        Map<String, Object> headers = new LinkedHashMap<>();

        String traceId = resolveContextHeader(MessageHeaderConstants.TRACE_ID, session, packet, jsonMap);
        if (!StringUtils.hasText(traceId)) {
            traceId = resolveContextHeader("traceId", session, packet, jsonMap);
        }
        if (!StringUtils.hasText(traceId)) {
            String currentMdcTrace = MDC.get(MDC_TRACE_ID);
            if (StringUtils.hasText(currentMdcTrace)) {
                traceId = currentMdcTrace;
            } else if (StringUtils.hasText(packet.getRequestId())) {
                traceId = packet.getRequestId();
            } else {
                traceId = IdUtil.fastSimpleUUID();
            }
        }
        headers.put(MessageHeaderConstants.TRACE_ID, traceId);

        copyIfHasText(headers, MessageHeaderConstants.TENANT_ID,
                resolveContextHeader(MessageHeaderConstants.TENANT_ID, session, packet, jsonMap));
        copyIfHasText(headers, MessageHeaderConstants.USER_ID,
                resolveContextHeader(MessageHeaderConstants.USER_ID, session, packet, jsonMap));
        copyIfHasText(headers, MessageHeaderConstants.IGNORE_TENANT,
                resolveContextHeader(MessageHeaderConstants.IGNORE_TENANT, session, packet, jsonMap));
        return headers;
    }

    private static void copyIfHasText(Map<String, Object> target, String key, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    @Nullable
    private String resolveContextHeader(String key,
                                        TcpSession session,
                                        TcpPacket packet,
                                        @Nullable Map<String, Object> jsonMap) {
        Object packetVal = packet.getHeader(key);
        if (packetVal != null) {
            return String.valueOf(packetVal);
        }
        Object jsonVal = extractPropertyFromJsonMap(jsonMap, key);
        if (jsonVal != null) {
            return String.valueOf(jsonVal);
        }
        Object sessionVal = session.getAttribute(key);
        return sessionVal != null ? String.valueOf(sessionVal) : null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private Map<String, Object> extractJsonMap(TcpPacket packet) {
        if (packet.getPayload() == null || packet.getPayload().isEmpty()) {
            return null;
        }
        String text = packet.getPayload().asString();
        if (text != null && text.trim().startsWith("{")) {
            try {
                return JsonUtil.parseObject(text, Map.class);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractDeviceId(@Nullable Map<String, Object> jsonMap) {
        Object devId = extractPropertyFromJsonMap(jsonMap, "deviceId");
        if (devId == null) {
            devId = extractPropertyFromJsonMap(jsonMap, "sn");
        }
        return devId != null ? devId.toString() : null;
    }

    @Nullable
    private Object extractPropertyFromJsonMap(@Nullable Map<String, Object> map, String propName) {
        if (map == null || propName == null) {
            return null;
        }
        if (map.containsKey(propName)) {
            return map.get(propName);
        }
        Object headersObj = map.get("headers");
        if (headersObj instanceof Map<?, ?> hmap && hmap.containsKey(propName)) {
            return hmap.get(propName);
        }
        return null;
    }

    /**
     * 智能解析处理方法的实参列表（并在存在 @Valid / @Validated 时执行 JSR-303 前置快速失败校验）
     */
    private Object[] resolveArguments(TcpSession session,
                                      TcpPacket packet,
                                      Object bean,
                                      Method method,
                                      @Nullable Map<String, Object> jsonMap) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];

        Validated methodValidated = AnnotatedElementUtils.findMergedAnnotation(method, Validated.class);
        Validated classValidated = AnnotatedElementUtils.findMergedAnnotation(bean.getClass(), Validated.class);
        boolean handlerRequiresValidation = methodValidated != null
                || classValidated != null
                || AnnotatedElementUtils.hasAnnotation(method, Valid.class);

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> type = param.getType();

            if (TcpSession.class.isAssignableFrom(type)) {
                args[i] = session;
            } else if (TcpPacket.class.isAssignableFrom(type)) {
                args[i] = packet;
            } else if (TcpPayload.class.isAssignableFrom(type)) {
                args[i] = packet.getPayload();
            } else if (param.isAnnotationPresent(TcpDeviceId.class)) {
                String devId = packet.getDeviceId();
                if (!StringUtils.hasText(devId)) {
                    devId = session.getDeviceId();
                }
                args[i] = devId;
            } else if (param.isAnnotationPresent(TcpHeader.class)) {
                TcpHeader headerAnno = param.getAnnotation(TcpHeader.class);
                String headerName = StringUtils.hasText(headerAnno.value()) ? headerAnno.value() : headerAnno.name();
                Object rawVal = packet.getHeader(headerName);
                if (rawVal == null) {
                    rawVal = extractPropertyFromJsonMap(jsonMap, headerName);
                }
                String val = rawVal != null ? String.valueOf(rawVal) : null;
                if (!StringUtils.hasText(val)) {
                    val = headerAnno.defaultValue();
                }
                args[i] = val;
            } else if (byte[].class.equals(type)) {
                args[i] = packet.getPayload() != null ? packet.getPayload().asBytes() : new byte[0];
            } else if (String.class.equals(type)) {
                args[i] = packet.getPayload() != null ? packet.getPayload().asText() : "";
            } else {
                // 复杂业务 POJO 载荷
                if (packet.getPayload() != null && !packet.getPayload().isEmpty()) {
                    String jsonText = packet.getPayload().asText();
                    if (StringUtils.hasText(jsonText)) {
                        args[i] = JsonUtil.parseObject(jsonText, type);
                    }
                }
                Validated paramValidated = AnnotatedElementUtils.findMergedAnnotation(param, Validated.class);
                boolean paramExplicitValid = param.isAnnotationPresent(Valid.class) || paramValidated != null;
                boolean shouldValidatePojo = paramExplicitValid
                        || (handlerRequiresValidation && !Map.class.isAssignableFrom(type)
                        && !org.springframework.beans.BeanUtils.isSimpleValueType(type));
                if (shouldValidatePojo) {
                    Class<?>[] groups = resolveValidationGroups(paramValidated, methodValidated, classValidated);
                    ValidatorUtil.validate(args[i], groups);
                }
            }
        }
        return args;
    }

    private static Class<?>[] resolveValidationGroups(@Nullable Validated paramValidated,
                                                      @Nullable Validated methodValidated,
                                                      @Nullable Validated classValidated) {
        if (paramValidated != null) {
            return paramValidated.value();
        }
        if (methodValidated != null) {
            return methodValidated.value();
        }
        if (classValidated != null) {
            return classValidated.value();
        }
        return new Class<?>[0];
    }

    /**
     * 调用 SPI 处理器
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeSpiHandler(TcpSession session, TcpPacket packet, SpiHandlerMetadata meta) {
        Object payload = null;
        Class<?> targetType = meta.payloadType;

        if (targetType != null && !Void.class.equals(targetType)) {
            if (TcpPacket.class.isAssignableFrom(targetType)) {
                payload = packet;
            } else if (TcpPayload.class.isAssignableFrom(targetType)) {
                payload = packet.getPayload();
            } else if (byte[].class.equals(targetType)) {
                payload = packet.getPayload() != null ? packet.getPayload().asBytes() : new byte[0];
            } else if (String.class.equals(targetType)) {
                payload = packet.getPayload() != null ? packet.getPayload().asText() : "";
            } else if (packet.getPayload() != null && !packet.getPayload().isEmpty()) {
                payload = JsonUtil.parseObject(packet.getPayload().asText(), targetType);
            }
        }
        ((TcpMessageHandler) meta.handler).handle(session, payload, packet);
    }

    /**
     * 扫描容器中的 @TcpListener 与 @TcpMapping
     */
    private void initMappingMethods() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(TcpListener.class);
        for (Object bean : beans.values()) {
            Method[] methods = ReflectionUtils.getAllDeclaredMethods(bean.getClass());
            for (Method method : methods) {
                TcpMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, TcpMapping.class);
                if (mapping != null) {
                    String command = StringUtils.hasText(mapping.command()) ? mapping.command() : mapping.value();
                    if (StringUtils.hasText(command)) {
                        ReflectionUtils.makeAccessible(method);
                        mappingRegistry.put(command.trim(), new MappingMethodMetadata(bean, method));
                        log.info("[TCP-DISPATCHER] 注册注解路由: command='{}' -> {}.{}()", command.trim(), bean.getClass().getSimpleName(), method.getName());
                    }
                }
            }
        }
    }

    /**
     * 扫描容器中的 TcpMessageHandler SPI
     */
    @SuppressWarnings("rawtypes")
    private void initSpiHandlers() {
        Map<String, TcpMessageHandler> beans = applicationContext.getBeansOfType(TcpMessageHandler.class);
        for (TcpMessageHandler handler : beans.values()) {
            if (StringUtils.hasText(handler.getCommand())) {
                registerHandler(handler);
            }
        }
    }

    private record MappingMethodMetadata(Object bean, Method method) {
    }

    private record SpiHandlerMetadata(TcpMessageHandler<?> handler, Class<?> payloadType) {
    }
}
