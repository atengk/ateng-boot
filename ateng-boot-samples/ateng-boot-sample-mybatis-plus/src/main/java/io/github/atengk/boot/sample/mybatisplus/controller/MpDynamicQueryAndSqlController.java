package io.github.atengk.boot.sample.mybatisplus.controller;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryParser;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.sample.mybatisplus.model.DynamicSqlHotSwapDTO;
import io.github.atengk.boot.sample.mybatisplus.model.UserDeptDetailVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserDynamicVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserSearchDTO;
import io.github.atengk.boot.sample.mybatisplus.model.UserVO;
import io.github.atengk.boot.sample.mybatisplus.service.DynamicSqlScenarioService;
import io.github.atengk.boot.sample.mybatisplus.service.MultiTableDynamicQueryScenarioService;
import io.github.atengk.boot.sample.mybatisplus.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 动态查询安全转译、外部动态 SQL 引擎与零实体泄露分页体验控制器
 * 对应模块文档第 6 章，演示 PageUtil 白名单安全排序与流式 VO 分页、URL 冒号高阶语法（含 ~ 波浪线范围与 or_组号:）、
 * @QueryField DTO 检索、多表 JOIN 方法引用直出与 AST 别名自愈、existsDynamic 单页物理短路及 DynamicSqlExecutor / DynamicSqlRegistry 热更新。
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/mybatis-plus/query")
@RequiredArgsConstructor
public class MpDynamicQueryAndSqlController {

    private final UserService userService;
    private final MultiTableDynamicQueryScenarioService multiTableService;
    private final DynamicSqlScenarioService dynamicSqlScenarioService;

    /**
     * 基于 PageUtil 实体白名单安全排序与流式 VO 转换执行用户分页查询
     * 自动过滤 @DynamicQueryIgnore、@FieldEncrypt 与未授权排序字段
     *
     * @param request HTTP 请求对象（承载 current、size 与 sort 参数）
     * @return 标准传输层用户分页结果
     */
    @GetMapping("/page-safe")
    public Result<PageResult<UserVO>> pageUsersSafely(HttpServletRequest request) {
        DynamicQueryRequest queryRequest = DynamicQueryParser.parse(request.getQueryString());
        return Result.success(userService.pageUsersSafely(queryRequest));
    }

    /**
     * 基于 URL Query 冒号高阶语法（支持 ~ 波浪线范围、or_组号: 嵌套分组与六重安全防卫）执行单表动态检索
     *
     * @param request HTTP 请求对象
     * @return 标准传输层用户分页结果
     */
    @GetMapping("/dynamic/url")
    public Result<PageResult<UserVO>> queryUsersByDynamicUrl(HttpServletRequest request) {
        return Result.success(userService.pageUsersByDynamicUrl(request.getQueryString()));
    }

    /**
     * 基于挂载 @QueryField 的业务检索载荷 UserSearchDTO 执行声明式动态分页检索
     *
     * @param searchDTO 用户声明式检索载荷
     * @return 标准传输层用户分页结果
     */
    @PostMapping("/dynamic/dto")
    public Result<PageResult<UserVO>> queryUsersBySearchDto(@RequestBody UserSearchDTO searchDTO) {
        return Result.success(userService.pageUsersBySearchDto(searchDTO));
    }

    /**
     * 基于多表 JOIN 方法引用（pageDynamic）执行联表分页查询，自动将 VO 属性名自愈映射为 d.dept_name 等底层物理列
     *
     * @param request HTTP 请求对象
     * @return 用户与部门联合投影分页结果
     */
    @GetMapping("/multi-table/page")
    public Result<PageResult<UserDeptDetailVO>> pageUserDeptMultiTable(HttpServletRequest request) {
        return Result.success(multiTableService.pageUserDeptByUrl(request.getQueryString()));
    }

    /**
     * 基于多表 JOIN 方法引用（listDynamic）执行联表列表查询，自动完成 AST 表别名自愈与 VO 直出
     *
     * @param request HTTP 请求对象
     * @return 用户与部门联合投影列表
     */
    @GetMapping("/multi-table/list")
    public Result<List<UserDeptDetailVO>> listUserDeptMultiTable(HttpServletRequest request) {
        return Result.success(multiTableService.listUserDeptByUrl(request.getQueryString()));
    }

    /**
     * 基于 existsDynamic 单页 Page(1, 1, false) 物理短路机制执行多表联查存在性判定
     *
     * @param request HTTP 请求对象
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    @GetMapping("/multi-table/exists")
    public Result<Boolean> existsUserDeptMultiTable(HttpServletRequest request) {
        return Result.success(multiTableService.existsUserDeptByUrl(request.getQueryString()));
    }

    /**
     * 基于 DynamicSqlExecutor 免 Mapper 接口执行外部 XML 动态 SQL 分页查询
     *
     * @param name      用户姓名模糊匹配（可选）
     * @param minAge    最小年龄（可选）
     * @param pageQuery 分页参数
     * @return 外部动态 SQL 分页查询结果
     */
    @GetMapping("/dynamic-sql/page")
    public Result<PageResult<UserDynamicVO>> pageUsersByExternalDynamicSql(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer minAge,
            PageQuery pageQuery) {
        return Result.success(dynamicSqlScenarioService.pageUsersDynamicWithSeed(name, minAge, pageQuery));
    }

    /**
     * 基于 DynamicSqlRegistry 执行运行时零停机原子热替换并立即验证查询（语法错误时由内存沙箱拦截回滚）
     *
     * @param dto 动态 SQL 热替换请求载荷
     * @return 热替换成功后立即执行的查询结果列表
     */
    @PostMapping("/dynamic-sql/hot-swap")
    public Result<List<Map<String, Object>>> hotSwapDynamicSql(@Valid @RequestBody DynamicSqlHotSwapDTO dto) {
        return Result.success(dynamicSqlScenarioService.hotSwapAndExecute(dto));
    }

    /**
     * 基于 DynamicSqlExecutor.exists(statementId, DynamicQueryRequest, extraParams) 执行单页 LIMIT 1 存在性探测
     *
     * @param name   用户姓名模糊匹配（可选）
     * @param minAge 最小年龄（可选）
     * @param deptId 部门 ID（作为 extraParams 绑定参数传入动态 SQL，可选）
     * @return 若存在至少一条匹配记录返回 true，否则返回 false
     */
    @GetMapping("/dynamic-sql/exists")
    public Result<Boolean> existsUsersByDynamicSqlRequest(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer minAge,
            @RequestParam(required = false) Long deptId) {
        return Result.success(dynamicSqlScenarioService.existsUsersDynamicRequest(name, minAge, deptId));
    }

    /**
     * 基于 DynamicSqlExecutor.existsDynamic(statementId, Object, extraParams) 按 DTO 执行存在性探测
     *
     * @param searchDTO 声明式动态检索 DTO（可选）
     * @param deptId    部门 ID（作为 extraParams 绑定参数传入，可选）
     * @return 若存在至少一条匹配记录返回 true，否则返回 false
     */
    @PostMapping("/dynamic-sql/exists-dynamic")
    public Result<Boolean> existsUsersByDynamicSqlDto(
            @RequestBody(required = false) UserSearchDTO searchDTO,
            @RequestParam(required = false) Long deptId) {
        return Result.success(dynamicSqlScenarioService.existsUsersDynamicDto(searchDTO, deptId));
    }
}
