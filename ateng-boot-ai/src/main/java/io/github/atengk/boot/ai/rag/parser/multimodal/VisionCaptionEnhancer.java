package io.github.atengk.boot.ai.rag.parser.multimodal;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import io.github.atengk.boot.ai.util.ImageContentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 视觉多模态图表摘要增强器 (VisionCaptionEnhancer)
 * <p>
 * 负责调度视觉多模态大模型对文档（Word/PDF）中内嵌的架构图、流程图、统计图表及扫描件页面
 * 进行自动化内容理解与结构化语义提炼，就地生成文字摘要插入文档流中，
 * 并支持联动 {@link ImageStorageUploader} 将原始图表自动持久化至对象存储。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class VisionCaptionEnhancer {

    private static final Logger log = LoggerFactory.getLogger(VisionCaptionEnhancer.class);

    /**
     * 图片对象存储上传器 SPI 接口
     */
    @FunctionalInterface
    public interface ImageStorageUploader {
        /**
         * 上传图片二进制数据并返回公开可访问 URL
         *
         * @param imageBytes 图片字节数组
         * @param filename   图片文件名
         * @return 图片公开访问 URL；若上传失败或忽略则返回 null
         */
        String upload(byte[] imageBytes, String filename);
    }

    private final VisionModelResolver visionModelResolver;
    private final AtengAiRagProperties.MultimodalProperties properties;
    private final ImageStorageUploader imageStorageUploader;
    private final ChatLanguageModel directVisionModel;

    public VisionCaptionEnhancer(VisionModelResolver visionModelResolver,
                                 AtengAiRagProperties.MultimodalProperties properties,
                                 ImageStorageUploader imageStorageUploader) {
        this.visionModelResolver = visionModelResolver;
        this.properties = properties != null ? properties : new AtengAiRagProperties.MultimodalProperties();
        this.imageStorageUploader = imageStorageUploader;
        this.directVisionModel = null;
    }

    public VisionCaptionEnhancer(VisionModelResolver visionModelResolver,
                                 AtengAiRagProperties.MultimodalProperties properties) {
        this(visionModelResolver, properties, (ImageStorageUploader) null);
    }

    public VisionCaptionEnhancer(ChatLanguageModel directVisionModel,
                                 AtengAiRagProperties.MultimodalProperties properties) {
        this(directVisionModel, properties, null);
    }

    public VisionCaptionEnhancer(ChatLanguageModel directVisionModel,
                                 AtengAiRagProperties.MultimodalProperties properties,
                                 ImageStorageUploader imageStorageUploader) {
        this.visionModelResolver = null;
        this.properties = properties != null ? properties : new AtengAiRagProperties.MultimodalProperties();
        this.imageStorageUploader = imageStorageUploader;
        this.directVisionModel = directVisionModel;
    }

    public AtengAiRagProperties.MultimodalProperties getProperties() {
        return properties;
    }

    /**
     * 判断当前是否启用了视觉摘要增强
     *
     * @return true 表示已启用
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 对内嵌图表生成语义摘要描述
     *
     * @param imageBytes 图片二进制字节流 (非空)
     * @param filename   图片文件名 (可选)
     * @param mimeType   MIME 类型 (例如 image/png, image/jpeg)
     * @return 格式化后的 Markdown 图表摘要块或平滑降级占位符
     */
    public String enhanceImage(byte[] imageBytes, String filename, String mimeType) {
        String safeFilename = StringUtils.hasText(filename) ? filename.trim() : "image_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        if (imageBytes == null || imageBytes.length == 0) {
            return formatPlaceholder(safeFilename, null);
        }

        // 1. 若配置并具备 ImageStorageUploader，上传原图至对象存储
        String imageUrl = null;
        if (properties.isStorageUpload() && imageStorageUploader != null) {
            try {
                imageUrl = imageStorageUploader.upload(imageBytes, safeFilename);
            } catch (Exception e) {
                log.warn("上传文档内嵌图表至对象存储失败，忽略并继续视觉解析 [file={}, error={}]", safeFilename, e.getMessage());
            }
        }

        // 2. 检查多模态视觉模型是否就绪
        ChatLanguageModel visionModel = resolveActiveVisionModel();
        if (visionModel == null) {
            return formatPlaceholder(safeFilename, imageUrl);
        }

        // 3. 构造 Base64 Data URI 并调用视觉大模型
        try {
            String safeMime = StringUtils.hasText(mimeType) ? mimeType.trim() : "image/png";
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:" + safeMime + ";base64," + base64;
            String prompt = StringUtils.hasText(properties.getPrompt())
                    ? properties.getPrompt()
                    : "请对以下架构图、流程图或数据图表进行精确、结构化的语义摘要描述：";

            UserMessage userMessage = ImageContentParser.toUserMessage(prompt, List.of(dataUri));
            Response<AiMessage> response = visionModel.generate(userMessage);
            String caption = (response != null && response.content() != null && response.content().text() != null)
                    ? response.content().text().trim()
                    : "";

            if (!StringUtils.hasText(caption)) {
                return formatPlaceholder(safeFilename, imageUrl);
            }

            return formatCaption(safeFilename, caption, imageUrl);
        } catch (Exception e) {
            // 4. 防御性降级：主动拦截 400 Bad Request 或网络异常，杜绝污染并中断文档解析主管道
            log.warn("视觉大模型调用失败，自动平滑降级为图表占位符 [file={}, error={}]", safeFilename, e.getMessage());
            return formatPlaceholder(safeFilename, imageUrl);
        }
    }

    /**
     * 对扫描件整页图片执行视觉 OCR 与文字排版提炼
     *
     * @param pageImageBytes 页面渲染图像字节
     * @param pageNumber     当前页码 (1-based)
     * @return 提取出的纯净文字内容；若模型未就绪或失败则返回友好占位标记
     */
    public String enhanceScannedPage(byte[] pageImageBytes, int pageNumber) {
        if (pageImageBytes == null || pageImageBytes.length == 0) {
            return String.format("> 📄 [第 %d 页为纯图片扫描件，无有效图像数据]", pageNumber);
        }

        ChatLanguageModel visionModel = resolveActiveVisionModel();
        if (visionModel == null) {
            return String.format("> 📄 [第 %d 页为纯图片扫描件，当前未配置视觉模型进行 OCR]", pageNumber);
        }

        try {
            String base64 = Base64.getEncoder().encodeToString(pageImageBytes);
            String dataUri = "data:image/png;base64," + base64;
            String ocrPrompt = "请识别并提取此扫描件页面中的全部文字与表格内容，忠实还原排版逻辑与章节段落，直接输出纯净文本：";

            UserMessage userMessage = ImageContentParser.toUserMessage(ocrPrompt, List.of(dataUri));
            Response<AiMessage> response = visionModel.generate(userMessage);
            String text = (response != null && response.content() != null && response.content().text() != null)
                    ? response.content().text().trim()
                    : "";

            if (StringUtils.hasText(text)) {
                return text;
            }
            return String.format("> 📄 [第 %d 页扫描件视觉 OCR 识别无文本输出]", pageNumber);
        } catch (Exception e) {
            log.warn("扫描件页面视觉 OCR 失败，自动平滑降级 [page={}, error={}]", pageNumber, e.getMessage());
            return String.format("> 📄 [第 %d 页扫描件视觉 OCR 识别异常: %s]", pageNumber, e.getMessage());
        }
    }

    private ChatLanguageModel resolveActiveVisionModel() {
        if (directVisionModel != null) {
            return directVisionModel;
        }
        if (visionModelResolver != null) {
            return visionModelResolver.resolveVisionModel();
        }
        return null;
    }

    private String formatCaption(String filename, String caption, String imageUrl) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(imageUrl)) {
            sb.append("> 🖼️ ![").append(filename).append("](").append(imageUrl).append(")\n");
        }
        sb.append("> 💡 [图表/架构图语义描述 - ").append(filename).append("]: ").append(caption);
        return sb.toString();
    }

    private String formatPlaceholder(String filename, String imageUrl) {
        if (StringUtils.hasText(imageUrl)) {
            return "> 🖼️ ![" + filename + "](" + imageUrl + ")";
        }
        return "> 🖼️ [内嵌图表: " + filename + "]";
    }
}
