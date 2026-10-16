package io.github.atengk.boot.excel.annotation;

/**
 * Excel 下拉选项动态提供者接口
 * <p>
 * 配合 {@link ExcelDropdown} 注解使用，当下拉选项需要从数据字典、枚举或运行时上下文动态加载时，
 * 可实现此接口提供下拉选项字符数组。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
@FunctionalInterface
public interface DropdownProvider {

    /**
     * 获取下拉菜单候选文本列表
     *
     * @return 候选文本数组
     */
    String[] getOptions();

    /**
     * 默认空提供者实现类
     */
    class Default implements DropdownProvider {
        @Override
        public String[] getOptions() {
            return new String[0];
        }
    }
}
