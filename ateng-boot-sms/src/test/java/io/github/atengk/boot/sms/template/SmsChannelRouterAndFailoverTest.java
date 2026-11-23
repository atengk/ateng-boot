package io.github.atengk.boot.sms.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration;
import io.github.atengk.boot.sms.context.SmsContextHolder;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.provider.AliyunSmsProvider;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多命名渠道注册中心、动态切源与高可用顺延容灾全维自洽单测
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest(classes = AtengSmsAutoConfiguration.class)
@DisplayName("多命名渠道注册中心、动态切源与高可用顺延容灾测试")
class SmsChannelRouterAndFailoverTest {

    @Autowired
    private SmsTemplate smsTemplate;

    @Autowired
    private MockSmsProvider mockSmsProvider;

    @Autowired
    private SmsChannelRegistry channelRegistry;

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() {
        mockSmsProvider.clear();
        SmsContextHolder.clear();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() throws IOException {
        SmsContextHolder.clear();
        TenantContextHolder.clear();
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("测试：SmsChannelRegistry 动态注册与注销渠道生命周期")
    void testChannelRegistry_RegisterAndUnregister() {
        SmsChannel dynamicChannel = SmsChannel.builder()
                .name("dynamic-mock-channel")
                .supplier(SmsSupplier.MOCK)
                .signName("动态测试签名")
                .build();

        // 1. 动态注册
        smsTemplate.registerChannel(dynamicChannel);
        assertTrue(channelRegistry.contains("dynamic-mock-channel"));
        assertEquals(dynamicChannel, channelRegistry.get("dynamic-mock-channel"));

        // 2. 使用新注册的渠道发信
        SmsRequest request = SmsRequest.builder()
                .phone("13800001234")
                .templateId("SMS_DYNAMIC_01")
                .channel("dynamic-mock-channel")
                .build();
        SmsResult result = smsTemplate.send(request);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("dynamic-mock-channel", result.getChannel());

        // 3. 动态注销
        SmsChannel unregistered = smsTemplate.unregisterChannel("dynamic-mock-channel");
        assertNotNull(unregistered);
        assertFalse(channelRegistry.contains("dynamic-mock-channel"));

        // 4. 注销后再发信抛出 SmsSendException (CHANNEL_NOT_FOUND)
        assertThrows(SmsSendException.class, () -> smsTemplate.send(request));
    }

    @Test
    @DisplayName("测试：SmsContextHolder 线程级动态切源与现场受控自动恢复")
    void testSmsContextHolder_ThreadLocalRouting() {
        SmsChannel channelA = SmsChannel.builder()
                .name("channel-alpha")
                .supplier(SmsSupplier.MOCK)
                .signName("ALPHA")
                .build();
        channelRegistry.register(channelA);

        SmsRequest request = SmsRequest.of("13800002222", "SMS_TPL_01", SmsParam.create());

        // 1. 默认上下文发信 -> 路由至默认 mock 渠道
        SmsResult resDefault = smsTemplate.send(request);
        assertEquals("mock", resDefault.getChannel());

        // 2. 编程式闭包 runWithChannel 切源 -> 路由至 channel-alpha
        SmsResult resScoped = SmsContextHolder.runWithChannel("channel-alpha", () -> smsTemplate.send(request));
        assertEquals("channel-alpha", resScoped.getChannel());

        // 3. 闭包退出后自动恢复为 null，再次发信回退默认 mock 渠道
        assertNull(SmsContextHolder.getChannel());
        SmsResult resRestored = smsTemplate.send(request);
        assertEquals("mock", resRestored.getChannel());
    }

    @Test
    @DisplayName("测试：TenantSmsChannelResolver 多租户自动动态寻址")
    void testTenantSmsChannelResolver_AutoRouting() {
        // 注册租户专属渠道：tenant-tenant_vip_001
        SmsChannel tenantChannel = SmsChannel.builder()
                .name("tenant-tenant_vip_001")
                .supplier(SmsSupplier.MOCK)
                .signName("VIP租户专属")
                .build();
        channelRegistry.register(tenantChannel);

        SmsRequest request = SmsRequest.of("13800003333", "SMS_TENANT_TPL", SmsParam.create());

        // 1. 在租户上下文中执行，未显式指定 channel，自动提取并匹配到 tenant-tenant_vip_001
        SmsResult tenantResult = TenantContextHolder.runWithTenant("tenant_vip_001", () -> smsTemplate.send(request));

        assertNotNull(tenantResult);
        assertTrue(tenantResult.isSuccess());
        assertEquals("tenant-tenant_vip_001", tenantResult.getChannel());

        // 2. 无租户上下文时，自动回退默认渠道
        SmsResult noTenantResult = smsTemplate.send(request);
        assertEquals("mock", noTenantResult.getChannel());
    }

    @Test
    @DisplayName("测试：三级级联寻址引擎优先级 (Explicit > ThreadLocal > TenantContext > DefaultChannel)")
    void testThreeTierCascadeAddressResolution() {
        // 准备各层级渠道
        channelRegistry.register(SmsChannel.builder().name("explicit-ch").supplier(SmsSupplier.MOCK).build());
        channelRegistry.register(SmsChannel.builder().name("thread-ch").supplier(SmsSupplier.MOCK).build());
        channelRegistry.register(SmsChannel.builder().name("tenant-cascade_tenant").supplier(SmsSupplier.MOCK).build());

        // Case 1: 均未设置 -> 第 3 级全局默认渠道
        assertEquals("mock", smsTemplate.resolveChannelName(null));

        // Case 2: 仅设置租户 -> 第 2 级多租户 SPI 解析
        TenantContextHolder.runWithTenant("cascade_tenant", () -> {
            assertEquals("tenant-cascade_tenant", smsTemplate.resolveChannelName(null));
            return null;
        });

        // Case 3: 同时存在租户与线程上下文 -> 第 1 级线程上下文优先于第 2 级租户
        TenantContextHolder.runWithTenant("cascade_tenant", () -> {
            SmsContextHolder.runWithChannel("thread-ch", () -> {
                assertEquals("thread-ch", smsTemplate.resolveChannelName(null));
                return null;
            });
            return null;
        });

        // Case 4: 存在显式入参 -> 第 0 级显式入参覆盖所有上下文
        TenantContextHolder.runWithTenant("cascade_tenant", () -> {
            SmsContextHolder.runWithChannel("thread-ch", () -> {
                assertEquals("explicit-ch", smsTemplate.resolveChannelName("explicit-ch"));
                return null;
            });
            return null;
        });
    }

    @Test
    @DisplayName("测试：MockWebServer 模拟主渠道 HTTP 500 时顺延容灾平滑切换至备用渠道")
    void testSmsFailoverChain_Primary500FallbackToBackup() throws Exception {
        // 1. 启动本地 MockWebServer
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String primaryEndpoint = mockWebServer.url("/").toString();

        // 2. 模拟阿里云主渠道返回 HTTP 500 内部服务错误
        String errorJson = """
                {
                  "Code": "isv.BUSINESS_LIMIT_CONTROL",
                  "Message": "业务限流，服务不可用",
                  "RequestId": "FAIL_REQ_500"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(errorJson));

        // 3. 注册主渠道 (指向 MockWebServer)，并配置容灾备用渠道为 "mock-backup"
        SmsChannel backupChannel = SmsChannel.builder()
                .name("mock-backup")
                .supplier(SmsSupplier.MOCK)
                .signName("备用沙箱")
                .build();
        channelRegistry.register(backupChannel);

        SmsChannel primaryChannel = SmsChannel.builder()
                .name("aliyun-primary")
                .supplier(SmsSupplier.ALIYUN)
                .accessKeyId("AK_PRIMARY")
                .accessKeySecret("SK_PRIMARY")
                .signName("主渠道签名")
                .endpoint(primaryEndpoint)
                .failoverChannel("mock-backup") // 绑定容灾渠道
                .build();
        channelRegistry.register(primaryChannel);

        // 4. 向主渠道发起发信请求
        SmsRequest request = SmsRequest.builder()
                .phone("13811118888")
                .templateId("SMS_FAILOVER_01")
                .channel("aliyun-primary")
                .build();

        // 5. 执行发信：主渠道遭遇 500 故障，顺延降级链自动重试并由 mock-backup 成功承接
        SmsResult result = smsTemplate.send(request);

        assertNotNull(result);
        assertTrue(result.isSuccess(), "降级至备用渠道后应最终成功投递");
        assertEquals("mock-backup", result.getChannel(), "发信结果中的投递渠道应为备用渠道");
        assertEquals(SmsSupplier.MOCK, result.getSupplier());

        // 验证备用沙箱接收到了真实发信
        assertEquals(1, mockSmsProvider.getSentCount());
        assertEquals("13811118888", mockSmsProvider.getLastRequest().getPhone());
    }

    @Test
    @DisplayName("测试：容灾链环路依赖防卫 (A -> B -> A 循环引用防御)")
    void testSmsFailoverChain_CircularReferenceDefense() {
        // 配置渠道 A 备用指向 B，B 备用指向 A
        SmsChannel channelA = SmsChannel.builder()
                .name("channel-loop-a")
                .supplier(SmsSupplier.MOCK)
                .failoverChannel("channel-loop-b")
                .build();
        SmsChannel channelB = SmsChannel.builder()
                .name("channel-loop-b")
                .supplier(SmsSupplier.MOCK)
                .failoverChannel("channel-loop-a")
                .build();

        // 自定义 Provider 模拟两渠道均抛出异常
        AtomicInteger callCount = new AtomicInteger(0);
        SmsProvider failProvider = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.MOCK;
            }

            @Override
            public SmsResult send(SmsRequest req, SmsChannel ch) {
                callCount.incrementAndGet();
                throw new SmsSendException(ch.getName(), "SIMULATED_ERR", "渠道故障", null, null);
            }
        };

        SmsChannelRegistry testRegistry = new SmsChannelRegistry();
        testRegistry.register(channelA);
        testRegistry.register(channelB);

        SmsTemplate customTemplate = new SmsTemplate(
                null,
                List.of(failProvider),
                testRegistry,
                null,
                null,
                null,
                null
        );

        SmsRequest request = SmsRequest.builder()
                .phone("13800004444")
                .templateId("TPL")
                .channel("channel-loop-a")
                .build();

        // 验证：环路被安全拦截熔断，未发生死循环或栈溢出，抛出 SmsSendException
        assertThrows(SmsSendException.class, () -> customTemplate.send(request));
        // A 执行失败后降级到 B，B 准备切回 A 时检测到环路终止，总调用次数恰好为 2 次
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("测试：渠道处于短时熔断开路 (OPEN) 态且配置了备用渠道时，毫秒级短路切换至备用渠道且不发起主渠道网络调用")
    void testCircuitBreaker_ShortCircuitToFailoverChannel() {
        AtomicInteger primaryCalls = new AtomicInteger(0);
        AtomicInteger backupCalls = new AtomicInteger(0);

        SmsProvider provider = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.MOCK;
            }

            @Override
            public SmsResult send(SmsRequest req, SmsChannel ch) {
                if ("cb-primary".equals(ch.getName())) {
                    primaryCalls.incrementAndGet();
                    return SmsResult.mock(ch.getName(), "BIZ_PRI", "REQ_PRI");
                }
                if ("cb-backup".equals(ch.getName())) {
                    backupCalls.incrementAndGet();
                    return SmsResult.mock(ch.getName(), "BIZ_BAK", "REQ_BAK");
                }
                return SmsResult.mock(ch.getName(), "BIZ_MOCK", "REQ_MOCK");
            }
        };

        SmsChannelRegistry testRegistry = new SmsChannelRegistry();
        testRegistry.register(SmsChannel.builder()
                .name("cb-backup")
                .supplier(SmsSupplier.MOCK)
                .signName("备用")
                .build());
        testRegistry.register(SmsChannel.builder()
                .name("cb-primary")
                .supplier(SmsSupplier.MOCK)
                .signName("主渠道")
                .failoverChannel("cb-backup")
                .build());

        SmsTemplate template = new SmsTemplate(null, List.of(provider), testRegistry, null, null, null, null, null);

        // 模拟 cb-primary 连续失败 5 次触发 OPEN
        for (int i = 0; i < 5; i++) {
            template.getCircuitBreaker().recordFailure("cb-primary");
        }
        assertTrue(template.isChannelOpen("cb-primary"), "cb-primary 应当已触发短时熔断 (OPEN)");

        // 向 cb-primary 发信
        SmsRequest request = SmsRequest.builder()
                .phone("13800005555")
                .templateId("TPL_CB")
                .channel("cb-primary")
                .build();

        SmsResult result = template.send(request);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("cb-backup", result.getChannel(), "应当毫秒级短路切换并由备用渠道 cb-backup 承接发信");
        assertEquals(0, primaryCalls.get(), "主渠道处于 OPEN 态，绝对严禁发起物理网络调用");
        assertEquals(1, backupCalls.get(), "备用渠道应当被成功调用 1 次");

        // 手动重置后，主渠道恢复
        template.resetChannelCircuitBreaker("cb-primary");
        assertFalse(template.isChannelOpen("cb-primary"));

        SmsResult resPrimary = template.send(request);
        assertEquals("cb-primary", resPrimary.getChannel());
        assertEquals(1, primaryCalls.get());
    }

    @Test
    @DisplayName("测试：无备用渠道时处于短时熔断 (OPEN) 态直接抛出 CIRCUIT_BREAKER_OPEN 异常")
    void testCircuitBreaker_NoFailoverThrowsException() {
        SmsChannelRegistry testRegistry = new SmsChannelRegistry();
        testRegistry.register(SmsChannel.builder()
                .name("cb-solo")
                .supplier(SmsSupplier.MOCK)
                .signName("独立渠道")
                .build());

        SmsTemplate template = new SmsTemplate(null, List.of(new MockSmsProvider()), testRegistry, null, null, null, null, null);

        for (int i = 0; i < 5; i++) {
            template.getCircuitBreaker().recordFailure("cb-solo");
        }
        assertTrue(template.isChannelOpen("cb-solo"));

        SmsRequest request = SmsRequest.builder()
                .phone("13800006666")
                .templateId("TPL_SOLO")
                .channel("cb-solo")
                .build();

        SmsSendException ex = assertThrows(SmsSendException.class, () -> template.send(request));
        assertEquals("CIRCUIT_BREAKER_OPEN", ex.getRawCode());
    }

    @Test
    @DisplayName("测试：平滑加权轮询 (SWRR) sendByLoadBalance 与 channel='load-balance' 自动选路")
    void testSendByLoadBalance_SmoothDistribution() {
        SmsChannelRegistry testRegistry = new SmsChannelRegistry();
        testRegistry.register(SmsChannel.builder()
                .name("lb-alpha")
                .supplier(SmsSupplier.MOCK)
                .signName("ALPHA")
                .weight(3)
                .build());
        testRegistry.register(SmsChannel.builder()
                .name("lb-beta")
                .supplier(SmsSupplier.MOCK)
                .signName("BETA")
                .weight(1)
                .build());

        SmsTemplate template = new SmsTemplate(null, List.of(mockSmsProvider), testRegistry, null, null, null, null, null);

        SmsRequest req = SmsRequest.of("13800007777", "SMS_LB", SmsParam.create());

        // 1. 通过 sendByLoadBalance 发送 4 次，验证 A, A, B, A 平滑选路
        List<String> sequence = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SmsResult res = template.sendByLoadBalance(req);
            sequence.add(res.getChannel());
        }
        assertEquals(List.of("lb-alpha", "lb-alpha", "lb-beta", "lb-alpha"), sequence);

        // 2. 通过指定 channel="load-balance" 触发通用 send 自动选路
        template.resetLoadBalancer();
        SmsRequest reqVirtual = req.withChannel("load-balance");
        SmsResult resVirtual = template.send(reqVirtual);
        assertEquals("lb-alpha", resVirtual.getChannel());
    }

    @Test
    @DisplayName("测试：加权负载均衡在主渠道熔断时自动剔除故障渠道并在自愈后恢复")
    void testSendByLoadBalance_SkipOpenChannelAndRecover() {
        SmsChannelRegistry testRegistry = new SmsChannelRegistry();
        testRegistry.register(SmsChannel.builder()
                .name("lb-healthy")
                .supplier(SmsSupplier.MOCK)
                .weight(1)
                .build());
        testRegistry.register(SmsChannel.builder()
                .name("lb-failing")
                .supplier(SmsSupplier.MOCK)
                .weight(5)
                .build());

        SmsTemplate template = new SmsTemplate(null, List.of(mockSmsProvider), testRegistry, null, null, null, null, null);

        // 将 lb-failing 触发熔断
        for (int i = 0; i < 5; i++) {
            template.getCircuitBreaker().recordFailure("lb-failing");
        }
        assertTrue(template.isChannelOpen("lb-failing"));

        SmsRequest req = SmsRequest.of("13800008888", "SMS_LB_GUARD", SmsParam.create());

        // 连续发送 3 次，因 lb-failing 处于 OPEN 态被自动过滤，全部路由至 lb-healthy
        for (int i = 0; i < 3; i++) {
            SmsResult res = template.sendByLoadBalance(req);
            assertEquals("lb-healthy", res.getChannel());
        }

        // 手动恢复 lb-failing 后，lb-failing (weight 5) 重新参与选路并立即被高权重选中
        template.resetChannelCircuitBreaker("lb-failing");
        assertFalse(template.isChannelOpen("lb-failing"));
        template.resetLoadBalancer();

        SmsResult resAfterRecover = template.sendByLoadBalance(req);
        assertEquals("lb-failing", resAfterRecover.getChannel());
    }
}
