package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.dto.AuditUserDTO;
import io.github.atengk.boot.sample.web.vo.UserRegisterVO;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.annotation.WebLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作审计与接口耗时日志场景控制器
 * 演示通过 @OperateLog 与 @WebLog 实现全链路无侵入结构化审计、敏感参数排除与动态脱敏出库
 *
 * @author Ateng
 * @since 2026-10-19
 */
@RestController
@RequestMapping("/api/sample/audit")
public class WebAuditSampleController {

    /**
     * 新增用户操作审计（入参敏感字段 password 自动排除）
     *
     * @param dto 审计用户载荷
     * @return 返回用户视图（包含动态脱敏手机号）
     */
    @PostMapping("/user")
    @OperateLog(
            module = "用户管理",
            type = OperateTypeEnum.INSERT,
            description = "新增系统用户",
            excludeParams = {"password"}
    )
    public Result<UserRegisterVO> createUser(@RequestBody AuditUserDTO dto) {
        UserRegisterVO vo = UserRegisterVO.builder()
                .id(1846182948192841920L)
                .name(dto.getUsername())
                .age(25)
                .email(dto.getEmail() != null ? dto.getEmail() : "user@example.com")
                .phone(dto.getPhone() != null ? dto.getPhone() : "13812345678")
                .build();
        return Result.success(vo);
    }

    /**
     * 查询用户操作审计（出参联动 @FieldSensitive 脱敏，内存对象保持真明文）
     *
     * @param id 用户主键
     * @return 用户视图
     */
    @GetMapping("/user/{id}")
    @OperateLog(
            module = "用户中心",
            type = OperateTypeEnum.QUERY,
            description = "查询用户详情"
    )
    public Result<UserRegisterVO> getUser(@PathVariable Long id) {
        UserRegisterVO vo = UserRegisterVO.builder()
                .id(id)
                .name("张三")
                .age(30)
                .email("zhangsan@example.com")
                .phone("13912345678")
                .build();
        return Result.success(vo);
    }

    /**
     * 业务操作异常审计（切面准确捕获异常状态并原样重抛，保障因果链透明）
     *
     * @return 无返回值
     */
    @GetMapping("/error")
    @OperateLog(
            module = "用户中心",
            type = OperateTypeEnum.OTHER,
            description = "异常测试"
    )
    public Result<Void> triggerError() {
        throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "模拟业务操作失败");
    }

    /**
     * WebLog 轻量级接口耗时与入参审计演示端点
     *
     * @param dto 审计用户载荷
     * @return 业务响应
     */
    @PostMapping("/weblog")
    @WebLog(value = "创建用户接口日志审计", excludeParams = {"password"}, printCostTime = true)
    public Result<String> webLogDemo(@RequestBody AuditUserDTO dto) {
        return Result.success("weblog-ok:" + dto.getUsername());
    }
}
