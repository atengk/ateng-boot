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
