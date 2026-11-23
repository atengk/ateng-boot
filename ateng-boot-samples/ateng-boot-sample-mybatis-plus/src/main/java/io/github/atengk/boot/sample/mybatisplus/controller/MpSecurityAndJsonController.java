package io.github.atengk.boot.sample.mybatisplus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.mybatisplus.model.CryptoLifecycleCompareVO;
import io.github.atengk.boot.sample.mybatisplus.model.MemberProfileCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.MemberProfileVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.service.JsonFieldScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字段级透明加解密、视图动态脱敏与受管 JSON 映射体验控制器
 * 对应模块文档第 4 章，演示 EncryptTypeHandler 持久层密文落盘、Service 层内存 100% 原始真明文、
 * UserVO @FieldSensitive 响应序列化动态掩码，以及 AtengJacksonTypeHandler / GenericJsonTypeHandler 复杂泛型与空安全保底。
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/mybatis-plus/security-json")
@RequiredArgsConstructor
public class MpSecurityAndJsonController {

    private final JsonFieldScenarioService jsonFieldScenarioService;

    /**
     * 写入敏感用户数据并返回“数据库底层密文 vs 业务内存真明文 vs 视图序列化动态掩码”三态对比结果
     *
     * @param dto 创建用户请求载荷
     * @return 数据安全三态对比视图
     */
    @PostMapping("/crypto-compare")
    public Result<CryptoLifecycleCompareVO> createAndCompareCryptoLifecycle(@Valid @RequestBody UserCreateDTO dto) {
        return Result.success(jsonFieldScenarioService.createAndCompareCryptoLifecycle(dto));
    }

    /**
     * 开箱即用的数据安全三态对比体验端点（按需生成一条演示用户并直观展示三态生命周期隔离）
     *
     * @return 数据安全三态对比视图
     */
    @GetMapping("/crypto-compare/demo")
    public Result<CryptoLifecycleCompareVO> demoCryptoCompare() {
        return Result.success(jsonFieldScenarioService.demoCryptoCompare());
    }

    /**
     * 创建会员画像记录（涵盖 List/Set/Map、含 LocalDateTime 的 POJO 与 List<ShippingAddress> 深层泛型 JSON 映射）
     *
     * @param dto 会员画像创建请求载荷
     * @return 包含数据库底层原始 JSON 文本与反序列化强类型结构的会员画像视图
     */
    @PostMapping("/member-profile")
    public Result<MemberProfileVO> createMemberProfile(@Valid @RequestBody MemberProfileCreateDTO dto) {
        return Result.success(jsonFieldScenarioService.createMemberProfile(dto));
    }

    /**
     * 根据主键 ID 查询会员画像详情（若表为空且查询 ID 为 1，按需初始化一条完整演示画像）
     *
     * @param id 会员画像主键 ID
     * @return 会员画像视图对象
     */
    @GetMapping("/member-profile/{id}")
    public Result<MemberProfileVO> getMemberProfile(@PathVariable Long id) {
        return Result.success(jsonFieldScenarioService.getOrSeedMemberProfile(id));
    }

    /**
     * 验证数据库 JSON 列为 NULL 时的反序列化空安全保底契约
     *
     * @param memberName 会员昵称
     * @return 集合字段均已兜底为空集合的会员画像视图
     */
    @PostMapping("/member-profile/null-safety")
    public Result<MemberProfileVO> verifyJsonNullSafety(
            @RequestParam(defaultValue = "空集合保底测试会员") String memberName) {
        return Result.success(jsonFieldScenarioService.verifyJsonNullSafety(memberName));
    }
}
