package io.github.atengk.boot.mqtt.ssl;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.properties.MqttSslProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

/**
 * MQTT TLS/SSL 与双向 mTLS 证书构建工厂
 * 基于 100% JDK 17 原生安全体系构建，支持 PEM 证书链与标准 KeyStore/TrustStore 存储介质
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class MqttSslSocketFactoryBuilder {

    private static final ResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    private MqttSslSocketFactoryBuilder() {}

    /**
     * 根据配置属性构建 {@link SSLSocketFactory}
     *
     * @param props SSL 配置属性
     * @return 构建完成的 SSLSocketFactory，若未启用则返回 null
     * @throws BusinessException 证书解析或初始化失败
     */
    public static SSLSocketFactory build(MqttSslProperties props) {
        SSLContext sslContext = buildSslContext(props);
        return sslContext != null ? sslContext.getSocketFactory() : null;
    }

    /**
     * 根据配置属性构建 {@link SSLContext}
     *
     * @param props SSL 配置属性
     * @return 构建完成的 SSLContext，若未启用则返回 null
     * @throws BusinessException 证书解析或初始化失败
     */
    public static SSLContext buildSslContext(MqttSslProperties props) {
        if (props == null || !props.isEnabled()) {
            return null;
        }

        try {
            String protocol = StringUtils.hasText(props.getProtocol()) ? props.getProtocol() : "TLSv1.3";
            SSLContext sslContext = SSLContext.getInstance(protocol);

            KeyManager[] keyManagers = buildKeyManagers(props);
            TrustManager[] trustManagers = buildTrustManagers(props);

            sslContext.init(keyManagers, trustManagers, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new BusinessException("Failed to initialize MQTT SSL socket factory: " + e.getMessage(), e);
        }
    }

    private static TrustManager[] buildTrustManagers(MqttSslProperties props) throws Exception {
        if (StringUtils.hasText(props.getTrustStore())) {
            KeyStore ts = KeyStore.getInstance(guessKeyStoreType(props.getTrustStore()));
            try (InputStream is = openStream(props.getTrustStore())) {
                char[] password = props.getTrustStorePassword() != null ? props.getTrustStorePassword().toCharArray() : null;
                ts.load(is, password);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            return tmf.getTrustManagers();
        }

        if (StringUtils.hasText(props.getCaCert())) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
            ts.load(null, null);
            try (InputStream is = openStream(props.getCaCert())) {
                Collection<? extends Certificate> certs = cf.generateCertificates(is);
                int idx = 1;
                for (Certificate cert : certs) {
                    ts.setCertificateEntry("ca-" + (idx++), cert);
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            return tmf.getTrustManagers();
        }

        return null;
    }

    private static KeyManager[] buildKeyManagers(MqttSslProperties props) throws Exception {
        if (StringUtils.hasText(props.getKeyStore())) {
            KeyStore ks = KeyStore.getInstance(guessKeyStoreType(props.getKeyStore()));
            char[] password = props.getKeyStorePassword() != null ? props.getKeyStorePassword().toCharArray() : null;
            try (InputStream is = openStream(props.getKeyStore())) {
                ks.load(is, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            return kmf.getKeyManagers();
        }

        if (StringUtils.hasText(props.getClientCert()) && StringUtils.hasText(props.getClientKey())) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate[] certChain;
            try (InputStream is = openStream(props.getClientCert())) {
                Collection<? extends Certificate> certs = cf.generateCertificates(is);
                certChain = certs.toArray(new Certificate[0]);
            }

            PrivateKey privateKey;
            try (InputStream is = openStream(props.getClientKey())) {
                privateKey = parsePrivateKey(is);
            }

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            char[] pwd = "ateng-mqtt-internal-password".toCharArray();
            ks.load(null, null);
            ks.setKeyEntry("client", privateKey, pwd, certChain);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pwd);
            return kmf.getKeyManagers();
        }

        return null;
    }

    private static PrivateKey parsePrivateKey(InputStream is) throws Exception {
        String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        String base64 = text
                .replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
                .replaceAll("-----END [A-Z0-9 ]+-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(base64);

        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(keySpec);
        } catch (InvalidKeySpecException e) {
            try {
                KeyFactory kf = KeyFactory.getInstance("EC");
                return kf.generatePrivate(keySpec);
            } catch (InvalidKeySpecException e2) {
                throw new IllegalArgumentException("Unsupported or invalid private key format, expected PKCS#8 RSA or EC key", e);
            }
        }
    }

    private static InputStream openStream(String location) throws IOException {
        if (!StringUtils.hasText(location)) {
            return null;
        }
        if (location.contains("-----BEGIN")) {
            return new ByteArrayInputStream(location.getBytes(StandardCharsets.UTF_8));
        }

        Resource resource = RESOURCE_LOADER.getResource(location);
        if (resource.exists()) {
            return resource.getInputStream();
        }

        File file = new File(location);
        if (file.exists() && file.isFile()) {
            return new FileInputStream(file);
        }

        throw new FileNotFoundException("SSL certificate/key resource not found: " + location);
    }

    private static String guessKeyStoreType(String path) {
        if (path.endsWith(".p12") || path.endsWith(".pfx")) {
            return "PKCS12";
        }
        if (path.endsWith(".jks")) {
            return "JKS";
        }
        return KeyStore.getDefaultType();
    }
}
