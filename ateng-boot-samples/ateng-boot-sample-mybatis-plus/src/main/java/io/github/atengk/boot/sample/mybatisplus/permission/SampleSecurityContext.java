package io.github.atengk.boot.sample.mybatisplus.permission;

import java.util.List;

/**
 * 样例安全上下文
 * 模拟当前登录用户所拥有的部门访问权限集合与租户上下文
 *
 * @author Ateng
 * @since 2026-10-19
 */
public final class SampleSecurityContext {

    private static final ThreadLocal<List<Long>> ACCESSIBLE_DEPT_IDS = new ThreadLocal<>();

    private SampleSecurityContext() {
    }

    public static void setAccessibleDeptIds(List<Long> deptIds) {
        ACCESSIBLE_DEPT_IDS.set(deptIds);
    }

    public static List<Long> getAccessibleDeptIds() {
        return ACCESSIBLE_DEPT_IDS.get();
    }

    public static void clear() {
        ACCESSIBLE_DEPT_IDS.remove();
    }
}
