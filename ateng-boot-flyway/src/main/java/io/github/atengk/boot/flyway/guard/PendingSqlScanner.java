package io.github.atengk.boot.flyway.guard;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.extensibility.MigrationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 待执行 SQL 迁移脚本资源读取与高危 DDL 词法扫描辅助器
 *
 * @author Ateng
 * @since 2026-11-19
 */
final class PendingSqlScanner {

    private static final Logger log = LoggerFactory.getLogger(PendingSqlScanner.class);
    private static final String EXEMPTION_MARKER = "@ateng-allow-destructive-ddl";
    private static final Pattern DESTRUCTIVE_DDL_PATTERN = Pattern.compile(
            "(?i)\\b(DROP\\s+(?:TABLE|DATABASE|SCHEMA)|TRUNCATE)\\b"
    );

    private PendingSqlScanner() {
    }

    static boolean isSqlMigrationCandidate(MigrationInfo info, Configuration configuration) {
        if (info == null) {
            return false;
        }
        MigrationType type = info.getType();
        if (type == CoreMigrationType.JDBC
                || type == CoreMigrationType.BASELINE
                || type == CoreMigrationType.SCHEMA
                || type == CoreMigrationType.DELETE) {
            return false;
        }
        if (type == CoreMigrationType.SQL || type == CoreMigrationType.UNDO_SCRIPT) {
            return true;
        }
        String script = info.getScript();
        if (!StringUtils.hasText(script)) {
            return false;
        }
        String lower = script.toLowerCase(Locale.ROOT);
        if (configuration != null && configuration.getSqlMigrationSuffixes() != null) {
            for (String suffix : configuration.getSqlMigrationSuffixes()) {
                if (StringUtils.hasText(suffix) && lower.endsWith(suffix.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return lower.endsWith(".sql");
    }

    static String readPendingSqlContent(MigrationInfo info, Configuration configuration) {
        Charset charset = configuration != null && configuration.getEncoding() != null
                ? configuration.getEncoding()
                : StandardCharsets.UTF_8;
        ResourceProvider resourceProvider = configuration != null ? configuration.getResourceProvider() : null;
        if (resourceProvider != null) {
            String fromProvider = readFromResourceProvider(resourceProvider, info.getPhysicalLocation());
            if (fromProvider != null) {
                return fromProvider;
            }
            fromProvider = readFromResourceProvider(resourceProvider, info.getScript());
            if (fromProvider != null) {
                return fromProvider;
            }
        }

        ClassLoader classLoader = configuration != null && configuration.getClassLoader() != null
                ? configuration.getClassLoader()
                : PendingSqlScanner.class.getClassLoader();

        String physical = info.getPhysicalLocation();
        if (StringUtils.hasText(physical)) {
            String content = readFromPathOrResource(physical.trim(), charset, classLoader);
            if (content != null) {
                return content;
            }
        }

        String script = info.getScript();
        if (StringUtils.hasText(script) && configuration != null && configuration.getLocations() != null) {
            for (Location location : configuration.getLocations()) {
                if (location == null) {
                    continue;
                }
                if (location.isFileSystem()) {
                    try {
                        Path candidate = Path.of(location.getPath(), script);
                        if (Files.isRegularFile(candidate)) {
                            return Files.readString(candidate, charset);
                        }
                    } catch (InvalidPathException | IOException e) {
                        log.debug("读取文件系统迁移脚本失败 [script={}]: {}",
                                script, FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
                    }
                } else if (location.isClassPath()) {
                    String base = location.getPath();
                    String combined = StringUtils.hasText(base)
                            ? (base.endsWith("/") ? base + script : base + "/" + script)
                            : script;
                    String content = readFromClassPath(combined, charset, classLoader);
                    if (content != null) {
                        return content;
                    }
                }
            }
        }
        return null;
    }

    static DetectionResult detectDestructiveDdl(String sqlContent) {
        ScanResult scanResult = scanSqlScript(sqlContent);
        Matcher matcher = DESTRUCTIVE_DDL_PATTERN.matcher(scanResult.strippedSql());
        if (!matcher.find()) {
            return null;
        }
        String matchedStatement = matcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return new DetectionResult(matchedStatement, scanResult.hasExemptionAnnotation());
    }

    private static String readFromResourceProvider(ResourceProvider provider, String location) {
        if (!StringUtils.hasText(location)) {
            return null;
        }
        LoadableResource resource = provider.getResource(location);
        if (resource == null) {
            return null;
        }
        try (Reader reader = resource.read()) {
            if (reader == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            log.debug("通过 Flyway ResourceProvider 读取脚本失败 [location={}]: {}",
                    location, FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
            return null;
        }
    }

    private static String readFromPathOrResource(String location, Charset charset, ClassLoader classLoader) {
        if (location.startsWith("classpath:")) {
            return readFromClassPath(location.substring("classpath:".length()), charset, classLoader);
        }
        if (location.startsWith("file:") || location.startsWith("jar:")) {
            try (InputStream in = URI.create(location).toURL().openStream()) {
                return new String(in.readAllBytes(), charset);
            } catch (IOException | IllegalArgumentException e) {
                log.debug("通过 URL 读取迁移脚本失败 [location={}]: {}",
                        location, FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
                return null;
            }
        }
        try {
            Path path = Path.of(location);
            if (Files.isRegularFile(path)) {
                return Files.readString(path, charset);
            }
        } catch (InvalidPathException | IOException e) {
            log.debug("通过本地路径读取迁移脚本失败 [location={}]: {}",
                    location, FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
        }
        return readFromClassPath(location, charset, classLoader);
    }

    private static String readFromClassPath(String resourcePath, Charset charset, ClassLoader classLoader) {
        if (!StringUtils.hasText(resourcePath) || classLoader == null) {
            return null;
        }
        String normalized = resourcePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        try (InputStream in = classLoader.getResourceAsStream(normalized)) {
            if (in != null) {
                return new String(in.readAllBytes(), charset);
            }
        } catch (IOException e) {
            log.debug("读取类路径迁移脚本失败 [resource={}]: {}",
                    normalized, FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
        }
        return null;
    }

    /**
     * 纯 JDK 17 SQL 词法状态机：剥离注释与单引号/双引号/反引号字面量，仅保留注释内的显式豁免指令判定
     */
    private static ScanResult scanSqlScript(String sql) {
        StringBuilder stripped = new StringBuilder(sql.length());
        boolean hasExemption = false;
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);
            char next = i + 1 < len ? sql.charAt(i + 1) : '\0';

            // 1. 单行注释 -- ...
            if (c == '-' && next == '-') {
                int start = i + 2;
                int end = start;
                while (end < len && sql.charAt(end) != '\n' && sql.charAt(end) != '\r') {
                    end++;
                }
                if (containsExemptionMarker(sql, start, end)) {
                    hasExemption = true;
                }
                stripped.append(' ');
                i = end;
                continue;
            }

            // 2. 块注释 /* ... */
            if (c == '/' && next == '*') {
                int start = i + 2;
                int closeIdx = sql.indexOf("*/", start);
                int end = closeIdx >= 0 ? closeIdx : len;
                if (containsExemptionMarker(sql, start, end)) {
                    hasExemption = true;
                }
                stripped.append(' ');
                i = closeIdx >= 0 ? closeIdx + 2 : len;
                continue;
            }

            // 3. 单引号字符串字面量 '...'（遵循标准 SQL '' 转义，防止以反斜杠结尾路径字符串绕过）
            if (c == '\'') {
                i = advanceQuoted(sql, i + 1, '\'');
                stripped.append(' ');
                continue;
            }

            // 4. 双引号标识符 "..."
            if (c == '"') {
                i = advanceQuoted(sql, i + 1, '"');
                stripped.append(' ');
                continue;
            }

            // 5. 反引号标识符 `...`
            if (c == '`') {
                i = advanceQuoted(sql, i + 1, '`');
                stripped.append(' ');
                continue;
            }

            stripped.append(c);
            i++;
        }
        return new ScanResult(hasExemption, stripped.toString());
    }

    private static int advanceQuoted(String sql, int start, char quoteChar) {
        int len = sql.length();
        int i = start;
        while (i < len) {
            if (sql.charAt(i) == quoteChar) {
                if (i + 1 < len && sql.charAt(i + 1) == quoteChar) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static boolean containsExemptionMarker(String sql, int start, int end) {
        if (end <= start) {
            return false;
        }
        String commentText = sql.substring(start, end).toLowerCase(Locale.ROOT);
        int idx = commentText.indexOf(EXEMPTION_MARKER);
        if (idx < 0) {
            return false;
        }
        int afterIdx = idx + EXEMPTION_MARKER.length();
        return afterIdx >= commentText.length() || Character.isWhitespace(commentText.charAt(afterIdx));
    }

    record DetectionResult(String statement, boolean exempted) {
    }

    private record ScanResult(boolean hasExemptionAnnotation, String strippedSql) {
    }
}
