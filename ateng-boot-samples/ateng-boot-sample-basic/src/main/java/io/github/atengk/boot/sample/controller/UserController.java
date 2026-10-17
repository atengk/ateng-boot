package io.github.atengk.boot.sample.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.dto.UserCreateDTO;
import io.github.atengk.boot.sample.dto.UserLoginDTO;
import io.github.atengk.boot.sample.service.UserService;
import io.github.atengk.boot.sample.vo.UserVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 示例用户控制层
 * 显式遵循 Result 契约与接入层薄层原则，调度 UserService 执行业务逻辑，提供用户管理、分页检索与异常演示端点
 *
 * @author Ateng
 * @since 2026-10-15
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 创建用户端点，结合 @Valid 校验前置拦截
     *
     * @param dto 创建用户请求 DTO
     * @return 新增用户分配的全局雪花 ID
     */
    @PostMapping
    @WebLog(value = "创建用户", printParams = true, printResult = true, printCostTime = true)
    public Result<Long> create(@Valid @RequestBody UserCreateDTO dto) {
        return Result.success(userService.createUser(dto));
    }

    /**
     * 用户登录模拟端点，演示敏感参数排除脱敏（排除 password）
     *
     * @param dto 登录请求 DTO
     * @return 模拟颁发的 Token
     */
    @PostMapping("/login")
    @WebLog(value = "用户登录", excludeParams = {"password"})
    public Result<String> login(@Valid @RequestBody UserLoginDTO dto) {
        String username = (dto != null && dto.getUsername() != null) ? dto.getUsername() : "anonymous";
        return Result.success("mock-token-" + username);
    }

    /**
     * 文件上传端点，演示大文件 MultipartFile 自动占位替换防 OOM
     *
     * @param file 上传文件
     * @return 文件名
     */
    @PostMapping("/upload")
    @WebLog(value = "文件上传")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(file.getOriginalFilename());
    }

    /**
     * 根据主键 ID 查询用户
     *
     * @param id 用户主键 ID
     * @return 用户视图对象
     */
    @GetMapping("/{id}")
    public Result<UserVO> getById(@PathVariable("id") Long id) {
        return Result.success(userService.getUserById(id));
    }

    /**
     * 更新用户名称，支持 CAS 乐观锁版本流转
     *
     * @param id   用户 ID
     * @param name 新名称
     * @return 是否更新成功
     */
    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable("id") Long id, @RequestParam("name") String name) {
        return Result.success(userService.updateUserName(id, name));
    }

    /**
     * 分页查询用户列表，接收标准 PageQuery 入参，返回彻底与持久层解耦的 PageResult<UserVO>
     * 业务逻辑与实体映射由 UserService 纯净流式承接，消除 Controller 对持久层 ORM 的直接依赖
     *
     * @param query 分页查询入参
     * @return 分页结果
     */
    @GetMapping("/page")
    public Result<PageResult<UserVO>> page(PageQuery query) {
        return Result.success(userService.pageUsers(query));
    }

    /**
     * 时间类型标准化序列化格式演示端点
     *
     * @return 包含 LocalDateTime、LocalDate 与 LocalTime 的响应载荷
     */
    @GetMapping("/date-time-demo")
    public Result<Map<String, Object>> dateTimeDemo() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dateTime", LocalDateTime.of(2026, 10, 15, 14, 30, 0));
        map.put("date", LocalDate.of(2026, 10, 15));
        map.put("time", LocalTime.of(14, 30, 0));
        return Result.success(map);
    }

    /**
     * 业务异常端点，用于验证 BusinessException 统一响应转换
     *
     * @return 无返回值
     */
    @GetMapping("/error/business")
    @WebLog(value = "业务异常端点", printParams = true, printCostTime = true)
    public Result<Void> businessError() {
        throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "用户积分余额不足");
    }

    /**
     * 非受控未知根异常端点，用于验证未知根异常生产脱敏与后台 traceId 关联
     *
     * @return 无返回值
     */
    @GetMapping("/error/unexpected")
    public Result<Void> unexpectedError() {
        throw new RuntimeException("底层数据库连接池耗尽崩溃");
    }
}
