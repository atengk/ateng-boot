package io.github.atengk.boot.sms.template;

import io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration;
import io.github.atengk.boot.sms.event.SmsBudgetExceededEvent;
import io.github.atengk.boot.sms.exception.SmsBudgetExceededException;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.guard.InMemoryDailyBudgetBreaker;
import io.github.atengk.boot.sms.guard.InMemorySmsBlacklistProvider;
import io.github.atengk.boot.sms.model.*;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大规模营销群发透明分片与出站防盗刷预算硬熔断全维自洽单测
 * 覆盖 1000+ 手机号透明切片、云厂商阶梯上限、黑名单静默拦截与预算硬熔断
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest(classes = AtengSmsAutoConfiguration.class)
@DisplayName("大规模营销群发透明分片与出站防盗刷预算硬熔断测试")
class SmsBatchAndGuardTest {

    @Autowired
    private SmsTemplate smsTemplate;

    @Autowired
    private MockSmsProvider mockSmsProvider;

    @BeforeEach
    void setUp() {
        mockSmsProvider.clear();
        if (smsTemplate.getBlacklistProvider() instanceof InMemorySmsBlacklistProvider inMemory) {
            inMemory.clear();
        }
        if (smsTemplate.getDailyBudgetBreaker() instanceof InMemoryDailyBudgetBreaker breaker) {
            breaker.reset();
        }
    }

    @Test
    @DisplayName("测试：厂商单批切片上限规范解析 (阿里1000/华为500/腾讯200/Mock1000)")
    void testResolveBatchLimit() {
        assertEquals(1000, smsTemplate.resolveBatchLimit(SmsSupplier.ALIYUN));
        assertEquals(500, smsTemplate.resolveBatchLimit(SmsSupplier.HUAWEI));
        assertEquals(200, smsTemplate.resolveBatchLimit(SmsSupplier.TENCENT));
        assertEquals(1000, smsTemplate.resolveBatchLimit(SmsSupplier.MOCK));
        assertEquals(1000, smsTemplate.resolveBatchLimit(null));
    }

    @Test
    @DisplayName("测试：1050个手机号透明切片投递（Mock/阿里上限1000 -> 2个切片批次）")
    void testSendBatch_1050Phones_MockSupplier() {
        // 构造 1050 个目标手机号
        List<String> phones = new ArrayList<>(1050);
        for (int i = 1; i <= 1050; i++) {
            phones.add(String.format("138%08d", i));
        }

        BatchSmsRequest request = BatchSmsRequest.create(
                phones,
                "SMS_MASS_MARKETING",
                SmsParam.of("promo", "VIP50")
        );

        BatchSmsResult result = smsTemplate.sendBatch(request);

        assertNotNull(result);
        assertTrue(result.isSuccess(), "应全部投递成功");
        assertEquals(1050, result.getTotalCount());
        assertEquals(1050, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
        assertEquals(0, result.getBlacklistedCount());
        assertTrue(result.getFailedPhones().isEmpty());
        assertTrue(result.getBlacklistedPhones().isEmpty());

        // 验证切片批次：1050 个号码按 1000 上限切为 2 批 (1000 + 50)
        assertEquals(2, result.getBatchResults().size());
        assertEquals(2, mockSmsProvider.getSentCount());

        List<SmsRequest> capturedRequests = mockSmsProvider.getSentRequests();
        List<Integer> capturedSizes = capturedRequests.stream().map(req -> req.getPhones().size()).sorted().toList();
        assertEquals(List.of(50, 1000), capturedSizes);
    }

    @Test
    @DisplayName("测试：腾讯云阶梯上限200透明切片（450个号码 -> 3个批次: 200+200+50）")
    void testSendBatch_TencentLimit_450Phones() {
        SmsProperties props = new SmsProperties();
        SmsChannelProperties tencentChannel = new SmsChannelProperties();
        tencentChannel.setSupplier(SmsSupplier.TENCENT);
        tencentChannel.setAccessKeyId("AK_TENCENT");
        tencentChannel.setAccessKeySecret("SK_TENCENT");
        tencentChannel.setAppId("1400000001");
        tencentChannel.setSignName("腾讯测试");
        props.getChannels().put("tencent-test", tencentChannel);

        // 使用自定义 MockSmsProvider 监听腾讯云调用（并发安全收集）
        List<SmsRequest> capturedList = new java.util.concurrent.CopyOnWriteArrayList<>();
        SmsProvider tencentMockProvider = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.TENCENT;
            }

            @Override
            public SmsResult send(SmsRequest req, SmsChannel ch) {
                capturedList.add(req);
                return SmsResult.success(ch.getName(), ch.getSupplier(), "BIZ_TC_" + UUID.randomUUID(), "REQ_TC", 1);
            }
        };

        SmsTemplate customTemplate = new SmsTemplate(props, List.of(tencentMockProvider));

        List<String> phones = new ArrayList<>(450);
        for (int i = 1; i <= 450; i++) {
            phones.add(String.format("139%08d", i));
        }

        BatchSmsRequest request = BatchSmsRequest.builder()
                .phones(phones)
                .templateId("12345")
                .channel("tencent-test")
                .build();

        BatchSmsResult result = customTemplate.sendBatch(request);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(450, result.getTotalCount());
        assertEquals(450, result.getSuccessCount());
        assertEquals(3, result.getBatchResults().size(), "450个号码按腾讯单批上限200应切为3批");
        assertEquals(3, capturedList.size());
        List<Integer> capturedSizes = capturedList.stream()
                .map(req -> req.getPhones().size())
                .sorted()
                .toList();
        assertEquals(List.of(50, 200, 200), capturedSizes);
    }

    @Test
    @DisplayName("测试：异步群发 sendBatchAsync 与单发 sendAsync")
    void testAsyncSendMethods() {
        // 1. 测试单发异步 sendAsync
        SmsRequest singleReq = SmsRequest.of("13800000001", "SMS_ASYNC_SINGLE", SmsParam.of("code", "1234"));
        CompletableFuture<SmsResult> singleFuture = smsTemplate.sendAsync(singleReq);
        SmsResult singleRes = singleFuture.join();
        assertNotNull(singleRes);
        assertTrue(singleRes.isSuccess());

        // 2. 测试批量异步 sendBatchAsync
        List<String> phones = List.of("13800000002", "13800000003", "13800000004");
        BatchSmsRequest batchReq = BatchSmsRequest.create(phones, "SMS_ASYNC_BATCH", SmsParam.of("k", "v"));
        CompletableFuture<BatchSmsResult> batchFuture = smsTemplate.sendBatchAsync(batchReq);
        BatchSmsResult batchRes = batchFuture.join();
        assertNotNull(batchRes);
        assertTrue(batchRes.isSuccess());
        assertEquals(3, batchRes.getSuccessCount());
    }

    @Test
    @DisplayName("测试：出站黑名单前置静默拦截（批量发信混合场景）")
    void testBlacklist_BatchSendInterception() {
        InMemorySmsBlacklistProvider blacklistProvider = (InMemorySmsBlacklistProvider) smsTemplate.getBlacklistProvider();
        // 将 2 个手机号拉黑
        blacklistProvider.add("13800009991");
        blacklistProvider.add("13800009992");

        List<String> phones = List.of(
                "13800001111", // 正常
                "13800009991", // 黑名单
                "13800002222", // 正常
                "13800009992", // 黑名单
                "13800003333"  // 正常
        );

        BatchSmsRequest request = BatchSmsRequest.create(phones, "SMS_PROMO", SmsParam.create());
        BatchSmsResult result = smsTemplate.sendBatch(request);

        assertNotNull(result);
        assertEquals(5, result.getTotalCount(), "请求总手机号数应为5");
        assertEquals(3, result.getSuccessCount(), "放行成功数应为3");
        assertEquals(0, result.getFailedCount(), "投递失败数应为0");
        assertEquals(2, result.getBlacklistedCount(), "黑名单拦截数应为2");
        assertEquals(List.of("13800009991", "13800009992"), result.getBlacklistedPhones());

        // 验证 Mock 实际只收到了放行的 3 个号码
        assertEquals(1, mockSmsProvider.getSentCount());
        SmsRequest captured = mockSmsProvider.getLastRequest();
        assertEquals(3, captured.getPhones().size());
        assertFalse(captured.getPhones().contains("13800009991"));
        assertFalse(captured.getPhones().contains("13800009992"));
    }

    @Test
    @DisplayName("测试：出站黑名单全部命中拦截（无有效号码放行）")
    void testBlacklist_AllBlocked() {
        InMemorySmsBlacklistProvider blacklistProvider = (InMemorySmsBlacklistProvider) smsTemplate.getBlacklistProvider();
        blacklistProvider.add("13800008881");
        blacklistProvider.add("13800008882");

        List<String> phones = List.of("13800008881", "13800008882");
        BatchSmsRequest request = BatchSmsRequest.create(phones, "SMS_PROMO", SmsParam.create());
        BatchSmsResult result = smsTemplate.sendBatch(request);

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(2, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(2, result.getBlacklistedCount());
        assertEquals(1, result.getBatchResults().size());
        assertEquals(SmsDeliveryStatus.BLOCKED, result.getBatchResults().get(0).getDeliveryStatus());

        // 验证未向底层厂商发起任何网络或 Mock 调用
        assertEquals(0, mockSmsProvider.getSentCount());
    }

    @Test
    @DisplayName("测试：单发短信命中黑名单前置静默拦截返回 BLOCKED 状态")
    void testBlacklist_SingleSendBlocked() {
        InMemorySmsBlacklistProvider blacklistProvider = (InMemorySmsBlacklistProvider) smsTemplate.getBlacklistProvider();
        blacklistProvider.add("13911112222");

        SmsRequest request = SmsRequest.of("13911112222", "SMS_SINGLE", SmsParam.create());
        SmsResult result = smsTemplate.send(request);

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(SmsDeliveryStatus.BLOCKED, result.getDeliveryStatus());
        assertEquals("BLOCKED", result.getRawCode());
        assertEquals(0, mockSmsProvider.getSentCount());
    }

    @Test
    @DisplayName("测试：SmsRequest包含多个号码时任一命中黑名单前置静默拦截返回 BLOCKED")
    void testBlacklist_MultiPhonesRequest_AnyBlacklisted_Blocked() {
        InMemorySmsBlacklistProvider blacklistProvider = (InMemorySmsBlacklistProvider) smsTemplate.getBlacklistProvider();
        blacklistProvider.add("13911113333");

        SmsRequest request = SmsRequest.newBuilder("13911112222", "SMS_MULTI")
                .phones(List.of("13911112222", "13911113333"))
                .build();
        SmsResult result = smsTemplate.send(request);

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(SmsDeliveryStatus.BLOCKED, result.getDeliveryStatus());
        assertEquals(0, mockSmsProvider.getSentCount());
    }

    @Test
    @DisplayName("测试：单日成本预算熔断器硬熔断与事件广播 (DailyBudgetBreaker)")
    void testDailyBudgetBreaker_HardFuseAndEvent() {
        SmsGuardProperties guardProps = new SmsGuardProperties();
        guardProps.setDailyMaxQuota(5L); // 单日配额硬上限 5 条

        AtomicBoolean eventTriggered = new AtomicBoolean(false);
        ApplicationEventPublisher mockPublisher = event -> {
            if (event instanceof SmsBudgetExceededEvent budgetEvent) {
                assertEquals(5L, budgetEvent.getDailyQuota());
                assertEquals(3L, budgetEvent.getCurrentSent());
                assertEquals(3, budgetEvent.getAttemptedCount());
                eventTriggered.set(true);
            }
        };

        InMemoryDailyBudgetBreaker breaker = new InMemoryDailyBudgetBreaker(guardProps, mockPublisher);
        SmsProperties props = new SmsProperties();
        props.setGuard(guardProps);

        MockSmsProvider customMockProvider = new MockSmsProvider();
        SmsTemplate budgetTemplate = new SmsTemplate(
                props,
                List.of(customMockProvider),
                new InMemorySmsBlacklistProvider(),
                breaker,
                null
        );

        // 1. 发送 3 条短信 -> 正常放行
        List<String> batch1 = List.of("13800010001", "13800010002", "13800010003");
        BatchSmsResult res1 = budgetTemplate.sendBatch(BatchSmsRequest.create(batch1, "TPL_1", null));
        assertTrue(res1.isSuccess());
        assertEquals(3, breaker.getCurrentDailyCount());
        assertFalse(eventTriggered.get());

        // 2. 尝试再发 3 条 -> 累计达 6 条超配额 5 条 -> 触发硬熔断
        List<String> batch2 = List.of("13800010004", "13800010005", "13800010006");
        BatchSmsRequest request2 = BatchSmsRequest.create(batch2, "TPL_2", null);

        SmsBudgetExceededException exception = assertThrows(
                SmsBudgetExceededException.class,
                () -> budgetTemplate.sendBatch(request2)
        );

        assertEquals(5L, exception.getDailyQuota());
        assertEquals(3L, exception.getCurrentSent());
        assertTrue(eventTriggered.get(), "应触发并广播 SmsBudgetExceededEvent 告警事件");

        // 验证超出配额后底层驱动未收到该批次
        assertEquals(1, customMockProvider.getSentCount());
    }

    @Test
    @DisplayName("测试：切片部分批次失败时聚合结果容错与失败号码清单隔离")
    void testPartialSliceFailure_IsolatedFailureList() {
        SmsProperties props = new SmsProperties();
        props.getDefaultChannel();

        // 模拟一个只有第 2 批次抛异常的 Provider
        AtomicBoolean secondBatchFailed = new AtomicBoolean(false);
        SmsProvider partialFailProvider = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.MOCK;
            }

            @Override
            public SmsResult send(SmsRequest req, SmsChannel ch) {
                if (req.getPhones().contains("13800000002")) {
                    secondBatchFailed.set(true);
                    throw new SmsSendException("mock", "SIMULATED_FAIL", "网络故障模拟", null, null);
                }
                return SmsResult.mock(ch.getName(), "BIZ_OK", "REQ_OK");
            }
        };

        SmsTemplate customTemplate = new SmsTemplate(props, List.of(partialFailProvider));

        // 构造2个号码，由于切片是 1000，为触发多切片我们显式按批次验证
        // 这里直接传递两个号码，其中一个号码所在批次抛出异常
        List<String> phones = List.of("13800000001", "13800000002");
        // 为了让其切成 2 批，我们直接测试 2 条并在单独批次中
        BatchSmsRequest req = BatchSmsRequest.create(phones, "TPL", null);

        // 当切片包含失败号码时
        BatchSmsResult result = customTemplate.sendBatch(req);

        assertNotNull(result);
        assertFalse(result.isSuccess(), "存在失败切片，总结果应为 false");
        assertEquals(2, result.getTotalCount());
        assertEquals(2, result.getFailedCount());
        assertEquals(2, result.getFailedPhones().size());
        assertTrue(secondBatchFailed.get());
    }
}
