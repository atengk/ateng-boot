package io.github.atengk.boot.core.spi;

/**
 * 当前操作人身份提取函数式 SPI 接口
 * 供外部安全鉴权体系（如 Sa-Token、Spring Security、JWT 解析等）无缝接入提供当前用户信息
 *
 * @author Ateng
 * @since 2026-10-15
 */
@FunctionalInterface
public interface CurrentUserProvider {

    /**
     * 获取当前操作人唯一标识（用户 ID 或账号）
     *
     * @return 当前操作人标识，未登录或未识别时可返回 null
     */
    String getCurrentUser();

    /**
     * 获取真实原始操作人唯一标识（仅在处于身份模拟 RunAs 时非空）
     *
     * @return 原始操作人标识，非模拟态返回 null
     */
    default String getOriginalUser() {
        return null;
    }

    /**
     * 判断当前是否处于身份模拟态 (RunAs)
     *
     * @return true 表示处于身份模拟态
     */
    default boolean isRunAs() {
        return false;
    }
}
