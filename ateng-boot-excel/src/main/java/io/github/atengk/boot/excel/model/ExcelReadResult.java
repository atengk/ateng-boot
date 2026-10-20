package io.github.atengk.boot.excel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 读取与校验结果复合模型
 * <p>
 * 包含合法解析的数据列表与未通过校验的错误行信息。
 * </p>
 *
 * @param <T> 数据实体泛型
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExcelReadResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 合法数据列表
     */
    @Builder.Default
    private List<T> data = new ArrayList<>();

    /**
     * 校验失败的错误行信息列表
     */
    @Builder.Default
    private List<ExcelErrorRow> errors = new ArrayList<>();

    /**
     * 是否触发了错误行数熔断（达到 maxErrorRows 上限后停止后续解析）
     */
    @Builder.Default
    private boolean circuitBroken = false;

    /**
     * 是否存在校验错误
     *
     * @return true 若存在至少一条错误
     */
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    /**
     * 获取不可修改的合法数据列表（防御性返回空集合）
     */
    public List<T> getData() {
        return data != null ? Collections.unmodifiableList(data) : Collections.emptyList();
    }

    /**
     * 获取不可修改的错误列表（防御性返回空集合）
     */
    public List<ExcelErrorRow> getErrors() {
        return errors != null ? Collections.unmodifiableList(errors) : Collections.emptyList();
    }

    /**
     * 是否全部校验通过
     *
     * @return true 若无校验错误
     */
    public boolean isSuccess() {
        return !hasErrors();
    }

    /**
     * 获取错误行数
     */
    public int getErrorCount() {
        return errors != null ? errors.size() : 0;
    }

    /**
     * 提取去重后的失败实体数据列表（防御性返回空集合）
     *
     * @return 校验失败的 Java 实体对象列表
     */
    @SuppressWarnings("unchecked")
    public List<T> getFailedData() {
        if (errors == null || errors.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Integer, T> uniqueRows = new LinkedHashMap<>();
        for (ExcelErrorRow err : errors) {
            if (err.getRowData() != null) {
                uniqueRows.putIfAbsent(err.getRowIndex(), (T) err.getRowData());
            }
        }
        return new ArrayList<>(uniqueRows.values());
    }
}
