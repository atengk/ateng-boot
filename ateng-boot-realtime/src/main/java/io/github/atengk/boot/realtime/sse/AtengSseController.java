package io.github.atengk.boot.realtime.sse;

import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.UUID;

/**
 * 实时通信原生 SSE 长连接管理端点控制器
 * <p>
 * 对外暴露 GET /sse/realtime 统一端点，托管 SseEmitter 生命周期，
 * 自动绑定三维坐标 SessionKey 并注册到 RealtimeSessionManager 会话中心。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*")
public class AtengSseController {

    private final RealtimeProperties properties;
    private final RealtimeSessionManager sessionManager;

    /**
     * 建立 SSE 长连接流式事件通道
     *
     * @param clientId   客户端会话标识（可空，为空时自动生成 UUID）
     * @param userId     用户标识（可空，未提供时结合鉴权属性或匿名配置解析）
     * @param deviceType 设备类型（可空，默认: default）
     * @param tenantId   多租户标识（可空）
     * @param request    HTTP 请求上下文
     * @return SseEmitter 响应体
     */
    @GetMapping("${ateng.boot.realtime.sse.path:/sse/realtime}")
    public SseEmitter connect(@RequestParam(name = "clientId", required = false) String clientId,
                              @RequestParam(name = "userId", required = false) String userId,
                              @RequestParam(name = "deviceType", required = false) String deviceType,
                              @RequestParam(name = "tenantId", required = false) Long tenantId,
                              HttpServletRequest request) {

        // 1. 规范化客户端标识
        String actualClientId = (clientId != null && !clientId.isBlank())
                ? clientId.trim()
                : UUID.randomUUID().toString().replace("-", "");

        // 2. 规范化设备类型
        String actualDeviceType = (deviceType != null && !deviceType.isBlank())
                ? deviceType.trim()
                : SessionKey.DEFAULT_DEVICE_TYPE;

        // 3. 提取用户标识（支持 URL 参数传参或由安全拦截器注入的 request attribute）
        String actualUserId = (userId != null && !userId.isBlank()) ? userId.trim() : null;
        if (actualUserId == null && request != null) {
            Object attrUser = request.getAttribute("userId");
            if (attrUser != null) {
                actualUserId = String.valueOf(attrUser).trim();
            }
        }

        // 4. 身份校验与匿名防卫
        if (actualUserId == null || actualUserId.isBlank()) {
            if (properties.getAuth().isAllowAnonymous()) {
                actualUserId = SessionKey.ANONYMOUS_USER_PREFIX + actualClientId;
            } else {
                log.warn("[Realtime] SSE 拒绝未经身份验证的连接请求: clientId={}", actualClientId);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required for SSE connection");
            }
        }

        // 5. 构建 SessionKey 与受管 SseEmitter
        SessionKey sessionKey = SessionKey.of(actualUserId, actualDeviceType, actualClientId);
        Duration defaultTimeout = properties.getSse().getDefaultTimeout();
        SseEmitter emitter = SseStreamHelper.create(defaultTimeout);

        // 6. 注册生命周期托管到 RealtimeSessionManager
        RealtimeSession session = RealtimeSession.ofSse(sessionKey, emitter, tenantId, null);

        emitter.onCompletion(() -> {
            log.debug("[Realtime] SSE 连接自然关闭: sessionKey={}", sessionKey);
            session.markClosed();
            sessionManager.removeSession(sessionKey);
        });

        emitter.onTimeout(() -> {
            log.debug("[Realtime] SSE 连接空闲超时: sessionKey={}", sessionKey);
            session.markClosed();
            sessionManager.removeAndClose(sessionKey, RealtimeCloseStatus.IDLE_TIMEOUT);
        });

        emitter.onError(t -> {
            if (SseStreamHelper.isClientAbortException(t)) {
                log.debug("[Realtime] SSE 客户端主动关闭连接: sessionKey={}", sessionKey);
            } else {
                log.warn("[Realtime] SSE 传输异常中止: sessionKey={}, error={}", sessionKey, t.getMessage());
            }
            session.markClosed();
            sessionManager.removeSession(sessionKey);
        });

        boolean registered = sessionManager.registerSession(session);
        if (!registered) {
            SseStreamHelper.completeWithError(emitter, new IllegalStateException("Max connections exceeded"));
            return emitter;
        }

        // 7. 下发连接建立保底确认注释帧，防止代理网关超时掐断
        SseStreamHelper.sendComment(emitter, "connected:" + sessionKey.format());

        return emitter;
    }
}
