package io.github.atengk.boot.excel.converter;

import io.github.atengk.boot.excel.annotation.ExcelEnum;
import io.github.atengk.boot.excel.util.TypeConvertUtil;
import org.apache.fesod.sheet.converters.Converter;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.GlobalConfiguration;
import org.apache.fesod.sheet.metadata.data.ReadCellData;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;

import java.lang.reflect.Field;

/**
 * 通用枚举双向转义转换器
 * <p>
 * 既可在字段上由框架通过 {@code @ExcelEnum} 自动扫描装配，
 * 也支持在 {@code @ExcelProperty(converter = ExcelEnumConverter.class)} 中显式声明。
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class ExcelEnumConverter implements Converter<Object> {

    private final Class<?> boundEnumClass;
    private final ExcelEnum boundExcelEnum;
    private final CellDataTypeEnum boundExcelTypeKey;

    /**
     * 无参构造器，供 Fesod 框架通过反射实例化
     */
    public ExcelEnumConverter() {
        this(null, null, null);
    }

    /**
     * 指定枚举类型与注解元数据的构造器，供框架自动注册特定枚举类型转换器时使用
     *
     * @param enumClass 绑定的枚举类
     * @param excelEnum 注解配置
     */
    public ExcelEnumConverter(Class<?> enumClass, ExcelEnum excelEnum) {
        this(enumClass, excelEnum, null);
    }

    /**
     * 指定枚举类型、注解元数据与单元格类型 Key 的构造器
     *
     * @param enumClass        绑定的枚举类
     * @param excelEnum        注解配置
     * @param excelTypeKey 单元格类型 Key (null 适配写入，STRING 适配读取)
     */
    public ExcelEnumConverter(Class<?> enumClass, ExcelEnum excelEnum, CellDataTypeEnum excelTypeKey) {
        this.boundEnumClass = enumClass;
        this.boundExcelEnum = excelEnum;
        this.boundExcelTypeKey = excelTypeKey;
    }

    @Override
    public Class<?> supportJavaTypeKey() {
        return boundEnumClass != null ? boundEnumClass : Object.class;
    }

    @Override
    public CellDataTypeEnum supportExcelTypeKey() {
        return boundExcelTypeKey;
    }

    @Override
    public Object convertToJavaData(ReadCellData<?> cellData, ExcelContentProperty contentProperty, GlobalConfiguration globalConfiguration) {
        if (cellData == null) {
            return null;
        }
        String text = cellData.getStringValue();
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        Field field = (contentProperty != null) ? contentProperty.getField() : null;
        EnumMappingCache.EnumMeta enumMeta = resolveEnumMeta(field);
        if (enumMeta == null) {
            return text;
        }

        Class<?> targetType = (field != null) ? field.getType() : boundEnumClass;
        if (targetType != null && targetType.isEnum()) {
            Enum<?> matchEnum = enumMeta.toEnum(text);
            if (matchEnum != null) {
                return matchEnum;
            }
            if (!enumMeta.ignoreUnmapped()) {
                throw new IllegalArgumentException("未找到匹配的枚举常量: [" + text + "], 目标枚举: " + targetType.getName());
            }
            return null;
        }

        // 目标字段为 String 或基础编码类型 (如 Integer, Long)
        Object code = enumMeta.toCode(text);
        if (code == null) {
            if (!enumMeta.ignoreUnmapped()) {
                throw new IllegalArgumentException("未找到匹配的枚举编码: [" + text + "], 目标枚举: " + enumMeta.enumClass().getName());
            }
            code = text;
        }
        return TypeConvertUtil.convertStringToTargetType(String.valueOf(code), targetType);
    }

    @Override
    public WriteCellData<?> convertToExcelData(Object value, ExcelContentProperty contentProperty, GlobalConfiguration globalConfiguration) {
        if (value == null) {
            return new WriteCellData<>("");
        }

        Field field = (contentProperty != null) ? contentProperty.getField() : null;
        EnumMappingCache.EnumMeta enumMeta = resolveEnumMeta(field);
        if (enumMeta == null) {
            return new WriteCellData<>(String.valueOf(value));
        }

        String label = enumMeta.toText(value);
        if (label == null) {
            if (!enumMeta.ignoreUnmapped()) {
                throw new IllegalArgumentException("未找到匹配的枚举描述: [" + value + "], 目标枚举: " + enumMeta.enumClass().getName());
            }
            label = "";
        }
        return new WriteCellData<>(label);
    }

    private EnumMappingCache.EnumMeta resolveEnumMeta(Field field) {
        Class<?> enumClass = boundEnumClass;
        ExcelEnum excelEnum = boundExcelEnum;

        if (field != null) {
            ExcelEnum fieldAnn = EnumMappingCache.resolveEnumAnnotation(field);
            if (fieldAnn != null) {
                excelEnum = fieldAnn;
            }
            Class<?> resolvedClass = EnumMappingCache.resolveEnumClass(field);
            if (resolvedClass != null) {
                enumClass = resolvedClass;
            }
        }

        if (enumClass == null || !enumClass.isEnum()) {
            return null;
        }
        return EnumMappingCache.getEnumMeta(enumClass, excelEnum);
    }
}
