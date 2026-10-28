package io.github.atengk.boot.excel.listener;

import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.exception.ExcelHeaderValidationException;
import org.apache.fesod.sheet.annotation.ExcelIgnore;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excel 导入表头契约前置校验监听拦截器
 * <p>
 * 在流式解析初始阶段比对表头列名与顺序，支持 {@link HeaderValidationStrategy#STRICT}（严格一致）
 * 与 {@link HeaderValidationStrategy#LOOSE}（必填列匹配）策略。
 * 首行表头失配即刻抛出 {@link ExcelHeaderValidationException} 触发毫秒级 Fail-Fast 阻断，杜绝无效脏数据穿透后续解析。
 * </p>
 *
 * @param <T> 数据实体泛型
 * @author Ateng
 * @since 2026-10-20
 */
public class ExcelHeaderValidator<T> extends AnalysisEventListener<T> {

    private static final Logger log = LoggerFactory.getLogger(ExcelHeaderValidator.class);

    private static final Map<Class<?>, ModelHeaderMeta> MODEL_HEADER_CACHE = new ConcurrentHashMap<>();

    private final Class<T> targetClass;
    private final HeaderValidationStrategy strategy;
    private final int expectedHeadRowCount;
    private final List<Map<Integer, String>> actualHeadRows = new ArrayList<>();
    private boolean validated = false;

    /**
     * 构造默认宽松校验（LOOSE）模式的表头拦截器
     *
     * @param targetClass 数据模型 Class（不可为空）
     */
    public ExcelHeaderValidator(Class<T> targetClass) {
        this(targetClass, HeaderValidationStrategy.LOOSE);
    }

    /**
     * 构造指定策略的表头拦截器
     *
     * @param targetClass 数据模型 Class（不可为空）
     * @param strategy    表头契约校验策略（不可为空）
     */
    public ExcelHeaderValidator(Class<T> targetClass, HeaderValidationStrategy strategy) {
        Assert.notNull(targetClass, "targetClass 数据模型类型不可为空");
        Assert.notNull(strategy, "HeaderValidationStrategy 表头校验策略不可为空");
        this.targetClass = targetClass;
        this.strategy = strategy;
        ModelHeaderMeta meta = getOrComputeModelHeader(targetClass);
        this.expectedHeadRowCount = meta.headRowCount();
    }

    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
        if (strategy == HeaderValidationStrategy.DISABLED || validated) {
            return;
        }

        Map<Integer, String> cleanMap = new LinkedHashMap<>();
        if (headMap != null) {
            headMap.forEach((k, v) -> cleanMap.put(k, (v != null) ? v.trim() : ""));
        }
        actualHeadRows.add(cleanMap);

        int currentSheetHeadRowCount = (context != null && context.readSheetHolder() != null && context.readSheetHolder().getHeadRowNumber() != null)
                ? context.readSheetHolder().getHeadRowNumber()
                : expectedHeadRowCount;

        if (actualHeadRows.size() >= currentSheetHeadRowCount) {
            validate(targetClass, actualHeadRows, strategy);
            validated = true;
        }
    }

    @Override
    public void invoke(T data, AnalysisContext context) {
        // 表头校验器不处理数据行
    }

    @Override
    public boolean hasNext(AnalysisContext context) {
        // 独立作为监听器使用时，表头校验通过后立即终止后续数据行流式扫描，秒级退出
        return !validated;
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        if (!validated && strategy != HeaderValidationStrategy.DISABLED) {
            validate(targetClass, actualHeadRows, strategy);
            validated = true;
        }
    }

    /**
     * 获取预期表头行数
     */
    public int getExpectedHeadRowCount() {
        return expectedHeadRowCount;
    }

    /**
     * 是否已完成表头校验
     */
    public boolean isValidated() {
        return validated;
    }

    /**
     * 获取当前生效的校验策略
     */
    public HeaderValidationStrategy getStrategy() {
        return strategy;
    }

    /**
     * 静态执行表头契约比对
     *
     * @param targetClass    实体 Class
     * @param actualHeadRows 实际读取到的各行表头 Map 列表
     * @param strategy       校验策略
     */
    public static void validate(Class<?> targetClass, List<Map<Integer, String>> actualHeadRows, HeaderValidationStrategy strategy) {
        if (strategy == null || strategy == HeaderValidationStrategy.DISABLED || targetClass == null) {
            return;
        }

        ModelHeaderMeta meta = getOrComputeModelHeader(targetClass);
        List<ModelHeaderColumn> expectedColumns = meta.columns();

        if (actualHeadRows == null || actualHeadRows.isEmpty()) {
            List<String> expectedNames = expectedColumns.stream().map(ModelHeaderColumn::leafName).toList();
            throw new ExcelHeaderValidationException(
                    "Excel 表头契约校验失败: 上传文件未包含有效表头行",
                    strategy,
                    expectedNames,
                    Collections.emptyList(),
                    expectedNames
            );
        }

        if (strategy == HeaderValidationStrategy.STRICT) {
            validateStrict(expectedColumns, actualHeadRows);
        } else if (strategy == HeaderValidationStrategy.LOOSE) {
            validateLoose(expectedColumns, actualHeadRows);
        }
    }

    private static void validateStrict(List<ModelHeaderColumn> expectedColumns, List<Map<Integer, String>> actualHeadRows) {
        List<String> expectedSummary = expectedColumns.stream().map(ModelHeaderColumn::leafName).toList();
        Map<Integer, String> lastActualRow = actualHeadRows.get(actualHeadRows.size() - 1);

        List<String> actualSummary = new ArrayList<>();
        int maxActualCol = lastActualRow.keySet().stream().max(Integer::compareTo).orElse(-1);
        for (int i = 0; i <= maxActualCol; i++) {
            actualSummary.add(lastActualRow.getOrDefault(i, ""));
        }

        for (int i = 0; i < expectedColumns.size(); i++) {
            ModelHeaderColumn expectedCol = expectedColumns.get(i);
            String[] expectedPath = expectedCol.path();

            for (int r = 0; r < expectedPath.length; r++) {
                Map<Integer, String> rowMap = (r < actualHeadRows.size()) ? actualHeadRows.get(r) : Collections.emptyMap();
                String actualVal = rowMap.getOrDefault(i, "").trim();
                String expectedVal = expectedPath[r].trim();

                if (!expectedVal.equals(actualVal)) {
                    throw new ExcelHeaderValidationException(
                            String.format("Excel 表头契约校验失败 (STRICT 模式)：第 %d 列表头不匹配。期望: [%s]，实际为: [%s]（预期表头: %s，实际表头: %s）",
                                    i + 1, expectedVal, actualVal, expectedSummary, actualSummary),
                            HeaderValidationStrategy.STRICT,
                            expectedSummary,
                            actualSummary,
                            Collections.singletonList(expectedVal)
                    );
                }
            }
        }

        // 校验是否存在多余冗余列（STRICT 模式严禁多列）
        List<String> redundantColumns = new ArrayList<>();
        for (int colIndex = expectedColumns.size(); colIndex <= maxActualCol; colIndex++) {
            for (Map<Integer, String> rowMap : actualHeadRows) {
                String val = rowMap.getOrDefault(colIndex, "").trim();
                if (!val.isEmpty()) {
                    redundantColumns.add(val);
                    break;
                }
            }
        }
        if (!redundantColumns.isEmpty()) {
            throw new ExcelHeaderValidationException(
                    String.format("Excel 表头契约校验失败 (STRICT 模式)：检测到多余的未知表头列 %s（预期总列数: %d，实际多出: %d 列。预期表头: %s，实际表头: %s）",
                            redundantColumns, expectedColumns.size(), redundantColumns.size(), expectedSummary, actualSummary),
                    HeaderValidationStrategy.STRICT,
                    expectedSummary,
                    actualSummary,
                    Collections.emptyList()
            );
        }
    }

    private static void validateLoose(List<ModelHeaderColumn> expectedColumns, List<Map<Integer, String>> actualHeadRows) {
        List<String> expectedLeafNames = expectedColumns.stream().map(ModelHeaderColumn::leafName).toList();

        Set<String> actualNamesSet = new HashSet<>();
        List<String> actualSummary = new ArrayList<>();

        for (Map<Integer, String> rowMap : actualHeadRows) {
            for (String val : rowMap.values()) {
                if (val != null && !val.isBlank()) {
                    actualNamesSet.add(val.trim());
                    if (!actualSummary.contains(val.trim())) {
                        actualSummary.add(val.trim());
                    }
                }
            }
        }

        List<String> missingHeaders = new ArrayList<>();
        for (String expectedLeaf : expectedLeafNames) {
            if (!actualNamesSet.contains(expectedLeaf)) {
                missingHeaders.add(expectedLeaf);
            }
        }

        if (!missingHeaders.isEmpty()) {
            throw new ExcelHeaderValidationException(
                    String.format("Excel 表头契约校验失败 (LOOSE 模式)：缺少必要列 %s。预期必填列: %s，实际表头包含: %s",
                            missingHeaders, expectedLeafNames, actualSummary),
                    HeaderValidationStrategy.LOOSE,
                    expectedLeafNames,
                    actualSummary,
                    missingHeaders
            );
        }
    }

    public static ModelHeaderMeta getOrComputeModelHeader(Class<?> clazz) {
        if (clazz == null) {
            return new ModelHeaderMeta(1, Collections.emptyList());
        }
        return MODEL_HEADER_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> allFields = new ArrayList<>();
            Class<?> current = c;
            List<Class<?>> hierarchy = new ArrayList<>();
            while (current != null && current != Object.class) {
                hierarchy.add(0, current); // 顶层父类优先在前
                current = current.getSuperclass();
            }
            for (Class<?> clz : hierarchy) {
                for (Field field : clz.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || Modifier.isTransient(field.getModifiers())
                            || field.isAnnotationPresent(ExcelIgnore.class)) {
                        continue;
                    }
                    allFields.add(field);
                }
            }

            List<ModelHeaderColumn> columns = new ArrayList<>();
            int defaultIndex = 0;
            int maxDepth = 1;

            for (Field field : allFields) {
                ExcelProperty excelProperty = field.getAnnotation(ExcelProperty.class);
                int colIndex = (excelProperty != null && excelProperty.index() >= 0)
                        ? excelProperty.index()
                        : defaultIndex;

                String[] values = (excelProperty != null && excelProperty.value() != null && excelProperty.value().length > 0)
                        ? excelProperty.value()
                        : new String[]{field.getName()};

                maxDepth = Math.max(maxDepth, values.length);
                String leafName = values[values.length - 1];

                columns.add(new ModelHeaderColumn(colIndex, field.getName(), values, leafName));
                defaultIndex++;
            }

            columns.sort(Comparator.comparingInt(ModelHeaderColumn::columnIndex));
            return new ModelHeaderMeta(maxDepth, Collections.unmodifiableList(columns));
        });
    }

    public record ModelHeaderColumn(int columnIndex, String fieldName, String[] path, String leafName) {}

    public record ModelHeaderMeta(int headRowCount, List<ModelHeaderColumn> columns) {}
}
