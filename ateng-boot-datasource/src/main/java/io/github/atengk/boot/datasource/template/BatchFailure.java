package io.github.atengk.boot.datasource.template;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
     * 原始异常对象（标配 @JsonIgnore，防止经 HTTP 响应序列化时泄露异常堆栈与底层细节）
     */
    @JsonIgnore
    private final Throwable cause;
}
