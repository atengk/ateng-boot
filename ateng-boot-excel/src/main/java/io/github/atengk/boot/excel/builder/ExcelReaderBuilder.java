package io.github.atengk.boot.excel.builder;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.listener.BatchValidationListener;
import io.github.atengk.boot.excel.listener.ExcelHeaderValidator;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelMultiSheetResult;
import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.ExcelReader;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.apache.fesod.sheet.read.builder.ExcelReaderSheetBuilder;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.apache.fesod.sheet.read.metadata.ReadSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Excel 异构多工作表流式读取构建器
 * <p>
 * 支持在单次 I/O 流生命周期内为同一个工作簿编排多个不同工作表（Sheet）的读取契约，
 * 支持按 0-based 索引寻址或表名称寻址、绑定异构强类型数据模型、分批流式消费回调与全量聚合双模读取，
 * 并支持 JSR-303 行级校验、表头契约校验与跨表错误聚合。
 * 实现了 {@link AutoCloseable} 与 {@link Closeable}，确保在读取完成后绝对确定性地释放底层流句柄与临时文件。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class ExcelReaderBuilder implements AutoCloseable, Closeable {

    private static final Logger log = LoggerFactory.getLogger(ExcelReaderBuilder.class);

    private final InputStream inputStream;
    private final List<SheetDefinition<?>> sheetDefinitions = new ArrayList<>();

    private int defaultBatchSize = 1000;
    private int maxRows = 100_000;
    private int maxTotalRows = 200_000;
    private int defaultHeadRowNumber = 1;
    private boolean autoCloseStream = true;
    private String password;
    private boolean defaultValidate = false;
    private boolean defaultFailFast = false;
    private HeaderValidationStrategy defaultHeaderStrategy = HeaderValidationStrategy.DISABLED;
    private int defaultMaxErrorRows = BatchValidationListener.DEFAULT_MAX_ERROR_ROWS;
    private boolean closed = false;
    private final List<AutoCloseable> cleanupResources = new ArrayList<>();

    /**
     * 基于输入流构造读取构建器
     *
     * @param inputStream 输入流（不可为空）
     */
    public ExcelReaderBuilder(InputStream inputStream) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        this.inputStream = inputStream;
    }

    /**
     * 基于输入流、默认分批大小与最大同步行数上限构造读取构建器
     *
     * @param inputStream      输入流（不可为空）
     * @param defaultBatchSize 默认分批大小
     * @param maxRows          单个 Sheet 最大同步读取行数限制（0 或负数表示无限制）
     */
    public ExcelReaderBuilder(InputStream inputStream, int defaultBatchSize, int maxRows) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        this.inputStream = inputStream;
        if (defaultBatchSize > 0) {
            this.defaultBatchSize = defaultBatchSize;
        }
        this.maxRows = maxRows;
    }

    /**
     * 配置流式分批读取时的默认批次大小
     *
     * @param batchSize 批次大小（必须大于 0）
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder defaultBatchSize(int batchSize) {
        Assert.isTrue(batchSize > 0, "batchSize 必须大于 0");
        this.defaultBatchSize = batchSize;
        return this;
    }

    /**
     * 配置单个 Sheet 聚合模式下的最大同步读取行数上限（熔断防 OOM）
     *
     * @param maxRows 最大允许行数（0 或负数表示不设上限）
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder maxRows(int maxRows) {
        this.maxRows = maxRows;
        return this;
    }

    /**
     * 配置全表累计聚合读取的最大总行数上限（熔断防 OOM）
     *
     * @param maxTotalRows 最大累计允许行数（0 或负数表示不设上限）
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder maxTotalRows(int maxTotalRows) {
        this.maxTotalRows = maxTotalRows;
        return this;
    }

    /**
     * 配置全局默认表头所占行数
     *
     * @param headRowNumber 表头行数（默认 1）
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder headRowNumber(int headRowNumber) {
        Assert.isTrue(headRowNumber >= 0, "headRowNumber 表头行数不可为负数");
        this.defaultHeadRowNumber = headRowNumber;
        return this;
    }

    /**
     * 配置读取完毕后是否自动闭合 InputStream 输入流
     *
     * @param autoCloseStream 是否自动闭合输入流（默认 true）
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder autoCloseStream(boolean autoCloseStream) {
        this.autoCloseStream = autoCloseStream;
        return this;
    }

    /**
     * 配置读取加密工作簿的访问密码
     *
     * @param password 工作簿密码
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder password(String password) {
        this.password = password;
        return this;
    }

    /**
     * 开启全工作簿默认 JSR-303 行级数据校验（默认按错误聚合模式收集跨表错误）
     *
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder validate() {
        this.defaultValidate = true;
        return this;
    }

    /**
     * 配置全工作簿默认是否开启 JSR-303 行级数据校验
     *
     * @param validate 是否开启校验（默认 false）
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder validate(boolean validate) {
        this.defaultValidate = validate;
        return this;
    }

    /**
     * 配置全工作簿默认校验模式是否为快速失败（fail-fast）
     *
     * @param failFast true 遇到首个错误行立即抛出 {@link io.github.atengk.boot.excel.exception.ExcelValidationException}；
     *                 false 聚合各 Sheet 校验错误不中断（默认 false）
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder failFast(boolean failFast) {
        this.defaultFailFast = failFast;
        return this;
    }

    /**
     * 配置全工作簿默认表头契约校验策略
     *
     * @param headerStrategy 表头校验策略（默认 {@link HeaderValidationStrategy#DISABLED}）
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder headerStrategy(HeaderValidationStrategy headerStrategy) {
        this.defaultHeaderStrategy = headerStrategy != null ? headerStrategy : HeaderValidationStrategy.DISABLED;
        return this;
    }

    /**
     * 配置开启行级校验时单个工作表允许聚合的最大错误行数阈值
     *
     * @param maxErrorRows 最大错误行数阈值（必须大于 0）
     * @return 当前构建器实例
     */
    public ExcelReaderBuilder maxErrorRows(int maxErrorRows) {
        Assert.isTrue(maxErrorRows > 0, "maxErrorRows 必须大于 0");
        this.defaultMaxErrorRows = maxErrorRows;
        return this;
    }

    // ==================== 聚合同步模式 Sheet 绑定 ====================

    /**
     * 按工作表索引（0-based）注册强类型实体模型（聚合返回模式）
     *
     * @param sheetNo 工作表索引（从 0 开始）
     * @param clazz   目标实体类型 Class（不可为空）
     * @param <T>     实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz) {
        return sheet(sheetNo, null, clazz, null, null, null, null, null, null, null);
    }

    /**
     * 按工作表名称注册强类型实体模型（聚合返回模式）
     *
     * @param sheetName 工作表名称（不可为空）
     * @param clazz     目标实体类型 Class（不可为空）
     * @param <T>       实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz) {
        return sheet(null, sheetName, clazz, null, null, null, null, null, null, null);
    }

    /**
     * 按工作表索引与名称共同注册强类型实体模型（聚合返回模式）
     *
     * @param sheetNo   工作表索引（从 0 开始）
     * @param sheetName 工作表名称
     * @param clazz     目标实体类型 Class（不可为空）
     * @param <T>       实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, String sheetName, Class<T> clazz) {
        return sheet(sheetNo, sheetName, clazz, null, null, null, null, null, null, null);
    }

    /**
     * 按工作表索引注册强类型实体模型，并指定表头行数（聚合返回模式）
     *
     * @param sheetNo       工作表索引（从 0 开始）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param headRowNumber 表头行数
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, int headRowNumber) {
        return sheet(sheetNo, null, clazz, headRowNumber, null, null, null, null, null, null);
    }

    /**
     * 按工作表名称注册强类型实体模型，并指定表头行数（聚合返回模式）
     *
     * @param sheetName     工作表名称（不可为空）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param headRowNumber 表头行数
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, int headRowNumber) {
        return sheet(null, sheetName, clazz, headRowNumber, null, null, null, null, null, null);
    }

    /**
     * 按工作表索引注册强类型实体模型，并单独指定是否启用行级数据校验
     *
     * @param sheetNo  工作表索引（从 0 开始）
     * @param clazz    目标实体类型 Class（不可为空）
     * @param validate 是否开启行级校验
     * @param <T>      实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, boolean validate) {
        return sheet(sheetNo, null, clazz, null, null, null, null, validate, null, null);
    }

    /**
     * 按工作表名称注册强类型实体模型，并单独指定是否启用行级数据校验
     *
     * @param sheetName 工作表名称（不可为空）
     * @param clazz     目标实体类型 Class（不可为空）
     * @param validate  是否开启行级校验
     * @param <T>       实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, boolean validate) {
        return sheet(null, sheetName, clazz, null, null, null, null, validate, null, null);
    }

    /**
     * 按工作表索引与名称注册强类型实体模型，并单独指定是否启用行级数据校验
     *
     * @param sheetNo   工作表索引（从 0 开始）
     * @param sheetName 工作表名称
     * @param clazz     目标实体类型 Class（不可为空）
     * @param validate  是否开启行级校验
     * @param <T>       实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, String sheetName, Class<T> clazz, boolean validate) {
        return sheet(sheetNo, sheetName, clazz, null, null, null, null, validate, null, null);
    }

    /**
     * 按工作表索引注册强类型实体模型，并单独配置行级校验与快速失败模式
     *
     * @param sheetNo  工作表索引（从 0 开始）
     * @param clazz    目标实体类型 Class（不可为空）
     * @param validate 是否开启行级校验
     * @param failFast 是否快速失败
     * @param <T>      实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, boolean validate, boolean failFast) {
        return sheet(sheetNo, null, clazz, null, null, null, null, validate, failFast, null);
    }

    /**
     * 按工作表名称注册强类型实体模型，并单独配置行级校验与快速失败模式
     *
     * @param sheetName 工作表名称（不可为空）
     * @param clazz     目标实体类型 Class（不可为空）
     * @param validate  是否开启行级校验
     * @param failFast  是否快速失败
     * @param <T>       实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, boolean validate, boolean failFast) {
        return sheet(null, sheetName, clazz, null, null, null, null, validate, failFast, null);
    }

    /**
     * 按工作表索引注册强类型实体模型，并单独配置表头契约校验策略
     *
     * @param sheetNo        工作表索引（从 0 开始）
     * @param clazz          目标实体类型 Class（不可为空）
     * @param headerStrategy 表头契约校验策略
     * @param <T>            实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, HeaderValidationStrategy headerStrategy) {
        return sheet(sheetNo, null, clazz, null, null, null, null, null, null, headerStrategy);
    }

    /**
     * 按工作表名称注册强类型实体模型，并单独配置表头契约校验策略
     *
     * @param sheetName      工作表名称（不可为空）
     * @param clazz          目标实体类型 Class（不可为空）
     * @param headerStrategy 表头契约校验策略
     * @param <T>            实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, HeaderValidationStrategy headerStrategy) {
        return sheet(null, sheetName, clazz, null, null, null, null, null, null, headerStrategy);
    }

    /**
     * 按工作表索引注册强类型实体模型，并完整覆盖该表的行级校验、快速失败与表头校验策略
     *
     * @param sheetNo        工作表索引（从 0 开始）
     * @param clazz          目标实体类型 Class（不可为空）
     * @param validate       是否开启行级校验
     * @param failFast       是否快速失败
     * @param headerStrategy 表头契约校验策略
     * @param <T>            实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, boolean validate, boolean failFast, HeaderValidationStrategy headerStrategy) {
        return sheet(sheetNo, null, clazz, null, null, null, null, validate, failFast, headerStrategy);
    }

    /**
     * 按工作表名称注册强类型实体模型，并完整覆盖该表的行级校验、快速失败与表头校验策略
     *
     * @param sheetName      工作表名称（不可为空）
     * @param clazz          目标实体类型 Class（不可为空）
     * @param validate       是否开启行级校验
     * @param failFast       是否快速失败
     * @param headerStrategy 表头契约校验策略
     * @param <T>            实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, boolean validate, boolean failFast, HeaderValidationStrategy headerStrategy) {
        return sheet(null, sheetName, clazz, null, null, null, null, validate, failFast, headerStrategy);
    }

    // ==================== 流式分批消费模式 Sheet 绑定 ====================

    /**
     * 按工作表索引注册强类型实体模型与分批回调消费者（防 OOM 流式消费模式）
     *
     * @param sheetNo       工作表索引（从 0 开始）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchSize     合法数据分批消费批次大小（大于 0）
     * @param batchConsumer 分批回调消费者
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, int batchSize, Consumer<List<T>> batchConsumer) {
        return sheet(sheetNo, null, clazz, null, batchSize, batchConsumer, null, null, null, null);
    }

    /**
     * 按工作表名称注册强类型实体模型与分批回调消费者（防 OOM 流式消费模式）
     *
     * @param sheetName     工作表名称（不可为空）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchSize     合法数据分批消费批次大小（大于 0）
     * @param batchConsumer 分批回调消费者
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, int batchSize, Consumer<List<T>> batchConsumer) {
        return sheet(null, sheetName, clazz, null, batchSize, batchConsumer, null, null, null, null);
    }

    /**
     * 按工作表索引与名称注册强类型实体模型与分批回调消费者（防 OOM 流式消费模式）
     *
     * @param sheetNo       工作表索引（从 0 开始）
     * @param sheetName     工作表名称
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchSize     合法数据分批消费批次大小（大于 0）
     * @param batchConsumer 分批回调消费者
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, String sheetName, Class<T> clazz, int batchSize, Consumer<List<T>> batchConsumer) {
        return sheet(sheetNo, sheetName, clazz, null, batchSize, batchConsumer, null, null, null, null);
    }

    /**
     * 按工作表索引注册分批回调消费者（使用全局默认批次大小）
     *
     * @param sheetNo       工作表索引（从 0 开始）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchConsumer 分批回调消费者
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, Consumer<List<T>> batchConsumer) {
        return sheet(sheetNo, null, clazz, null, null, batchConsumer, null, null, null, null);
    }

    /**
     * 按工作表名称注册分批回调消费者（使用全局默认批次大小）
     *
     * @param sheetName     工作表名称（不可为空）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchConsumer 分批回调消费者
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, Consumer<List<T>> batchConsumer) {
        return sheet(null, sheetName, clazz, null, null, batchConsumer, null, null, null, null);
    }

    /**
     * 按工作表索引注册分批回调消费者，并指定是否开启行级数据校验
     *
     * @param sheetNo       工作表索引（从 0 开始）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchConsumer 合法数据分批回调消费者
     * @param validate      是否开启行级校验
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, Consumer<List<T>> batchConsumer, boolean validate) {
        return sheet(sheetNo, null, clazz, null, null, batchConsumer, null, validate, null, null);
    }

    /**
     * 按工作表名称注册分批回调消费者，并指定是否开启行级数据校验
     *
     * @param sheetName     工作表名称（不可为空）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchConsumer 合法数据分批回调消费者
     * @param validate      是否开启行级校验
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, Consumer<List<T>> batchConsumer, boolean validate) {
        return sheet(null, sheetName, clazz, null, null, batchConsumer, null, validate, null, null);
    }

    /**
     * 按工作表索引注册强类型实体模型、批次大小与分批回调消费者，并指定是否开启行级数据校验
     *
     * @param sheetNo       工作表索引（从 0 开始）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchSize     合法数据分批消费批次大小（大于 0）
     * @param batchConsumer 合法数据分批回调消费者
     * @param validate      是否开启行级校验
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, int batchSize, Consumer<List<T>> batchConsumer, boolean validate) {
        return sheet(sheetNo, null, clazz, null, batchSize, batchConsumer, null, validate, null, null);
    }

    /**
     * 按工作表名称注册强类型实体模型、批次大小与分批回调消费者，并指定是否开启行级数据校验
     *
     * @param sheetName     工作表名称（不可为空）
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchSize     合法数据分批消费批次大小（大于 0）
     * @param batchConsumer 合法数据分批回调消费者
     * @param validate      是否开启行级校验
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, int batchSize, Consumer<List<T>> batchConsumer, boolean validate) {
        return sheet(null, sheetName, clazz, null, batchSize, batchConsumer, null, validate, null, null);
    }

    /**
     * 按工作表索引与名称注册强类型实体模型、批次大小与分批回调消费者，并指定是否开启行级数据校验
     *
     * @param sheetNo       工作表索引（从 0 开始）
     * @param sheetName     工作表名称
     * @param clazz         目标实体类型 Class（不可为空）
     * @param batchSize     合法数据分批消费批次大小（大于 0）
     * @param batchConsumer 合法数据分批回调消费者
     * @param validate      是否开启行级校验
     * @param <T>           实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, String sheetName, Class<T> clazz, int batchSize, Consumer<List<T>> batchConsumer, boolean validate) {
        return sheet(sheetNo, sheetName, clazz, null, batchSize, batchConsumer, null, validate, null, null);
    }

    /**
     * 按工作表索引注册强类型实体模型与流式分批回调消费者，并完整覆盖校验、快速失败与表头校验策略
     *
     * @param sheetNo        工作表索引（从 0 开始）
     * @param clazz          目标实体类型 Class（不可为空）
     * @param batchSize      合法数据分批消费批次大小（大于 0）
     * @param batchConsumer  合法数据分批回调消费者
     * @param validate       是否开启行级校验
     * @param failFast       是否快速失败
     * @param headerStrategy 表头契约校验策略
     * @param <T>            实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo,
                                        Class<T> clazz,
                                        int batchSize,
                                        Consumer<List<T>> batchConsumer,
                                        boolean validate,
                                        boolean failFast,
                                        HeaderValidationStrategy headerStrategy) {
        return sheet(sheetNo, null, clazz, null, batchSize, batchConsumer, null, validate, failFast, headerStrategy);
    }

    /**
     * 按工作表名称注册强类型实体模型与流式分批回调消费者，并完整覆盖校验、快速失败与表头校验策略
     *
     * @param sheetName      工作表名称（不可为空）
     * @param clazz          目标实体类型 Class（不可为空）
     * @param batchSize      合法数据分批消费批次大小（大于 0）
     * @param batchConsumer  合法数据分批回调消费者
     * @param validate       是否开启行级校验
     * @param failFast       是否快速失败
     * @param headerStrategy 表头契约校验策略
     * @param <T>            实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName,
                                        Class<T> clazz,
                                        int batchSize,
                                        Consumer<List<T>> batchConsumer,
                                        boolean validate,
                                        boolean failFast,
                                        HeaderValidationStrategy headerStrategy) {
        return sheet(null, sheetName, clazz, null, batchSize, batchConsumer, null, validate, failFast, headerStrategy);
    }

    // ==================== 自定义监听器模式 Sheet 绑定 ====================

    /**
     * 按工作表索引注册自定义 ReadListener
     *
     * @param sheetNo        工作表索引（从 0 开始）
     * @param clazz          目标实体类型 Class（不可为空）
     * @param customListener 自定义读取监听器
     * @param <T>            实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(int sheetNo, Class<T> clazz, ReadListener<T> customListener) {
        return sheet(sheetNo, null, clazz, null, null, null, customListener, null, null, null);
    }

    /**
     * 按工作表名称注册自定义 ReadListener
     *
     * @param sheetName      工作表名称（不可为空）
     * @param clazz          目标实体类型 Class（不可为空）
     * @param customListener 自定义读取监听器
     * @param <T>            实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelReaderBuilder sheet(String sheetName, Class<T> clazz, ReadListener<T> customListener) {
        return sheet(null, sheetName, clazz, null, null, null, customListener, null, null, null);
    }

    /**
     * 核心统一 Sheet 注册方法
     */
    private <T> ExcelReaderBuilder sheet(Integer sheetNo,
                                         String sheetName,
                                         Class<T> clazz,
                                         Integer headRowNumber,
                                         Integer batchSize,
                                         Consumer<List<T>> batchConsumer,
                                         ReadListener<T> customListener,
                                         Boolean validate,
                                         Boolean failFast,
                                         HeaderValidationStrategy headerStrategy) {
        Assert.isTrue(sheetNo != null || (sheetName != null && !sheetName.isBlank()), "工作表索引 sheetNo 与工作表名称 sheetName 不可同时为空");
        if (sheetNo != null) {
            Assert.isTrue(sheetNo >= 0, "工作表索引 sheetNo 必须大于等于 0");
        }
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");
        if (batchSize != null) {
            Assert.isTrue(batchSize > 0, "batchSize 必须大于 0");
        }
        sheetDefinitions.add(new SheetDefinition<>(sheetNo, sheetName, clazz, headRowNumber, batchSize,
                batchConsumer, customListener, validate, failFast, headerStrategy));
        return this;
    }

    // ==================== 执行读取与资源清理 ====================

    /**
     * 触发多工作表读取任务，并在执行完毕后自动闭合输入流与底层 Fesod 资源
     *
     * @return 多工作表聚合读取结果容器
     */
    public ExcelMultiSheetResult read() {
        try {
            return doRead();
        } finally {
            close();
        }
    }

    /**
     * 同步读取别名，内部调用 {@link #read()}
     *
     * @return 多工作表聚合读取结果容器
     */
    public ExcelMultiSheetResult readSync() {
        return read();
    }

    /**
     * 执行底层多工作表读取编排
     *
     * @return 多工作表聚合读取结果容器
     */
    public ExcelMultiSheetResult doRead() {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.state(!sheetDefinitions.isEmpty(), "至少需要配置一个目标 Sheet 的读取规则");
        Assert.state(!closed, "当前 ExcelReaderBuilder 已关闭，不可重复执行读取操作");

        ExcelMultiSheetResult result = new ExcelMultiSheetResult();
        AtomicInteger totalRowsCounter = new AtomicInteger(0);
        List<ReadSheet> readSheets = new ArrayList<>(sheetDefinitions.size());
        List<Runnable> postReadFlushers = new ArrayList<>();

        for (SheetDefinition<?> def : sheetDefinitions) {
            readSheets.add(buildFesodReadSheet(def, result, totalRowsCounter, postReadFlushers));
        }

        InputStream effectiveStream = ExcelSecurityUtil.decryptIfEncrypted(this.inputStream, this.password, cleanupResources::add);
        var fesodReaderBuilder = FesodSheet.read(effectiveStream);
        if (this.password != null && !this.password.isBlank()) {
            fesodReaderBuilder.password(this.password);
        }
        fesodReaderBuilder.autoCloseStream(false);
        ExcelReader fesodReader = fesodReaderBuilder.build();

        try {
            fesodReader.read(readSheets);
            // 兜底刷新：当某 Sheet 触发 maxErrorRows 熔断导致 Fesod 提前停止且跳过 doAfterAllAnalysed 时，仍确保数据、错误与熔断态落盘
            for (Runnable flusher : postReadFlushers) {
                flusher.run();
            }
        } catch (Exception e) {
            BusinessException unwrapped = unwrapBusinessException(e);
            if (unwrapped != null) {
                throw unwrapped;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Excel 多工作表流式解析失败: " + e.getMessage(), e);
        } finally {
            try {
                fesodReader.finish();
            } finally {
                releaseTempCleanupResources();
            }
        }

        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ReadSheet buildFesodReadSheet(SheetDefinition<?> def,
                                          ExcelMultiSheetResult result,
                                          AtomicInteger totalRowsCounter,
                                          List<Runnable> postReadFlushers) {
        ExcelReaderSheetBuilder sheetBuilder;
        if (def.sheetNo != null && def.sheetName != null) {
            sheetBuilder = FesodSheet.readSheet(def.sheetNo, def.sheetName);
        } else if (def.sheetNo != null) {
            sheetBuilder = FesodSheet.readSheet(def.sheetNo);
        } else {
            sheetBuilder = FesodSheet.readSheet(def.sheetName);
        }

        sheetBuilder.head(def.clazz);
        int headRows = (def.headRowNumber != null) ? def.headRowNumber : this.defaultHeadRowNumber;
        sheetBuilder.headRowNumber(headRows);

        // 注入枚举转换器与公式注入保护转换器
        ExcelUtil.registerEnumConverters(sheetBuilder, def.clazz);

        boolean effectiveValidate = def.validate != null ? def.validate : this.defaultValidate;
        boolean effectiveFailFast = def.failFast != null ? def.failFast : this.defaultFailFast;
        HeaderValidationStrategy effectiveHeaderStrategy = def.headerStrategy != null
                ? def.headerStrategy
                : this.defaultHeaderStrategy;

        // 注册监听器
        if (def.customListener != null) {
            if (effectiveHeaderStrategy != HeaderValidationStrategy.DISABLED) {
                sheetBuilder.registerReadListener(new ExcelHeaderValidator(def.clazz, effectiveHeaderStrategy, false));
            }
            sheetBuilder.registerReadListener(def.customListener);
        } else if (effectiveValidate) {
            int batch = (def.batchSize != null && def.batchSize > 0) ? def.batchSize : this.defaultBatchSize;
            AbstractValidatedListener validatedListener;
            if (def.batchConsumer != null) {
                validatedListener = new ValidatedStreamingBatchListener(
                        def, result, batch, effectiveFailFast, this.defaultMaxErrorRows, effectiveHeaderStrategy, def.batchConsumer);
            } else {
                validatedListener = new ValidatedAggregatedListener(
                        def, result, batch, effectiveFailFast, this.defaultMaxErrorRows, effectiveHeaderStrategy,
                        this.maxRows, this.maxTotalRows, totalRowsCounter);
            }
            sheetBuilder.registerReadListener(validatedListener);
            postReadFlushers.add(validatedListener::flushIfNeeded);
        } else {
            if (effectiveHeaderStrategy != HeaderValidationStrategy.DISABLED) {
                sheetBuilder.registerReadListener(new ExcelHeaderValidator(def.clazz, effectiveHeaderStrategy, false));
            }
            if (def.batchConsumer != null) {
                int batch = (def.batchSize != null && def.batchSize > 0) ? def.batchSize : this.defaultBatchSize;
                sheetBuilder.registerReadListener(new StreamingBatchListener(def, result, batch, def.batchConsumer));
            } else {
                sheetBuilder.registerReadListener(new AggregatedListener(def, result, this.maxRows, this.maxTotalRows, totalRowsCounter));
            }
        }

        return sheetBuilder.build();
    }

    private static BusinessException unwrapBusinessException(Throwable throwable) {
        Throwable cursor = throwable;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof BusinessException be) {
                return be;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private void releaseTempCleanupResources() {
        for (AutoCloseable res : cleanupResources) {
            try {
                res.close();
            } catch (Exception ex) {
                log.debug("释放 ExcelReaderBuilder 临时解密资源失败: {}", ex.getMessage());
            }
        }
        cleanupResources.clear();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            releaseTempCleanupResources();
            if (autoCloseStream && inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ex) {
                    log.debug("关闭 ExcelReaderBuilder 输入流失败: {}", ex.getMessage());
                }
            }
        }
    }

    // ==================== 内部组件定义 ====================

    private record SheetDefinition<T>(
            Integer sheetNo,
            String sheetName,
            Class<T> clazz,
            Integer headRowNumber,
            Integer batchSize,
            Consumer<List<T>> batchConsumer,
            ReadListener<T> customListener,
            Boolean validate,
            Boolean failFast,
            HeaderValidationStrategy headerStrategy
    ) {}

    private record SheetKeys(Set<Integer> sheetNos, Set<String> sheetNames) {}

    private static SheetKeys resolveSheetKeys(SheetDefinition<?> definition, AnalysisContext context) {
        Integer actualNo = (context != null && context.readSheetHolder() != null)
                ? context.readSheetHolder().getSheetNo()
                : definition.sheetNo;
        String actualName = (context != null && context.readSheetHolder() != null)
                ? context.readSheetHolder().getSheetName()
                : definition.sheetName;

        Set<Integer> sheetNos = new LinkedHashSet<>();
        if (actualNo != null) {
            sheetNos.add(actualNo);
        }
        if (definition.sheetNo != null) {
            sheetNos.add(definition.sheetNo);
        }

        Set<String> sheetNames = new LinkedHashSet<>();
        if (actualName != null && !actualName.isBlank()) {
            sheetNames.add(actualName);
        }
        if (definition.sheetName != null && !definition.sheetName.isBlank()) {
            sheetNames.add(definition.sheetName);
        }
        return new SheetKeys(sheetNos, sheetNames);
    }

    private static void enforceRowLimits(SheetDefinition<?> definition,
                                         AnalysisContext context,
                                         int currentSheetRows,
                                         int maxRows,
                                         int maxTotalRows,
                                         AtomicInteger totalRowsCounter) {
        if (maxRows > 0 && currentSheetRows >= maxRows) {
            String desc = resolveSheetDescription(definition, context);
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "Excel " + desc + " 数据行数超出同步读取安全上限（" + maxRows + " 行），请改用流式分批回调消费以防内存溢出");
        }
        if (maxTotalRows > 0 && totalRowsCounter.incrementAndGet() > maxTotalRows) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "Excel 多工作表累计数据行数超出同步读取安全上限（" + maxTotalRows + " 行），当前已解析 " + totalRowsCounter.get() + " 行，请使用分批流式消费回调以防内存溢出");
        }
    }

    private static String resolveSheetDescription(SheetDefinition<?> definition, AnalysisContext context) {
        if (definition.sheetName != null) {
            return "工作表 [" + definition.sheetName + "]";
        }
        if (context != null && context.readSheetHolder() != null && context.readSheetHolder().getSheetName() != null) {
            return "工作表 [" + context.readSheetHolder().getSheetName() + "]";
        }
        int no = (definition.sheetNo != null)
                ? definition.sheetNo
                : (context != null && context.readSheetHolder() != null ? context.readSheetHolder().getSheetNo() : 0);
        return "工作表 (序号: " + no + ")";
    }

    private static class AggregatedListener<T> implements ReadListener<T> {
        private final SheetDefinition<T> definition;
        private final ExcelMultiSheetResult result;
        private final int maxRows;
        private final int maxTotalRows;
        private final AtomicInteger totalRowsCounter;
        private final List<T> dataList = new ArrayList<>();
        private int currentSheetRows = 0;

        AggregatedListener(SheetDefinition<T> definition,
                           ExcelMultiSheetResult result,
                           int maxRows,
                           int maxTotalRows,
                           AtomicInteger totalRowsCounter) {
            this.definition = definition;
            this.result = result;
            this.maxRows = maxRows;
            this.maxTotalRows = maxTotalRows;
            this.totalRowsCounter = totalRowsCounter;
        }

        @Override
        public void invoke(T data, AnalysisContext context) {
            if (data == null) {
                return;
            }
            enforceRowLimits(definition, context, currentSheetRows, maxRows, maxTotalRows, totalRowsCounter);
            dataList.add(data);
            currentSheetRows++;
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            SheetKeys keys = resolveSheetKeys(definition, context);
            result.recordSheetData(this.dataList, keys.sheetNos(), keys.sheetNames());
        }
    }

    private static class StreamingBatchListener<T> implements ReadListener<T> {
        private final SheetDefinition<T> definition;
        private final ExcelMultiSheetResult result;
        private final int batchSize;
        private final Consumer<List<T>> batchConsumer;
        private final List<T> buffer;
        private int rowCount = 0;

        StreamingBatchListener(SheetDefinition<T> definition,
                               ExcelMultiSheetResult result,
                               int batchSize,
                               Consumer<List<T>> batchConsumer) {
            this.definition = definition;
            this.result = result;
            this.batchSize = batchSize;
            this.batchConsumer = batchConsumer;
            this.buffer = new ArrayList<>(batchSize);
        }

        @Override
        public void invoke(T data, AnalysisContext context) {
            if (data == null) {
                return;
            }
            buffer.add(data);
            rowCount++;
            if (buffer.size() >= batchSize) {
                batchConsumer.accept(new ArrayList<>(buffer));
                buffer.clear(); // 关键：清空缓冲区，交由 GC 回收，保障常数级内存占用
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            if (!buffer.isEmpty()) {
                batchConsumer.accept(new ArrayList<>(buffer));
                buffer.clear();
            }
            SheetKeys keys = resolveSheetKeys(definition, context);
            result.recordStreamedRowCount(this.rowCount, keys.sheetNos(), keys.sheetNames());
        }
    }

    private abstract static class AbstractValidatedListener<T> extends AnalysisEventListener<T> {
        protected final SheetDefinition<T> definition;
        protected final ExcelMultiSheetResult result;
        protected final BatchValidationListener<T> delegate;
        private AnalysisContext lastContext;
        private boolean flushedToResult = false;

        AbstractValidatedListener(SheetDefinition<T> definition,
                                  ExcelMultiSheetResult result,
                                  BatchValidationListener<T> delegate) {
            this.definition = definition;
            this.result = result;
            this.delegate = delegate;
        }

        @Override
        public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
            this.lastContext = context;
            delegate.invokeHeadMap(headMap, context);
        }

        @Override
        public boolean hasNext(AnalysisContext context) {
            return delegate.hasNext(context);
        }

        @Override
        public final void invoke(T data, AnalysisContext context) {
            this.lastContext = context;
            handleRow(data, context);
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            this.lastContext = context;
            flushToResult(context);
        }

        final void flushIfNeeded() {
            flushToResult(lastContext);
        }

        private void flushToResult(AnalysisContext context) {
            if (flushedToResult) {
                return;
            }
            flushedToResult = true;
            delegate.doAfterAllAnalysed(context);
            SheetKeys keys = resolveSheetKeys(definition, context);
            recordValidatedResult(keys);
            result.recordSheetErrors(delegate.getErrorList(), keys.sheetNos(), keys.sheetNames());
            result.markCircuitBroken(delegate.isCircuitBroken());
        }

        protected abstract void handleRow(T data, AnalysisContext context);

        protected abstract void recordValidatedResult(SheetKeys keys);
    }

    private static class ValidatedAggregatedListener<T> extends AbstractValidatedListener<T> {
        private final int maxRows;
        private final int maxTotalRows;
        private final AtomicInteger totalRowsCounter;
        private int currentSheetRows = 0;

        ValidatedAggregatedListener(SheetDefinition<T> definition,
                                    ExcelMultiSheetResult result,
                                    int batchSize,
                                    boolean failFast,
                                    int maxErrorRows,
                                    HeaderValidationStrategy headerStrategy,
                                    int maxRows,
                                    int maxTotalRows,
                                    AtomicInteger totalRowsCounter) {
            super(definition, result, new BatchValidationListener<>(
                    definition.clazz(), batchSize, failFast, maxErrorRows, headerStrategy, null, null, null));
            this.maxRows = maxRows;
            this.maxTotalRows = maxTotalRows;
            this.totalRowsCounter = totalRowsCounter;
        }

        @Override
        protected void handleRow(T data, AnalysisContext context) {
            if (data == null || !delegate.hasNext(context)) {
                return;
            }
            enforceRowLimits(definition, context, currentSheetRows, maxRows, maxTotalRows, totalRowsCounter);
            currentSheetRows++;
            delegate.invoke(data, context);
        }

        @Override
        protected void recordValidatedResult(SheetKeys keys) {
            result.recordSheetData(delegate.getValidDataList(), keys.sheetNos(), keys.sheetNames());
        }
    }

    private static class ValidatedStreamingBatchListener<T> extends AbstractValidatedListener<T> {
        private final AtomicInteger streamedValidRows;

        ValidatedStreamingBatchListener(SheetDefinition<T> definition,
                                        ExcelMultiSheetResult result,
                                        int batchSize,
                                        boolean failFast,
                                        int maxErrorRows,
                                        HeaderValidationStrategy headerStrategy,
                                        Consumer<List<T>> batchConsumer) {
            this(definition, result, batchSize, failFast, maxErrorRows, headerStrategy, batchConsumer, new AtomicInteger(0));
        }

        private ValidatedStreamingBatchListener(SheetDefinition<T> definition,
                                                ExcelMultiSheetResult result,
                                                int batchSize,
                                                boolean failFast,
                                                int maxErrorRows,
                                                HeaderValidationStrategy headerStrategy,
                                                Consumer<List<T>> batchConsumer,
                                                AtomicInteger streamedValidRows) {
            super(definition, result, new BatchValidationListener<>(
                    definition.clazz(),
                    batchSize,
                    failFast,
                    maxErrorRows,
                    headerStrategy,
                    batch -> {
                        if (batch != null && !batch.isEmpty()) {
                            streamedValidRows.addAndGet(batch.size());
                            batchConsumer.accept(batch);
                        }
                    },
                    null,
                    null));
            this.streamedValidRows = streamedValidRows;
        }

        @Override
        protected void handleRow(T data, AnalysisContext context) {
            delegate.invoke(data, context);
        }

        @Override
        protected void recordValidatedResult(SheetKeys keys) {
            result.recordStreamedRowCount(streamedValidRows.get(), keys.sheetNos(), keys.sheetNames());
        }
    }
}
