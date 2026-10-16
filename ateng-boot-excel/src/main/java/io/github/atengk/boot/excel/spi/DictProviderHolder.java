package io.github.atengk.boot.excel.spi;

import io.github.atengk.boot.excel.util.TypeConvertUtil;

/**
 * 字典 SPI 提供者静态持有器
 * <p>
 * 提供轻量静态门面供转换器与单元格拦截器调用，支持 Spring 容器自动装配或脱机独立单元测试注入。
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class DictProviderHolder {

    private static volatile DictProvider provider;

    private DictProviderHolder() {
    }

    /**
     * 注册或更新全局字典提供者
     *
     * @param dictProvider 字典 SPI 实例
     */
    public static void setProvider(DictProvider dictProvider) {
        provider = dictProvider;
    }

    /**
     * 获取当前全局字典提供者
     *
     * @return 字典 SPI 实例
     */
    public static DictProvider getProvider() {
        return provider;
    }

    /**
     * 导出转义：将字典编码转换为中文标签
     *
     * @param dictType               字典类型
     * @param code                   字典编码
     * @param keepOriginalIfUnmapped 未匹配时是否保留原值
     * @return 展示标签
     */
    public static String toLabel(String dictType, Object code, boolean keepOriginalIfUnmapped) {
        if (code == null) {
            return null;
        }
        DictProvider current = provider;
        if (current != null) {
            String label = current.toLabel(dictType, code);
            if (label != null) {
                return label;
            }
        }
        return keepOriginalIfUnmapped ? String.valueOf(code) : null;
    }

    /**
     * 导入转义：将中文标签反向翻译并转换为目标字段类型
     *
     * @param dictType               字典类型
     * @param label                  中文标签
     * @param targetType             目标字段类型
     * @param keepOriginalIfUnmapped 未匹配时是否保留原值
     * @return 目标类型值
     */
    public static Object toCode(String dictType, String label, Class<?> targetType, boolean keepOriginalIfUnmapped) {
        if (label == null) {
            return null;
        }
        DictProvider current = provider;
        String codeStr = null;
        if (current != null) {
            codeStr = current.toCode(dictType, label);
        }
        if (codeStr == null) {
            codeStr = keepOriginalIfUnmapped ? label : null;
        }
        if (codeStr == null) {
            return null;
        }
        return TypeConvertUtil.convertStringToTargetType(codeStr, targetType);
    }
}
