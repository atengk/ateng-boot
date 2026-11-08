package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.MemberProfileEntity;
import io.github.atengk.boot.sample.mybatisplus.model.CryptoLifecycleCompareVO;
import io.github.atengk.boot.sample.mybatisplus.model.MemberProfileCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.MemberProfileVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserCreateDTO;

/**
 * 会员画像 JSON 字段结构化存储、空安全保底与数据安全三态核验业务场景服务契约
 *
 * @author Ateng
 * @since 2026-10-27
 */
public interface JsonFieldScenarioService extends AtengBaseService<MemberProfileEntity> {

    /**
     * 写入敏感用户数据并返回“数据库底层密文 vs 业务内存真明文 vs 视图序列化动态掩码”三态对比结果
     *
     * @param dto 创建用户请求载荷
     * @return 数据安全三态对比视图
     */
    CryptoLifecycleCompareVO createAndCompareCryptoLifecycle(UserCreateDTO dto);

    /**
     * 生成默认演示用户并返回数据安全三态生命周期对比视图
     *
     * @return 数据安全三态对比视图
     */
    CryptoLifecycleCompareVO demoCryptoCompare();

    /**
     * 创建会员画像记录并返回包含数据库原始 JSON 落盘文本的视图对象
     *
     * @param dto 会员画像创建载荷
     * @return 会员画像视图对象
     */
    MemberProfileVO createMemberProfile(MemberProfileCreateDTO dto);

    /**
     * 根据主键 ID 查询会员画像详情（若表为空且 ID 为 1 则自动初始化演示种子）
     *
     * @param id 会员画像主键 ID
     * @return 会员画像视图对象
     */
    MemberProfileVO getOrSeedMemberProfile(Long id);

    /**
     * 验证数据库 JSON 列为 NULL 时的反序列化空安全保底契约
     *
     * @param memberName 会员昵称
     * @return 集合字段均已兜底为空集合的会员画像视图
     */
    MemberProfileVO verifyJsonNullSafety(String memberName);
}
