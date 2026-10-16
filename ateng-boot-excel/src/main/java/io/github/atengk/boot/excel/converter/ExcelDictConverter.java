package io.github.atengk.boot.excel.converter;

import io.github.atengk.boot.excel.annotation.ExcelDict;
import io.github.atengk.boot.excel.spi.DictProviderHolder;
import org.apache.fesod.sheet.converters.Converter;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.GlobalConfiguration;
import org.apache.fesod.sheet.metadata.data.ReadCellData;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;

import java.lang.reflect.Field;

/**
 * 通用字典双向转义转换器
 * <p>
 * 配合 {@link ExcelDict} 注解与 {@code DictProvider} SPI，实现字典编码与中文标签的无缝互转。
 * 可在 {@code @ExcelProperty(converter = ExcelDictConverter.class)} 中显式配置。
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class ExcelDictConverter implements Converter<Object> {

    @Override
    public Class<?> supportJavaTypeKey() {
        return Object.class;
    }

    @Override
    public CellDataTypeEnum supportExcelTypeKey() {
        return CellDataTypeEnum.STRING;
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
        if (field == null) {
            return text;
        }

        ExcelDict excelDict = field.getAnnotation(ExcelDict.class);
        if (excelDict == null || excelDict.value().isEmpty()) {
            return io.github.atengk.boot.excel.util.TypeConvertUtil.convertStringToTargetType(text, field.getType());
        }

        return DictProviderHolder.toCode(excelDict.value(), text, field.getType(), excelDict.keepOriginalIfUnmapped());
    }

    @Override
    public WriteCellData<?> convertToExcelData(Object value, ExcelContentProperty contentProperty, GlobalConfiguration globalConfiguration) {
        if (value == null) {
            return new WriteCellData<>("");
        }

        Field field = (contentProperty != null) ? contentProperty.getField() : null;
        if (field == null) {
            return new WriteCellData<>(String.valueOf(value));
        }

        ExcelDict excelDict = field.getAnnotation(ExcelDict.class);
        if (excelDict == null || excelDict.value().isEmpty()) {
            return new WriteCellData<>(String.valueOf(value));
        }

        String label = DictProviderHolder.toLabel(excelDict.value(), value, excelDict.keepOriginalIfUnmapped());
        return new WriteCellData<>(label != null ? label : "");
    }
}
