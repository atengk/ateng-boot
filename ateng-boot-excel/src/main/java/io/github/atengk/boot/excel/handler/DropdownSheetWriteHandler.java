package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.excel.annotation.DropdownProvider;
import io.github.atengk.boot.excel.annotation.ExcelDropdown;
import io.github.atengk.boot.excel.util.ExcelColumnMetadataResolver;
import org.apache.fesod.sheet.write.handler.SheetWriteHandler;
import org.apache.fesod.sheet.write.handler.context.SheetWriteHandlerContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模板下拉数据约束写入处理器
 * <p>
 * 拦截工作表创建后阶段（{@link #afterSheetCreate}），根据实体字段上的 {@link ExcelDropdown} 注解，
 * 自动向目标列注册 Apache POI 的 {@link DataValidation} 原生下拉列表：
 * <ul>
 *     <li>动态 {@link DropdownProvider} 优先从 Spring 容器获取受管 Bean，无容器或未注册时平滑回退无参构造反射；</li>
 *     <li>当选项总长度突破 200 字符安全阈值或选项含英文逗号时，自动创建/复用隐藏字典工作表并注册工作簿级
 *     {@link Name} 跨表公式引用，突破 Excel 255 字符单行列表限制。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class DropdownSheetWriteHandler implements SheetWriteHandler {

    private static final Logger log = LoggerFactory.getLogger(DropdownSheetWriteHandler.class);

    /**
     * 隐藏字典工作表名称
     */
    public static final String HIDDEN_DICT_SHEET_NAME = "_ateng_dropdown_dict";

    /**
     * 内联序列长度安全上限（低于 Excel 255 字符硬限制预留引号与分隔符余量）
     */
    private static final int INLINE_LIST_LENGTH_LIMIT = 200;

    private static final Map<Class<?>, List<DropdownMeta>> DROPDOWN_META_CACHE = new ConcurrentHashMap<>();

    private final Class<?> targetClass;

    public DropdownSheetWriteHandler(Class<?> targetClass) {
        this.targetClass = targetClass;
    }

    @Override
    public void afterSheetCreate(SheetWriteHandlerContext context) {
        if (targetClass == null || context == null || context.getWriteSheetHolder() == null) {
            return;
        }

        Sheet sheet = context.getWriteSheetHolder().getSheet();
        if (sheet == null) {
            return;
        }

        List<DropdownMeta> metas = getDropdownMetas(targetClass);
        if (metas.isEmpty()) {
            return;
        }

        Workbook workbook = (context.getWriteWorkbookHolder() != null && context.getWriteWorkbookHolder().getWorkbook() != null)
                ? context.getWriteWorkbookHolder().getWorkbook()
                : sheet.getWorkbook();
        DataValidationHelper helper = sheet.getDataValidationHelper();

        for (DropdownMeta meta : metas) {
            String[] options = meta.resolveOptions();
            if (options == null || options.length == 0) {
                continue;
            }

            CellRangeAddressList addressList = new CellRangeAddressList(
                    meta.firstRow(), meta.lastRow(), meta.columnIndex(), meta.columnIndex()
            );

            DataValidationConstraint constraint;
            if (workbook != null && requiresHiddenDictionarySheet(options)) {
                constraint = createHiddenDictionaryConstraint(workbook, sheet, helper, meta.columnIndex(), options);
            } else {
                constraint = helper.createExplicitListConstraint(options);
            }

            DataValidation validation = helper.createValidation(constraint, addressList);
            validation.setSuppressDropDownArrow(true);
            validation.setShowErrorBox(true);
            validation.createErrorBox("输入不合法", "请从下拉列表中选择有效选项");

            sheet.addValidationData(validation);
        }
    }

    private static boolean requiresHiddenDictionarySheet(String[] options) {
        int totalLength = 0;
        for (int i = 0; i < options.length; i++) {
            String opt = options[i] != null ? options[i] : "";
            if (opt.indexOf(',') >= 0) {
                return true;
            }
            totalLength += opt.length() + (i > 0 ? 1 : 0);
            if (totalLength > INLINE_LIST_LENGTH_LIMIT) {
                return true;
            }
        }
        return false;
    }

    private static DataValidationConstraint createHiddenDictionaryConstraint(Workbook workbook,
                                                                           Sheet targetSheet,
                                                                           DataValidationHelper helper,
                                                                           int columnIndex,
                                                                           String[] options) {
        Sheet dictSheet = workbook.getSheet(HIDDEN_DICT_SHEET_NAME);
        if (dictSheet == null) {
            dictSheet = workbook.createSheet(HIDDEN_DICT_SHEET_NAME);
        }
        int dictIndex = workbook.getSheetIndex(dictSheet);
        workbook.setSheetHidden(dictIndex, true);

        // 在字典表 Column A 单调追加非重叠行区，兼容 SXSSFWorkbook 流式窗口不回写已刷盘行
        int lastRowNum = dictSheet.getLastRowNum();
        int startRow = (lastRowNum < 0 || (dictSheet.getPhysicalNumberOfRows() == 0 && lastRowNum == 0)) ? 0 : lastRowNum + 1;

        for (int i = 0; i < options.length; i++) {
            Row row = dictSheet.createRow(startRow + i);
            Cell cell = row.createCell(0);
            cell.setCellValue(options[i] != null ? options[i] : "");
        }

        int firstExcelRow = startRow + 1;
        int lastExcelRow = startRow + options.length;
        String refersTo = "'" + HIDDEN_DICT_SHEET_NAME + "'!$A$" + firstExcelRow + ":$A$" + lastExcelRow;

        int sheetIdx = Math.max(0, workbook.getSheetIndex(targetSheet));
        String baseName = "_ateng_dd_s" + sheetIdx + "_c" + columnIndex + "_r" + firstExcelRow;
        String uniqueName = baseName;
        int suffix = 1;
        while (workbook.getName(uniqueName) != null) {
            uniqueName = baseName + "_" + (suffix++);
        }

        Name namedRange = workbook.createName();
        namedRange.setNameName(uniqueName);
        namedRange.setRefersToFormula(refersTo);

        return helper.createFormulaListConstraint(uniqueName);
    }

    private static List<DropdownMeta> getDropdownMetas(Class<?> clazz) {
        return DROPDOWN_META_CACHE.computeIfAbsent(clazz, c -> {
            List<ExcelColumnMetadataResolver.ColumnMetadata> columns = ExcelColumnMetadataResolver.resolve(c).columns();
            List<DropdownMeta> list = new ArrayList<>();
            for (ExcelColumnMetadataResolver.ColumnMetadata col : columns) {
                ExcelDropdown dropdown = col.field().getAnnotation(ExcelDropdown.class);
                if (dropdown != null) {
                    list.add(new DropdownMeta(
                            col.columnIndex(),
                            dropdown.firstRow(),
                            dropdown.lastRow(),
                            dropdown.value(),
                            dropdown.source()
                    ));
                }
            }
            return Collections.unmodifiableList(list);
        });
    }

    private record DropdownMeta(
            int columnIndex,
            int firstRow,
            int lastRow,
            String[] staticOptions,
            Class<? extends DropdownProvider> providerClass
    ) {
        public String[] resolveOptions() {
            if (staticOptions != null && staticOptions.length > 0) {
                return staticOptions;
            }
            if (providerClass != null && !DropdownProvider.Default.class.equals(providerClass)) {
                DropdownProvider provider = resolveProviderBeanOrNew(providerClass);
                if (provider != null) {
                    return provider.getOptions();
                }
            }
            return new String[0];
        }

        private static DropdownProvider resolveProviderBeanOrNew(Class<? extends DropdownProvider> providerClass) {
            if (SpringUtil.isInitialized()) {
                try {
                    DropdownProvider bean = SpringUtil.getBean(providerClass);
                    if (bean != null) {
                        return bean;
                    }
                } catch (Exception ex) {
                    log.debug("未在 Spring 容器中找到 DropdownProvider Bean [{}], 回退无参构造反射: {}",
                            providerClass.getName(), ex.getMessage());
                }
            }
            try {
                return providerClass.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                log.warn("无法实例化 DropdownProvider: {}, 原因: {}", providerClass.getName(), ex.getMessage());
                return null;
            }
        }
    }
}
