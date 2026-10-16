package io.github.atengk.boot.core.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基础响应状态码枚举测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("基础响应码枚举测试")
class CommonResultCodeTest {

    @Test
    @DisplayName("验证预置响应状态码与提示文案完整性")
    void shouldDefineStandardResultCodesCorrectly() {
        assertAll(
                () -> {
                    assertEquals(200, CommonResultCode.SUCCESS.getCode());
                    assertEquals("操作成功", CommonResultCode.SUCCESS.getMsg());
                },
                () -> {
                    assertEquals(400, CommonResultCode.BAD_REQUEST.getCode());
                    assertEquals("参数校验失败", CommonResultCode.BAD_REQUEST.getMsg());
                },
                () -> {
                    assertEquals(401, CommonResultCode.UNAUTHORIZED.getCode());
                    assertEquals("尚未登录或令牌已过期", CommonResultCode.UNAUTHORIZED.getMsg());
                },
                () -> {
                    assertEquals(403, CommonResultCode.FORBIDDEN.getCode());
                    assertEquals("权限不足，拒绝访问", CommonResultCode.FORBIDDEN.getMsg());
                },
                () -> {
                    assertEquals(404, CommonResultCode.NOT_FOUND.getCode());
                    assertEquals("请求资源不存在", CommonResultCode.NOT_FOUND.getMsg());
                },
                () -> {
                    assertEquals(500, CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
                    assertEquals("系统繁忙，请稍后再试", CommonResultCode.INTERNAL_SERVER_ERROR.getMsg());
                },
                () -> {
                    assertEquals(40003, CommonResultCode.REPEAT_SUBMIT.getCode());
                    assertEquals("操作过于频繁，请稍后再试", CommonResultCode.REPEAT_SUBMIT.getMsg());
                }
        );
    }

    @Test
    @DisplayName("验证 IResultCode 契约多态赋值")
    void shouldConformToIResultCodeInterface() {
        IResultCode resultCode = CommonResultCode.SUCCESS;
        assertNotNull(resultCode.getCode());
        assertNotNull(resultCode.getMsg());
        assertInstanceOf(IResultCode.class, CommonResultCode.SUCCESS);
    }
}
