package io.github.atengk.boot.document.spi;

import io.github.atengk.boot.document.exception.DocumentException;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.properties.DocumentConverterProperties;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.util.DocumentWebHelper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * 基于 Gotenberg HTTP API 的远程文档跨格式转换 SPI 适配器
 * <p>
 * 支持通过 LibreOffice（DOCX -> PDF）与 Chromium（HTML -> PDF）多部分端点完成无头文档转换，
 * 内置请求大小安全上限、连接与读取超时防御及因果异常链转译。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
public class GotenbergDocumentConverter implements DocumentConverter {

    private static final String LIBREOFFICE_CONVERT_PATH = "/forms/libreoffice/convert";
    private static final String CHROMIUM_HTML_CONVERT_PATH = "/forms/chromium/convert/html";

    private final DocumentProperties documentProperties;
    private final String baseUrl;
    private final RestClient restClient;

    /**
     * 使用默认 Spring 6 {@link RestClient} 构造 Gotenberg 转换适配器
     *
     * @param properties 文档模块全局配置属性
     */
    public GotenbergDocumentConverter(DocumentProperties properties) {
        this(properties, null);
    }

    /**
     * 使用自定义 {@link RestClient} 构造 Gotenberg 转换适配器（适用于 Mock 联调或自定义连接池）
     *
     * @param properties 文档模块全局配置属性
     * @param restClient 自定义 RestClient 实例（为 null 时根据超时配置自动构建）
     */
    public GotenbergDocumentConverter(DocumentProperties properties, RestClient restClient) {
        this.documentProperties = Objects.requireNonNull(properties, "properties 不能为空");
        DocumentConverterProperties converterProps = properties.getConverter() != null
                ? properties.getConverter()
                : new DocumentConverterProperties();
        DocumentConverterProperties.GotenbergProperties gotenbergProps = converterProps.getGotenberg() != null
                ? converterProps.getGotenberg()
                : new DocumentConverterProperties.GotenbergProperties();

        String rawBaseUrl = gotenbergProps.getBaseUrl();
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw new IllegalArgumentException("ateng.boot.document.converter.gotenberg.baseUrl 不能为空");
        }
        String normalized = rawBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("ateng.boot.document.converter.gotenberg.baseUrl 格式不合法: " + rawBaseUrl);
        }
        URI parsedUri;
        try {
            parsedUri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ateng.boot.document.converter.gotenberg.baseUrl 不是合法 URI: " + rawBaseUrl, e);
        }
        String scheme = parsedUri.getScheme() != null ? parsedUri.getScheme().toLowerCase(Locale.ROOT) : "";
        if ((!"http".equals(scheme) && !"https".equals(scheme))
                || parsedUri.getHost() == null || parsedUri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "ateng.boot.document.converter.gotenberg.baseUrl 必须包含合法 http/https 协议与主机地址: " + rawBaseUrl);
        }

        Duration connectTimeout = validatePositiveTimeout(gotenbergProps.getConnectTimeout(), "connectTimeout");
        Duration readTimeout = validatePositiveTimeout(gotenbergProps.getReadTimeout(), "readTimeout");

        this.baseUrl = normalized;
        this.restClient = restClient != null ? restClient : createDefaultRestClient(this.baseUrl, connectTimeout, readTimeout);
    }

    @Override
    public byte[] docxToPdf(InputStream docxInputStream) {
        Objects.requireNonNull(docxInputStream, "待转换的 DOCX 输入流不能为空");
        return executeMultipartConvert(docxInputStream, LIBREOFFICE_CONVERT_PATH, "document.docx");
    }

    @Override
    public byte[] htmlToPdf(InputStream htmlInputStream) {
        Objects.requireNonNull(htmlInputStream, "待转换的 HTML 输入流不能为空");
        return executeMultipartConvert(htmlInputStream, CHROMIUM_HTML_CONVERT_PATH, "index.html");
    }

    private byte[] executeMultipartConvert(InputStream sourceStream, String endpointPath, String partFilename) {
        long maxBytes = resolveMaxDocumentSizeBytes();
        byte[] payload;
        try {
            payload = DocumentWebHelper.readBytesBounded(sourceStream, maxBytes);
        } catch (DocumentException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentException(DocumentResultCode.CONVERSION_FAILED,
                    "读取待转换文档输入流失败 [" + partFilename + "]: " + e.getMessage(), e);
        }

        ByteArrayResource fileResource = new ByteArrayResource(payload) {
            @Override
            public String getFilename() {
                return partFilename;
            }
        };
        MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
        multipartBody.add("files", fileResource);

        try {
            byte[] pdfBytes = restClient.post()
                    .uri(baseUrl + endpointPath)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipartBody)
                    .retrieve()
                    .body(byte[].class);
            if (pdfBytes == null || pdfBytes.length == 0) {
                throw new DocumentException(DocumentResultCode.CONVERSION_FAILED,
                        "Gotenberg 转换服务返回空 PDF 内容: " + endpointPath);
            }
            return pdfBytes;
        } catch (DocumentException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentException(DocumentResultCode.CONVERSION_FAILED,
                    "调用 Gotenberg 转换端点失败 [" + endpointPath + "]: " + e.getMessage(), e);
        }
    }

    private long resolveMaxDocumentSizeBytes() {
        if (documentProperties.getMaxDocumentSize() != null
                && documentProperties.getMaxDocumentSize().toBytes() > 0) {
            return documentProperties.getMaxDocumentSize().toBytes();
        }
        return Long.MAX_VALUE;
    }

    private static Duration validatePositiveTimeout(Duration timeout, String fieldName) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(
                    "ateng.boot.document.converter.gotenberg." + fieldName + " 必须为大于 0 的 Duration: " + timeout);
        }
        return timeout;
    }

    private static RestClient createDefaultRestClient(
            String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        // 预设 baseUrl 供相对路由调用；实际发起请求仍统一走绝对 URI 以兼容外部注入的自定义 RestClient
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
