package io.github.atengk.boot.document.word;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.config.ConfigureBuilder;
import com.deepoove.poi.plugin.table.LoopColumnTableRenderPolicy;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import com.deepoove.poi.policy.RenderPolicy;
import com.deepoove.poi.template.BlockTemplate;
import com.deepoove.poi.template.ElementTemplate;
import com.deepoove.poi.template.MetaTemplate;
import com.deepoove.poi.util.TableTools;
import com.deepoove.poi.xwpf.NiceXWPFDocument;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.document.exception.DocumentException;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.WordRenderException;
import io.github.atengk.boot.document.pdf.WatermarkMacroParser;
import io.github.atengk.boot.document.pdf.model.WatermarkOptions;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.properties.DocumentWatermarkProperties;
import io.github.atengk.boot.document.properties.DocumentWordProperties;
import io.github.atengk.boot.document.spi.DefaultDocumentConverter;
import io.github.atengk.boot.document.spi.DocumentConverter;
import io.github.atengk.boot.document.util.DocumentWebHelper;
import io.github.atengk.boot.document.word.model.StitchOptions;
import io.github.atengk.boot.document.word.model.WordMetadata;
import io.github.atengk.boot.document.word.policy.FloatingPictureRenderPolicy;
import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.CipherAlgorithm;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.crypt.HashAlgorithm;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFactory;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFSettings;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHdrFtrRef;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.WritableResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 企业级 Word (.docx) 模板渲染统一操作门面
 * <p>
 * 基于 poi-tl 与 Apache POI 构建，提供多源输入自适应、强类型/Map 数据模型渲染、
 * 表格行循环动态追加、单元格跨行跨列合并、浮动电子印章嵌入、多文档物理无缝拼装与只读锁定保护。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class WordTemplate {

    private static final Logger log = LoggerFactory.getLogger(WordTemplate.class);

    private static final String CLASSPATH_PREFIX = "classpath:";

    private final DocumentWordProperties wordProperties;
    private final DocumentProperties documentProperties;
    private final DocumentConverter documentConverter;

    /**
     * 使用根配置属性与转换器 SPI 构造 WordTemplate 门面实例
     *
     * @param documentProperties 文档模块全局配置属性
     * @param documentConverter  文档跨格式转换器 SPI（为空时回退 {@link DefaultDocumentConverter}）
     */
    public WordTemplate(DocumentProperties documentProperties, DocumentConverter documentConverter) {
        this.documentProperties = documentProperties != null ? documentProperties : new DocumentProperties();
        this.wordProperties = this.documentProperties.getWord() != null
                ? this.documentProperties.getWord()
                : new DocumentWordProperties();
        this.documentConverter = documentConverter != null ? documentConverter : new DefaultDocumentConverter();
    }

    /**
     * 使用根配置属性构造 WordTemplate 门面实例（同步继承 maxDocumentSize 等全局防线）
     *
     * @param documentProperties 文档模块全局配置属性
     */
    public WordTemplate(DocumentProperties documentProperties) {
        this(documentProperties, null);
    }

    /**
     * 使用指定 Word 子配置构造 WordTemplate 门面实例
     *
     * @param wordProperties Word 文档配置属性
     */
    public WordTemplate(DocumentWordProperties wordProperties) {
        DocumentProperties fallback = new DocumentProperties();
        if (wordProperties != null) {
            fallback.setWord(wordProperties);
        }
        this.documentProperties = fallback;
        this.wordProperties = fallback.getWord();
        this.documentConverter = new DefaultDocumentConverter();
    }

    /**
     * 使用缺省配置构造 WordTemplate 门面实例
     */
    public WordTemplate() {
        this(new DocumentProperties(), null);
    }

    /**
     * 静态工厂构造方法
     *
     * @param wordProperties Word 配置属性
     * @return WordTemplate 实例
     */
    public static WordTemplate of(DocumentWordProperties wordProperties) {
        return new WordTemplate(wordProperties);
    }

    /**
     * 静态工厂构造方法（继承全局文档安全阈值配置）
     *
     * @param documentProperties 文档模块全局配置属性
     * @return WordTemplate 实例
     */
    public static WordTemplate of(DocumentProperties documentProperties) {
        return new WordTemplate(documentProperties);
    }

    /**
     * 静态工厂构造方法（继承全局配置并绑定文档跨格式转换器 SPI）
     *
     * @param documentProperties 文档模块全局配置属性
     * @param documentConverter  文档跨格式转换器 SPI
     * @return WordTemplate 实例
     */
    public static WordTemplate of(DocumentProperties documentProperties, DocumentConverter documentConverter) {
        return new WordTemplate(documentProperties, documentConverter);
    }

    /**
     * 静态工厂缺省构造方法
     *
     * @return WordTemplate 实例
     */
    public static WordTemplate of() {
        return new WordTemplate();
    }

    /**
     * 编译 Word 模板并渲染数据，输出至指定目标
     *
     * @param source 模板数据源（支持 Resource, InputStream, File, Path, byte[], String）
     * @param data   渲染数据模型（支持 Map 或 JavaBean）
     * @param target 目标输出（支持 File, Path, OutputStream, WritableResource, String 文件路径）
     * @throws WordRenderException 渲染失败或模板不存在时抛出
     */
    public void render(Object source, Object data, Object target) {
        render(source, data, target, createDefaultConfigure());
    }

    /**
     * 使用自定义配置编译并渲染 Word 模板，输出至指定目标
     *
     * @param source    模板数据源
     * @param data      渲染数据模型
     * @param target    目标输出
     * @param configure 自定义 poi-tl 配置
     * @throws WordRenderException 渲染失败或模板不存在时抛出
     */
    public void render(Object source, Object data, Object target, Configure configure) {
        Objects.requireNonNull(target, "target 不能为空");
        Configure configToUse = configure != null ? configure : createDefaultConfigure();
        XWPFTemplate template = null;
        OutputStream os = null;
        try {
            template = compile(source, configToUse);
            os = resolveOutputStream(target);
            template.render(data != null ? data : new Object());
            template.write(os);
            os.flush();
        } catch (WordRenderException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            if (e instanceof org.apache.poi.UnsupportedFileFormatException) {
                throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                        "渲染 Word 模板失败: " + e.getMessage(), e);
            }
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                    "渲染 Word 模板失败: " + e.getMessage(), e);
        } finally {
            clearFloatingPicturePolicies(configToUse);
            if (template != null) {
                try {
                    template.close();
                } catch (Exception ignored) {
                }
            }
            if (os != null && !(target instanceof OutputStream)) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 编译 Word 模板并渲染数据，返回渲染后的二进制字节数组
     *
     * @param source 模板数据源
     * @param data   渲染数据模型
     * @return 渲染后的 docx 二进制字节数据
     * @throws WordRenderException 渲染失败时抛出
     */
    public byte[] renderToBytes(Object source, Object data) {
        return renderToBytes(source, data, createDefaultConfigure());
    }

    /**
     * 使用自定义配置编译并渲染 Word 模板，返回渲染后的二进制字节数组
     *
     * @param source    模板数据源
     * @param data      渲染数据模型
     * @param configure 自定义 poi-tl 配置
     * @return 渲染后的 docx 二进制字节数据
     * @throws WordRenderException 渲染失败时抛出
     */
    public byte[] renderToBytes(Object source, Object data, Configure configure) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        Configure configToUse = configure != null ? configure : createDefaultConfigure();
        try (XWPFTemplate template = compile(source, configToUse)) {
            template.render(data != null ? data : new Object());
            template.write(baos);
            return baos.toByteArray();
        } catch (WordRenderException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            if (e instanceof org.apache.poi.UnsupportedFileFormatException) {
                throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                        "渲染 Word 模板并导出字节失败: " + e.getMessage(), e);
            }
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                    "渲染 Word 模板并导出字节失败: " + e.getMessage(), e);
        } finally {
            clearFloatingPicturePolicies(configToUse);
        }
    }

    /**
     * 使用默认配置编译 Word 模板实例
     *
     * @param source 模板数据源
     * @return XWPFTemplate 实例
     * @throws WordRenderException 编译失败时抛出
     */
    public XWPFTemplate compile(Object source) {
        return compile(source, createDefaultConfigure());
    }

    /**
     * 使用自定义配置编译 Word 模板实例
     *
     * @param source    模板数据源
     * @param configure 自定义 poi-tl 配置
     * @return XWPFTemplate 实例
     * @throws WordRenderException 编译失败时抛出
     */
    public XWPFTemplate compile(Object source, Configure configure) {
        Objects.requireNonNull(source, "source 不能为空");
        InputStream is = resolveInputStream(source);
        Configure configToUse = configure != null ? configure : createDefaultConfigure();
        try {
            return XWPFTemplate.compile(is, configToUse);
        } catch (WordRenderException e) {
            if (!(source instanceof InputStream)) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
            throw e;
        } catch (Exception e) {
            if (!(source instanceof InputStream)) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                    "编译 Word 模板异常 [" + source + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 逆向解析 Word 模板，提取其中包含的所有声明占位符变量名清单（保持文档出现顺序去重）
     *
     * @param source 模板数据源（支持 Resource, InputStream, File, Path, byte[], String）
     * @return 占位符变量名列表（如 ["employeeName", "items", "seal"]）
     * @throws WordRenderException 模板不存在或解析失败时抛出
     */
    public List<String> listVariableNames(Object source) {
        return listVariableNames(source, createDefaultConfigure());
    }

    /**
     * 逆向解析 Word 模板，提取其中包含的所有声明占位符变量名清单（支持指定自定义 Configure 配置）
     *
     * @param source    模板数据源
     * @param configure 自定义 poi-tl 配置
     * @return 占位符变量名列表
     * @throws WordRenderException 模板不存在或解析失败时抛出
     */
    public List<String> listVariableNames(Object source, Configure configure) {
        Objects.requireNonNull(source, "source 不能为空");
        Configure configToUse = configure != null ? configure : createDefaultConfigure();
        XWPFTemplate template = null;
        try {
            template = compile(source, configToUse);
            List<MetaTemplate> elementTemplates = template.getElementTemplates();
            Set<String> variableNames = new LinkedHashSet<>();
            if (elementTemplates != null) {
                for (MetaTemplate meta : elementTemplates) {
                    collectVariableNames(meta, variableNames);
                }
            }
            return new ArrayList<>(variableNames);
        } catch (WordRenderException e) {
            throw e;
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                    "解析 Word 模板变量清单失败 [" + source + "]: " + e.getMessage(), e);
        } finally {
            if (template != null) {
                try {
                    template.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void collectVariableNames(MetaTemplate meta, Set<String> names) {
        if (meta == null) {
            return;
        }
        if (meta instanceof ElementTemplate et) {
            String tagName = et.getTagName();
            if (tagName != null && !tagName.isBlank()) {
                names.add(tagName.trim());
            }
        } else {
            String var = meta.variable();
            if (var != null && !var.isBlank()) {
                names.add(var.trim());
            }
        }
        if (meta instanceof BlockTemplate bt) {
            if (bt.getStartMark() != null && bt.getStartMark().getTagName() != null) {
                names.add(bt.getStartMark().getTagName().trim());
            }
            if (bt.getTemplates() != null) {
                for (MetaTemplate child : bt.getTemplates()) {
                    collectVariableNames(child, names);
                }
            }
        }
    }

    /**
     * 提取 Word (.docx) 文档正文段落与表格中的纯文本内容
     *
     * @param source Word 数据源（支持 Resource, InputStream, File, Path, byte[], String）
     * @return 正文纯文本
     * @throws WordRenderException 读取或解析失败时抛出
     */
    public String extractText(Object source) {
        Objects.requireNonNull(source, "source 不能为空");
        InputStream is = resolveInputStream(source);
        try (XWPFDocument doc = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        } catch (WordRenderException e) {
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.DOCUMENT_OPERATION_FAILED,
                    "提取 Word 文档正文纯文本失败 [" + source + "]: " + e.getMessage(), e);
        } finally {
            if (is != null && !(source instanceof InputStream)) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 创建默认的 poi-tl 配置构建器（按配置 {@code springElEnabled} 决定是否启用 SpringEL，默认关闭防注入）
     *
     * @return ConfigureBuilder 实例
     */
    public ConfigureBuilder configureBuilder() {
        return configureBuilder(wordProperties.isSpringElEnabled());
    }

    /**
     * 创建 poi-tl 配置构建器并显式控制是否启用 SpringEL 表达式解析（仅建议在可信模板场景开启）
     *
     * @param enableSpringEl 是否启用 SpringEL 表达式求值
     * @return ConfigureBuilder 实例
     */
    public ConfigureBuilder configureBuilder(boolean enableSpringEl) {
        ConfigureBuilder builder = Configure.builder();
        if (enableSpringEl) {
            builder.useSpringEL(wordProperties.isSpringElStrict());
        }
        // 预绑定常用表格行循环策略（支持同一行中包含 {{items}}[prop] 模板）
        LoopRowTableRenderPolicy loopRowPolicy = new LoopRowTableRenderPolicy(true);
        builder.bind("items", loopRowPolicy);
        builder.bind("rows", loopRowPolicy);
        builder.bind("list", loopRowPolicy);

        // 预绑定常用浮动印章策略
        FloatingPictureRenderPolicy sealPolicy = new FloatingPictureRenderPolicy();
        builder.bind("seal", sealPolicy);
        builder.bind("stamp", sealPolicy);
        builder.bind("sign", sealPolicy);

        return builder;
    }

    /**
     * 创建默认的 poi-tl Configure 配置对象
     *
     * @return Configure 实例
     */
    public Configure createDefaultConfigure() {
        return configureBuilder().build();
    }

    /**
     * 便捷方法：为指定标签名称绑定表格行循环策略
     *
     * @param builder  配置构建器
     * @param tagNames 标签名称列表
     * @return 链式 ConfigureBuilder
     */
    public ConfigureBuilder bindLoopRow(ConfigureBuilder builder, String... tagNames) {
        Objects.requireNonNull(builder, "builder 不能为空");
        if (tagNames != null) {
            LoopRowTableRenderPolicy policy = new LoopRowTableRenderPolicy(true);
            for (String tag : tagNames) {
                if (tag != null && !tag.trim().isEmpty()) {
                    builder.bind(tag.trim(), policy);
                }
            }
        }
        return builder;
    }

    /**
     * 便捷方法：为指定标签名称绑定表格列循环策略（横向动态扩展多列表格列）
     *
     * @param builder  配置构建器
     * @param tagNames 标签名称列表
     * @return 链式 ConfigureBuilder
     */
    public ConfigureBuilder bindLoopColumn(ConfigureBuilder builder, String... tagNames) {
        Objects.requireNonNull(builder, "builder 不能为空");
        if (tagNames != null) {
            LoopColumnTableRenderPolicy policy = new LoopColumnTableRenderPolicy(true);
            for (String tag : tagNames) {
                if (tag != null && !tag.trim().isEmpty()) {
                    builder.bind(tag.trim(), policy);
                }
            }
        }
        return builder;
    }

    /**
     * 便捷方法：为指定标签名称绑定浮动印章渲染策略
     *
     * @param builder  配置构建器
     * @param tagNames 标签名称列表
     * @return 链式 ConfigureBuilder
     */
    public ConfigureBuilder bindFloatingPicture(ConfigureBuilder builder, String... tagNames) {
        Objects.requireNonNull(builder, "builder 不能为空");
        if (tagNames != null) {
            FloatingPictureRenderPolicy policy = new FloatingPictureRenderPolicy();
            for (String tag : tagNames) {
                if (tag != null && !tag.trim().isEmpty()) {
                    builder.bind(tag.trim(), policy);
                }
            }
        }
        return builder;
    }

    private void clearFloatingPicturePolicies(Configure config) {
        if (config == null || config.getCustomPolicys() == null) {
            return;
        }
        for (RenderPolicy policy : config.getCustomPolicys().values()) {
            if (policy instanceof FloatingPictureRenderPolicy fpPolicy) {
                fpPolicy.clear();
            }
        }
    }

    /**
     * 获取当前绑定的 Word 配置属性
     *
     * @return DocumentWordProperties 实例
     */
    public DocumentWordProperties getWordProperties() {
        return wordProperties;
    }

    /**
     * 静态工具：对指定表格的特定行进行横向单元格动态合并（ColSpan）
     *
     * @param table   目标 XWPFTable 表格
     * @param row     行索引（从 0 开始）
     * @param fromCol 起始列索引（包含）
     * @param toCol   结束列索引（包含）
     */
    public static void mergeCellsHorizontal(XWPFTable table, int row, int fromCol, int toCol) {
        Objects.requireNonNull(table, "table 不能为空");
        TableTools.mergeCellsHorizonal(table, row, fromCol, toCol);
    }

    /**
     * 静态工具：对指定表格的特定列进行纵向单元格动态合并（RowSpan）
     *
     * @param table   目标 XWPFTable 表格
     * @param col     列索引（从 0 开始）
     * @param fromRow 起始行索引（包含）
     * @param toRow   结束行索引（包含）
     */
    public static void mergeCellsVertical(XWPFTable table, int col, int fromRow, int toRow) {
        Objects.requireNonNull(table, "table 不能为空");
        TableTools.mergeCellsVertically(table, col, fromRow, toRow);
    }

    /**
     * 将多源模板入参自适应解析为可用输入流，统一施加路径穿越防线与单文档体积上限保护
     *
     * @param source 多源模板输入
     * @return 模板二进制输入流
     * @throws WordRenderException 模板不存在、超出体积上限或解析失败时抛出
     */
    public InputStream resolveInputStream(Object source) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        long maxBytes = resolveMaxDocumentSizeBytes();

        if (source instanceof InputStream is) {
            return wrapBoundedInputStream(is, maxBytes);
        }
        if (source instanceof byte[] bytes) {
            if (bytes.length > maxBytes) {
                throw new WordRenderException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                        "Word 字节数组大小超出安全上限 (" + maxBytes + " bytes): " + bytes.length);
            }
            return new ByteArrayInputStream(bytes);
        }
        if (source instanceof File file) {
            if (!file.exists() || !file.isFile() || !file.canRead()) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "Word 模板物理文件不存在或不可读: " + file.getAbsolutePath());
            }
            if (file.length() > maxBytes) {
                throw new WordRenderException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                        "Word 模板物理文件大小超出安全上限 (" + maxBytes + " bytes): " + file.length());
            }
            try {
                return wrapBoundedInputStream(new FileInputStream(file), maxBytes);
            } catch (IOException e) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "无法打开 Word 模板文件: " + file.getAbsolutePath(), e);
            }
        }
        if (source instanceof Path path) {
            if (!Files.exists(path) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "Word 模板 Path 路径不存在或不可读: " + path.toAbsolutePath());
            }
            try {
                long size = Files.size(path);
                if (size > maxBytes) {
                    throw new WordRenderException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                            "Word 模板 Path 文件大小超出安全上限 (" + maxBytes + " bytes): " + size);
                }
                return wrapBoundedInputStream(Files.newInputStream(path), maxBytes);
            } catch (WordRenderException e) {
                throw e;
            } catch (IOException e) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "无法打开 Word 模板 Path 文件: " + path.toAbsolutePath(), e);
            }
        }
        if (source instanceof Resource resource) {
            if (!resource.exists()) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "Word 模板 Spring Resource 资源不存在: " + resource.getDescription());
            }
            try {
                return wrapBoundedInputStream(resource.getInputStream(), maxBytes);
            } catch (IOException e) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "无法读取 Word 模板 Spring Resource: " + resource.getDescription(), e);
            }
        }
        if (source instanceof String str) {
            String rawPath = str.trim();
            if (rawPath.isEmpty()) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND, "Word 模板路径不能为空");
            }
            // 1. 显式 classpath: 前缀解析（强制路径净化防穿越）
            if (rawPath.startsWith(CLASSPATH_PREFIX)) {
                String cleanSub = FileUtil.cleanPath(rawPath.substring(CLASSPATH_PREFIX.length()));
                InputStream is = loadClasspathStream(cleanSub);
                if (is != null) {
                    return wrapBoundedInputStream(is, maxBytes);
                }
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "未找到指定的类路径 Word 模板: " + rawPath);
            }

            // 1.5 通用路径穿越校验
            String path = FileUtil.cleanPath(rawPath);

            // 2. 本地绝对或相对物理文件检查
            File file = new File(path);
            if (file.exists() && file.isFile() && file.canRead()) {
                if (file.length() > maxBytes) {
                    throw new WordRenderException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                            "Word 模板物理文件大小超出安全上限 (" + maxBytes + " bytes): " + file.length());
                }
                try {
                    return wrapBoundedInputStream(new FileInputStream(file), maxBytes);
                } catch (IOException e) {
                    throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                            "读取物理 Word 模板文件失败: " + file.getAbsolutePath(), e);
                }
            }

            // 3. 基于 templateBaseDir 安全组合探测
            String baseDir = wordProperties.getTemplateBaseDir();
            if (baseDir != null && !baseDir.trim().isEmpty() && !file.isAbsolute()) {
                String normalizedBase = baseDir.trim();
                String relative = path.startsWith("/") ? path.substring(1) : path;
                if (normalizedBase.startsWith(CLASSPATH_PREFIX)) {
                    String baseSub = normalizedBase.substring(CLASSPATH_PREFIX.length());
                    String combinedSub = baseSub.endsWith("/") ? baseSub + relative : baseSub + "/" + relative;
                    String cleanCombinedSub = FileUtil.cleanPath(combinedSub);
                    InputStream is = loadClasspathStream(cleanCombinedSub);
                    if (is != null) {
                        return wrapBoundedInputStream(is, maxBytes);
                    }
                } else {
                    String cleanBase = FileUtil.cleanPath(normalizedBase);
                    Path safeCombined = FileUtil.resolveSafePath(Path.of(cleanBase), relative);
                    File combinedFile = safeCombined.toFile();
                    if (combinedFile.exists() && combinedFile.isFile() && combinedFile.canRead()) {
                        if (combinedFile.length() > maxBytes) {
                            throw new WordRenderException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                                    "Word 模板物理文件大小超出安全上限 (" + maxBytes + " bytes): " + combinedFile.length());
                        }
                        try {
                            return wrapBoundedInputStream(new FileInputStream(combinedFile), maxBytes);
                        } catch (IOException e) {
                            throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                                    "读取模板基准路径下的物理文件失败: " + combinedFile.getAbsolutePath(), e);
                        }
                    }
                }
            }

            // 4. 兜底直接尝试作为 classpath 相对路径读取
            InputStream fallbackIs = loadClasspathStream(path);
            if (fallbackIs != null) {
                return wrapBoundedInputStream(fallbackIs, maxBytes);
            }

            throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                    "未能定位到 Word 模板文件: " + rawPath);
        }

        throw new IllegalArgumentException("不支持的模板输入源类型: " + source.getClass().getName());
    }

    private long resolveMaxDocumentSizeBytes() {
        if (documentProperties.getMaxDocumentSize() != null
                && documentProperties.getMaxDocumentSize().toBytes() > 0) {
            return documentProperties.getMaxDocumentSize().toBytes();
        }
        return Long.MAX_VALUE;
    }

    private InputStream wrapBoundedInputStream(InputStream delegate, long maxBytes) {
        return DocumentWebHelper.wrapBoundedInputStream(delegate, maxBytes,
                msg -> new WordRenderException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED, msg));
    }

    /**
     * 将目标输出入参自适应解析为可用输出流
     *
     * @param target 目标输出对象
     * @return 目标输出流
     * @throws WordRenderException 目标流解析或创建失败时抛出
     */
    public OutputStream resolveOutputStream(Object target) {
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
                throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                        "创建目标文件输出流失败: " + file.getAbsolutePath(), e);
            }
        }
        if (target instanceof Path path) {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                            "创建目标输出目录失败: " + parent, e);
                }
            }
            try {
                return Files.newOutputStream(path);
            } catch (IOException e) {
                throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                        "创建目标 Path 输出流失败: " + path, e);
            }
        }
        if (target instanceof WritableResource wr) {
            try {
                return wr.getOutputStream();
            } catch (IOException e) {
                throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                        "获取 WritableResource 输出流失败: " + wr.getDescription(), e);
            }
        }
        if (target instanceof String str) {
            return resolveOutputStream(new File(FileUtil.cleanPath(str.trim())));
        }

        throw new IllegalArgumentException("不支持的目标输出类型: " + target.getClass().getName());
    }

    private InputStream loadClasspathStream(String rawPath) {
        String path = rawPath.trim();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream is = cl != null ? cl.getResourceAsStream(path) : null;
        if (is == null) {
            is = WordTemplate.class.getClassLoader().getResourceAsStream(path);
        }
        if (is == null) {
            is = ClassLoader.getSystemResourceAsStream(path);
        }
        return is;
    }

    /**
     * 将多个独立的 Word 文档物理无缝按序拼装并输出至目标
     *
     * @param target      目标输出对象（支持 File, Path, OutputStream, WritableResource, String）
     * @param docxSources 待拼装的文档列表（支持 Resource, InputStream, File, Path, byte[], String）
     * @throws WordRenderException 拼装合并失败或输入流异常时抛出
     */
    public void stitch(Object target, List<?> docxSources) {
        stitch(target, docxSources, new StitchOptions());
    }

    /**
     * 按指定拼装控制选项将多个独立的 Word 文档按序合并并输出至目标（支持子文档间插入分页符）
     *
     * @param target      目标输出对象（支持 File, Path, OutputStream, WritableResource, String）
     * @param docxSources 待拼装的文档列表（支持 Resource, InputStream, File, Path, byte[], String）
     * @param options     拼装控制选项（为 null 时按连续无缝拼接处理）
     * @throws WordRenderException 拼装合并失败或输入流异常时抛出
     */
    public void stitch(Object target, List<?> docxSources, StitchOptions options) {
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        if (docxSources == null || docxSources.isEmpty()) {
            throw new IllegalArgumentException("待拼装的 Word 文档输入列表 docxSources 不能为空");
        }
        boolean pageBreak = options != null && options.isPageBreakBetween();
        boolean keepHeaderFooter = options == null || options.isKeepHeaderFooter();

        try {
            NiceXWPFDocument mainDoc = null;
            List<NiceXWPFDocument> subDocs = new ArrayList<>();
            List<NiceXWPFDocument> docsToClose = new ArrayList<>();
            List<InputStream> openedStreams = new ArrayList<>();

            try {
                for (int i = 0; i < docxSources.size(); i++) {
                    Object src = docxSources.get(i);
                    if (src == null) {
                        throw new IllegalArgumentException("待拼装的 Word 文档项不能为空 (索引: " + i + ")");
                    }
                    InputStream is = resolveInputStream(src);
                    openedStreams.add(is);
                    NiceXWPFDocument doc = new NiceXWPFDocument(is);
                    docsToClose.add(doc);
                    if (i == 0) {
                        mainDoc = doc;
                    } else {
                        subDocs.add(doc);
                    }
                }

                if (!subDocs.isEmpty()) {
                    if (pageBreak) {
                        for (NiceXWPFDocument subDoc : subDocs) {
                            XWPFParagraph breakPara = mainDoc.createParagraph();
                            breakPara.createRun().addBreak(BreakType.PAGE);
                            XWPFRun anchorRun = mainDoc.createParagraph().createRun();
                            mainDoc = mainDoc.merge(List.of(subDoc), anchorRun);
                            if (mainDoc != null && !docsToClose.contains(mainDoc)) {
                                docsToClose.add(mainDoc);
                            }
                        }
                    } else {
                        mainDoc = mainDoc.merge(subDocs, mainDoc.createParagraph().createRun());
                        if (mainDoc != null && !docsToClose.contains(mainDoc)) {
                            docsToClose.add(mainDoc);
                        }
                    }
                }

                OutputStream os = null;
                try (NiceXWPFDocument finalDoc = mainDoc.generate()) {
                    if (!keepHeaderFooter) {
                        stripHeadersAndFooters(finalDoc);
                    }
                    os = resolveOutputStream(target);
                    finalDoc.write(os);
                    os.flush();
                } finally {
                    closeOutputStreamQuietly(os, target);
                }
            } finally {
                for (NiceXWPFDocument d : docsToClose) {
                    try {
                        d.close();
                    } catch (Exception ignored) {
                    }
                }
                for (InputStream is : openedStreams) {
                    try {
                        is.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (WordRenderException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED, "多文档拼装合并失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按指定拼装控制选项将多个独立的 Word 文档按序合并并输出至目标（选项前置重载）
     *
     * @param target      目标输出对象
     * @param options     拼装控制选项
     * @param docxSources 待拼装的文档列表
     */
    public void stitch(Object target, StitchOptions options, List<?> docxSources) {
        stitch(target, docxSources, options);
    }

    /**
     * 将多个独立的 Word 文档物理无缝按序拼装并输出至目标（可变参数便捷重载）
     *
     * @param target      目标输出对象
     * @param docxSources 待拼装的文档输入源列表
     */
    public void stitch(Object target, Object... docxSources) {
        Objects.requireNonNull(docxSources, "待拼装的 Word 文档输入源不能为空");
        stitch(target, Arrays.asList(docxSources));
    }

    /**
     * 按指定拼装控制选项将多个独立的 Word 文档按序拼装并输出至目标（选项前置可变参数重载）
     *
     * @param target      目标输出对象
     * @param options     拼装控制选项
     * @param docxSources 待拼装的文档输入源列表
     */
    public void stitch(Object target, StitchOptions options, Object... docxSources) {
        Objects.requireNonNull(docxSources, "待拼装的 Word 文档输入源不能为空");
        stitch(target, Arrays.asList(docxSources), options);
    }

    /**
     * 将多个独立的 Word 文档物理无缝拼装并导出为二进制字节数组
     *
     * @param docxSources 待拼装的文档列表
     * @return 拼装后的 Word 二进制数据
     */
    public byte[] stitchToBytes(List<?> docxSources) {
        return stitchToBytes(docxSources, new StitchOptions());
    }

    /**
     * 按指定拼装控制选项将多个独立的 Word 文档拼装并导出为二进制字节数组
     *
     * @param docxSources 待拼装的文档列表
     * @param options     拼装控制选项
     * @return 拼装后的 Word 二进制数据
     */
    public byte[] stitchToBytes(List<?> docxSources, StitchOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stitch(baos, docxSources, options);
        return baos.toByteArray();
    }

    /**
     * 按指定拼装控制选项将多个独立的 Word 文档拼装并导出为二进制字节数组（选项前置重载）
     *
     * @param options     拼装控制选项
     * @param docxSources 待拼装的文档列表
     * @return 拼装后的 Word 二进制数据
     */
    public byte[] stitchToBytes(StitchOptions options, List<?> docxSources) {
        return stitchToBytes(docxSources, options);
    }

    /**
     * 将多个独立的 Word 文档物理无缝拼装并导出为二进制字节数组（可变参数便捷重载）
     *
     * @param docxSources 待拼装的文档输入源列表
     * @return 拼装后的 Word 二进制数据
     */
    public byte[] stitchToBytes(Object... docxSources) {
        Objects.requireNonNull(docxSources, "待拼装的 Word 文档输入源不能为空");
        return stitchToBytes(Arrays.asList(docxSources));
    }

    /**
     * 按指定拼装控制选项将多个独立的 Word 文档拼装并导出为二进制字节数组（选项前置可变参数重载）
     *
     * @param options     拼装控制选项
     * @param docxSources 待拼装的文档输入源列表
     * @return 拼装后的 Word 二进制数据
     */
    public byte[] stitchToBytes(StitchOptions options, Object... docxSources) {
        Objects.requireNonNull(docxSources, "待拼装的 Word 文档输入源不能为空");
        return stitchToBytes(Arrays.asList(docxSources), options);
    }

    private void stripHeadersAndFooters(XWPFDocument doc) {
        for (XWPFHeader header : new ArrayList<>(doc.getHeaderList())) {
            header.clearHeaderFooter();
            String relId = doc.getRelationId(header);
            if (relId != null) {
                doc.getPackagePart().removeRelationship(relId);
            }
        }
        for (XWPFFooter footer : new ArrayList<>(doc.getFooterList())) {
            footer.clearHeaderFooter();
            String relId = doc.getRelationId(footer);
            if (relId != null) {
                doc.getPackagePart().removeRelationship(relId);
            }
        }
        if (doc.getDocument().getBody() != null) {
            clearSectionHeaderFooterRefs(doc.getDocument().getBody().getSectPr());
        }
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            if (paragraph.getCTP() != null && paragraph.getCTP().getPPr() != null) {
                clearSectionHeaderFooterRefs(paragraph.getCTP().getPPr().getSectPr());
            }
        }
    }

    private void clearSectionHeaderFooterRefs(CTSectPr sectPr) {
        if (sectPr == null) {
            return;
        }
        sectPr.setHeaderReferenceArray(new CTHdrFtrRef[0]);
        sectPr.setFooterReferenceArray(new CTHdrFtrRef[0]);
    }

    /**
     * 为 Word 文档施加基于 {@code settings.xml} 的只读编辑锁定保护（{@code ReadonlyProtection}）
     * <p>
     * 注意：该方法写入 OOXML 编辑限制标记防止误编辑，文件物理载体仍以明文 ZIP 封装可直接打开；
     * 若需真正禁止无密码打开访问，请使用 {@link #encrypt(Object, Object, String)}（Agile AES-256 加密信封）。
     * </p>
     *
     * @param source   原始 Word 文档输入源
     * @param target   目标输出对象
     * @param password 保护密码（若为 null 或空串则启用无密码只读锁定）
     * @throws WordRenderException 施加保护失败时抛出
     */
    public void protect(Object source, Object target, String password) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        try (InputStream is = resolveInputStream(source);
             XWPFDocument doc = new XWPFDocument(is)) {

            ensureSettingsInitialized(doc);

            if (password != null && !password.isEmpty()) {
                doc.enforceReadonlyProtection(password, HashAlgorithm.sha512);
            } else {
                doc.enforceReadonlyProtection();
            }

            OutputStream os = resolveOutputStream(target);
            try {
                doc.write(os);
                os.flush();
            } finally {
                if (!(target instanceof OutputStream)) {
                    os.close();
                }
            }
        } catch (WordRenderException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED, "为 Word 文档施加只读保护失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为 Word 文档施加无密码只读锁定保护
     *
     * @param source 原始 Word 文档输入源
     * @param target 目标输出对象
     */
    public void protect(Object source, Object target) {
        protect(source, target, null);
    }

    /**
     * 为 Word 文档施加只读密码锁定保护并导出为字节数组
     *
     * @param source   原始 Word 文档输入源
     * @param password 保护密码
     * @return 保护后的 Word 二进制数据
     */
    public byte[] protectToBytes(Object source, String password) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        protect(source, baos, password);
        return baos.toByteArray();
    }

    /**
     * 为 Word 文档施加无密码只读锁定保护并导出为字节数组
     *
     * @param source 原始 Word 文档输入源
     * @return 保护后的 Word 二进制数据
     */
    public byte[] protectToBytes(Object source) {
        return protectToBytes(source, null);
    }

    /**
     * 解除 Word 文档的只读锁定保护状态（Unprotect）
     *
     * @param source 原始 Word 文档输入源
     * @param target 目标输出对象
     */
    public void unprotect(Object source, Object target) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        try (InputStream is = resolveInputStream(source);
             XWPFDocument doc = new XWPFDocument(is)) {

            ensureSettingsInitialized(doc);
            doc.removeProtectionEnforcement();

            OutputStream os = resolveOutputStream(target);
            try {
                doc.write(os);
                os.flush();
            } finally {
                if (!(target instanceof OutputStream)) {
                    os.close();
                }
            }
        } catch (WordRenderException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED, "解除 Word 文档只读保护失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解除 Word 文档的保护状态并导出为字节数组
     *
     * @param source 原始 Word 文档输入源
     * @return 解除保护后的二进制字节数据
     */
    public byte[] unprotectToBytes(Object source) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        unprotect(source, baos);
        return baos.toByteArray();
    }

    /**
     * 判断指定的 Word 文档是否启用了只读锁定保护
     *
     * @param source Word 文档输入源
     * @return 是否已开启只读保护
     */
    public boolean isEnforcedReadonlyProtection(Object source) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        try (InputStream is = resolveInputStream(source);
             XWPFDocument doc = new XWPFDocument(is)) {
            return doc.isEnforcedReadonlyProtection();
        } catch (WordRenderException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED, "检查 Word 文档只读保护状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 校验 Word 文档的保护密码是否匹配
     *
     * @param source   Word 文档输入源
     * @param password 待校验的明文密码
     * @return 密码匹配返回 true，否则返回 false
     */
    public boolean validateProtectionPassword(Object source, String password) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        if (password == null) {
            return false;
        }
        try (InputStream is = resolveInputStream(source);
             XWPFDocument doc = new XWPFDocument(is)) {
            return doc.validateProtectionPassword(password);
        } catch (WordRenderException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED, "校验 Word 文档保护密码失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 Agile AES-256（SHA-512）复合文档加密信封对 Word (.docx) 施加文件打开级加密保护
     *
     * @param source   原始明文 Word 文档输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @param target   目标输出对象（支持 File, Path, OutputStream, WritableResource, String）
     * @param password 打开文档密码（不可为空或纯空白）
     * @throws WordRenderException 口令为空、文档损坏、已加密或加密失败时抛出
     */
    public void encrypt(Object source, Object target, String password) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        if (password == null || password.isBlank()) {
            throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR, "Word 文件级加密密码不能为空");
        }

        byte[] plainBytes = readSourceBytesBounded(source);
        OutputStream os = null;
        try {
            FileMagic magic = FileMagic.valueOf(plainBytes);
            if (magic != FileMagic.OOXML) {
                throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR,
                        "待加密的 Word 文档必须为标准明文 OOXML (.docx) 格式，当前探测格式: " + magic);
            }
            // 校验 OOXML ZIP 包结构完整性，防畸形伪造流
            try (OPCPackage ignored = OPCPackage.open(new ByteArrayInputStream(plainBytes))) {
                // 仅校验可正常打开
            }

            try (POIFSFileSystem fs = new POIFSFileSystem()) {
                EncryptionInfo info = new EncryptionInfo(
                        EncryptionMode.agile, CipherAlgorithm.aes256, HashAlgorithm.sha512, -1, -1, null);
                Encryptor encryptor = info.getEncryptor();
                encryptor.confirmPassword(password);
                try (OutputStream encOs = encryptor.getDataStream(fs)) {
                    encOs.write(plainBytes);
                    encOs.flush();
                }
                os = resolveOutputStream(target);
                fs.writeFilesystem(os);
                os.flush();
            }
        } catch (WordRenderException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR,
                    "Word 文件级 AES-256 加密失败: " + e.getMessage(), e);
        } finally {
            closeOutputStreamQuietly(os, target);
        }
    }

    /**
     * 使用 Agile AES-256（SHA-512）加密 Word (.docx) 并导出为字节数组
     *
     * @param source   原始明文 Word 文档输入源
     * @param password 打开文档密码
     * @return 加密后的复合文档字节数组
     */
    public byte[] encryptToBytes(Object source, String password) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        encrypt(source, baos, password);
        return baos.toByteArray();
    }

    /**
     * 使用打开密码解密 Agile AES-256 加密的 Word (.docx) 文档并输出还原明文
     *
     * @param source   加密 Word 文档输入源
     * @param target   目标明文输出对象
     * @param password 打开文档密码（不可为空或纯空白）
     * @throws WordRenderException 口令为空、口令错误、文档非加密信封或解密失败时抛出
     */
    public void decrypt(Object source, Object target, String password) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        if (password == null || password.isBlank()) {
            throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR, "Word 文件级解密密码不能为空");
        }

        byte[] encryptedBytes = readSourceBytesBounded(source);
        byte[] decryptedBytes = decryptToPlainBytes(encryptedBytes, password);
        writeBytesToTarget(decryptedBytes, target, DocumentResultCode.ENCRYPTION_ERROR, "写出解密后的 Word 文档流失败");
    }

    /**
     * 解密 Word (.docx) 文档并导出为明文字节数组
     *
     * @param source   加密 Word 文档输入源
     * @param password 打开文档密码
     * @return 明文 OOXML 字节数组
     */
    public byte[] decryptToBytes(Object source, String password) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        decrypt(source, baos, password);
        return baos.toByteArray();
    }

    /**
     * 判断指定 Word 文档是否处于文件打开级加密信封保护状态（区别于仅 settings.xml 只读编辑锁）
     *
     * @param source Word 文档输入源
     * @return 已施加加密信封返回 true，标准明文 OOXML 返回 false
     * @throws WordRenderException 文档不存在、超限或损坏无法识别格式时抛出
     */
    public boolean isEncrypted(Object source) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        byte[] bytes = readSourceBytesBounded(source);
        try {
            FileMagic magic = FileMagic.valueOf(bytes);
            if (magic == FileMagic.OOXML) {
                return false;
            }
            if (magic == FileMagic.OLE2) {
                try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
                    return fs.getRoot().hasEntry(EncryptionInfo.ENCRYPTION_INFO_ENTRY);
                }
            }
            throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR,
                    "无法识别的 Word 文档头魔数，疑似文件损坏或非 .docx 格式: " + magic);
        } catch (WordRenderException e) {
            throw e;
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR,
                    "探测 Word 文档加密状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为 Word (.docx) 文档压印原生页眉 VML 对角文字水印（基于默认全局水印配置与动态宏变量）
     *
     * @param source 原始 Word 文档输入源
     * @param target 目标输出对象
     */
    public void addWatermark(Object source, Object target) {
        addWatermark(source, target, (WatermarkOptions) null);
    }

    /**
     * 为 Word (.docx) 文档压印原生页眉 VML 对角文字水印（支持动态宏变量如 #{user.name}、#{user.phone}、#{tenantId}）
     *
     * @param source 原始 Word 文档输入源
     * @param target 目标输出对象
     * @param text   水印文本模板
     */
    public void addWatermark(Object source, Object target, String text) {
        addWatermark(source, target, WatermarkOptions.ofText(text));
    }

    /**
     * 为 Word (.docx) 文档压印原生页眉 VML 对角文字水印并输出至目标
     *
     * @param source  原始 Word 文档输入源
     * @param target  目标输出对象
     * @param options 水印配置选项（为 null 时回退全局默认水印配置）
     */
    public void addWatermark(Object source, Object target, WatermarkOptions options) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        Objects.requireNonNull(target, "目标输出 target 不能为空");

        DocumentWatermarkProperties defaultWp = documentProperties.getWatermark() != null
                ? documentProperties.getWatermark()
                : new DocumentWatermarkProperties();
        String rawText = options != null && options.getText() != null
                ? options.getText()
                : defaultWp.getDefaultText();
        String resolvedText = WatermarkMacroParser.parse(rawText, options != null ? options.getVariables() : null);

        if (resolvedText == null || resolvedText.isBlank()) {
            byte[] passthrough = readSourceBytesBounded(source);
            writeBytesToTarget(passthrough, target, DocumentResultCode.DOCUMENT_OPERATION_FAILED, "写入 Word 原样输出流失败");
            return;
        }

        OutputStream os = null;
        try (InputStream is = resolveInputStream(source);
             XWPFDocument doc = new XWPFDocument(is)) {
            if (doc.getDocument().getBody().getSectPr() == null) {
                doc.getDocument().getBody().addNewSectPr();
            }
            XWPFHeaderFooterPolicy headerFooterPolicy = new XWPFHeaderFooterPolicy(doc);
            headerFooterPolicy.createWatermark(resolvedText);

            os = resolveOutputStream(target);
            doc.write(os);
            os.flush();
        } catch (WordRenderException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            WordRenderException nested = unwrapWordRenderException(e);
            if (nested != null) {
                throw nested;
            }
            throw new WordRenderException(DocumentResultCode.DOCUMENT_OPERATION_FAILED,
                    "Word 页眉水印压印失败: " + e.getMessage(), e);
        } finally {
            closeOutputStreamQuietly(os, target);
        }
    }

    /**
     * 为 Word (.docx) 文档压印原生页眉 VML 水印并导出为字节数组
     *
     * @param source 原始 Word 文档输入源
     * @param text   水印文本模板
     * @return 带水印的 Word 文档字节数组
     */
    public byte[] addWatermarkToBytes(Object source, String text) {
        return addWatermarkToBytes(source, WatermarkOptions.ofText(text));
    }

    /**
     * 为 Word (.docx) 文档压印原生页眉 VML 水印并导出为字节数组
     *
     * @param source  原始 Word 文档输入源
     * @param options 水印配置选项
     * @return 带水印的 Word 文档字节数组
     */
    public byte[] addWatermarkToBytes(Object source, WatermarkOptions options) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        addWatermark(source, baos, options);
        return baos.toByteArray();
    }

    /**
     * 自省探查明文 Word (.docx) 文档的结构计数、安全保护状态与核心元数据属性
     *
     * @param source Word 文档输入源
     * @return 结构化 {@link WordMetadata} 元数据模型
     */
    public WordMetadata inspectMetadata(Object source) {
        return inspectMetadata(source, null);
    }

    /**
     * 自省探查 Word (.docx) 文档的结构计数、安全保护状态与核心元数据属性（支持加密文档口令穿透）
     *
     * @param source   Word 文档输入源
     * @param password 打开密码（文档处于 Agile AES-256 加密状态时必填）
     * @return 结构化 {@link WordMetadata} 元数据模型
     */
    public WordMetadata inspectMetadata(Object source, String password) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");
        byte[] rawBytes = readSourceBytesBounded(source);
        try {
            FileMagic magic = FileMagic.valueOf(rawBytes);
            boolean isAesEncrypted = false;
            byte[] plainBytes;

            if (magic == FileMagic.OLE2) {
                try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(rawBytes))) {
                    if (!fs.getRoot().hasEntry(EncryptionInfo.ENCRYPTION_INFO_ENTRY)) {
                        throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR,
                                "检测到未包含加密信封的非 OOXML OLE2 文档，当前自省仅支持 .docx 或 AES-256 加密 .docx");
                    }
                }
                if (password == null || password.isBlank()) {
                    throw new WordRenderException(DocumentResultCode.PASSWORD_ERROR,
                            "Word 文档已加密，自省元数据需要提供打开密码");
                }
                plainBytes = decryptToPlainBytes(rawBytes, password);
                isAesEncrypted = true;
            } else if (magic == FileMagic.OOXML) {
                plainBytes = rawBytes;
            } else {
                throw new WordRenderException(DocumentResultCode.DOCUMENT_OPERATION_FAILED,
                        "不支持或已损坏的 Word 文档格式: " + magic);
            }

            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(plainBytes))) {
                int paragraphs = doc.getParagraphs().size();
                for (XWPFTable table : doc.getTables()) {
                    paragraphs += countTableParagraphs(table);
                }

                POIXMLProperties xmlProps = doc.getProperties();
                POIXMLProperties.CoreProperties core = xmlProps != null ? xmlProps.getCoreProperties() : null;
                POIXMLProperties.ExtendedProperties extended = xmlProps != null ? xmlProps.getExtendedProperties() : null;

                return WordMetadata.builder()
                        .pageCountHint(extended != null ? Math.max(0, extended.getPages()) : 0)
                        .paragraphCount(paragraphs)
                        .tableCount(doc.getTables().size())
                        .pictureCount(doc.getAllPictures().size())
                        .hasHeadersOrFooters(!doc.getHeaderList().isEmpty() || !doc.getFooterList().isEmpty())
                        .readonlyProtected(doc.isEnforcedReadonlyProtection())
                        .encrypted(isAesEncrypted)
                        .title(core != null ? core.getTitle() : null)
                        .author(core != null ? core.getCreator() : null)
                        .subject(core != null ? core.getSubject() : null)
                        .keywords(core != null ? core.getKeywords() : null)
                        .creator(extended != null ? extended.getApplication() : null)
                        .creationDate(core != null && core.getCreated() != null ? core.getCreated().toInstant() : null)
                        .modificationDate(core != null && core.getModified() != null ? core.getModified().toInstant() : null)
                        .build();
            }
        } catch (WordRenderException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.DOCUMENT_OPERATION_FAILED,
                    "探查 Word 文档元数据失败: " + e.getMessage(), e);
        }
    }

    private int countTableParagraphs(XWPFTable table) {
        int count = 0;
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                count += cell.getParagraphs().size();
                for (XWPFTable nested : cell.getTables()) {
                    count += countTableParagraphs(nested);
                }
            }
        }
        return count;
    }

    /**
     * 渲染 Word 模板并经由当前绑定的 {@link DocumentConverter} SPI 转换为 PDF 输出至目标
     *
     * @param source 模板数据源
     * @param data   渲染数据模型
     * @param target 目标 PDF 输出对象
     */
    public void renderToPdf(Object source, Object data, Object target) {
        renderToPdf(source, data, target, null, this.documentConverter);
    }

    /**
     * 渲染 Word 模板并委托指定 {@link DocumentConverter} SPI 转换为 PDF 输出至目标
     *
     * @param source    模板数据源
     * @param data      渲染数据模型
     * @param target    目标 PDF 输出对象
     * @param converter 指定的文档转换器 SPI
     */
    public void renderToPdf(Object source, Object data, Object target, DocumentConverter converter) {
        renderToPdf(source, data, target, null, converter);
    }

    /**
     * 使用自定义 poi-tl 配置渲染 Word 模板并委托 {@link DocumentConverter} SPI 转换为 PDF 输出至目标
     *
     * @param source    模板数据源
     * @param data      渲染数据模型
     * @param target    目标 PDF 输出对象
     * @param configure 自定义 poi-tl 配置（为 null 时使用默认配置）
     * @param converter 文档转换器 SPI（为 null 时使用当前门面绑定的默认转换器）
     */
    public void renderToPdf(Object source, Object data, Object target, Configure configure, DocumentConverter converter) {
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        byte[] pdfBytes = renderToPdfToBytes(source, data, configure, converter);
        writeBytesToTarget(pdfBytes, target, DocumentResultCode.CONVERSION_FAILED, "写出渲染后的 PDF 字节流失败");
    }

    /**
     * 渲染 Word 模板并经由当前绑定的 {@link DocumentConverter} SPI 导出 PDF 字节数组
     *
     * @param source 模板数据源
     * @param data   渲染数据模型
     * @return 转换后的 PDF 二进制字节数据
     */
    public byte[] renderToPdfToBytes(Object source, Object data) {
        return renderToPdfToBytes(source, data, null, this.documentConverter);
    }

    /**
     * 渲染 Word 模板并委托指定 {@link DocumentConverter} SPI 导出 PDF 字节数组
     *
     * @param source    模板数据源
     * @param data      渲染数据模型
     * @param converter 指定的文档转换器 SPI
     * @return 转换后的 PDF 二进制字节数据
     */
    public byte[] renderToPdfToBytes(Object source, Object data, DocumentConverter converter) {
        return renderToPdfToBytes(source, data, null, converter);
    }

    /**
     * 使用自定义 poi-tl 配置渲染 Word 模板并委托 {@link DocumentConverter} SPI 导出 PDF 字节数组
     *
     * @param source    模板数据源
     * @param data      渲染数据模型
     * @param configure 自定义 poi-tl 配置
     * @param converter 文档转换器 SPI
     * @return 转换后的 PDF 二进制字节数据
     */
    public byte[] renderToPdfToBytes(Object source, Object data, Configure configure, DocumentConverter converter) {
        DocumentConverter effectiveConverter = converter != null ? converter : this.documentConverter;
        if (effectiveConverter == null) {
            throw new WordRenderException(DocumentResultCode.CONVERSION_FAILED, "未配置可用的 DocumentConverter 转换器 SPI");
        }
        byte[] docxBytes = renderToBytes(source, data, configure);
        return effectiveConverter.docxToPdf(new ByteArrayInputStream(docxBytes));
    }

    private void writeBytesToTarget(byte[] data, Object target, DocumentResultCode errorCode, String errorMessage) {
        OutputStream os = null;
        try {
            os = resolveOutputStream(target);
            os.write(data);
            os.flush();
        } catch (IOException e) {
            throw new WordRenderException(errorCode, errorMessage + ": " + e.getMessage(), e);
        } finally {
            closeOutputStreamQuietly(os, target);
        }
    }

    private void closeOutputStreamQuietly(OutputStream os, Object target) {
        if (os != null && !(target instanceof OutputStream)) {
            try {
                os.close();
            } catch (Exception ignored) {
            }
        }
    }

    private byte[] readSourceBytesBounded(Object source) {
        InputStream is = resolveInputStream(source);
        try {
            return DocumentWebHelper.readBytesBounded(is, resolveMaxDocumentSizeBytes());
        } catch (WordRenderException e) {
            throw e;
        } catch (DocumentException e) {
            throw new WordRenderException(e.getResultCode(), e.getMessage(), e);
        } catch (IOException e) {
            throw new WordRenderException(DocumentResultCode.DOCUMENT_OPERATION_FAILED,
                    "读取 Word 文档字节内容失败: " + e.getMessage(), e);
        } finally {
            if (!(source instanceof InputStream)) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private byte[] decryptToPlainBytes(byte[] encryptedBytes, String password) {
        try {
            FileMagic magic = FileMagic.valueOf(encryptedBytes);
            if (magic != FileMagic.OLE2) {
                throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR,
                        "待解密文档并非加密的 OLE2 复合文档信封，当前格式: " + magic);
            }
            try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(encryptedBytes))) {
                if (!fs.getRoot().hasEntry(EncryptionInfo.ENCRYPTION_INFO_ENTRY)) {
                    throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR,
                            "OLE2 复合文档缺失 EncryptionInfo 加密描述条目");
                }
                EncryptionInfo info = new EncryptionInfo(fs);
                Decryptor decryptor = Decryptor.getInstance(info);
                if (!decryptor.verifyPassword(password)) {
                    throw new WordRenderException(DocumentResultCode.PASSWORD_ERROR, "Word 文档打开密码错误");
                }
                try (InputStream decIs = decryptor.getDataStream(fs)) {
                    return DocumentWebHelper.readBytesBounded(decIs, resolveMaxDocumentSizeBytes());
                }
            }
        } catch (WordRenderException e) {
            throw e;
        } catch (DocumentException e) {
            throw new WordRenderException(e.getResultCode(), e.getMessage(), e);
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.ENCRYPTION_ERROR,
                    "Word 文件级解密失败: " + e.getMessage(), e);
        }
    }

    private WordRenderException unwrapWordRenderException(Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth < 16) {
            if (cursor instanceof WordRenderException wre) {
                return wre;
            }
            cursor = cursor.getCause();
            depth++;
        }
        return null;
    }

    private void ensureSettingsInitialized(XWPFDocument doc) {
        if (doc.getSettings() == null) {
            try {
                POIXMLDocumentPart part = doc.createRelationship(XWPFRelation.SETTINGS, XWPFFactory.getInstance());
                if (part instanceof XWPFSettings settings) {
                    Field field = XWPFDocument.class.getDeclaredField("settings");
                    field.setAccessible(true);
                    field.set(doc, settings);
                }
            } catch (Exception ex) {
                log.warn("防御性初始化 Word 文档 settings 失败: {}", ex.getMessage());
            }
        }
    }
}
