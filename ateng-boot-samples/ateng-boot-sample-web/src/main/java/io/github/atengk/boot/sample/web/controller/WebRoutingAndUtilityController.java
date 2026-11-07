package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.util.IpUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API 多版本路由分流与 Web 运行时工具集场景控制器
 * 演示多级反向代理客户端真实 IP 提取（IpUtil）、RFC 6266 防乱码双重编码安全流式下载（ServletUtil）及 CIDR 子网判定
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/web/routing")
public class WebRoutingAndUtilityController {

    /**
     * 客户端真实 IP 穿透多级反向代理提取演示端点
     * 穿透 X-Forwarded-For, X-Real-IP, Proxy-Client-IP, WL-Proxy-Client-IP 等提取真实外网 IP
     *
     * @param request HTTP 请求上下文
     * @return 客户端真实 IP
     */
    @GetMapping("/client-ip")
    public Result<String> getClientIp(HttpServletRequest request) {
        String clientIp = IpUtil.getClientIp(request);
        return Result.success(clientIp);
    }

    /**
     * 财务对账单 RFC 6266 双重文件名编码防乱码安全流式下载演示端点
     * 自动组装 filename="xxx.txt" 与 filename*=UTF-8''xxx.txt，杜绝 Chrome, Edge, Safari 各端乱码
     *
     * @param response HTTP 响应
     * @throws IOException 流写出异常
     */
    @GetMapping("/download")
    public void downloadSampleFile(HttpServletResponse response) throws IOException {
        byte[] data = "ateng-boot 企业级运行时对账单文件下载内容 (RFC 6266 标准编码)".getBytes(StandardCharsets.UTF_8);
        ServletUtil.writeDownload(response, "财务对账月度报表-202611.txt", data);
    }

    /**
     * 纯原生无 DNS 依赖 CIDR 子网掩码匹配判定工具端点
     *
     * @param ip   待检测 IP 地址
     * @param cidr CIDR 规则表达式（如 192.168.1.0/24 或 2001:db8::/32）
     * @return 判定结果
     */
    @GetMapping("/ip-check")
    public Result<Map<String, Object>> checkIpCidr(@RequestParam(defaultValue = "192.168.1.50") String ip,
                                                  @RequestParam(defaultValue = "192.168.1.0/24") String cidr) {
        boolean inRange = IpUtil.isInRange(ip, cidr);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ip", ip);
        map.put("cidr", cidr);
        map.put("inRange", inRange);
        return Result.success(map);
    }
}
