package io.github.atengk.boot.storage.autoconfigure;

import io.github.atengk.boot.storage.controller.LocalStorageDownloadController;
import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.properties.LocalStorageProperties;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
     * 默认本地存储驱动 Bean 装配
     * 优先选用配置的 local.basePath，未指定时降级保底至系统临时目录下的 ateng-storage；
     * 若未配置 secretKey 则自动生成 256 位安全随机密钥并回填配置属性
     *
     * @param properties 存储配置属性
     * @return LocalDriver 实例
     */
    @Bean
    @ConditionalOnMissingBean(StorageDriver.class)
    public StorageDriver storageDriver(StorageProperties properties) {
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
     * 本地存储预签名防盗链下载端点 Bean 装配（仅在 Servlet Web 环境下激活）
     *
     * @param properties    存储配置属性
     * @param storageDriver 活动存储驱动
     * @return LocalStorageDownloadController 端点控制器
     */
    @Bean
    @ConditionalOnMissingBean(LocalStorageDownloadController.class)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public LocalStorageDownloadController localStorageDownloadController(
            StorageProperties properties,
            StorageDriver storageDriver) {
        return new LocalStorageDownloadController(properties, storageDriver);
    }
}
