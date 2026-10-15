package io.github.atengk.boot.sample.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.boot.sample.dto.UserCreateDTO;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.mapper.UserMapper;
import io.github.atengk.boot.sample.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 示例用户控制层
 * 显式遵循 Result 契约，提供用户创建、按 ID 查询、乐观锁更新、分页防刷与受控/非受控异常演示端点
 *
 * @author Ateng
 * @since 2026-10-15
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserMapper userMapper;

    public UserController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 创建用户端点，结合 @Valid 校验前置拦截
     *
     * @param dto 创建用户请求 DTO
     * @return 新增用户分配的全局雪花 ID
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserCreateDTO dto) {
        UserEntity entity = new UserEntity();
        entity.setName(dto.getName());
        entity.setAge(dto.getAge());
        entity.setEmail(dto.getEmail());
        userMapper.insert(entity);
        return Result.success(entity.getId());
    }

    /**
     * 根据主键 ID 查询用户
     *
     * @param id 用户主键 ID
     * @return 用户实体详情
     */
    @GetMapping("/{id}")
    public Result<UserEntity> getById(@PathVariable("id") Long id) {
        UserEntity user = userMapper.selectById(id);
        BusinessAssert.notNull(user, "目标用户不存在");
        return Result.success(user);
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
        UserEntity user = userMapper.selectById(id);
        BusinessAssert.notNull(user, "目标用户不存在");
        user.setName(name);
        int rows = userMapper.updateById(user);
        return Result.success(rows > 0);
    }

    /**
     * 分页查询用户列表，接收标准 PageQuery 入参，返回彻底与持久层解耦的 PageResult<UserVO>
     * 在服务边界通过 PageUtil 一行完成 Entity 到 VO 的流式转换，消除 ORM 属性泄露
     *
     * @param query 分页查询入参
     * @return 分页结果
     */
    @GetMapping("/page")
    public Result<PageResult<UserVO>> page(PageQuery query) {
        Page<UserEntity> page = PageUtil.toPage(query);
        Page<UserEntity> entityPage = userMapper.selectPage(page, null);
        PageResult<UserVO> result = PageUtil.toPageResult(entityPage).map(UserVO::from);
        return Result.success(result);
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
