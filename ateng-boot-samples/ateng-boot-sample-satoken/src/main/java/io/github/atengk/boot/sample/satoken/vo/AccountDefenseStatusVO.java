package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 账号登录防暴力破解状态视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountDefenseStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 账号标识
     */
    private String account;

    /**
     * 是否处于锁定状态
     */
    private boolean locked;

    /**
     * 连续失败次数
     */
    private int failureCount;

    /**
     * 剩余重试次数
     */
    private int remainRetryCount;

    /**
     * 剩余锁定时间（秒）
     */
    private long lockTime;
}
