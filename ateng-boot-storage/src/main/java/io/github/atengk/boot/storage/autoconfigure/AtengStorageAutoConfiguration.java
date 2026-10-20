package io.github.atengk.boot.storage.autoconfigure;

import io.github.atengk.boot.storage.aspect.StorageAspect;
import io.github.atengk.boot.storage.context.StorageTaskDecorator;
import io.github.atengk.boot.storage.controller.LocalStorageDownloadController;
import io.github.atengk.boot.storage.controller.LocalStorageUploadController;
import io.github.atengk.boot.storage.driver.S3Driver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.properties.StorageSourceProperties;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.Map;

/**
 * 存储基础设施自动装配类
 * 支持单源平铺与多源 sources 智能双模装配、多驱动生命周期管理、AOP 切面及跨线程异步透传
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
     * 存储驱动单例工厂 Bean 装配
     * 统一纳管所有命名存储源（单源极简平铺或多源 sources 字典），提供动态切源与生命周期优雅释放
     *
     * @param properties 存储配置属性
     * @return StorageDriverFactory 工厂实例
     */
    @Bean
    @ConditionalOnMissingBean(StorageDriverFactory.class)
    public StorageDriverFactory storageDriverFactory(StorageProperties properties) {
        String primary = properties.getPrimary();
        if (primary == null || primary.isBlank()) {
            primary = "primary";
        }
        StorageDriverFactory factory = new StorageDriverFactory(primary);

        // 1. 若配置了 sources 多源字典，遍历并创建对应驱动
        Map<String, StorageSourceProperties> sources = properties.getSources();
        if (sources != null && !sources.isEmpty()) {
            for (Map.Entry<String, StorageSourceProperties> entry : sources.entrySet()) {
                factory.registerDriver(entry.getKey(), StorageDriverFactory.createDriver(entry.getValue()));
            }
        }

        // 2. 若工厂中尚未包含 primary 驱动，则使用顶层单源平铺配置补齐作为 primary
        if (!factory.containsDriver(primary)) {
            StorageSourceProperties fallbackSource = new StorageSourceProperties();
            fallbackSource.setType(properties.getType());
            fallbackSource.setLocal(properties.getLocal());
            fallbackSource.setS3(properties.getS3());
            factory.registerDriver(primary, StorageDriverFactory.createDriver(fallbackSource));
        }

        return factory;
    }

    /**
     * 默认主存储驱动 Bean 装配
     * 从 StorageDriverFactory 中获取 primary 驱动，保障对单驱动注入的 100% 向后兼容
     *
     * @param factory 存储驱动工厂
     * @return primary StorageDriver 实例
     */
    @Bean
    @ConditionalOnMissingBean(StorageDriver.class)
    public StorageDriver storageDriver(StorageDriverFactory factory) {
        return factory.getPrimaryDriver();
    }

    /**
     * 存储安全防卫与文件魔数嗅探器 Bean 装配
     *
     * @param properties 存储配置属性
     * @return StorageGuard 实例
     */
    @Bean
    @ConditionalOnMissingBean(StorageGuard.class)
    public StorageGuard storageGuard(StorageProperties properties) {
        return new StorageGuard(properties.getGuard());
    }

    /**
     * 存储核心操作门面 Bean 装配
     *
     * @param defaultDriver 默认主存储驱动
     * @param factory       存储驱动工厂
     * @param storageGuard  存储安全防卫器
     * @return StorageTemplate 门面实例
     */
    @Bean
    @ConditionalOnMissingBean(StorageOperations.class)
    public StorageTemplate storageTemplate(StorageDriver defaultDriver, StorageDriverFactory factory, StorageGuard storageGuard) {
        StorageTemplate template = new StorageTemplate(defaultDriver, factory);
        template.setStorageGuard(storageGuard);
        return template;
    }

    /**
     * 声明式多存储源切换切面 Bean 装配
     * 仅当类路径存在 AspectJ 切面环境时自动装配
     *
     * @return StorageAspect 切面实例
     */
    @Bean
    @ConditionalOnClass(Aspect.class)
    @ConditionalOnMissingBean(StorageAspect.class)
    public StorageAspect storageAspect() {
        return new StorageAspect();
    }

    /**
     * 存储上下文感知异步任务装饰器 Bean 装配
     * 联动 Core 模块四维快照与存储上下文在跨线程池调度时保持透传
     *
     * @return StorageTaskDecorator 实例
     */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    public StorageTaskDecorator storageTaskDecorator() {
        return new StorageTaskDecorator();
    }

    /**
     * S3 独立客户端静态嵌套配置类
     * 仅当类路径存在 S3Client 且配置了 type=s3 时激活，隔离类加载与方法内省防御
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(S3Client.class)
    @ConditionalOnProperty(prefix = "ateng.boot.storage", name = "type", havingValue = "s3")
    static class S3StorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(S3Client.class)
        public S3Client s3Client(StorageProperties properties) {
            return S3Driver.createS3Client(properties.getS3());
        }

        @Bean
        @ConditionalOnMissingBean(S3Presigner.class)
        @ConditionalOnClass(S3Presigner.class)
        public S3Presigner s3Presigner(StorageProperties properties) {
            return S3Driver.createS3Presigner(properties.getS3());
        }
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

    /**
     * 本地存储预签名直传写入端点 Bean 装配（仅在 Servlet Web 环境下且类型为 local 时激活）
     *
     * @param properties 存储配置属性
     * @param storageDriver 活动存储驱动
     * @param driverFactoryProvider 多存储源工厂提供者
     * @param storageGuardProvider 安全防卫嗅探器提供者
     * @return LocalStorageUploadController 端点控制器
     */
    @Bean
    @ConditionalOnMissingBean(LocalStorageUploadController.class)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "ateng.boot.storage", name = "type", havingValue = "local", matchIfMissing = true)
    public LocalStorageUploadController localStorageUploadController(
            StorageProperties properties,
            StorageDriver storageDriver,
            ObjectProvider<StorageDriverFactory> driverFactoryProvider,
            ObjectProvider<StorageGuard> storageGuardProvider) {
        return new LocalStorageUploadController(properties, storageDriver, driverFactoryProvider, storageGuardProvider);
    }
}
