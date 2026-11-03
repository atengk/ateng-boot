package io.github.atengk.boot.sms.template;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.exception.SmsException;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsVerifyCodeException;
import io.github.atengk.boot.sms.limiter.SmsRateLimiter;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.properties.SmsVerifyCodeProperties;
import io.github.atengk.boot.sms.repository.SmsVerifyCodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Random;

/**
 * 验证码全生命周期治理专职门面
 * 整合频控防刷、冷却拦截、随机生成、发信投递、防爆破输错冻结与原子核销防重放
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class SmsVerifyCodeTemplate {

    private static final String NUMBERS = "0123456789";
    private static final String UPPER_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String ALL_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SmsTemplate smsTemplate;
    private final SmsProperties properties;
    private final SmsRateLimiter rateLimiter;
    private final SmsVerifyCodeRepository repository;
    private final Random random = new SecureRandom();

    public SmsVerifyCodeTemplate(SmsTemplate smsTemplate,
                                 SmsProperties properties,
                                 SmsRateLimiter rateLimiter,
                                 SmsVerifyCodeRepository repository) {
        this.smsTemplate = smsTemplate;
        this.properties = properties != null ? properties : new SmsProperties();
        this.rateLimiter = rateLimiter;
        this.repository = repository;
    }

    /**
     * 生成验证码并通过短信发送（使用默认模板与渠道）
     *
     * @param phone   目标手机号
     * @param bizType 业务类型标识（如 "login", "register"）
     * @return 生成的验证码明文
     */
    public String generateAndSend(String phone, String bizType) {
        return generateAndSend(phone, bizType, null, null, null);
    }

    /**
     * 生成验证码并通过短信发送（指定模板ID）
     *
     * @param phone      目标手机号
     * @param bizType    业务类型标识
     * @param templateId 短信模板ID
     * @return 生成的验证码明文
     */
    public String generateAndSend(String phone, String bizType, String templateId) {
        return generateAndSend(phone, bizType, templateId, null, null);
    }

    /**
     * 生成验证码并通过短信发送（指定模板、渠道与签名）
     *
     * @param phone      目标手机号
     * @param bizType    业务类型标识
     * @param templateId 短信模板ID
     * @param channel    指定短信渠道名
     * @param signName   覆盖短信签名
     * @return 生成的验证码明文
     */
    public String generateAndSend(String phone, String bizType, String templateId, String channel, String signName) {
        return generateAndSend(phone, bizType, templateId, channel, signName, null);
    }

    /**
     * 生成验证码并通过短信发送（支持 IP 协同防刷）
     *
     * @param phone      目标手机号
     * @param bizType    业务类型标识
     * @param templateId 短信模板ID
     * @param channel    指定短信渠道名
     * @param signName   覆盖短信签名
     * @param ip         客户端 IP 地址（支持协同防刷）
     * @return 生成的验证码明文
     */
    public String generateAndSend(String phone, String bizType, String templateId, String channel, String signName, String ip) {
        BusinessAssert.hasText(phone, "目标手机号不能为空");
        SmsVerifyCodeProperties codeProp = properties.getVerifyCode() != null
                ? properties.getVerifyCode()
                : new SmsVerifyCodeProperties();

        // 1. 前置重发冷却防刷拦截（前置于滑动窗口，避免虚耗频控配额）
        repository.checkCooldown(phone, bizType);

        // 2. 滑动窗口频控防刷检查（1m/1h/1d，支持手机号与 IP 协同）
        if (rateLimiter != null) {
            rateLimiter.acquire(phone, ip);
        }

        // 3. 生成随机安全验证码
        String code = generateCode(codeProp);

        // 4. 写入仓储
        repository.save(
                phone,
                bizType,
                code,
                codeProp.getTtl(),
                codeProp.getResendInterval(),
                codeProp.getMaxRetryAttempts()
        );

        // 5. 解析模板ID（若未指定，尝试从默认或渠道配置中推导）
        String finalTemplateId = StringUtils.hasText(templateId) ? templateId : "SMS_VERIFY_CODE";

        // 6. 组装发信请求并投递
        SmsRequest request = SmsRequest.newBuilder(phone, finalTemplateId)
                .param("code", code)
                .channel(channel)
                .signName(signName)
                .bizId(bizType)
                .build();

        log.info("[SmsVerifyCodeTemplate] 触发验证码短信投递 -> 目标号码: {}, 业务类型: {}, 模板ID: {}",
                DesensitizeUtil.mobilePhone(phone), bizType, finalTemplateId);

        try {
            SmsResult result = smsTemplate.send(request);
            if (!result.isSuccess()) {
                log.warn("[SmsVerifyCodeTemplate] 验证码发送失败，回滚作废验证码: {}", result.getRawMessage());
                repository.remove(phone, bizType);
                String msg = result.getRawMessage() != null ? result.getRawMessage() : "厂商发信拒绝";
                throw new SmsVerifyCodeException("验证码短信发送失败: " + msg);
            }
        } catch (Exception ex) {
            repository.remove(phone, bizType);
            if (ex instanceof SmsException se) {
                throw se;
            }
            throw new SmsVerifyCodeException("验证码短信发送异常: " + ex.getMessage(), ex);
        }

        return code;
    }

    /**
     * 校验并原子核销验证码（防重放攻击与防暴力破解）
     * 校验成功后立即物理销毁验证码
     * 校验失败自动扣减重试次数，连续输错达到上限自动作废并冻结
     *
     * @param phone   目标手机号
     * @param code    待核验的验证码
     * @param bizType 业务类型标识
     * @return true 表示验证通过
     */
    public boolean verify(String phone, String code, String bizType) {
        return repository.verify(phone, code, bizType);
    }

    /**
     * 只读核对验证码（不删除，不扣减重试次数，供前置预校验使用）
     *
     * @param phone   目标手机号
     * @param code    待核对的验证码
     * @param bizType 业务类型标识
     * @return true 表示验证码有效且正确
     */
    public boolean check(String phone, String code, String bizType) {
        return repository.check(phone, code, bizType);
    }

    /**
     * 主动作废并清除指定手机号的验证码
     *
     * @param phone   目标手机号
     * @param bizType 业务类型标识
     */
    public void invalidate(String phone, String bizType) {
        repository.remove(phone, bizType);
    }

    /**
     * 生成随机验证码
     */
    private String generateCode(SmsVerifyCodeProperties prop) {
        int length = prop.getLength() > 0 ? prop.getLength() : 6;
        String source = switch (prop.getCharType()) {
            case ALPHA_NUMERIC -> ALL_CHARS;
            case UPPER_NUMERIC -> UPPER_CHARS;
            default -> NUMBERS;
        };

        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(source.charAt(random.nextInt(source.length())));
        }
        return sb.toString();
    }
}
