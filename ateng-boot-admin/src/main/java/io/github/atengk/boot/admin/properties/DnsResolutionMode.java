package io.github.atengk.boot.admin.properties;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 实例注册及出站代理目标域名 DNS 解析校验模式
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
@AllArgsConstructor
public enum DnsResolutionMode implements IBaseEnum<String> {

    /**
     * 禁用 DNS 解析，仅校验证书/字面量 IP 与 URL 模式白名单
     */
    DISABLED("disabled", "禁用 DNS 解析"),

    /**
     * 宽松兜底：域名解析结果命中黑名单则阻断；解析失败（如离线环境或内网服务名）时放行
     */
    FAIL_OPEN("fail_open", "DNS 解析命中阻断，解析失败时宽松放行"),

    /**
     * 严格封闭：域名解析命中黑名单或解析失败均严格阻断（生产高安全推荐）
     */
    FAIL_CLOSED("fail_closed", "DNS 解析命中阻断且解析失败严格拒绝");

    private final String code;

    private final String label;
}
