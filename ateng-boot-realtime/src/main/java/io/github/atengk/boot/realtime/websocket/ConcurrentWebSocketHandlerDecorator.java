package io.github.atengk.boot.realtime.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 并发安全 WebSocketHandler 装饰器
 * <p>
 * 自动为底层物理 WebSocketSession 统装 {@link ConcurrentWebSocketSessionDecorator}，
 * 内置排队缓冲队列限制与发送超时保护，彻底杜绝并发写入时因底层容器抛出 TEXT_PARTIAL_WRITING 导致连接意外掐断。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class ConcurrentWebSocketHandlerDecorator extends WebSocketHandlerDecorator {

    /**
     * 会话属性中绑定的并发安全装饰器 Session 键名
     */
    public static final String CONCURRENT_SESSION_ATTR = "ATENG_CONCURRENT_SESSION";

    private final int sendTimeLimit;
    private final int bufferSizeLimit;
    private final Map<String, WebSocketSession> sessionRegistry = new ConcurrentHashMap<>();

    /**
     * 构造并发安全处理器装饰器
     *
     * @param delegate        目标底层 WebSocketHandler 实例
     * @param sendTimeLimit   单次发送超时阈值（毫秒）
     * @param bufferSizeLimit 发送排队缓冲区上限（字节数）
     */
    public ConcurrentWebSocketHandlerDecorator(WebSocketHandler delegate, int sendTimeLimit, int bufferSizeLimit) {
        super(delegate);
        this.sendTimeLimit = sendTimeLimit;
        this.bufferSizeLimit = bufferSizeLimit;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSession concurrentSession = new ConcurrentWebSocketSessionDecorator(session, sendTimeLimit, bufferSizeLimit);
        session.getAttributes().put(CONCURRENT_SESSION_ATTR, concurrentSession);
        sessionRegistry.put(session.getId(), concurrentSession);
        log.trace("[Realtime] 装配并发安全装饰器 Session: sessionId={}, sendTimeLimit={}ms, bufferSizeLimit={}B",
                session.getId(), sendTimeLimit, bufferSizeLimit);
        super.afterConnectionEstablished(concurrentSession);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        WebSocketSession concurrentSession = getConcurrentSession(session);
        super.handleMessage(concurrentSession, message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        WebSocketSession concurrentSession = getConcurrentSession(session);
        super.handleTransportError(concurrentSession, exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        WebSocketSession concurrentSession = sessionRegistry.remove(session.getId());
        if (concurrentSession == null) {
            concurrentSession = getConcurrentSession(session);
        }
        super.afterConnectionClosed(concurrentSession, closeStatus);
    }

    /**
     * 从会话属性或本地注册表中检索已装饰的并发安全 Session
     *
     * @param session 原始或已装饰 Session
     * @return 并发安全 Session
     */
    public WebSocketSession getConcurrentSession(WebSocketSession session) {
        if (session instanceof ConcurrentWebSocketSessionDecorator) {
            return session;
        }
        WebSocketSession concurrentSession = (WebSocketSession) session.getAttributes().get(CONCURRENT_SESSION_ATTR);
        if (concurrentSession != null) {
            return concurrentSession;
        }
        concurrentSession = sessionRegistry.get(session.getId());
        return concurrentSession != null ? concurrentSession : session;
    }
}
