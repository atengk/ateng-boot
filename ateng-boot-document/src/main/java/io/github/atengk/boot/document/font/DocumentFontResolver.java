package io.github.atengk.boot.document.font;

import io.github.atengk.boot.document.exception.DocumentFontException;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.properties.DocumentPdfProperties;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * CJK 中文字体云原生三级弹性探测器与加载解析器
 * <p>
 * 负责在云原生容器与异构操作系统环境中自适应定位可用中文字体，
 * 严格按照“1. 外部显式配置路径 -> 2. 宿主机 OS 目录扫描探测 -> 3. 缺失时快速失败抛出指导性异常”三级阶梯解析，
 * 并通过线程安全内存缓存避免重复扫描磁盘。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class DocumentFontResolver {

    private static final Logger log = LoggerFactory.getLogger(DocumentFontResolver.class);

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final byte[] TTC_MAGIC = new byte[]{(byte) 0x74, (byte) 0x74, (byte) 0x63, (byte) 0x66};

    private static final Map<String, List<String>> FONT_KEYWORD_MAP = Map.of(
            "simsun", List.of("simsun", "sursong", "songti", "stsong"),
            "microsoft yahei", List.of("msyh", "yahei"),
            "source han sans cn", List.of("sourcehansans", "source-han-sans", "notosanscjk", "notosanssc", "notoserifcjk"),
            "wenquanyi micro hei", List.of("wqy-microhei", "wqy-zenhei", "wenquanyi"),
            "pingfang sc", List.of("pingfang")
    );

    private static final List<String> FALLBACK_KEYWORDS = List.of(
            "simhei", "simkai", "simfang", "kaiti", "fangsong", "heiti"
    );

    private final DocumentPdfProperties pdfProperties;
    private final List<String> customSearchDirectories;
    private final AtomicReference<ResolvedFont> cachedFont = new AtomicReference<>();

    /**
     * 使用指定 PDF 配置属性构造解析器
     *
     * @param pdfProperties PDF 文档配置属性
     */
    public DocumentFontResolver(DocumentPdfProperties pdfProperties) {
        this(pdfProperties, Collections.emptyList());
    }

    /**
     * 具备自定义搜索目录注入能力的构造器（专供容器定制或测试隔离）
     *
     * @param pdfProperties           PDF 文档配置属性
     * @param customSearchDirectories 自定义系统字体扫描目录列表
     */
    public DocumentFontResolver(DocumentPdfProperties pdfProperties, List<String> customSearchDirectories) {
        this.pdfProperties = pdfProperties != null ? pdfProperties : new DocumentPdfProperties();
        this.customSearchDirectories = customSearchDirectories != null
                ? new ArrayList<>(customSearchDirectories)
                : Collections.emptyList();
    }

    /**
     * 使用缺省配置构造解析器
     */
    public DocumentFontResolver() {
        this(new DocumentPdfProperties());
    }

    /**
     * 执行三级阶梯字体探测并获取中文字体对象（具备线程安全只读缓存）
     *
     * @return 已解析的中文字体持有对象
     * @throws DocumentFontException 当所有阶梯均未找到可用中文字体时抛出
     */
    public ResolvedFont resolveFont() {
        ResolvedFont font = cachedFont.get();
        if (font != null) {
            return font;
        }
        synchronized (this) {
            font = cachedFont.get();
            if (font != null) {
                return font;
            }
            font = doResolveFont();
            cachedFont.set(font);
            return font;
        }
    }

    /**
     * 将解析到的中文字体加载至目标 PDFBox 文档实例中
     *
     * @param document 目标 PDF 文档
     * @return 绑定的 Type0 字体实例
     * @throws DocumentFontException 字体解析或加载失败时抛出
     */
    public PDType0Font loadFont(PDDocument document) {
        Objects.requireNonNull(document, "document 不能为空");
        ResolvedFont resolved = resolveFont();
        return loadType0Font(document, resolved);
    }

    /**
     * 从指定自定义字体路径加载字体至目标 PDFBox 文档实例中
     *
     * @param document   目标 PDF 文档
     * @param customPath 自定义字体路径（支持绝对路径或 classpath: 前缀，若为空则回退到默认解析）
     * @return 绑定的 Type0 字体实例
     * @throws DocumentFontException 字体加载失败时抛出
     */
    public PDType0Font loadFont(PDDocument document, String customPath) {
        Objects.requireNonNull(document, "document 不能为空");
        if (customPath == null || customPath.trim().isEmpty()) {
            return loadFont(document);
        }
        ResolvedFont resolved = resolveExplicitPath(customPath.trim());
        return loadType0Font(document, resolved);
    }

    /**
     * 探测当前运行环境中是否存在可用的中文字体（不抛出异常）
     *
     * @return 若存在可用字体返回 true，否则返回 false
     */
    public boolean isFontAvailable() {
        try {
            return resolveFont() != null;
        } catch (DocumentFontException e) {
            return false;
        }
    }

    /**
     * 清理当前已缓存的字体解析结果（用于测试用例重置或动态重新加载）
     */
    public void clearCache() {
        cachedFont.set(null);
    }

    /**
     * 获取当前已缓存的解析字体（未解析时返回 null）
     *
     * @return 已缓存的字体对象或 null
     */
    public ResolvedFont getCachedFont() {
        return cachedFont.get();
    }

    /**
     * 获取绑定的 PDF 属性配置
     *
     * @return PDF 属性配置
     */
    public DocumentPdfProperties getPdfProperties() {
        return pdfProperties;
    }

    /**
     * 实际执行三级阶梯解析核心流程
     */
    private ResolvedFont doResolveFont() {
        // 1. 一级：外部显式配置路径优先
        String fontPath = pdfProperties.getFontPath();
        if (fontPath != null && !fontPath.trim().isEmpty()) {
            ResolvedFont explicit = resolveExplicitPath(fontPath.trim());
            log.info("【DocumentFontResolver】通过一级配置成功加载中文字体 [{}], 来源: {}", explicit.fontName(), explicit.location());
            return explicit;
        }

        // 2. 二级：宿主机系统字体目录自适应枚举探测
        List<String> searchDirs = resolveSearchDirectories();
        ResolvedFont systemFont = scanSystemDirectories(searchDirs);
        if (systemFont != null) {
            log.info("【DocumentFontResolver】通过二级系统探测成功获取中文字体 [{}], 物理路径: {}", systemFont.fontName(), systemFont.location());
            return systemFont;
        }

        // 3. 三级：前置快速失败防卫并输出 Docker 指南
        String errorMsg = buildFailureGuideMessage(searchDirs);
        log.error(errorMsg);
        throw new DocumentFontException(DocumentResultCode.FONT_NOT_FOUND, errorMsg);
    }

    /**
     * 解析外部显式配置的字体路径（支持 classpath: 与本地绝对路径）
     */
    private ResolvedFont resolveExplicitPath(String path) {
        if (path.startsWith(CLASSPATH_PREFIX)) {
            String resourcePath = path.substring(CLASSPATH_PREFIX.length()).trim();
            if (resourcePath.startsWith("/")) {
                resourcePath = resourcePath.substring(1);
            }
            try (InputStream is = obtainResourceStream(resourcePath)) {
                if (is == null) {
                    throw new DocumentFontException(DocumentResultCode.FONT_NOT_FOUND,
                            "未找到指定的类路径中文字体文件: " + path);
                }
                byte[] bytes = is.readAllBytes();
                boolean isTtc = checkIsTtc(path, bytes);
                String fontName = extractFontName(resourcePath);
                return new ResolvedFont(fontName, FontResolutionTier.CONFIG, path, null, bytes, isTtc);
            } catch (IOException e) {
                throw new DocumentFontException(DocumentResultCode.FONT_NOT_FOUND,
                        "读取类路径中文字体文件失败 [" + path + "]: " + e.getMessage(), e);
            }
        }

        File file = new File(path);
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new DocumentFontException(DocumentResultCode.FONT_NOT_FOUND,
                    "未找到指定的中文字体物理文件或文件不可读: " + path);
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            boolean isTtc = checkIsTtc(file.getName(), bytes);
            String fontName = extractFontName(file.getName());
            return new ResolvedFont(fontName, FontResolutionTier.CONFIG, file.getAbsolutePath(), file, bytes, isTtc);
        } catch (IOException e) {
            throw new DocumentFontException(DocumentResultCode.FONT_NOT_FOUND,
                    "读取物理中文字体文件失败 [" + path + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 扫描系统标准字体目录中的候选字体
     */
    private ResolvedFont scanSystemDirectories(List<String> directories) {
        List<String> preferredList = pdfProperties.getPreferredFontNames();
        if (preferredList == null || preferredList.isEmpty()) {
            preferredList = List.of("SimSun", "Microsoft YaHei", "Source Han Sans CN", "WenQuanYi Micro Hei", "PingFang SC");
        }

        for (String candidate : preferredList) {
            List<String> keywords = resolveKeywordsForCandidate(candidate);
            for (String dirPath : directories) {
                File dir = new File(dirPath);
                if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) {
                    continue;
                }
                File matchedFile = findFontFileInDirectory(dir.toPath(), keywords);
                if (matchedFile != null) {
                    try {
                        byte[] bytes = Files.readAllBytes(matchedFile.toPath());
                        boolean isTtc = checkIsTtc(matchedFile.getName(), bytes);
                        return new ResolvedFont(candidate, FontResolutionTier.SYSTEM, matchedFile.getAbsolutePath(), matchedFile, bytes, isTtc);
                    } catch (IOException e) {
                        log.warn("读取探测到的系统字体文件失败 [{}]: {}", matchedFile.getAbsolutePath(), e.getMessage());
                    }
                }
            }
        }

        // 尝试兜底通用中文字体关键词
        for (String dirPath : directories) {
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) {
                continue;
            }
            File matchedFile = findFontFileInDirectory(dir.toPath(), FALLBACK_KEYWORDS);
            if (matchedFile != null) {
                try {
                    byte[] bytes = Files.readAllBytes(matchedFile.toPath());
                    boolean isTtc = checkIsTtc(matchedFile.getName(), bytes);
                    String name = extractFontName(matchedFile.getName());
                    return new ResolvedFont(name, FontResolutionTier.SYSTEM, matchedFile.getAbsolutePath(), matchedFile, bytes, isTtc);
                } catch (IOException e) {
                    log.warn("读取兜底系统字体文件失败 [{}]: {}", matchedFile.getAbsolutePath(), e.getMessage());
                }
            }
        }

        return null;
    }

    /**
     * 在指定目录下递归查找匹配关键词的字体文件（限制最大深度 5）
     */
    private File findFontFileInDirectory(Path startPath, List<String> keywords) {
        try (Stream<Path> stream = Files.walk(startPath, 5)) {
            return stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(file -> isSupportedFontFile(file.getName()))
                    .filter(file -> matchesAnyKeyword(file.getName(), keywords))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.debug("遍历字体目录异常 [{}]: {}", startPath, e.getMessage());
            return null;
        }
    }

    private boolean isSupportedFontFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ttf") || lower.endsWith(".ttc") || lower.endsWith(".otf");
    }

    private boolean matchesAnyKeyword(String fileName, List<String> keywords) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> resolveKeywordsForCandidate(String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT).trim();
        List<String> mapped = FONT_KEYWORD_MAP.get(lower);
        if (mapped != null && !mapped.isEmpty()) {
            return mapped;
        }
        String sanitized = lower.replaceAll("[^a-z0-9]", "");
        return List.of(lower, sanitized);
    }

    /**
     * 收集操作系统标准字体目录列表
     */
    private List<String> resolveSearchDirectories() {
        if (!customSearchDirectories.isEmpty()) {
            return customSearchDirectories;
        }

        List<String> dirs = new ArrayList<>();
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (osName.contains("win")) {
            String winDir = System.getenv("WINDIR");
            dirs.add(winDir != null ? winDir + "\\Fonts" : "C:\\Windows\\Fonts");
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isEmpty()) {
                dirs.add(localAppData + "\\Microsoft\\Windows\\Fonts");
            }
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            dirs.add("/System/Library/Fonts");
            dirs.add("/Library/Fonts");
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isEmpty()) {
                dirs.add(userHome + "/Library/Fonts");
            }
        } else {
            // Linux / Unix / Docker 容器环境
            dirs.add("/usr/share/fonts");
            dirs.add("/usr/local/share/fonts");
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isEmpty()) {
                dirs.add(userHome + "/.fonts");
                dirs.add(userHome + "/.local/share/fonts");
            }
        }

        return dirs.stream()
                .filter(d -> d != null && !d.trim().isEmpty())
                .filter(d -> Files.isDirectory(Paths.get(d)))
                .toList();
    }

    /**
     * 将已解析的中文字体安全绑定至 PDDocument 实例
     */
    private PDType0Font loadType0Font(PDDocument document, ResolvedFont font) {
        if (font.isCollection()) {
            return loadFromTtc(document, font);
        }

        try {
            if (font.file() != null) {
                return PDType0Font.load(document, font.file());
            } else {
                return PDType0Font.load(document, new ByteArrayInputStream(font.fontBytes()));
            }
        } catch (IOException e) {
            throw new DocumentFontException(DocumentResultCode.FONT_NOT_FOUND,
                    "加载中文字体失败 [" + font.fontName() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 从 TrueType Collection (.ttc) 中提取并加载字体
     */
    private PDType0Font loadFromTtc(PDDocument document, ResolvedFont font) {
        try {
            TrueTypeCollection ttc = font.file() != null
                    ? new TrueTypeCollection(font.file())
                    : new TrueTypeCollection(new ByteArrayInputStream(font.fontBytes()));
            try (ttc) {
                final TrueTypeFont[] matched = new TrueTypeFont[1];
                ttc.processAllFonts(ttf -> {
                    if (matched[0] == null) {
                        matched[0] = ttf;
                    }
                });
                if (matched[0] == null) {
                    throw new DocumentFontException(DocumentResultCode.FONT_NOT_FOUND,
                            "未能从 TrueType Collection 集合 [" + font.location() + "] 中提取出有效字体");
                }
                return PDType0Font.load(document, matched[0], true);
            }
        } catch (IOException e) {
            throw new DocumentFontException(DocumentResultCode.FONT_NOT_FOUND,
                    "加载 TrueType 集合字体失败 [" + font.fontName() + "]: " + e.getMessage(), e);
        }
    }

    private boolean checkIsTtc(String pathOrName, byte[] bytes) {
        if (bytes != null && bytes.length >= 4) {
            return bytes[0] == TTC_MAGIC[0]
                    && bytes[1] == TTC_MAGIC[1]
                    && bytes[2] == TTC_MAGIC[2]
                    && bytes[3] == TTC_MAGIC[3];
        }
        return pathOrName != null && pathOrName.toLowerCase(Locale.ROOT).endsWith(".ttc");
    }

    private String extractFontName(String path) {
        String name = path;
        int idx = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    private InputStream obtainResourceStream(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream is = cl != null ? cl.getResourceAsStream(resourcePath) : null;
        if (is == null) {
            is = DocumentFontResolver.class.getClassLoader().getResourceAsStream(resourcePath);
        }
        if (is == null) {
            is = ClassLoader.getSystemResourceAsStream(resourcePath);
        }
        return is;
    }

    /**
     * 构建包含清晰 Docker 修复指南的快速失败异常信息
     */
    private String buildFailureGuideMessage(List<String> scannedDirs) {
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        List<String> preferred = pdfProperties.getPreferredFontNames();

        return String.format(
                "未检测到可用的 CJK 中文字体，PDF 渲染中文将产生方块乱码或崩溃。%n"
                        + "【运行环境】操作系统: %s (%s)%n"
                        + "【扫描目录】%s%n"
                        + "【偏好字体】%s%n"
                        + "【解决建议】请通过以下方式之一修复：%n"
                        + "  1. 在 Dockerfile 中安装开源中文字体（推荐）：%n"
                        + "     - Debian / Ubuntu: RUN apt-get update && apt-get install -y fonts-wqy-microhei%n"
                        + "     - Alpine Linux:   RUN apk add --no-cache font-noto-cjk%n"
                        + "     - CentOS / RHEL:  RUN yum install -y wqy-microhei-fonts%n"
                        + "  2. 挂载宿主机字体目录到容器卷（如 -v /usr/share/fonts:/usr/share/fonts）%n"
                        + "  3. 在 application.yml 中显式配置字体路径（支持绝对路径或 classpath:）：%n"
                        + "     ateng.boot.document.pdf.font-path: /app/fonts/simsun.ttc 或 classpath:fonts/your-font.ttf",
                osName, osArch, scannedDirs, preferred
        );
    }
}
