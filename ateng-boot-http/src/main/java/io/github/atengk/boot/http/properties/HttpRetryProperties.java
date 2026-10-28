package io.github.atengk.boot.http.properties;

import lombok.Data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 出站 HTTP 弹性指数退避重试配置属性类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
public class HttpRetryProperties {

    /**
     * 是否启用出站 HTTP 弹性重试机制（默认：false 关闭）
     */
    private boolean enabled = false;

    /**
     * 最大尝试总次数（包含首次调用，例如 3 表示 1 次首次 + 2 次重试，默认：3）
     */
    private int maxAttempts = 3;

    /**
     * 初始退避重试等待时长（默认：500ms）
     */
    private Duration delay = Duration.ofMillis(500);

    /**
     * 指数退避增长倍数因子（默认：2.0）
     */
    private double multiplier = 2.0;

    /**
     * 单次退避等待时长上限（防止指数无界爆炸，默认：3s）
     */
    private Duration maxDelay = Duration.ofSeconds(3);

    /**
     * 是否启用 Equal Jitter 平滑随机抖动以打散重试洪峰（默认：true 开启）
     */
    private boolean jitter = true;

    /**
     * 允许触发自动重试的 HTTP 动词白名单（默认仅针对幂等方法 GET 与 HEAD，防止非幂等副作用）
     */
    private List<String> retryMethods = new ArrayList<>(List.of("GET", "HEAD"));

    /**
     * 允许触发自动重试的远端 HTTP 状态码列表（默认针对网络临时不可达或网关抖动：502, 503, 504）
     */
    private List<Integer> retryStatusCodes = new ArrayList<>(List.of(502, 503, 504));
}
