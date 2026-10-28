package io.github.atengk.boot.excel.properties;

import lombok.Data;

/**
 * Excel 安全防护与公式注入防御配置属性
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
public class ExcelSecurityProperties {

    /**
     * 是否开启公式注入安全防御（默认开启，导出时对以 =, +, -, @, \t, \r 开头的字符串前置单引号，导入时自动逆向还原）
     */
    private boolean formulaProtection = true;
}
