package io.github.atengk.boot.core.code;

import io.github.atengk.boot.core.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态码号段规约与唯一性防重校验单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("状态码号段规约与防重工具 ResultCodeUtil 测试")
class ResultCodeUtilTest {

    @Getter
    @AllArgsConstructor
    enum MockCoreCode implements IResultCode {
        PARAM_ERROR(1001, "核心参数错误"),
        ASSERT_FAIL(1002, "断言未通过");

        private final Integer code;
        private final String msg;
    }

    @Getter
    @AllArgsConstructor
    enum MockAuthCode implements IResultCode {
        TOKEN_EXPIRED(2001, "令牌已过期"),
        NO_PERMISSION(2002, "无操作权限");

        private final Integer code;
        private final String msg;
    }

    @Getter
    @AllArgsConstructor
    enum MockCollisionCode implements IResultCode {
        DUPLICATE_AUTH(1001, "故意冲突的状态码");

        private final Integer code;
        private final String msg;
    }

    @Test
    @DisplayName("ResultCodeSegment 边界与区间归属判定测试")
    void shouldCorrectlyDetermineSegmentIntervals() {
        // SYSTEM_CORE (1000 ~ 1999)
        assertThat(ResultCodeSegment.SYSTEM_CORE.contains(999)).isFalse();
        assertThat(ResultCodeSegment.SYSTEM_CORE.contains(1000)).isTrue();
        assertThat(ResultCodeSegment.SYSTEM_CORE.contains(1500)).isTrue();
        assertThat(ResultCodeSegment.SYSTEM_CORE.contains(1999)).isTrue();
        assertThat(ResultCodeSegment.SYSTEM_CORE.contains(2000)).isFalse();

        // AUTH_SECURITY (2000 ~ 2999)
        assertThat(ResultCodeSegment.AUTH_SECURITY.contains(2000)).isTrue();
        assertThat(ResultCodeSegment.AUTH_SECURITY.contains(2999)).isTrue();

        // STORAGE_FILE (3000 ~ 3999)
        assertThat(ResultCodeSegment.STORAGE_FILE.contains(3000)).isTrue();
        assertThat(ResultCodeSegment.STORAGE_FILE.contains(3999)).isTrue();

        // NETWORK_RPC (4000 ~ 4999)
        assertThat(ResultCodeSegment.NETWORK_RPC.contains(4000)).isTrue();
        assertThat(ResultCodeSegment.NETWORK_RPC.contains(4999)).isTrue();

        // PERSISTENCE_TX (5000 ~ 5999)
        assertThat(ResultCodeSegment.PERSISTENCE_TX.contains(5000)).isTrue();
        assertThat(ResultCodeSegment.PERSISTENCE_TX.contains(5999)).isTrue();

        // BUSINESS_RESERVED (6000 ~ 9999)
        assertThat(ResultCodeSegment.BUSINESS_RESERVED.contains(6000)).isTrue();
        assertThat(ResultCodeSegment.BUSINESS_RESERVED.contains(9999)).isTrue();
        assertThat(ResultCodeSegment.BUSINESS_RESERVED.contains(10000)).isFalse();

        // 未纳入规范号段的状态码（如标准 HTTP 码 200, 404）
        assertThat(ResultCodeSegment.of(200)).isNull();
        assertThat(ResultCodeSegment.of(404)).isNull();
        assertThat(ResultCodeSegment.of(500)).isNull();
        assertThat(ResultCodeSegment.of(null)).isNull();
    }

    @Test
    @DisplayName("ResultCodeUtil 号段探测与 isInSegment 判定测试")
    void shouldResolveSegmentAndCheckMembership() {
        assertThat(ResultCodeUtil.getSegment(1001)).isEqualTo(ResultCodeSegment.SYSTEM_CORE);
        assertThat(ResultCodeUtil.getSegment(2001)).isEqualTo(ResultCodeSegment.AUTH_SECURITY);
        assertThat(ResultCodeUtil.getSegment(3001)).isEqualTo(ResultCodeSegment.STORAGE_FILE);
        assertThat(ResultCodeUtil.getSegment(4001)).isEqualTo(ResultCodeSegment.NETWORK_RPC);
        assertThat(ResultCodeUtil.getSegment(5001)).isEqualTo(ResultCodeSegment.PERSISTENCE_TX);
        assertThat(ResultCodeUtil.getSegment(8888)).isEqualTo(ResultCodeSegment.BUSINESS_RESERVED);

        assertThat(ResultCodeUtil.getSegment(MockCoreCode.PARAM_ERROR)).isEqualTo(ResultCodeSegment.SYSTEM_CORE);
        assertThat(ResultCodeUtil.isInSegment(MockCoreCode.PARAM_ERROR, ResultCodeSegment.SYSTEM_CORE)).isTrue();
        assertThat(ResultCodeUtil.isInSegment(MockCoreCode.PARAM_ERROR, ResultCodeSegment.AUTH_SECURITY)).isFalse();

        // HTTP 码不在千位号段内
        assertThat(ResultCodeUtil.getSegment(CommonResultCode.SUCCESS)).isNull();
        assertThat(ResultCodeUtil.isInSegment(CommonResultCode.SUCCESS, ResultCodeSegment.SYSTEM_CORE)).isFalse();
    }

    @Test
    @DisplayName("assertInSegment 校验号段合规性，违规时抛出语义化 BusinessException")
    void shouldAssertInSegmentStrictly() {
        // 合规通过
        ResultCodeUtil.assertInSegment(MockCoreCode.PARAM_ERROR, ResultCodeSegment.SYSTEM_CORE);
        ResultCodeUtil.assertInSegment(MockAuthCode.TOKEN_EXPIRED, ResultCodeSegment.AUTH_SECURITY);

        // 违规号段拦截
        assertThatThrownBy(() -> ResultCodeUtil.assertInSegment(MockCoreCode.PARAM_ERROR, ResultCodeSegment.AUTH_SECURITY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在期望的领域号段");

        // 空保护
        assertThatThrownBy(() -> ResultCodeUtil.assertInSegment(null, ResultCodeSegment.SYSTEM_CORE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ResultCodeUtil.assertInSegment(MockCoreCode.PARAM_ERROR, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("assertUnique 检测状态码数值唯一性，无冲突通过，冲突时阻断拦截")
    void shouldAssertUniqueCodesWithoutCollisions() {
        // 无冲突测试
        ResultCodeUtil.assertUnique(MockCoreCode.PARAM_ERROR, MockCoreCode.ASSERT_FAIL, MockAuthCode.TOKEN_EXPIRED);
        ResultCodeUtil.assertUnique(Arrays.asList(MockCoreCode.PARAM_ERROR, MockAuthCode.NO_PERMISSION));
        ResultCodeUtil.assertUnique((IResultCode[]) null);
        ResultCodeUtil.assertUnique(Collections.emptyList());

        // 碰撞测试：MockCoreCode.PARAM_ERROR (1001) 与 MockCollisionCode.DUPLICATE_AUTH (1001)
        List<IResultCode> conflictList = Arrays.asList(
                MockCoreCode.PARAM_ERROR,
                MockAuthCode.TOKEN_EXPIRED,
                MockCollisionCode.DUPLICATE_AUTH
        );

        assertThatThrownBy(() -> ResultCodeUtil.assertUnique(conflictList))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("检测到状态码冲突碰撞: code=[1001]")
                .hasMessageContaining("MockCoreCode")
                .hasMessageContaining("MockCollisionCode");
    }
}
