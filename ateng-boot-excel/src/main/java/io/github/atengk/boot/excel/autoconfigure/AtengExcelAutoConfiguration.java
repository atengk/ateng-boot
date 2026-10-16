package io.github.atengk.boot.excel.autoconfigure;

import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.spi.DictProvider;
import io.github.atengk.boot.excel.spi.DictProviderHolder;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.FesodSheet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

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

    public AtengExcelAutoConfiguration(ExcelProperties properties,
                                      ObjectProvider<DictProvider> dictProviderProvider) {
        if (properties != null) {
            ExcelUtil.initProperties(properties);
        }
        if (dictProviderProvider != null) {
            dictProviderProvider.ifAvailable(DictProviderHolder::setProvider);
        }
    }
}
