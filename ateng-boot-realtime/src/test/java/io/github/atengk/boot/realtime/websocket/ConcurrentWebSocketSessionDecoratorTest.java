package io.github.atengk.boot.realtime.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发安全会话装饰器对抗性测试 (TEXT_PARTIAL_WRITING 防卫实测)
 *
 * @author Ateng
 * @since 2026-10-21
 */
class ConcurrentWebSocketSessionDecoratorTest {

    @Test
    void testRawSessionThrowsPartialWritingExceptionUnderConcurrency() throws InterruptedException {
        MockPartialWritingSession rawSession = new MockPartialWritingSession();
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    rawSession.sendMessage(new TextMessage("msg-" + index));
                } catch (IllegalStateException e) {
                    if (e.getMessage().contains("TEXT_PARTIAL_WRITING")) {
                        exceptionCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // 原始未装饰 Session 在高并发下必定抛出 TEXT_PARTIAL_WRITING 异常
        assertThat(exceptionCount.get()).isPositive();
    }

    @Test
    void testConcurrentDecoratorCompletelyEliminatesPartialWritingException() throws Exception {
        MockPartialWritingSession rawSession = new MockPartialWritingSession();
        // 装配并发安全装饰器 (10s 超时, 512KB 缓冲区)
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(rawSession, 10000, 524288);

        int threadCount = 20;
        int messagesPerThread = 5;
        int totalExpectedMessages = threadCount * messagesPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int m = 0; m < messagesPerThread; m++) {
                        safeSession.sendMessage(new TextMessage("t" + threadId + "-m" + m));
                    }
                } catch (Throwable t) {
                    exceptionCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // 统装装饰器后，异常计数必须为 0，彻底根除 TEXT_PARTIAL_WRITING 崩溃
        assertThat(exceptionCount.get()).isZero();
        assertThat(rawSession.getDeliveredMessages()).hasSize(totalExpectedMessages);
    }

    /**
     * 模拟底层容器非线程安全物理连接的 TestDouble：
     * 若同一时刻检测到多线程并发写入，立即抛出标准 Tomcat TEXT_PARTIAL_WRITING 异常。
     */
    private static class MockPartialWritingSession implements WebSocketSession {
        private final AtomicBoolean isWriting = new AtomicBoolean(false);
        private final List<String> deliveredMessages = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Object> attributes = new HashMap<>();

        public List<String> getDeliveredMessages() {
            return deliveredMessages;
        }

        @Override
        public String getId() {
            return "mock-session-001";
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws/realtime");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 65536;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 65536;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return Collections.emptyList();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            // 对抗性并发写探测：模拟 Tomcat 严格排他状态机
            if (!isWriting.compareAndSet(false, true)) {
                throw new IllegalStateException("The remote endpoint was in state [TEXT_PARTIAL_WRITING] which is an invalid state for called method");
            }
            try {
                // 模拟物理网络 I/O 耗时 (3ms)
                Thread.sleep(3);
                deliveredMessages.add(message.getPayload().toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during send", e);
            } finally {
                isWriting.set(false);
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public void close(CloseStatus status) throws IOException {
        }
    }
}
