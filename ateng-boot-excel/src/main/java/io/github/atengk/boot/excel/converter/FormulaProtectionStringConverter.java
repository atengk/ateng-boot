package io.github.atengk.boot.excel.converter;

import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.converters.Converter;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.GlobalConfiguration;
import org.apache.fesod.sheet.metadata.data.ReadCellData;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;

/**
 * Excel 字符串与公式注入对称脱转转换器
 * <p>
 * 在数据读取/导入阶段，自动识别单引号保护前缀并对称脱去（{@link ExcelSecurityUtil#unescapeFormulaLeading}），
 * 确保写入 Java 实体对象的字符串为真实业务明文，严格守卫“内存真明文”不变量。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public class FormulaProtectionStringConverter implements Converter<String> {

    @Override
    public Class<?> supportJavaTypeKey() {
        return String.class;
    }

    @Override
    public CellDataTypeEnum supportExcelTypeKey() {
        return CellDataTypeEnum.STRING;
    }

    @Override
    public String convertToJavaData(ReadCellData<?> cellData, ExcelContentProperty contentProperty, GlobalConfiguration globalConfiguration) {
        if (cellData == null) {
            return null;
        }
        String value = cellData.getStringValue();
        if (value == null) {
            return null;
        }
        if (ExcelUtil.getSecurityProperties().isFormulaProtection()) {
            return ExcelSecurityUtil.unescapeFormulaLeading(value);
        }
        return value;
    }

    @Override
    public WriteCellData<?> convertToExcelData(String value, ExcelContentProperty contentProperty, GlobalConfiguration globalConfiguration) {
        return new WriteCellData<>(value);
    }
}
