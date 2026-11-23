package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.MemberProfileEntity;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.MemberProfileMapper;
import io.github.atengk.boot.sample.mybatisplus.model.CryptoLifecycleCompareVO;
import io.github.atengk.boot.sample.mybatisplus.model.MemberProfileCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.MemberProfileVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.UserVO;
import io.github.atengk.boot.sample.mybatisplus.service.JsonFieldScenarioService;
import io.github.atengk.boot.sample.mybatisplus.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会员画像 JSON 字段结构化存储、空安全保底与数据安全三态核验业务场景服务实现
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Service
@RequiredArgsConstructor
public class JsonFieldScenarioServiceImpl
        extends AtengServiceImpl<MemberProfileMapper, MemberProfileEntity>
        implements JsonFieldScenarioService {

    private final UserService userService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public CryptoLifecycleCompareVO createAndCompareCryptoLifecycle(UserCreateDTO dto) {
        UserEntity entity = new UserEntity(
                dto.getName(),
                dto.getAge(),
                dto.getEmail(),
                dto.getPhone(),
                dto.getDeptId() != null ? dto.getDeptId() : 100L
        );
        userService.save(entity);
        return buildCryptoCompareVO(entity.getId(), dto.getPhone());
    }

    @Override
    public CryptoLifecycleCompareVO demoCryptoCompare() {
        String rawPhone = "13812345678";
        UserEntity demoUser = new UserEntity("赵六", 30, "zhaoliu@example.com", rawPhone, 100L);
        userService.save(demoUser);
        return buildCryptoCompareVO(demoUser.getId(), rawPhone);
    }

    @Override
    public MemberProfileVO createMemberProfile(MemberProfileCreateDTO dto) {
        MemberProfileEntity entity = new MemberProfileEntity();
        entity.setMemberName(dto.getMemberName());
        entity.setTags(dto.getTags());
        entity.setRoles(dto.getRoles());
        entity.setExtraInfo(dto.getExtraInfo());
        if (dto.getBio() != null || dto.getLevel() != null) {
            entity.setProfile(new MemberProfileEntity.UserProfile(
                    dto.getBio() != null ? dto.getBio() : "默认会员简介",
                    dto.getLevel() != null ? dto.getLevel() : 1,
                    LocalDateTime.now().withNano(0)
            ));
        }
        if (dto.getShippingAddresses() != null) {
            entity.setShippingAddresses(dto.getShippingAddresses().stream()
                    .map(a -> new MemberProfileEntity.ShippingAddress(
                            a.getLabel(), a.getCity(), a.getDetailAddress(), a.getDefaultAddress()
                    ))
                    .toList());
        }
        save(entity);
        return loadMemberProfileVO(entity.getId());
    }

    @Override
    public MemberProfileVO getOrSeedMemberProfile(Long id) {
        BusinessAssert.notNull(id, "会员画像 ID 不能为空");
        MemberProfileEntity entity = getById(id);
        if (entity == null && id == 1L && count() == 0L) {
            MemberProfileEntity seed = buildSeedMemberProfile();
            save(seed);
            return loadMemberProfileVO(seed.getId());
        }
        BusinessAssert.notNull(entity, "会员画像记录不存在: id=" + id);
        return loadMemberProfileVO(id);
    }

    @Override
    public MemberProfileVO verifyJsonNullSafety(String memberName) {
        MemberProfileEntity nullFieldsEntity = new MemberProfileEntity();
        nullFieldsEntity.setMemberName(memberName);
        nullFieldsEntity.setTags(null);
        nullFieldsEntity.setRoles(null);
        nullFieldsEntity.setExtraInfo(null);
        nullFieldsEntity.setProfile(null);
        nullFieldsEntity.setShippingAddresses(null);
        save(nullFieldsEntity);
        return loadMemberProfileVO(nullFieldsEntity.getId());
    }

    private CryptoLifecycleCompareVO buildCryptoCompareVO(Long userId, String expectedPlainPhone) {
        // 1. 直接通过底层 JDBC 读取物理表中的原始密文列，证明入库已透明加密
        Map<String, Object> rawRow = jdbcTemplate.queryForMap(
                "SELECT phone, email FROM sys_user WHERE id = ?", userId
        );
        String dbCipherPhone = (String) rawRow.get("phone");
        String dbCipherEmail = (String) rawRow.get("email");

        // 2. 通过 Service 读取内存实体，验证内存中保持 100% 原始真明文（未被打星号污染）
        UserEntity loadedEntity = userService.getById(userId);
        boolean isPlaintextInMemory = loadedEntity != null
                && expectedPlainPhone.equals(loadedEntity.getPhone())
                && !loadedEntity.getPhone().contains("*");

        // 3. 封装为带 @FieldSensitive 注解的 UserVO，交由 Jackson 响应序列化层执行动态掩码
        CryptoLifecycleCompareVO compareVO = new CryptoLifecycleCompareVO();
        compareVO.setUserId(userId);
        compareVO.setDbCipherPhone(dbCipherPhone);
        compareVO.setDbCipherEmail(dbCipherEmail);
        compareVO.setInMemoryPlaintextVerified(isPlaintextInMemory);
        compareVO.setInMemoryPhoneLength(loadedEntity != null && loadedEntity.getPhone() != null
                ? loadedEntity.getPhone().length() : 0);
        compareVO.setMaskedView(UserVO.fromEntity(loadedEntity));
        return compareVO;
    }

    private MemberProfileVO loadMemberProfileVO(Long id) {
        MemberProfileEntity loaded = getById(id);
        MemberProfileVO vo = MemberProfileVO.fromEntity(loaded);
        if (vo != null) {
            String rawJson = jdbcTemplate.queryForObject(
                    "SELECT shipping_addresses FROM member_profile WHERE id = ?",
                    String.class,
                    id
            );
            vo.setRawAddressesJsonInDb(rawJson);
        }
        return vo;
    }

    private MemberProfileEntity buildSeedMemberProfile() {
        MemberProfileEntity seed = new MemberProfileEntity();
        seed.setMemberName("阿腾架构师");
        seed.setTags(List.of("SpringBoot3", "MyBatis-Plus", "云原生"));
        seed.setRoles(Set.of("VIP_MEMBER", "ARCHITECT"));
        seed.setExtraInfo(Map.of("theme", "dark", "notifyChannel", "email"));
        seed.setProfile(new MemberProfileEntity.UserProfile(
                "专注高并发分布式底座研发", 9, LocalDateTime.of(2026, 10, 27, 10, 30, 0)
        ));
        seed.setShippingAddresses(List.of(
                new MemberProfileEntity.ShippingAddress("居家地址", "深圳市", "南山区科技园南路 88 号", true),
                new MemberProfileEntity.ShippingAddress("公司地址", "深圳市", "福田区深南大道 1001 号", false)
        ));
        return seed;
    }
}
