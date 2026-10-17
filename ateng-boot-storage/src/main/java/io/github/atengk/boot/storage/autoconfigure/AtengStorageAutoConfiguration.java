package io.github.atengk.boot.storage.autoconfigure;

import io.github.atengk.boot.storage.controller.LocalStorageDownloadController;
import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.S3Driver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.properties.LocalStorageProperties;
import io.github.atengk.boot.storage.properties.S3StorageProperties;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 存储基础设施自动装配类
 *
 * @author Ateng
 * @since 2026-10-17
 */
@AutoConfiguration
@ConditionalOnClass(StorageDriver.class)
@ConditionalOnProperty(prefix = "ateng.boot.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StorageProperties.class)
public class AtengStorageAutoConfiguration {

    /**
     * 默认本地存储驱动 Bean 装配（当 type=local 或未指定时装配）
     * 优先选用配置的 local.basePath，未指定时降级保底至系统临时目录下的 ateng-storage；
     * 若未配置 secretKey 则自动生成 256 位安全随机密钥并回填配置属性
     *
     * @param properties 存储配置属性
     * @return LocalDriver 实例
     */
    @Bean
    @ConditionalOnMissingBean(StorageDriver.class)
    @ConditionalOnProperty(prefix = "ateng.boot.storage", name = "type", havingValue = "local", matchIfMissing = true)
    public StorageDriver localStorageDriver(StorageProperties properties) {
        LocalStorageProperties local = properties.getLocal();
        String basePath = local.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            basePath = System.getProperty("java.io.tmpdir") + "/ateng-storage";
        }
        String secretKey = local.getSecretKey();
        if (secretKey == null || secretKey.isBlank()) {
            secretKey = StorageSecurityUtil.generateRandomSecretKey();
            local.setSecretKey(secretKey);
        }
        return new LocalDriver(basePath, local.getDomain(), secretKey, local.getDownloadEndpoint());
    }

    /**
     * S3Client Bean 自动装配（当 type=s3 且类路径存在 S3Client 时生效）
     *
     * @param properties 存储配置属性
     * @return S3Client 实例
     */
    @Bean
    @ConditionalOnMissingBean(S3Client.class)
    @ConditionalOnClass(S3Client.class)
    @ConditionalOnProperty(prefix = "ateng.boot.storage", name = "type", havingValue = "s3")
    public S3Client s3Client(StorageProperties properties) {
        return S3Driver.createS3Client(properties.getS3());
    }

    /**
     * S3Presigner Bean 自动装配（当 type=s3 且类路径存在 S3Presigner 时生效）
     *
     * @param properties 存储配置属性
     * @return S3Presigner 实例
     */
    @Bean
    @ConditionalOnMissingBean(S3Presigner.class)
    @ConditionalOnClass(S3Presigner.class)
    @ConditionalOnProperty(prefix = "ateng.boot.storage", name = "type", havingValue = "s3")
    public S3Presigner s3Presigner(StorageProperties properties) {
        return S3Driver.createS3Presigner(properties.getS3());
    }

    /**
     * S3 存储驱动 Bean 装配（当 type=s3 且类路径存在 S3Client 时生效）
     *
     * @param properties          存储配置属性
     * @param s3ClientProvider    S3Client 候选 Provider
     * @param s3PresignerProvider S3Presigner 候选 Provider
     * @return S3Driver 驱动实例
     */
    @Bean
    @ConditionalOnMissingBean(StorageDriver.class)
    @ConditionalOnClass(S3Client.class)
    @ConditionalOnProperty(prefix = "ateng.boot.storage", name = "type", havingValue = "s3")
    public StorageDriver s3StorageDriver(StorageProperties properties,
                                        ObjectProvider<S3Client> s3ClientProvider,
                                        ObjectProvider<S3Presigner> s3PresignerProvider) {
        S3StorageProperties s3 = properties.getS3();
        S3Client s3Client = s3ClientProvider.getIfAvailable(() -> S3Driver.createS3Client(s3));
        S3Presigner s3Presigner = s3PresignerProvider.getIfAvailable(() -> S3Driver.createS3Presigner(s3));
        return new S3Driver(s3Client, s3Presigner, s3.getBucket(), s3.getDomain());
    }

    /**
     * 存储核心操作门面 Bean 装配
     *
     * @param defaultDriver 默认存储驱动
     * @return StorageTemplate 门面实例
     */
    @Bean
    @ConditionalOnMissingBean(StorageOperations.class)
    public StorageTemplate storageTemplate(StorageDriver defaultDriver) {
        return new StorageTemplate(defaultDriver);
    }

    /**
     * 本地存储预签名防盗链下载端点 Bean 装配（仅在 Servlet Web 环境下且类型为 local 时激活）
     *
     * @param properties    存储配置属性
     * @param storageDriver 活动存储驱动
     * @return LocalStorageDownloadController 端点控制器
     */
    @Bean
    @ConditionalOnMissingBean(LocalStorageDownloadController.class)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "ateng.boot.storage", name = "type", havingValue = "local", matchIfMissing = true)
    public LocalStorageDownloadController localStorageDownloadController(
            StorageProperties properties,
            StorageDriver storageDriver) {
        return new LocalStorageDownloadController(properties, storageDriver);
    }
}
