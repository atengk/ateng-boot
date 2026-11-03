package io.github.atengk.boot.sample.sms.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.model.*;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import io.github.atengk.boot.sms.template.SmsVerifyCodeTemplate;
import io.github.atengk.boot.sample.sms.dto.BatchSmsSendRequest;
import io.github.atengk.boot.sample.sms.dto.DynamicChannelRegisterRequest;
import io.github.atengk.boot.sample.sms.dto.SendSmsRequest;
import io.github.atengk.boot.sample.sms.dto.SendVerifyCodeRequest;
import io.github.atengk.boot.sample.sms.dto.VerifyCodeCheckRequest;
import io.github.atengk.boot.sample.sms.vo.SmsChannelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 短信多场景业务编排服务
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsScenarioService {

    private final SmsTemplate smsTemplate;
    private final SmsVerifyCodeTemplate verifyCodeTemplate;
    private final SmsChannelRegistry channelRegistry;

    /**
     * 单条短信同步发送
     *
     * @param req 发信请求传输对象
     * @return SmsResult
     */
    public SmsResult sendSingleSms(SendSmsRequest req) {
        SmsRequest request = buildRequest(req);
        log.info("[SmsScenarioService] 发起单条短信同步投递 -> 目标号码: {}, 模板: {}",
                DesensitizeUtil.mobilePhone(req.getPhone()), req.getTemplateId());
        return smsTemplate.send(request);
    }

    /**
     * 单条短信受管异步发送
     *
     * @param req 发信请求传输对象
     * @return CompletableFuture<SmsResult>
     */
    public CompletableFuture<SmsResult> sendSingleSmsAsync(SendSmsRequest req) {
        SmsRequest request = buildRequest(req);
        log.info("[SmsScenarioService] 发起单条短信受管异步投递 -> 目标号码: {}, 模板: {}",
                DesensitizeUtil.mobilePhone(req.getPhone()), req.getTemplateId());
        return smsTemplate.sendAsync(request);
    }

    /**
     * 本地事务感知发信 (演示事务提交与回滚防幽灵短信)
     *
     * @param req            发信请求
     * @param shouldRollback 是否模拟业务异常触发事务回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendTransactional(SendSmsRequest req, boolean shouldRollback) {
        SmsRequest request = buildRequest(req);
        log.info("[SmsScenarioService] 开启本地物理事务，注册发信提交钩子 (目标号码: {})",
                DesensitizeUtil.mobilePhone(req.getPhone()));

        // 1. 注册事务提交后投递钩子
        smsTemplate.sendAfterCommit(request);

        // 2. 模拟业务逻辑执行（如抛出异常回滚）
        if (shouldRollback) {
            log.warn("[SmsScenarioService] 触发业务异常，预期事务回滚并自动取消短信发信！");
            throw new BusinessException("模拟业务异常触发事务回滚，防幽灵短信生效");
        }

        log.info("[SmsScenarioService] 业务逻辑正常结束，事务即将物理 COMMIT！");
    }

    /**
     * 批量营销短信群发切片
     *
     * @param req 批量发信请求传输对象
     * @return BatchSmsResult
     */
    public BatchSmsResult sendBatchSms(BatchSmsSendRequest req) {
        BatchSmsRequest request = BatchSmsRequest.builder()
                .phones(req.getPhones())
                .templateId(req.getTemplateId())
                .signName(req.getSignName())
                .channel(req.getChannel())
                .params(toSmsParam(req.getParams()))
                .build();

        log.info("[SmsScenarioService] 发起海量营销短信批量切片发送 -> 总手机号数: {}, 模板: {}",
                req.getPhones().size(), req.getTemplateId());
        return smsTemplate.sendBatch(request);
    }

    /**
     * 发送业务验证码 (全生命周期治理 + 滑动窗口防刷限流)
     *
     * @param req 验证码请求
     * @return 生成的验证码字符串
     */
    public String sendVerifyCode(SendVerifyCodeRequest req) {
        log.info("[SmsScenarioService] 触发业务验证码生成与投递 -> 号码: {}, 业务类型: {}",
                DesensitizeUtil.mobilePhone(req.getPhone()), req.getBizType());
        return verifyCodeTemplate.generateAndSend(req.getPhone(), req.getBizType());
    }

    /**
     * 核验业务验证码 (通过后立即物理自毁)
     *
     * @param req 验证码核验请求
     * @return 是否核验成功
     */
    public boolean verifyCode(VerifyCodeCheckRequest req) {
        log.info("[SmsScenarioService] 触发业务验证码核验 -> 号码: {}, 业务类型: {}",
                DesensitizeUtil.mobilePhone(req.getPhone()), req.getBizType());
        return verifyCodeTemplate.verify(req.getPhone(), req.getCode(), req.getBizType());
    }

    /**
     * 动态注册或更新短信渠道
     *
     * @param req 渠道注册请求传输对象
     */
    public void registerDynamicChannel(DynamicChannelRegisterRequest req) {
        SmsChannel channel = SmsChannel.builder()
                .name(req.getName())
                .supplier(req.getSupplier())
                .accessKeyId(req.getAccessKeyId())
                .accessKeySecret(req.getAccessKeySecret())
                .signName(req.getSignName())
                .appId(req.getAppId())
                .region(req.getRegion())
                .endpoint(req.getEndpoint())
                .failoverChannel(req.getFailoverChannel())
                .build();
        channelRegistry.register(channel);
    }

    /**
     * 获取所有已注册的短信渠道视图列表（已过滤敏感凭据）
     *
     * @return 渠道视图集合
     */
    public Collection<SmsChannelVO> getChannels() {
        return channelRegistry.getChannels().values().stream()
                .map(c -> SmsChannelVO.builder()
                        .name(c.getName())
                        .supplier(c.getSupplier())
                        .signName(c.getSignName())
                        .appId(c.getAppId())
                        .region(c.getRegion())
                        .endpoint(c.getEndpoint())
                        .failoverChannel(c.getFailoverChannel())
                        .build())
                .toList();
    }

    private SmsRequest buildRequest(SendSmsRequest req) {
        return SmsRequest.builder()
                .phone(req.getPhone())
                .templateId(req.getTemplateId())
                .signName(req.getSignName())
                .channel(req.getChannel())
                .params(toSmsParam(req.getParams()))
                .build();
    }

    private SmsParam toSmsParam(Map<String, String> map) {
        SmsParam param = SmsParam.create();
        if (map != null) {
            map.forEach(param::add);
        }
        return param;
    }
}
