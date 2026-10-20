package io.github.atengk.boot.excel.autoconfigure;

import io.github.atengk.boot.excel.async.AsyncExportTaskService;
import io.github.atengk.boot.excel.async.InMemoryAsyncExportTaskService;
import io.github.atengk.boot.excel.async.RedisAsyncExportTaskService;
import io.github.atengk.boot.excel.async.storage.ExportFileStorage;
import io.github.atengk.boot.excel.async.storage.LocalFileSystemStorage;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.spi.DictProvider;
import io.github.atengk.boot.excel.spi.DictProviderHolder;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.FesodSheet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Excel 基础设施自动装配类
 *
 * @author Ateng
 * @since 2026-10-16
 */
@AutoConfiguration
@ConditionalOnClass(FesodSheet.class)
@ConditionalOnProperty(prefix = "ateng.boot.excel", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ExcelProperties.class)
public class AtengExcelAutoConfiguration {

    /**
     * 容器初始化完成后自动将受管配置与字典提供者注入静态工具门面与持有者
     * 规避在构造方法中对静态状态产生副作用的生命周期异味
     *
     * @param properties 配置属性
     * @param dictProviderProvider 字典提供者 SPI
     * @return SmartInitializingSingleton 实例
     */
    @Bean
    public org.springframework.beans.factory.SmartInitializingSingleton excelStaticFacadeInitializer(
            ExcelProperties properties,
            ObjectProvider<DictProvider> dictProviderProvider) {
        return () -> {
            if (properties != null) {
                ExcelUtil.initProperties(properties);
            }
            if (dictProviderProvider != null) {
                dictProviderProvider.ifAvailable(DictProviderHolder::setProvider);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(ExportFileStorage.class)
    @ConditionalOnProperty(prefix = "ateng.boot.excel.async-export", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ExportFileStorage exportFileStorage(ExcelProperties properties) {
        return new LocalFileSystemStorage(properties);
    }

    @Bean
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(AsyncExportTaskService.class)
    @ConditionalOnProperty(prefix = "ateng.boot.excel.async-export.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AsyncExportTaskService redisAsyncExportTaskService(
            ExcelProperties properties,
            StringRedisTemplate redisTemplate,
            ExportFileStorage fileStorage) {
        return new RedisAsyncExportTaskService(properties, redisTemplate, fileStorage);
    }

    @Bean
    @ConditionalOnMissingBean(AsyncExportTaskService.class)
    @ConditionalOnProperty(prefix = "ateng.boot.excel.async-export", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AsyncExportTaskService asyncExportTaskService(
            ExcelProperties properties,
            ExportFileStorage fileStorage) {
        return new InMemoryAsyncExportTaskService(properties, fileStorage);
    }
}
