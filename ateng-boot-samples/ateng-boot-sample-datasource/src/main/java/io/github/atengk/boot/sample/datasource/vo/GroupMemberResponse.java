package io.github.atengk.boot.sample.datasource.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

/**
 * 分组数据源节点成员变更展示视图对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 分组数据源标识
     */
    private String groupKey;

    /**
     * 物理成员数据源标识
     */
    private String memberKey;

    /**
     * 操作类型（ADDED / REMOVED）
     */
    private String operation;

    /**
     * 本次变更是否生效
     */
    private boolean operated;

    /**
     * 当前已注册的全部分组键名集合
     */
    private Set<String> activeGroupKeys;
}
