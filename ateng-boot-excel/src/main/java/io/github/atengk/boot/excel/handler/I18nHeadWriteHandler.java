package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.poi.ss.usermodel.Cell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多语言国际化表头动态转译写入处理器
 * <p>
 * 拦截表头单元格渲染事件（{@link #afterCellDispose}），自动识别表头中的 {@code {i18n:message.code}} 占位符语法，
 * 依托 Spring {@link MessageSource} 与当前上下文/请求的 {@link Locale} 执行国际化转译。
 * 未配置国际化标识的普通表头或未匹配到 Key 时平滑原样保底输出，实现 100% 无感兼容与高内聚动态国际化。
 * 显式配置 {@link #order()} 为 {@code -80000}，确保转译优先于自适应列宽与企业主题样式渲染执行。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public class I18nHeadWriteHandler implements CellWriteHandler {

    private static final Logger log = LoggerFactory.getLogger(I18nHeadWriteHandler.class);

    /**
     * 国际化占位符正则模式：匹配 {i18n:xxx}
     */
    private static final Pattern I18N_PATTERN = Pattern.compile("\\{i18n:([^}]+)\\}");
    private static final String I18N_PREFIX = "{i18n:";

    private MessageSource messageSource;
    private Locale locale;

    /**
     * 构造默认无状态处理器（自动从 Spring 上下文获取 MessageSource 与当前线程请求 Locale）
     */
    public I18nHeadWriteHandler() {
        this(null, null);
    }

    /**
     * 构造指定 MessageSource 的处理器（Locale 动态读取当前线程请求上下文）
     *
     * @param messageSource Spring MessageSource 国际化源
     */
    public I18nHeadWriteHandler(MessageSource messageSource) {
        this(messageSource, null);
    }

    /**
     * 构造指定 Locale 的处理器（MessageSource 自动从 Spring 上下文获取）
     *
     * @param locale 显式指定的国际化语言环境
     */
    public I18nHeadWriteHandler(Locale locale) {
        this(null, locale);
    }

    /**
     * 构造显式指定 MessageSource 与 Locale 的处理器
     *
     * @param messageSource Spring MessageSource 国际化源
     * @param locale        显式指定的国际化语言环境
     */
    public I18nHeadWriteHandler(MessageSource messageSource, Locale locale) {
        this.messageSource = messageSource;
        this.locale = locale;
    }

    @Override
    public int order() {
        // 保证在列宽计算 (0) 与单元格样式渲染 (-50000) 之前完成文本转译
        return -80000;
    }

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        // 1. 仅对表头单元格执行国际化转译
        if (context == null || !Boolean.TRUE.equals(context.getHead())) {
            return;
        }

        // 2. 检查全局配置开关是否开启多语言国际化转译
        if (!ExcelUtil.getI18nProperties().isEnabled()) {
            return;
        }

        Cell cell = context.getCell();
        if (cell == null) {
            return;
        }

        String rawText;
        try {
            rawText = cell.getStringCellValue();
        } catch (Exception ex) {
            return;
        }

        if (rawText == null || !rawText.contains(I18N_PREFIX)) {
            return;
        }

        Locale targetLocale = resolveLocale();
        String translated = translate(rawText, targetLocale);

        if (!rawText.equals(translated)) {
            cell.setCellValue(translated);
            if (context.getFirstCellData() != null) {
                context.getFirstCellData().setStringValue(translated);
            }
        }
    }

    /**
     * 对包含国际化占位符的表头文本执行解析与转译
     *
     * @param text         待转译原始表头文本
     * @param targetLocale 目标语言环境
     * @return 转译后的文本内容（无占位符或未匹配时原样保底返回）
     */
    public String translate(String text, Locale targetLocale) {
        if (text == null || !text.contains(I18N_PREFIX)) {
            return text;
        }

        Matcher matcher = I18N_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean matchedAny = false;
        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            String code = expr;
            String defaultVal = null;
            int colonIdx = expr.indexOf(':');
            if (colonIdx > 0) {
                code = expr.substring(0, colonIdx).trim();
                defaultVal = expr.substring(colonIdx + 1).trim();
            }

            String message = resolveMessage(code, targetLocale);
            if (message != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(message));
                matchedAny = true;
            } else if (defaultVal != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(defaultVal));
                matchedAny = true;
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        if (!matchedAny) {
            return text;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 依托 MessageSource 解析指定国际化 Key
     *
     * @param code         国际化消息编码
     * @param targetLocale 目标语言环境
     * @return 转译后的多语言消息（未找到时返回 null）
     */
    private String resolveMessage(String code, Locale targetLocale) {
        MessageSource ms = this.messageSource;
        if (ms == null) {
            try {
                ms = SpringUtil.getBean(MessageSource.class);
            } catch (Throwable ignored) {
                // Spring 上下文未初始化或未声明 MessageSource
            }
        }

        if (ms != null) {
            try {
                return ms.getMessage(code, null, targetLocale);
            } catch (NoSuchMessageException ex) {
                log.debug("解析国际化表头 Key 未找到 [code={}, locale={}]: {}", code, targetLocale, ex.getMessage());
                return null;
            } catch (Exception ex) {
                log.debug("解析国际化表头异常 [code={}]: {}", code, ex.getMessage());
                return null;
            }
        }

        return null;
    }

    private Locale resolveLocale() {
        if (this.locale != null) {
            return this.locale;
        }
        try {
            Locale contextLocale = LocaleContextHolder.getLocale();
            if (contextLocale != null) {
                return contextLocale;
            }
        } catch (Throwable ignored) {
        }
        return Locale.getDefault();
    }

    public MessageSource getMessageSource() {
        return messageSource;
    }

    public void setMessageSource(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public Locale getLocale() {
        return locale;
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }
}
