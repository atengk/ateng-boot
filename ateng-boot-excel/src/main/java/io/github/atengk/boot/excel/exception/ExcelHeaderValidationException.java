package io.github.atengk.boot.excel.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * Excel 导入表头契约校验异常
 * <p>
 * 当上传的 Excel 表头列名、排列顺序或必要列缺失，未满足指定的表头契约策略时抛出。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Getter
public class ExcelHeaderValidationException extends BusinessException {

    private final HeaderValidationStrategy strategy;
    private final List<String> expectedHeaders;
    private final List<String> actualHeaders;
    private final List<String> missingHeaders;

    public ExcelHeaderValidationException(String message,
                                          HeaderValidationStrategy strategy,
                                          List<String> expectedHeaders,
                                          List<String> actualHeaders,
                                          List<String> missingHeaders) {
        super(CommonResultCode.BAD_REQUEST.getCode(), message);
        this.strategy = strategy;
        this.expectedHeaders = (expectedHeaders != null) ? Collections.unmodifiableList(expectedHeaders) : Collections.emptyList();
        this.actualHeaders = (actualHeaders != null) ? Collections.unmodifiableList(actualHeaders) : Collections.emptyList();
        this.missingHeaders = (missingHeaders != null) ? Collections.unmodifiableList(missingHeaders) : Collections.emptyList();
    }
}
