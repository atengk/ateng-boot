package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.document.enums.PageNumberPosition;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.pdf.model.FormOptions;
import io.github.atengk.boot.document.pdf.model.ImageStampOptions;
import io.github.atengk.boot.document.pdf.model.PageImageConsumer;
import io.github.atengk.boot.document.pdf.model.PageNumberOptions;
import io.github.atengk.boot.document.pdf.model.PdfFormFieldDescriptor;
import io.github.atengk.boot.document.pdf.model.PdfMetadata;
import io.github.atengk.boot.document.pdf.model.PdfRenderOptions;
import io.github.atengk.boot.document.pdf.model.SecurityOptions;
import io.github.atengk.boot.document.pdf.model.WatermarkOptions;
import io.github.atengk.boot.document.properties.DocumentPdfProperties;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.properties.DocumentWatermarkProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
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
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    private final DocumentProperties documentProperties;
    private final DocumentFontResolver fontResolver;

    /**
     * 全参构造方法
     *
     * @param documentProperties 文档治理根配置属性
     * @param fontResolver       中文字体解析器
     */
    public PdfTemplate(DocumentProperties documentProperties, DocumentFontResolver fontResolver) {
        this.documentProperties = documentProperties != null ? documentProperties : new DocumentProperties();
        this.fontResolver = fontResolver != null ? fontResolver : new DocumentFontResolver(this.documentProperties.getPdf());
    }

    /**
     * 单参配置构造方法
     *
     * @param documentProperties 文档治理根配置属性
     */
    public PdfTemplate(DocumentProperties documentProperties) {
        this(documentProperties, null);
    }

    /**
     * 缺省无参构造方法
     */
    public PdfTemplate() {
        this(new DocumentProperties(), null);
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
     * 为 PDF 文档添加防泄密半透明平铺暗水印
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
        String parsedText = WatermarkMacroParser.parse(opts.getText(), opts.getVariables());
        if (parsedText == null || parsedText.trim().isEmpty()) {
            log.warn("水印文本解析结果为空，跳过水印绘制");
            copySourceToTarget(source, target);
            return;
        }

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请先解密后再施加水印");
            }

            PDFont font = resolveWatermarkFont(doc);
            Color parsedColor = parseColor(opts.getColor());

            // 遍历所有页面均匀绘制平铺网格
            for (PDPage page : doc.getPages()) {
                drawPageWatermark(doc, page, parsedText, font, opts, parsedColor);
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
        } catch (PdfOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "为 PDF 添加水印失败: " + e.getMessage(), e);
        }
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

    private void drawPageWatermark(PDDocument doc, PDPage page, String text, PDFont font,
                                   WatermarkOptions opts, Color color) throws IOException {
        PDRectangle mediaBox = page.getMediaBox();
        float width = mediaBox.getWidth();
        float height = mediaBox.getHeight();

        PDPageContentStream.AppendMode mode = opts.getLayer() == WatermarkLayer.UNDERLAY
                ? PDPageContentStream.AppendMode.PREPEND
                : PDPageContentStream.AppendMode.APPEND;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page, mode, true, true)) {
            // 设置透明度
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(opts.getOpacity());
            gs.setStrokingAlphaConstant(opts.getOpacity());
            cs.setGraphicsStateParameters(gs);

            // 设置颜色
            cs.setNonStrokingColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f);

            // 计算平铺网格坐标范围（外扩以保证倾斜旋转后四角无留白防截屏）
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
                    Matrix matrix = Matrix.getRotateInstance(angleRad, x, y);
                    cs.transform(matrix);
                    cs.beginText();
                    cs.setFont(font, opts.getFontSize());
                    cs.showText(text);
                    cs.endText();
                    cs.restoreGraphicsState();
                }
            }
        }
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

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请先解密后再盖章");
            }

            int totalPages = doc.getNumberOfPages();
            if (totalPages <= 0) {
                throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "PDF 文档页数为空");
            }

            int targetPageIndex = options.getPageIndex();
            if (targetPageIndex < 0) {
                // 支持负数索引，例如 -1 映射为最后一页
                targetPageIndex = totalPages + targetPageIndex;
            }

            if (targetPageIndex < 0 || targetPageIndex >= totalPages) {
                throw new IllegalArgumentException(String.format("目标页面索引超出范围 (总页数: %d, 请求页码: %d)",
                        totalPages, options.getPageIndex()));
            }

            PDPage page = doc.getPage(targetPageIndex);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, imageBytes, "stamp");

            float width = options.getWidth() > 0 ? options.getWidth() : pdImage.getWidth();
            float height = options.getHeight() > 0 ? options.getHeight() : pdImage.getHeight();

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

                cs.drawImage(pdImage, options.getX(), options.getY(), width, height);
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
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(startPage);
            stripper.setEndPage(endPage);
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
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
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
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        if (pdfSources == null || pdfSources.isEmpty()) {
            throw new IllegalArgumentException("待合并的 PDF 文档输入列表 pdfSources 不能为空");
        }

        try {
            PDFMergerUtility merger = new PDFMergerUtility();
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        merge(baos, pdfSources);
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
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(outputDir, "输出目录 outputDir 不能为空");

        File targetDir;
        if (outputDir instanceof File f) {
            targetDir = f;
        } else if (outputDir instanceof Path p) {
            targetDir = p.toFile();
        } else if (outputDir instanceof String s) {
            targetDir = new File(s.trim());
        } else {
            throw new IllegalArgumentException("不支持的输出目录类型: " + outputDir.getClass().getName());
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        String filePrefix = prefix != null && !prefix.trim().isEmpty() ? prefix.trim() : "split_";
        List<File> generatedFiles = new ArrayList<>();

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument srcDoc = loadedPdf.getDocument();
            Splitter splitter = new Splitter();
            splitter.setSplitAtPage(1); // 逐页拆分
            List<PDDocument> pages = splitter.split(srcDoc);

            try {
                for (int i = 0; i < pages.size(); i++) {
                    PDDocument pageDoc = pages.get(i);
                    File outFile = new File(targetDir, filePrefix + (i + 1) + ".pdf");
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
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        if (splitAtPage < 1) {
            throw new IllegalArgumentException("拆分页数 splitAtPage 必须大于等于 1");
        }
        List<byte[]> result = new ArrayList<>();
        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument srcDoc = loadedPdf.getDocument();
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
        if (fromPage < 1 || toPage < fromPage) {
            throw new IllegalArgumentException(String.format("页码范围无效 (fromPage: %d, toPage: %d)", fromPage, toPage));
        }
        int[] indexes = new int[toPage - fromPage + 1];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = fromPage - 1 + i;
        }
        extractPages(source, target, indexes);
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
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        Objects.requireNonNull(pageIndexes, "待提取的页面索引列表不能为空");
        if (pageIndexes.length == 0) {
            throw new IllegalArgumentException("待提取的页面索引列表不能为空");
        }

        try (LoadedPdfDocument loadedPdf = loadDocument(source);
             PDDocument destDoc = new PDDocument()) {

            PDDocument srcDoc = loadedPdf.getDocument();
            int totalPages = srcDoc.getNumberOfPages();

            for (int rawIndex : pageIndexes) {
                int actualIndex = rawIndex < 0 ? totalPages + rawIndex : rawIndex;
                if (actualIndex < 0 || actualIndex >= totalPages) {
                    throw new IllegalArgumentException(String.format("待提取的页面索引超出范围 (总页数: %d, 请求页码: %d)",
                            totalPages, rawIndex));
                }
                destDoc.importPage(srcDoc.getPage(actualIndex));
            }

            OutputStream os = resolveOutputStream(target);
            try {
                destDoc.save(os);
                os.flush();
            } finally {
                if (!(target instanceof OutputStream)) {
                    os.close();
                }
            }
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        extractPageRange(source, baos, fromPage, toPage);
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        extractPages(source, baos, pageIndexes);
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
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        if (angle % 90 != 0) {
            throw new IllegalArgumentException("旋转角度 angle 必须为 90 的整数倍 (如 90, 180, 270): " + angle);
        }

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            int totalPages = doc.getNumberOfPages();

            if (pageIndexes == null || pageIndexes.length == 0) {
                // 旋转全部页面
                for (PDPage page : doc.getPages()) {
                    int currentRotation = page.getRotation();
                    page.setRotation((currentRotation + angle + 360) % 360);
                }
            } else {
                // 旋转特定指定页面
                for (int rawIndex : pageIndexes) {
                    int actualIndex = rawIndex < 0 ? totalPages + rawIndex : rawIndex;
                    if (actualIndex < 0 || actualIndex >= totalPages) {
                        throw new IllegalArgumentException(String.format("待旋转的页面索引超出范围 (总页数: %d, 请求页码: %d)",
                                totalPages, rawIndex));
                    }
                    PDPage page = doc.getPage(actualIndex);
                    int currentRotation = page.getRotation();
                    page.setRotation((currentRotation + angle + 360) % 360);
                }
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        rotatePages(source, baos, angle, pageIndexes);
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

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            int totalPages = doc.getNumberOfPages();
            if (totalPages <= 0) {
                copySourceToTarget(source, target);
                return;
            }

            PDFont font = resolvePageNumberFont(doc, opts.getCustomFontPath());
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

            OutputStream os = resolveOutputStream(target);
            try {
                doc.save(os);
                os.flush();
            } finally {
                if (!(target instanceof OutputStream)) {
                    os.close();
                }
            }
        } catch (PdfOperationException | IllegalArgumentException e) {
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
        PDRectangle mediaBox = page.getMediaBox();
        float width = mediaBox.getWidth();
        float height = mediaBox.getHeight();
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
            cs.setNonStrokingColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f);
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(x, y);
            cs.showText(text);
            cs.endText();
        }
    }

    private PDFont resolvePageNumberFont(PDDocument doc, String customFontPath) {
        if (customFontPath != null && !customFontPath.trim().isEmpty()) {
            try {
                return fontResolver.loadFont(doc, customFontPath.trim());
            } catch (Exception e) {
                log.warn("通过自定义路径加载页码字体失败: {}", e.getMessage());
            }
        }
        try {
            return fontResolver.loadFont(doc);
        } catch (Exception e) {
            return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
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
            if (ownerPass == null || ownerPass.isEmpty()) {
                ownerPass = userPass.isEmpty() ? "AtengDocSecurityOwnerPass#2026" : userPass + "_owner";
            }

            StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPass, userPass, permissions);
            policy.setEncryptionKeyLength(options.getKeyLength() > 0 ? options.getKeyLength() : 256);
            doc.protect(policy);

            OutputStream os = resolveOutputStream(target);
            try {
                doc.save(os);
                os.flush();
            } finally {
                if (!(target instanceof OutputStream)) {
                    os.close();
                }
            }
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
            doc.setAllSecurityToBeRemoved(true);
            OutputStream os = resolveOutputStream(target);
            try {
                doc.save(os);
                os.flush();
            } finally {
                if (!(target instanceof OutputStream)) {
                    os.close();
                }
            }
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
     * 探测指定的 PDF 文档是否处于加密保护状态
     *
     * @param source PDF 输入源
     * @return 若已加密返回 true，未加密返回 false
     */
    public boolean isEncrypted(Object source) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            return loadedPdf.getDocument().isEncrypted();
        } catch (Exception e) {
            return true;
        }
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

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请先解密后再填充表单");
            }

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

            OutputStream os = resolveOutputStream(target);
            try {
                doc.save(os);
                os.flush();
            } finally {
                if (!(target instanceof OutputStream)) {
                    os.close();
                }
            }
        } catch (PdfOperationException | IllegalArgumentException e) {
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
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请先解密后再获取表单字段");
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
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请先解密后再获取表单字段元数据");
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
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            return acroForm != null && !acroForm.getFields().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void fillAcroFormFields(PDDocument doc, PDAcroForm acroForm, Map<String, Object> data, FormOptions opts) throws IOException {
        PDFont[] cachedChineseFont = new PDFont[1];
        COSName[] cachedFontName = new COSName[1];

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
            setFieldValue(doc, acroForm, field, value, opts, cachedChineseFont, cachedFontName);
        }
    }

    private void setFieldValue(PDDocument doc, PDAcroForm acroForm, PDField field, Object val,
                               FormOptions opts, PDFont[] cachedChineseFont, COSName[] cachedFontName) throws IOException {
        if (field instanceof PDTextField textField) {
            String strVal = val != null ? String.valueOf(val) : "";
            if (containsChinese(strVal)) {
                ensureChineseFontConfigured(doc, acroForm, textField, opts, cachedChineseFont, cachedFontName);
            }
            try {
                textField.setValue(strVal);
            } catch (IllegalArgumentException e) {
                // 若因编码问题抛出异常，强制适配中文字体后重试
                ensureChineseFontConfigured(doc, acroForm, textField, opts, cachedChineseFont, cachedFontName);
                if (cachedFontName[0] != null) {
                    float size = parseFontSize(textField.getDefaultAppearance(), 10.0f);
                    textField.setDefaultAppearance("/" + cachedFontName[0].getName() + " " + size + " Tf 0 g");
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
                boolean hasCjk = list.stream().anyMatch(item -> containsChinese(String.valueOf(item)));
                if (hasCjk) {
                    ensureChineseFontConfigured(doc, acroForm, choice, opts, cachedChineseFont, cachedFontName);
                }
                choice.setValue(list.stream().map(String::valueOf).toList());
            } else if (val != null) {
                String strVal = String.valueOf(val);
                if (containsChinese(strVal)) {
                    ensureChineseFontConfigured(doc, acroForm, choice, opts, cachedChineseFont, cachedFontName);
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
                                             FormOptions opts, PDFont[] cachedChineseFont, COSName[] cachedFontName) {
        if (cachedFontName[0] == null) {
            PDResources resources = acroForm.getDefaultResources();
            if (resources == null) {
                resources = new PDResources();
                acroForm.setDefaultResources(resources);
            }
            PDFont font = resolveFormFont(doc, opts.getCustomFontPath());
            cachedChineseFont[0] = font;
            cachedFontName[0] = resources.add(font);
        }

        String da = variableText.getDefaultAppearance();
        float size = parseFontSize(da, 10.0f);
        variableText.setDefaultAppearance("/" + cachedFontName[0].getName() + " " + size + " Tf 0 g");
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

    private PDFont resolveFormFont(PDDocument doc, String customFontPath) {
        if (customFontPath != null && !customFontPath.trim().isEmpty()) {
            try {
                return fontResolver.loadFont(doc, customFontPath.trim());
            } catch (Exception e) {
                log.warn("通过自定义路径加载表单字体失败: {}", e.getMessage());
            }
        }
        try {
            return fontResolver.loadFont(doc);
        } catch (Exception e) {
            log.warn("解析中文字体失败，表单回退至 Helvetica: {}", e.getMessage());
            return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        }
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
        if (acroForm != null && acroForm.hasXFA()) {
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
                PdfFormFieldDescriptor descriptor = PdfFormFieldDescriptor.builder()
                        .name(f.getPartialName())
                        .fullyQualifiedName(f.getFullyQualifiedName())
                        .fieldType(f.getFieldType())
                        .fieldCategory(category)
                        .value(val != null ? val : "")
                        .readOnly(f.isReadOnly())
                        .required(f.isRequired())
                        .build();
                result.add(descriptor);
            }
        }
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

    private static boolean containsChinese(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                    || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    // ==========================================
    // 9. PDF 页面转高清图像/缩略图与防 OOM 流式治理 (Page-to-Image Rendering)
    // ==========================================

    /**
     * 将 PDF 文档指定页面渲染为内存位图对象（BufferedImage）
     *
     * @param source    原始 PDF 输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @param pageIndex 页面索引（0-based，支持负数索引例如 -1 表示最后一页）
     * @param dpi       渲染分辨率 DPI（有效范围 72.0f ~ 600.0f）
     * @return 渲染出的 BufferedImage 对象
     * @throws PdfOperationException 索引越界、文档加密或渲染失败时抛出
     */
    public BufferedImage renderPageToImage(Object source, int pageIndex, float dpi) {
        return renderPageToImage(source, pageIndex, PdfRenderOptions.ofDpi(dpi));
    }

    /**
     * 将 PDF 文档指定页面渲染为内存位图对象（使用高级选项）
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @param options   渲染配置选项（为 null 时使用默认 150 DPI RGB）
     * @return 渲染出的 BufferedImage 对象
     * @throws PdfOperationException 索引越界、文档加密或渲染失败时抛出
     */
    public BufferedImage renderPageToImage(Object source, int pageIndex, PdfRenderOptions options) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        PdfRenderOptions opts = options != null ? options : PdfRenderOptions.defaults();
        validateDpi(opts.getDpi());

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请先解密后再渲染页面图像");
            }
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
     * 将 PDF 文档指定页面渲染为 PNG 图像字节数组（使用默认 150 DPI）
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @return PNG 图像二进制字节数组
     */
    public byte[] renderPageToPng(Object source, int pageIndex) {
        return renderPageToPng(source, pageIndex, 150.0f);
    }

    /**
     * 将 PDF 文档指定页面渲染为 PNG 图像字节数组
     *
     * @param source    原始 PDF 输入源
     * @param pageIndex 页面索引（0-based，支持负数索引）
     * @param dpi       渲染分辨率 DPI（有效范围 72.0f ~ 600.0f）
     * @return PNG 图像二进制字节数组
     */
    public byte[] renderPageToPng(Object source, int pageIndex, float dpi) {
        return renderPageToImageBytes(source, pageIndex, PdfRenderOptions.ofDpi(dpi));
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
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        PdfRenderOptions opts = options != null ? options : PdfRenderOptions.defaults();
        validateDpi(opts.getDpi());

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请先解密后再渲染页面图像");
            }
            int totalPages = doc.getNumberOfPages();
            int actualIndex = pageIndex < 0 ? totalPages + pageIndex : pageIndex;
            if (actualIndex < 0 || actualIndex >= totalPages) {
                throw new PdfOperationException(DocumentResultCode.PDF_PAGE_INDEX_OUT_OF_BOUNDS,
                        String.format("PDF 页面索引越界 (总页数: %d, 请求页索引: %d)", totalPages, pageIndex));
            }

            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImageWithDPI(actualIndex, opts.getDpi(), opts.getImageType());
            try {
                return toImageBytes(image, opts.getFormat());
            } finally {
                image.flush();
            }
        } catch (PdfOperationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "渲染 PDF 页面图像字节数组失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 PDF 文档所有页面批量渲染为 PNG 图像字节数组列表（默认 150 DPI，上限 50 页）
     *
     * @param source 原始 PDF 输入源
     * @return 逐页 PNG 图像字节数组列表
     * @throws PdfOperationException 页数超限或渲染失败时抛出
     */
    public List<byte[]> renderAllPagesToPng(Object source) {
        return renderAllPagesToPng(source, 150.0f);
    }

    /**
     * 将 PDF 文档所有页面批量渲染为 PNG 图像字节数组列表（上限 50 页）
     *
     * @param source 原始 PDF 输入源
     * @param dpi    渲染分辨率 DPI（有效范围 72.0f ~ 600.0f）
     * @return 逐页 PNG 图像字节数组列表
     * @throws PdfOperationException 页数超限或渲染失败时抛出
     */
    public List<byte[]> renderAllPagesToPng(Object source, float dpi) {
        return renderAllPagesToBytes(source, PdfRenderOptions.ofDpi(dpi));
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
        PdfRenderOptions opts = options != null ? options : PdfRenderOptions.defaults();
        validateDpi(opts.getDpi());

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请先解密后再渲染页面图像");
            }
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
                    result.add(toImageBytes(image, opts.getFormat()));
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
     * @param dpi       渲染分辨率 DPI（有效范围 72.0f ~ 600.0f）
     * @param consumer  逐页位图消费回调函数
     */
    public void renderPages(Object source, int startPage, int endPage, float dpi, PageImageConsumer consumer) {
        renderPages(source, startPage, endPage, PdfRenderOptions.ofDpi(dpi), consumer);
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

        PdfRenderOptions opts = options != null ? options : PdfRenderOptions.defaults();
        validateDpi(opts.getDpi());

        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDDocument doc = loadedPdf.getDocument();
            if (doc.isEncrypted()) {
                throw new PdfOperationException(DocumentResultCode.PASSWORD_ERROR, "PDF 文档已加密，请先解密后再渲染页面图像");
            }
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

    private static void validateDpi(float dpi) {
        if (dpi < 72.0f || dpi > 600.0f) {
            throw new IllegalArgumentException(String.format("渲染 DPI 超出安全范围 [72.0, 600.0]: %.1f", dpi));
        }
    }

    private static byte[] toImageBytes(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String fmt = format != null && !format.trim().isEmpty() ? format.trim() : "PNG";
        boolean written = ImageIO.write(image, fmt, baos);
        if (!written) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "不支持的图像输出格式: " + fmt);
        }
        return baos.toByteArray();
    }

    // ==========================================
    // 10. 10MB 阈值防 OOM 与临时文件物理清理核心逻辑
    // ==========================================

    private LoadedPdfDocument loadDocument(Object source) throws IOException {
        return loadDocument(source, null);
    }

    private LoadedPdfDocument loadDocument(Object source, String password) throws IOException {
        long thresholdBytes = documentProperties.getInMemoryThreshold().toBytes();

        try {
            // 1. 本地物理文件
            if (source instanceof File file) {
                if (!file.exists() || !file.isFile() || !file.canRead()) {
                    throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "PDF 文件不存在或不可读: " + file.getAbsolutePath());
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
                return loadFromInputStream(is, thresholdBytes, password);
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
                    return loadFromInputStream(ris, thresholdBytes, password);
                }
            }

            // 6. 字符串路径自适应解析
            if (source instanceof String str) {
                String path = str.trim();
                if (path.isEmpty()) {
                    throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "PDF 路径不能为空");
                }
                if (path.startsWith(CLASSPATH_PREFIX)) {
                    String sub = path.substring(CLASSPATH_PREFIX.length());
                    if (sub.startsWith("/")) {
                        sub = sub.substring(1);
                    }
                    InputStream cis = getClass().getClassLoader().getResourceAsStream(sub);
                    if (cis == null) {
                        cis = Thread.currentThread().getContextClassLoader().getResourceAsStream(sub);
                    }
                    if (cis == null) {
                        throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "未找到指定的类路径 PDF 资源: " + path);
                    }
                    try (InputStream finalCis = cis) {
                        return loadFromInputStream(finalCis, thresholdBytes, password);
                    }
                }
                File f = new File(path);
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
        return loadFromInputStream(is, thresholdBytes, null);
    }

    private LoadedPdfDocument loadFromInputStream(InputStream is, long thresholdBytes, String password) throws IOException {
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

    private PDFont resolveWatermarkFont(PDDocument doc) {
        try {
            return fontResolver.loadFont(doc);
        } catch (Exception e) {
            log.warn("使用 DocumentFontResolver 加载中文字体失败，尝试回退系统默认字体: {}", e.getMessage());
            try {
                return new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD);
            } catch (Exception ex) {
                throw new PdfOperationException(DocumentResultCode.FONT_NOT_FOUND, "无法加载可用字体: " + ex.getMessage(), ex);
            }
        }
    }

    private Color parseColor(String hex) {
        if (hex == null || hex.trim().isEmpty()) {
            return Color.GRAY;
        }
        try {
            String clean = hex.trim();
            if (!clean.startsWith("#")) {
                clean = "#" + clean;
            }
            return Color.decode(clean);
        } catch (Exception e) {
            return Color.GRAY;
        }
    }

    private byte[] resolveImageBytes(Object image) {
        if (image instanceof byte[] b) {
            return b;
        }
        if (image instanceof File f) {
            try {
                return Files.readAllBytes(f.toPath());
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片文件失败: " + f.getAbsolutePath(), e);
            }
        }
        if (image instanceof Path p) {
            try {
                return Files.readAllBytes(p);
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片 Path 失败: " + p.toAbsolutePath(), e);
            }
        }
        if (image instanceof InputStream is) {
            try {
                return is.readAllBytes();
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片流失败", e);
            }
        }
        if (image instanceof Resource res) {
            try (InputStream is = res.getInputStream()) {
                return is.readAllBytes();
            } catch (IOException e) {
                throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取印章图片 Resource 失败", e);
            }
        }
        if (image instanceof String str) {
            String path = str.trim();
            if (path.startsWith(CLASSPATH_PREFIX)) {
                String sub = path.substring(CLASSPATH_PREFIX.length());
                if (sub.startsWith("/")) {
                    sub = sub.substring(1);
                }
                InputStream cis = getClass().getClassLoader().getResourceAsStream(sub);
                if (cis != null) {
                    try (cis) {
                        return cis.readAllBytes();
                    } catch (IOException e) {
                        throw new PdfOperationException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "读取类路径印章图片失败: " + path, e);
                    }
                }
            }
            File f = new File(path);
            if (f.exists() && f.isFile()) {
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
            return resolveOutputStream(new File(str.trim()));
        }
        throw new IllegalArgumentException("不支持的目标输出类型: " + target.getClass().getName());
    }

    private void copySourceToTarget(Object source, Object target) {
        OutputStream os = resolveOutputStream(target);
        try (InputStream is = resolveRawInputStream(source)) {
            is.transferTo(os);
            os.flush();
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
        if (source instanceof InputStream is) {
            return is;
        }
        if (source instanceof byte[] b) {
            return new ByteArrayInputStream(b);
        }
        if (source instanceof File f) {
            return new FileInputStream(f);
        }
        if (source instanceof Path p) {
            return Files.newInputStream(p);
        }
        if (source instanceof Resource r) {
            return r.getInputStream();
        }
        if (source instanceof String s) {
            return new FileInputStream(s.trim());
        }
        throw new IllegalArgumentException("不支持的输入源: " + source.getClass().getName());
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

    public DocumentProperties getDocumentProperties() {
        return documentProperties;
    }

    public DocumentFontResolver getFontResolver() {
        return fontResolver;
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
