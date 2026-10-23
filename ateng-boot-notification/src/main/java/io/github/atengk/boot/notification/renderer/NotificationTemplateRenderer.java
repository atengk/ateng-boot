package io.github.atengk.boot.notification.renderer;

import java.util.Map;

/**
 * 协同通知动态模板渲染引擎 SPI
 * 解耦具体模板引擎实现，内置极简正则占位符替换保底，可平滑扩展 Thymeleaf、Freemarker 等第三方模板引擎
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface NotificationTemplateRenderer {

    /**
     * 依据模板正文与变量键值对执行动态插值渲染
     *
     * @param templateContent 模板正文（包含占位符）
     * @param variables       变量上下文键值对
     * @return 渲染后的最终文本
     */
    String render(String templateContent, Map<String, Object> variables);
}
