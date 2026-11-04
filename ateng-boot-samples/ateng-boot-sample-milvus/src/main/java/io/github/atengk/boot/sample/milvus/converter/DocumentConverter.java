package io.github.atengk.boot.sample.milvus.converter;

import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.sample.milvus.dto.DocumentInsertDto;
import io.github.atengk.boot.sample.milvus.model.SampleDocumentEntity;
import io.github.atengk.boot.sample.milvus.vo.DocumentHitVo;
import io.github.atengk.boot.sample.milvus.vo.DocumentVo;

/**
 * 样例工程文档模型与传输对象转换器
 * 负责在 Controller 传输边界与持久层实体之间执行解耦映射
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class DocumentConverter {

    private DocumentConverter() {
    }

    /**
     * 将插入请求 DTO 映射为 Milvus 实体
     *
     * @param dto 插入请求 DTO
     * @return 实体对象
     */
    public static SampleDocumentEntity toEntity(DocumentInsertDto dto) {
        if (dto == null) {
            return null;
        }
        return SampleDocumentEntity.builder()
                .id(dto.getId())
                .category(dto.getCategory())
                .tenantId(dto.getTenantId())
                .title(dto.getTitle())
                .content(dto.getContent())
                .vector(dto.getVector())
                .build();
    }

    /**
     * 将 Milvus 实体转换为对外暴露的视图对象 (VO)
     *
     * @param entity 实体对象
     * @return 视图对象
     */
    public static DocumentVo toVo(SampleDocumentEntity entity) {
        if (entity == null) {
            return null;
        }
        return DocumentVo.builder()
                .id(entity.getId())
                .category(entity.getCategory())
                .tenantId(entity.getTenantId())
                .title(entity.getTitle())
                .content(entity.getContent())
                .vector(entity.getVector())
                .build();
    }

    /**
     * 将检索命中结果包装为统一的 DocumentHitVo
     *
     * @param hit 检索命中项
     * @return 命中项视图对象
     */
    public static DocumentHitVo toHitVo(MilvusHit<SampleDocumentEntity> hit) {
        if (hit == null) {
            return null;
        }
        return DocumentHitVo.builder()
                .id(hit.getId())
                .score(hit.getScore())
                .document(toVo(hit.getEntity()))
                .build();
    }
}
