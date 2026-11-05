package io.github.atengk.boot.ai.rag.parser;

import org.springframework.core.Ordered;

import java.io.InputStream;

/**
 * 多源异构文档解析器顶级 SPI 接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
public interface DocumentParser extends Ordered {

    /**
     * 判断当前解析器是否支持给定的文档格式或文件名
     *
     * @param format   文档格式枚举 (非空)
     * @param filename 源文件名 (可选，可能为 null)
     * @return true 表示当前解析器支持该文档解析
     */
    boolean supports(DocumentFormat format, String filename);

    /**
     * 解析文档输入流并提取结构化文本内容与元数据
     *
     * @param in      文档输入流 (保证非 null)
     * @param options 解析配置选项 (保证非 null)
     * @return 结构化文档结果对象，绝不返回 null
     */
    ParsedDocument parse(InputStream in, ParseOptions options);

    /**
     * 使用默认配置选项解析文档输入流
     *
     * @param in 文档输入流 (保证非 null)
     * @return 结构化文档结果对象
     */
    default ParsedDocument parse(InputStream in) {
        return parse(in, ParseOptions.defaults());
    }

    /**
     * 解析器排序优先级 (默认优先级为 0，数值越小优先级越高)
     *
     * @return 优先级数值
     */
    @Override
    default int getOrder() {
        return 0;
    }
}
