package io.github.atengk.boot.sample.milvus.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 集合信息视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MilvusCollectionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 集合名称
     */
    private String collectionName;

    /**
     * 集合描述
     */
    private String description;

    /**
     * 加载状态 (Loaded / NotLoaded)
     */
    private String loadState;

    /**
     * 集合行数预估
     */
    private Long rowCount;
}
