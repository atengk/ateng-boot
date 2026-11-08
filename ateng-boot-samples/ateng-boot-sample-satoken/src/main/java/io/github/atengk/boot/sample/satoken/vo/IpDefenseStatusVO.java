package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 客户端 IP 防暴力破解熔断状态视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IpDefenseStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户端真实 IP
     */
    private String clientIp;

    /**
     * 是否处于临时熔断状态
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

    /**
     * 是否命中白名单豁免
     */
    private boolean whiteListed;
}
