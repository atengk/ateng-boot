package io.github.atengk.boot.http.factory;

import io.github.atengk.boot.http.context.HttpRequestContextHolder;
import io.github.atengk.boot.http.options.RequestOptionsApplier;
import io.github.atengk.boot.http.properties.HttpPoolProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpProxyProperties;
import io.github.atengk.boot.http.properties.HttpSslProperties;
import io.github.atengk.boot.http.util.HttpSslUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

/**
 * JDK 17 原生 HttpClient 物理连接工厂
 * 基于 java.net.http.HttpClient 提供零外部网络库依赖、虚拟线程友好的优雅保底实现
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public final class JdkHttpClientFactory {

    private JdkHttpClientFactory() {
    }

    /**
     * 根据配置构建 JdkClientHttpRequestFactory
     *
     * @param properties 配置属性
     * @return ClientHttpRequestFactory 物理请求工厂
     */
    public static ClientHttpRequestFactory createFactory(HttpProperties properties) {
        HttpPoolProperties pool = properties.getPool();
        HttpSslProperties ssl = properties.getSsl();
        HttpProxyProperties proxy = properties.getProxy();

        HttpClient.Builder builder = HttpClient.newBuilder();

        // 1. 建联超时配置
        if (pool.getConnectTimeout() != null) {
            builder.connectTimeout(pool.getConnectTimeout());
        }

        // 2. SSL Insecure 信任全部证书与客户端 mTLS 双向认证
        boolean hasKeyStore = ssl.hasKeyStore();
        if (ssl.isInsecure() || hasKeyStore) {
            try {
                TrustManager[] trustManagers = null;
                if (ssl.isInsecure()) {
                    trustManagers = new TrustManager[]{
                            new X509TrustManager() {
                                @Override
                                public X509Certificate[] getAcceptedIssuers() {
                                    return new X509Certificate[0];
                                }

                                @Override
                                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                                }

                                @Override
                                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                                }
                            }
                    };
                }
                KeyManager[] keyManagers = null;
                if (hasKeyStore) {
                    keyManagers = loadKeyManagers(ssl);
                }
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(keyManagers, trustManagers, new SecureRandom());
                builder.sslContext(sslContext);
                if (ssl.isInsecure()) {
                    SSLParameters sslParameters = new SSLParameters();
                    sslParameters.setEndpointIdentificationAlgorithm("");
                    builder.sslParameters(sslParameters);
                    log.info("JDK HttpClient 已启用 Insecure SSL 模式，信任所有证书并跳过主机名校验");
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("构建 JDK SSLContext 失败: " + e.getMessage(), e);
            }
        }

        // 3. 出口正向代理与白名单直连支持
        if (proxy.isEnabled()) {
            Assert.hasText(proxy.getHost(), "HTTP 代理已启用(ateng.boot.http.proxy.enabled=true)，代理主机 host 不能为空");
            InetSocketAddress proxyAddress = new InetSocketAddress(proxy.getHost(), proxy.getPort());
            Proxy proxyObj = new Proxy(Proxy.Type.HTTP, proxyAddress);
            if (proxy.getNonProxyHosts() != null && !proxy.getNonProxyHosts().isEmpty()) {
                builder.proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        if (uri != null && proxy.isNonProxyHost(uri.getHost())) {
                            return List.of(Proxy.NO_PROXY);
                        }
                        return List.of(proxyObj);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                        log.warn("JDK 代理服务器连接失败: uri={}, sa={}, error={}", uri, sa, ioe.getMessage());
                    }
                });
            } else {
                builder.proxy(ProxySelector.of(proxyAddress));
            }
            if (StringUtils.hasText(proxy.getUsername())) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                                proxy.getUsername(),
                                proxy.getPassword() != null ? proxy.getPassword().toCharArray() : new char[0]
                        );
                    }
                });
            }
            log.info("JDK HttpClient 已启用出口代理: {}:{}, nonProxyHosts={}", proxy.getHost(), proxy.getPort(), proxy.getNonProxyHosts());
        }

        HttpClient httpClient = builder.build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient) {
            @Override
            public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
                ClientHttpRequest request = super.createRequest(uri, httpMethod);
                Duration perRequestReadTimeout = HttpRequestContextHolder.getReadTimeout();
                Duration perRequestConnectTimeout = HttpRequestContextHolder.getConnectTimeout();
                if (perRequestReadTimeout != null || perRequestConnectTimeout != null) {
                    RequestOptionsApplier.applyTimeout(request, perRequestReadTimeout, perRequestConnectTimeout);
                }
                return request;
            }
        };
        if (pool.getReadTimeout() != null) {
            factory.setReadTimeout(pool.getReadTimeout());
        }

        log.info("JDK 17 原生 HttpClient 工厂初始化成功 [connectTimeout={}, readTimeout={}]",
                pool.getConnectTimeout(), pool.getReadTimeout());
        return factory;
    }

    /**
     * 加载 mTLS 客户端私钥证书 KeyManager 数组
     *
     * @param ssl SSL 配置属性
     * @return KeyManager 数组
     * @throws Exception 证书加载或解析异常
     */
    private static KeyManager[] loadKeyManagers(HttpSslProperties ssl) throws Exception {
        return HttpSslUtil.loadKeyManagers(ssl);
    }
}
