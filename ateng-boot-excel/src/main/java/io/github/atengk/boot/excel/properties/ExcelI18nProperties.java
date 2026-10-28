package io.github.atengk.boot.excel.properties;

import lombok.Data;

/**
 * Excel 国际化多语言配置属性
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
public class ExcelI18nProperties {

    /**
     * 是否开启多语言国际化表头动态转译（默认开启）
     */
    private boolean enabled = true;
}
