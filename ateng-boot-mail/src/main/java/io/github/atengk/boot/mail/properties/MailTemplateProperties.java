package io.github.atengk.boot.mail.properties;

import lombok.Data;
import org.springframework.validation.annotation.Validated;

/**
 * 邮件模板渲染配置属性
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Validated
public class MailTemplateProperties {

    /**
     * 是否在内置 SimpleMailTemplateRenderer 中开启 HTML 特殊字符转义防 XSS，默认 false 保持向后兼容
     */
    private boolean htmlEscape = false;
}
