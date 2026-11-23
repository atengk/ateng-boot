package io.github.atengk.boot.mail.template.renderer;

import java.util.Locale;
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

    /**
     * 根据指定国际化区域语言渲染邮件模板
     *
     * @param templateName 模板名称（如 welcome、mail/verify-code 或内联模板字符串）
     * @param model        数据模型字典
     * @param locale       国际化语言区域（若为 null 则退化使用默认区域）
     * @return 渲染后的富文本 HTML 字符串
     */
    default String render(String templateName, Map<String, Object> model, Locale locale) {
        return render(templateName, model);
    }
}
