package io.github.atengk.boot.admin.provider;

/**
 * Spring Boot Admin 控制台登录身份校验扩展点 SPI
 *
 * @author Ateng
 * @since 2026-10-26
 */
public interface AdminAuthProvider {

    /**
     * 校验用户名与访问口令
     *
     * @param username 用户名
     * @param password 访问口令
     * @return true 若认证通过
     */
    boolean authenticate(String username, String password);
}
