package io.github.atengk.boot.excel.converter;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.excel.annotation.ExcelImage;
import io.github.atengk.boot.excel.properties.ExcelSecurityProperties;
import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.converters.Converter;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.GlobalConfiguration;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 智能多媒体图片转换器
 * <p>
 * 专为业务实体中广泛使用的 {@code String} 类型图片字段打造，零配置自动识别并分发：
 * <ul>
 *     <li>网络 HTTP/HTTPS 图片 URL（内置内网/环回/云元数据 SSRF 拦截、连接与读取超时控制及单图字节上限有界读取）</li>
 *     <li>Base64 数据流（{@code data:image/...;base64,...}，同样受单图容量上限限制）</li>
 *     <li>本地受控文件路径（借助 {@link FileUtil} 归一化清洗路径，阻断 {@code ..} 穿越与系统敏感目录，并按需强制基目录隔离）</li>
 * </ul>
 * 仅对标注了 {@link ExcelImage} 的字段生效；未标注该注解的常规文本字段毫秒级快速直通，杜绝误转换与性能损耗。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class SmartImageConverter implements Converter<String> {

    private static final Logger log = LoggerFactory.getLogger(SmartImageConverter.class);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REDIRECT_HOPS = 3;
    private static final Pattern URI_SCHEME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]{1,}:.*");
    private static final Pattern WINDOWS_SENSITIVE_SYSTEM_PATTERN = Pattern.compile("^[a-z]:/windows(/|$)");
    private static final String[] POSIX_SENSITIVE_PREFIXES = {
            "/etc", "/proc", "/sys", "/dev", "/boot", "/root", "/bin", "/sbin", "/usr/bin", "/usr/sbin", "/var/run", "/private/etc"
    };
    private static final String DEFAULT_USER_AGENT;

    static {
        String version = SmartImageConverter.class.getPackage().getImplementationVersion();
        if (version != null && !version.isBlank()) {
            DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ateng-boot-excel/" + version;
        } else {
            DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ateng-boot-excel";
        }
    }

    @Override
    public Class<?> supportJavaTypeKey() {
        return String.class;
    }

    @Override
    public CellDataTypeEnum supportExcelTypeKey() {
        return null;
    }

    @Override
    public WriteCellData<?> convertToExcelData(String value, ExcelContentProperty contentProperty, GlobalConfiguration globalConfiguration) {
        if (value == null) {
            return new WriteCellData<>(CellDataTypeEnum.EMPTY);
        }

        // 仅对显式标注 @ExcelImage 的字段进行图片流解析；未标注的常规字符串直接输出文本
        ExcelImage imgAnn = null;
        if (contentProperty != null && contentProperty.getField() != null) {
            imgAnn = contentProperty.getField().getAnnotation(ExcelImage.class);
            if (imgAnn == null && !isExplicitlyConfigured(contentProperty)) {
                return new WriteCellData<>(value);
            }
        }

        try {
            ExcelSecurityProperties security = resolveSecurityProperties();
            int connectTimeout = resolveEffectiveTimeout(
                    imgAnn != null ? imgAnn.connectTimeout() : (int) DEFAULT_CONNECT_TIMEOUT.toMillis(),
                    security.getImageConnectTimeout(),
                    DEFAULT_CONNECT_TIMEOUT
            );
            int readTimeout = resolveEffectiveTimeout(
                    imgAnn != null ? imgAnn.readTimeout() : (int) DEFAULT_READ_TIMEOUT.toMillis(),
                    security.getImageReadTimeout(),
                    DEFAULT_READ_TIMEOUT
            );
            int maxImageBytes = resolveMaxImageBytes(security.getMaxImageSize());

            byte[] imageBytes = resolveImageBytes(value, connectTimeout, readTimeout, maxImageBytes, security);
            if (imageBytes != null && imageBytes.length > 0) {
                return new WriteCellData<>(imageBytes);
            }
        } catch (Exception e) {
            log.warn("SmartImageConverter 解析图片数据失败，降级为字面量输出: {}", e.getMessage());
        }

        return new WriteCellData<>(value);
    }

    private boolean isExplicitlyConfigured(ExcelContentProperty contentProperty) {
        return contentProperty != null && contentProperty.getConverter() instanceof SmartImageConverter;
    }

    private static ExcelSecurityProperties resolveSecurityProperties() {
        ExcelSecurityProperties security = ExcelUtil.getSecurityProperties();
        return (security != null) ? security : new ExcelSecurityProperties();
    }

    private static int resolveEffectiveTimeout(int annotationTimeout, Duration configuredTimeout, Duration baselineDefault) {
        int baselineMs = (int) Math.min(Integer.MAX_VALUE, baselineDefault.toMillis());
        int configuredMs = (configuredTimeout != null && !configuredTimeout.isNegative() && !configuredTimeout.isZero())
                ? (int) Math.min(Integer.MAX_VALUE, configuredTimeout.toMillis())
                : baselineMs;
        if (annotationTimeout > 0 && annotationTimeout != baselineMs) {
            return (configuredMs != baselineMs) ? Math.min(annotationTimeout, configuredMs) : annotationTimeout;
        }
        return configuredMs;
    }

    private static int resolveMaxImageBytes(DataSize maxImageSize) {
        if (maxImageSize == null) {
            return DEFAULT_MAX_IMAGE_BYTES;
        }
        long bytes = maxImageSize.toBytes();
        if (bytes <= 0) {
            throw new IllegalArgumentException("maxImageSize 必须大于 0");
        }
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    private byte[] resolveImageBytes(String str,
                                   int connectTimeout,
                                   int readTimeout,
                                   int maxBytes,
                                   ExcelSecurityProperties security) throws Exception {
        String trimmed = str.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.contains("\0")) {
            throw new IllegalArgumentException("图片数据源包含非法空字符");
        }

        // 1. 带前缀的 Base64 图片数据 (data:image/png;base64,xxxx)
        if (trimmed.startsWith("data:image/") && trimmed.contains("base64,")) {
            String base64Part = trimmed.substring(trimmed.indexOf("base64,") + 7).trim();
            return decodeBoundedBase64(base64Part, maxBytes);
        }

        // 2. 带有 URI Scheme 的地址（严格放行 HTTP/HTTPS，拦截 file://、ftp://、gopher:// 等非 HTTP(S) 协议）
        if (URI_SCHEME_PATTERN.matcher(trimmed).matches()) {
            URI uri = URI.create(trimmed);
            validateRemoteUriAndHost(uri, security.isBlockInternalImageUrls());
            return downloadHttpImageSafely(uri, connectTimeout, readTimeout, maxBytes, security.isBlockInternalImageUrls());
        }

        // 3. 受控本地文件路径加载
        Path localPath = resolveSafeLocalImagePath(trimmed, security);
        if (localPath != null && Files.exists(localPath) && Files.isRegularFile(localPath)) {
            long fileSize = Files.size(localPath);
            if (fileSize > maxBytes) {
                throw new IllegalArgumentException("本地图片文件大小超出安全限制 (" + maxBytes + " bytes): " + fileSize);
            }
            try (InputStream in = Files.newInputStream(localPath)) {
                return FileUtil.readNBytesSafe(in, maxBytes);
            }
        }

        // 4. 尝试作为普通纯 Base64 解码 (若长度足够且无空格回车，且非路径字符)
        try {
            if (trimmed.length() > 64 && !trimmed.contains(" ") && !trimmed.contains("\n") && !trimmed.contains("\\")) {
                return decodeBoundedBase64(trimmed, maxBytes);
            }
        } catch (IllegalArgumentException ignored) {
        }

        return null;
    }

    private static byte[] decodeBoundedBase64(String base64, int maxBytes) {
        long estimatedMaxDecoded = ((long) base64.length() * 3L) / 4L;
        if (estimatedMaxDecoded > (long) maxBytes + 4L) {
            throw new IllegalArgumentException("Base64 图片体积超出允许上限: " + maxBytes + " bytes");
        }
        byte[] decoded = Base64.getDecoder().decode(base64);
        if (decoded.length > maxBytes) {
            throw new IllegalArgumentException("Base64 图片解码后超出允许上限 (" + maxBytes + " bytes): " + decoded.length);
        }
        return decoded;
    }

    private static Path resolveSafeLocalImagePath(String rawPath, ExcelSecurityProperties security) throws IOException {
        if (!security.isAllowLocalImageFile()) {
            throw new IllegalArgumentException("安全策略已禁止加载本地文件系统图片");
        }
        if (ExcelSecurityUtil.hasTraversalSegment(rawPath)) {
            throw new IllegalArgumentException("检测到本地图片路径穿越风险 (..): " + rawPath);
        }

        String cleaned = FileUtil.cleanPath(rawPath);
        if (cleaned.isEmpty()) {
            return null;
        }

        String allowedBaseDir = security.getAllowedImageBaseDir();
        Path resolved;
        if (allowedBaseDir != null && !allowedBaseDir.isBlank()) {
            String cleanedBase = FileUtil.cleanPath(allowedBaseDir);
            Path baseDirPath = Paths.get(cleanedBase).toAbsolutePath().normalize();
            try {
                resolved = ExcelSecurityUtil.resolveContainedPath(baseDirPath, rawPath);
            } catch (io.github.atengk.boot.excel.exception.ExcelValidationException ex) {
                throw new IllegalArgumentException(ex.getMessage(), ex);
            }
            if (Files.exists(resolved) && Files.exists(baseDirPath)) {
                Path realResolved = resolved.toRealPath();
                Path realBase = baseDirPath.toRealPath();
                if (!realResolved.startsWith(realBase)) {
                    throw new IllegalArgumentException("本地图片真实路径超出允许的基目录范围: " + rawPath);
                }
            }
        } else {
            resolved = Paths.get(cleaned).toAbsolutePath().normalize();
        }

        if (isSensitiveSystemPath(cleaned, resolved)) {
            throw new IllegalArgumentException("禁止访问系统敏感目录图片路径: " + rawPath);
        }
        return resolved;
    }

    private static boolean isSensitiveSystemPath(String cleanedPath, Path resolvedPath) {
        String cleanedLower = cleanedPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        String absLower = resolvedPath.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);

        for (String prefix : POSIX_SENSITIVE_PREFIXES) {
            if (cleanedLower.equals(prefix) || cleanedLower.startsWith(prefix + "/")
                    || absLower.equals(prefix) || absLower.startsWith(prefix + "/")) {
                return true;
            }
        }

        if (WINDOWS_SENSITIVE_SYSTEM_PATTERN.matcher(cleanedLower).find()
                || WINDOWS_SENSITIVE_SYSTEM_PATTERN.matcher(absLower).find()) {
            String tmpDir = System.getProperty("java.io.tmpdir");
            if (tmpDir != null && !tmpDir.isBlank()) {
                String normalizedTmp = Paths.get(tmpDir).toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!WINDOWS_SENSITIVE_SYSTEM_PATTERN.matcher(normalizedTmp).find() || !absLower.startsWith(normalizedTmp)) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private static void validateRemoteUriAndHost(URI uri, boolean blockInternal) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅允许 HTTP/HTTPS 协议图片 URL，已拦截非法协议: " + scheme);
        }

        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new IllegalArgumentException("无效的图片 URL 主机名: " + uri);
        }

        if (!blockInternal) {
            return;
        }

        String cleanHost = unwrapIpv6Host(rawHost).toLowerCase(Locale.ROOT);
        while (cleanHost.endsWith(".")) {
            cleanHost = cleanHost.substring(0, cleanHost.length() - 1);
        }

        if ("localhost".equals(cleanHost)
                || "ip6-localhost".equals(cleanHost)
                || "ip6-loopback".equals(cleanHost)
                || "0.0.0.0".equals(cleanHost)
                || "::".equals(cleanHost)
                || "::1".equals(cleanHost)
                || "metadata.google.internal".equals(cleanHost)
                || "metadata".equals(cleanHost)
                || cleanHost.endsWith(".localhost")
                || cleanHost.endsWith(".local")
                || cleanHost.endsWith(".internal")
                || cleanHost.endsWith(".arpa")
                || cleanHost.startsWith("127.")
                || cleanHost.startsWith("169.254.")) {
            throw new SecurityException("SSRF 防护已拦截环回/本地/云元数据域名或地址: " + rawHost);
        }

        InetAddress[] addresses = InetAddress.getAllByName(cleanHost);
        if (addresses.length == 0) {
            throw new SecurityException("无法解析图片主机地址: " + rawHost);
        }
        for (InetAddress addr : addresses) {
            if (isBlockedInternalAddress(addr)) {
                throw new SecurityException("SSRF 防护已拦截内网、环回或链路本地 IP 地址: " + addr.getHostAddress());
            }
        }
    }

    private static String unwrapIpv6Host(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean isBlockedInternalAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0 || b0 == 10) {
                return true;
            }
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) {
                return true;
            }
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) {
                return true;
            }
            if (b0 == 192 && b1 == 168) {
                return true;
            }
        } else if (bytes.length == 16) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // Unique Local Address (fc00::/7) 与链路本地 (fe80::/10)
            if ((b0 & 0xFE) == 0xFC) {
                return true;
            }
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
                return true;
            }
        }
        return false;
    }

    private byte[] downloadHttpImageSafely(URI initialUri,
                                           int connectTimeout,
                                           int readTimeout,
                                           int maxBytes,
                                           boolean blockInternal) throws Exception {
        URI currentUri = initialUri;
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            URL url = currentUri.toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);

            try {
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307
                        || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IllegalStateException("HTTP 重定向缺少 Location 头");
                    }
                    URI nextUri = currentUri.resolve(location.trim());
                    validateRemoteUriAndHost(nextUri, blockInternal);
                    currentUri = nextUri;
                    continue;
                }

                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP 图片下载失败，响应码: " + code);
                }

                long contentLength = conn.getContentLengthLong();
                if (contentLength > maxBytes) {
                    throw new IllegalArgumentException("远程图片 Content-Length 超出最大允许限制 (" + maxBytes + " bytes): " + contentLength);
                }

                try (InputStream in = conn.getInputStream()) {
                    return FileUtil.readNBytesSafe(in, maxBytes);
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new IllegalStateException("图片 URL 重定向次数超过上限 (" + MAX_REDIRECT_HOPS + ")");
    }
}
