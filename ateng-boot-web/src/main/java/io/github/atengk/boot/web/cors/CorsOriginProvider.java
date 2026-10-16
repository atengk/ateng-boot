package io.github.atengk.boot.web.cors;

/**
 * 动态跨域来源判定提供者 SPI 契约接口
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface CorsOriginProvider {

    /**
     * 判断指定请求来源 Origin 是否允许跨域访问
     *
     * @param origin 客户端请求头携带的 Origin 域名（如 "https://app.ateng.com"）
     * @return true 允许跨域访问；false 拒绝跨域请求
     */
    boolean isAllowed(String origin);
}
