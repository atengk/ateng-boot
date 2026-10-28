package io.github.atengk.boot.pay.core.certificate;

import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信平台证书自动轮换与生命周期管理器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class WechatAutoCertificateManagerTest {

    private static final String CLASSPATH_CERT = "classpath:certs/test_certificate.pem";
    private WechatAutoCertificateManager manager;
    private X509Certificate testCert;
    private String serialNumber;

    @BeforeEach
    void setUp() {
        manager = new WechatAutoCertificateManager(Duration.ofMinutes(10));
        testCert = PayKeyLoader.loadCertificate(CLASSPATH_CERT);
        serialNumber = PayKeyLoader.getCertificateSerialNumber(testCert);
    }

    @AfterEach
    void tearDown() {
        manager.destroy();
    }

    @Test
    @DisplayName("直接注入证书与大小写不敏感序列号检索")
    void testRegisterAndRetrieveCertificates() {
        String merchantId = "1900000001";
        manager.registerCertificates(merchantId, Map.of(serialNumber, testCert));

        // 1. 精确检索
        X509Certificate cert = manager.getCertificate(merchantId, serialNumber);
        assertNotNull(cert);
        assertEquals(testCert, cert);

        // 2. 小写序列号大小写不敏感检索
        X509Certificate certLowerCase = manager.getCertificate(merchantId, serialNumber.toLowerCase());
        assertNotNull(certLowerCase);
        assertEquals(testCert, certLowerCase);

        // 3. 获取最新可用证书
        X509Certificate availableCert = manager.getAvailableCertificate(merchantId);
        assertNotNull(availableCert);
        assertEquals(testCert, availableCert);
    }

    @Test
    @DisplayName("模拟商户证书拉取闭包与按需触发刷新测试")
    void testRegisterDownloadTaskAndRefresh() {
        String merchantId = "1900000002";
        AtomicInteger downloadCount = new AtomicInteger(0);

        Runnable mockDownloadTask = () -> {
            downloadCount.incrementAndGet();
            manager.registerCertificates(merchantId, Map.of(serialNumber, testCert));
        };

        // 注册时应自动同步执行一次初始拉取
        manager.registerMerchant(merchantId, mockDownloadTask);
        assertEquals(1, downloadCount.get(), "注册时应执行一次初始下载");

        // 手动触发单商户刷新
        manager.refreshCertificate(merchantId);
        assertEquals(2, downloadCount.get());

        // 手动触发全局刷新
        manager.refreshAll();
        assertEquals(3, downloadCount.get());

        // 检索证书验证已注入
        assertNotNull(manager.getCertificate(merchantId, serialNumber));
    }

    @Test
    @DisplayName("注销商户与异常快速失败防御")
    void testUnregisterAndDefenses() {
        String merchantId = "1900000003";
        manager.registerCertificates(merchantId, Map.of(serialNumber, testCert));
        assertNotNull(manager.getCertificate(merchantId, serialNumber));

        // 注销商户
        manager.unregisterMerchant(merchantId);

        // 未配置商户获取证书应快速失败
        assertThrows(PaySignException.class, () -> manager.getCertificate(merchantId, serialNumber));
        assertThrows(PaySignException.class, () -> manager.getAvailableCertificate(merchantId));

        // 空入参防御
        assertThrows(PaySignException.class, () -> manager.getCertificate(null, serialNumber));
        assertThrows(PaySignException.class, () -> manager.getCertificate(merchantId, null));
        assertThrows(PaySignException.class, () -> manager.getAvailableCertificate(null));
    }
}
