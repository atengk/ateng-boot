package io.github.atengk.boot.sms.properties;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 短信出站测试安全沙箱配置属性
 * <p>
 * 用于非生产环境防止误发真实客户手机号，支持纯日志模拟、通配符白名单放行与防轰炸测试号重定向。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
public class SmsSandboxProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否开启短信测试安全沙箱，默认 false
     */
    private boolean enabled = false;

    /**
     * 纯日志模拟模式，默认 false
     * 开启后不向云厂商发起任何物理外发请求，仅输出脱敏模拟日志并返回虚拟成功回执
     */
    private boolean logOnly = false;

    /**
     * 允许穿透放行的测试手机号白名单列表（支持 * 通配符，如 13800138*）
     */
    private List<String> allowedPhones = new ArrayList<>();

    /**
     * 非白名单手机号强制重定向的目标测试接收手机号
     * 若未配置此项，所有包含非白名单号码的请求将被 Fail-Fast 阻断告警
     */
    private String redirectTo;
}
