package io.github.atengk.boot.sample.milvus.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * MilvusExprHelper 复合表达式检索结果视图对象
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExprSearchVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实际编译生成的安全过滤表达式
     */
    private String compiledExpr;

    /**
     * 检索命中结果列表
     */
    private List<DocumentHitVo> hits;
}
