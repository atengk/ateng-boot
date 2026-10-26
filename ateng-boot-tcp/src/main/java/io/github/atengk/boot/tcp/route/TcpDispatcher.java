package io.github.atengk.boot.tcp.route;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.annotation.TcpDeviceId;
import io.github.atengk.boot.tcp.annotation.TcpHeader;
import io.github.atengk.boot.tcp.annotation.TcpListener;
import io.github.atengk.boot.tcp.annotation.TcpMapping;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.spi.TcpMessageHandler;
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

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

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

        // 自动提取关联流水号与设备标识（若原包未显式设定）
        if (!StringUtils.hasText(packet.getRequestId())) {
            String extractedReqId = TcpRequestCorrelationManager.DEFAULT_EXTRACTOR.extract(packet);
            if (StringUtils.hasText(extractedReqId)) {
                packet.setRequestId(extractedReqId);
            }
        }
        if (!StringUtils.hasText(packet.getDeviceId())) {
            String extractedDevId = extractDeviceId(packet);
            if (StringUtils.hasText(extractedDevId)) {
                packet.setDeviceId(extractedDevId);
            }
        }

        final String finalCommand = command;
        final MappingMethodMetadata mappingMeta = mappingRegistry.get(finalCommand);
        final SpiHandlerMetadata spiMeta = spiRegistry.get(finalCommand);

        if (mappingMeta == null && spiMeta == null) {
            log.debug("[TCP-DISPATCHER] 未找到匹配的命令字处理器: command={}, 会话={}", finalCommand, session.getSessionId());
            return;
        }

        // 2. 准备 MDC traceId 穿透
        String traceId = MDC.get(MDC_TRACE_ID);
        boolean generatedTraceId = false;
        if (!StringUtils.hasText(traceId)) {
            if (StringUtils.hasText(packet.getRequestId())) {
                traceId = packet.getRequestId();
            } else {
                traceId = IdUtil.fastSimpleUUID();
            }
            MDC.put(MDC_TRACE_ID, traceId);
            generatedTraceId = true;
        }

        final boolean finalGenerated = generatedTraceId;
        Runnable task = () -> {
            try {
                if (mappingMeta != null) {
                    invokeMappingMethod(session, packet, finalCommand, mappingMeta);
                }
                if (spiMeta != null) {
                    invokeSpiHandler(session, packet, spiMeta);
                }
            } catch (Throwable ex) {
                log.error("[TCP-DISPATCHER] 业务处理异常: command={}, 会话={}", finalCommand, session.getSessionId(), ex);
            } finally {
                if (finalGenerated) {
                    MDC.remove(MDC_TRACE_ID);
                }
            }
        };

        // 3. 异步派发至受管线程池隔离执行
        Runnable wrappedTask = AsyncContext.wrap(task);
        if (businessExecutor != null) {
            businessExecutor.execute(wrappedTask);
        } else {
            wrappedTask.run();
        }
    }

    /**
     * 反射调用 @TcpMapping 方法
     */
    private void invokeMappingMethod(TcpSession session, TcpPacket packet, String command, MappingMethodMetadata meta) throws Exception {
        Object[] args = resolveArguments(session, packet, meta.method);
        Object result = meta.method.invoke(meta.bean, args);

        // 如果调用端发起了 RPC 等待（带有 requestId），且处理方法有返回值，自动发回响应
        if (result != null && StringUtils.hasText(packet.getRequestId())) {
            TcpPacket responsePacket;
            if (result instanceof TcpPacket p) {
                responsePacket = p;
                if (!StringUtils.hasText(responsePacket.getRequestId())) {
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
                        if (!node.has("command") && StringUtils.hasText(command)) {
                            node.put("command", command);
                        }
                        responsePacket = TcpPacket.of(command, packet.getRequestId(), TcpPayload.of(node.toString()));
                    } else {
                        Map<String, Object> respMap = new LinkedHashMap<>();
                        respMap.put("requestId", packet.getRequestId());
                        respMap.put("command", command);
                        respMap.put("data", result);
                        responsePacket = TcpPacket.of(command, packet.getRequestId(), TcpPayload.fromJson(respMap));
                    }
                } catch (Exception ex) {
                    responsePacket = TcpPacket.ofJson(command, result);
                    responsePacket.setRequestId(packet.getRequestId());
                }
                if (StringUtils.hasText(packet.getDeviceId())) {
                    responsePacket.setDeviceId(packet.getDeviceId());
                }
            }

            // 保持分帧定界符对称性（若请求以换行符结尾，响应也追加换行符）
            if (packet.getPayload() != null) {
                String reqText = packet.getPayload().asString();
                if (reqText.endsWith("\r\n")) {
                    responsePacket.setPayload(TcpPayload.of(responsePacket.getPayload().asString() + "\r\n"));
                } else if (reqText.endsWith("\n")) {
                    responsePacket.setPayload(TcpPayload.of(responsePacket.getPayload().asString() + "\n"));
                }
            }
            session.send(responsePacket);
        }
    }

    private String extractDeviceId(TcpPacket packet) {
        Object devId = extractPropertyFromJson(packet, "deviceId");
        if (devId == null) {
            devId = extractPropertyFromJson(packet, "sn");
        }
        return devId != null ? devId.toString() : null;
    }

    private Object extractPropertyFromJson(TcpPacket packet, String propName) {
        if (packet.getPayload() == null || packet.getPayload().isEmpty()) {
            return null;
        }
        String text = packet.getPayload().asString();
        if (text != null && text.trim().startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JsonUtil.parseObject(text, Map.class);
                if (map != null) {
                    if (map.containsKey(propName)) {
                        return map.get(propName);
                    }
                    Object headersObj = map.get("headers");
                    if (headersObj instanceof Map<?, ?> hmap && hmap.containsKey(propName)) {
                        return hmap.get(propName);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 智能解析处理方法的实参列表
     */
    private Object[] resolveArguments(TcpSession session, TcpPacket packet, Method method) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];

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
                    rawVal = extractPropertyFromJson(packet, headerName);
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
            }
        }
        return args;
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
            String command = handler.getCommand();
            if (StringUtils.hasText(command)) {
                Class<?> payloadType = ResolvableType.forClass(handler.getClass())
                        .as(TcpMessageHandler.class)
                        .getGeneric(0)
                        .resolve(Object.class);
                spiRegistry.put(command.trim(), new SpiHandlerMetadata(handler, payloadType));
                log.info("[TCP-DISPATCHER] 注册 SPI 路由: command='{}' -> {}", command.trim(), handler.getClass().getName());
            }
        }
    }

    private record MappingMethodMetadata(Object bean, Method method) {
    }

    private record SpiHandlerMetadata(TcpMessageHandler<?> handler, Class<?> payloadType) {
    }
}
