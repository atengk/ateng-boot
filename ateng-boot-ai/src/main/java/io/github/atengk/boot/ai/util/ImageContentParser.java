package io.github.atengk.boot.ai.util;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 视觉多模态图文输入内容解析器
 * 负责将 HTTP/HTTPS 外部图片链接、Data URI 或原始 Base64 编码统一转换为 LangChain4j 标准 ImageContent
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class ImageContentParser {

    private ImageContentParser() {
    }

    /**
     * 将提示词与图片地址列表转换为 UserMessage 消息对象
     *
     * @param prompt    提示词文本
     * @param imageUrls 图片 URL 或 Base64 编码集合
     * @return 包含文本与图片内容的 UserMessage
     */
    public static UserMessage toUserMessage(String prompt, List<String> imageUrls) {
        boolean hasPrompt = StringUtils.hasText(prompt);
        boolean hasImages = imageUrls != null && !imageUrls.isEmpty();

        if (!hasPrompt && !hasImages) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "提示词与图片列表不能同时为空");
        }

        List<Content> contents = new ArrayList<>();
        if (hasPrompt) {
            contents.add(TextContent.from(prompt.trim()));
        }
        if (hasImages) {
            for (String rawImage : imageUrls) {
                if (StringUtils.hasText(rawImage)) {
                    contents.add(parseImage(rawImage.trim()));
                }
            }
        }

        if (contents.isEmpty()) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "有效提问内容与图片不能为空");
        }

        return UserMessage.from(contents);
    }

    /**
     * 解析单个图片字符串为 ImageContent
     *
     * @param rawImage 图片 URL 或 Base64 数据
     * @return ImageContent 实例
     */
    public static ImageContent parseImage(String rawImage) {
        if (!StringUtils.hasText(rawImage)) {
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "图片数据不能为空");
        }

        String trimmed = rawImage.trim();

        // 1. 标准 HTTP/HTTPS 网络图片 URL
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                return ImageContent.from(URI.create(trimmed));
            } catch (Exception e) {
                throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "无效的图片 URL 格式: " + trimmed, e);
            }
        }

        // 2. Data URI 协议格式，例如 "data:image/png;base64,iVBORw0KGgo..."
        if (trimmed.startsWith("data:")) {
            int commaIdx = trimmed.indexOf(',');
            if (commaIdx > 5) {
                String meta = trimmed.substring(5, commaIdx).toLowerCase();
                String base64Data = trimmed.substring(commaIdx + 1).trim();
                String mimeType = "image/jpeg";
                if (meta.contains(";")) {
                    String part = meta.substring(0, meta.indexOf(';')).trim();
                    if (StringUtils.hasText(part)) {
                        mimeType = part;
                    }
                } else if (StringUtils.hasText(meta)) {
                    mimeType = meta.trim();
                }
                return ImageContent.from(base64Data, mimeType);
            }
            throw new AiException(AiResultCode.AI_INVALID_PAYLOAD, "格式非法的 Data URI 图片格式: " + trimmed);
        }

        // 3. 裸 Base64 编码字符串，默认使用 image/jpeg 封装
        return ImageContent.from(trimmed, "image/jpeg");
    }
}
