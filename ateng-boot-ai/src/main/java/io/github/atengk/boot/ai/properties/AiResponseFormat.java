package io.github.atengk.boot.ai.properties;

/**
 * AI 大语言模型输出响应格式规范 (Response Format)
 *
 * @author Ateng
 * @since 2026-11-05
 */
public enum AiResponseFormat {

    /**
     * 默认自由非结构化文本输出
     */
    TEXT,

    /**
     * 标准 JSON 对象输出 (兼容 OpenAI json_object 模式)
     */
    JSON_OBJECT,

    /**
     * 严格 JSON Schema 语法约束解码 (兼容 OpenAI json_schema 模式)
     */
    JSON_SCHEMA
}
