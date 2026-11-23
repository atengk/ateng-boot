package io.github.atengk.boot.sample.milvus.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 游标流式深度遍历汇总视图对象
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamQueryVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实际执行的标量过滤表达式
     */
    private String filterExpr;

    /**
     * 游标分批拉取次数
     */
    private int batchCount;

    /**
     * 累计遍历文档总数
     */
    private long totalCount;

    /**
     * 遍历出的文档视图列表
     */
    private List<DocumentVo> items;
}
