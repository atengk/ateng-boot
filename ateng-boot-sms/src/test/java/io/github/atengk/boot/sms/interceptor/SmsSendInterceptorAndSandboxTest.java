package io.github.atengk.boot.sms.interceptor;

import io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.properties.SmsSandboxProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单 #1148 拦截器链与安全沙箱对抗测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1148 拦截器链与安全沙箱测试")
class SmsSendInterceptorAndSandboxTest {

    @Test
    @DisplayName("自定义 SmsSendInterceptor 按 Ordered 顺序执行 preSend/postSend/onError 且支持上下文改写")
    void shouldExecuteInterceptorsInOrderedSequenceAndSupportRewriteAndOnError() {
        SmsProperties props = new SmsProperties();
        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder().name("mock").supplier(SmsSupplier.MOCK).defaultTemplateId("TPL_1").build());
        MockSmsProvider mockProvider = new MockSmsProvider();

        List<String> trace = new ArrayList<>();

        SmsSendInterceptor second = new SmsSendInterceptor() {
            @Override
            public int getOrder() {
                return 20;
            }

            @Override
            public boolean preSend(SmsSendContext context) {
                trace.add("pre:20");
                SmsRequest rewritten = context.getRequest().withContent("rewritten-by-20");
                context.setRequest(rewritten);
                return true;
            }

            @Override
            public void postSend(SmsSendContext context, SmsResult result) {
                trace.add("post:20");
            }

            @Override
            public void onError(SmsSendContext context, Throwable error) {
                trace.add("error:20");
            }
        };

        SmsSendInterceptor first = new SmsSendInterceptor() {
            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public boolean preSend(SmsSendContext context) {
                trace.add("pre:10");
                return true;
            }

            @Override
            public void postSend(SmsSendContext context, SmsResult result) {
                trace.add("post:10");
            }

            @Override
            public void onError(SmsSendContext context, Throwable error) {
                trace.add("error:10");
            }
        };

        // 故意乱序传入，验证 Ordered 排序生效
        SmsTemplate template = new SmsTemplate(
                props,
                List.of(mockProvider),
                registry,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(second, first),
                null
        );

        SmsResult result = template.send(SmsRequest.ofContent("13800138000", "original"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(mockProvider.getLastRequest().getContent()).isEqualTo("rewritten-by-20");
        assertThat(trace).containsExactly("pre:10", "pre:20", "post:20", "post:10");

        // 验证异常触发 onError（按逆序回收）
        trace.clear();
        SmsProvider failingProvider = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.MOCK;
            }

            @Override
            public boolean supportsPlainText() {
                return true;
            }

            @Override
            public SmsResult send(SmsRequest request, SmsChannel channel) {
                throw new SmsSendException(channel.getName(), "ERR", "boom", null, "boom");
            }
        };

        SmsTemplate failingTemplate = new SmsTemplate(
                props,
                List.of(failingProvider),
                registry,
                null, null, null, null, null, null, null, null, null, null,
                List.of(second, first),
                null
        );

        assertThatThrownBy(() -> failingTemplate.send(SmsRequest.ofContent("13800138000", "hi")))
                .isInstanceOf(SmsSendException.class);
        assertThat(trace).containsExactly("pre:10", "pre:20", "error:20", "error:10");
    }

    @Test
    @DisplayName("SmsSandboxInterceptor: log-only 零外部发送并返回模拟成功")
    void shouldSimulateSuccessWithoutExternalDispatchInLogOnlyMode() {
        SmsProperties props = new SmsProperties();
        props.getSandbox().setEnabled(true);
        props.getSandbox().setLogOnly(true);
        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        MockSmsProvider mockProvider = new MockSmsProvider();

        SmsSandboxInterceptor sandbox = new SmsSandboxInterceptor(props.getSandbox(), props.getGuard());
        SmsTemplate template = new SmsTemplate(
                props,
                List.of(mockProvider),
                registry,
                null, null, null, null, null, null, null, null, null, null,
                List.of(sandbox),
                null
        );

        SmsResult singleResult = template.send(SmsRequest.of("13911112222", "TPL_1", SmsParam.of("code", "123456")));
        assertThat(singleResult.isSuccess()).isTrue();
        assertThat(mockProvider.getSentCount()).isZero();

        BatchSmsResult batchResult = template.sendBatch(BatchSmsRequest.create(
                List.of("13911112222", "13911113333"), "TPL_1", SmsParam.of("code", "123456")));
        assertThat(batchResult.isSuccess()).isTrue();
        assertThat(batchResult.getSuccessCount()).isEqualTo(2);
        assertThat(mockProvider.getSentCount()).isZero();
    }

    @Test
    @DisplayName("SmsSandboxInterceptor: 通配符白名单正常穿透、非白名单批量合并去重重定向、无 redirectTo 立即 Fail-Fast")
    void shouldEnforceWhitelistRedirectDeduplicationAndFailFast() {
        SmsProperties props = new SmsProperties();
        SmsSandboxProperties sandboxProps = props.getSandbox();
        sandboxProps.setEnabled(true);
        sandboxProps.setAllowedPhones(List.of("13800138*"));
        sandboxProps.setRedirectTo("13999990000");

        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        MockSmsProvider mockProvider = new MockSmsProvider();
        SmsSandboxInterceptor sandbox = new SmsSandboxInterceptor(sandboxProps, props.getGuard());
        SmsTemplate template = new SmsTemplate(
                props,
                List.of(mockProvider),
                registry,
                null, null, null, null, null, null, null, null, null, null,
                List.of(sandbox),
                null
        );

        // 1. 通配符白名单正常穿透
        SmsResult allowedRes = template.send(SmsRequest.of("13800138001", "TPL_1"));
        assertThat(allowedRes.isSuccess()).isTrue();
        assertThat(mockProvider.getLastRequest().getPhone()).isEqualTo("13800138001");

        // 1.5 多号码 SmsRequest 混含白名单与非白名单：保留白名单并合并去重 redirectTo
        mockProvider.clear();
        SmsRequest mixedSingleReq = SmsRequest.builder()
                .phones(List.of("13800138001", "13700000001", "13700000002"))
                .templateId("TPL_1")
                .build();
        SmsResult mixedSingleRes = template.send(mixedSingleReq);
        assertThat(mixedSingleRes.isSuccess()).isTrue();
        assertThat(mockProvider.getSentCount()).isEqualTo(1);
        assertThat(mockProvider.getLastRequest().getPhones()).containsExactly("13800138001", "13999990000");

        // 2. 批量全部为非白名单号码 -> 合并去重仅向 redirectTo 发出 1 条测试短信
        mockProvider.clear();
        BatchSmsResult batchRes = template.sendBatch(BatchSmsRequest.create(
                List.of("13700000001", "13700000002", "13700000003"), "TPL_BATCH", SmsParam.create()));
        assertThat(batchRes.isSuccess()).isTrue();
        assertThat(mockProvider.getSentCount()).isEqualTo(1);
        assertThat(mockProvider.getLastRequest().getPhones()).containsExactly("13999990000");

        // 3. 未配置 redirect-to 时，非白名单号码立即 Fail-Fast 阻断抛错
        sandboxProps.setRedirectTo(null);
        assertThatThrownBy(() -> template.send(SmsRequest.of("13700000001", "TPL_1")))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("redirect-to");
        assertThatThrownBy(() -> template.sendBatch(BatchSmsRequest.create(
                List.of("13800138001", "13700000002"), "TPL_BATCH", SmsParam.create())))
                .isInstanceOf(SmsSendException.class);
    }

    @Test
    @DisplayName("自动装配: 默认不装配 SmsSandboxInterceptor，开启 sandbox.enabled=true 后装配")
    void shouldConditionallyAutoConfigureSandboxInterceptor() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengSmsAutoConfiguration.class));

        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SmsSandboxInterceptor.class));

        runner.withPropertyValues("ateng.boot.sms.sandbox.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SmsSandboxInterceptor.class);
                    assertThat(ctx.getBean(SmsTemplate.class).getSendInterceptors())
                            .anySatisfy(i -> assertThat(i).isInstanceOf(SmsSandboxInterceptor.class));
                });
    }
}
