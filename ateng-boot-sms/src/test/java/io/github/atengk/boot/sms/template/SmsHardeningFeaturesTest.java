package io.github.atengk.boot.sms.template;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration;
import io.github.atengk.boot.sms.context.SmsContextHolder;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.failover.SmsFailoverChain;
import io.github.atengk.boot.sms.model.*;
import io.github.atengk.boot.sms.properties.SmsTemplateProperties;
import io.github.atengk.boot.sms.provider.AbstractHttpSmsProvider;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工单 #759 深度加固特性全维自洽单测
 * 涵盖：跨云逻辑模板与专属签名转译、默认模板回退机制、RestClient 缓存复用、单渠道无状态瞬态重试、事务后上下文透传
 *
 * @author Ateng
 * @since 2026-11-07
 */
@SpringBootTest(classes = AtengSmsAutoConfiguration.class)
@DisplayName("工单 #759 深度加固特性单测")
class SmsHardeningFeaturesTest {

    @Autowired
    private SmsTemplate smsTemplate;

    @Autowired
    private MockSmsProvider mockSmsProvider;

    @Autowired
    private SmsChannelRegistry channelRegistry;

    @BeforeEach
    void setUp() {
        mockSmsProvider.clear();
        SmsContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @AfterEach
    void tearDown() {
        mockSmsProvider.clear();
        SmsContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("测试：SmsChannel 跨云逻辑模板转译、专属签名决策与参数顺序解析")
    void testLogicalTemplateAndSignResolution() {
        SmsTemplateProperties loginTpl = new SmsTemplateProperties();
        loginTpl.setTemplateId("SMS_ALI_LOGIN_001");
        loginTpl.setSignName("阿里验证码专属");
        loginTpl.setParamOrder(List.of("code", "expire"));

        SmsChannel channel = SmsChannel.builder()
                .name("aliyun-custom")
                .supplier(SmsSupplier.ALIYUN)
                .signName("阿里全局默认签名")
                .defaultTemplateId("SMS_DEFAULT_FALLBACK")
                .templates(Map.of("LOGIN_AUTH", loginTpl))
                .build();

        // 1. 命中逻辑模板转译物理 templateId
        assertEquals("SMS_ALI_LOGIN_001", channel.resolveTemplateId("LOGIN_AUTH"));

        // 2. 未命中逻辑模板但入参非空，原样返回（向后兼容）
        assertEquals("SMS_UNMAPPED_999", channel.resolveTemplateId("SMS_UNMAPPED_999"));

        // 3. 入参为 null / 空白时，回退到 defaultTemplateId
        assertEquals("SMS_DEFAULT_FALLBACK", channel.resolveTemplateId(null));
        assertEquals("SMS_DEFAULT_FALLBACK", channel.resolveTemplateId("   "));

        // 4. 签名决策：显式入参 > 逻辑模板专属签名 > 渠道默认签名
        assertEquals("显式指定签名", channel.resolveSignName("LOGIN_AUTH", "显式指定签名"));
        assertEquals("阿里验证码专属", channel.resolveSignName("LOGIN_AUTH", null));
        assertEquals("阿里验证码专属", channel.resolveSignName("LOGIN_AUTH", "  "));
        assertEquals("阿里全局默认签名", channel.resolveSignName("UNMAPPED", null));

        // 5. 参数顺序映射：逻辑模板名与物理模板名双向支持
        assertEquals(List.of("code", "expire"), channel.getParamOrder("LOGIN_AUTH"));
        assertEquals(List.of("code", "expire"), channel.getParamOrder("SMS_ALI_LOGIN_001"));
        assertEquals(Collections.emptyList(), channel.getParamOrder("NON_EXIST"));
    }

    @Test
    @DisplayName("测试：SmsFailoverChain 顺延容灾时跨云异构逻辑模板与专属签名自动转译")
    void testCrossCloudFailoverTemplateTranslation() {
        // 主渠道：ali-failing 模拟失败并顺延到备用渠道 tx-backup
        SmsTemplateProperties aliProp = new SmsTemplateProperties();
        aliProp.setTemplateId("ALI_SMS_100");
        aliProp.setSignName("阿里专属签名");

        SmsChannel aliChannel = SmsChannel.builder()
                .name("ali-failing")
                .supplier(SmsSupplier.MOCK)
                .signName("阿里默认签名")
                .failoverChannel("tx-backup")
                .templates(Map.of("ORDER_NOTIFY", aliProp))
                .build();

        // 备用渠道：tx-backup
        SmsTemplateProperties txProp = new SmsTemplateProperties();
        txProp.setTemplateId("TX_SMS_200");
        txProp.setSignName("腾讯专属签名");
        txProp.setParamOrder(List.of("orderNo", "status"));

        SmsChannel txChannel = SmsChannel.builder()
                .name("tx-backup")
                .supplier(SmsSupplier.MOCK)
                .signName("腾讯默认签名")
                .templates(Map.of("ORDER_NOTIFY", txProp))
                .build();

        channelRegistry.register(aliChannel);
        channelRegistry.register(txChannel);

        // 注入可控 SmsProvider 使 ali-failing 失败而 tx-backup 成功
        SmsProvider failingAliProvider = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.MOCK;
            }

            @Override
            public SmsResult send(SmsRequest request, SmsChannel channel) {
                if ("ali-failing".equals(channel.getName())) {
                    // 主渠道校验接收到转译后的 ALI_SMS_100 与专属签名
                    assertEquals("ALI_SMS_100", request.getTemplateId());
                    assertEquals("阿里专属签名", request.getSignName());
                    throw new SmsSendException("ali-failing", "NETWORK_ERROR", "网关连接超时",
                            io.github.atengk.boot.sms.exception.SmsResultCode.SMS_SEND_ERROR, "网关连接超时");
                }
                // 备用渠道由底座透传接收，校验转译为 TX_SMS_200 与专属签名
                assertEquals("TX_SMS_200", request.getTemplateId());
                assertEquals("腾讯专属签名", request.getSignName());
                return SmsResult.mock(channel.getName(), "BIZ_TX_MOCK", "REQ_TX_MOCK");
            }
        };

        // 使用自定义 failoverChain 执行
        SmsFailoverChain failoverChain = new SmsFailoverChain(
                channelRegistry,
                Map.of(SmsSupplier.MOCK, failingAliProvider),
                Collections.emptyList()
        );

        SmsRequest request = SmsRequest.of("13800000002", "ORDER_NOTIFY", SmsParam.of("orderNo", "SO-12345"));
        SmsResult result = failoverChain.execute(request, aliChannel);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("tx-backup", result.getChannel());
    }

    @Test
    @DisplayName("测试：发信请求缺省 templateId 时自动回退至渠道 defaultTemplateId")
    void testFallbackToDefaultTemplateId() {
        SmsChannel defaultChannel = SmsChannel.builder()
                .name("channel-with-default-tpl")
                .supplier(SmsSupplier.MOCK)
                .signName("默认渠道签名")
                .defaultTemplateId("SMS_FALLBACK_DEFAULT")
                .build();
        channelRegistry.register(defaultChannel);

        // 1. 单条短信缺省 templateId
        SmsRequest request = SmsRequest.builder()
                .phone("13800000003")
                .channel("channel-with-default-tpl")
                .params(SmsParam.of("code", "123456"))
                .build();

        SmsResult result = smsTemplate.send(request);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("SMS_FALLBACK_DEFAULT", mockSmsProvider.getLastRequest().getTemplateId());

        // 2. 批量短信缺省 templateId
        BatchSmsRequest batchRequest = BatchSmsRequest.builder()
                .phones(List.of("13800000003", "13800000004"))
                .channel("channel-with-default-tpl")
                .params(SmsParam.of("code", "654321"))
                .build();

        BatchSmsResult batchResult = smsTemplate.sendBatch(batchRequest);
        assertNotNull(batchResult);
        assertEquals(2, batchResult.getTotalCount());
        assertEquals(2, batchResult.getSuccessCount());
        assertEquals("SMS_FALLBACK_DEFAULT", mockSmsProvider.getLastRequest().getTemplateId());

        // 3. 渠道无 defaultTemplateId 且请求无 templateId 时抛出快速失败业务异常
        SmsChannel noDefaultChannel = SmsChannel.builder()
                .name("channel-without-default-tpl")
                .supplier(SmsSupplier.MOCK)
                .signName("无默认模板签名")
                .build();
        channelRegistry.register(noDefaultChannel);

        SmsRequest invalidRequest = SmsRequest.builder()
                .phone("13800000005")
                .channel("channel-without-default-tpl")
                .build();
        assertThrows(BusinessException.class, () -> smsTemplate.send(invalidRequest));

        BatchSmsRequest invalidBatchRequest = BatchSmsRequest.builder()
                .phones(List.of("13800000005"))
                .channel("channel-without-default-tpl")
                .build();
        assertThrows(BusinessException.class, () -> smsTemplate.sendBatch(invalidBatchRequest));
    }

    @Test
    @DisplayName("测试：AbstractHttpSmsProvider RestClient 实例基于超时时间复用缓存")
    void testAbstractHttpSmsProviderRestClientCache() {
        TestHttpSmsProvider provider = new TestHttpSmsProvider();

        // 1. 相同超时时间返回同一复用实例
        RestClient client1 = provider.getRestClient(Duration.ofSeconds(5));
        RestClient client2 = provider.getRestClient(Duration.ofSeconds(5));
        assertSame(client1, client2, "相同超时阈值应复用缓存中的 RestClient 实例");
        assertEquals(1, provider.getCachedRestClientCount());

        // 2. 不同超时阈值创建并缓存独立实例
        RestClient client3 = provider.getRestClient(Duration.ofSeconds(15));
        assertNotSame(client1, client3, "不同超时阈值应生成不同的 RestClient 实例");
        assertEquals(2, provider.getCachedRestClientCount());

        // 3. 再次获取 15 秒实例命中缓存
        RestClient client4 = provider.getRestClient(Duration.ofSeconds(15));
        assertSame(client3, client4);
        assertEquals(2, provider.getCachedRestClientCount());
    }

    @Test
    @DisplayName("测试：AbstractHttpSmsProvider 线程安全无状态单渠道瞬态重试执行器")
    void testAbstractHttpSmsProviderStatelessRetry() throws Exception {
        TestHttpSmsProvider provider = new TestHttpSmsProvider();
        SmsChannel retryChannel = SmsChannel.builder()
                .name("retry-channel")
                .supplier(SmsSupplier.MOCK)
                .maxRetries(2)
                .retryInterval(Duration.ofMillis(20))
                .build();

        // 1. 模拟瞬态 IO 网络异常重试 2 次后在第 3 次成功返回
        AtomicInteger attemptCounter = new AtomicInteger(0);
        String result = provider.executeRetry(retryChannel, "TestSend", () -> {
            int current = attemptCounter.incrementAndGet();
            if (current <= 2) {
                throw new ResourceAccessException("Connection timed out (mock transient)");
            }
            return "SUCCESS_ON_ATTEMPT_" + current;
        });

        assertEquals("SUCCESS_ON_ATTEMPT_3", result);
        assertEquals(3, attemptCounter.get(), "应总共执行 3 次（初始 1 次 + 重试 2 次）");

        // 2. 模拟非瞬态异常（如 IllegalArgumentException）快速失败，不触发重试
        AtomicInteger failFastCounter = new AtomicInteger(0);
        assertThrows(IllegalArgumentException.class, () ->
                provider.executeRetry(retryChannel, "TestFailFast", () -> {
                    failFastCounter.incrementAndGet();
                    throw new IllegalArgumentException("Bad Request (non-retryable)");
                })
        );
        assertEquals(1, failFastCounter.get(), "非瞬态异常应直接快速失败，不重试");

        // 3. 模拟瞬态异常超过重试上限，最终抛出原异常
        AtomicInteger exhaustedCounter = new AtomicInteger(0);
        assertThrows(ResourceAccessException.class, () ->
                provider.executeRetry(retryChannel, "TestExhausted", () -> {
                    exhaustedCounter.incrementAndGet();
                    throw new ResourceAccessException("Persistent network failure");
                })
        );
        assertEquals(3, exhaustedCounter.get(), "重试耗尽应执行 1 + 2 = 3 次");
    }

    @Test
    @DisplayName("测试：sendAfterCommit 与 sendAsyncAfterCommit 在事务提交后精确透传 SmsContextHolder 渠道现场")
    void testTransactionalContextPropagationWithChannelSnapshot() throws Exception {
        SmsChannel vipChannel = SmsChannel.builder()
                .name("tx-vip-channel")
                .supplier(SmsSupplier.MOCK)
                .signName("VIP专属签名")
                .build();
        channelRegistry.register(vipChannel);

        SmsRequest request = SmsRequest.of("13800000008", "SMS_TX_SNAPSHOT", SmsParam.of("code", "9999"));

        // 1. 测试 sendAfterCommit 在事务提交后还原闭包内设置的 SmsContextHolder 渠道现场
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // 在 runWithChannel 闭包内部注册事务提交钩子
            SmsContextHolder.runWithChannel("tx-vip-channel", () -> {
                smsTemplate.sendAfterCommit(request);
            });

            // runWithChannel 退出后，当前线程上下文已被彻底清理
            assertNull(SmsContextHolder.getChannel(), "runWithChannel 退出后线程上下文应被清理为空");
            assertEquals(0, mockSmsProvider.getSentCount(), "事务未提交前不得发送短信");

            // 触发事务提交
            TransactionSynchronizationUtils.triggerAfterCommit();

            // 事务提交后，afterCommit 依赖 AsyncContext 还原了闭包内的 "tx-vip-channel"
            assertEquals(1, mockSmsProvider.getSentCount());
            assertEquals("tx-vip-channel", mockSmsProvider.getLastRequest().getChannel());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            mockSmsProvider.clear();
        }

        // 2. 测试 sendAsyncAfterCommit 在事务提交后通过受管异步线程池发信且透传渠道现场
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            CompletableFuture<SmsResult> future = SmsContextHolder.runWithChannel("tx-vip-channel", () ->
                    smsTemplate.sendAsyncAfterCommit(request)
            );

            assertNull(SmsContextHolder.getChannel());
            assertEquals(0, mockSmsProvider.getSentCount());
            assertFalse(future.isDone());

            // 触发事务提交
            TransactionSynchronizationUtils.triggerAfterCommit();

            // 等待异步完成
            SmsResult asyncResult = future.get();
            assertNotNull(asyncResult);
            assertTrue(asyncResult.isSuccess());
            assertEquals("tx-vip-channel", asyncResult.getChannel());
            assertEquals(1, mockSmsProvider.getSentCount());
            assertEquals("tx-vip-channel", mockSmsProvider.getLastRequest().getChannel());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            mockSmsProvider.clear();
        }

        // 3. 测试 sendAsyncAfterCommit 在事务回滚 (ROLLBACK) 时安全取消，Future 异常结束
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            CompletableFuture<SmsResult> future = smsTemplate.sendAsyncAfterCommit(request);
            assertEquals(0, mockSmsProvider.getSentCount());

            // 触发回滚
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            assertTrue(future.isCompletedExceptionally(), "事务回滚后 Future 应被异常取消");
            assertEquals(0, mockSmsProvider.getSentCount(), "事务回滚后不得发信");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("测试：sendBatchAsyncAfterCommit 批量发信事务感知与上下文透传")
    void testBatchTransactionalContextPropagation() throws Exception {
        SmsChannel batchVipChannel = SmsChannel.builder()
                .name("batch-vip-channel")
                .supplier(SmsSupplier.MOCK)
                .signName("批量VIP签名")
                .build();
        channelRegistry.register(batchVipChannel);

        BatchSmsRequest batchRequest = BatchSmsRequest.create(
                List.of("13800000010", "13800000011"),
                "SMS_BATCH_TX",
                SmsParam.of("msg", "Batch Notice")
        );

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            CompletableFuture<BatchSmsResult> future = SmsContextHolder.runWithChannel("batch-vip-channel", () ->
                    smsTemplate.sendBatchAsyncAfterCommit(batchRequest)
            );

            assertNull(SmsContextHolder.getChannel());
            assertEquals(0, mockSmsProvider.getSentCount());

            TransactionSynchronizationUtils.triggerAfterCommit();

            BatchSmsResult batchResult = future.get();
            assertNotNull(batchResult);
            assertEquals(2, batchResult.getTotalCount());
            assertEquals(2, batchResult.getSuccessCount());
            assertEquals("batch-vip-channel", mockSmsProvider.getLastRequest().getChannel());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("测试：个性化批量发信在非原生厂商并发降级时发生顺延容灾能正确转译备用渠道异构模板")
    void testPersonalizedBatchFailoverCrossCloudTemplateTranslation() {
        SmsTemplateProperties primaryProp = new SmsTemplateProperties();
        primaryProp.setTemplateId("PRIMARY_TPL_001");
        primaryProp.setSignName("主渠道专属签名");

        SmsChannel primaryChannel = SmsChannel.builder()
                .name("primary-failing")
                .supplier(SmsSupplier.MOCK)
                .signName("主默认签名")
                .failoverChannel("secondary-backup")
                .templates(Map.of("BATCH_NOTIFY", primaryProp))
                .build();

        SmsTemplateProperties secondaryProp = new SmsTemplateProperties();
        secondaryProp.setTemplateId("SECONDARY_TPL_002");
        secondaryProp.setSignName("备渠道专属签名");

        SmsChannel secondaryChannel = SmsChannel.builder()
                .name("secondary-backup")
                .supplier(SmsSupplier.MOCK)
                .signName("备默认签名")
                .templates(Map.of("BATCH_NOTIFY", secondaryProp))
                .build();

        channelRegistry.register(primaryChannel);
        channelRegistry.register(secondaryChannel);

        SmsProvider failoverMockProvider = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.MOCK;
            }

            @Override
            public SmsResult send(SmsRequest request, SmsChannel channel) {
                if ("primary-failing".equals(channel.getName())) {
                    assertEquals("PRIMARY_TPL_001", request.getTemplateId());
                    throw new SmsSendException("primary-failing", "NETWORK_ERROR", "主渠道故障",
                            io.github.atengk.boot.sms.exception.SmsResultCode.SMS_SEND_ERROR, "主渠道故障");
                }
                // 备用渠道必须成功基于原始逻辑模板 BATCH_NOTIFY 转译为备用渠道专属模板 SECONDARY_TPL_002
                assertEquals("SECONDARY_TPL_002", request.getTemplateId(),
                        "个性化批量顺延容灾时，备用渠道必须基于逻辑模板 BATCH_NOTIFY 转译为 SECONDARY_TPL_002，而非继承主渠道物理模板");
                assertEquals("备渠道专属签名", request.getSignName());
                return SmsResult.mock(channel.getName(), "BIZ_SEC_" + request.getPhone(), "REQ_SEC");
            }
        };

        SmsTemplate customTemplate = new SmsTemplate(
                new io.github.atengk.boot.sms.properties.SmsProperties(),
                List.of(failoverMockProvider),
                channelRegistry,
                null,
                null,
                null,
                new org.springframework.core.task.SyncTaskExecutor()
        );

        Map<String, SmsParam> phoneParams = Map.of(
                "13800000011", SmsParam.of("msg", "hello"),
                "13800000012", SmsParam.of("msg", "world")
        );
        BatchSmsRequest batchRequest = BatchSmsRequest.builder()
                .phoneParams(phoneParams)
                .templateId("BATCH_NOTIFY")
                .channel("primary-failing")
                .build();

        BatchSmsResult batchResult = customTemplate.sendBatch(batchRequest);
        assertNotNull(batchResult);
        assertEquals(2, batchResult.getTotalCount());
        assertEquals(2, batchResult.getSuccessCount());
        assertEquals(0, batchResult.getFailedCount());
        assertEquals(2, batchResult.getBatchResults().size());
        assertEquals("secondary-backup", batchResult.getBatchResults().get(0).getChannel());
    }

    /**
     * 测试用 HTTP 短信驱动基类暴露器
     */
    private static class TestHttpSmsProvider extends AbstractHttpSmsProvider {

        public TestHttpSmsProvider() {
            super(RestClient.builder());
        }

        public RestClient getRestClient(Duration timeout) {
            return buildRestClient(timeout);
        }

        public <T> T executeRetry(SmsChannel channel, String actionName, SmsExecutionCallback<T> callback) throws Exception {
            return executeWithRetry(channel, actionName, callback);
        }

        @Override
        public SmsSupplier getSupplier() {
            return SmsSupplier.MOCK;
        }

        @Override
        public SmsResult send(SmsRequest request, SmsChannel channel) {
            return SmsResult.mock(channel.getName(), "MOCK_BIZ", "MOCK_REQ");
        }
    }
}
