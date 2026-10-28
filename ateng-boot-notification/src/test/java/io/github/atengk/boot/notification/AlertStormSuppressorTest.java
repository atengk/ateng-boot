package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.alert.AlertContext;
import io.github.atengk.boot.notification.alert.AlertStormSuppressor;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationAlertProperties;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 防告警风暴聚合抑制引擎 AlertStormSuppressor 单元测试与高并发 500+ 抗洪测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("AlertStormSuppressor 防告警风暴抑制引擎测试")
class AlertStormSuppressorTest {

    private NotificationTemplate mockTemplate;
    private NotificationAlertProperties alertProperties;
    private ScheduledExecutorService scheduler;
    private AlertStormSuppressor suppressor;
    private List<NotificationMessage> sentMessages;

    @BeforeEach
    void setUp() {
        mockTemplate = mock(NotificationTemplate.class);
        alertProperties = new NotificationAlertProperties();
        alertProperties.setSuppressWindow(Duration.ofMillis(100)); // 测试环境采用 100ms 极速窗口
        alertProperties.setDefaultChannel("ops-alert");

        sentMessages = new CopyOnWriteArrayList<>();
        when(mockTemplate.send(any(), any(NotificationMessage.class))).thenAnswer(invocation -> {
            NotificationMessage msg = invocation.getArgument(1);
            sentMessages.add(msg);
            return NotificationSendResult.success("msg-1", "ops-alert", Platform.DINGTALK, Duration.ofMillis(5), "ok");
        });
        when(mockTemplate.sendAsync(any(), any(NotificationMessage.class))).thenAnswer(invocation -> {
            NotificationMessage msg = invocation.getArgument(1);
            sentMessages.add(msg);
            return CompletableFuture.completedFuture(
                    NotificationSendResult.success("msg-1", "ops-alert", Platform.DINGTALK, Duration.ofMillis(5), "ok")
            );
        });

        scheduler = Executors.newScheduledThreadPool(4);
        suppressor = new AlertStormSuppressor(mockTemplate, alertProperties, scheduler);
    }

    @AfterEach
    void tearDown() {
        suppressor.destroy();
        scheduler.shutdownNow();
    }

    private AlertContext buildTestAlert(String fingerprint, String methodSig) {
        return buildTestAlert(fingerprint, methodSig, Duration.ofMillis(100));
    }

    private AlertContext buildTestAlert(String fingerprint, String methodSig, Duration window) {
        return AlertContext.builder()
                .fingerprint(fingerprint)
                .channel("ops-alert")
                .title("订单处理未捕获异常")
                .methodSignature(methodSig)
                .lineNumber(42)
                .exceptionClassName("java.lang.NullPointerException")
                .exceptionMessage("NPE test error")
                .rootCauseClassName("java.lang.NullPointerException")
                .rootCauseMessage("NPE test error")
                .traceId("trace-123456")
                .userId("admin-test")
                .desensitizedArgs("{\"orderId\":\"ORD001\",\"password\":\"******\"}")
                .topStackFrames("  at com.example.OrderService.pay(OrderService.java:42)")
                .suppressWindow(window)
                .occurrenceTime(LocalDateTime.now())
                .build();
    }

    private NullPointerException createNpe(String message) {
        return new NullPointerException(message);
    }

    @Test
    @DisplayName("验证首发异常即时推送红色紧急告警卡片")
    void testFirstOccurrenceDispatchesEmergencyCard() {
        AlertContext alert = buildTestAlert("fp-001", "OrderService#pay", Duration.ofSeconds(10));

        boolean sent = suppressor.processAlert(alert);
        assertTrue(sent);

        assertEquals(1, sentMessages.size());
        NotificationMessage msg = sentMessages.get(0);
        assertInstanceOf(CardMessage.class, msg);

        CardMessage card = (CardMessage) msg;
        assertEquals(CardColor.RED, card.getHeaderColor());
        assertThat(card.getTitle()).contains("[紧急告警]");
        assertThat(card.getContent())
                .contains("OrderService#pay")
                .contains("trace-123456")
                .contains("admin-test")
                .contains("ORD001")
                .contains("******")
                .contains("com.example.OrderService.pay(OrderService.java:42)");

        assertEquals(1, suppressor.getActiveWindowsCount());
    }

    @Test
    @DisplayName("验证窗口期内重复异常被静默拦截，原子计数器正确累加")
    void testDuplicatesWithinWindowAreSuppressed() {
        AlertContext alert = buildTestAlert("fp-002", "OrderService#pay", Duration.ofSeconds(10));

        // 首发透传
        assertTrue(suppressor.processAlert(alert));
        assertEquals(1, sentMessages.size());

        // 连续 9 次相同指纹的告警全部被静默拦截
        for (int i = 0; i < 9; i++) {
            boolean result = suppressor.processAlert(alert);
            assertFalse(result);
        }

        // 消息总量依然为 1，无重复刷屏
        assertEquals(1, sentMessages.size());

        AlertStormSuppressor.SuppressRecord record = suppressor.getRecord("fp-002");
        assertNotNull(record);
        assertEquals(9, record.getSuppressedCount());
    }

    @Test
    @DisplayName("验证窗口期满后触发阶段三：补发橙色汇总聚合卡片")
    void testWindowExpirationDispatchesAggregationSummary() throws InterruptedException {
        AlertContext alert = buildTestAlert("fp-003", "OrderService#pay");

        // 1 次首发 + 5 次窗口期抑制
        suppressor.processAlert(alert);
        for (int i = 0; i < 5; i++) {
            suppressor.processAlert(alert);
        }

        assertEquals(1, sentMessages.size());

        // 等待 100ms 窗口到期执行
        TimeUnit.MILLISECONDS.sleep(180);

        // 验证已补发汇总通报卡片
        assertEquals(2, sentMessages.size());
        NotificationMessage summaryMsg = sentMessages.get(1);
        assertInstanceOf(CardMessage.class, summaryMsg);

        CardMessage summaryCard = (CardMessage) summaryMsg;
        assertEquals(CardColor.ORANGE, summaryCard.getHeaderColor());
        assertThat(summaryCard.getTitle()).contains("【告警汇总】");
        assertThat(summaryCard.getContent())
                .contains("累计重复触发了 **5** 次")
                .contains("fp-003")
                .contains("trace-123456")
                .contains("OrderService#pay");

        // 窗口已自动关闭移除
        assertEquals(0, suppressor.getActiveWindowsCount());
    }

    @Test
    @DisplayName("验证窗口期内无重复异常时（count=0），到期后不发送冗余汇总")
    void testWindowExpirationWithoutDuplicatesDoesNotDispatchSummary() throws InterruptedException {
        AlertContext alert = buildTestAlert("fp-004", "OrderService#pay");

        // 仅首发 1 次，无重复
        suppressor.processAlert(alert);
        assertEquals(1, sentMessages.size());

        TimeUnit.MILLISECONDS.sleep(180);

        // 到期后不应有第 2 条汇总消息
        assertEquals(1, sentMessages.size());
        assertEquals(0, suppressor.getActiveWindowsCount());
    }

    @Test
    @DisplayName("验证 MD5 异常指纹计算确定性与同因归纳")
    void testFingerprintDeterminism() throws NoSuchMethodException {
        Method method1 = String.class.getMethod("substring", int.class);
        Method method2 = String.class.getMethod("indexOf", int.class);

        NullPointerException npe1 = createNpe("order 1001 failed");
        NullPointerException npe2 = createNpe("order 1002 failed");

        // 相同方法与相同异常类型（错误描述不同）归纳为相同指纹
        String fp1 = AlertStormSuppressor.computeFingerprint(method1, npe1);
        String fp2 = AlertStormSuppressor.computeFingerprint(method1, npe2);
        assertEquals(fp1, fp2);

        // 不同方法产生不同指纹
        String fp3 = AlertStormSuppressor.computeFingerprint(method2, npe1);
        assertNotEquals(fp1, fp3);

        // 不同异常类型产生不同指纹
        IllegalArgumentException iae = new IllegalArgumentException("arg invalid");
        String fp4 = AlertStormSuppressor.computeFingerprint(method1, iae);
        assertNotEquals(fp1, fp4);
    }

    @Test
    @DisplayName("高并发极限抗洪测试：500+ 并发瞬时异常仅首发 1 次紧急卡片，到期精准汇总 499 次")
    void testHighConcurrencyAntiStorm500Requests() throws InterruptedException {
        int totalRequests = 500;
        String fingerprint = "fp-storm-500";
        AlertContext alert = buildTestAlert(fingerprint, "StormService#process");

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalRequests);

        AtomicInteger initialSentCount = new AtomicInteger();
        AtomicInteger suppressedCount = new AtomicInteger();

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    boolean sent = suppressor.processAlert(alert);
                    if (sent) {
                        initialSentCount.incrementAndGet();
                    } else {
                        suppressedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        // 瞬间并发倾泻 500 个请求
        startGate.countDown();
        assertTrue(endGate.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // 阶段一与阶段二强断言：500 个瞬时并发下，首发紧急告警严格只有 1 次，被抑制静默拦截严格为 499 次！
        assertEquals(1, initialSentCount.get());
        assertEquals(totalRequests - 1, suppressedCount.get());
        assertEquals(1, sentMessages.size());

        // 等待 180ms 窗口到期触发阶段三汇总通报
        TimeUnit.MILLISECONDS.sleep(180);

        // 强断言：消息总量严格为 2（首发 1 次 + 汇总 1 次）
        assertEquals(2, sentMessages.size());
        CardMessage summaryCard = (CardMessage) sentMessages.get(1);
        assertThat(summaryCard.getContent()).contains("累计重复触发了 **499** 次");
    }

    @Test
    @DisplayName("验证多层框架包装异常能够穿透至业务类提取真实首发行号")
    void testFindFirstBusinessLineNumberWithNestedExceptions() throws NoSuchMethodException {
        java.lang.reflect.Method testMethod = AlertStormSuppressorTest.class.getDeclaredMethod("testFindFirstBusinessLineNumberWithNestedExceptions");

        // 模拟业务层抛出的 NPE（带有当前类的堆栈）
        NullPointerException businessEx = new NullPointerException("真实业务异常");
        // 外层被框架包裹两层
        IllegalStateException wrapper1 = new IllegalStateException("框架包装异常 1", businessEx);
        RuntimeException wrapper2 = new RuntimeException("框架包装异常 2", wrapper1);

        int lineNo = AlertStormSuppressor.findFirstBusinessLineNumber(wrapper2, testMethod);
        assertTrue(lineNo > 0, "应穿透到内部业务类的真实代码行号");
    }
}
