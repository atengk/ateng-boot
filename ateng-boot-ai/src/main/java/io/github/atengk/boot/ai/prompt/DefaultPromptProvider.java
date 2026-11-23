package io.github.atengk.boot.ai.prompt;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiPromptProperties;
import io.github.atengk.boot.core.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 默认基于 Classpath / 本地文件系统 YAML 与 Markdown 资源的提示词资产提供者
 * <p>
 * 强制通过 Core {@link FileUtil} 执行路径穿越清洗防御，支持 YAML 单条/批量列表定义以及带 YAML Frontmatter 的 Markdown 资产文件。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class DefaultPromptProvider implements PromptProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultPromptProvider.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".yml", ".yaml", ".md");

    private final AtengAiPromptProperties properties;
    private final PathMatchingResourcePatternResolver resourceResolver;

    public DefaultPromptProvider(AtengAiPromptProperties properties) {
        this.properties = properties != null ? properties : new AtengAiPromptProperties();
        this.resourceResolver = new PathMatchingResourcePatternResolver();
    }

    @Override
    public List<PromptDefinition> loadAll() {
        List<String> locations = properties.getLocations();
        if (locations == null || locations.isEmpty()) {
            return Collections.emptyList();
        }

        List<PromptDefinition> definitions = new ArrayList<>();
        Set<String> visitedResources = new HashSet<>();

        for (String rawLocation : locations) {
            if (!StringUtils.hasText(rawLocation)) {
                continue;
            }
            List<String> resolvedPatterns = sanitizeAndExpandPatterns(rawLocation);
            for (String pattern : resolvedPatterns) {
                try {
                    Resource[] resources = resolveResources(pattern);
                    for (Resource resource : resources) {
                        if (resource == null || !resource.exists() || !resource.isReadable()) {
                            continue;
                        }
                        String uniqueDesc = resource.getDescription();
                        if (!visitedResources.add(uniqueDesc)) {
                            continue;
                        }
                        parseResource(resource, definitions);
                    }
                } catch (FileNotFoundException e) {
                    log.debug("[AI-PROMPT] 提示词资源目录暂不存在，跳过加载: {}", pattern);
                } catch (AiException ae) {
                    throw ae;
                } catch (Exception e) {
                    throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                            "扫描提示词资源失败 [" + pattern + "]: " + e.getMessage(), e);
                }
            }
        }
        return definitions;
    }

    /**
     * 校验并规范化外部路径或 Ant 通配模式，依托 Core FileUtil 拦截路径穿越攻击
     */
    List<String> sanitizeAndExpandPatterns(String rawLocation) {
        String trimmed = rawLocation.trim();
        if (trimmed.indexOf('\0') >= 0) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "检测到非法空字符路径: " + rawLocation);
        }

        String scheme = "";
        String pathBody = trimmed;
        if (trimmed.startsWith("classpath*:")) {
            scheme = "classpath*:";
            pathBody = trimmed.substring("classpath*:".length());
        } else if (trimmed.startsWith("classpath:")) {
            scheme = "classpath:";
            pathBody = trimmed.substring("classpath:".length());
        } else if (trimmed.startsWith("file:")) {
            scheme = "file:";
            pathBody = trimmed.substring("file:".length());
        }

        // 检查是否存在 '..' 相对逃逸片段（防止先规范化后掩盖非法穿越输入）
        String normalizedSlashes = pathBody.replace('\\', '/');
        for (String seg : normalizedSlashes.split("/+")) {
            if ("..".equals(seg)) {
                throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                        "检测到非法提示词资源路径穿越尝试: " + rawLocation);
            }
        }

        // 分离 Windows 盘符前缀（如 D:/...），避免 cleanPath 对相对/绝对路径差异误判
        String windowsDrivePrefix = "";
        String cleanTarget = normalizedSlashes;
        if (cleanTarget.length() >= 2 && Character.isLetter(cleanTarget.charAt(0)) && cleanTarget.charAt(1) == ':') {
            windowsDrivePrefix = cleanTarget.substring(0, 2);
            cleanTarget = cleanTarget.substring(2);
        }

        String cleanedBody;
        try {
            cleanedBody = FileUtil.cleanPath(cleanTarget.isEmpty() ? "/" : cleanTarget);
        } catch (IllegalArgumentException e) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                    "提示词资源路径不合法 [" + rawLocation + "]: " + e.getMessage(), e);
        }

        String fullCleanPath = windowsDrivePrefix + cleanedBody;
        if (!StringUtils.hasText(scheme) && isExistingFilesystemPath(fullCleanPath)) {
            scheme = "file:";
        } else if (!StringUtils.hasText(scheme)) {
            scheme = "classpath:";
        }

        String normalized = scheme + ("classpath:".equals(scheme) || "classpath*:".equals(scheme)
                ? stripLeadingSlash(fullCleanPath)
                : fullCleanPath);

        if (hasWildcard(normalized) || isSupportedFile(normalized)) {
            return List.of(normalized);
        }

        String dir = normalized.endsWith("/") ? normalized : normalized + "/";
        return List.of(dir + "**/*.md", dir + "**/*.yml", dir + "**/*.yaml");
    }

    private Resource[] resolveResources(String pattern) throws IOException {
        if (pattern.startsWith("file:") && !hasWildcard(pattern)) {
            Path path = Path.of(pattern.substring("file:".length()));
            if (!Files.exists(path)) {
                return new Resource[0];
            }
            return new Resource[]{new FileSystemResource(path)};
        }
        return resourceResolver.getResources(pattern);
    }

    private void parseResource(Resource resource, List<PromptDefinition> target) {
        String rawFilename = resource.getFilename();
        if (!StringUtils.hasText(rawFilename)) {
            return;
        }
        String cleanName = FileUtil.cleanFilename(rawFilename);
        if (!StringUtils.hasText(cleanName)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "提示词资源文件名不合法: " + resource.getDescription());
        }

        String contentText;
        try (InputStream in = resource.getInputStream()) {
            contentText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                    "读取提示词资源失败 [" + cleanName + "]: " + e.getMessage(), e);
        }

        String lowerName = cleanName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".yml") || lowerName.endsWith(".yaml")) {
            parseYamlDefinitions(contentText, cleanName, target);
        } else if (lowerName.endsWith(".md")) {
            target.add(parseMarkdownDefinition(contentText, cleanName));
        }
    }

    private void parseYamlDefinitions(String yamlText, String filename, List<PromptDefinition> target) {
        if (!StringUtils.hasText(yamlText)) {
            return;
        }
        Yaml yaml = createSafeYaml();
        Object loaded = yaml.load(yamlText);
        if (loaded == null) {
            return;
        }
        String defaultCode = stripExtension(filename);
        if (loaded instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    target.add(definitionFromMap(map, defaultCode));
                }
            }
            return;
        }
        if (loaded instanceof Map<?, ?> map) {
            Object nestedPrompts = map.get("prompts");
            if (nestedPrompts instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> itemMap) {
                        target.add(definitionFromMap(itemMap, defaultCode));
                    }
                }
                return;
            }
            target.add(definitionFromMap(map, defaultCode));
        }
    }

    private PromptDefinition parseMarkdownDefinition(String markdownText, String filename) {
        String defaultCode = stripExtension(filename);
        String trimmed = markdownText != null ? markdownText : "";
        String body = trimmed;
        Map<?, ?> frontmatter = null;

        if (trimmed.startsWith("---\n") || trimmed.startsWith("---\r\n")) {
            int firstBreak = trimmed.indexOf('\n');
            int[] closingBounds = findClosingFrontmatter(trimmed, firstBreak + 1);
            if (closingBounds != null) {
                String fmYaml = trimmed.substring(firstBreak + 1, closingBounds[0]);
                Object parsed = createSafeYaml().load(fmYaml);
                if (parsed instanceof Map<?, ?> map) {
                    frontmatter = map;
                }
                body = closingBounds[1] <= trimmed.length() ? trimmed.substring(closingBounds[1]) : "";
            }
        }

        if (frontmatter != null) {
            PromptDefinition.Builder builder = builderFromMetadata(frontmatter, defaultCode);
            if (frontmatter.containsKey("content") && frontmatter.get("content") != null && !StringUtils.hasText(body)) {
                builder.content(String.valueOf(frontmatter.get("content")));
            } else {
                builder.content(body.trim());
            }
            return builder.build();
        }
        return PromptDefinition.of(defaultCode, body.trim());
    }

    private static int[] findClosingFrontmatter(String text, int startFrom) {
        int lineStart = startFrom;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            String line = (lineEnd >= 0 ? text.substring(lineStart, lineEnd) : text.substring(lineStart)).trim();
            if ("---".equals(line)) {
                int markerEnd = lineStart - 1;
                int bodyStart = lineEnd >= 0 ? lineEnd + 1 : text.length();
                return new int[]{markerEnd, bodyStart};
            }
            if (lineEnd < 0) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return null;
    }

    private static PromptDefinition definitionFromMap(Map<?, ?> map, String fallbackCode) {
        PromptDefinition.Builder builder = builderFromMetadata(map, fallbackCode);
        Object contentObj = map.get("content");
        if (contentObj != null) {
            builder.content(String.valueOf(contentObj));
        }
        return builder.build();
    }

    private static PromptDefinition.Builder builderFromMetadata(Map<?, ?> map, String fallbackCode) {
        Object codeObj = map.get("code");
        String code = (codeObj != null && StringUtils.hasText(String.valueOf(codeObj)))
                ? String.valueOf(codeObj).trim()
                : fallbackCode;
        PromptDefinition.Builder builder = PromptDefinition.builder().code(code);
        if (map.get("version") != null) {
            builder.version(String.valueOf(map.get("version")));
        }
        if (map.get("label") != null) {
            builder.label(String.valueOf(map.get("label")));
        }
        if (map.get("description") != null) {
            builder.description(String.valueOf(map.get("description")));
        }
        Object reqObj = map.get("requiredVariables");
        if (reqObj instanceof Collection<?> collection) {
            List<String> vars = new ArrayList<>();
            for (Object v : collection) {
                if (v != null && StringUtils.hasText(String.valueOf(v))) {
                    vars.add(String.valueOf(v).trim());
                }
            }
            builder.requiredVariables(vars);
        }
        return builder;
    }

    private static Yaml createSafeYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    private static boolean isSupportedFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWildcard(String path) {
        return path.contains("*") || path.contains("?");
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    private static String stripLeadingSlash(String path) {
        String result = path;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private static boolean isExistingFilesystemPath(String path) {
        try {
            return Files.exists(Path.of(path));
        } catch (Exception e) {
            return false;
        }
    }
}
