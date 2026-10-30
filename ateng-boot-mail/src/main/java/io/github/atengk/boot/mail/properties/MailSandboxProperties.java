package io.github.atengk.boot.mail.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮件测试安全沙箱配置属性
 * <p>
 * 用于在非生产（本地/测试/预发）环境拦截或重定向邮件，杜绝向真实用户误发测试数据。
 * 支持白名单精确与通配符放行、强制重定向清场与纯日志模拟发信。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Data
public class MailSandboxProperties {

    /**
     * 沙箱总开关，默认 false
     */
    private boolean enabled = false;

    /**
     * 白名单收件人列表（支持精确邮箱如 admin@example.com 与通配符匹配如 *@internal.com、*@test.org）
     * 匹配白名单的邮件将正常放行物理发信。
     */
    private List<String> allowedRecipients = new ArrayList<>();

    /**
     * 测试重定向安全邮箱地址（如 mail-trap@company.com）
     * 当邮件包含非白名单收件人时，将 to 重定向至此地址，并彻底清空 cc 和 bcc。
     */
    private String redirectTo;

    /**
     * 纯日志模拟发信开关，默认 false
     * 开启后不发起任何真实物理 SMTP 网络连接，直接在控制台输出脱敏完整邮件报文并返回虚拟成功回执。
     */
    private boolean logOnly = false;
}
