package io.github.atengk.boot.sample.sms.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sample.sms.dto.*;
import io.github.atengk.boot.sample.sms.listener.SampleSmsEventListener;
import io.github.atengk.boot.sample.sms.vo.SmsChannelVO;
import io.github.atengk.boot.sample.sms.vo.SmsCircuitBreakerStatusVO;
import io.github.atengk.boot.sample.sms.vo.SmsSendAuditVO;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.model.*;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import io.github.atengk.boot.sms.template.SmsVerifyCodeTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
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
    private final SampleSmsEventListener eventListener;

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
     * 本地事务提交后受管异步发信 (演示事务提交后异步发信与回滚防幽灵短信)
     *
     * @param req            发信请求
     * @param shouldRollback 是否模拟业务异常触发事务回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendTransactionalAsync(SendSmsRequest req, boolean shouldRollback) {
        SmsRequest request = buildRequest(req);
        log.info("[SmsScenarioService] 开启本地物理事务，注册发信提交后受管异步投递钩子 (目标号码: {})",
                DesensitizeUtil.mobilePhone(req.getPhone()));

        // 1. 注册事务提交后受管异步发信钩子
        smsTemplate.sendAsyncAfterCommit(request);

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
     * 千人千面差异化个性化批量发信
     *
     * @param req 差异化批量请求
     * @return BatchSmsResult
     */
    public BatchSmsResult sendDifferentialBatchSms(DifferentialBatchSmsRequest req) {
        Map<String, SmsParam> phoneParams = new LinkedHashMap<>();
        if (req.getPhoneParams() != null) {
            req.getPhoneParams().forEach((p, paramMap) -> phoneParams.put(p, toSmsParam(paramMap)));
        }

        BatchSmsRequest request = BatchSmsRequest.builder()
                .templateId(req.getTemplateId())
                .signName(req.getSignName())
                .channel(req.getChannel())
                .bizId(req.getBizId())
                .urgent(req.isUrgent())
                .phoneParams(phoneParams)
                .build();

        log.info("[SmsScenarioService] 发起千人千面差异化批量群发 -> 目标号码数: {}, 模板: {}",
                request.getPhones().size(), req.getTemplateId());
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
     * 验证码只读预检（不自毁，不扣减重试次数）
     *
     * @param req 核验请求
     * @return 是否有效
     */
    public boolean checkVerifyCode(VerifyCodeCheckRequest req) {
        log.info("[SmsScenarioService] 触发业务验证码只读预检 -> 号码: {}, 业务类型: {}",
                DesensitizeUtil.mobilePhone(req.getPhone()), req.getBizType());
        return verifyCodeTemplate.check(req.getPhone(), req.getCode(), req.getBizType());
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
     * 验证码主动作废
     *
     * @param req 作废请求
     */
    public void invalidateVerifyCode(VerifyCodeInvalidateRequest req) {
        log.info("[SmsScenarioService] 触发业务验证码主动作废 -> 号码: {}, 业务类型: {}",
                DesensitizeUtil.mobilePhone(req.getPhone()), req.getBizType());
        verifyCodeTemplate.invalidate(req.getPhone(), req.getBizType());
    }

    /**
     * 动态注册或更新短信渠道
     *
     * @param req 渠道注册请求传输对象
     */
    public void registerDynamicChannel(DynamicChannelRegisterRequest req) {
        Map<String, io.github.atengk.boot.sms.properties.SmsTemplateProperties> templateMap = null;
        if (req.getTemplates() != null) {
            templateMap = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : req.getTemplates().entrySet()) {
                io.github.atengk.boot.sms.properties.SmsTemplateProperties prop = new io.github.atengk.boot.sms.properties.SmsTemplateProperties();
                prop.setTemplateId(entry.getValue());
                templateMap.put(entry.getKey(), prop);
            }
        }

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
                .templates(templateMap)
                .weight(req.getWeight() != null ? req.getWeight() : 1)
                .build();
        channelRegistry.register(channel);
    }

    /**
     * 注销短信渠道
     *
     * @param channelName 渠道名称
     */
    public void unregisterChannel(String channelName) {
        log.info("[SmsScenarioService] 动态注销短信渠道 -> 名称: {}", channelName);
        channelRegistry.unregister(channelName);
    }

    /**
     * 获取所有已注册的短信渠道视图列表（已过滤敏感凭据）
     *
     * @return 渠道视图集合
     */
    public Collection<SmsChannelVO> getChannels() {
        return channelRegistry.getChannels().values().stream()
                .map(c -> {
                    Map<String, String> tplMap = null;
                    if (c.getTemplates() != null) {
                        tplMap = new LinkedHashMap<>();
                        for (Map.Entry<String, io.github.atengk.boot.sms.properties.SmsTemplateProperties> entry : c.getTemplates().entrySet()) {
                            tplMap.put(entry.getKey(), entry.getValue() != null ? entry.getValue().getTemplateId() : null);
                        }
                    }
                    return SmsChannelVO.builder()
                            .name(c.getName())
                            .supplier(c.getSupplier())
                            .signName(c.getSignName())
                            .appId(c.getAppId())
                            .region(c.getRegion())
                            .endpoint(c.getEndpoint())
                            .failoverChannel(c.getFailoverChannel())
                            .weight(c.getWeight())
                            .templates(tplMap)
                            .build();
                })
                .toList();
    }

    /**
     * 平滑加权轮询负载均衡发信
     *
     * @param req 发信请求
     * @return SmsResult
     */
    public SmsResult sendLoadBalanced(SendSmsRequest req) {
        req.setChannel(SmsTemplate.LOAD_BALANCE_CHANNEL);
        return sendSingleSms(req);
    }

    /**
     * 获取所有渠道健康度熔断状态
     *
     * @return 渠道名称 -> 熔断状态视图对象
     */
    public Map<String, SmsCircuitBreakerStatusVO> getCircuitBreakerStatus() {
        SmsChannelCircuitBreaker cb = smsTemplate.getCircuitBreaker();
        Map<String, SmsCircuitBreakerStatusVO> statusMap = new LinkedHashMap<>();
        for (String channelName : channelRegistry.getChannels().keySet()) {
            boolean open = cb != null && cb.isOpen(channelName);
            statusMap.put(channelName, SmsCircuitBreakerStatusVO.builder()
                    .channel(channelName)
                    .isOpen(open)
                    .status(open ? "OPEN" : "CLOSED")
                    .build());
        }
        return statusMap;
    }

    /**
     * 重置渠道健康度熔断状态
     *
     * @param channelName 渠道名称（为 null 或 blank 时重置全部渠道）
     */
    public void resetCircuitBreaker(String channelName) {
        SmsChannelCircuitBreaker cb = smsTemplate.getCircuitBreaker();
        if (cb != null) {
            if (channelName != null && !channelName.isBlank()) {
                cb.reset(channelName);
                log.info("[SmsScenarioService] 重置渠道 [{}] 熔断状态为 CLOSED", channelName);
            } else {
                cb.resetAll();
                log.info("[SmsScenarioService] 重置全部渠道熔断状态为 CLOSED");
            }
        }
    }

    /**
     * 获取当前出站黑名单号码集合
     *
     * @return 黑名单手机号集合
     */
    public Set<String> getBlacklist() {
        return smsTemplate.getBlacklist();
    }

    /**
     * 添加手机号到出站黑名单
     *
     * @param phone  手机号
     * @param reason 拉黑原因
     */
    public void addBlacklist(String phone, String reason) {
        log.info("[SmsScenarioService] 将手机号 [{}] 纳入出站黑名单 (原因: {})",
                DesensitizeUtil.mobilePhone(phone), reason);
        smsTemplate.addBlacklist(phone, reason != null ? reason : "手动添加黑名单");
    }

    /**
     * 从出站黑名单移除手机号
     *
     * @param phone 手机号
     */
    public void removeBlacklist(String phone) {
        log.info("[SmsScenarioService] 将手机号 [{}] 从出站黑名单移除", DesensitizeUtil.mobilePhone(phone));
        smsTemplate.removeBlacklist(phone);
    }

    /**
     * 清空出站黑名单
     */
    public void clearBlacklist() {
        if (smsTemplate.getBlacklistProvider() != null) {
            smsTemplate.getBlacklistProvider().clear();
            log.info("[SmsScenarioService] 清空出站黑名单");
        }
    }

    /**
     * 获取全链路发信审计事件流水
     *
     * @return 审计视图对象列表
     */
    public List<SmsSendAuditVO> getAuditEvents() {
        return eventListener.getAuditEvents().stream()
                .map(SmsSendAuditVO::fromEvent)
                .toList();
    }

    private SmsRequest buildRequest(SendSmsRequest req) {
        return SmsRequest.builder()
                .phone(req.getPhone())
                .templateId(req.getTemplateId())
                .signName(req.getSignName())
                .channel(req.getChannel())
                .bizId(req.getBizId())
                .urgent(req.isUrgent())
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
