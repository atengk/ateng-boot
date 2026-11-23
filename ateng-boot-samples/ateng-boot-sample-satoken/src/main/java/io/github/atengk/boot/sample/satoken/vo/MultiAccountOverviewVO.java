package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 多账号渠道概览视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiAccountOverviewVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前上下文用户唯一标识
     */
    private String userId;

    /**
     * 当前上下文租户唯一标识
     */
    private String tenantId;

    /**
     * 所属账号体系渠道（如 admin、member）
     */
    private String channel;
}
