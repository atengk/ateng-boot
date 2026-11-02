package io.github.atengk.boot.social.http;

import io.github.atengk.boot.social.context.CurrentSocialAppContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.http.AdapterHttpResponse;
import org.dromara.anduin.http.HttpClientAdapter;
import org.dromara.anduin.http.HttpConstants;
import org.dromara.anduin.http.HttpHeader;
import org.dromara.anduin.utils.MapUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Spring 6 RestClient 原生实现的 OAuth2 网络适配器
 * 支持全链路 MDC X-Trace-Id 出站标头透传、强类型超时治理与应用级细粒度网络代理
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public class SpringRestClientHttpImpl implements HttpClientAdapter {

    private final RestClient defaultRestClient;
    private final Map<String, RestClient> appClientMap = new ConcurrentHashMap<>();

    /**
     * 默认构造函数（连接超时 10s，读取超时 15s，无代理）
     */
    public SpringRestClientHttpImpl() {
        this(Duration.ofSeconds(10), Duration.ofSeconds(15), null);
    }

    /**
     * 带超时与代理配置的构造函数
     *
     * @param connectTimeout 连接超时时间
     * @param readTimeout    读取超时时间
     * @param proxy          可选网络代理
     */
    public SpringRestClientHttpImpl(Duration connectTimeout, Duration readTimeout, Proxy proxy) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        if (connectTimeout != null) {
            requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
        }
        if (readTimeout != null) {
            requestFactory.setReadTimeout((int) readTimeout.toMillis());
        }
        if (proxy != null) {
            requestFactory.setProxy(proxy);
        }
        this.defaultRestClient = RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(new SocialTraceInterceptor())
                .build();
    }

    /**
     * 基于外部传入 RestClient 实例构建
     *
     * @param restClient 外部受管 RestClient 实例
     */
    public SpringRestClientHttpImpl(RestClient restClient) {
        this.defaultRestClient = restClient != null ? restClient : RestClient.create();
    }

    /**
     * 为特定应用标识注册独立配置的 RestClient（如针对海外平台挂载专用代理或定制超时）
     *
     * @param appKey         应用唯一标识
     * @param connectTimeout 连接超时
     * @param readTimeout    读取超时
     * @param proxy          专属代理
     */
    public void registerAppRestClient(String appKey, Duration connectTimeout, Duration readTimeout, Proxy proxy) {
        if (appKey == null || appKey.isBlank()) {
            return;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        if (connectTimeout != null) {
            requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
        }
        if (readTimeout != null) {
            requestFactory.setReadTimeout((int) readTimeout.toMillis());
        }
        if (proxy != null) {
            requestFactory.setProxy(proxy);
        }
        RestClient client = RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(new SocialTraceInterceptor())
                .build();
        appClientMap.put(appKey, client);
    }

    /**
     * 获取当前上下文适用的 RestClient
     */
    public RestClient obtainClient() {
        String currentAppKey = CurrentSocialAppContextHolder.getAppKey();
        if (currentAppKey != null && !currentAppKey.isBlank()) {
            RestClient appClient = appClientMap.get(currentAppKey);
            if (appClient != null) {
                return appClient;
            }
        }
        return defaultRestClient;
    }

    @Override
    public AdapterHttpResponse get(String url) {
        return get(url, null, false);
    }

    @Override
    public AdapterHttpResponse get(String url, Map<String, String> params, boolean encode) {
        return get(url, params, null, encode);
    }

    @Override
    public AdapterHttpResponse get(String url, Map<String, String> params, HttpHeader header, boolean encode) {
        String reqUrl = buildUrlWithParams(url, params, encode);
        return execute(HttpMethod.GET, reqUrl, null, header);
    }

    @Override
    public AdapterHttpResponse post(String url) {
        return post(url, (String) null);
    }

    @Override
    public AdapterHttpResponse post(String url, String data) {
        return post(url, data, null);
    }

    @Override
    public AdapterHttpResponse post(String url, String data, HttpHeader header) {
        return execute(HttpMethod.POST, url, data, header);
    }

    @Override
    public AdapterHttpResponse post(String url, Map<String, String> params, boolean encode) {
        return post(url, params, null, encode);
    }

    @Override
    public AdapterHttpResponse post(String url, Map<String, String> params, HttpHeader header, boolean encode) {
        String reqUrl = buildUrlWithParams(url, params, encode);
        return post(reqUrl, null, header);
    }

    @Override
    public AdapterHttpResponse postForm(String url, Map<String, String> params, HttpHeader header) {
        String body = "";
        if (params != null && !params.isEmpty()) {
            body = MapUtil.parseMapToString(params, true);
        }
        HttpHeader formHeader = new HttpHeader();
        if (header != null && header.getHeaders() != null) {
            header.getHeaders().forEach((k, v) -> {
                if (!HttpConstants.CONTENT_TYPE.equalsIgnoreCase(k)) {
                    formHeader.add(k, v);
                }
            });
        }
        formHeader.add(HttpConstants.CONTENT_TYPE, "application/x-www-form-urlencoded");
        return post(url, body, formHeader);
    }

    private String buildUrlWithParams(String url, Map<String, String> params, boolean encode) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + MapUtil.parseMapToString(params, encode);
    }

    private AdapterHttpResponse execute(HttpMethod method, String url, String body, HttpHeader customHeader) {
        try {
            URI uri = URI.create(url);
            RestClient.RequestBodySpec requestSpec = obtainClient().method(method).uri(uri);

            // 统一挂载 User-Agent 标头
            requestSpec.header(HttpConstants.USER_AGENT, HttpConstants.USER_AGENT_DATA);

            // 应用自定义请求标头
            boolean hasContentType = false;
            if (customHeader != null && customHeader.getHeaders() != null) {
                for (Map.Entry<String, String> entry : customHeader.getHeaders().entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        requestSpec.header(entry.getKey(), entry.getValue());
                        if (HttpConstants.CONTENT_TYPE.equalsIgnoreCase(entry.getKey())) {
                            hasContentType = true;
                        }
                    }
                }
            }

            // 若为 POST 且存在数据且未指定 Content-Type，默认设为 application/json
            if (HttpMethod.POST.equals(method) && body != null && !body.isEmpty() && !hasContentType) {
                requestSpec.header(HttpConstants.CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON);
            }

            if (body != null) {
                requestSpec.body(body);
            }

            return requestSpec.exchange((clientRequest, clientResponse) -> {
                int statusCode = clientResponse.getStatusCode().value();
                boolean success = clientResponse.getStatusCode().is2xxSuccessful();
                HttpHeaders responseHeaders = clientResponse.getHeaders();
                Map<String, List<String>> headerMap = new HashMap<>(responseHeaders);
                byte[] responseBytes = clientResponse.getBody().readAllBytes();
                String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
                return new AdapterHttpResponse(success, statusCode, headerMap, responseBody, null);
            });
        } catch (Exception ex) {
            log.error("Social HTTP request execution error: {} {}", method, url, ex);
            return new AdapterHttpResponse(false, 500, Collections.emptyMap(), null, ex.getMessage());
        }
    }
}
