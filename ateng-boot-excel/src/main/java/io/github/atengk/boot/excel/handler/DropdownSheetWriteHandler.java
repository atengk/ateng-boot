package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.annotation.DropdownProvider;
import io.github.atengk.boot.excel.annotation.ExcelDropdown;
import org.apache.fesod.sheet.annotation.ExcelIgnore;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.write.handler.SheetWriteHandler;
import org.apache.fesod.sheet.write.handler.context.SheetWriteHandlerContext;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模板下拉数据约束写入处理器
 * <p>
 * 拦截工作表创建后阶段（{@link #afterSheetCreate}），根据实体字段上的 {@link ExcelDropdown} 注解，
 * 自动向目标列注册 Apache POI 的 {@link DataValidation} 原生下拉列表。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class DropdownSheetWriteHandler implements SheetWriteHandler {

    private static final Logger log = LoggerFactory.getLogger(DropdownSheetWriteHandler.class);

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

        DataValidationHelper helper = sheet.getDataValidationHelper();

        for (DropdownMeta meta : metas) {
            String[] options = meta.resolveOptions();
            if (options == null || options.length == 0) {
                continue;
            }

            CellRangeAddressList addressList = new CellRangeAddressList(
                    meta.firstRow(), meta.lastRow(), meta.columnIndex(), meta.columnIndex()
            );

            DataValidationConstraint constraint = helper.createExplicitListConstraint(options);
            DataValidation validation = helper.createValidation(constraint, addressList);
            validation.setSuppressDropDownArrow(true);
            validation.setShowErrorBox(true);
            validation.createErrorBox("输入不合法", "请从下拉列表中选择有效选项");

            sheet.addValidationData(validation);
        }
    }

    private static List<DropdownMeta> getDropdownMetas(Class<?> clazz) {
        return DROPDOWN_META_CACHE.computeIfAbsent(clazz, c -> {
            List<DropdownMeta> list = new ArrayList<>();
            List<Class<?>> hierarchy = new ArrayList<>();
            Class<?> current = c;
            while (current != null && current != Object.class) {
                hierarchy.add(0, current); // 父类在前
                current = current.getSuperclass();
            }

            int defaultIndex = 0;
            for (Class<?> clz : hierarchy) {
                for (Field field : clz.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                        continue;
                    }
                    if (field.isAnnotationPresent(ExcelIgnore.class)) {
                        continue;
                    }

                    ExcelProperty excelProperty = field.getAnnotation(ExcelProperty.class);
                    int colIndex = (excelProperty != null && excelProperty.index() >= 0)
                            ? excelProperty.index()
                            : defaultIndex;

                    ExcelDropdown dropdown = field.getAnnotation(ExcelDropdown.class);
                    if (dropdown != null) {
                        list.add(new DropdownMeta(
                                colIndex,
                                dropdown.firstRow(),
                                dropdown.lastRow(),
                                dropdown.value(),
                                dropdown.source()
                        ));
                    }
                    defaultIndex++;
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
                try {
                    DropdownProvider provider = providerClass.getDeclaredConstructor().newInstance();
                    return provider.getOptions();
                } catch (Exception ex) {
                    log.warn("无法实例化 DropdownProvider: {}, 原因: {}", providerClass.getName(), ex.getMessage());
                }
            }
            return new String[0];
        }
    }
}
