package io.github.atengk.boot.excel.converter;

import io.github.atengk.boot.excel.annotation.ExcelImage;
import org.apache.fesod.sheet.converters.Converter;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.GlobalConfiguration;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.Base64;

/**
 * 智能多媒体图片转换器
 * <p>
 * 专为业务实体中广泛使用的 {@code String} 类型图片字段打造，零配置自动识别并分发：
 * <ul>
 *     <li>网络 HTTP/HTTPS 图片 URL（自动下载图片字节）</li>
 *     <li>Base64 数据流（{@code data:image/...;base64,...}）</li>
 *     <li>本地绝对文件路径</li>
 * </ul>
 * 仅对标注了 {@link ExcelImage} 的字段生效；未标注该注解的常规文本字段毫秒级快速直通，杜绝误转换与性能损耗。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class SmartImageConverter implements Converter<String> {

    private static final Logger log = LoggerFactory.getLogger(SmartImageConverter.class);

    private static final int DEFAULT_CONNECT_TIMEOUT = 3000;
    private static final int DEFAULT_READ_TIMEOUT = 10000;

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
        if (contentProperty != null && contentProperty.getField() != null) {
            ExcelImage annotation = contentProperty.getField().getAnnotation(ExcelImage.class);
            if (annotation == null && !isExplicitlyConfigured(contentProperty)) {
                return new WriteCellData<>(value);
            }
        }

        try {
            int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
            int readTimeout = DEFAULT_READ_TIMEOUT;
            if (contentProperty != null && contentProperty.getField() != null) {
                ExcelImage imgAnn = contentProperty.getField().getAnnotation(ExcelImage.class);
                if (imgAnn != null) {
                    connectTimeout = imgAnn.connectTimeout();
                    readTimeout = imgAnn.readTimeout();
                }
            }

            byte[] imageBytes = resolveImageBytes(value, connectTimeout, readTimeout);
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

    private byte[] resolveImageBytes(String str, int connectTimeout, int readTimeout) throws Exception {
        String trimmed = str.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // 1. 网络 URL (HTTP / HTTPS)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            URL url = URI.create(trimmed).toURL();
            return downloadHttpImage(url, connectTimeout, readTimeout);
        }

        // 2. 带前缀的 Base64 图片数据 (data:image/png;base64,xxxx)
        if (trimmed.startsWith("data:image/") && trimmed.contains("base64,")) {
            String base64Part = trimmed.substring(trimmed.indexOf("base64,") + 7);
            return Base64.getDecoder().decode(base64Part.trim());
        }

        // 3. 本地绝对路径文件
        File localFile = new File(trimmed);
        if (localFile.exists() && localFile.isFile()) {
            return Files.readAllBytes(localFile.toPath());
        }

        // 4. 尝试作为普通纯 Base64 解码 (若长度足够且无空格回车)
        try {
            if (trimmed.length() > 64 && !trimmed.contains(" ") && !trimmed.contains("\n")) {
                return Base64.getDecoder().decode(trimmed);
            }
        } catch (IllegalArgumentException ignored) {
        }

        return null;
    }

    private byte[] downloadHttpImage(URL url, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ateng-boot-excel/0.0.5");

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP 图片下载失败，响应码: " + code);
        }

        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
}
