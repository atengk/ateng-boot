package io.github.atengk.boot.pay.core.certificate;

import com.wechat.pay.java.core.auth.Credential;
import com.wechat.pay.java.core.auth.Validator;
import com.wechat.pay.java.core.auth.WechatPay2Credential;
import com.wechat.pay.java.core.certificate.CertificateDownloader;
import com.wechat.pay.java.core.certificate.CertificateHandler;
import com.wechat.pay.java.core.certificate.CertificateProvider;
import com.wechat.pay.java.core.cipher.AeadAesCipher;
import com.wechat.pay.java.core.cipher.RSASigner;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.http.HttpHeaders;
import com.wechat.pay.java.core.util.PemUtil;
import io.github.atengk.boot.pay.config.properties.PayHttpProperties;
import io.github.atengk.boot.pay.config.properties.WechatMerchantProperties;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PaySignException;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Comparator;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 微信支付平台证书自动定时轮换与生命周期管理器
 * <p>
 * 核心特性：
 * <ol>
 *     <li><b>受管后台定时轮换</b>：基于受管后台守护线程池，在应用免重启运行态定时拉取最新平台证书并平滑替换；</li>
 *     <li><b>零中断弹性保底</b>：当定时刷新发生瞬态网络抖动或失败时，保留内存中现有的有效证书，坚决杜绝线上支付中断；</li>
 *     <li><b>多商户内存安全隔离</b>：证书按商户号（merchantId）在并发容器中物理隔离存储；</li>
 *     <li><b>100% 离线测试支持</b>：支持直接注入模拟平台证书与下载任务桩，不依赖微信真实网络环境。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class WechatAutoCertificateManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WechatAutoCertificateManager.class);
    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofHours(1);
    private static final String CERT_DOWNLOAD_URL = "https://api.mch.weixin.qq.com/v3/certificates?algorithm_type=RSA";
    private static final CertificateHandler RSA_CERTIFICATE_HANDLER = new CertificateHandler() {
        @Override
        public X509Certificate generateCertificate(String certificate) {
            return PemUtil.loadX509FromString(certificate);
        }

        @Override
        public void validateCertPath(X509Certificate certificate) {
            // 证书完整性已由 APIv3 密钥 AEAD-AES-256-GCM 解密校验
        }
    };
    private static final Validator BOOTSTRAP_VALIDATOR = new Validator() {
        @Override
        public <T> boolean validate(HttpHeaders responseHeaders, String responseBody) {
            return true;
        }

        @Override
        public <T> String getSerialNumber() {
            return "";
        }
    };

    /**
     * 商户证书缓存表：merchantId -> (serialNumber -> X509Certificate)
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, X509Certificate>> certificateCache = new ConcurrentHashMap<>();

    /**
     * 商户证书刷新任务表：merchantId -> downloadRunnable
     */
    private final ConcurrentHashMap<String, Runnable> downloadTaskMap = new ConcurrentHashMap<>();

    /**
     * 后台受管调度线程池
     */
    private final ScheduledThreadPoolExecutor scheduledExecutor;
    private ScheduledFuture<?> scheduledFuture;

    public WechatAutoCertificateManager() {
        this(DEFAULT_REFRESH_INTERVAL);
    }

    public WechatAutoCertificateManager(Duration refreshInterval) {
        AtomicInteger threadCount = new AtomicInteger(0);
        this.scheduledExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "wechat-cert-rotation-" + threadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.scheduledExecutor.setRemoveOnCancelPolicy(true);

        long intervalMinutes = Math.max(1, refreshInterval.toMinutes());
        this.scheduledFuture = this.scheduledExecutor.scheduleWithFixedDelay(
                this::refreshAllSafely,
                intervalMinutes,
                intervalMinutes,
                TimeUnit.MINUTES
        );
        log.info("Initialized WechatAutoCertificateManager with rotation interval: {} minutes", intervalMinutes);
    }

    /**
     * 注册或更新指定商户的平台证书集合
     *
     * @param merchantId   微信商户号
     * @param certificates 证书序列号与证书映射
     */
    public void registerCertificates(String merchantId, Map<String, X509Certificate> certificates) {
        if (merchantId == null || merchantId.isBlank() || certificates == null || certificates.isEmpty()) {
            return;
        }
        ConcurrentHashMap<String, X509Certificate> targetMap =
                certificateCache.computeIfAbsent(merchantId, k -> new ConcurrentHashMap<>());
        targetMap.putAll(certificates);
        log.info("Registered/Updated {} WeChat platform certificates for merchant [{}]", certificates.size(), merchantId);
    }

    /**
     * 注册商户定时下载任务并立即同步执行一次初始拉取
     *
     * @param merchantId   微信商户号
     * @param downloadTask 证书拉取任务闭包
     */
    public void registerMerchant(String merchantId, Runnable downloadTask) {
        if (merchantId == null || merchantId.isBlank() || downloadTask == null) {
            return;
        }
        downloadTaskMap.put(merchantId, downloadTask);
        runInitialFetchSafely(merchantId, downloadTask);
    }

    /**
     * 幂等注册商户定时下载任务：仅在商户尚未注册下载任务且当前无已缓存证书时立即执行初始拉取
     *
     * @param merchantId   微信商户号
     * @param downloadTask 证书拉取任务闭包
     */
    public void registerMerchantIfAbsent(String merchantId, Runnable downloadTask) {
        if (merchantId == null || merchantId.isBlank() || downloadTask == null) {
            return;
        }
        if (downloadTaskMap.putIfAbsent(merchantId, downloadTask) == null && !hasCertificates(merchantId)) {
            runInitialFetchSafely(merchantId, downloadTask);
        }
    }

    /**
     * 当商户配置启用了自动平台证书下载且凭据完备时，幂等注册官方 {@code /v3/certificates} 下载轮换任务
     *
     * @param merchant                 微信商户配置
     * @param httpProps                HTTP 超时配置（可空，默认兜底）
     * @param customHttpClientSupplier 自定义 HttpClient 提供器（可空，主要用于测试桩注入）
     */
    public void registerMerchantIfReady(WechatMerchantProperties merchant,
                                        PayHttpProperties httpProps,
                                        Supplier<HttpClient> customHttpClientSupplier) {
        if (merchant == null || !merchant.isAutoCertificate()) {
            return;
        }
        String merchantId = merchant.getMchId();
        if (merchantId == null || merchantId.isBlank() || downloadTaskMap.containsKey(merchantId)) {
            return;
        }
        String serialNumber = merchant.getMerchantSerialNumber();
        String apiV3Key = merchant.getApiV3Key();
        String keySource = merchant.resolvePrivateKeySource();
        if (serialNumber == null || serialNumber.isBlank()
                || apiV3Key == null || apiV3Key.isBlank()
                || keySource == null) {
            return;
        }

        PayHttpProperties effectiveHttp = PayHttpProperties.orDefault(httpProps);
        // 每个商户仅构建一次默认 HTTP 下载客户端并在周期轮换中复用连接池，避免重复创建泄露资源
        HttpClient reusableDefaultClient = customHttpClientSupplier == null
                ? buildBootstrapDownloadClient(merchantId, serialNumber, keySource, effectiveHttp)
                : null;
        Runnable task = () -> {
            HttpClient customClient = customHttpClientSupplier != null ? customHttpClientSupplier.get() : null;
            HttpClient downloadClient = customClient != null
                    ? customClient
                    : (reusableDefaultClient != null
                            ? reusableDefaultClient
                            : buildBootstrapDownloadClient(merchantId, serialNumber, keySource, effectiveHttp));

            CertificateDownloader downloader = new CertificateDownloader.Builder()
                    .downloadUrl(CERT_DOWNLOAD_URL)
                    .httpClient(downloadClient)
                    .aeadCipher(new AeadAesCipher(apiV3Key.getBytes(StandardCharsets.UTF_8)))
                    .certificateHandler(RSA_CERTIFICATE_HANDLER)
                    .build();
            Map<String, X509Certificate> downloaded = downloader.download();
            registerCertificates(merchantId, downloaded);
        };
        registerMerchantIfAbsent(merchantId, task);
    }

    private HttpClient buildBootstrapDownloadClient(String merchantId,
                                                    String serialNumber,
                                                    String keySource,
                                                    PayHttpProperties effectiveHttp) {
        PrivateKey privateKey = PayKeyLoader.loadPrivateKey(keySource);
        Credential credential = new WechatPay2Credential(merchantId, new RSASigner(serialNumber, privateKey));
        return new DefaultHttpClientBuilder()
                .credential(credential)
                .validator(BOOTSTRAP_VALIDATOR)
                .connectTimeoutMs(effectiveHttp.resolveConnectTimeoutMs())
                .readTimeoutMs(effectiveHttp.resolveReadTimeoutMs())
                .writeTimeoutMs(effectiveHttp.resolveWriteTimeoutMs())
                .build();
    }

    /**
     * 构建绑定指定商户号的官方 SDK {@link CertificateProvider} 适配器
     *
     * @param merchantId 微信商户号
     * @return CertificateProvider 实例
     */
    public CertificateProvider buildCertificateProvider(String merchantId) {
        return new CertificateProvider() {
            @Override
            public X509Certificate getCertificate(String serialNumber) {
                return WechatAutoCertificateManager.this.getCertificate(merchantId, serialNumber);
            }

            @Override
            public X509Certificate getAvailableCertificate() {
                return WechatAutoCertificateManager.this.getAvailableCertificate(merchantId);
            }
        };
    }

    /**
     * 判断指定商户当前是否已存在缓存证书
     *
     * @param merchantId 微信商户号
     * @return true 已缓存至少一张证书
     */
    public boolean hasCertificates(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return false;
        }
        ConcurrentHashMap<String, X509Certificate> cached = certificateCache.get(merchantId);
        return cached != null && !cached.isEmpty();
    }

    private void runInitialFetchSafely(String merchantId, Runnable downloadTask) {
        try {
            downloadTask.run();
            log.info("Successfully executed initial certificate fetch for merchant [{}]", merchantId);
        } catch (Exception e) {
            log.warn("Initial certificate fetch for merchant [{}] failed, will retry in background schedule: {}",
                    merchantId, e.getMessage());
        }
    }

    /**
     * 注销商户的证书更新任务与缓存
     *
     * @param merchantId 微信商户号
     */
    public void unregisterMerchant(String merchantId) {
        if (merchantId == null) {
            return;
        }
        downloadTaskMap.remove(merchantId);
        certificateCache.remove(merchantId);
        log.info("Unregistered merchant [{}] from WechatAutoCertificateManager", merchantId);
    }

    /**
     * 根据证书序列号获取指定商户的平台证书
     *
     * @param merchantId   微信商户号
     * @param serialNumber 证书序列号（不区分大小写）
     * @return X.509 平台证书实例
     */
    public X509Certificate getCertificate(String merchantId, String serialNumber) {
        if (merchantId == null || serialNumber == null) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "商户号与证书序列号不能为空");
        }
        ConcurrentHashMap<String, X509Certificate> map = certificateCache.get(merchantId);
        if (map != null) {
            for (Map.Entry<String, X509Certificate> entry : map.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(serialNumber)) {
                    return entry.getValue();
                }
            }
        }

        // 若当前缓存未命中，尝试执行一次同步刷新后再探测
        Runnable task = downloadTaskMap.get(merchantId);
        if (task != null) {
            try {
                task.run();
                ConcurrentHashMap<String, X509Certificate> updated = certificateCache.get(merchantId);
                if (updated != null) {
                    for (Map.Entry<String, X509Certificate> entry : updated.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(serialNumber)) {
                            return entry.getValue();
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("On-demand certificate refresh for merchant [{}] failed: {}", merchantId, e.getMessage());
            }
        }

        throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR,
                "未找到对应的微信支付平台证书: merchantId=" + merchantId + ", serialNumber=" + serialNumber);
    }

    /**
     * 获取指定商户当前最新可用的微信支付平台证书（优先挑选有效期最长且合法的证书）
     *
     * @param merchantId 微信商户号
     * @return 可用 X.509 平台证书
     */
    public X509Certificate getAvailableCertificate(String merchantId) {
        if (merchantId == null) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "商户号不能为空");
        }
        ConcurrentHashMap<String, X509Certificate> activeMap = certificateCache.get(merchantId);
        if (activeMap == null || activeMap.isEmpty()) {
            refreshCertificate(merchantId);
            activeMap = certificateCache.get(merchantId);
        }
        if (activeMap == null || activeMap.isEmpty()) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR,
                    "微信商户 [" + merchantId + "] 未下载或未注册任何可用平台证书");
        }

        Date now = new Date();
        final ConcurrentHashMap<String, X509Certificate> targetMap = activeMap;
        return targetMap.values().stream()
                .filter(cert -> {
                    try {
                        cert.checkValidity(now);
                        return true;
                    } catch (CertificateExpiredException | CertificateNotYetValidException e) {
                        return false;
                    }
                })
                .max(Comparator.comparing(X509Certificate::getNotAfter))
                .orElseGet(() -> targetMap.values().iterator().next());
    }

    /**
     * 手动触发指定商户的证书拉取与更新
     *
     * @param merchantId 微信商户号
     */
    public void refreshCertificate(String merchantId) {
        Runnable task = downloadTaskMap.get(merchantId);
        if (task != null) {
            task.run();
        }
    }

    /**
     * 手动触发全量商户的证书更新
     */
    public void refreshAll() {
        for (Map.Entry<String, Runnable> entry : downloadTaskMap.entrySet()) {
            try {
                entry.getValue().run();
                log.info("Refreshed WeChat platform certificates for merchant [{}]", entry.getKey());
            } catch (Exception e) {
                log.error("Failed to refresh WeChat platform certificates for merchant [{}]: {}",
                        entry.getKey(), e.getMessage(), e);
            }
        }
    }

    /**
     * 周期任务执行的安全兜底包装（杜绝因单商户异常导致调度中断）
     */
    private void refreshAllSafely() {
        try {
            refreshAll();
        } catch (Throwable t) {
            log.error("Unexpected error in WeChat platform certificate rotation background schedule", t);
        }
    }

    @Override
    public void destroy() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        scheduledExecutor.shutdown();
        try {
            if (!scheduledExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        certificateCache.clear();
        downloadTaskMap.clear();
        log.info("WechatAutoCertificateManager destroyed cleanly");
    }
}
