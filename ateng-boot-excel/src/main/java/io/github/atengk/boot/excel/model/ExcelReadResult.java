package io.github.atengk.boot.excel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
}
