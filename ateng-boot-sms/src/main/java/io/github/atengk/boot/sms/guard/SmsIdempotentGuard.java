package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.SmsRequest;

import java.time.Duration;

/**
 * 短信出站发信幂等防重卫士 SPI
 * 在指定时间窗口内基于业务流水号 (bizId) 或发信三元组指纹实施防重拦截
 *
 * @author Ateng
 * @since 2026-11-07
 */
public interface SmsIdempotentGuard {

    /**
     * 尝试获取幂等排他锁
     *
     * @param key    幂等唯一指纹键
     * @param window 幂等窗口时长
     * @return true 获取成功（首次发信）；false 获取失败（已在窗口期内存在，需拦截）
     */
    boolean acquireLock(String key, Duration window);

    /**
     * 主动释放幂等锁（底层发送遭遇异常失败时回滚释放，允许业务后续重试）
     *
     * @param key 幂等唯一指纹键
     */
    void releaseLock(String key);

    /**
     * 根据发信请求生成规范化幂等指纹键
     * 若请求携带显式 bizId 则直接使用；否则基于 phone + templateId + params 计算 SHA-256 摘要
     *
     * @param request 发信请求
     * @return 幂等指纹键
     */
    default String generateKey(SmsRequest request) {
        if (request == null) {
            return "";
        }
        if (request.getBizId() != null && !request.getBizId().isBlank()) {
            return "biz:" + request.getBizId().trim();
        }
        String phone = request.getPhone() != null ? request.getPhone().trim() : "";
        String templateId = request.getTemplateId() != null ? request.getTemplateId().trim() : "";
        String paramsStr = request.getParams() != null ? request.getParams().asMap().toString() : "";
        StringBuilder rawBuilder = new StringBuilder()
                .append(phone).append("|").append(templateId).append("|").append(paramsStr);
        if (request.getContent() != null && !request.getContent().isBlank()) {
            rawBuilder.append("|").append(request.getContent().trim());
        }
        return "hash:" + DigestUtil.sha256Hex(rawBuilder.toString());
    }

    /**
     * 根据批量发信请求生成幂等指纹键（仅当显式传入 bizId 时生成防重键，未传入则返回 null）
     *
     * @param request 批量发信请求
     * @return 批量幂等指纹键，未指定 bizId 时返回 null
     */
    default String generateKey(BatchSmsRequest request) {
        if (request == null || request.getBizId() == null || request.getBizId().isBlank()) {
            return null;
        }
        return "batch:biz:" + request.getBizId().trim();
    }
}
