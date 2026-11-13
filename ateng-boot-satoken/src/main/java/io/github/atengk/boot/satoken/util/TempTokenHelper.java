package io.github.atengk.boot.satoken.util;

import cn.dev33.satoken.temp.SaTempUtil;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 强类型临时业务令牌静态门面
 * 面向短效一次性业务凭证场景（如限时文件下载签名、扫码确认、邮件激活、敏感操作二次校验票据），
 * 基于 Sa-Token {@link SaTempUtil} 与 {@link JsonUtil} 封装，提供 Duration 强类型有效期控制与零 @class 序列化存储
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Slf4j
public final class TempTokenHelper {

    private TempTokenHelper() {
    }

    /**
     * 签发临时业务令牌（支持字符串与任意 POJO 载荷，零 @class 序列化存储）
     *
     * @param value   业务载荷（字符串直存，POJO 自动转为纯净 JSON 字符串），不能为空
     * @param timeout 令牌有效期，必须为正数且不少于 1 秒
     * @return 生成的临时业务令牌凭据
     */
    public static String createTempToken(Object value, Duration timeout) {
        if (value == null) {
            throw new IllegalArgumentException("签发临时令牌失败：载荷 (value) 不能为空");
        }
        if (value instanceof CharSequence cs && cs.toString().isBlank()) {
            throw new IllegalArgumentException("签发临时令牌失败：字符串载荷不能为空白");
        }
        long timeoutSeconds = validateTimeoutSeconds(timeout);
        String rawPayload = (value instanceof CharSequence cs) ? cs.toString() : JsonUtil.toJsonString(value);
        try {
            return SaTempUtil.createToken(rawPayload, timeoutSeconds);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "签发临时业务令牌失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 解析临时业务令牌并还原为原始字符串载荷
     *
     * @param token 临时业务令牌凭据
     * @return 原始字符串载荷；若令牌已过期或不存在则返回 null
     */
    public static String parseTempToken(String token) {
        String normalizedToken = validateToken(token, "解析临时令牌");
        try {
            Object raw = SaTempUtil.parseToken(normalizedToken);
            if (raw == null) {
                return null;
            }
            return (raw instanceof String str) ? str : String.valueOf(raw);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "解析临时业务令牌失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 解析临时业务令牌并还原为目标强类型对象（支持 String 直返与 POJO 反序列化）
     *
     * @param token 临时业务令牌凭据
     * @param clazz 目标反序列化类型 Class，不能为 null
     * @param <T>   目标返回类型泛型
     * @return 强类型对象实例；若令牌已过期或不存在则返回 null
     */
    public static <T> T parseTempToken(String token, Class<T> clazz) {
        String normalizedToken = validateToken(token, "解析临时令牌");
        if (clazz == null) {
            throw new IllegalArgumentException("解析临时令牌失败：目标类型 (clazz) 不能为 null");
        }
        Object raw;
        try {
            raw = SaTempUtil.parseToken(normalizedToken);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "解析临时业务令牌失败: " + ex.getMessage(), ex);
        }
        if (raw == null) {
            return null;
        }
        if (String.class.equals(clazz) || CharSequence.class.equals(clazz)) {
            return clazz.cast(raw instanceof String str ? str : String.valueOf(raw));
        }
        if (clazz.isInstance(raw)) {
            return clazz.cast(raw);
        }
        String rawJson = String.valueOf(raw);
        try {
            return JsonUtil.parseObject(rawJson, clazz);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "临时令牌载荷反序列化为 " + clazz.getSimpleName() + " 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 查询临时业务令牌剩余有效时间（秒）
     *
     * @param token 临时业务令牌凭据
     * @return 剩余有效秒数；已过期或不存在时返回 -2（或非正数）
     */
    public static long getTempTokenTimeout(String token) {
        String normalizedToken = validateToken(token, "查询临时令牌有效期");
        try {
            return SaTempUtil.getTimeout(normalizedToken);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "查询临时令牌有效期失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 主动销毁临时业务令牌（实现阅后即焚一次性消费闭环）
     *
     * @param token 待销毁的临时业务令牌凭据
     */
    public static void deleteTempToken(String token) {
        String normalizedToken = validateToken(token, "销毁临时令牌");
        try {
            SaTempUtil.deleteToken(normalizedToken);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "销毁临时业务令牌失败: " + ex.getMessage(), ex);
        }
    }

    private static String validateToken(String token, String action) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(action + "失败：token 不能为空");
        }
        return token.trim();
    }

    private static long validateTimeoutSeconds(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("签发临时令牌失败：有效期 (timeout) 不能为空");
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.toSeconds() <= 0) {
            throw new IllegalArgumentException("签发临时令牌失败：有效期 (timeout) 必须为正数且不少于 1 秒: " + timeout);
        }
        return timeout.toSeconds();
    }
}
