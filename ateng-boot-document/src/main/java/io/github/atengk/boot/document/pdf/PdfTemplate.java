package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.document.enums.CrossPageSealEdge;
import io.github.atengk.boot.document.enums.KeywordStampOccurrence;
import io.github.atengk.boot.document.enums.PageNumberPosition;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import io.github.atengk.boot.document.exception.DocumentException;
import io.github.atengk.boot.document.exception.DocumentFontException;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.pdf.model.CrossPageSealOptions;
import io.github.atengk.boot.document.pdf.model.FormOptions;
import io.github.atengk.boot.document.pdf.model.ImageStampOptions;
import io.github.atengk.boot.document.pdf.model.ImageToPdfOptions;
import io.github.atengk.boot.document.pdf.model.KeywordStampOptions;
import io.github.atengk.boot.document.pdf.model.PageImageConsumer;
import io.github.atengk.boot.document.pdf.model.PageNumberOptions;
import io.github.atengk.boot.document.pdf.model.PdfFormFieldDescriptor;
import io.github.atengk.boot.document.pdf.model.PdfMetadata;
import io.github.atengk.boot.document.pdf.model.PdfMetadataUpdate;
import io.github.atengk.boot.document.pdf.model.PdfRenderOptions;
import io.github.atengk.boot.document.pdf.model.RedactArea;
import io.github.atengk.boot.document.pdf.model.RedactOptions;
import io.github.atengk.boot.document.pdf.model.SecurityOptions;
import io.github.atengk.boot.document.pdf.model.TextPositionMatch;
import io.github.atengk.boot.document.pdf.model.WatermarkOptions;
import io.github.atengk.boot.document.properties.DocumentPdfProperties;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.properties.DocumentWatermarkProperties;
import io.github.atengk.boot.document.spi.DefaultDocumentConverter;
import io.github.atengk.boot.document.spi.DocumentConverter;
import io.github.atengk.boot.document.util.DocumentWebHelper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.WritableResource;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 企业级 PDF 文档治理统一操作门面
 * <p>
 * 基于 Apache PDFBox 3.x 构建，提供防泄密平铺半透明暗水印、动态操作人宏变量解析脱敏、
 * 指定页绝对物理坐标盖章签名，并在底层联动内存阈值防 OOM 与确定性临时资源清理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class PdfTemplate {

    private static final Logger log = LoggerFactory.getLogger(PdfTemplate.class);
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DocumentProperties documentProperties;
    private final DocumentFontResolver fontResolver;
    private final DocumentConverter documentConverter;

    /**
     * 全参构造方法（注入转换器 SPI 支持 fromDocx / fromHtml 转档桥接）
     *
     * @param documentProperties 文档治理根配置属性
     * @param fontResolver       中文字体解析器
     * @param documentConverter  文档跨格式转换器 SPI（为空时回退 {@link DefaultDocumentConverter}）
     */
    public PdfTemplate(DocumentProperties documentProperties, DocumentFontResolver fontResolver,
                       DocumentConverter documentConverter) {
        this.documentProperties = documentProperties != null ? documentProperties : new DocumentProperties();
        this.fontResolver = fontResolver != null ? fontResolver : new DocumentFontResolver(this.documentProperties.getPdf());
        this.documentConverter = documentConverter != null ? documentConverter : new DefaultDocumentConverter();
    }

    /**
     * 双参构造方法
     *
     * @param documentProperties 文档治理根配置属性
     * @param fontResolver       中文字体解析器
     */
    public PdfTemplate(DocumentProperties documentProperties, DocumentFontResolver fontResolver) {
        this(documentProperties, fontResolver, null);
    }

    /**
     * 单参配置构造方法
     *
     * @param documentProperties 文档治理根配置属性
     */
    public PdfTemplate(DocumentProperties documentProperties) {
        this(documentProperties, null, null);
    }

    /**
     * 缺省无参构造方法
     */
    public PdfTemplate() {
        this(new DocumentProperties(), null, null);
    }

    /**
     * 静态工厂构造方法
     *
     * @param documentProperties 文档治理根配置属性
     * @param fontResolver       中文字体解析器
     * @return PdfTemplate 实例
     */
    public static PdfTemplate of(DocumentProperties documentProperties, DocumentFontResolver fontResolver) {
        return new PdfTemplate(documentProperties, fontResolver);
    }

    /**
     * 静态工厂缺省构造方法
     *
     * @return PdfTemplate 实例
     */
    public static PdfTemplate of() {
        return new PdfTemplate();
    }

    // ==========================================
    // 1. 防泄密平铺暗水印能力 (Watermark)
    // ==========================================

    /**
     * 为 PDF 文档添加防泄密半透明平铺暗水印（支持文本与图片双模平铺、多行文本换行、页面过滤及旋转页面校准）
     *
     * @param source  PDF 输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @param target  目标输出（支持 File, Path, OutputStream, WritableResource, String）
     * @param options 水印高级选项（若为 null 则使用全局默认配置）
     * @throws PdfOperationException 水印添加或文档读写失败时抛出
     */
    public void addWatermark(Object source, Object target, WatermarkOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        WatermarkOptions opts = options != null ? options : createDefaultWatermarkOptions();
        boolean imageMode = opts.getImage() != null;
        String parsedText = null;
        if (!imageMode) {
            parsedText = WatermarkMacroParser.parse(opts.getText(), opts.getVariables());
            if (parsedText == null || parsedText.trim().isEmpty()) {
                log.warn("水印文本解析结果为空，跳过水印绘制");
                copySourceToTarget(source, target);
                return;
            }
        } else {
            if (opts.getImageWidth() <= 0.0f || opts.getImageHeight() <= 0.0f) {
                throw new IllegalArgumentException(String.format(
                        "图片水印宽高必须大于 0 (imageWidth=%.1f, imageHeight=%.1f)",
                        opts.getImageWidth(), opts.getImageHeight()));
            }
        }

        try (LoadedPdfDocument loadedPdf = loadDocument(source, opts.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, opts.getPassword(), "施加水印");

            if (imageMode) {
                byte[] imageBytes = resolveImageBytes(opts.getImage());
                PDImageXObject watermarkImage = PDImageXObject.createFromByteArray(doc, imageBytes, "watermark");
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    if (shouldApplyWatermark(i, opts)) {
                        drawPageImageWatermark(doc, doc.getPage(i), watermarkImage, opts);
                    }
                }
            } else {
                PDFont font = resolveFontForText(doc, null, parsedText, true);
                Color parsedColor = parseColor(opts.getColor());
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    if (shouldApplyWatermark(i, opts)) {
                        drawPageWatermark(doc, doc.getPage(i), parsedText, font, opts, parsedColor);
                    }
                }
            }

            saveDocument(doc, target);
        } catch (DocumentException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "为 PDF 添加水印失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为 PDF 文档添加图片/Logo 平铺暗水印
     *
     * @param source PDF 输入源
     * @param target 目标输出
     * @param image  水印图片输入源
     */
    public void addImageWatermark(Object source, Object target, Object image) {
        addImageWatermark(source, target, image, null);
    }

    /**
     * 为 PDF 文档添加图片/Logo 平铺暗水印（使用自定义选项）
     *
     * @param source  PDF 输入源
     * @param target  目标输出
     * @param image   水印图片输入源
     * @param options 水印选项（可控制透明度、宽高、间距、角度与页码过滤）
     */
    public void addImageWatermark(Object source, Object target, Object image, WatermarkOptions options) {
        Objects.requireNonNull(image, "水印图片输入源 image 不能为空");
        WatermarkOptions effective = options != null ? options.toBuilder().image(image).build() : WatermarkOptions.ofImage(image);
        addWatermark(source, target, effective);
    }

    /**
     * 为 PDF 文档添加图片/Logo 平铺暗水印并导出为字节数组
     *
     * @param source PDF 输入源
     * @param image  水印图片输入源
     * @return 处理后的 PDF 二进制数据
     */
    public byte[] addImageWatermarkToBytes(Object source, Object image) {
        return addImageWatermarkToBytes(source, image, null);
    }

    /**
     * 为 PDF 文档添加图片/Logo 平铺暗水印并导出为字节数组
     *
     * @param source  PDF 输入源
     * @param image   水印图片输入源
     * @param options 水印选项
     * @return 处理后的 PDF 二进制数据
     */
    public byte[] addImageWatermarkToBytes(Object source, Object image, WatermarkOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        addImageWatermark(source, baos, image, options);
        return baos.toByteArray();
    }

    /**
     * 为 PDF 文档添加指定文本的平铺暗水印
     *
     * @param source        PDF 输入源
     * @param target        目标输出
     * @param watermarkText 水印文本
     */
    public void addWatermark(Object source, Object target, String watermarkText) {
        WatermarkOptions options = createDefaultWatermarkOptions();
        options.setText(watermarkText);
        addWatermark(source, target, options);
    }

    /**
     * 为 PDF 文档添加默认配置的平铺暗水印
     *
     * @param source PDF 输入源
     * @param target 目标输出
     */
    public void addWatermark(Object source, Object target) {
        addWatermark(source, target, createDefaultWatermarkOptions());
    }

    /**
     * 为 PDF 文档添加平铺暗水印并导出为字节数组
     *
     * @param source  PDF 输入源
     * @param options 水印高级选项
     * @return 处理后的 PDF 二进制数据
     */
    public byte[] addWatermarkToBytes(Object source, WatermarkOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        addWatermark(source, baos, options);
        return baos.toByteArray();
    }

    /**
     * 为 PDF 文档添加指定文本的平铺暗水印并导出为字节数组
     *
     * @param source        PDF 输入源
     * @param watermarkText 水印文本
     * @return 处理后的 PDF 二进制数据
     */
    public byte[] addWatermarkToBytes(Object source, String watermarkText) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        addWatermark(source, baos, watermarkText);
        return baos.toByteArray();
    }

    /**
     * 为 PDF 文档添加默认平铺暗水印并导出为字节数组
     *
     * @param source PDF 输入源
     * @return 处理后的 PDF 二进制数据
     */
    public byte[] addWatermarkToBytes(Object source) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        addWatermark(source, baos);
        return baos.toByteArray();
    }

    private boolean shouldApplyWatermark(int zeroBasedPageIndex, WatermarkOptions opts) {
        if (zeroBasedPageIndex < Math.max(0, opts.getSkipPages())) {
            return false;
        }
        if (opts.getTargetPages() != null && !opts.getTargetPages().isEmpty()) {
            return opts.getTargetPages().contains(zeroBasedPageIndex + 1);
        }
        return true;
    }

    @FunctionalInterface
    private interface TiledWatermarkCellDrawer {
        void draw(PDPageContentStream cs) throws IOException;
    }

    private void drawTiledWatermark(PDDocument doc, PDPage page, WatermarkOptions opts, Color color,
                                    TiledWatermarkCellDrawer cellDrawer) throws IOException {
        PDRectangle visibleBox = PdfRedactionSupport.resolveVisibleBox(page);
        int rotation = PdfRedactionSupport.normalizeRotation(page);
        PdfRedactionSupport.VisualSize visSize = PdfRedactionSupport.resolveVisualSize(visibleBox, rotation);
        float width = visSize.width();
        float height = visSize.height();

        PDPageContentStream.AppendMode mode = opts.getLayer() == WatermarkLayer.UNDERLAY
                ? PDPageContentStream.AppendMode.PREPEND
                : PDPageContentStream.AppendMode.APPEND;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page, mode, true, true)) {
            cs.saveGraphicsState();
            PdfRedactionSupport.applyVisualCoordinateTransform(cs, visibleBox, rotation);

            // 设置透明度与可选前景色
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(opts.getOpacity());
            gs.setStrokingAlphaConstant(opts.getOpacity());
            cs.setGraphicsStateParameters(gs);
            if (color != null) {
                cs.setNonStrokingColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f);
            }

            // 基于可见旋转区域外扩平铺，确保任何角度不留边缘空白
            float colSpacing = Math.max(50.0f, opts.getColSpacing());
            float rowSpacing = Math.max(30.0f, opts.getRowSpacing());
            float minX = -width * 0.5f;
            float maxX = width * 1.5f;
            float minY = -height * 0.5f;
            float maxY = height * 1.5f;
            double angleRad = Math.toRadians(opts.getRotation());

            for (float x = minX; x <= maxX; x += colSpacing) {
                for (float y = minY; y <= maxY; y += rowSpacing) {
                    cs.saveGraphicsState();
                    cs.transform(Matrix.getRotateInstance(angleRad, x, y));
                    cellDrawer.draw(cs);
                    cs.restoreGraphicsState();
                }
            }
            cs.restoreGraphicsState();
        }
    }

    private void drawPageWatermark(PDDocument doc, PDPage page, String text, PDFont font,
                                   WatermarkOptions opts, Color color) throws IOException {
        String[] rawLines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        float fontSize = opts.getFontSize();
        float lineHeight = fontSize * 1.35f;
        float[] lineWidths = new float[rawLines.length];
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            lineWidths[i] = line.isEmpty() ? 0.0f : font.getStringWidth(line) / 1000.0f * fontSize;
        }

        drawTiledWatermark(doc, page, opts, color, cs -> {
            for (int idx = 0; idx < rawLines.length; idx++) {
                String line = rawLines[idx];
                if (line.isEmpty()) {
                    continue;
                }
                float offsetX = -lineWidths[idx] / 2.0f;
                float offsetY = -idx * lineHeight;
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(offsetX, offsetY);
                cs.showText(line);
                cs.endText();
            }
        });
    }

    private void drawPageImageWatermark(PDDocument doc, PDPage page, PDImageXObject image,
                                        WatermarkOptions opts) throws IOException {
        float imgW = opts.getImageWidth();
        float imgH = opts.getImageHeight();
        drawTiledWatermark(doc, page, opts, null,
                cs -> cs.drawImage(image, -imgW / 2.0f, -imgH / 2.0f, imgW, imgH));
    }

    // ==========================================
    // 2. 电子公章与手写签名绝对物理坐标盖章 (Stamp)
    // ==========================================

    /**
     * 在 PDF 指定页面的绝对物理坐标压印电子公章或手写签名
     *
     * @param source  PDF 输入源
     * @param target  目标输出
     * @param options 盖章选项（包含图片、页码、XY 坐标、宽高与透明度）
     * @throws PdfOperationException 盖章操作失败或页面越界时抛出
     */
    public void addImageStamp(Object source, Object target, ImageStampOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        Objects.requireNonNull(options, "盖章选项 options 不能为空");
        Objects.requireNonNull(options.getImage(), "印章图片输入源 image 不能为空");

        byte[] imageBytes = resolveImageBytes(options.getImage());

        try (LoadedPdfDocument loadedPdf = loadDocument(source, options.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, options.getPassword(), "压印电子公章");

            int totalPages = doc.getNumberOfPages();
            if (totalPages <= 0) {
                throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "PDF 文档页数为空");
            }

            int targetPageIndex = normalizePageIndex(options.getPageIndex(), totalPages);

            PDPage page = doc.getPage(targetPageIndex);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, imageBytes, "stamp");

            float width = options.getWidth() > 0 ? options.getWidth() : pdImage.getWidth();
            float height = options.getHeight() > 0 ? options.getHeight() : pdImage.getHeight();

            PDPageContentStream.AppendMode mode = options.getLayer() == WatermarkLayer.UNDERLAY
                    ? PDPageContentStream.AppendMode.PREPEND
                    : PDPageContentStream.AppendMode.APPEND;

            drawStampImageOnPage(doc, page, pdImage, options.getX(), options.getY(), width, height, options.getOpacity(), mode);
            saveDocument(doc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "在 PDF 压印电子公章失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在 PDF 指定页面的绝对物理坐标压印公章并导出为字节数组
     *
     * @param source  PDF 输入源
     * @param options 盖章选项
     * @return 盖章后的 PDF 二进制数据
     */
    public byte[] addImageStampToBytes(Object source, ImageStampOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        addImageStamp(source, baos, options);
        return baos.toByteArray();
    }

    /**
     * 便捷方法：在指定页码与物理坐标压印印章
     *
     * @param source    PDF 输入源
     * @param target    目标输出
     * @param image     印章图片输入源
     * @param pageIndex 页码索引（-1 为最后一页）
     * @param x         X 坐标 (pt)
     * @param y         Y 坐标 (pt)
     * @param width     宽度 (pt)
     * @param height    高度 (pt)
     */
    public void addImageStamp(Object source, Object target, Object image, int pageIndex, float x, float y, float width, float height) {
        ImageStampOptions options = ImageStampOptions.of(image, pageIndex, x, y, width, height);
        addImageStamp(source, target, options);
    }

    /**
     * 便捷方法：在指定页码与物理坐标压印印章并导出为字节数组
     *
     * @param source    PDF 输入源
     * @param image     印章图片输入源
     * @param pageIndex 页码索引（-1 为最后一页）
     * @param x         X 坐标 (pt)
     * @param y         Y 坐标 (pt)
     * @param width     宽度 (pt)
     * @param height    高度 (pt)
     * @return 盖章后的 PDF 二进制数据
     */
    public byte[] addImageStampToBytes(Object source, Object image, int pageIndex, float x, float y, float width, float height) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        addImageStamp(source, baos, image, pageIndex, x, y, width, height);
        return baos.toByteArray();
    }

    /**
     * 为多页 PDF 文档施加跨页骑缝章 (Cross-Page Seal)
     * <p>
     * 将电子印章沿垂直或水平方向按目标页数进行无损等分切片（保留完整 Alpha 透明通道），
     * 并按物理边缘（默认右侧边缘居中贴合）自动压印于各页边框，支持跳过封面、跳过封底或指定自定义页面集合。
     * </p>
     *
     * @param source  PDF 输入源（支持 File, Path, InputStream, byte[], Resource, String 路径）
     * @param target  目标输出（支持 File, Path, OutputStream, WritableResource）
     * @param options 骑缝章配置选项
     * @throws PdfOperationException 操作失败或文档已加密时抛出
     */
    public void stampCrossPageSeal(Object source, Object target, CrossPageSealOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        Objects.requireNonNull(options, "骑缝章选项 options 不能为空");
        Objects.requireNonNull(options.getSealImage(), "印章图片输入源 sealImage 不能为空");
        CrossPageSealOptions.validateDimensions(options.getWidth(), options.getHeight());

        byte[] sealBytes = resolveImageBytes(options.getSealImage());
        BufferedImage originalSealImage;
        try {
            originalSealImage = ImageIO.read(new ByteArrayInputStream(sealBytes));
        } catch (IOException e) {
            throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片失败: " + e.getMessage(), e);
        }
        if (originalSealImage == null) {
            throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "无法解析印章图片内容，请确认图片格式为合法图片 (如 PNG/JPG)");
        }

        try (LoadedPdfDocument loadedPdf = loadDocument(source, options.getPassword())) {
            if (originalSealImage.getWidth() <= 0 || originalSealImage.getHeight() <= 0) {
                throw new IllegalArgumentException("印章图像像素尺寸必须大于 0");
            }
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, options.getPassword(), "施加跨页骑缝章");

            int totalPages = doc.getNumberOfPages();
            if (totalPages <= 0) {
                throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "PDF 文档页数为空");
            }

            List<Integer> targetPageIndices = resolveTargetPages(options, totalPages);
            int pageCount = targetPageIndices.size();
            if (pageCount <= 0) {
                throw new IllegalArgumentException("计算后的有效盖章页数为空，无法施加跨页骑缝章");
            }

            int originalWidth = originalSealImage.getWidth();
            int originalHeight = originalSealImage.getHeight();
            CrossPageSealEdge edge = options.getEdge() != null ? options.getEdge() : CrossPageSealEdge.RIGHT;

            for (int sliceIndex = 0; sliceIndex < pageCount; sliceIndex++) {
                int pageIndex = targetPageIndices.get(sliceIndex);
                PDPage page = doc.getPage(pageIndex);
                PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                float pageWidth = box.getWidth();
                float pageHeight = box.getHeight();
                float originX = box.getLowerLeftX();
                float originY = box.getLowerLeftY();

                BufferedImage slice;
                float slicePdfWidth;
                float slicePdfHeight;
                float x;
                float y;

                if (edge == CrossPageSealEdge.TOP || edge == CrossPageSealEdge.BOTTOM) {
                    int startY = (int) Math.round((double) sliceIndex * originalHeight / pageCount);
                    int endY = (int) Math.round((double) (sliceIndex + 1) * originalHeight / pageCount);
                    slice = createSealSlice(originalSealImage, 0, startY, originalWidth, endY);
                    int stripHeight = slice.getHeight();

                    slicePdfWidth = options.getWidth();
                    slicePdfHeight = options.getHeight() * ((float) stripHeight / originalHeight);

                    x = originX + (pageWidth - slicePdfWidth) / 2.0f + options.getOffset();
                    if (edge == CrossPageSealEdge.BOTTOM) {
                        y = originY + options.getMargin();
                    } else {
                        y = originY + pageHeight - slicePdfHeight - options.getMargin();
                    }
                } else {
                    int startX = (int) Math.round((double) sliceIndex * originalWidth / pageCount);
                    int endX = (int) Math.round((double) (sliceIndex + 1) * originalWidth / pageCount);
                    slice = createSealSlice(originalSealImage, startX, 0, endX, originalHeight);
                    int stripWidth = slice.getWidth();

                    slicePdfWidth = options.getWidth() * ((float) stripWidth / originalWidth);
                    slicePdfHeight = options.getHeight();

                    y = originY + (pageHeight - slicePdfHeight) / 2.0f + options.getOffset();
                    if (edge == CrossPageSealEdge.LEFT) {
                        x = originX + options.getMargin();
                    } else {
                        x = originX + pageWidth - slicePdfWidth - options.getMargin();
                    }
                }

                byte[] sliceBytes;
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    ImageIO.write(slice, "PNG", baos);
                    sliceBytes = baos.toByteArray();
                } finally {
                    slice.flush();
                }

                PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, sliceBytes, "seal-slice-" + sliceIndex);

                PDPageContentStream.AppendMode mode = options.getLayer() == WatermarkLayer.UNDERLAY
                        ? PDPageContentStream.AppendMode.PREPEND
                        : PDPageContentStream.AppendMode.APPEND;

                try (PDPageContentStream cs = new PDPageContentStream(doc, page, mode, true, true)) {
                    if (options.getOpacity() < 1.0f) {
                        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                        float alpha = Math.max(0.0f, Math.min(1.0f, options.getOpacity()));
                        gs.setNonStrokingAlphaConstant(alpha);
                        cs.setGraphicsStateParameters(gs);
                    }
                    cs.drawImage(pdImage, x, y, slicePdfWidth, slicePdfHeight);
                }
            }

            saveDocument(doc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "施加 PDF 跨页骑缝章失败: " + e.getMessage(), e);
        } finally {
            originalSealImage.flush();
        }
    }

    private static BufferedImage createSealSlice(BufferedImage sourceImage, int srcX1, int srcY1, int srcX2, int srcY2) {
        int targetWidth = Math.max(1, srcX2 - srcX1);
        int targetHeight = Math.max(1, srcY2 - srcY1);
        BufferedImage slice = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = slice.createGraphics();
        try {
            g2d.setComposite(AlphaComposite.Src);
            g2d.drawImage(sourceImage, 0, 0, targetWidth, targetHeight, srcX1, srcY1, srcX2, srcY2, null);
        } finally {
            g2d.dispose();
        }
        return slice;
    }

    /**
     * 为多页 PDF 文档施加跨页骑缝章（指定印章源与选项）
     *
     * @param source    PDF 输入源
     * @param target    目标输出
     * @param sealImage 印章图片输入源
     * @param options   骑缝章配置选项
     */
    public void stampCrossPageSeal(Object source, Object target, Object sealImage, CrossPageSealOptions options) {
        CrossPageSealOptions opt;
        if (options != null) {
            opt = options.toBuilder().sealImage(sealImage != null ? sealImage : options.getSealImage()).build();
        } else {
            opt = CrossPageSealOptions.of(sealImage);
        }
        stampCrossPageSeal(source, target, opt);
    }

    /**
     * 为多页 PDF 文档施加跨页骑缝章（使用默认选项：120pt * 120pt，右侧居中贴边）
     *
     * @param source    PDF 输入源
     * @param target    目标输出
     * @param sealImage 印章图片输入源
     */
    public void stampCrossPageSeal(Object source, Object target, Object sealImage) {
        stampCrossPageSeal(source, target, CrossPageSealOptions.of(sealImage));
    }

    /**
     * 为多页 PDF 文档施加跨页骑缝章并返回二进制字节数组
     *
     * @param source  PDF 输入源
     * @param options 骑缝章配置选项
     * @return 施加骑缝章后的 PDF 二进制数据
     */
    public byte[] stampCrossPageSealToBytes(Object source, CrossPageSealOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stampCrossPageSeal(source, baos, options);
        return baos.toByteArray();
    }

    /**
     * 为多页 PDF 文档施加跨页骑缝章并返回二进制字节数组（指定印章源与选项）
     *
     * @param source    PDF 输入源
     * @param sealImage 印章图片输入源
     * @param options   骑缝章配置选项
     * @return 施加骑缝章后的 PDF 二进制数据
     */
    public byte[] stampCrossPageSealToBytes(Object source, Object sealImage, CrossPageSealOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stampCrossPageSeal(source, baos, sealImage, options);
        return baos.toByteArray();
    }

    /**
     * 为多页 PDF 文档施加跨页骑缝章并返回二进制字节数组（使用默认选项：120pt * 120pt，右侧居中贴边）
     *
     * @param source    PDF 输入源
     * @param sealImage 印章图片输入源
     * @return 施加骑缝章后的 PDF 二进制数据
     */
    public byte[] stampCrossPageSealToBytes(Object source, Object sealImage) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stampCrossPageSeal(source, baos, sealImage);
        return baos.toByteArray();
    }

    private List<Integer> resolveTargetPages(CrossPageSealOptions options, int totalPages) {
        if (options.getTargetPages() != null && !options.getTargetPages().isEmpty()) {
            List<Integer> resolved = new ArrayList<>();
            for (Integer p : options.getTargetPages()) {
                if (p == null) {
                    continue;
                }
                int idx = p < 0 ? totalPages + p : p;
                if (idx < 0 || idx >= totalPages) {
                    throw new IllegalArgumentException(String.format("指定的骑缝章页面索引超出范围 (总页数: %d, 请求页码: %d)", totalPages, p));
                }
                resolved.add(idx);
            }
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException("自定义目标页面列表有效页数为空");
            }
            return resolved.stream().distinct().sorted().toList();
        }

        int start = options.isSkipCover() ? 1 : 0;
        int end = options.isSkipBackCover() ? totalPages - 2 : totalPages - 1;

        if (start > end) {
            throw new IllegalArgumentException(String.format(
                    "计算后的有效盖章页数为空，请检查总页数与跳过首页/末页配置 (总页数: %d, skipCover: %s, skipBackCover: %s)",
                    totalPages, options.isSkipCover(), options.isSkipBackCover()));
        }

        List<Integer> pages = new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            pages.add(i);
        }
        return pages;
    }

    // ==========================================
    // 2B. 基于关键字文本定位与自动盖章 (Keyword Anchor Stamp)
    // ==========================================

    /**
     * 在 PDF 文档中检索指定关键字的物理坐标位置列表（统一换算为 PDF 左下角坐标系）
     *
     * @param source  PDF 输入源
     * @param keyword 待定位关键字文本
     * @return 匹配命中的位置清单（无匹配返回空列表）
     */
    public List<TextPositionMatch> findTextPositions(Object source, String keyword) {
        return findTextPositions(source, keyword, null);
    }

    /**
     * 在 PDF 文档中检索指定关键字的物理坐标位置列表（支持密码解密与左下角坐标系换算）
     *
     * @param source   PDF 输入源
     * @param keyword  待定位关键字文本
     * @param password 打开密码或管理密码（可选）
     * @return 匹配命中的位置清单（无匹配返回空列表）
     * @throws PdfOperationException 文档解析失败或密码错误时抛出
     */
    public List<TextPositionMatch> findTextPositions(Object source, String keyword, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        if (keyword == null || keyword.isEmpty()) {
            throw new IllegalArgumentException("搜索关键字 keyword 不能为空");
        }
        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument doc = loadedPdf.getDocument();
            ensureExtractAllowed(doc, password);
            return PdfRedactionSupport.findTextPositionsInDocument(doc, keyword, null);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "检索 PDF 关键字坐标失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于关键字文本自动定位并压印电子公章或手写签名
     *
     * @param source  PDF 输入源
     * @param target  目标输出
     * @param options 关键字盖章配置选项
     * @throws PdfOperationException 未命中且开启 failIfNotFound，或文档操作失败时抛出
     */
    public void stampAtKeyword(Object source, Object target, KeywordStampOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        Objects.requireNonNull(options, "关键字盖章选项 options 不能为空");
        if (options.getKeyword() == null || options.getKeyword().isEmpty()) {
            throw new IllegalArgumentException("待定位关键字 keyword 不能为空");
        }
        Objects.requireNonNull(options.getStampImage(), "印章图片输入源 stampImage 不能为空");

        byte[] imageBytes = resolveImageBytes(options.getStampImage());
        try (LoadedPdfDocument loadedPdf = loadDocument(source, options.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, options.getPassword(), "按关键字盖章");
            ensureExtractAllowed(doc, options.getPassword());

            int totalPages = doc.getNumberOfPages();
            if (totalPages <= 0) {
                throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "PDF 文档页数为空");
            }

            List<TextPositionMatch> matches = PdfRedactionSupport.findTextPositionsInDocument(
                    doc, options.getKeyword(), options.getTargetPages());
            if (matches.isEmpty()) {
                if (options.isFailIfNotFound()) {
                    throw new PdfOperationException(DocumentResultCode.KEYWORD_NOT_FOUND,
                            "未在 PDF 文档中定位到关键字锚点: " + options.getKeyword());
                }
                saveDocument(doc, target);
                return;
            }

            List<TextPositionMatch> selected = PdfRedactionSupport.selectKeywordMatches(matches, options.getOccurrence());
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, imageBytes, "keyword-stamp");
            float width = options.getWidth() > 0 ? options.getWidth() : pdImage.getWidth();
            float height = options.getHeight() > 0 ? options.getHeight() : pdImage.getHeight();
            PDPageContentStream.AppendMode mode = options.getLayer() == WatermarkLayer.UNDERLAY
                    ? PDPageContentStream.AppendMode.PREPEND
                    : PDPageContentStream.AppendMode.APPEND;

            for (TextPositionMatch match : selected) {
                PDPage page = doc.getPage(match.getPageIndex());
                drawStampImageOnPage(doc, page, pdImage,
                        match.getX() + options.getOffsetX(),
                        match.getY() + options.getOffsetY(),
                        width, height, options.getOpacity(), mode);
            }
            saveDocument(doc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "基于关键字压印 PDF 电子公章失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于关键字文本自动定位压印电子公章并导出为字节数组
     *
     * @param source  PDF 输入源
     * @param options 关键字盖章配置选项
     * @return 盖章后的 PDF 二进制数据
     */
    public byte[] stampAtKeywordToBytes(Object source, KeywordStampOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stampAtKeyword(source, baos, options);
        return baos.toByteArray();
    }

    // ==========================================
    // 2C. 敏感内容物理不可逆涂黑脱敏 (Physical Raster Redaction)
    // ==========================================

    /**
     * 对 PDF 文档执行敏感内容物理不可逆涂黑脱敏（仅对命中页进行光栅化重封，未命中页保持 100% 原生矢量）
     *
     * @param source  PDF 输入源
     * @param target  目标输出
     * @param options 脱敏配置选项
     */
    public void redact(Object source, Object target, RedactOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        Objects.requireNonNull(options, "物理涂黑脱敏选项 options 不能为空");

        boolean hasKeywords = options.getKeywords() != null && options.getKeywords().stream().anyMatch(k -> k != null && !k.isEmpty());
        boolean hasAreas = options.getAreas() != null && !options.getAreas().isEmpty();
        if (!hasKeywords && !hasAreas) {
            throw new IllegalArgumentException("物理涂黑脱敏 keywords 与 areas 不能同时为空");
        }

        float dpi = options.getDpi() != null ? options.getDpi() : resolveDefaultRedactionDpi();
        validateDpi(dpi);
        Color maskColor = parseColor(options.resolveEffectiveMaskColor(), Color.BLACK);
        float padding = Math.max(0.0f, options.getPadding());

        try (LoadedPdfDocument loadedPdf = loadDocument(source, options.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, options.getPassword(), "执行物理涂黑脱敏");
            if (hasKeywords) {
                ensureExtractAllowed(doc, options.getPassword());
            }

            int totalPages = doc.getNumberOfPages();
            if (totalPages <= 0) {
                throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "PDF 文档页数为空");
            }

            PdfRedactionSupport.RedactionPlan plan = PdfRedactionSupport.collectRedactionPlan(
                    doc, options, padding, totalPages);
            if (options.isFailIfNotFound()
                    && (plan.masksByPage().isEmpty() || (hasKeywords && plan.keywordMatchCount() == 0))) {
                throw new PdfOperationException(DocumentResultCode.KEYWORD_NOT_FOUND,
                        "未在 PDF 文档中命中任何待物理涂黑脱敏的敏感内容");
            }
            if (!plan.masksByPage().isEmpty()) {
                PdfRedactionSupport.applyRasterRedactionToPages(doc, plan.masksByPage(), dpi, maskColor);
            }
            saveDocument(doc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "PDF 物理涂黑脱敏失败: " + e.getMessage(), e);
        }
    }

    /**
     * 对 PDF 文档执行敏感内容物理不可逆涂黑脱敏并导出为字节数组
     *
     * @param source  PDF 输入源
     * @param options 脱敏配置选项
     * @return 脱敏重封后的 PDF 二进制数据
     */
    public byte[] redactToBytes(Object source, RedactOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        redact(source, baos, options);
        return baos.toByteArray();
    }

    /**
     * 便捷方法：按敏感关键字清单对 PDF 执行物理不可逆涂黑脱敏
     *
     * @param source   PDF 输入源
     * @param target   目标输出
     * @param keywords 敏感关键字清单
     */
    public void redactKeywords(Object source, Object target, List<String> keywords) {
        redactKeywords(source, target, keywords, null);
    }

    /**
     * 按敏感关键字清单对 PDF 执行物理不可逆涂黑脱敏（支持密码解密）
     *
     * @param source   PDF 输入源
     * @param target   目标输出
     * @param keywords 敏感关键字清单
     * @param password 打开密码或管理密码（可选）
     */
    public void redactKeywords(Object source, Object target, List<String> keywords, String password) {
        redact(source, target, RedactOptions.builder().keywords(keywords).password(password).build());
    }

    /**
     * 便捷方法：按敏感关键字清单对 PDF 执行物理不可逆涂黑脱敏并导出为字节数组
     *
     * @param source   PDF 输入源
     * @param keywords 敏感关键字清单
     * @return 脱敏重封后的 PDF 二进制数据
     */
    public byte[] redactKeywordsToBytes(Object source, List<String> keywords) {
        return redactKeywordsToBytes(source, keywords, null);
    }

    /**
     * 按敏感关键字清单对 PDF 执行物理不可逆涂黑脱敏并导出为字节数组（支持密码解密）
     *
     * @param source   PDF 输入源
     * @param keywords 敏感关键字清单
     * @param password 打开密码或管理密码（可选）
     * @return 脱敏重封后的 PDF 二进制数据
     */
    public byte[] redactKeywordsToBytes(Object source, List<String> keywords, String password) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        redactKeywords(source, baos, keywords, password);
        return baos.toByteArray();
    }

    /**
     * 便捷方法：按指定物理坐标矩形清单对 PDF 执行物理不可逆涂黑脱敏
     *
     * @param source PDF 输入源
     * @param target 目标输出
     * @param areas  物理涂黑区域清单
     */
    public void redactAreas(Object source, Object target, List<RedactArea> areas) {
        redactAreas(source, target, areas, null);
    }

    /**
     * 按指定物理坐标矩形清单对 PDF 执行物理不可逆涂黑脱敏（支持密码解密）
     *
     * @param source   PDF 输入源
     * @param target   目标输出
     * @param areas    物理涂黑区域清单
     * @param password 打开密码或管理密码（可选）
     */
    public void redactAreas(Object source, Object target, List<RedactArea> areas, String password) {
        redact(source, target, RedactOptions.builder().areas(areas).password(password).build());
    }

    /**
     * 便捷方法：按指定物理坐标矩形清单对 PDF 执行物理不可逆涂黑脱敏并导出为字节数组
     *
     * @param source PDF 输入源
     * @param areas  物理涂黑区域清单
     * @return 脱敏重封后的 PDF 二进制数据
     */
    public byte[] redactAreasToBytes(Object source, List<RedactArea> areas) {
        return redactAreasToBytes(source, areas, null);
    }

    /**
     * 按指定物理坐标矩形清单对 PDF 执行物理不可逆涂黑脱敏并导出为字节数组（支持密码解密）
     *
     * @param source   PDF 输入源
     * @param areas    物理涂黑区域清单
     * @param password 打开密码或管理密码（可选）
     * @return 脱敏重封后的 PDF 二进制数据
     */
    public byte[] redactAreasToBytes(Object source, List<RedactArea> areas, String password) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        redactAreas(source, baos, areas, password);
        return baos.toByteArray();
    }

    private void drawStampImageOnPage(PDDocument doc, PDPage page, PDImageXObject pdImage,
                                      float x, float y, float width, float height, float opacity,
                                      PDPageContentStream.AppendMode mode) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, mode, true, true)) {
            if (opacity < 1.0f) {
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(Math.max(0.0f, Math.min(1.0f, opacity)));
                cs.setGraphicsStateParameters(gs);
            }
            cs.drawImage(pdImage, x, y, width, height);
        }
    }

    /**
     * 将文档写出至目标输出；修改类操作在传入加密文档打开口令解密编辑时默认清除旧口令以便后续流程消费（如需重加密请显式链式调用 protect）。
     */
    private void saveDocument(PDDocument doc, Object target) throws IOException {
        saveDocument(doc, target, true);
    }

    private void saveDocument(PDDocument doc, Object target, boolean removeSecurity) throws IOException {
        if (removeSecurity && doc.isEncrypted()) {
            doc.setAllSecurityToBeRemoved(true);
        }
        OutputStream os = resolveOutputStream(target);
        try {
            doc.save(os);
            os.flush();
        } finally {
            if (!(target instanceof OutputStream)) {
                os.close();
            }
        }
    }

    static int normalizePageIndex(int pageIndex, int totalPages) {
        int idx = pageIndex < 0 ? totalPages + pageIndex : pageIndex;
        if (idx < 0 || idx >= totalPages) {
            throw new IllegalArgumentException(String.format("目标页面索引超出范围 (总页数: %d, 请求页码: %d)", totalPages, pageIndex));
        }
        return idx;
    }

    private static void ensureEditableUnlocked(PDDocument doc, String password, String actionName) {
        if (doc.isEncrypted() && password == null) {
            throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR,
                    "PDF 文档已加密，请提供有效密码后再" + actionName);
        }
    }

    private static void ensureExtractAllowed(PDDocument doc, String password) {
        if (doc.isEncrypted()) {
            if (password == null) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请提供有效密码后再提取文本");
            }
            AccessPermission ap = doc.getCurrentAccessPermission();
            if (ap != null && !ap.canExtractContent()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "当前密码无 PDF 文本提取权限");
            }
        }
    }

    private float resolveDefaultRedactionDpi() {
        DocumentPdfProperties pdfProps = documentProperties.getPdf();
        return pdfProps != null && pdfProps.getRedactionDpi() >= 72.0f
                ? pdfProps.getRedactionDpi()
                : 200.0f;
    }

    // ==========================================
    // 3. 通用辅助与文本提取 (Utilities)
    // ==========================================

    /**
     * 从 PDF 文档中提取全文纯文本
     *
     * @param source 原始 PDF 输入源
     * @return 全文文本内容
     */
    public String extractText(Object source) {
        return extractText(source, 1, Integer.MAX_VALUE, null);
    }

    /**
     * 从 PDF 文档中提取全文纯文本（支持密码解密访问）
     *
     * @param source   原始 PDF 输入源
     * @param password 打开密码（若无密码传 null）
     * @return 全文文本内容
     */
    public String extractText(Object source, String password) {
        return extractText(source, 1, Integer.MAX_VALUE, password);
    }

    /**
     * 提取 PDF 文档指定页码范围的纯文本内容（1-based 包含两端）
     *
     * @param source    原始 PDF 输入源
     * @param startPage 起始页码（从 1 开始）
     * @param endPage   结束页码（从 1 开始）
     * @return 范围内的纯文本内容
     */
    public String extractText(Object source, int startPage, int endPage) {
        return extractText(source, startPage, endPage, null);
    }

    /**
     * 提取 PDF 文档指定页码范围的纯文本内容（支持密码解密访问，1-based 包含两端）
     *
     * @param source    原始 PDF 输入源
     * @param startPage 起始页码（从 1 开始）
     * @param endPage   结束页码（从 1 开始）
     * @param password  打开密码（若无密码传 null）
     * @return 范围内的纯文本内容
     */
    public String extractText(Object source, int startPage, int endPage, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        if (startPage < 1) {
            throw new IllegalArgumentException("起始页码 startPage 必须大于等于 1");
        }
        if (endPage < startPage) {
            throw new IllegalArgumentException(String.format("结束页码 endPage (%d) 不能小于起始页码 startPage (%d)", endPage, startPage));
        }

        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                AccessPermission ap = doc.getCurrentAccessPermission();
                if (ap != null && !ap.canExtractContent()) {
                    throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密且无内容提取权限，请先提供密码解密");
                }
            }
            int totalPages = doc.getNumberOfPages();
            if (startPage > totalPages) {
                throw new PdfOperationException(DocumentResultCode.PDF_PAGE_INDEX_OUT_OF_BOUNDS,
                        String.format("起始页码 startPage (%d) 超出文档总页数 (%d)", startPage, totalPages));
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(startPage);
            stripper.setEndPage(Math.min(endPage, totalPages));
            return stripper.getText(doc);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "提取 PDF 文本失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 PDF 文档的总页数
     *
     * @param source PDF 输入源
     * @return 总页数
     */
    public int getPageCount(Object source) {
        return getPageCount(source, null);
    }

    /**
     * 获取 PDF 文档的总页数（支持密码解密访问）
     *
     * @param source   PDF 输入源
     * @param password 打开密码或管理密码（可选）
     * @return 总页数
     */
    public int getPageCount(Object source, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            return loadedPdf.getDocument().getNumberOfPages();
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "获取 PDF 页数失败: " + e.getMessage(), e);
        }
    }

    /**
     * 巡检并自省 PDF 文档核心元数据（页数、版本、摘要信息、创建修改时间及加密权限状态）
     *
     * @param source 原始 PDF 输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @return 填充完整的 PDF 核心元数据模型（绝不返回 null）
     * @throws PdfOperationException 文档解析或 IO 失败时抛出
     */
    public PdfMetadata inspectMetadata(Object source) {
        return inspectMetadata(source, null);
    }

    /**
     * 巡检并自省 PDF 文档核心元数据（支持密码解密访问）
     *
     * @param source   原始 PDF 输入源
     * @param password 打开密码或管理密码（为 null 表示未加密或使用默认空密码探测）
     * @return 填充完整的 PDF 核心元数据模型（绝不返回 null）
     * @throws PdfOperationException 密码错误或文档解析失败时抛出
     */
    public PdfMetadata inspectMetadata(Object source, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument doc = loadedPdf.getDocument();
            int totalPages = doc.getNumberOfPages();
            float version = doc.getVersion();
            boolean encrypted = doc.isEncrypted();

            PDDocumentInformation info = doc.getDocumentInformation();
            String title = info != null ? info.getTitle() : null;
            String author = info != null ? info.getAuthor() : null;
            String subject = info != null ? info.getSubject() : null;
            String keywords = info != null ? info.getKeywords() : null;
            String creator = info != null ? info.getCreator() : null;
            String producer = info != null ? info.getProducer() : null;
            Instant creationDate = info != null && info.getCreationDate() != null
                    ? info.getCreationDate().toInstant() : null;
            Instant modificationDate = info != null && info.getModificationDate() != null
                    ? info.getModificationDate().toInstant() : null;

            AccessPermission permission = doc.getCurrentAccessPermission();
            boolean canPrint = permission == null || permission.canPrint();
            boolean canModify = permission == null || permission.canModify();
            boolean canExtract = permission == null || permission.canExtractContent();
            boolean canAssemble = permission == null || permission.canAssembleDocument();
            boolean canFill = permission == null || permission.canFillInForm();

            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            boolean hasAcroForm = acroForm != null && !acroForm.getFields().isEmpty();
            boolean hasXfa = acroForm != null && acroForm.hasXFA();

            return PdfMetadata.builder()
                    .pageCount(totalPages)
                    .version(version)
                    .title(title)
                    .author(author)
                    .subject(subject)
                    .keywords(keywords)
                    .creator(creator)
                    .producer(producer)
                    .creationDate(creationDate)
                    .modificationDate(modificationDate)
                    .encrypted(encrypted)
                    .canPrint(canPrint)
                    .canModify(canModify)
                    .canExtractContent(canExtract)
                    .canCopy(canExtract)
                    .canAssembleDocument(canAssemble)
                    .canFillInForm(canFill)
                    .hasAcroForm(hasAcroForm)
                    .hasXfa(hasXfa)
                    .build();
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "自省 PDF 元数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 增量更新 PDF 文档元数据（仅覆写 update 中的非 null 字段）
     *
     * @param source 原始 PDF 输入源
     * @param target 目标输出
     * @param update 元数据增量更新模型
     */
    public void updateMetadata(Object source, Object target, PdfMetadataUpdate update) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        Objects.requireNonNull(update, "元数据更新选项 update 不能为空");

        try (LoadedPdfDocument loadedPdf = loadDocument(source, update.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, update.getPassword(), "更新 PDF 元数据");

            PDDocumentInformation info = doc.getDocumentInformation();
            if (info == null) {
                info = new PDDocumentInformation();
            }
            if (update.getTitle() != null) {
                info.setTitle(update.getTitle());
            }
            if (update.getAuthor() != null) {
                info.setAuthor(update.getAuthor());
            }
            if (update.getSubject() != null) {
                info.setSubject(update.getSubject());
            }
            if (update.getKeywords() != null) {
                info.setKeywords(update.getKeywords());
            }
            if (update.getCreator() != null) {
                info.setCreator(update.getCreator());
            }
            if (update.getProducer() != null) {
                info.setProducer(update.getProducer());
            }
            if (update.getCreationDate() != null) {
                info.setCreationDate(GregorianCalendar.from(update.getCreationDate().atZone(ZoneOffset.UTC)));
            }
            if (update.getModificationDate() != null) {
                info.setModificationDate(GregorianCalendar.from(update.getModificationDate().atZone(ZoneOffset.UTC)));
            }
            doc.setDocumentInformation(info);
            saveDocument(doc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "更新 PDF 元数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 增量更新 PDF 文档元数据并导出为字节数组
     *
     * @param source 原始 PDF 输入源
     * @param update 元数据增量更新模型
     * @return 更新元数据后的 PDF 二进制数据
     */
    public byte[] updateMetadataToBytes(Object source, PdfMetadataUpdate update) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        updateMetadata(source, baos, update);
        return baos.toByteArray();
    }

    /**
     * 彻底脱敏剥离 PDF 文档标准摘要信息与 XMP 元数据
     *
     * @param source 原始 PDF 输入源
     * @param target 目标输出
     */
    public void stripMetadata(Object source, Object target) {
        stripMetadata(source, target, null);
    }

    /**
     * 彻底脱敏剥离 PDF 文档标准摘要信息与 XMP 元数据（支持密码解密）
     *
     * @param source   原始 PDF 输入源
     * @param target   目标输出
     * @param password 打开密码或管理密码（可选）
     */
    public void stripMetadata(Object source, Object target, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, password, "剥离 PDF 元数据");

            doc.setDocumentInformation(new PDDocumentInformation());
            doc.getDocumentCatalog().setMetadata(null);
            saveDocument(doc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "剥离 PDF 元数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 彻底脱敏剥离 PDF 元数据并导出为字节数组
     *
     * @param source 原始 PDF 输入源
     * @return 剥离元数据后的 PDF 二进制数据
     */
    public byte[] stripMetadataToBytes(Object source) {
        return stripMetadataToBytes(source, null);
    }

    /**
     * 彻底脱敏剥离 PDF 元数据并导出为字节数组（支持密码解密）
     *
     * @param source   原始 PDF 输入源
     * @param password 打开密码或管理密码（可选）
     * @return 剥离元数据后的 PDF 二进制数据
     */
    public byte[] stripMetadataToBytes(Object source, String password) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stripMetadata(source, baos, password);
        return baos.toByteArray();
    }

    // ==========================================
    // 4. 多 PDF 物理合并与拆分提取 (Merge & Split)
    // ==========================================

    /**
     * 将多个独立的 PDF 文档物理顺序合并为单一完整文档
     *
     * @param target     目标输出对象（支持 File, Path, OutputStream, WritableResource, String）
     * @param pdfSources 待合并的 PDF 文档列表（支持 Resource, InputStream, File, Path, byte[], String）
     * @throws PdfOperationException 合并操作失败或输入流异常时抛出
     */
    public void merge(Object target, List<?> pdfSources) {
        merge(target, pdfSources, null);
    }

    /**
     * 将多个独立的 PDF 文档物理顺序合并为单一完整文档（支持密码解密访问）
     *
     * @param target     目标输出对象（支持 File, Path, OutputStream, WritableResource, String）
     * @param pdfSources 待合并的 PDF 文档列表
     * @param password   统一打开密码（为 null 表示未加密）
     * @throws PdfOperationException 合并操作失败、密码错误或输入流异常时抛出
     */
    public void merge(Object target, List<?> pdfSources, String password) {
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        if (pdfSources == null || pdfSources.isEmpty()) {
            throw new IllegalArgumentException("待合并的 PDF 文档输入列表 pdfSources 不能为空");
        }

        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            if (password != null) {
                List<LoadedPdfDocument> loadedDocs = new ArrayList<>(pdfSources.size());
                try (PDDocument mergedDoc = new PDDocument()) {
                    for (int i = 0; i < pdfSources.size(); i++) {
                        Object src = pdfSources.get(i);
                        if (src == null) {
                            throw new IllegalArgumentException("待合并的 PDF 文档项不能为空 (索引: " + i + ")");
                        }
                        LoadedPdfDocument loaded = loadDocument(src, password);
                        loadedDocs.add(loaded);
                        merger.appendDocument(mergedDoc, loaded.getDocument());
                    }
                    saveDocument(mergedDoc, target);
                } finally {
                    for (LoadedPdfDocument loaded : loadedDocs) {
                        try {
                            loaded.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
                return;
            }

            List<InputStream> openedStreams = new ArrayList<>();
            try {
                for (int i = 0; i < pdfSources.size(); i++) {
                    Object src = pdfSources.get(i);
                    if (src == null) {
                        throw new IllegalArgumentException("待合并的 PDF 文档项不能为空 (索引: " + i + ")");
                    }
                    InputStream is = resolveRawInputStream(src);
                    openedStreams.add(is);
                    merger.addSource(new RandomAccessReadBuffer(is));
                }

                OutputStream os = resolveOutputStream(target);
                try {
                    merger.setDestinationStream(os);
                    merger.mergeDocuments(null);
                    os.flush();
                } finally {
                    if (!(target instanceof OutputStream)) {
                        os.close();
                    }
                }
            } finally {
                for (InputStream is : openedStreams) {
                    try {
                        is.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (DocumentException e) {
            throw new PdfOperationException(e.getResultCode(), e.getMessage(), e);
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "合并 PDF 文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将多个独立的 PDF 文档物理合并并输出至目标（可变参数便捷重载）
     *
     * @param target     目标输出对象
     * @param pdfSources 待合并的 PDF 文档输入源列表
     */
    public void merge(Object target, Object... pdfSources) {
        Objects.requireNonNull(pdfSources, "待合并的 PDF 文档输入源不能为空");
        merge(target, Arrays.asList(pdfSources));
    }

    /**
     * 将多个独立的 PDF 文档物理合并并导出为二进制字节数组
     *
     * @param pdfSources 待合并的 PDF 文档列表
     * @return 合并后的 PDF 二进制数据
     */
    public byte[] mergeToBytes(List<?> pdfSources) {
        return mergeToBytes(pdfSources, null);
    }

    /**
     * 将多个独立的 PDF 文档物理合并并导出为二进制字节数组（支持密码解密访问）
     *
     * @param pdfSources 待合并的 PDF 文档列表
     * @param password   统一打开密码（可选）
     * @return 合并后的 PDF 二进制数据
     */
    public byte[] mergeToBytes(List<?> pdfSources, String password) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        merge(baos, pdfSources, password);
        return baos.toByteArray();
    }

    /**
     * 将多个独立的 PDF 文档物理合并并导出为二进制字节数组（可变参数便捷重载）
     *
     * @param pdfSources 待合并的 PDF 文档输入源列表
     * @return 合并后的 PDF 二进制数据
     */
    public byte[] mergeToBytes(Object... pdfSources) {
        Objects.requireNonNull(pdfSources, "待合并的 PDF 文档输入源不能为空");
        return mergeToBytes(Arrays.asList(pdfSources));
    }

    /**
     * 将 PDF 文档逐页物理拆分并保存至指定输出目录
     *
     * @param source    原始 PDF 输入源
     * @param outputDir 输出目录（支持 File, Path, String）
     * @param prefix    生成拆分文件名称前缀（如 "page_"，若为空默认 "split_"）
     * @return 拆分生成的物理文件列表
     * @throws PdfOperationException 拆分操作失败时抛出
     */
    public List<File> split(Object source, Object outputDir, String prefix) {
        return split(source, outputDir, prefix, null);
    }

    /**
     * 将 PDF 文档逐页物理拆分并保存至指定输出目录（支持密码解密访问）
     *
     * @param source    原始 PDF 输入源
     * @param outputDir 输出目录（支持 File, Path, String）
     * @param prefix    生成拆分文件名称前缀（如 "page_"，若为空默认 "split_"）
     * @param password  打开密码或管理密码（可选）
     * @return 拆分生成的物理文件列表
     * @throws PdfOperationException 拆分操作失败时抛出
     */
    public List<File> split(Object source, Object outputDir, String prefix, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(outputDir, "输出目录 outputDir 不能为空");

        File targetDir;
        if (outputDir instanceof File f) {
            targetDir = f;
        } else if (outputDir instanceof Path p) {
            targetDir = p.toFile();
        } else if (outputDir instanceof String s) {
            targetDir = new File(FileUtil.cleanPath(s.trim()));
        } else {
            throw new IllegalArgumentException("不支持的输出目录类型: " + outputDir.getClass().getName());
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        String filePrefix = "split_";
        if (prefix != null && !prefix.trim().isEmpty()) {
            String cleanedPrefix = FileUtil.cleanFilename(prefix.replace("\r", "").replace("\n", "").trim());
            if (cleanedPrefix.isEmpty()) {
                throw new IllegalArgumentException("拆分文件名前缀包含非法路径穿越或无效字符: " + prefix);
            }
            filePrefix = cleanedPrefix;
        }
        List<File> generatedFiles = new ArrayList<>();

        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument srcDoc = loadedPdf.getDocument();
            ensureEditableUnlocked(srcDoc, password, "拆分 PDF 文档");
            Splitter splitter = new Splitter();
            splitter.setSplitAtPage(1); // 逐页拆分
            List<PDDocument> pages = splitter.split(srcDoc);

            try {
                for (int i = 0; i < pages.size(); i++) {
                    PDDocument pageDoc = pages.get(i);
                    File outFile = FileUtil.resolveSafePath(targetDir.toPath(), filePrefix + (i + 1) + ".pdf").toFile();
                    pageDoc.save(outFile);
                    generatedFiles.add(outFile);
                }
            } finally {
                for (PDDocument pDoc : pages) {
                    try {
                        pDoc.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            return generatedFiles;
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "拆分 PDF 文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 PDF 文档拆分并输出至指定目录（使用默认前缀 "split_"）
     *
     * @param source    原始 PDF 输入源
     * @param outputDir 输出目录
     * @return 拆分生成的物理文件列表
     */
    public List<File> split(Object source, Object outputDir) {
        return split(source, outputDir, "split_");
    }

    /**
     * 将 PDF 文档逐页拆分为多个单页 PDF 字节数组列表
     *
     * @param source 原始 PDF 输入源
     * @return 拆分后的单页 PDF 字节数组列表
     * @throws PdfOperationException 拆分操作失败时抛出
     */
    public List<byte[]> splitToBytes(Object source) {
        return splitToBytes(source, 1);
    }

    /**
     * 按指定每份页数将 PDF 文档拆分为多个 PDF 字节数组列表
     *
     * @param source      原始 PDF 输入源
     * @param splitAtPage 每份拆分文件的页数（默认 1）
     * @return 拆分后的 PDF 字节数组列表
     * @throws PdfOperationException 拆分操作失败时抛出
     */
    public List<byte[]> splitToBytes(Object source, int splitAtPage) {
        return splitToBytes(source, splitAtPage, null);
    }

    /**
     * 按指定每份页数将 PDF 文档拆分为多个 PDF 字节数组列表（支持密码解密访问）
     *
     * @param source      原始 PDF 输入源
     * @param splitAtPage 每份拆分文件的页数（默认 1）
     * @param password    打开密码或管理密码（可选）
     * @return 拆分后的 PDF 字节数组列表
     * @throws PdfOperationException 拆分操作失败时抛出
     */
    public List<byte[]> splitToBytes(Object source, int splitAtPage, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        if (splitAtPage < 1) {
            throw new IllegalArgumentException("拆分页数 splitAtPage 必须大于等于 1");
        }
        List<byte[]> result = new ArrayList<>();
        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument srcDoc = loadedPdf.getDocument();
            ensureEditableUnlocked(srcDoc, password, "拆分 PDF 文档");
            Splitter splitter = new Splitter();
            splitter.setSplitAtPage(splitAtPage);
            List<PDDocument> pages = splitter.split(srcDoc);

            try {
                for (PDDocument pageDoc : pages) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    pageDoc.save(baos);
                    result.add(baos.toByteArray());
                }
            } finally {
                for (PDDocument pDoc : pages) {
                    try {
                        pDoc.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            return result;
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "内存拆分 PDF 文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取 PDF 文档中指定页码范围并保存为新文档（1-based 包含两端）
     *
     * @param source   原始 PDF 输入源
     * @param target   目标输出
     * @param fromPage 起始页码（从 1 开始，包含）
     * @param toPage   结束页码（从 1 开始，包含）
     */
    public void extractPageRange(Object source, Object target, int fromPage, int toPage) {
        extractPageRange(source, target, fromPage, toPage, null);
    }

    /**
     * 提取 PDF 文档中指定页码范围并保存为新文档（1-based 包含两端，支持密码解密访问）
     *
     * @param source   原始 PDF 输入源
     * @param target   目标输出
     * @param fromPage 起始页码（从 1 开始，包含）
     * @param toPage   结束页码（从 1 开始，包含）
     * @param password 打开密码或管理密码（可选）
     */
    public void extractPageRange(Object source, Object target, int fromPage, int toPage, String password) {
        if (fromPage < 1 || toPage < fromPage) {
            throw new IllegalArgumentException(String.format("页码范围无效 (fromPage: %d, toPage: %d)", fromPage, toPage));
        }
        int[] indexes = new int[toPage - fromPage + 1];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = fromPage - 1 + i;
        }
        extractPages(source, target, password, indexes);
    }

    /**
     * 提取 PDF 文档中指定页码范围并保存为新文档（兼容重载，支持 1-based 范围与 0-based 索引自适应）
     *
     * @param source   原始 PDF 输入源
     * @param target   目标输出
     * @param fromPage 起始页码或第一个页面索引
     * @param toPage   结束页码或第二个页面索引
     */
    public void extractPages(Object source, Object target, int fromPage, int toPage) {
        if (fromPage == 0) {
            // 当传入 0 索引时，自适应作为 0-based 页面索引处理，消除与 int... 变长重载歧义
            extractPages(source, target, new int[]{fromPage, toPage});
            return;
        }
        extractPageRange(source, target, fromPage, toPage);
    }

    /**
     * 提取 PDF 文档中指定页面索引并保存为新文档（0-based，支持负数索引如 -1 为最后一页）
     *
     * @param source      原始 PDF 输入源
     * @param target      目标输出
     * @param pageIndexes 待提取的页面索引列表
     */
    public void extractPages(Object source, Object target, int... pageIndexes) {
        extractPages(source, target, null, pageIndexes);
    }

    /**
     * 按离散页面索引精确提取 PDF 文档中的页面并重组为新文档（0-based，支持负数与密码解密访问）
     *
     * @param source      原始 PDF 输入源
     * @param target      目标输出
     * @param password    打开密码或管理密码（可选）
     * @param pageIndexes 待提取的页面索引列表（如 0, 2, -1）
     */
    public void extractPages(Object source, Object target, String password, int... pageIndexes) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        Objects.requireNonNull(pageIndexes, "待提取的页面索引列表不能为空");
        if (pageIndexes.length == 0) {
            throw new IllegalArgumentException("待提取的页面索引列表不能为空");
        }

        try (LoadedPdfDocument loadedPdf = loadDocument(source, password);
             PDDocument destDoc = new PDDocument()) {

            PDDocument srcDoc = loadedPdf.getDocument();
            ensureEditableUnlocked(srcDoc, password, "提取 PDF 页面");
            int totalPages = srcDoc.getNumberOfPages();

            for (int rawIndex : pageIndexes) {
                int actualIndex = normalizePageIndex(rawIndex, totalPages);
                destDoc.importPage(srcDoc.getPage(actualIndex));
            }

            saveDocument(destDoc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "提取 PDF 页面失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取 PDF 文档指定页码范围并导出为字节数组（1-based 包含两端）
     *
     * @param source   原始 PDF 输入源
     * @param fromPage 起始页码（从 1 开始）
     * @param toPage   结束页码（从 1 开始）
     * @return 提取后的 PDF 二进制数据
     */
    public byte[] extractPageRangeToBytes(Object source, int fromPage, int toPage) {
        return extractPageRangeToBytes(source, fromPage, toPage, null);
    }

    /**
     * 提取 PDF 文档指定页码范围并导出为字节数组（1-based 包含两端，支持密码解密）
     *
     * @param source   原始 PDF 输入源
     * @param fromPage 起始页码（从 1 开始）
     * @param toPage   结束页码（从 1 开始）
     * @param password 打开密码或管理密码（可选）
     * @return 提取后的 PDF 二进制数据
     */
    public byte[] extractPageRangeToBytes(Object source, int fromPage, int toPage, String password) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        extractPageRange(source, baos, fromPage, toPage, password);
        return baos.toByteArray();
    }

    /**
     * 提取 PDF 文档指定页码范围并导出为字节数组（兼容重载，支持 1-based 范围与 0-based 索引自适应）
     *
     * @param source   原始 PDF 输入源
     * @param fromPage 起始页码或第一个索引
     * @param toPage   结束页码或第二个索引
     * @return 提取后的 PDF 二进制数据
     */
    public byte[] extractPagesToBytes(Object source, int fromPage, int toPage) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        extractPages(source, baos, fromPage, toPage);
        return baos.toByteArray();
    }

    /**
     * 提取 PDF 文档指定页面索引并导出为字节数组（0-based，支持负数）
     *
     * @param source      原始 PDF 输入源
     * @param pageIndexes 待提取的页面索引列表
     * @return 提取后的 PDF 二进制数据
     */
    public byte[] extractPagesToBytes(Object source, int... pageIndexes) {
        return extractPagesToBytes(source, null, pageIndexes);
    }

    /**
     * 提取 PDF 文档指定页面索引并导出为字节数组（0-based，支持负数与密码解密）
     *
     * @param source      原始 PDF 输入源
     * @param password    打开密码或管理密码（可选）
     * @param pageIndexes 待提取的页面索引列表
     * @return 提取后的 PDF 二进制数据
     */
    public byte[] extractPagesToBytes(Object source, String password, int... pageIndexes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        extractPages(source, baos, password, pageIndexes);
        return baos.toByteArray();
    }

    // ==========================================
    // 4B. 页面删除、重排、插入与多图合成 PDF (Page Orchestration & ImagesToPdf)
    // ==========================================

    /**
     * 从 PDF 文档中物理删除指定页面索引（0-based，支持负数倒数索引）
     *
     * @param source      原始 PDF 输入源
     * @param target      目标输出
     * @param pageIndexes 待删除的页面索引列表
     */
    public void deletePages(Object source, Object target, int... pageIndexes) {
        deletePages(source, target, null, pageIndexes);
    }

    /**
     * 从 PDF 文档中物理删除指定页面索引（支持密码解密，至少保留 1 页）
     *
     * @param source      原始 PDF 输入源
     * @param target      目标输出
     * @param password    打开密码或管理密码（可选）
     * @param pageIndexes 待删除的页面索引列表（0-based，支持负数）
     */
    public void deletePages(Object source, Object target, String password, int... pageIndexes) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        if (pageIndexes == null || pageIndexes.length == 0) {
            throw new IllegalArgumentException("待删除的页面索引列表 pageIndexes 不能为空");
        }

        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, password, "删除 PDF 页面");
            int totalPages = doc.getNumberOfPages();
            if (totalPages <= 0) {
                throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "PDF 文档页数为空");
            }

            java.util.Set<Integer> toRemove = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
            for (int rawIdx : pageIndexes) {
                toRemove.add(normalizePageIndex(rawIdx, totalPages));
            }
            if (toRemove.size() >= totalPages) {
                throw new IllegalArgumentException("不能删除 PDF 的全部页面，至少保留 1 页");
            }
            for (int idx : toRemove) {
                doc.removePage(idx);
            }
            saveDocument(doc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "删除 PDF 页面失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 PDF 文档中物理删除指定页面并导出为字节数组
     *
     * @param source      原始 PDF 输入源
     * @param pageIndexes 待删除页面索引（0-based，支持负数）
     * @return 删除页面后的 PDF 二进制数据
     */
    public byte[] deletePagesToBytes(Object source, int... pageIndexes) {
        return deletePagesToBytes(source, null, pageIndexes);
    }

    /**
     * 从 PDF 文档中物理删除指定页面并导出为字节数组（支持密码解密）
     *
     * @param source      原始 PDF 输入源
     * @param password    打开密码或管理密码（可选）
     * @param pageIndexes 待删除页面索引
     * @return 删除页面后的 PDF 二进制数据
     */
    public byte[] deletePagesToBytes(Object source, String password, int... pageIndexes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        deletePages(source, baos, password, pageIndexes);
        return baos.toByteArray();
    }

    /**
     * 按全新页面顺序索引重新编排 PDF 页面结构
     *
     * @param source               原始 PDF 输入源
     * @param target               目标输出
     * @param newOrderPageIndexes  目标顺序页面索引列表（0-based，支持负数与重排筛选）
     */
    public void reorderPages(Object source, Object target, int... newOrderPageIndexes) {
        reorderPages(source, target, null, newOrderPageIndexes);
    }

    /**
     * 按全新页面顺序索引重新编排 PDF 页面结构（支持密码解密）
     *
     * @param source               原始 PDF 输入源
     * @param target               目标输出
     * @param password             打开密码或管理密码（可选）
     * @param newOrderPageIndexes  目标顺序页面索引列表
     */
    public void reorderPages(Object source, Object target, String password, int... newOrderPageIndexes) {
        extractPages(source, target, password, newOrderPageIndexes);
    }

    /**
     * 按全新页面顺序索引列表重新编排 PDF 页面结构并导出为字节数组
     *
     * @param source              原始 PDF 输入源
     * @param newOrderPageIndexes 目标顺序页面索引列表
     * @return 重排后的 PDF 二进制数据
     */
    public byte[] reorderPagesToBytes(Object source, int... newOrderPageIndexes) {
        return reorderPagesToBytes(source, null, newOrderPageIndexes);
    }

    /**
     * 按全新页面顺序索引列表重新编排 PDF 页面结构并导出为字节数组（支持密码解密）
     *
     * @param source              原始 PDF 输入源
     * @param password            打开密码或管理密码（可选）
     * @param newOrderPageIndexes 目标顺序页面索引列表
     * @return 重排后的 PDF 二进制数据
     */
    public byte[] reorderPagesToBytes(Object source, String password, int... newOrderPageIndexes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        reorderPages(source, baos, password, newOrderPageIndexes);
        return baos.toByteArray();
    }

    /**
     * 在主 PDF 文档的指定 0-based 页码位置之前插入另一份 PDF 文档全部页面
     *
     * @param targetPdfSource    宿主目标 PDF 输入源
     * @param insertPdfSource    待插入的源 PDF 输入源
     * @param outputTarget       最终融合输出目标
     * @param insertAtPageIndex  插入位置索引（0-based，0 表示插入至首页之前，equal totalPages 表示追加至末尾）
     */
    public void insertPages(Object targetPdfSource, Object insertPdfSource, Object outputTarget, int insertAtPageIndex) {
        insertPages(targetPdfSource, insertPdfSource, outputTarget, insertAtPageIndex, null, null);
    }

    /**
     * 在主 PDF 文档的指定 0-based 页码位置之前插入另一份 PDF 文档全部页面（支持双端独立密码解密）
     *
     * @param targetPdfSource    宿主目标 PDF 输入源
     * @param insertPdfSource    待插入的源 PDF 输入源
     * @param outputTarget       最终融合输出目标
     * @param insertAtPageIndex  插入位置索引（0 ~ totalPages 之间）
     * @param targetPassword     宿主 PDF 打开密码（可选）
     * @param insertPassword     待插入 PDF 打开密码（可选）
     */
    public void insertPages(Object targetPdfSource, Object insertPdfSource, Object outputTarget,
                            int insertAtPageIndex, String targetPassword, String insertPassword) {
        Objects.requireNonNull(targetPdfSource, "宿主 PDF 输入源 targetPdfSource 不能为空");
        Objects.requireNonNull(insertPdfSource, "待插入 PDF 输入源 insertPdfSource 不能为空");
        Objects.requireNonNull(outputTarget, "目标输出 outputTarget 不能为空");

        try (LoadedPdfDocument targetLoaded = loadDocument(targetPdfSource, targetPassword);
             LoadedPdfDocument insertLoaded = loadDocument(insertPdfSource, insertPassword);
             PDDocument destDoc = new PDDocument()) {

            PDDocument targetDoc = targetLoaded.getDocument();
            PDDocument insertDoc = insertLoaded.getDocument();
            ensureEditableUnlocked(targetDoc, targetPassword, "插入 PDF 页面（宿主文档）");
            ensureEditableUnlocked(insertDoc, insertPassword, "插入 PDF 页面（源文档）");

            int totalTargetPages = targetDoc.getNumberOfPages();
            if (insertAtPageIndex < 0 || insertAtPageIndex > totalTargetPages) {
                throw new IllegalArgumentException(String.format(
                        "目标插入位置索引 insertAtPageIndex 超出有效范围 [0, %d]: %d",
                        totalTargetPages, insertAtPageIndex));
            }

            for (int i = 0; i < insertAtPageIndex; i++) {
                destDoc.importPage(targetDoc.getPage(i));
            }
            for (int j = 0; j < insertDoc.getNumberOfPages(); j++) {
                destDoc.importPage(insertDoc.getPage(j));
            }
            for (int i = insertAtPageIndex; i < totalTargetPages; i++) {
                destDoc.importPage(targetDoc.getPage(i));
            }

            saveDocument(destDoc, outputTarget);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "向 PDF 插入页面失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在主 PDF 文档指定位置插入另一份 PDF 并导出为字节数组
     *
     * @param targetPdfSource   宿主 PDF 输入源
     * @param insertPdfSource   待插入 PDF 输入源
     * @param insertAtPageIndex 插入位置索引（0 ~ totalPages）
     * @return 插入合并后的 PDF 二进制数据
     */
    public byte[] insertPagesToBytes(Object targetPdfSource, Object insertPdfSource, int insertAtPageIndex) {
        return insertPagesToBytes(targetPdfSource, insertPdfSource, insertAtPageIndex, null, null);
    }

    /**
     * 在主 PDF 文档指定位置插入另一份 PDF 并导出为字节数组（支持双端密码解密）
     *
     * @param targetPdfSource   宿主 PDF 输入源
     * @param insertPdfSource   待插入 PDF 输入源
     * @param insertAtPageIndex 插入位置索引（0 ~ totalPages）
     * @param targetPassword    宿主 PDF 密码（可选）
     * @param insertPassword    待插入 PDF 密码（可选）
     * @return 插入合并后的 PDF 二进制数据
     */
    public byte[] insertPagesToBytes(Object targetPdfSource, Object insertPdfSource, int insertAtPageIndex,
                                     String targetPassword, String insertPassword) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        insertPages(targetPdfSource, insertPdfSource, baos, insertAtPageIndex, targetPassword, insertPassword);
        return baos.toByteArray();
    }

    /**
     * 将多张图片按顺序合成编排为单一 PDF 文档（默认标准 A4 自适应）
     *
     * @param imageSources 图片输入源列表（支持 File, Path, InputStream, byte[], Resource, BufferedImage, String）
     * @param target       目标输出
     */
    public void imagesToPdf(List<?> imageSources, Object target) {
        imagesToPdf(imageSources, target, ImageToPdfOptions.defaults());
    }

    /**
     * 将多张图片按顺序合成编排为单一 PDF 文档（支持标准页面适配或按原图尺寸精确封装）
     *
     * @param imageSources 图片输入源列表
     * @param target       目标输出
     * @param options      合成编排配置选项
     */
    public void imagesToPdf(List<?> imageSources, Object target, ImageToPdfOptions options) {
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        if (imageSources == null || imageSources.isEmpty()) {
            throw new IllegalArgumentException("待合成的图片输入列表 imageSources 不能为空");
        }
        ImageToPdfOptions opts = options != null ? options : ImageToPdfOptions.defaults();

        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < imageSources.size(); i++) {
                Object src = imageSources.get(i);
                if (src == null) {
                    throw new IllegalArgumentException("图片输入源不能为空 (索引: " + i + ")");
                }
                byte[] imgBytes = resolveImageBytes(src);
                PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, imgBytes, "img-" + i);

                float imgW = Math.max(1.0f, pdImage.getWidth());
                float imgH = Math.max(1.0f, pdImage.getHeight());
                float left = Math.max(0.0f, opts.getMarginLeft());
                float right = Math.max(0.0f, opts.getMarginRight());
                float top = Math.max(0.0f, opts.getMarginTop());
                float bottom = Math.max(0.0f, opts.getMarginBottom());

                float pageW = opts.isFitToImage() ? (imgW + left + right) : Math.max(1.0f, opts.getPageWidth());
                float pageH = opts.isFitToImage() ? (imgH + top + bottom) : Math.max(1.0f, opts.getPageHeight());

                PDPage page = new PDPage(new PDRectangle(pageW, pageH));
                doc.addPage(page);

                float availW = Math.max(1.0f, pageW - left - right);
                float availH = Math.max(1.0f, pageH - top - bottom);
                float drawW;
                float drawH;
                if (opts.isFitToImage()) {
                    drawW = imgW;
                    drawH = imgH;
                } else if (opts.isKeepAspectRatio()) {
                    float ratio = Math.min(availW / imgW, availH / imgH);
                    drawW = imgW * ratio;
                    drawH = imgH * ratio;
                } else {
                    drawW = availW;
                    drawH = availH;
                }

                float drawX = opts.isCenter() ? left + (availW - drawW) / 2.0f : left;
                float drawY = opts.isCenter() ? bottom + (availH - drawH) / 2.0f : pageH - top - drawH;

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(pdImage, drawX, drawY, drawW, drawH);
                }
            }
            saveDocument(doc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "多图合成导出 PDF 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将多张图片按顺序合成为单一 PDF 并导出为字节数组（默认 A4 自适应）
     *
     * @param imageSources 图片输入源列表
     * @return 合成后的 PDF 二进制数据
     */
    public byte[] imagesToPdfToBytes(List<?> imageSources) {
        return imagesToPdfToBytes(imageSources, ImageToPdfOptions.defaults());
    }

    /**
     * 将多张图片按顺序合成为单一 PDF 并导出为字节数组
     *
     * @param imageSources 图片输入源列表
     * @param options      合成编排配置选项
     * @return 合成后的 PDF 二进制数据
     */
    public byte[] imagesToPdfToBytes(List<?> imageSources, ImageToPdfOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        imagesToPdf(imageSources, baos, options);
        return baos.toByteArray();
    }

    // ==========================================
    // 5. 页面旋转物理校准 (Rotate)
    // ==========================================

    /**
     * 对 PDF 的全部或特定页面按指定角度进行物理旋转校准
     *
     * @param source      原始 PDF 输入源
     * @param target      目标输出
     * @param angle       旋转角度（必须为 90 的整数倍，如 90, 180, 270）
     * @param pageIndexes 目标旋转页面索引（0-based，支持负数；若未提供则旋转全部页面）
     * @throws PdfOperationException 旋转操作失败时抛出
     */
    public void rotatePages(Object source, Object target, int angle, int... pageIndexes) {
        rotatePages(source, target, angle, null, pageIndexes);
    }

    /**
     * 对 PDF 的全部或特定页面按指定角度进行物理旋转校准（支持密码解密访问）
     *
     * @param source      原始 PDF 输入源
     * @param target      目标输出
     * @param angle       旋转角度（必须为 90 的整数倍，如 90, 180, 270）
     * @param password    打开密码或管理密码（可选）
     * @param pageIndexes 目标旋转页面索引（0-based，支持负数；若未提供则旋转全部页面）
     * @throws PdfOperationException 旋转操作失败时抛出
     */
    public void rotatePages(Object source, Object target, int angle, String password, int... pageIndexes) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        if (angle % 90 != 0) {
            throw new IllegalArgumentException("旋转角度 angle 必须为 90 的整数倍 (如 90, 180, 270): " + angle);
        }

        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, password, "旋转 PDF 页面");
            int totalPages = doc.getNumberOfPages();

            if (pageIndexes == null || pageIndexes.length == 0) {
                for (PDPage page : doc.getPages()) {
                    int currentRotation = page.getRotation();
                    page.setRotation((currentRotation + angle + 360) % 360);
                }
            } else {
                for (int rawIndex : pageIndexes) {
                    int actualIndex = normalizePageIndex(rawIndex, totalPages);
                    PDPage page = doc.getPage(actualIndex);
                    int currentRotation = page.getRotation();
                    page.setRotation((currentRotation + angle + 360) % 360);
                }
            }

            saveDocument(doc, target);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "旋转 PDF 页面失败: " + e.getMessage(), e);
        }
    }

    /**
     * 对 PDF 页面进行物理旋转校准并导出为字节数组
     *
     * @param source      原始 PDF 输入源
     * @param angle       旋转角度（必须为 90 的整数倍）
     * @param pageIndexes 目标旋转页面索引（未提供则旋转全部页面）
     * @return 旋转后的 PDF 二进制数据
     */
    public byte[] rotatePagesToBytes(Object source, int angle, int... pageIndexes) {
        return rotatePagesToBytes(source, angle, null, pageIndexes);
    }

    /**
     * 对 PDF 页面进行物理旋转校准并导出为字节数组（支持密码解密访问）
     *
     * @param source      原始 PDF 输入源
     * @param angle       旋转角度（必须为 90 的整数倍）
     * @param password    打开密码或管理密码（可选）
     * @param pageIndexes 目标旋转页面索引（未提供则旋转全部页面）
     * @return 旋转后的 PDF 二进制数据
     */
    public byte[] rotatePagesToBytes(Object source, int angle, String password, int... pageIndexes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        rotatePages(source, baos, angle, password, pageIndexes);
        return baos.toByteArray();
    }

    // ==========================================
    // 6. 页码与页眉页脚压印 (Page Numbers)
    // ==========================================

    /**
     * 为 PDF 文档压印自定义格式页码（支持跳过封面与重算总页数）
     *
     * @param source  原始 PDF 输入源
     * @param target  目标输出
     * @param options 页码高级配置选项（包含格式、跳过页数、位置、字体与边距）
     * @throws PdfOperationException 页码压印失败时抛出
     */
    public void addPageNumbers(Object source, Object target, PageNumberOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        PageNumberOptions opts = options != null ? options : PageNumberOptions.builder().build();

        try (LoadedPdfDocument loadedPdf = loadDocument(source, opts.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, opts.getPassword(), "压印页码");
            int totalPages = doc.getNumberOfPages();
            if (totalPages > 0) {
                PDFont font = resolveFontForText(doc, opts.getCustomFontPath(), opts.getFormat(), false);
                Color color = parseColor(opts.getColor());
                int skipPages = Math.max(0, opts.getSkipPages());
                int effectiveTotal = opts.isRecalculateTotal() ? Math.max(1, totalPages - skipPages) : totalPages;

                for (int i = 0; i < totalPages; i++) {
                    if (i < skipPages) {
                        // 跳过封面与前置目录
                        continue;
                    }

                    int displayPage = (i - skipPages) + opts.getStartNumber();

                    String pageText = opts.getFormat()
                            .replace("{page}", String.valueOf(displayPage))
                            .replace("{total}", String.valueOf(effectiveTotal));

                    PDPage page = doc.getPage(i);
                    drawPageNumber(doc, page, pageText, font, opts, color);
                }
            }

            saveDocument(doc, target);
        } catch (DocumentException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "为 PDF 添加页码失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为 PDF 文档添加默认居中页码（第 X 页 / 共 Y 页）
     *
     * @param source 原始 PDF 输入源
     * @param target 目标输出
     */
    public void addPageNumbers(Object source, Object target) {
        addPageNumbers(source, target, PageNumberOptions.builder().build());
    }

    /**
     * 为 PDF 文档压印页码并导出为二进制字节数组
     *
     * @param source  原始 PDF 输入源
     * @param options 页码配置选项
     * @return 压印页码后的 PDF 二进制数据
     */
    public byte[] addPageNumbersToBytes(Object source, PageNumberOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        addPageNumbers(source, baos, options);
        return baos.toByteArray();
    }

    /**
     * 为 PDF 文档压印默认页码并导出为二进制字节数组
     *
     * @param source 原始 PDF 输入源
     * @return 压印页码后的 PDF 二进制数据
     */
    public byte[] addPageNumbersToBytes(Object source) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        addPageNumbers(source, baos);
        return baos.toByteArray();
    }

    private void drawPageNumber(PDDocument doc, PDPage page, String text, PDFont font,
                                PageNumberOptions opts, Color color) throws IOException {
        PDRectangle visibleBox = PdfRedactionSupport.resolveVisibleBox(page);
        int rotation = PdfRedactionSupport.normalizeRotation(page);
        PdfRedactionSupport.VisualSize visSize = PdfRedactionSupport.resolveVisualSize(visibleBox, rotation);
        float width = visSize.width();
        float height = visSize.height();
        float fontSize = opts.getFontSize();
        float margin = opts.getMargin();

        float textWidth = font.getStringWidth(text) / 1000.0f * fontSize;
        float x;
        float y;

        switch (opts.getPosition()) {
            case BOTTOM_LEFT -> {
                x = margin;
                y = margin;
            }
            case BOTTOM_RIGHT -> {
                x = width - margin - textWidth;
                y = margin;
            }
            case TOP_LEFT -> {
                x = margin;
                y = height - margin;
            }
            case TOP_CENTER -> {
                x = (width - textWidth) / 2.0f;
                y = height - margin;
            }
            case TOP_RIGHT -> {
                x = width - margin - textWidth;
                y = height - margin;
            }
            case BOTTOM_CENTER -> {
                x = (width - textWidth) / 2.0f;
                y = margin;
            }
            default -> {
                x = (width - textWidth) / 2.0f;
                y = margin;
            }
        }

        try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            cs.saveGraphicsState();
            PdfRedactionSupport.applyVisualCoordinateTransform(cs, visibleBox, rotation);
            cs.setNonStrokingColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f);
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(x, y);
            cs.showText(text);
            cs.endText();
            cs.restoreGraphicsState();
        }
    }

    // ==========================================
    // 7. 密码安全加密与复制/打印权限控制 (Security)
    // ==========================================

    /**
     * 为 PDF 文档施加密码安全加密与复制/打印权限控制锁
     *
     * @param source  原始 PDF 输入源
     * @param target  目标输出
     * @param options 安全权限配置选项（包含打开密码、管理密码及各项权限标志）
     * @throws PdfOperationException 施加安全控制失败时抛出
     */
    public void protect(Object source, Object target, SecurityOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        Objects.requireNonNull(options, "安全配置选项 options 不能为空");

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();

            AccessPermission permissions = new AccessPermission();
            permissions.setCanPrint(options.isCanPrint());
            permissions.setCanExtractContent(options.isCanCopy());
            permissions.setCanModify(options.isCanModify());
            permissions.setCanModifyAnnotations(options.isCanAnnotate());
            permissions.setCanFillInForm(options.isCanFillForms());

            String ownerPass = options.getOwnerPassword();
            String userPass = options.getUserPassword() != null ? options.getUserPassword() : "";
            if (ownerPass == null || ownerPass.isBlank()) {
                ownerPass = generateRandomOwnerPassword();
            }

            StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPass, userPass, permissions);
            policy.setEncryptionKeyLength(options.getKeyLength() > 0 ? options.getKeyLength() : 256);
            doc.protect(policy);

            saveDocument(doc, target, false);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "为 PDF 文档施加安全权限锁失败: " + e.getMessage(), e);
        }
    }

    /**
     * 便捷方法：为 PDF 文档施加打开密码与管理密码加密保护
     *
     * @param source        原始 PDF 输入源
     * @param target        目标输出
     * @param userPassword  打开密码
     * @param ownerPassword 管理密码
     */
    public void protect(Object source, Object target, String userPassword, String ownerPassword) {
        SecurityOptions options = SecurityOptions.withPasswords(userPassword, ownerPassword);
        protect(source, target, options);
    }

    /**
     * 为 PDF 文档施加密码安全保护并导出为二进制字节数组
     *
     * @param source  原始 PDF 输入源
     * @param options 安全权限配置选项
     * @return 加密后的 PDF 二进制数据
     */
    public byte[] protectToBytes(Object source, SecurityOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        protect(source, baos, options);
        return baos.toByteArray();
    }

    /**
     * 为 PDF 文档施加打开密码与管理密码并导出为二进制字节数组
     *
     * @param source        原始 PDF 输入源
     * @param userPassword  打开密码
     * @param ownerPassword 管理密码
     * @return 加密后的 PDF 二进制数据
     */
    public byte[] protectToBytes(Object source, String userPassword, String ownerPassword) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        protect(source, baos, userPassword, ownerPassword);
        return baos.toByteArray();
    }

    /**
     * 使用有效密码解密并移除 PDF 文档的全部安全限制（Unprotect）
     *
     * @param source   受保护的 PDF 输入源
     * @param target   目标输出
     * @param password 打开密码或管理密码
     * @throws PdfOperationException 密码错误或解密失败时抛出
     */
    public void unprotect(Object source, Object target, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        String pass = password != null ? password : "";

        try (LoadedPdfDocument loadedPdf = loadDocument(source, pass)) {
            PDDocument doc = loadedPdf.getDocument();
            saveDocument(doc, target, true);
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "解除 PDF 文档保护失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用有效密码解密并移除安全限制，导出为二进制字节数组
     *
     * @param source   受保护的 PDF 输入源
     * @param password 打开密码或管理密码
     * @return 解除保护后的 PDF 二进制数据
     */
    public byte[] unprotectToBytes(Object source, String password) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        unprotect(source, baos, password);
        return baos.toByteArray();
    }

    /**
     * 探测指定的 PDF 文档是否处于加密保护状态（缺失、超限或损坏文件将前置快速失败抛出 {@link PdfOperationException}）
     *
     * @param source PDF 输入源
     * @return 若已加密返回 true，未加密返回 false
     */
    public boolean isEncrypted(Object source) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            return loadedPdf.getDocument().isEncrypted();
        } catch (PdfOperationException e) {
            if (e.getResultCode() == DocumentResultCode.PASSWORD_ERROR) {
                return true;
            }
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED,
                    "探测 PDF 文档加密状态失败: " + e.getMessage(), e);
        }
    }

    private static String generateRandomOwnerPassword() {
        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    // ==========================================
    // 8. 交互式表单（AcroForm）数据填报与矢量扁平化防篡改 (Form Filling & Flattening)
    // ==========================================

    /**
     * 填充 PDF 交互式表单（AcroForm）并输出至目标位置（支持不可逆矢量扁平化与只读保护）
     *
     * @param source   原始 PDF 输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @param target   目标输出（支持 File, Path, OutputStream, WritableResource, String）
     * @param formData 表单填充键值对映射
     * @param options  表单填报与防篡改选项（为 null 时使用默认配置，默认执行矢量扁平化）
     * @throws PdfOperationException 表单不存在或填充操作失败时抛出
     */
    public void fillForm(Object source, Object target, Map<String, Object> formData, FormOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        FormOptions opts = options != null ? options : FormOptions.defaults();
        Map<String, Object> data = formData != null ? formData : Collections.emptyMap();

        try (LoadedPdfDocument loadedPdf = loadDocument(source, opts.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, opts.getPassword(), "填充表单");

            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                throw new PdfOperationException(DocumentResultCode.FORM_ERROR, "PDF 文档不包含 AcroForm 交互式表单");
            }
            checkXfaSupport(acroForm);

            // 填充表单字段
            fillAcroFormFields(doc, acroForm, data, opts);

            // 只读锁定策略
            applyFormReadOnlyPolicy(acroForm, opts);

            // 不可逆矢量扁平化防篡改
            if (opts.isFlatten()) {
                acroForm.flatten();
            }

            saveDocument(doc, target);
        } catch (DocumentException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.FORM_ERROR, "填充 PDF 表单失败: " + e.getMessage(), e);
        }
    }

    /**
     * 填充 PDF 交互式表单并输出至目标位置（默认开启不可逆矢量扁平化）
     *
     * @param source   原始 PDF 输入源
     * @param target   目标输出
     * @param formData 表单填充键值对映射
     */
    public void fillForm(Object source, Object target, Map<String, Object> formData) {
        fillForm(source, target, formData, FormOptions.defaults());
    }

    /**
     * 填充 PDF 交互式表单并导出为二进制字节数组
     *
     * @param source   原始 PDF 输入源
     * @param formData 表单填充键值对映射
     * @param options  表单填报与防篡改选项
     * @return 填充后的 PDF 二进制数据
     */
    public byte[] fillFormToBytes(Object source, Map<String, Object> formData, FormOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        fillForm(source, baos, formData, options);
        return baos.toByteArray();
    }

    /**
     * 填充 PDF 交互式表单并导出为二进制字节数组（默认开启不可逆矢量扁平化）
     *
     * @param source   原始 PDF 输入源
     * @param formData 表单填充键值对映射
     * @return 填充后的 PDF 二进制数据
     */
    public byte[] fillFormToBytes(Object source, Map<String, Object> formData) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        fillForm(source, baos, formData, FormOptions.defaults());
        return baos.toByteArray();
    }

    /**
     * 提取 PDF 文档中所有交互式表单域的名称及其当前值映射
     *
     * @param source PDF 输入源
     * @return 字段名称与当前值的键值映射（若文档不含表单则返回空 Map，绝不返回 null）
     */
    public Map<String, String> getFormFields(Object source) {
        return getFormFields(source, null);
    }

    /**
     * 提取 PDF 文档中所有交互式表单域的名称及其当前值映射（支持密码解密访问）
     *
     * @param source   PDF 输入源
     * @param password 打开密码（若无密码传 null）
     * @return 字段名称与当前值的键值映射（若文档不含表单则返回空 Map，绝不返回 null）
     */
    public Map<String, String> getFormFields(Object source, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                AccessPermission ap = doc.getCurrentAccessPermission();
                if (ap != null && !ap.canExtractContent() && !ap.canFillInForm()) {
                    throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密且无表单访问权限，请先提供密码解密");
                }
            }
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                return Collections.emptyMap();
            }
            checkXfaSupport(acroForm);
            Map<String, String> result = new LinkedHashMap<>();
            collectFields(acroForm.getFields(), result);
            return result;
        } catch (PdfOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.FORM_ERROR, "获取 PDF 表单字段失败: " + e.getMessage(), e);
        }
    }

    /**
     * 巡检并自省 PDF 文档中所有 AcroForm 交互式表单域元数据描述列表
     *
     * @param source 原始 PDF 输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @return 表单字段结构描述列表（若文档不含表单则返回空列表，绝不返回 null）
     * @throws PdfOperationException 表单读取异常或动态 XFA 拦截时抛出
     */
    public List<PdfFormFieldDescriptor> listFormFields(Object source) {
        return listFormFields(source, null);
    }

    /**
     * 巡检并自省 PDF 文档中所有 AcroForm 交互式表单域元数据描述列表（支持密码解密访问）
     *
     * @param source   原始 PDF 输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @param password 打开密码（若无密码传 null）
     * @return 表单字段结构描述列表（若文档不含表单则返回空列表，绝不返回 null）
     * @throws PdfOperationException 表单读取异常或动态 XFA 拦截时抛出
     */
    public List<PdfFormFieldDescriptor> listFormFields(Object source, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                AccessPermission ap = doc.getCurrentAccessPermission();
                if (ap != null && !ap.canExtractContent() && !ap.canFillInForm()) {
                    throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密且无表单访问权限，请先提供密码解密");
                }
            }
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                return Collections.emptyList();
            }
            checkXfaSupport(acroForm);
            if (acroForm.getFields().isEmpty()) {
                return Collections.emptyList();
            }
            List<PdfFormFieldDescriptor> result = new ArrayList<>();
            collectFormFieldDescriptors(acroForm.getFields(), result);
            return Collections.unmodifiableList(result);
        } catch (PdfOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.FORM_ERROR, "自省巡检 PDF 表单字段失败: " + e.getMessage(), e);
        }
    }

    /**
     * 探测指定的 PDF 文档是否包含 AcroForm 交互式表单
     *
     * @param source PDF 输入源
     * @return 若包含交互式表单且至少存在一个表单域返回 true，否则返回 false
     */
    public boolean hasAcroForm(Object source) {
        return hasAcroForm(source, null);
    }

    /**
     * 探测指定的 PDF 文档是否包含 AcroForm 交互式表单（支持密码解密访问）
     *
     * @param source   PDF 输入源
     * @param password 打开密码（若无密码传 null）
     * @return 若包含交互式表单且至少存在一个表单域返回 true，否则返回 false
     */
    public boolean hasAcroForm(Object source, String password) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source, password)) {
            PDDocument doc = loadedPdf.getDocument();
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            return acroForm != null && !acroForm.getFields().isEmpty();
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED,
                    "探测 PDF 交互式表单状态失败: " + e.getMessage(), e);
        }
    }

    private static class ChineseFontHolder {
        private PDFont font;
        private COSName fontName;
    }

    private void fillAcroFormFields(PDDocument doc, PDAcroForm acroForm, Map<String, Object> data, FormOptions opts) throws IOException {
        ChineseFontHolder fontHolder = new ChineseFontHolder();

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String fieldName = entry.getKey();
            if (fieldName == null) {
                continue;
            }
            PDField field = acroForm.getField(fieldName);
            if (field == null) {
                log.debug("PDF 表单域不存在，跳过填充: {}", fieldName);
                continue;
            }
            Object value = entry.getValue();
            setFieldValue(doc, acroForm, field, value, opts, fontHolder);
        }
    }

    private void setFieldValue(PDDocument doc, PDAcroForm acroForm, PDField field, Object val,
                               FormOptions opts, ChineseFontHolder fontHolder) throws IOException {
        if (field instanceof PDTextField textField) {
            String strVal = val != null ? String.valueOf(val) : "";
            if (!isWinAnsiCompatible(strVal)) {
                ensureChineseFontConfigured(doc, acroForm, textField, opts, strVal, fontHolder);
            }
            try {
                textField.setValue(strVal);
            } catch (IllegalArgumentException e) {
                // 若因编码问题抛出异常，强制适配中文字体后重试
                ensureChineseFontConfigured(doc, acroForm, textField, opts, strVal, fontHolder);
                if (fontHolder.fontName != null) {
                    float size = parseFontSize(textField.getDefaultAppearance(), 10.0f);
                    textField.setDefaultAppearance("/" + fontHolder.fontName.getName() + " " + size + " Tf 0 g");
                    textField.setValue(strVal);
                } else {
                    throw e;
                }
            }
        } else if (field instanceof PDCheckBox checkBox) {
            if (val instanceof Boolean b) {
                if (b) {
                    checkBox.check();
                } else {
                    checkBox.unCheck();
                }
            } else if (val != null) {
                String s = String.valueOf(val).trim();
                if ("true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s)
                        || s.equalsIgnoreCase(checkBox.getOnValue())) {
                    checkBox.check();
                } else {
                    checkBox.unCheck();
                }
            } else {
                checkBox.unCheck();
            }
        } else if (field instanceof PDRadioButton radioButton) {
            if (val != null) {
                radioButton.setValue(String.valueOf(val));
            }
        } else if (field instanceof PDChoice choice) {
            if (val instanceof List<?> list) {
                String joined = list.stream().map(String::valueOf).reduce("", String::concat);
                if (!isWinAnsiCompatible(joined)) {
                    ensureChineseFontConfigured(doc, acroForm, choice, opts, joined, fontHolder);
                }
                choice.setValue(list.stream().map(String::valueOf).toList());
            } else if (val != null) {
                String strVal = String.valueOf(val);
                if (!isWinAnsiCompatible(strVal)) {
                    ensureChineseFontConfigured(doc, acroForm, choice, opts, strVal, fontHolder);
                }
                choice.setValue(strVal);
            }
        } else {
            if (val != null) {
                field.setValue(String.valueOf(val));
            }
        }
    }

    private void ensureChineseFontConfigured(PDDocument doc, PDAcroForm acroForm, PDVariableText variableText,
                                             FormOptions opts, String sampleText, ChineseFontHolder fontHolder) {
        if (fontHolder.fontName == null) {
            PDResources resources = acroForm.getDefaultResources();
            if (resources == null) {
                resources = new PDResources();
                acroForm.setDefaultResources(resources);
            }
            PDFont font = resolveFontForText(doc, opts.getCustomFontPath(), sampleText, false);
            fontHolder.font = font;
            fontHolder.fontName = resources.add(font);
        }

        String da = variableText.getDefaultAppearance();
        float size = parseFontSize(da, 10.0f);
        variableText.setDefaultAppearance("/" + fontHolder.fontName.getName() + " " + size + " Tf 0 g");
    }

    private float parseFontSize(String da, float defaultSize) {
        if (da == null || da.trim().isEmpty()) {
            return defaultSize;
        }
        try {
            String[] parts = da.trim().split("\\s+");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("Tf".equalsIgnoreCase(parts[i + 1])) {
                    return Float.parseFloat(parts[i]);
                }
            }
        } catch (Exception ignored) {
        }
        return defaultSize;
    }

    private void applyFormReadOnlyPolicy(PDAcroForm acroForm, FormOptions opts) {
        if (opts.isReadOnly()) {
            setFieldsReadOnly(acroForm.getFields());
        } else if (opts.getReadOnlyFields() != null && !opts.getReadOnlyFields().isEmpty()) {
            for (String fieldName : opts.getReadOnlyFields()) {
                if (fieldName != null) {
                    PDField field = acroForm.getField(fieldName);
                    if (field != null) {
                        field.setReadOnly(true);
                    }
                }
            }
        }
    }

    private void setFieldsReadOnly(List<PDField> fields) {
        if (fields == null) {
            return;
        }
        for (PDField f : fields) {
            f.setReadOnly(true);
            if (f instanceof PDNonTerminalField ntf) {
                setFieldsReadOnly(ntf.getChildren());
            }
        }
    }

    private void collectFields(List<PDField> fields, Map<String, String> result) {
        if (fields == null) {
            return;
        }
        for (PDField f : fields) {
            if (f instanceof PDNonTerminalField ntf) {
                collectFields(ntf.getChildren(), result);
            } else {
                String name = f.getFullyQualifiedName();
                String val = f.getValueAsString();
                result.put(name, val != null ? val : "");
            }
        }
    }

    private void checkXfaSupport(PDAcroForm acroForm) {
        if (acroForm != null && (acroForm.hasXFA() || acroForm.getXFA() != null)) {
            throw new PdfOperationException(DocumentResultCode.PDF_XFA_UNSUPPORTED,
                    "检测到当前 PDF 包含动态 XFA (XML Forms Architecture) 表单，PDFBox 暂不支持动态 XFA 渲染与填报；"
                            + "请先在 Adobe Acrobat 中执行「打印为 PDF」或「转换为静态 AcroForm」后再行操作");
        }
    }

    private void collectFormFieldDescriptors(List<PDField> fields, List<PdfFormFieldDescriptor> result) {
        if (fields == null) {
            return;
        }
        for (PDField f : fields) {
            if (f instanceof PDNonTerminalField ntf) {
                collectFormFieldDescriptors(ntf.getChildren(), result);
            } else {
                PdfFormFieldDescriptor.FieldCategory category = resolveFieldCategory(f);
                String val = f.getValueAsString();
                String defaultVal = resolveDefaultValue(f);
                boolean multiSelect = (f instanceof PDChoice choice) && choice.isMultiSelect();
                PdfFormFieldDescriptor descriptor = PdfFormFieldDescriptor.builder()
                        .name(f.getPartialName())
                        .fullyQualifiedName(f.getFullyQualifiedName())
                        .fieldType(f.getFieldType())
                        .fieldCategory(category)
                        .value(val != null ? val : "")
                        .defaultValue(defaultVal != null ? defaultVal : "")
                        .readOnly(f.isReadOnly())
                        .required(f.isRequired())
                        .multiSelect(multiSelect)
                        .build();
                result.add(descriptor);
            }
        }
    }

    private String resolveDefaultValue(PDField field) {
        if (field == null || field.getCOSObject() == null) {
            return "";
        }
        COSBase dv = field.getCOSObject().getDictionaryObject(COSName.DV);
        if (dv == null) {
            return "";
        }
        if (dv instanceof COSString cosStr) {
            return cosStr.getString();
        } else if (dv instanceof COSName cosName) {
            return cosName.getName();
        } else if (dv instanceof COSArray cosArray) {
            List<String> list = new ArrayList<>();
            for (COSBase item : cosArray) {
                if (item instanceof COSString s) {
                    list.add(s.getString());
                } else if (item instanceof COSName n) {
                    list.add(n.getName());
                } else if (item != null) {
                    list.add(item.toString());
                }
            }
            return String.join(", ", list);
        }
        return dv.toString();
    }

    private PdfFormFieldDescriptor.FieldCategory resolveFieldCategory(PDField field) {
        if (field instanceof PDTextField) {
            return PdfFormFieldDescriptor.FieldCategory.TEXT;
        } else if (field instanceof PDCheckBox) {
            return PdfFormFieldDescriptor.FieldCategory.CHECKBOX;
        } else if (field instanceof PDRadioButton) {
            return PdfFormFieldDescriptor.FieldCategory.RADIO_BUTTON;
        } else if (field instanceof PDChoice) {
            return PdfFormFieldDescriptor.FieldCategory.CHOICE;
        } else if (field instanceof PDSignatureField) {
            return PdfFormFieldDescriptor.FieldCategory.SIGNATURE;
        } else {
            return PdfFormFieldDescriptor.FieldCategory.OTHER;
        }
    }

    private static boolean isWinAnsiCompatible(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint != '\r' && codePoint != '\n' && codePoint != '\t'
                    && !(codePoint >= 0x20 && codePoint <= 0x7E)
                    && !WinAnsiEncoding.INSTANCE.contains(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    // ==========================================
    // 9. PDF 页面转高清图像/缩略图与防 OOM 流式治理 (Page-to-Image Rendering)
    // ==========================================

    /**
     * 将 PDF 文档指定页面渲染为内存位图对象（BufferedImage）
     *
     * @param source    原始 PDF 输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @param pageIndex 页面索引（0-based，支持负数索引例如 -1 表示最后一页）
     * @param dpi       渲染分辨率 DPI（有效范围由 {@link DocumentPdfProperties#getMaxRenderDpi()} 约束）
     * @return 渲染出的 BufferedImage 对象
     * @throws PdfOperationException 索引越界、文档加密或渲染失败时抛出
     */
    public BufferedImage renderPageToImage(Object source, int pageIndex, float dpi) {
        validateDpi(dpi);
        return renderPageToImage(source, pageIndex, PdfRenderOptions.builder()
                .dpi(dpi)
                .maxPages(resolveDefaultMaxRenderPages())
                .build());
    }

    /**
     * 将 PDF 文档首页渲染为默认高清图像（BufferedImage，采用配置默认 DPI 与 RGB）
     *
     * @param source 原始 PDF 输入源
     * @return 渲染出的 BufferedImage 对象
     * @throws PdfOperationException 渲染失败或文档已加密时抛出
     */
    public BufferedImage renderPageToImage(Object source) {
        return renderPageToImage(source, 0, resolveDefaultRenderOptions());
    }

    /**
     * 将 PDF 文档指定页面渲染为内存位图对象（使用高级选项）
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @param options   渲染配置选项（为 null 时使用默认配置）
     * @return 渲染出的 BufferedImage 对象
     * @throws PdfOperationException 索引越界、文档加密或渲染失败时抛出
     */
    public BufferedImage renderPageToImage(Object source, int pageIndex, PdfRenderOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        PdfRenderOptions opts = options != null ? options : resolveDefaultRenderOptions();
        validateDpi(opts.getDpi());

        try (LoadedPdfDocument loadedPdf = loadDocument(source, opts.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, opts.getPassword(), "渲染页面图像");
            int totalPages = doc.getNumberOfPages();
            int actualIndex = pageIndex < 0 ? totalPages + pageIndex : pageIndex;
            if (actualIndex < 0 || actualIndex >= totalPages) {
                throw new PdfOperationException(DocumentResultCode.PDF_PAGE_INDEX_OUT_OF_BOUNDS,
                        String.format("PDF 页面索引越界 (总页数: %d, 请求页索引: %d)", totalPages, pageIndex));
            }

            PDFRenderer renderer = new PDFRenderer(doc);
            return renderer.renderImageWithDPI(actualIndex, opts.getDpi(), opts.getImageType());
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "渲染 PDF 页面图像失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 PDF 文档指定页面渲染为 PNG 图像字节数组（采用配置默认 DPI）
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @return PNG 图像二进制字节数组
     */
    public byte[] renderPageToPng(Object source, int pageIndex) {
        return renderPageToPng(source, pageIndex, null);
    }

    /**
     * 将 PDF 文档指定页面渲染为 PNG 图像字节数组（采用配置默认 DPI，支持密码解密）
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @param password  打开密码或管理密码（可选）
     * @return PNG 图像二进制字节数组
     */
    public byte[] renderPageToPng(Object source, int pageIndex, String password) {
        PdfRenderOptions opts = resolveDefaultRenderOptions();
        opts.setPassword(password);
        return renderPageToImageBytes(source, pageIndex, opts);
    }

    /**
     * 将 PDF 文档指定页面渲染为 PNG 图像字节数组
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @param dpi       渲染分辨率 DPI（有效范围 72.0f ~ maxRenderDpi）
     * @return PNG 图像二进制字节数组
     */
    public byte[] renderPageToPng(Object source, int pageIndex, float dpi) {
        return renderPageToPng(source, pageIndex, dpi, null);
    }

    /**
     * 将 PDF 文档指定页面渲染为 PNG 图像字节数组（支持密码解密）
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @param dpi       渲染分辨率 DPI（有效范围 72.0f ~ maxRenderDpi）
     * @param password  打开密码或管理密码（可选）
     * @return PNG 图像二进制字节数组
     */
    public byte[] renderPageToPng(Object source, int pageIndex, float dpi, String password) {
        validateDpi(dpi);
        return renderPageToImageBytes(source, pageIndex, PdfRenderOptions.builder()
                .dpi(dpi)
                .maxPages(resolveDefaultMaxRenderPages())
                .password(password)
                .build());
    }

    /**
     * 将 PDF 文档指定页面渲染为指定格式的图像字节数组
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @param options   渲染配置选项
     * @return 图像二进制字节数组
     */
    public byte[] renderPageToImageBytes(Object source, int pageIndex, PdfRenderOptions options) {
        PdfRenderOptions opts = options != null ? options : resolveDefaultRenderOptions();
        BufferedImage image = renderPageToImage(source, pageIndex, opts);
        try {
            return PdfRedactionSupport.toImageBytes(image, opts.getFormat());
        } catch (IOException e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "编码 PDF 页面图像字节数组失败: " + e.getMessage(), e);
        } finally {
            image.flush();
        }
    }

    /**
     * 将 PDF 文档首页渲染为默认高清图像字节数组
     *
     * @param source 原始 PDF 输入源
     * @return 图像二进制字节数组
     */
    public byte[] renderPageToBytes(Object source) {
        return renderPageToBytes(source, 0, resolveDefaultRenderOptions());
    }

    /**
     * 将 PDF 文档指定页面渲染为图像字节数组（别名对齐，等价于 renderPageToImageBytes）
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @param options   渲染配置选项
     * @return 图像二进制字节数组
     */
    public byte[] renderPageToBytes(Object source, int pageIndex, PdfRenderOptions options) {
        return renderPageToImageBytes(source, pageIndex, options);
    }

    /**
     * 将 PDF 文档所有页面批量渲染为 PNG 图像字节数组列表（继承全局默认 DPI 与最大页数上限）
     *
     * @param source 原始 PDF 输入源
     * @return 逐页 PNG 图像字节数组列表
     * @throws PdfOperationException 页数超限或渲染失败时抛出
     */
    public List<byte[]> renderAllPagesToPng(Object source) {
        return renderAllPagesToPng(source, null);
    }

    /**
     * 将 PDF 文档所有页面批量渲染为 PNG 图像字节数组列表（继承全局默认 DPI 与最大页数上限，支持密码解密）
     *
     * @param source   原始 PDF 输入源
     * @param password 打开密码或管理密码（可选）
     * @return 逐页 PNG 图像字节数组列表
     * @throws PdfOperationException 页数超限或渲染失败时抛出
     */
    public List<byte[]> renderAllPagesToPng(Object source, String password) {
        PdfRenderOptions opts = resolveDefaultRenderOptions();
        opts.setPassword(password);
        return renderAllPagesToBytes(source, opts);
    }

    /**
     * 将 PDF 文档所有页面批量渲染为 PNG 图像字节数组列表
     *
     * @param source 原始 PDF 输入源
     * @param dpi    渲染分辨率 DPI
     * @return 逐页 PNG 图像字节数组列表
     * @throws PdfOperationException 页数超限或渲染失败时抛出
     */
    public List<byte[]> renderAllPagesToPng(Object source, float dpi) {
        return renderAllPagesToPng(source, dpi, null);
    }

    /**
     * 将 PDF 文档所有页面批量渲染为 PNG 图像字节数组列表（支持密码解密）
     *
     * @param source   原始 PDF 输入源
     * @param dpi      渲染分辨率 DPI
     * @param password 打开密码或管理密码（可选）
     * @return 逐页 PNG 图像字节数组列表
     * @throws PdfOperationException 页数超限或渲染失败时抛出
     */
    public List<byte[]> renderAllPagesToPng(Object source, float dpi, String password) {
        validateDpi(dpi);
        return renderAllPagesToBytes(source, PdfRenderOptions.builder()
                .dpi(dpi)
                .maxPages(resolveDefaultMaxRenderPages())
                .password(password)
                .build());
    }

    /**
     * 将 PDF 文档所有页面批量渲染为图像字节数组列表（受 maxPages 安全上限守卫）
     *
     * @param source  原始 PDF 输入源
     * @param options 渲染配置选项（包含 maxPages 堆内存保护上限）
     * @return 逐页图像字节数组列表
     * @throws PdfOperationException 页数超限或渲染失败时抛出
     */
    public List<byte[]> renderAllPagesToBytes(Object source, PdfRenderOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        PdfRenderOptions opts = options != null ? options : resolveDefaultRenderOptions();
        validateDpi(opts.getDpi());

        try (LoadedPdfDocument loadedPdf = loadDocument(source, opts.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, opts.getPassword(), "渲染页面图像");
            int totalPages = doc.getNumberOfPages();
            if (totalPages > opts.getMaxPages()) {
                throw new PdfOperationException(DocumentResultCode.PDF_RENDER_EXCEEDED_MAX_PAGES,
                        String.format("PDF 页面渲染总数 (%d) 超过单次安全上限 (%d)，请改用流式分批接口 renderPages 防御堆内存溢出",
                                totalPages, opts.getMaxPages()));
            }

            PDFRenderer renderer = new PDFRenderer(doc);
            List<byte[]> result = new ArrayList<>(totalPages);
            for (int i = 0; i < totalPages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, opts.getDpi(), opts.getImageType());
                try {
                    result.add(PdfRedactionSupport.toImageBytes(image, opts.getFormat()));
                } finally {
                    image.flush();
                }
            }
            return result;
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "批量渲染 PDF 页面图像失败: " + e.getMessage(), e);
        }
    }

    /**
     * 全文档流式逐页渲染图像（每页回调后立即物理 flush 释放位图，防堆内存积压）
     *
     * @param source   原始 PDF 输入源
     * @param options  渲染配置选项
     * @param consumer 逐页位图消费回调函数
     */
    public void renderPages(Object source, PdfRenderOptions options, PageImageConsumer consumer) {
        renderPages(source, 1, Integer.MAX_VALUE, options, consumer);
    }

    /**
     * 按页码范围流式逐页渲染图像（每页回调后立即物理 flush 释放位图）
     *
     * @param source    原始 PDF 输入源
     * @param startPage 起始页码（1-based 物理页码）
     * @param endPage   结束页码（1-based 物理页码）
     * @param dpi       渲染分辨率 DPI
     * @param consumer  逐页位图消费回调函数
     */
    public void renderPages(Object source, int startPage, int endPage, float dpi, PageImageConsumer consumer) {
        validateDpi(dpi);
        renderPages(source, startPage, endPage, PdfRenderOptions.builder()
                .dpi(dpi)
                .maxPages(resolveDefaultMaxRenderPages())
                .build(), consumer);
    }

    /**
     * 按页码范围流式逐页渲染图像（每页回调后立即物理 flush 释放位图，防 OOM 核心通道）
     *
     * @param source    原始 PDF 输入源
     * @param startPage 起始页码（1-based 物理页码）
     * @param endPage   结束页码（1-based 物理页码）
     * @param options   渲染配置选项
     * @param consumer  逐页位图消费回调函数
     */
    public void renderPages(Object source, int startPage, int endPage, PdfRenderOptions options, PageImageConsumer consumer) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(consumer, "页面图像消费回调 consumer 不能为空");

        if (startPage < 1) {
            throw new IllegalArgumentException("起始页码 startPage 必须大于等于 1");
        }
        if (endPage < startPage) {
            throw new IllegalArgumentException(String.format("结束页码 endPage (%d) 不能小于起始页码 startPage (%d)", endPage, startPage));
        }

        PdfRenderOptions opts = options != null ? options : resolveDefaultRenderOptions();
        validateDpi(opts.getDpi());

        try (LoadedPdfDocument loadedPdf = loadDocument(source, opts.getPassword())) {
            PDDocument doc = loadedPdf.getDocument();
            ensureEditableUnlocked(doc, opts.getPassword(), "渲染页面图像");
            int totalPages = doc.getNumberOfPages();
            if (startPage > totalPages) {
                throw new PdfOperationException(DocumentResultCode.PDF_PAGE_INDEX_OUT_OF_BOUNDS,
                        String.format("起始页码 (%d) 超出文档总页数 (%d)", startPage, totalPages));
            }

            int effectiveEndPage = Math.min(endPage, totalPages);
            PDFRenderer renderer = new PDFRenderer(doc);

            for (int p = startPage; p <= effectiveEndPage; p++) {
                BufferedImage image = renderer.renderImageWithDPI(p - 1, opts.getDpi(), opts.getImageType());
                try {
                    consumer.accept(p, image);
                } catch (Exception e) {
                    throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED,
                            String.format("流式消费第 %d 页图像时发生异常: %s", p, e.getMessage()), e);
                } finally {
                    image.flush();
                }
            }
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "流式渲染 PDF 页面图像失败: " + e.getMessage(), e);
        }
    }

    private PdfRenderOptions resolveDefaultRenderOptions() {
        DocumentPdfProperties pdfProps = documentProperties.getPdf();
        float defaultDpi = pdfProps != null && pdfProps.getDefaultRenderDpi() >= 72.0f
                ? pdfProps.getDefaultRenderDpi()
                : 150.0f;
        return PdfRenderOptions.builder()
                .dpi(defaultDpi)
                .maxPages(resolveDefaultMaxRenderPages())
                .build();
    }

    private int resolveDefaultMaxRenderPages() {
        DocumentPdfProperties pdfProps = documentProperties.getPdf();
        return pdfProps != null && pdfProps.getDefaultMaxRenderPages() > 0
                ? pdfProps.getDefaultMaxRenderPages()
                : 50;
    }

    private float resolveMaxRenderDpi() {
        DocumentPdfProperties pdfProps = documentProperties.getPdf();
        return pdfProps != null && pdfProps.getMaxRenderDpi() >= 72.0f
                ? pdfProps.getMaxRenderDpi()
                : 600.0f;
    }

    private void validateDpi(float dpi) {
        PdfRenderOptions.validateDpi(dpi);
        float maxDpi = resolveMaxRenderDpi();
        if (dpi > maxDpi) {
            throw new IllegalArgumentException(
                    String.format("渲染 DPI 超出当前允许的安全上限 [72.0, %.1f]: %.1f", maxDpi, dpi));
        }
    }

    // ==========================================
    // 10. 10MB 阈值防 OOM 与临时文件物理清理核心逻辑
    // ==========================================

    private LoadedPdfDocument loadDocument(Object source) throws IOException {
        return loadDocument(source, null);
    }

    private long resolveMaxDocumentSizeBytes() {
        if (documentProperties.getMaxDocumentSize() != null
                && documentProperties.getMaxDocumentSize().toBytes() > 0) {
            return documentProperties.getMaxDocumentSize().toBytes();
        }
        return Long.MAX_VALUE;
    }

    private LoadedPdfDocument loadDocument(Object source, String password) throws IOException {
        long thresholdBytes = documentProperties.getInMemoryThreshold().toBytes();
        long maxDocumentBytes = resolveMaxDocumentSizeBytes();

        try {
            // 1. 本地物理文件
            if (source instanceof File file) {
                if (!file.exists() || !file.isFile() || !file.canRead()) {
                    throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "PDF 文件不存在或不可读: " + file.getAbsolutePath());
                }
                if (file.length() > maxDocumentBytes) {
                    throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                            "PDF 文件体积超出安全上限 (" + maxDocumentBytes + " bytes): " + file.length());
                }
                if (file.length() > thresholdBytes) {
                    // 超限直接使用 RandomAccessReadBufferedFile 缓冲文件模式
                    RandomAccessReadBufferedFile rar = new RandomAccessReadBufferedFile(file);
                    return new LoadedPdfDocument(password != null ? Loader.loadPDF(rar, password) : Loader.loadPDF(rar), null);
                } else {
                    return new LoadedPdfDocument(password != null ? Loader.loadPDF(file, password) : Loader.loadPDF(file), null);
                }
            }

            // 2. 本地 Path 路径
            if (source instanceof Path path) {
                return loadDocument(path.toFile(), password);
            }

            // 3. 字节数组
            if (source instanceof byte[] bytes) {
                if (bytes.length > maxDocumentBytes) {
                    throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                            "PDF 字节数组体积超出安全上限 (" + maxDocumentBytes + " bytes): " + bytes.length);
                }
                if (bytes.length > thresholdBytes) {
                    File tempFile = createTrackedTempFile("pdf-overflow-", ".pdf");
                    try {
                        Files.write(tempFile.toPath(), bytes);
                        RandomAccessReadBufferedFile rar = new RandomAccessReadBufferedFile(tempFile);
                        return new LoadedPdfDocument(password != null ? Loader.loadPDF(rar, password) : Loader.loadPDF(rar), tempFile);
                    } catch (Throwable t) {
                        if (tempFile.exists()) {
                            try {
                                tempFile.delete();
                            } catch (Exception ignored) {
                            }
                        }
                        throw t;
                    }
                } else {
                    RandomAccessReadBuffer rar = new RandomAccessReadBuffer(bytes);
                    return new LoadedPdfDocument(password != null ? Loader.loadPDF(rar, password) : Loader.loadPDF(rar), null);
                }
            }

            // 4. 输入流自适应读取与溢出防 OOM 控制
            if (source instanceof InputStream is) {
                return loadFromInputStream(is, thresholdBytes, maxDocumentBytes, password);
            }

            // 5. Spring Resource
            if (source instanceof Resource resource) {
                if (!resource.exists()) {
                    throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "PDF Spring Resource 不存在: " + resource.getDescription());
                }
                try {
                    File f = resource.getFile();
                    if (f.exists()) {
                        return loadDocument(f, password);
                    }
                } catch (Exception ignored) {
                    // 不支持 getFile 时转为 InputStream 处理
                }
                try (InputStream ris = resource.getInputStream()) {
                    return loadFromInputStream(ris, thresholdBytes, maxDocumentBytes, password);
                }
            }

            // 6. 字符串路径自适应解析
            if (source instanceof String str) {
                String path = str.trim();
                if (path.isEmpty()) {
                    throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "PDF 路径不能为空");
                }
                if (path.startsWith(CLASSPATH_PREFIX)) {
                    String cleanSub = FileUtil.cleanPath(path.substring(CLASSPATH_PREFIX.length()));
                    String sub = cleanSub.startsWith("/") ? cleanSub.substring(1) : cleanSub;
                    InputStream cis = getClass().getClassLoader().getResourceAsStream(sub);
                    if (cis == null) {
                        cis = Thread.currentThread().getContextClassLoader().getResourceAsStream(sub);
                    }
                    if (cis == null) {
                        throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "未找到指定的类路径 PDF 资源: " + path);
                    }
                    try (InputStream finalCis = cis) {
                        return loadFromInputStream(finalCis, thresholdBytes, maxDocumentBytes, password);
                    }
                }
                String cleanPath = FileUtil.cleanPath(path);
                File f = new File(cleanPath);
                if (f.exists() && f.isFile() && f.canRead()) {
                    return loadDocument(f, password);
                }
                throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "未定位到指定的 PDF 文件: " + path);
            }

            throw new IllegalArgumentException("不支持的 PDF 输入源类型: " + source.getClass().getName());
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档密码错误或未提供密码: " + e.getMessage(), e);
        }
    }

    private LoadedPdfDocument loadFromInputStream(InputStream is, long thresholdBytes) throws IOException {
        return loadFromInputStream(is, thresholdBytes, resolveMaxDocumentSizeBytes(), null);
    }

    private LoadedPdfDocument loadFromInputStream(InputStream is, long thresholdBytes,
                                                  long maxDocumentBytes, String password) throws IOException {
        ByteArrayOutputStream memoryBaos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        long totalRead = 0;
        File tempFile = null;
        FileOutputStream fos = null;
        boolean documentLoaded = false;

        try {
            while ((bytesRead = is.read(buffer)) != -1) {
                totalRead += bytesRead;
                if (totalRead > maxDocumentBytes) {
                    throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                            "PDF 输入流大小超出安全上限 (" + maxDocumentBytes + " bytes): " + totalRead);
                }
                if (tempFile == null && totalRead > thresholdBytes) {
                    // 超过阈值，立刻切换为临时文件磁盘缓存模式
                    tempFile = createTrackedTempFile("pdf-overflow-", ".pdf");
                    fos = new FileOutputStream(tempFile);
                    memoryBaos.writeTo(fos);
                    memoryBaos.reset();
                    fos.write(buffer, 0, bytesRead);
                } else if (tempFile != null) {
                    fos.write(buffer, 0, bytesRead);
                } else {
                    memoryBaos.write(buffer, 0, bytesRead);
                }
            }

            if (tempFile != null) {
                fos.flush();
                fos.close();
                fos = null;
                RandomAccessReadBufferedFile rar = new RandomAccessReadBufferedFile(tempFile);
                LoadedPdfDocument loadedDoc = new LoadedPdfDocument(
                        password != null ? Loader.loadPDF(rar, password) : Loader.loadPDF(rar), tempFile);
                documentLoaded = true;
                return loadedDoc;
            } else {
                byte[] inMemBytes = memoryBaos.toByteArray();
                RandomAccessReadBuffer rar = new RandomAccessReadBuffer(inMemBytes);
                LoadedPdfDocument loadedDoc = new LoadedPdfDocument(
                        password != null ? Loader.loadPDF(rar, password) : Loader.loadPDF(rar), null);
                documentLoaded = true;
                return loadedDoc;
            }
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
            if (!documentLoaded && tempFile != null && tempFile.exists()) {
                try {
                    tempFile.delete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private File createTrackedTempFile(String prefix, String suffix) throws IOException {
        String effectiveTempDir = documentProperties.resolveEffectiveTempDir();
        File dir = new File(effectiveTempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return File.createTempFile(prefix, suffix, dir);
    }

    private PDFont resolveFontForText(PDDocument doc, String customFontPath, String sampleText, boolean boldFallback) {
        if (customFontPath != null && !customFontPath.trim().isEmpty()) {
            return fontResolver.loadFont(doc, customFontPath.trim());
        }
        try {
            return fontResolver.loadFont(doc);
        } catch (DocumentFontException e) {
            if (isWinAnsiCompatible(sampleText)) {
                log.warn("未检测到可用 CJK 字体，但输出内容均为 ASCII/WinAnsi 字符，安全回退标准西文字体: {}", e.getMessage());
                return new PDType1Font(boldFallback ? Standard14Fonts.FontName.HELVETICA_BOLD : Standard14Fonts.FontName.HELVETICA);
            }
            throw e;
        }
    }

    private Color parseColor(String hex) {
        return parseColor(hex, Color.GRAY);
    }

    private Color parseColor(String hex, Color defaultColor) {
        if (hex == null || hex.trim().isEmpty()) {
            return defaultColor;
        }
        String clean = hex.trim();
        if (!clean.startsWith("#")) {
            clean = "#" + clean;
        }
        try {
            return Color.decode(clean);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法十六进制颜色值: " + hex, e);
        }
    }

    private byte[] resolveImageBytes(Object image) {
        long maxBytes = resolveMaxDocumentSizeBytes();
        if (image instanceof byte[] b) {
            if (b.length > maxBytes) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                        "印章图片大小超出安全上限 (" + maxBytes + " bytes): " + b.length);
            }
            return b;
        }
        if (image instanceof BufferedImage bi) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(bi, "PNG", baos);
                return baos.toByteArray();
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "转换 BufferedImage 为字节数组失败: " + e.getMessage(), e);
            }
        }
        if (image instanceof File f) {
            if (f.length() > maxBytes) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                        "印章图片文件大小超出安全上限 (" + maxBytes + " bytes): " + f.length());
            }
            try {
                return Files.readAllBytes(f.toPath());
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片文件失败: " + f.getAbsolutePath(), e);
            }
        }
        if (image instanceof Path p) {
            try {
                if (Files.size(p) > maxBytes) {
                    throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                            "印章图片 Path 大小超出安全上限 (" + maxBytes + " bytes)");
                }
                return Files.readAllBytes(p);
            } catch (PdfOperationException e) {
                throw e;
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片 Path 失败: " + p.toAbsolutePath(), e);
            }
        }
        if (image instanceof InputStream is) {
            try {
                return DocumentWebHelper.readBytesBounded(is, maxBytes);
            } catch (DocumentException e) {
                throw new PdfOperationException(e.getResultCode(), e.getMessage(), e);
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片流失败", e);
            }
        }
        if (image instanceof Resource res) {
            try (InputStream is = res.getInputStream()) {
                return DocumentWebHelper.readBytesBounded(is, maxBytes);
            } catch (DocumentException e) {
                throw new PdfOperationException(e.getResultCode(), e.getMessage(), e);
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片 Resource 失败", e);
            }
        }
        if (image instanceof String str) {
            String path = str.trim();
            if (path.startsWith(CLASSPATH_PREFIX)) {
                String cleanSub = FileUtil.cleanPath(path.substring(CLASSPATH_PREFIX.length()));
                String sub = cleanSub.startsWith("/") ? cleanSub.substring(1) : cleanSub;
                InputStream cis = getClass().getClassLoader().getResourceAsStream(sub);
                if (cis != null) {
                    try (cis) {
                        return DocumentWebHelper.readBytesBounded(cis, maxBytes);
                    } catch (DocumentException e) {
                        throw new PdfOperationException(e.getResultCode(), e.getMessage(), e);
                    } catch (IOException e) {
                        throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取类路径印章图片失败: " + path, e);
                    }
                }
            }
            String cleanPath = FileUtil.cleanPath(path);
            File f = new File(cleanPath);
            if (f.exists() && f.isFile()) {
                if (f.length() > maxBytes) {
                    throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                            "印章图片文件大小超出安全上限 (" + maxBytes + " bytes): " + f.length());
                }
                try {
                    return Files.readAllBytes(f.toPath());
                } catch (IOException e) {
                    throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片文件失败: " + f.getAbsolutePath(), e);
                }
            }
        }
        throw new IllegalArgumentException("不支持的印章图片输入类型: " + image.getClass().getName());
    }

    private OutputStream resolveOutputStream(Object target) {
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        if (target instanceof OutputStream os) {
            return os;
        }
        if (target instanceof File file) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try {
                return new FileOutputStream(file);
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "创建目标文件输出流失败: " + file.getAbsolutePath(), e);
            }
        }
        if (target instanceof Path path) {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "创建目标输出目录失败: " + parent, e);
                }
            }
            try {
                return Files.newOutputStream(path);
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "创建目标 Path 输出流失败: " + path, e);
            }
        }
        if (target instanceof WritableResource wr) {
            try {
                return wr.getOutputStream();
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "获取 WritableResource 输出流失败", e);
            }
        }
        if (target instanceof String str) {
            return resolveOutputStream(new File(FileUtil.cleanPath(str.trim())));
        }
        throw new IllegalArgumentException("不支持的目标输出类型: " + target.getClass().getName());
    }

    private void copySourceToTarget(Object source, Object target) {
        OutputStream os = resolveOutputStream(target);
        try (InputStream is = resolveRawInputStream(source)) {
            is.transferTo(os);
            os.flush();
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (DocumentException e) {
            throw new PdfOperationException(e.getResultCode(), e.getMessage(), e);
        } catch (IOException e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "复制源文件到目标失败: " + e.getMessage(), e);
        } finally {
            if (!(target instanceof OutputStream)) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private InputStream resolveRawInputStream(Object source) throws IOException {
        long maxBytes = resolveMaxDocumentSizeBytes();
        if (source instanceof byte[] b && b.length > maxBytes) {
            throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                    "PDF 字节数组体积超出安全上限 (" + maxBytes + " bytes): " + b.length);
        }
        if (source instanceof File f && f.length() > maxBytes) {
            throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                    "PDF 文件体积超出安全上限 (" + maxBytes + " bytes): " + f.length());
        }
        if (source instanceof Path p && Files.size(p) > maxBytes) {
            throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                    "PDF Path 文件体积超出安全上限 (" + maxBytes + " bytes)");
        }
        try {
            InputStream raw = DocumentWebHelper.resolveInputStream(source);
            return DocumentWebHelper.wrapBoundedInputStream(raw, maxBytes);
        } catch (FileNotFoundException e) {
            throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, e.getMessage(), e);
        }
    }

    private WatermarkOptions createDefaultWatermarkOptions() {
        DocumentWatermarkProperties wp = documentProperties.getWatermark();
        return WatermarkOptions.builder()
                .text(wp.getDefaultText())
                .opacity(wp.getOpacity())
                .rotation(wp.getRotation())
                .fontSize(wp.getFontSize())
                .color(wp.getColor())
                .layer(wp.getLayer())
                .rowSpacing(wp.getRowSpacing())
                .colSpacing(wp.getColSpacing())
                .build();
    }

    // ==========================================
    // 10. 跨格式转档桥接 (Word / HTML -> PDF via DocumentConverter)
    // ==========================================

    /**
     * 将 Word (.docx) 文档经由全局 {@link DocumentConverter} SPI 转换为 PDF 并输出至目标
     *
     * @param docxSource Word 文档输入源（支持 Resource, InputStream, File, Path, byte[], String 路径）
     * @param target     目标 PDF 输出对象
     */
    public void fromDocx(Object docxSource, Object target) {
        fromDocx(docxSource, target, null);
    }

    /**
     * 将 Word (.docx) 文档经由指定 {@link DocumentConverter} 转换为 PDF 并输出至目标
     *
     * @param docxSource Word 文档输入源
     * @param target     目标 PDF 输出对象
     * @param converter  自定义转换器（为 null 时使用默认注入的 SPI）
     */
    public void fromDocx(Object docxSource, Object target, DocumentConverter converter) {
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        byte[] pdfBytes = fromDocxToBytes(docxSource, converter);
        writeBytesToTarget(pdfBytes, target, "写出 DOCX 转档后的 PDF 失败");
    }

    /**
     * 将 Word (.docx) 文档经由全局 {@link DocumentConverter} SPI 转换为 PDF 字节数组
     *
     * @param docxSource Word 文档输入源
     * @return PDF 二进制字节数组
     */
    public byte[] fromDocxToBytes(Object docxSource) {
        return fromDocxToBytes(docxSource, null);
    }

    /**
     * 将 Word (.docx) 文档经由指定 {@link DocumentConverter} 转换为 PDF 字节数组
     *
     * @param docxSource Word 文档输入源
     * @param converter  自定义转换器（为 null 时使用默认注入的 SPI）
     * @return PDF 二进制字节数组
     */
    public byte[] fromDocxToBytes(Object docxSource, DocumentConverter converter) {
        return convertViaBridge(docxSource, converter, false);
    }

    /**
     * 将 HTML 内容或文件经由全局 {@link DocumentConverter} SPI 转换为 PDF 并输出至目标
     *
     * @param htmlSource HTML 输入源（支持原生 HTML 字符串、文件路径、classpath:、InputStream、byte[]、Resource）
     * @param target     目标 PDF 输出对象
     */
    public void fromHtml(Object htmlSource, Object target) {
        fromHtml(htmlSource, target, null);
    }

    /**
     * 将 HTML 内容或文件经由指定 {@link DocumentConverter} 转换为 PDF 并输出至目标
     *
     * @param htmlSource HTML 输入源
     * @param target     目标 PDF 输出对象
     * @param converter  自定义转换器（为 null 时使用默认注入的 SPI）
     */
    public void fromHtml(Object htmlSource, Object target, DocumentConverter converter) {
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        byte[] pdfBytes = fromHtmlToBytes(htmlSource, converter);
        writeBytesToTarget(pdfBytes, target, "写出 HTML 转档后的 PDF 失败");
    }

    /**
     * 将 HTML 内容或文件经由全局 {@link DocumentConverter} SPI 转换为 PDF 字节数组
     *
     * @param htmlSource HTML 输入源
     * @return PDF 二进制字节数组
     */
    public byte[] fromHtmlToBytes(Object htmlSource) {
        return fromHtmlToBytes(htmlSource, null);
    }

    /**
     * 将 HTML 内容或文件经由指定 {@link DocumentConverter} 转换为 PDF 字节数组
     *
     * @param htmlSource HTML 输入源
     * @param converter  自定义转换器（为 null 时使用默认注入的 SPI）
     * @return PDF 二进制字节数组
     */
    public byte[] fromHtmlToBytes(Object htmlSource, DocumentConverter converter) {
        return convertViaBridge(htmlSource, converter, true);
    }

    private byte[] convertViaBridge(Object inputSource, DocumentConverter customConverter, boolean htmlMode) {
        Objects.requireNonNull(inputSource, htmlMode ? "HTML 输入源 htmlSource 不能为空" : "Word 输入源 docxSource 不能为空");
        DocumentConverter effective = customConverter != null ? customConverter : this.documentConverter;
        if (effective == null) {
            throw new PdfOperationException(DocumentResultCode.CONVERSION_FAILED, "未配置可用的 DocumentConverter 转换器 SPI");
        }
        try (InputStream is = resolveBridgeInputStream(inputSource, htmlMode)) {
            byte[] converted = htmlMode ? effective.htmlToPdf(is) : effective.docxToPdf(is);
            if (converted == null || converted.length == 0) {
                throw new PdfOperationException(DocumentResultCode.CONVERSION_FAILED,
                        (htmlMode ? "HTML" : "DOCX") + " 转换引擎返回空 PDF 字节内容");
            }
            return converted;
        } catch (DocumentException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.CONVERSION_FAILED,
                    (htmlMode ? "HTML" : "DOCX") + " 转换 PDF 失败: " + e.getMessage(), e);
        }
    }

    private InputStream resolveBridgeInputStream(Object source, boolean htmlMode) throws IOException {
        if (htmlMode && source instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("HTML 内容不能为空");
            }
            if (trimmed.startsWith("<")) {
                byte[] htmlBytes = str.getBytes(StandardCharsets.UTF_8);
                long maxBytes = resolveMaxDocumentSizeBytes();
                if (htmlBytes.length > maxBytes) {
                    throw new PdfOperationException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                            "HTML 体积超出安全上限 (" + maxBytes + " bytes): " + htmlBytes.length);
                }
                return new ByteArrayInputStream(htmlBytes);
            }
        }
        return resolveRawInputStream(source);
    }

    private void writeBytesToTarget(byte[] data, Object target, String errorMessage) {
        OutputStream os = resolveOutputStream(target);
        try {
            os.write(data);
            os.flush();
        } catch (IOException e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, errorMessage + ": " + e.getMessage(), e);
        } finally {
            if (!(target instanceof OutputStream)) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public DocumentProperties getDocumentProperties() {
        return documentProperties;
    }

    public DocumentFontResolver getFontResolver() {
        return fontResolver;
    }

    public DocumentConverter getDocumentConverter() {
        return documentConverter;
    }

    /**
     * 持有 PDDocument 并在关闭时确保关联临时文件 100% 物理销毁的包装上下文
     */
    private static class LoadedPdfDocument implements AutoCloseable {
        private final PDDocument document;
        private final File tempFileToClean;

        public LoadedPdfDocument(PDDocument document, File tempFileToClean) {
            this.document = document;
            this.tempFileToClean = tempFileToClean;
        }

        public PDDocument getDocument() {
            return document;
        }

        @Override
        public void close() throws IOException {
            try {
                if (document != null) {
                    document.close();
                }
            } finally {
                if (tempFileToClean != null && tempFileToClean.exists()) {
                    try {
                        Files.deleteIfExists(tempFileToClean.toPath());
                    } catch (Exception e) {
                        tempFileToClean.deleteOnExit();
                    }
                }
            }
        }
    }
}
