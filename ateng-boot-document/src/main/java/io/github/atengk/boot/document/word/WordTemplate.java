package io.github.atengk.boot.document.word;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.config.ConfigureBuilder;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import com.deepoove.poi.util.TableTools;
import com.deepoove.poi.xwpf.NiceXWPFDocument;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.WordRenderException;
import io.github.atengk.boot.document.properties.DocumentWordProperties;
import io.github.atengk.boot.document.word.policy.FloatingPictureRenderPolicy;
import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.poifs.crypt.HashAlgorithm;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFactory;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.apache.poi.xwpf.usermodel.XWPFSettings;
import org.apache.poi.xwpf.usermodel.XWPFTable;
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
import java.util.List;
import java.util.Objects;

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

    /**
     * 使用指定配置构造 WordTemplate 门面实例
     *
     * @param wordProperties Word 文档配置属性
     */
    public WordTemplate(DocumentWordProperties wordProperties) {
        this.wordProperties = wordProperties != null ? wordProperties : new DocumentWordProperties();
    }

    /**
     * 使用缺省配置构造 WordTemplate 门面实例
     */
    public WordTemplate() {
        this(new DocumentWordProperties());
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
        try (XWPFTemplate template = compile(source, configToUse);
             OutputStream os = resolveOutputStream(target)) {
            template.render(data != null ? data : new Object());
            template.write(os);
            os.flush();
        } catch (WordRenderException e) {
            throw e;
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                    "渲染 Word 模板失败: " + e.getMessage(), e);
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
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                    "渲染 Word 模板并导出字节失败: " + e.getMessage(), e);
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
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED,
                    "编译 Word 模板异常 [" + source + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 创建默认的 poi-tl 配置构建器（预置 SpringEL 支持与常用标签策略）
     *
     * @return ConfigureBuilder 实例
     */
    public ConfigureBuilder configureBuilder() {
        ConfigureBuilder builder = Configure.builder();
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
     * 将多源模板入参自适应解析为可用输入流
     *
     * @param source 多源模板输入
     * @return 模板二进制输入流
     * @throws WordRenderException 模板不存在或解析失败时抛出
     */
    public InputStream resolveInputStream(Object source) {
        Objects.requireNonNull(source, "模板输入源 source 不能为空");

        if (source instanceof InputStream is) {
            return is;
        }
        if (source instanceof byte[] bytes) {
            return new ByteArrayInputStream(bytes);
        }
        if (source instanceof File file) {
            if (!file.exists() || !file.isFile() || !file.canRead()) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "Word 模板物理文件不存在或不可读: " + file.getAbsolutePath());
            }
            try {
                return new FileInputStream(file);
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
                return Files.newInputStream(path);
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
                return resource.getInputStream();
            } catch (IOException e) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "无法读取 Word 模板 Spring Resource: " + resource.getDescription(), e);
            }
        }
        if (source instanceof String str) {
            String path = str.trim();
            if (path.isEmpty()) {
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND, "Word 模板路径不能为空");
            }
            // 1. 显式 classpath: 前缀解析
            if (path.startsWith(CLASSPATH_PREFIX)) {
                InputStream is = loadClasspathStream(path.substring(CLASSPATH_PREFIX.length()));
                if (is != null) {
                    return is;
                }
                throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                        "未找到指定的类路径 Word 模板: " + path);
            }

            // 2. 本地绝对或相对物理文件检查
            File file = new File(path);
            if (file.exists() && file.isFile() && file.canRead()) {
                try {
                    return new FileInputStream(file);
                } catch (IOException e) {
                    throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                            "读取物理 Word 模板文件失败: " + file.getAbsolutePath(), e);
                }
            }

            // 3. 基于 templateBaseDir 组合探测
            String baseDir = wordProperties.getTemplateBaseDir();
            if (baseDir != null && !baseDir.trim().isEmpty()) {
                String normalizedBase = baseDir.trim();
                String combined = normalizedBase.endsWith("/") || path.startsWith("/")
                        ? normalizedBase + (path.startsWith("/") ? path.substring(1) : path)
                        : normalizedBase + "/" + path;
                if (combined.startsWith(CLASSPATH_PREFIX)) {
                    InputStream is = loadClasspathStream(combined.substring(CLASSPATH_PREFIX.length()));
                    if (is != null) {
                        return is;
                    }
                } else {
                    File combinedFile = new File(combined);
                    if (combinedFile.exists() && combinedFile.isFile() && combinedFile.canRead()) {
                        try {
                            return new FileInputStream(combinedFile);
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
                return fallbackIs;
            }

            throw new WordRenderException(DocumentResultCode.TEMPLATE_NOT_FOUND,
                    "未能定位到 Word 模板文件: " + path);
        }

        throw new IllegalArgumentException("不支持的模板输入源类型: " + source.getClass().getName());
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
            return resolveOutputStream(new File(str.trim()));
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
        Objects.requireNonNull(target, "目标输出 target 不能为空");
        if (docxSources == null || docxSources.isEmpty()) {
            throw new IllegalArgumentException("待拼装的 Word 文档输入列表 docxSources 不能为空");
        }

        try {
            NiceXWPFDocument mainDoc = null;
            List<NiceXWPFDocument> subDocs = new ArrayList<>();
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
                    if (i == 0) {
                        mainDoc = doc;
                    } else {
                        subDocs.add(doc);
                    }
                }

                if (!subDocs.isEmpty()) {
                    mainDoc = mainDoc.merge(subDocs, mainDoc.createParagraph().createRun());
                }

                NiceXWPFDocument finalDoc = mainDoc.generate();
                OutputStream os = resolveOutputStream(target);
                try {
                    finalDoc.write(os);
                    os.flush();
                } finally {
                    finalDoc.close();
                    if (!(target instanceof OutputStream)) {
                        os.close();
                    }
                }
            } finally {
                if (mainDoc != null) {
                    try {
                        mainDoc.close();
                    } catch (Exception ignored) {
                    }
                }
                for (NiceXWPFDocument d : subDocs) {
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
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED, "多文档拼装合并失败: " + e.getMessage(), e);
        }
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
     * 将多个独立的 Word 文档物理无缝拼装并导出为二进制字节数组
     *
     * @param docxSources 待拼装的文档列表
     * @return 拼装后的 Word 二进制数据
     */
    public byte[] stitchToBytes(List<?> docxSources) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stitch(baos, docxSources);
        return baos.toByteArray();
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
     * 为 Word 文档施加只读密码锁定保护（Read-Only Protection with Password）
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
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED, "校验 Word 文档保护密码失败: " + e.getMessage(), e);
        }
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
