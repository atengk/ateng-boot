package io.github.atengk.boot.sample.ai.vo;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * 多源异构文档解析响应载体
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Value
@Builder
public class AiRagParseVo {

    /**
     * 文档文件名
     */
    String filename;

    /**
     * 命中并执行解析的解析器类名
     */
    String parserClass;

    /**
     * 抽取文本总字符数
     */
    int characterCount;

    /**
     * 解析抽取文本预览 (前 1000 字符)
     */
    String textPreview;

    /**
     * 结构化提取的元数据字典 (如 sheetName, pageCount 等)
     */
    Map<String, Object> metadata;
}
