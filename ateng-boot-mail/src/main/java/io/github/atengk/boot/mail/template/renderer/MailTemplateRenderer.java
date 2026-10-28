package io.github.atengk.boot.mail.template.renderer;

import java.util.Map;

/**
 * 邮件模板动态渲染引擎 SPI 契约
 *
 * @author Ateng
 * @since 2026-10-22
 */
public interface MailTemplateRenderer {

    /**
     * 渲染指定模板名称与数据模型
     *
     * @param templateName 模板名称（如 welcome、mail/verify-code 或内联模板字符串）
     * @param model        数据模型字典
     * @return 渲染后的富文本 HTML 字符串
     */
    String render(String templateName, Map<String, Object> model);
}
