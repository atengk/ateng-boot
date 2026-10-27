package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.ip.IpAccessMode;
import io.github.atengk.boot.web.ip.annotation.IpAccessControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * IP 访问控制防火墙与 CIDR 子网判定场景控制器
 * 演示方法级白名单、黑名单、CIDR/IPv6 网段、自定义拦截文案与物理 403 切断模式
 *
 * @author Ateng
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/sample/ip-access")
public class WebIpAccessSampleController {

    /**
     * 演示方法级白名单控制：仅允许 127.0.0.1 及 192.168.1.0/24 访问
     *
     * @return 访问结果
     */
    @GetMapping("/whitelist")
    @IpAccessControl(mode = IpAccessMode.WHITE_LIST, rules = {"127.0.0.1", "192.168.1.0/24"})
    public Result<String> whitelistEndpoint() {
        return Result.success("白名单放行成功");
    }

    /**
     * 演示方法级黑名单控制：拒绝 10.0.0.0/8 与 203.0.113.100 访问
     *
     * @return 访问结果
     */
    @GetMapping("/blacklist")
    @IpAccessControl(mode = IpAccessMode.BLACK_LIST, rules = {"10.0.0.0/8", "203.0.113.100"})
    public Result<String> blacklistEndpoint() {
        return Result.success("黑名单未命中，放行成功");
    }

    /**
     * 演示 CIDR 子网掩码与 IPv6 网段白名单
     *
     * @return 访问结果
     */
    @GetMapping("/cidr-ipv6")
    @IpAccessControl(mode = IpAccessMode.WHITE_LIST, rules = {"172.16.0.0/12", "2001:db8::/32", "::1"})
    public Result<Map<String, String>> cidrIpv6Endpoint() {
        return Result.success(Map.of("network", "CIDR-IPv6-Allowed"));
    }

    /**
     * 演示自定义拒绝文案（遵循 ADR-0004 统一 HTTP 200 + Result.fail(403)）
     *
     * @return 访问结果
     */
    @GetMapping("/custom-message")
    @IpAccessControl(
            mode = IpAccessMode.WHITE_LIST,
            rules = {"192.168.1.200"},
            message = "内部核心机房特许通道，当前 IP 无权访问"
    )
    public Result<String> customMessageEndpoint() {
        return Result.success("特许通道放行成功");
    }

    /**
     * 演示物理 403 切断模式（直接返回 HTTP 403 状态码）
     *
     * @return 访问结果
     */
    @GetMapping("/http-403")
    @IpAccessControl(
            mode = IpAccessMode.WHITE_LIST,
            rules = {"192.168.1.200"},
            httpStatus403 = true
    )
    public Result<String> http403Endpoint() {
        return Result.success("特许通道放行成功");
    }

    /**
     * 供 URL 路径级过滤器演示拦截的受保护端点
     *
     * @return 访问结果
     */
    @GetMapping("/filter-admin/dashboard")
    public Result<String> filterProtectedDashboard() {
        return Result.success("管理员仪表盘数据");
    }

    /**
     * 供 URL 路径级过滤器演示排除放行的公开端点
     *
     * @return 访问结果
     */
    @GetMapping("/filter-admin/public-ping")
    public Result<String> filterPublicPing() {
        return Result.success("public-pong");
    }
}
