package io.github.atengk.boot.search.util;

import io.github.atengk.boot.search.query.SearchQueryBuilder;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Objects;

/**
 * 搜索引擎数据权限范围过滤辅助工具
 * <p>
 * 提供向 {@link SearchQueryBuilder} 挂载行级数据权限（如部门数据范围、个人数据归属）的便捷原语，
 * 默认织入 filter 分支以享受查询缓存并防止非授权数据泄露。
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class SearchDataScopeHelper {

    private SearchDataScopeHelper() {
    }

    /**
     * 挂载单个字段的数据范围白名单过滤
     *
     * @param builder       查询构建器
     * @param field         权限控制字段名（如 dept_id）
     * @param allowedValues 授权的数据值集合（若为空或包含 null 则不挂载或视具体业务处理）
     */
    public static void applyScope(SearchQueryBuilder builder, String field, Collection<?> allowedValues) {
        if (builder == null || !StringUtils.hasText(field) || allowedValues == null || allowedValues.isEmpty()) {
            return;
        }
        builder.filter(b -> b.terms(field, allowedValues));
    }

    /**
     * 挂载“用户本人归属 OR 所属部门”复合数据权限
     *
     * @param builder   查询构建器
     * @param userField 拥有者用户字段（如 create_by）
     * @param userId    当前用户 ID
     * @param deptField 所属部门字段（如 dept_id）
     * @param deptIds   当前用户管辖部门 ID 集合
     */
    public static void applyUserOrDeptScope(SearchQueryBuilder builder,
                                           String userField, Object userId,
                                           String deptField, Collection<?> deptIds) {
        if (builder == null) {
            return;
        }

        boolean hasUser = StringUtils.hasText(userField) && userId != null;
        boolean hasDepts = StringUtils.hasText(deptField) && deptIds != null && !deptIds.isEmpty();

        if (hasUser && hasDepts) {
            builder.filter(b -> b.should(sb -> {
                sb.term(userField, userId);
                sb.terms(deptField, deptIds);
            }).minimumShouldMatch(1));
        } else if (hasUser) {
            builder.filter(b -> b.term(userField, userId));
        } else if (hasDepts) {
            builder.filter(b -> b.terms(deptField, deptIds));
        }
    }
}
