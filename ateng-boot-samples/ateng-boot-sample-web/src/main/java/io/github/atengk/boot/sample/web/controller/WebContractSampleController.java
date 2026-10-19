package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.core.validation.ValidationGroups;
import io.github.atengk.boot.sample.web.dto.UserRegisterDTO;
import io.github.atengk.boot.sample.web.vo.DateTimeDemoVO;
import io.github.atengk.boot.sample.web.vo.UserRegisterVO;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Web 响应契约、第一道防线校验与全局异常防御场景控制器
 * 严格践行 ADR-0002 显式 Result 契约，杜绝 @ResponseBodyAdvice，全真演示 JSR-303、ValidList、分组校验与异常脱敏
 *
 * @author Ateng
 * @since 2026-10-19
 */
@RestController
@RequestMapping("/api/sample/contract")
public class WebContractSampleController {

    /**
     * 单实体创建注册（语法与格式第一道防线校验）
     *
     * @param dto 用户注册载荷
     * @return 显式强类型契约 Result<UserRegisterVO>
     */
    @PostMapping("/user")
    public Result<UserRegisterVO> createUser(@Valid @RequestBody UserRegisterDTO dto) {
        UserRegisterVO vo = UserRegisterVO.builder()
                .id(1846182948192841920L)
                .name(dto.getName())
                .age(dto.getAge())
                .email(dto.getEmail())
                .phone(dto.getPhone())
                .build();
        return Result.success(vo);
    }

    /**
     * 批量用户入参校验（防 Spring 默认 List 漏检，依托 ValidList 实现 Fail-Fast）
     *
     * @param users 批量用户列表载荷
     * @return 注册成功的用户总数
     */
    @PostMapping("/users/batch")
    public Result<Integer> batchCreateUsers(@Valid @RequestBody ValidList<UserRegisterDTO> users) {
        return Result.success(users.size());
    }

    /**
     * 用户信息更新（演示 ValidationGroups.Update 分组校验，必须校验 id 非空）
     *
     * @param dto 用户更新载荷
     * @return 更新后的视图对象
     */
    @PutMapping("/user")
    public Result<UserRegisterVO> updateUser(@Validated({Default.class, ValidationGroups.Update.class}) @RequestBody UserRegisterDTO dto) {
        UserRegisterVO vo = UserRegisterVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .age(dto.getAge())
                .email(dto.getEmail())
                .phone(dto.getPhone())
                .build();
        return Result.success(vo);
    }

    /**
     * 日期时间与雪花 ID 序列化格式规范演示端点
     *
     * @return 日期时间视图
     */
    @GetMapping("/datetime")
    public Result<DateTimeDemoVO> getDateTimeDemo() {
        DateTimeDemoVO vo = DateTimeDemoVO.builder()
                .id(1846182948192841920L)
                .dateTime(LocalDateTime.of(2026, 10, 19, 14, 30, 0))
                .date(LocalDate.of(2026, 10, 19))
                .time(LocalTime.of(14, 30, 0))
                .build();
        return Result.success(vo);
    }

    /**
     * 业务受控异常中断演示（无 cause：轻量级日志记录，返回业务错误码）
     *
     * @return 无返回值
     */
    @GetMapping("/error/business")
    public Result<Void> triggerBusinessException() {
        throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "用户积分余额不足");
    }

    /**
     * 携带底层驱动 cause 根因的因果业务异常（保留完整异常因果链）
     *
     * @return 无返回值
     */
    @GetMapping("/error/causal")
    public Result<Void> triggerCausalException() {
        throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "网关代理服务通信异常",
                new IOException("Connection reset by peer"));
    }

    /**
     * 未捕获未知根异常（生产强制脱敏，禁止泄露内部代码堆栈，MDC traceId 闭环溯源）
     *
     * @return 无返回值
     */
    @GetMapping("/error/unexpected")
    public Result<Void> triggerUnexpectedException() {
        throw new NullPointerException("内部系统模拟空指针异常");
    }

    /**
     * 契约解包测试端点
     *
     * @return 字符串契约
     */
    @GetMapping("/unpack")
    public Result<String> unpackDemo() {
        return Result.success("unpack-ok");
    }
}
