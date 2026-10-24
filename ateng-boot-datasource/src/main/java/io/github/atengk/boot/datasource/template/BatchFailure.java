package io.github.atengk.boot.datasource.template;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 批处理单项执行失败详情
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Getter
@ToString
@AllArgsConstructor
public class BatchFailure implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 执行目标标识（租户 ID 或数据源 Key）
     */
    private final String target;

    /**
     * 异常错误信息简述
     */
    private final String errorMessage;

    /**
     * 原始异常对象
     */
    private final Throwable cause;
}
