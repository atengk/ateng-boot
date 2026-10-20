package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 身份模拟状态与双维审计快照视图展示对象
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImpersonateStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否处于身份模拟状态
     */
    private Boolean isRunAs;

    /**
     * 当前生效用户唯一标识（被模拟人）
     */
    private String currentUserId;

    /**
     * 真实登录人唯一标识
     */
    private String realUserId;

    /**
     * 原始操作人唯一标识（运维管理员）
     */
    private String originalUserId;
}
