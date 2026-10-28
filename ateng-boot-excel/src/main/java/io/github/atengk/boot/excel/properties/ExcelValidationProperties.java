package io.github.atengk.boot.excel.properties;

import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import lombok.Data;

/**
 * Excel 导入数据校验与错误熔断配置属性
 * <p>
 * 控制前置表头契约校验策略（STRICT/LOOSE/DISABLED）以及流式读取错误行数熔断阈值，
 * 防御非法脏模板穿透与大文件海量错误导致的堆内存耗尽 (OOM)。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
public class ExcelValidationProperties {

    /**
     * 导入表头契约校验策略（默认 LOOSE 宽松全包含匹配；STRICT: 严格匹配列名与顺序；DISABLED: 禁用表头校验）
     */
    private HeaderValidationStrategy headerStrategy = HeaderValidationStrategy.LOOSE;

    /**
     * 错误行数熔断阈值（默认 500 行；当单次导入校验失败错误行数累计达到该阈值时触发熔断终止解析，避免 OOM）
     */
    private int maxErrorRows = 500;
}
