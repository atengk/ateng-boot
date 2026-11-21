package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import io.github.atengk.boot.sms.support.SmsDesensitizeSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 本地开发与离线测试 Mock 沙箱短信驱动
 * 纯内存记录发信请求并打印结构化脱敏审计日志，支持模板短信与无模板纯文本直发，保障脱网环境下 100% 离线自洽
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class MockSmsProvider implements SmsProvider {

    private final ConcurrentLinkedQueue<SmsRequest> sentRequests = new ConcurrentLinkedQueue<>();
    private final SmsGuardProperties guardProperties;

    public MockSmsProvider() {
        this(new SmsGuardProperties());
    }

    public MockSmsProvider(SmsGuardProperties guardProperties) {
        this.guardProperties = guardProperties != null ? guardProperties : new SmsGuardProperties();
    }

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
        String channelName = channel != null ? channel.getName() : "mock";
        String maskedPhone = DesensitizeUtil.mobilePhone(request.getPhone());
        String bizId = "MOCK_BIZ_" + IdUtil.fastSimpleUUID();
        String requestId = "MOCK_REQ_" + IdUtil.fastSimpleUUID();
        String templateLabel = StringUtils.hasText(request.getTemplateId())
                ? request.getTemplateId()
                : "[PLAIN_TEXT len=" + (request.getContent() != null ? request.getContent().length() : 0) + "]";
        String sign = request.getSignName() != null
                ? request.getSignName()
                : (channel != null ? channel.getSignName() : "MOCK");

        // 纯内存记录真明文请求
        sentRequests.offer(request);

        SmsParam loggedParams = guardProperties.isLogDesensitizeContent()
                ? SmsDesensitizeSupport.maskSensitiveParams(
                request.getParams(),
                channel != null ? channel.getParamOrder(request.getTemplateId()) : null,
                guardProperties.getSensitiveParamKeys())
                : request.getParams();

        if (request.getPhones() != null && request.getPhones().size() > 1) {
            log.info("[MockSmsProvider] 模拟批量发信成功 -> 渠道: {}, 目标号码数: {}, 模板ID: {}, 签名: {}, 动态参数: {}, 模拟流水号: {}",
                    channelName,
                    request.getPhones().size(),
                    templateLabel,
                    sign,
                    loggedParams,
                    bizId);
        } else {
            log.info("[MockSmsProvider] 模拟发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, 签名: {}, 动态参数: {}, 模拟流水号: {}",
                    channelName,
                    maskedPhone,
                    templateLabel,
                    sign,
                    loggedParams,
                    bizId);
        }

        return SmsResult.mock(channelName, bizId, requestId);
    }

    /**
     * 获取所有在内存中记录的发信请求快照
     *
     * @return 请求列表拷贝
     */
    public List<SmsRequest> getSentRequests() {
        return Collections.unmodifiableList(new ArrayList<>(sentRequests));
    }

    /**
     * 获取最近一条发信请求
     *
     * @return 最近发送的请求，若队列为空则返回 null
     */
    public SmsRequest getLastRequest() {
        List<SmsRequest> list = new ArrayList<>(sentRequests);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /**
     * 获取已模拟发送的总条数
     *
     * @return 发送总数
     */
    public int getSentCount() {
        return sentRequests.size();
    }

    /**
     * 清空内存发信记录队列（通常用于单元测试 @BeforeEach / @AfterEach 清理）
     */
    public void clear() {
        sentRequests.clear();
    }
}
