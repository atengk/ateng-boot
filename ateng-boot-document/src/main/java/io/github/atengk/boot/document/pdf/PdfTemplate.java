package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.document.enums.WatermarkLayer;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.pdf.model.ImageStampOptions;
import io.github.atengk.boot.document.pdf.model.WatermarkOptions;
import io.github.atengk.boot.document.properties.DocumentPdfProperties;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.properties.DocumentWatermarkProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.WritableResource;

import java.awt.Color;
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
import java.util.Objects;

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
     * @param source PDF 输入源
     * @return 全文文本内容
     */
    public String extractText(Object source) {
        Objects.requireNonNull(source, "PDF 输入源 source 不能为空");
        try (LoadedPdfDocument loadedPdf = loadDocument(source)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(loadedPdf.getDocument());
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
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "获取 PDF 页数失败: " + e.getMessage(), e);
        }
    }

    // ==========================================
    // 4. 10MB 阈值防 OOM 与临时文件物理清理核心逻辑
    // ==========================================

    private LoadedPdfDocument loadDocument(Object source) throws IOException {
        long thresholdBytes = documentProperties.getInMemoryThreshold().toBytes();

        // 1. 本地物理文件
        if (source instanceof File file) {
            if (!file.exists() || !file.isFile() || !file.canRead()) {
                throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "PDF 文件不存在或不可读: " + file.getAbsolutePath());
            }
            if (file.length() > thresholdBytes) {
                // 超限直接使用 RandomAccessReadBufferedFile 缓冲文件模式
                return new LoadedPdfDocument(Loader.loadPDF(new RandomAccessReadBufferedFile(file)), null);
            } else {
                return new LoadedPdfDocument(Loader.loadPDF(file), null);
            }
        }

        // 2. 本地 Path 路径
        if (source instanceof Path path) {
            return loadDocument(path.toFile());
        }

        // 3. 字节数组
        if (source instanceof byte[] bytes) {
            if (bytes.length > thresholdBytes) {
                File tempFile = createTrackedTempFile("pdf-overflow-", ".pdf");
                Files.write(tempFile.toPath(), bytes);
                return new LoadedPdfDocument(Loader.loadPDF(new RandomAccessReadBufferedFile(tempFile)), tempFile);
            } else {
                return new LoadedPdfDocument(Loader.loadPDF(new RandomAccessReadBuffer(bytes)), null);
            }
        }

        // 4. 输入流自适应读取与溢出防 OOM 控制
        if (source instanceof InputStream is) {
            return loadFromInputStream(is, thresholdBytes);
        }

        // 5. Spring Resource
        if (source instanceof Resource resource) {
            if (!resource.exists()) {
                throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "PDF Spring Resource 不存在: " + resource.getDescription());
            }
            try {
                File f = resource.getFile();
                if (f.exists()) {
                    return loadDocument(f);
                }
            } catch (Exception ignored) {
                // 不支持 getFile 时转为 InputStream 处理
            }
            try (InputStream ris = resource.getInputStream()) {
                return loadFromInputStream(ris, thresholdBytes);
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
                    return loadFromInputStream(finalCis, thresholdBytes);
                }
            }
            File f = new File(path);
            if (f.exists() && f.isFile() && f.canRead()) {
                return loadDocument(f);
            }
            throw new PdfOperationException(DocumentResultCode.TEMPLATE_NOT_FOUND, "未定位到指定的 PDF 文件: " + path);
        }

        throw new IllegalArgumentException("不支持的 PDF 输入源类型: " + source.getClass().getName());
    }

    private LoadedPdfDocument loadFromInputStream(InputStream is, long thresholdBytes) throws IOException {
        ByteArrayOutputStream memoryBaos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        long totalRead = 0;
        File tempFile = null;
        FileOutputStream fos = null;

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
                return new LoadedPdfDocument(Loader.loadPDF(new RandomAccessReadBufferedFile(tempFile)), tempFile);
            } else {
                byte[] inMemBytes = memoryBaos.toByteArray();
                return new LoadedPdfDocument(Loader.loadPDF(new RandomAccessReadBuffer(inMemBytes)), null);
            }
        } finally {
            if (fos != null) {
                try {
                    fos.close();
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
        try (InputStream is = resolveRawInputStream(source);
             OutputStream os = resolveOutputStream(target)) {
            is.transferTo(os);
            os.flush();
        } catch (IOException e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "复制源文件到目标失败: " + e.getMessage(), e);
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
