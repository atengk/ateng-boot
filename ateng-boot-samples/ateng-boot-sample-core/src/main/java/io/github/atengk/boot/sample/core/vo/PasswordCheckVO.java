package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户口令安全哈希、校验与平滑升级演示响应视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordCheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务账号名
     */
    private String username;

    /**
     * 默认安全基线生成的自描述哈希串
     */
    private String encodedPassword;

    /**
     * 同明文再次哈希生成的自描述哈希串（随机盐不同）
     */
    private String secondEncodedPassword;

    /**
     * 同明文两次哈希输出是否严格不相等（随机盐隔离验证）
     */
    private boolean saltUnique;

    /**
     * 正确口令常量时间比对结果
     */
    private boolean matched;

    /**
     * 错误口令与畸形哈希串是否均被安全拦截返回 false
     */
    private boolean wrongPasswordRejected;

    /**
     * 存量低迭代哈希串是否触发升级提示
     */
    private boolean legacyNeedsUpgrade;

    /**
     * 升级重算后的最新默认基线哈希串
     */
    private String upgradedPassword;

    /**
     * 最新默认基线哈希串是否无需再升级
     */
    private boolean currentNeedsUpgrade;
}
