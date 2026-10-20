package io.github.atengk.boot.excel.listener;

import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.exception.ExcelValidationException;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Excel 行级数据校验与分批流式读取监听器
 * <p>
 * 基于 Jakarta Bean Validation (JSR-303/380) 规范逐行校验读取到的 Excel 数据实体，
 * 精确计算真实物理行号与列信息，集成前置表头契约校验 ({@link ExcelHeaderValidator})
 * 与错误行数熔断器 (Circuit Breaker)，支持错误聚合、快速失败与超限熔断。
 * </p>
 *
 * @param <T> 数据实体泛型
 * @author Ateng
 * @since 2026-10-16
 */
public class BatchValidationListener<T> extends AnalysisEventListener<T> {

    private static final Logger log = LoggerFactory.getLogger(BatchValidationListener.class);

    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int DEFAULT_MAX_ERROR_ROWS = 500;

    /**
     * 类属性元数据缓存 (Class -> Map<fieldName, PropertyMeta>)
     */
    private static final Map<Class<?>, Map<String, PropertyMeta>> CLASS_META_CACHE = new ConcurrentHashMap<>();

    /**
     * 默认校验器单例（惰性初始化）
     */
    private static volatile Validator defaultValidator;

    private final Validator validator;
    private final Class<T> targetClass;
    private final int batchSize;
    private final int maxErrorRows;
    private final boolean failFast;
    private final HeaderValidationStrategy headerStrategy;
    private final ExcelHeaderValidator<T> headerValidator;
    private final Consumer<List<T>> dataConsumer;
    private final Consumer<List<ExcelErrorRow>> errorConsumer;

    private final List<T> validDataList = new ArrayList<>();
    private final List<ExcelErrorRow> errorList = new ArrayList<>();
    private volatile boolean circuitBroken = false;

    /**
     * 构造默认错误聚合模式监听器（关闭表头校验，默认 500 行错误熔断）
     *
     * @param targetClass 读取映射的目标实体 Class（不可为空）
     */
    public BatchValidationListener(Class<T> targetClass) {
        this(targetClass, DEFAULT_BATCH_SIZE, false, DEFAULT_MAX_ERROR_ROWS, HeaderValidationStrategy.DISABLED, null, null, null);
    }

    /**
     * 构造支持指定 failFast 模式的监听器
     *
     * @param targetClass 读取映射的目标实体 Class（不可为空）
     * @param failFast    是否快速失败（true: 遇到第一个错误立即抛出异常中断；false: 聚合错误行）
     */
    public BatchValidationListener(Class<T> targetClass, boolean failFast) {
        this(targetClass, DEFAULT_BATCH_SIZE, failFast, DEFAULT_MAX_ERROR_ROWS, HeaderValidationStrategy.DISABLED, null, null, null);
    }

    /**
     * 构造指定表头校验策略的监听器
     *
     * @param targetClass    读取映射的目标实体 Class（不可为空）
     * @param headerStrategy 表头契约校验策略
     */
    public BatchValidationListener(Class<T> targetClass, HeaderValidationStrategy headerStrategy) {
        this(targetClass, DEFAULT_BATCH_SIZE, false, DEFAULT_MAX_ERROR_ROWS, headerStrategy, null, null, null);
    }

    /**
     * 构造指定表头校验策略与错误熔断阈值的监听器
     *
     * @param targetClass    读取映射的目标实体 Class（不可为空）
     * @param headerStrategy 表头契约校验策略
     * @param maxErrorRows   错误行数熔断阈值
     */
    public BatchValidationListener(Class<T> targetClass, HeaderValidationStrategy headerStrategy, int maxErrorRows) {
        this(targetClass, DEFAULT_BATCH_SIZE, false, maxErrorRows, headerStrategy, null, null, null);
    }

    /**
     * 构造支持注入 Validator 的监听器
     *
     * @param targetClass 读取映射的目标实体 Class（不可为空）
     * @param validator   自定义 Validator 校验器
     */
    public BatchValidationListener(Class<T> targetClass, Validator validator) {
        this(targetClass, DEFAULT_BATCH_SIZE, false, DEFAULT_MAX_ERROR_ROWS, HeaderValidationStrategy.DISABLED, null, null, validator);
    }

    /**
     * 构造支持注入 Validator 与指定 failFast 模式的监听器
     *
     * @param targetClass 读取映射的目标实体 Class（不可为空）
     * @param validator   自定义 Validator 校验器
     * @param failFast    是否快速失败
     */
    public BatchValidationListener(Class<T> targetClass, Validator validator, boolean failFast) {
        this(targetClass, DEFAULT_BATCH_SIZE, failFast, DEFAULT_MAX_ERROR_ROWS, HeaderValidationStrategy.DISABLED, null, null, validator);
    }

    /**
     * 构造支持注入 Validator、指定 failFast 模式与错误熔断阈值的监听器
     *
     * @param targetClass  读取映射的目标实体 Class（不可为空）
     * @param validator    自定义 Validator 校验器
     * @param failFast     是否快速失败
     * @param maxErrorRows 错误行数熔断阈值
     */
    public BatchValidationListener(Class<T> targetClass, Validator validator, boolean failFast, int maxErrorRows) {
        this(targetClass, DEFAULT_BATCH_SIZE, failFast, maxErrorRows, HeaderValidationStrategy.DISABLED, null, null, validator);
    }

    /**
     * 构造分批校验监听器
     *
     * @param targetClass     读取映射的目标实体 Class（不可为空）
     * @param batchSize       合法数据分批消费大小（大于 0）
     * @param failFast        是否快速失败（true: 首错即抛中断；false: 聚合错误）
     * @param dataConsumer    合法数据分批消费者（可为 null）
     * @param errorConsumer   聚合错误消费者（可为 null）
     * @param customValidator 自定义 Validator（为 null 时使用默认校验器单例）
     */
    public BatchValidationListener(Class<T> targetClass, int batchSize, boolean failFast,
                                   Consumer<List<T>> dataConsumer,
                                   Consumer<List<ExcelErrorRow>> errorConsumer,
                                   Validator customValidator) {
        this(targetClass, batchSize, failFast, DEFAULT_MAX_ERROR_ROWS, HeaderValidationStrategy.DISABLED, dataConsumer, errorConsumer, customValidator);
    }

    /**
     * 构造全功能分批校验监听器
     *
     * @param targetClass     读取映射的目标实体 Class（不可为空）
     * @param batchSize       合法数据分批消费大小（大于 0）
     * @param failFast        是否快速失败（true: 首错即抛中断；false: 聚合错误）
     * @param maxErrorRows    最大允许错误行数阈值（达到后触发熔断终止解析）
     * @param headerStrategy  表头契约校验策略（为 null 时默认 DISABLED）
     * @param dataConsumer    合法数据分批消费者（可为 null）
     * @param errorConsumer   聚合错误消费者（可为 null）
     * @param customValidator 自定义 Validator（为 null 时使用默认校验器单例）
     */
    public BatchValidationListener(Class<T> targetClass, int batchSize, boolean failFast,
                                   int maxErrorRows, HeaderValidationStrategy headerStrategy,
                                   Consumer<List<T>> dataConsumer,
                                   Consumer<List<ExcelErrorRow>> errorConsumer,
                                   Validator customValidator) {
        Assert.notNull(targetClass, "targetClass 读取目标实体类型不可为空");
        this.targetClass = targetClass;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.maxErrorRows = maxErrorRows > 0 ? maxErrorRows : DEFAULT_MAX_ERROR_ROWS;
        this.failFast = failFast;
        this.headerStrategy = headerStrategy != null ? headerStrategy : HeaderValidationStrategy.DISABLED;
        this.headerValidator = (this.headerStrategy != HeaderValidationStrategy.DISABLED)
                ? new ExcelHeaderValidator<>(targetClass, this.headerStrategy)
                : null;
        this.dataConsumer = dataConsumer;
        this.errorConsumer = errorConsumer;
        this.validator = customValidator != null ? customValidator : getOrCreateDefaultValidator();
    }

    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
        if (headerValidator != null) {
            headerValidator.invokeHeadMap(headMap, context);
        }
    }

    @Override
    public boolean hasNext(AnalysisContext context) {
        return !circuitBroken;
    }

    @Override
    public void invoke(T data, AnalysisContext context) {
        if (circuitBroken || data == null) {
            return;
        }

        // 1. 获取当前物理行号（从 1 开始，如表头为 1，首行数据为 2）
        int rowIndex = (context != null && context.readRowHolder() != null)
                ? context.readRowHolder().getRowIndex() + 1
                : 1;

        // 2. 执行 JSR-303 规则校验
        Set<ConstraintViolation<T>> violations = validator != null
                ? validator.validate(data)
                : Collections.emptySet();

        if (violations.isEmpty()) {
            // 校验通过：收集或分批消费合法数据
            validDataList.add(data);
            if (dataConsumer != null && validDataList.size() >= batchSize) {
                dataConsumer.accept(new ArrayList<>(validDataList));
                validDataList.clear();
            }
            return;
        }

        // 3. 校验失败：解析结构化错误行信息
        Map<String, PropertyMeta> metaMap = getPropertyMetaMap(targetClass);
        List<ExcelErrorRow> currentErrors = new ArrayList<>();

        for (ConstraintViolation<T> violation : violations) {
            String propertyName = violation.getPropertyPath().toString();
            PropertyMeta meta = metaMap.get(propertyName);

            ExcelErrorRow errorRow = ExcelErrorRow.builder()
                    .rowIndex(rowIndex)
                    .columnIndex(meta != null ? meta.columnIndex() : null)
                    .columnName(meta != null ? meta.columnName() : propertyName)
                    .fieldName(propertyName)
                    .invalidValue(violation.getInvalidValue())
                    .message(violation.getMessage())
                    .rowData(data)
                    .build();

            currentErrors.add(errorRow);
        }

        // 4. 模式分支判断
        if (failFast) {
            // 快速失败：立即中断读取并抛出异常
            throw new ExcelValidationException("Excel 第 " + rowIndex + " 行数据校验失败 (fail-fast)", currentErrors);
        }

        // 错误聚合模式：累计错误并探测熔断阈值
        errorList.addAll(currentErrors);
        if (errorList.size() >= maxErrorRows) {
            this.circuitBroken = true;
            log.warn("Excel 导入错误数达到或超出熔断阈值 [maxErrorRows={}]，触发紧急熔断阻断后续解析", maxErrorRows);
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // 1. 若存在表头校验器，做收尾契约防卫
        if (headerValidator != null) {
            headerValidator.doAfterAllAnalysed(context);
        }

        // 2. 消费剩余未触发批次的合法数据
        if (dataConsumer != null && !validDataList.isEmpty()) {
            dataConsumer.accept(new ArrayList<>(validDataList));
            validDataList.clear();
        }

        // 3. 消费聚合的错误信息
        if (errorConsumer != null && !errorList.isEmpty()) {
            errorConsumer.accept(Collections.unmodifiableList(errorList));
        }
    }

    /**
     * 获取解析成功的合法数据列表
     */
    public List<T> getValidDataList() {
        return Collections.unmodifiableList(validDataList);
    }

    /**
     * 获取解析失败的全部结构化错误行集合
     */
    public List<ExcelErrorRow> getErrorList() {
        return Collections.unmodifiableList(errorList);
    }

    /**
     * 是否存在校验错误
     *
     * @return true 若存在至少一条错误
     */
    public boolean hasErrors() {
        return !errorList.isEmpty();
    }

    /**
     * 是否触发了错误行数熔断
     *
     * @return true 若达到 maxErrorRows 阈值已熔断
     */
    public boolean isCircuitBroken() {
        return circuitBroken;
    }

    /**
     * 获取当前生效的错误行熔断阈值
     */
    public int getMaxErrorRows() {
        return maxErrorRows;
    }

    /**
     * 获取当前表头契约校验策略
     */
    public HeaderValidationStrategy getHeaderStrategy() {
        return headerStrategy;
    }

    /**
     * 将当前校验解析状态转换为只读的 ExcelReadResult 复合对象
     *
     * @return 结构化读取与校验结果
     */
    public ExcelReadResult<T> toResult() {
        return ExcelReadResult.<T>builder()
                .data(new ArrayList<>(validDataList))
                .errors(new ArrayList<>(errorList))
                .circuitBroken(circuitBroken)
                .build();
    }

    private static Validator getOrCreateDefaultValidator() {
        if (defaultValidator == null) {
            synchronized (BatchValidationListener.class) {
                if (defaultValidator == null) {
                    // 1. 优先尝试从 Spring 容器获取受管 Validator Bean（支持 Spring 依赖注入与自定义校验规则）
                    try {
                        Validator springValidator = SpringUtil.getBean(Validator.class);
                        if (springValidator != null) {
                            defaultValidator = springValidator;
                            return defaultValidator;
                        }
                    } catch (Throwable ignored) {
                        // Spring 上下文未就绪或未注册 Validator，降级到默认 JSR-303 工厂
                    }

                    // 2. 降级使用标准 JSR-303 ValidatorFactory
                    try {
                        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
                        defaultValidator = factory.getValidator();
                    } catch (Exception ex) {
                        try {
                            // 降级使用零 EL 依赖的 ParameterMessageInterpolator
                            Class<?> interpolatorClass = Class.forName("org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator");
                            Object interpolator = interpolatorClass.getDeclaredConstructor().newInstance();
                            ValidatorFactory factory = Validation.byDefaultProvider()
                                    .configure()
                                    .messageInterpolator((jakarta.validation.MessageInterpolator) interpolator)
                                    .buildValidatorFactory();
                            defaultValidator = factory.getValidator();
                        } catch (Throwable innerEx) {
                            log.warn("未能初始化默认 Validator，JSR-303 行级校验将降级跳过: {}", ex.getMessage());
                        }
                    }
                }
            }
        }
        return defaultValidator;
    }

    private static Map<String, PropertyMeta> getPropertyMetaMap(Class<?> clazz) {
        if (clazz == null) {
            return Collections.emptyMap();
        }
        return CLASS_META_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, PropertyMeta> map = new HashMap<>();
            List<Field> allFields = new ArrayList<>();
            Class<?> current = c;
            List<Class<?>> hierarchy = new ArrayList<>();
            while (current != null && current != Object.class) {
                hierarchy.add(0, current); // 顶层父类优先在前
                current = current.getSuperclass();
            }
            for (Class<?> clz : hierarchy) {
                for (Field field : clz.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        allFields.add(field);
                    }
                }
            }

            int defaultIndex = 0;
            for (Field field : allFields) {
                ExcelProperty excelProperty = field.getAnnotation(ExcelProperty.class);
                if (excelProperty != null) {
                    int colIndex = excelProperty.index() >= 0 ? excelProperty.index() : defaultIndex;
                    String[] headValues = excelProperty.value();
                    String colName = (headValues != null && headValues.length > 0)
                            ? headValues[headValues.length - 1]
                            : field.getName();
                    map.put(field.getName(), new PropertyMeta(colIndex, colName));
                } else {
                    map.put(field.getName(), new PropertyMeta(defaultIndex, field.getName()));
                }
                defaultIndex++;
            }
            return Collections.unmodifiableMap(map);
        });
    }

    private record PropertyMeta(int columnIndex, String columnName) {}
}
