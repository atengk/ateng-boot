package io.github.atengk.boot.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.slf4j.MDC;

import java.io.Serializable;

/**
 * 统一 API 响应结果实体
 *
 * @param <T> 响应数据类型
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * MDC 中默认链路追踪标识键名
     */
    private static final String MDC_TRACE_ID_KEY = "traceId";

    /**
     * 业务状态码
     */
    private Integer code;

    /**
     * 提示文案
     */
    private String msg;

    /**
     * 业务载荷数据
     */
    private T data;

    /**
     * 响应时间戳（毫秒）
     */
    private long timestamp;

    /**
     * 业务成功标识
     */
    @Setter(AccessLevel.NONE)
    @JsonProperty("success")
    private boolean success;

    /**
     * 全链路追踪唯一标识
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String traceId;

    /**
     * 无参默认构造方法（用于 JSON 反序列化，不预取本地 MDC 以免反序列化污染）
     */
    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 基于基础要素构造
     *
     * @param code 业务状态码
     * @param msg  提示信息
     * @param data 载荷数据
     */
    public Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
        this.success = isCodeSuccess(code);
        this.traceId = obtainMdcTraceId();
    }

    /**
     * 判定当前响应状态码是否表征业务成功
     *
     * @return 若状态码为 200 则返回 true，否则返回 false
     */
    public boolean isSuccess() {
        return isCodeSuccess(this.code);
    }

    /**
     * 显式设置业务状态码并自动同步 success 标识
     *
     * @param code 业务状态码
     */
    public void setCode(Integer code) {
        this.code = code;
        this.success = isCodeSuccess(code);
    }

    /**
     * 链式设置链路追踪标识
     *
     * @param traceId 链路追踪标识
     * @return 当前 Result 实例
     */
    public Result<T> traceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    /**
     * 流式安全解包响应数据
     * 若当前结果非成功状态，直接抛出对应业务异常
     *
     * @return 业务载荷数据
     * @throws BusinessException 当 isSuccess() 为 false 时抛出
     */
    @JsonIgnore
    public T getOrThrow() {
        if (!isSuccess()) {
            throw new BusinessException(this.code, this.msg);
        }
        return this.data;
    }

    /**
     * 函数式转换：若当前响应成功且数据非空，则将数据应用转换函数并包装为新载荷的 Result；若失败则保留原错误要素
     *
     * @param mapper 数据映射函数
     * @param <R>    新数据泛型
     * @return 转换后的 Result 实例
     */
    @JsonIgnore
    public <R> Result<R> map(java.util.function.Function<? super T, ? extends R> mapper) {
        java.util.Objects.requireNonNull(mapper, "mapper 函数不能为空");
        if (!isSuccess()) {
            Result<R> failResult = Result.fail(this.code, this.msg);
            failResult.setTimestamp(this.timestamp);
            return failResult.traceId(this.traceId);
        }
        R newData = this.data != null ? mapper.apply(this.data) : null;
        Result<R> newResult = new Result<>(this.code, this.msg, newData);
        newResult.setTimestamp(this.timestamp);
        return newResult.traceId(this.traceId);
    }

    /**
     * 函数式观察：若当前响应成功且数据非空，执行消费者副作用并返回自身
     *
     * @param consumer 数据消费者
     * @return 当前 Result 实例
     */
    @JsonIgnore
    public Result<T> peek(java.util.function.Consumer<? super T> consumer) {
        java.util.Objects.requireNonNull(consumer, "consumer 不能为空");
        if (isSuccess() && this.data != null) {
            consumer.accept(this.data);
        }
        return this;
    }

    /**
     * 函数式条件执行：若当前响应成功且数据非空，执行消费回调
     *
     * @param consumer 数据消费者
     * @return 当前 Result 实例
     */
    @JsonIgnore
    public Result<T> ifSuccess(java.util.function.Consumer<? super T> consumer) {
        java.util.Objects.requireNonNull(consumer, "consumer 不能为空");
        if (isSuccess() && this.data != null) {
            consumer.accept(this.data);
        }
        return this;
    }

    /**
     * 函数式条件执行：若当前响应表征失败，执行错误回调
     *
     * @param consumer 失败结果消费者
     * @return 当前 Result 实例
     */
    @JsonIgnore
    public Result<T> ifFail(java.util.function.Consumer<Result<T>> consumer) {
        java.util.Objects.requireNonNull(consumer, "consumer 不能为空");
        if (!isSuccess()) {
            consumer.accept(this);
        }
        return this;
    }

    /**
     * 获取数据载荷，若失败或数据为 null 则返回指定的默认值
     *
     * @param other 备选保底值
     * @return 载荷数据或保底值
     */
    @JsonIgnore
    public T orElse(T other) {
        return (isSuccess() && this.data != null) ? this.data : other;
    }

    /**
     * 获取数据载荷，若失败或数据为 null 则执行供应者函数返回兜底值
     *
     * @param supplier 兜底供应者
     * @return 载荷数据或兜底供应结果
     */
    @JsonIgnore
    public T orElseGet(java.util.function.Supplier<? extends T> supplier) {
        java.util.Objects.requireNonNull(supplier, "supplier 不能为空");
        return (isSuccess() && this.data != null) ? this.data : supplier.get();
    }

    /**
     * 获取数据载荷；若当前响应非成功状态，则抛出携带当前状态码与提示文案的 BusinessException
     *
     * @return 业务载荷数据
     * @throws BusinessException 当 isSuccess() 为 false 时抛出
     */
    @JsonIgnore
    public T orElseThrow() {
        return getOrThrow();
    }

    /**
     * 获取数据载荷；若当前响应非成功状态，则基于当前错误提示生成并抛出定制运行时异常
     *
     * @param exceptionSupplier 异常构造函数（入参为错误提示文案 msg）
     * @param <X>               异常类型（约束为 RuntimeException 非受检异常）
     * @return 业务载荷数据
     * @throws X 当 isSuccess() 为 false 时抛出定制运行时异常
     */
    @JsonIgnore
    public <X extends RuntimeException> T orElseThrow(java.util.function.Function<String, ? extends X> exceptionSupplier) {
        java.util.Objects.requireNonNull(exceptionSupplier, "exceptionSupplier 不能为空");
        if (!isSuccess()) {
            throw exceptionSupplier.apply(this.msg);
        }
        return this.data;
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 构建无数据载荷的成功响应
     *
     * @param <T> 数据泛型
     * @return 成功响应对象
     */
    public static <T> Result<T> success() {
        return new Result<>(
                CommonResultCode.SUCCESS.getCode(),
                CommonResultCode.SUCCESS.getMsg(),
                null
        );
    }

    /**
     * 构建携带数据载荷的成功响应
     *
     * @param data 业务载荷数据
     * @param <T>  数据泛型
     * @return 成功响应对象
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(
                CommonResultCode.SUCCESS.getCode(),
                CommonResultCode.SUCCESS.getMsg(),
                data
        );
    }

    /**
     * 构建携带自定义文案与数据载荷的成功响应
     *
     * @param data 业务载荷数据
     * @param msg  自定义提示文案
     * @param <T>  数据泛型
     * @return 成功响应对象
     */
    public static <T> Result<T> success(T data, String msg) {
        return new Result<>(
                CommonResultCode.SUCCESS.getCode(),
                msg,
                data
        );
    }

    /**
     * 构建默认系统繁忙失败响应
     *
     * @param <T> 数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> fail() {
        return fail(CommonResultCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * 构建携带自定义错误文案的失败响应（默认业务码 500）
     *
     * @param msg 错误提示文案
     * @param <T> 数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> fail(String msg) {
        return new Result<>(
                CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                msg,
                null
        );
    }

    /**
     * 构建携带指定错误码与错误文案的失败响应
     *
     * @param code 业务错误码
     * @param msg  错误提示文案
     * @param <T>  数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> fail(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }

    /**
     * 构建基于统一响应状态码契约的失败响应
     *
     * @param resultCode 状态码对象
     * @param <T>        数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> fail(IResultCode resultCode) {
        if (resultCode == null) {
            return fail();
        }
        return new Result<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    /**
     * 构建基于统一响应状态码契约与自定义文案的失败响应
     *
     * @param resultCode 状态码对象
     * @param customMsg  自定义提示文案
     * @param <T>        数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> fail(IResultCode resultCode, String customMsg) {
        Integer code = resultCode != null ? resultCode.getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode();
        return new Result<>(code, customMsg, null);
    }

    /**
     * 构建默认系统繁忙失败响应（{@link #fail()} 别名）
     *
     * @param <T> 数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> failure() {
        return fail();
    }

    /**
     * 构建携带自定义错误文案的失败响应（{@link #fail(String)} 别名）
     *
     * @param msg 错误提示文案
     * @param <T> 数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> failure(String msg) {
        return fail(msg);
    }

    /**
     * 构建携带指定错误码与错误文案的失败响应（{@link #fail(Integer, String)} 别名）
     *
     * @param code 业务错误码
     * @param msg  错误提示文案
     * @param <T>  数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> failure(Integer code, String msg) {
        return fail(code, msg);
    }

    /**
     * 构建基于统一响应状态码契约的失败响应（{@link #fail(IResultCode)} 别名）
     *
     * @param resultCode 状态码对象
     * @param <T>        数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> failure(IResultCode resultCode) {
        return fail(resultCode);
    }

    /**
     * 构建基于统一响应状态码契约与自定义文案的失败响应（{@link #fail(IResultCode, String)} 别名）
     *
     * @param resultCode 状态码对象
     * @param customMsg  自定义提示文案
     * @param <T>        数据泛型
     * @return 失败响应对象
     */
    public static <T> Result<T> failure(IResultCode resultCode, String customMsg) {
        return fail(resultCode, customMsg);
    }

    private static boolean isCodeSuccess(Integer code) {
        return code != null && code == 200;
    }

    private static String obtainMdcTraceId() {
        try {
            return MDC.get(MDC_TRACE_ID_KEY);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
