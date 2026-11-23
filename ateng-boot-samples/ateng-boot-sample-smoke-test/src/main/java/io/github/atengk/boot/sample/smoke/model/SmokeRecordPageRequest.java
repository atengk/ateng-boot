package io.github.atengk.boot.sample.smoke.model;

import io.github.atengk.boot.core.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 冒烟记录分页查询请求 DTO
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SmokeRecordPageRequest extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 按标题模糊过滤（可选）
     */
    private String title;

    /**
     * 按状态精确过滤（可选）
     */
    private String status;
}
