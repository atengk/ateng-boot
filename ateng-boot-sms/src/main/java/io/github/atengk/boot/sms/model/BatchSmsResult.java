package io.github.atengk.boot.sms.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 批量短信发送聚合结果领域对象
 * 封装总数、成功数、失败数、黑名单静默拦截数、失败号码清单及各批次厂商原始回执
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@Builder
@ToString
public class BatchSmsResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否全部成功发送（无失败号码且成功数 > 0）
     */
    private final boolean success;

    /**
     * 请求总手机号数
     */
    private final int totalCount;

    /**
     * 成功投递手机号总数
     */
    private final int successCount;

    /**
     * 投递失败手机号总数
     */
    private final int failedCount;

    /**
     * 出站黑名单前置静默拦截数
     */
    private final int blacklistedCount;

    /**
     * 发送失败的手机号列表
     */
    private final List<String> failedPhones;

    /**
     * 黑名单静默拦截的手机号列表
     */
    private final List<String> blacklistedPhones;

    /**
     * 各切片批次厂商返回结果列表
     */
    private final List<SmsResult> batchResults;

    /**
     * 构造聚合结果快捷方法
     *
     * @param totalCount        请求总号码数
     * @param successCount      成功号码数
     * @param failedPhones      失败号码列表
     * @param blacklistedPhones 黑名单拦截号码列表
     * @param batchResults      切片批次回执列表
     * @return BatchSmsResult 聚合结果
     */
    public static BatchSmsResult of(int totalCount,
                                    int successCount,
                                    List<String> failedPhones,
                                    List<String> blacklistedPhones,
                                    List<SmsResult> batchResults) {
        int failed = failedPhones != null ? failedPhones.size() : 0;
        int blacklisted = blacklistedPhones != null ? blacklistedPhones.size() : 0;
        boolean allSuccess = (failed == 0 && successCount > 0);
        return BatchSmsResult.builder()
                .success(allSuccess)
                .totalCount(totalCount)
                .successCount(successCount)
                .failedCount(failed)
                .blacklistedCount(blacklisted)
                .failedPhones(failedPhones != null ? Collections.unmodifiableList(failedPhones) : Collections.emptyList())
                .blacklistedPhones(blacklistedPhones != null ? Collections.unmodifiableList(blacklistedPhones) : Collections.emptyList())
                .batchResults(batchResults != null ? Collections.unmodifiableList(batchResults) : Collections.emptyList())
                .build();
    }
}
