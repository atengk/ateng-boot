package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.rag.parser.DocumentFormat;
import io.github.atengk.boot.ai.rag.parser.DocumentParser;
import io.github.atengk.boot.ai.rag.parser.DocumentParserManager;
import io.github.atengk.boot.ai.rag.parser.ParseOptions;
import io.github.atengk.boot.ai.rag.parser.ParsedDocument;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRagParseRequest;
import io.github.atengk.boot.sample.ai.vo.AiRagParseVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

/**
 * AI RAG 多源异构文档解析与结构化抽取接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@RestController
@RequestMapping("/sample/ai/rag")
@RequiredArgsConstructor
public class AiRagParserController {

    private final DocumentParserManager parserManager;

    /**
     * 多源异构文档解析体验 (支持 TXT, MD, HTML, Word, PDF, Excel 等)
     *
     * @param request 文档解析入参
     * @return 抽取出的纯文本与元数据详情
     */
    @PostMapping("/parse")
    public Result<AiRagParseVo> parseDocument(@Valid @RequestBody AiRagParseRequest request) {
        String filename = request.getFilename();
        byte[] bytes;
        try {
            // 尝试 Base64 解码；若不是合规 Base64 则直接视为纯文本 utf-8
            bytes = Base64.getDecoder().decode(request.getContent());
        } catch (IllegalArgumentException e) {
            bytes = request.getContent().getBytes(StandardCharsets.UTF_8);
        }

        ParseOptions options = ParseOptions.builder()
                .filename(filename)
                .build();

        ParsedDocument parsed;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            parsed = parserManager.parse(in, options);
        } catch (Exception e) {
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }

        DocumentFormat format = DocumentFormat.fromFilename(filename);
        DocumentParser parser = null;
        try {
            parser = parserManager.findParser(format, filename);
        } catch (Exception ignored) {
        }
        String parserClass = parser != null ? parser.getClass().getSimpleName() : "Unknown";

        String text = parsed.getText() != null ? parsed.getText() : "";
        String textPreview = text.length() > 1000 ? text.substring(0, 1000) + "..." : text;
        Map<String, Object> metadata = parsed.getMetadata() != null ? parsed.getMetadata() : Collections.emptyMap();

        AiRagParseVo vo = AiRagParseVo.builder()
                .filename(filename)
                .parserClass(parserClass)
                .characterCount(text.length())
                .textPreview(textPreview)
                .metadata(metadata)
                .build();

        return Result.success(vo);
    }
}
