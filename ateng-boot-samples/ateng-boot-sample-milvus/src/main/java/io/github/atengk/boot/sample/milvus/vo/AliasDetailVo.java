package io.github.atengk.boot.sample.milvus.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 集合别名详情视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AliasDetailVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 逻辑别名
     */
    private String alias;

    /**
     * 当前绑定的物理集合名称
     */
    private String collectionName;
}
