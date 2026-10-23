package io.github.atengk.boot.sample.ssh.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.ssh.model.SshShellOptions;
import io.github.atengk.boot.ssh.shell.SshShellHandle;
import io.github.atengk.boot.ssh.template.SshTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * WebSSH 交互式终端 WebSocket 协议处理器
 * <p>
 * 基于 Spring 原生 WebSocket 抽象，将前端 xterm.js 终端的键盘按键与视口 resize 消息透明代理至
 * 后端 {@link SshShellHandle} 伪终端会话管道，并实现连接断开时的确定性双向清理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSshWebSocketHandler extends AbstractWebSocketHandler {

    private static final String SHELL_SESSION_KEY = "SSH_SHELL_HANDLE";

    private final SshTemplate sshTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSSH: 收到前端 WebSocket 建立连接请求 [sessionId={}]", session.getId());

        try {
            // 开启交互式 PTY 伪终端通道 (默认 xterm-256color, 80x24)
            SshShellHandle shell = sshTemplate.openShell(SshShellOptions.defaults());
            session.getAttributes().put(SHELL_SESSION_KEY, shell);

            // 1. 挂载远端终端回显监听器 -> 实时推送回前端 WebSocket
            shell.onOutput(bytes -> {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes)));
                    } catch (IOException e) {
                        log.warn("WebSSH: 向前端 WebSocket 推送终端输出异常: {}", e.getMessage());
                    }
                }
            });

            // 2. 挂载远端通道关闭监听 -> 联动关闭前端 WebSocket 会话
            shell.onClose(() -> {
                log.info("WebSSH: 远端 SSH 终端已关闭，正在同步切断 WebSocket 连接 [sessionId={}]", session.getId());
                if (session.isOpen()) {
                    try {
                        session.close(CloseStatus.NORMAL);
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (Exception e) {
            log.error("WebSSH: 开启 SSH 伪终端失败: {}", e.getMessage(), e);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SshShellHandle shell = (SshShellHandle) session.getAttributes().get(SHELL_SESSION_KEY);
        if (shell == null || !shell.isAlive()) {
            return;
        }

        String payload = message.getPayload();
        // 尝试解析视口调整 JSON 协议包 (如 {"action":"resize","cols":120,"rows":40})
        if (payload.trim().startsWith("{") && payload.contains("resize")) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                if ("resize".equalsIgnoreCase(root.path("action").asText())) {
                    int cols = root.path("cols").asInt(80);
                    int rows = root.path("rows").asInt(24);
                    shell.resize(cols, rows);
                    log.debug("WebSSH: 收到前端视口调整请求 [cols={}, rows={}]", cols, rows);
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        // 普通字符或按键输入直接透传给远端伪终端
        shell.sendInput(payload);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SshShellHandle shell = (SshShellHandle) session.getAttributes().get(SHELL_SESSION_KEY);
        if (shell != null && shell.isAlive()) {
            ByteBuffer byteBuffer = message.getPayload();
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);
            shell.sendInput(bytes);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSSH: 前端 WebSocket 连接断开 [sessionId={}, status={}]", session.getId(), status);
        SshShellHandle shell = (SshShellHandle) session.getAttributes().remove(SHELL_SESSION_KEY);
        if (shell != null && shell.isAlive()) {
            shell.close();
        }
    }
}
