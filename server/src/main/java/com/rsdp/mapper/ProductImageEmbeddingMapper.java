package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.ProductImageEmbedding;
import com.rsdp.service.vector.ExistingVector;
import com.rsdp.service.vector.VectorHit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 图片向量表 Mapper：向量检索（余弦距离 {@code <=>}）、行锁、幂等 upsert 与批量删除。
 *
 * <p>检索固定过滤：当前编码配置、图片内容版本一致、图片/产品未删除；
 * {@code activeOnly} 映射历史 Chroma where 的 status=active 语义（疑似同款检测传 false，
 * 不带状态过滤）。所有 SQL 均为参数绑定，排序使用原始余弦距离升序。</p>
 */
@Mapper
public interface ProductImageEmbeddingMapper extends BaseMapper<ProductImageEmbedding> {

    String VECTOR_TYPE_HANDLER = "com.rsdp.config.typehandler.VectorTypeHandler";

    /**
     * 相似图片检索：返回图片 ID、所属 RSPU 与原始余弦距离（升序，HNSW 索引）。
     *
     * @param queryVector  查询向量
     * @param limit        返回上限
     * @param profileId    编码配置标识
     * @param categoryCode 品类过滤（可空）
     * @param activeOnly   true=仅在售产品（检索语义）；false=不限状态（同款检测语义）
     * @return 命中列表（imageId, rspuId, distance）
     */
    @Select("<script>"
        + "SELECT e.image_id AS imageId, i.rspu_id AS rspuId, "
        + "e.embedding &lt;=> #{queryVector, typeHandler=" + VECTOR_TYPE_HANDLER + "} AS distance "
        + "FROM product_image_embedding e "
        + "JOIN image_assets i ON i.image_id = e.image_id "
        + "JOIN rspu_master r ON r.rspu_id = i.rspu_id "
        + "WHERE e.profile_id = #{profileId} "
        + "AND e.source_revision = i.content_revision "
        + "AND i.deleted_at IS NULL AND r.deleted_at IS NULL "
        + "<if test='activeOnly'>AND r.status = 'active' </if>"
        + "<if test=\"categoryCode != null and categoryCode != ''\">AND r.category_code = #{categoryCode} </if>"
        + "ORDER BY e.embedding &lt;=> #{queryVector, typeHandler=" + VECTOR_TYPE_HANDLER + "} "
        + "LIMIT #{limit}"
        + "</script>")
    List<VectorHit> search(@Param("queryVector") float[] queryVector,
                           @Param("limit") int limit,
                           @Param("profileId") String profileId,
                           @Param("categoryCode") String categoryCode,
                           @Param("activeOnly") boolean activeOnly);

    /**
     * 锁定图片行（须在事务内调用），供写入前版本/删除状态核验。
     *
     * @param imageId 图片 ID
     * @return 图片行（不存在返回 null）
     */
    @Select("SELECT image_id, rspu_id, content_revision, deleted_at FROM image_assets "
        + "WHERE image_id = #{imageId} FOR UPDATE")
    ImageAssets lockById(@Param("imageId") String imageId);

    /**
     * 幂等写入：同 image_id 冲突时全量覆盖（含版本与输入哈希）。
     *
     * @param row 向量行
     * @return 影响行数
     */
    @Insert("INSERT INTO product_image_embedding "
        + "(image_id, profile_id, source_revision, input_hash, embedding, created_at, updated_at) "
        + "VALUES (#{imageId}, #{profileId}, #{sourceRevision}, #{inputHash}, "
        + "#{embedding, typeHandler=" + VECTOR_TYPE_HANDLER + "}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
        + "ON CONFLICT (image_id) DO UPDATE SET profile_id = EXCLUDED.profile_id, "
        + "source_revision = EXCLUDED.source_revision, input_hash = EXCLUDED.input_hash, "
        + "embedding = EXCLUDED.embedding, updated_at = CURRENT_TIMESTAMP")
    int upsert(ProductImageEmbedding row);

    /**
     * 批量查询已存在向量的编码配置标识与内容版本。
     *
     * @param imageIds 图片 ID 列表
     * @return 已存在向量（imageId, profileId, sourceRevision）
     */
    @Select("<script>"
        + "SELECT image_id AS imageId, profile_id AS profileId, source_revision AS sourceRevision "
        + "FROM product_image_embedding WHERE image_id IN "
        + "<foreach collection='imageIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<ExistingVector> findExisting(@Param("imageIds") List<String> imageIds);

    /**
     * 按图片 ID 幂等删除。
     *
     * @param imageIds 图片 ID 列表
     * @return 删除行数
     */
    @Delete("<script>"
        + "DELETE FROM product_image_embedding WHERE image_id IN "
        + "<foreach collection='imageIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int deleteByImageIds(@Param("imageIds") List<String> imageIds);

    /**
     * 按 RSPU 删除其全部图片向量（RSPU 删除联动）。
     *
     * @param rspuIds RSPU ID 列表
     * @return 删除行数
     */
    @Delete("<script>"
        + "DELETE FROM product_image_embedding WHERE image_id IN "
        + "(SELECT image_id FROM image_assets WHERE rspu_id IN "
        + "<foreach collection='rspuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)"
        + "</script>")
    int deleteByRspuIds(@Param("rspuIds") List<String> rspuIds);
}
