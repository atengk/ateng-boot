package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 账号防暴力破解状态视图展示对象
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefenseStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否已被封禁锁定
     */
    private Boolean isLocked;

    /**
     * 剩余可重试尝试次数
     */
    private Integer remainRetryCount;

    /**
     * 当前时间窗口内累计失败次数
     */
    private Integer failureCount;

    /**
     * 剩余封禁锁定时间（秒）
     */
    private Long disableTime;

    /**
     * 防爆破策略组件是否启用
     */
    private Boolean enabled;
}
