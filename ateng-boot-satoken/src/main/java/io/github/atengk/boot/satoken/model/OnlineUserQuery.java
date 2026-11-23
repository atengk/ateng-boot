package io.github.atengk.boot.satoken.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 在线会话多维度条件分页查询对象
 * <p>
 * 支持按所属租户精确隔离、模糊关键字及细粒度账号/设备/IP（精确 IP 与 CIDR 网段）条件筛选；
 * 所有字符串过滤条件均自动 {@code trim} 并忽略空白字符。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class OnlineUserQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 可选账号体系类型标识（为空时使用调用方传入 loginType 或默认 "login" 体系）
     */
    private String loginType;

    /**
     * 所属多租户标识（精确匹配）
     */
    private String tenantId;

    /**
     * 综合模糊关键字（大小写不敏感匹配 userId / userAccount / userName / tokenValue）
     */
    private String keyword;

    /**
     * 用户唯一标识（精确匹配）
     */
    private String userId;

    /**
     * 登录账号（大小写不敏感包含匹配）
     */
    private String userAccount;

    /**
     * 用户姓名或昵称（大小写不敏感包含匹配）
     */
    private String userName;

    /**
     * 登录终端设备标识（大小写不敏感精确匹配，如 PC / APP / H5）
     */
    private String loginDevice;

    /**
     * 登录客户端 IP 或 CIDR 网段条件（如 192.168.1.10 或 10.10.2.0/24）
     */
    private String loginIp;

    /**
     * 本次查询单次安全扫描上限覆盖值（可选，大于 0 时覆盖全局默认扫描上限）
     */
    private Integer maxScanLimit;

    /**
     * 判断当前查询对象是否未设置任何实质性过滤条件
     *
     * @return true 表示无任何条件筛选，可走快速分页路径
     */
    public boolean hasNoFilterCriteria() {
        return isBlank(tenantId)
                && isBlank(keyword)
                && isBlank(userId)
                && isBlank(userAccount)
                && isBlank(userName)
                && isBlank(loginDevice)
                && isBlank(loginIp);
    }

    private static boolean isBlank(String val) {
        return val == null || val.isBlank();
    }
}
