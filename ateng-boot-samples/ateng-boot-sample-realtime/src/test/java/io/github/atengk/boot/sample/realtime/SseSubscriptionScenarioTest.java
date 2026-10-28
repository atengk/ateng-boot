package io.github.atengk.boot.sample.realtime;

import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 2：SSE 客户端订阅与打字机流式推流实测
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("场景 2: SSE 客户端订阅与打字机流式推流实测")
class SseSubscriptionScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RealtimeTemplate realtimeTemplate;

    @Test
    @DisplayName("端到端验证: SSE 客户端建立长连接并成功接收 RealtimeTemplate.sendToUser 下发消息")
    void testSseSubscribeAndReceivePush() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String sseUrl = "http://127.0.0.1:" + port + "/sse/realtime?userId=sse-user-01&clientId=sse-sub-01";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sseUrl))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        CompletableFuture<HttpResponse<java.io.InputStream>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());

        HttpResponse<java.io.InputStream> response = future.get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);

        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        CompletableFuture.runAsync(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.offer(line);
                    }
                }
            } catch (Exception ignored) {
            }
        });

        // 异步通过 RealtimeTemplate 向该用户下发消息
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(300);
                realtimeTemplate.sendToUser("sse-user-01", RealtimeMessage.of("notice:sse", "Hello SSE Client!"));
            } catch (Exception ignored) {
            }
        });

        // 验证读取到包含 notice:sse 的事件行或数据行
        boolean received = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String l = lines.poll(1, TimeUnit.SECONDS);
            if (l != null && (l.contains("notice:sse") || l.contains("Hello SSE Client!"))) {
                received = true;
                break;
            }
        }
        assertThat(received).isTrue();
        response.body().close();
    }

    @Test
    @DisplayName("端到端验证: 消费 SseStreamHelper 打字机流式推流并成功接收 Chunk")
    void testStreamLlmChunks() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String streamUrl = "http://127.0.0.1:" + port + "/api/realtime/stream-llm";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(streamUrl))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);

        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        StringBuilder receivedData = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                receivedData.append(line.substring(5).trim());
            }
        }
        response.body().close();

        // 断言已完整接收到拼装的打字机流文本
        assertThat(receivedData.toString()).contains("Hello");
        assertThat(receivedData.toString()).contains("ateng-boot-realtime");
    }
}
