package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 当前登录会话与底层 Sa-Token 配置生效快照视图对象
 *
 * @author Ateng
 * @since 2026-11-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenPolicySnapshotVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 账号体系类型标识（如 login、admin）
     */
    private String loginType;

    /**
     * 当前令牌实际剩余有效秒数
     */
    private Long tokenTimeout;

    /**
     * 当前令牌实际剩余活跃空闲秒数（-1 表示未开启活跃超时）
     */
    private Long tokenActiveTimeout;

    /**
     * 生效的全局 Token 绝对有效期（秒）
     */
    private Long globalTimeout;

    /**
     * 生效的全局活跃空闲超时（秒，-1 表示关闭）
     */
    private Long globalActiveTimeout;

    /**
     * 同一账号是否允许并发多端登录
     */
    private Boolean isConcurrent;

    /**
     * 同端多次登录是否共用同一 Token
     */
    private Boolean isShare;

    /**
     * 最大并发登录会话数
     */
    private Integer maxLoginCount;

    /**
     * Token 生成算法风格
     */
    private String tokenStyle;
}
