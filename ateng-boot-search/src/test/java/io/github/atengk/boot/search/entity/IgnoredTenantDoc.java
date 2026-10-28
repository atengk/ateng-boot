package io.github.atengk.boot.search.entity;

import io.github.atengk.boot.search.annotation.IgnoreTenant;
import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标注了 @IgnoreTenant 豁免多租户过滤的全局字典测试实体
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IgnoreTenant
@SearchIndex("global_dict_idx")
public class IgnoredTenantDoc {

    @SearchId
    private String code;

    private String name;
}
