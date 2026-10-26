package io.github.atengk.boot.core.code;

import io.github.atengk.boot.core.exception.BusinessException;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 业务状态码号段规约与唯一性防重校验静态工具
 * 为全反应堆 28+ Starter 与业务系统提供统一的号段识别、区间合规性防卫与冲突防重检测能力
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class ResultCodeUtil {

    private ResultCodeUtil() {
    }

    /**
     * 获取指定状态码数值归属的领域号段
     *
     * @param code 状态码数值
     * @return 归属的 ResultCodeSegment；若不属于任何规范号段则返回 null
     */
    public static ResultCodeSegment getSegment(int code) {
        return ResultCodeSegment.of(code);
    }

    /**
     * 获取指定状态码契约对象归属的领域号段
     *
     * @param resultCode 状态码契约对象
     * @return 归属的 ResultCodeSegment；若入参为空或不属于任何规范号段则返回 null
     */
    public static ResultCodeSegment getSegment(IResultCode resultCode) {
        return ResultCodeSegment.of(resultCode);
    }

    /**
     * 判断指定数值状态码是否位于目标号段内
     *
     * @param code    状态码数值
     * @param segment 目标领域号段（非空）
     * @return 若在号段内返回 true，否则返回 false
     */
    public static boolean isInSegment(int code, ResultCodeSegment segment) {
        Objects.requireNonNull(segment, "segment 不能为空");
        return segment.contains(code);
    }

    /**
     * 判断指定状态码契约对象是否位于目标号段内
     *
     * @param resultCode 状态码契约对象
     * @param segment    目标领域号段（非空）
     * @return 若对象及其 code 非空且在号段内返回 true，否则返回 false
     */
    public static boolean isInSegment(IResultCode resultCode, ResultCodeSegment segment) {
        Objects.requireNonNull(segment, "segment 不能为空");
        if (resultCode == null || resultCode.getCode() == null) {
            return false;
        }
        return segment.contains(resultCode.getCode());
    }

    /**
     * 校验指定状态码契约对象必须处于期望的领域号段内，否则抛出业务异常
     *
     * @param resultCode      状态码契约对象（非空）
     * @param expectedSegment 期望号段（非空）
     * @throws BusinessException 当状态码不处于期望号段时抛出
     */
    public static void assertInSegment(IResultCode resultCode, ResultCodeSegment expectedSegment) {
        Objects.requireNonNull(resultCode, "resultCode 不能为空");
        Objects.requireNonNull(expectedSegment, "expectedSegment 不能为空");
        Integer code = resultCode.getCode();
        if (code == null || !expectedSegment.contains(code)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "状态码 [" + code + "] (" + resultCode.getMsg() + ") 不在期望的领域号段 ["
                            + expectedSegment.name() + "] (" + expectedSegment.getMinCode() + "~" + expectedSegment.getMaxCode() + ") 内");
        }
    }

    /**
     * 断言传入的一组状态码契约对象数值绝对唯一（无重复碰撞）
     * 用于应用启动期检查或单元测试，拦截跨模块/枚举的状态码编号冲突
     *
     * @param codes 状态码数组
     * @throws BusinessException 当发现重复状态码数值时抛出
     */
    public static void assertUnique(IResultCode... codes) {
        if (codes == null || codes.length == 0) {
            return;
        }
        assertUnique(Arrays.asList(codes));
    }

    /**
     * 断言传入的一组状态码契约集合数值绝对唯一（无重复碰撞）
     *
     * @param codes 状态码集合
     * @throws BusinessException 当发现重复状态码数值时抛出
     */
    public static void assertUnique(Collection<? extends IResultCode> codes) {
        if (codes == null || codes.isEmpty()) {
            return;
        }

        Map<Integer, IResultCode> seen = new HashMap<>(codes.size());
        for (IResultCode codeObj : codes) {
            if (codeObj == null) {
                continue;
            }
            Integer code = codeObj.getCode();
            if (code == null) {
                continue;
            }
            IResultCode conflict = seen.putIfAbsent(code, codeObj);
            if (conflict != null) {
                throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                        "检测到状态码冲突碰撞: code=[" + code + "] 分别被 ["
                                + conflict.getClass().getSimpleName() + "." + conflict + " -> " + conflict.getMsg()
                                + "] 与 [" + codeObj.getClass().getSimpleName() + "." + codeObj + " -> " + codeObj.getMsg() + "] 重复定义");
            }
        }
    }
}
