package io.github.atengk.boot.pay.util;

import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.spi.PayCertificateProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 支付密钥与证书四模自适应解析器
 * <p>
 * 原生支持从以下四种模式自适应定位并解析 RSA 私钥与 X.509 平台/应用证书：
 * <ol>
 *     <li><b>文件系统路径 (file:)</b>：支持绝对路径与相对路径（如 {@code file:/data/cert/apiclient_key.pem}）</li>
 *     <li><b>类路径资源 (classpath:)</b>：支持 ClassPath 资源包（如 {@code classpath:cert/apiclient_key.pem}）</li>
 *     <li><b>行内 PEM 纯文本</b>：直接在 YAML 或环境变量中注入的 PEM 文本（包含或不包含 BEGIN/END 标头）</li>
 *     <li><b>外部 SPI 扩展 (PayCertificateProvider)</b>：支持企业 KMS、Vault 或数据库密钥动态提供者</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class PayKeyLoader {

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";
    private static final List<PayCertificateProvider> REGISTERED_PROVIDERS = new CopyOnWriteArrayList<>();

    private PayKeyLoader() {
    }

    /**
     * 注册全局证书提供者 SPI
     *
     * @param provider 证书提供者
     */
    public static void registerProvider(PayCertificateProvider provider) {
        if (provider != null) {
            REGISTERED_PROVIDERS.add(provider);
        }
    }

    /**
     * 清理所有已注册的全局证书提供者
     */
    public static void clearProviders() {
        REGISTERED_PROVIDERS.clear();
    }

    /**
     * 自适应加载私钥或证书的原始字节数组
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @param customProvider    特定自定义 SPI 提供者（可为 null）
     * @return 字节数组
     */
    public static byte[] loadBytes(String locationOrContent, PayCertificateProvider customProvider) {
        if (locationOrContent == null || locationOrContent.isBlank()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID, "证书或私钥定位符/内容不能为空");
        }
        String trimmed = locationOrContent.trim();

        // 1. 优先尝试特定自定义 SPI 提供者
        if (customProvider != null && customProvider.supports(trimmed)) {
            byte[] bytes = customProvider.loadBytes(trimmed);
            if (bytes != null && bytes.length > 0) {
                return bytes;
            }
        }

        // 2. 尝试已注册的全局 SPI 提供者列表
        for (PayCertificateProvider provider : REGISTERED_PROVIDERS) {
            if (provider.supports(trimmed)) {
                byte[] bytes = provider.loadBytes(trimmed);
                if (bytes != null && bytes.length > 0) {
                    return bytes;
                }
            }
        }

        // 3. ClassPath 类路径资源
        if (trimmed.startsWith(CLASSPATH_PREFIX)) {
            String resourcePath = trimmed.substring(CLASSPATH_PREFIX.length()).trim();
            if (resourcePath.startsWith("/")) {
                resourcePath = resourcePath.substring(1);
            }
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = PayKeyLoader.class.getClassLoader();
            }
            try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new PayException(PayResultCode.PAY_PARAM_INVALID, "未在类路径中找到指定证书资源: " + locationOrContent);
                }
                return is.readAllBytes();
            } catch (IOException e) {
                throw new PayException(PayResultCode.PAY_PARAM_INVALID, "读取类路径证书资源异常: " + locationOrContent, e);
            }
        }

        // 4. File 文件系统路径
        if (trimmed.startsWith(FILE_PREFIX)) {
            String filePath = trimmed.substring(FILE_PREFIX.length()).trim();
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                throw new PayException(PayResultCode.PAY_PARAM_INVALID, "文件系统中未找到指定证书文件: " + locationOrContent);
            }
            try {
                return Files.readAllBytes(path);
            } catch (IOException e) {
                throw new PayException(PayResultCode.PAY_PARAM_INVALID, "读取文件系统证书失败: " + locationOrContent, e);
            }
        }

        // 5. 显式 PEM 文本块 (含有 BEGIN 标头或包含换行)
        if (trimmed.contains("-----BEGIN") || trimmed.contains("\n") || trimmed.contains("\r")) {
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }

        // 6. 探测是否为未带 file: 前缀的本地真实文件路径
        try {
            Path path = Path.of(trimmed);
            if (Files.isRegularFile(path)) {
                return Files.readAllBytes(path);
            }
        } catch (Exception ignored) {
            // 路径语法不合法时静默转入纯文本处理
        }

        // 7. 纯字符串/Base64 内容直接转字节数组
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 自适应加载私钥或证书的原始字符串
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @return 文本字符串
     */
    public static String loadString(String locationOrContent) {
        return loadString(locationOrContent, null);
    }

    /**
     * 自适应加载私钥或证书的原始字符串
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @param customProvider    特定自定义 SPI 提供者（可为 null）
     * @return 文本字符串
     */
    public static String loadString(String locationOrContent, PayCertificateProvider customProvider) {
        byte[] bytes = loadBytes(locationOrContent, customProvider);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 自适应加载 RSA 私钥
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @return Java RSA PrivateKey 实例
     */
    public static PrivateKey loadPrivateKey(String locationOrContent) {
        return loadPrivateKey(locationOrContent, null);
    }

    /**
     * 自适应加载 RSA 私钥（支持指定自定义 SPI 提供者）
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @param customProvider    特定自定义 SPI 提供者（可为 null）
     * @return Java RSA PrivateKey 实例
     */
    public static PrivateKey loadPrivateKey(String locationOrContent, PayCertificateProvider customProvider) {
        String keyString = loadString(locationOrContent, customProvider);
        String cleanBase64 = cleanPrivateKeyPem(keyString);
        try {
            byte[] keyBytes = Base64.getDecoder().decode(cleanBase64);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(keySpec);
        } catch (NoSuchAlgorithmException e) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "当前 JVM 环境不支持 RSA 算法", e);
        } catch (IllegalArgumentException | InvalidKeySpecException e) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "RSA 私钥格式非法或损坏: " + locationOrContent, e);
        }
    }

    /**
     * 自适应加载清洗后的 RSA 私钥文本（无任何 BEGIN/END 标头与换行空白，适用于支付宝 SDK）
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @return 清洗后的标准 Base64 私钥字符串
     */
    public static String loadPrivateKeyPem(String locationOrContent) {
        return loadPrivateKeyPem(locationOrContent, null);
    }

    /**
     * 自适应加载清洗后的 RSA 私钥文本
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @param customProvider    特定自定义 SPI 提供者（可为 null）
     * @return 清洗后的标准 Base64 私钥字符串
     */
    public static String loadPrivateKeyPem(String locationOrContent, PayCertificateProvider customProvider) {
        String keyString = loadString(locationOrContent, customProvider);
        return cleanPrivateKeyPem(keyString);
    }

    /**
     * 自适应加载清洗后的 RSA 公钥或证书文本（无任何 BEGIN/END 标头与换行空白，适用于支付宝 SDK）
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @return 清洗后的标准 Base64 公钥或证书字符串
     */
    public static String loadPublicKeyPem(String locationOrContent) {
        return loadPublicKeyPem(locationOrContent, null);
    }

    /**
     * 自适应加载清洗后的 RSA 公钥或证书文本
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @param customProvider    特定自定义 SPI 提供者（可为 null）
     * @return 清洗后的标准 Base64 公钥或证书字符串
     */
    public static String loadPublicKeyPem(String locationOrContent, PayCertificateProvider customProvider) {
        String keyString = loadString(locationOrContent, customProvider);
        return cleanCertificatePem(keyString);
    }

    /**
     * 自适应加载 X.509 证书
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @return Java X509Certificate 实例
     */
    public static X509Certificate loadCertificate(String locationOrContent) {
        return loadCertificate(locationOrContent, null);
    }

    /**
     * 自适应加载 X.509 证书（支持指定自定义 SPI 提供者）
     *
     * @param locationOrContent 资源路径、PEM 字符串或自定义标识符
     * @param customProvider    特定自定义 SPI 提供者（可为 null）
     * @return Java X509Certificate 实例
     */
    public static X509Certificate loadCertificate(String locationOrContent, PayCertificateProvider customProvider) {
        byte[] certBytes = loadBytes(locationOrContent, customProvider);
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(certBytes)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(inputStream);
        } catch (CertificateException e) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "X.509 证书内容解析失败: " + locationOrContent, e);
        } catch (IOException e) {
            throw new PaySignException(PayResultCode.PAY_PARAM_INVALID, "读取证书字节流失败: " + locationOrContent, e);
        }
    }

    /**
     * 获取 X.509 证书序列号（微信支付标准的 16 进制大写格式）
     *
     * @param certificate X.509 证书实例
     * @return 16 进制大写证书序列号
     */
    public static String getCertificateSerialNumber(X509Certificate certificate) {
        Objects.requireNonNull(certificate, "证书实例不能为空");
        return certificate.getSerialNumber().toString(16).toUpperCase();
    }

    /**
     * 清洗私钥文本，去除常见的 PEM 标头、标尾与空白字符
     *
     * @param rawPem 原始 PEM 文本
     * @return 纯 Base64 内容
     */
    public static String cleanPrivateKeyPem(String rawPem) {
        if (rawPem == null) {
            return "";
        }
        return rawPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
    }

    /**
     * 清洗公钥或证书文本，去除常见的 PEM 标头、标尾与空白字符
     *
     * @param rawPem 原始证书/公钥 PEM 文本
     * @return 纯 Base64 内容
     */
    public static String cleanCertificatePem(String rawPem) {
        if (rawPem == null) {
            return "";
        }
        return rawPem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
    }
}
