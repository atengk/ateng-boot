package io.github.atengk.boot.http.util;

import io.github.atengk.boot.http.properties.HttpSslProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * 出站 HTTP SSL 与金融级 mTLS 双向认证证书加载工具类
 * 统一收敛解析 Spring Resource 格式的 KeyStore 证书文件并初始化密钥管理器
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public final class HttpSslUtil {

    private static final ResourceLoader DEFAULT_RESOURCE_LOADER = new DefaultResourceLoader();

    private HttpSslUtil() {
    }

    /**
     * 依据 SSL 配置属性解析并加载 KeyStore 密钥库实例
     *
     * @param ssl SSL 配置属性
     * @return 加载就绪的 KeyStore 实例
     * @throws Exception 证书文件未找到、密码错误或解析异常
     */
    public static KeyStore loadKeyStore(HttpSslProperties ssl) throws Exception {
        Assert.notNull(ssl, "ssl properties must not be null");
        String keyStorePath = ssl.getKeyStore();
        Assert.hasText(keyStorePath, "keyStore 路径不能为空");

        Resource resource = DEFAULT_RESOURCE_LOADER.getResource(keyStorePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("未找到 mTLS 客户端证书文件: " + keyStorePath);
        }

        String type = StringUtils.hasText(ssl.getKeyStoreType()) ? ssl.getKeyStoreType() : "PKCS12";
        KeyStore keyStore = KeyStore.getInstance(type);
        char[] password = StringUtils.hasText(ssl.getKeyStorePassword()) ? ssl.getKeyStorePassword().toCharArray() : new char[0];

        try (InputStream in = resource.getInputStream()) {
            keyStore.load(in, password);
        }
        log.info("成功加载 mTLS 客户端私钥证书: [{}] (type: {})", keyStorePath, type);
        return keyStore;
    }

    /**
     * 依据 SSL 配置属性构建 JDK 原生 KeyManager 数组
     *
     * @param ssl SSL 配置属性
     * @return 初始化完成的 KeyManager 数组
     * @throws Exception 密钥管理器初始化异常
     */
    public static KeyManager[] loadKeyManagers(HttpSslProperties ssl) throws Exception {
        KeyStore keyStore = loadKeyStore(ssl);
        char[] password = StringUtils.hasText(ssl.getKeyStorePassword()) ? ssl.getKeyStorePassword().toCharArray() : new char[0];
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        return kmf.getKeyManagers();
    }
}
