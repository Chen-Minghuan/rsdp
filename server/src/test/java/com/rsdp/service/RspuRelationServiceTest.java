package com.rsdp.service;

import com.rsdp.dto.request.RspuRelationCreateRequest;
import com.rsdp.dto.request.RspuRelationUpdateRequest;
import com.rsdp.dto.response.RspuRelationResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuRelation;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuRelationMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

/**
 * {@link RspuRelationService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RspuRelationServiceTest {

    @Mock
    private RspuRelationMapper relationMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private DataScopeHelper dataScopeHelper;

    @InjectMocks
    private RspuRelationService relationService;

    @Test
    void listByAnchor_shouldReturnActiveRelations() {
        lenient().when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);

        RspuRelation relation = new RspuRelation();
        relation.setRelationId("REL-001");
        relation.setAnchorRspuId("RSPU-BED");
        relation.setRelatedRspuId("RSPU-MATTRESS");
        relation.setRelationType("official");
        relation.setStatus("active");

        RspuMaster related = new RspuMaster();
        related.setRspuId("RSPU-MATTRESS");
        related.setCategoryPath("家具/卧室/床垫");

        when(rspuMapper.selectById("RSPU-BED")).thenReturn(new RspuMaster());
        when(relationMapper.selectList(any())).thenReturn(List.of(relation));
        when(rspuMapper.selectBatchIds(List.of("RSPU-MATTRESS"))).thenReturn(List.of(related));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        List<RspuRelationResponse> result = relationService.listByAnchor("RSPU-BED");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRelatedRspuId()).isEqualTo("RSPU-MATTRESS");
        assertThat(result.get(0).getTargetCategoryPath()).isEqualTo("家具/卧室/床垫");
    }

    @Test
    void listByRelated_shouldReturnActiveRelations() {
        RspuRelation relation = new RspuRelation();
        relation.setRelationId("REL-001");
        relation.setAnchorRspuId("RSPU-BED");
        relation.setRelatedRspuId("RSPU-MATTRESS");
        relation.setRelationType("official");
        relation.setStatus("active");

        RspuMaster anchor = new RspuMaster();
        anchor.setRspuId("RSPU-BED");
        when(rspuMapper.selectById("RSPU-MATTRESS")).thenReturn(new RspuMaster());
        when(relationMapper.selectList(any())).thenReturn(List.of(relation));
        when(rspuMapper.selectBatchIds(List.of("RSPU-BED"))).thenReturn(List.of(anchor));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        List<RspuRelationResponse> result = relationService.listByRelated("RSPU-MATTRESS");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAnchorRspuId()).isEqualTo("RSPU-BED");
    }

    @Test
    void listByAnchor_shouldFilterDeletedTargetRspu() {
        RspuRelation relation = new RspuRelation();
        relation.setRelationId("REL-001");
        relation.setAnchorRspuId("RSPU-BED");
        relation.setRelatedRspuId("RSPU-DELETED");
        relation.setRelationType("official");
        relation.setStatus("active");

        when(rspuMapper.selectById("RSPU-BED")).thenReturn(new RspuMaster());
        when(relationMapper.selectList(any())).thenReturn(List.of(relation));
        when(rspuMapper.selectBatchIds(List.of("RSPU-DELETED"))).thenReturn(List.of());

        List<RspuRelationResponse> result = relationService.listByAnchor("RSPU-BED");

        assertThat(result).isEmpty();
    }

    @Test
    void createRelation_shouldInsertWhenValid() {
        RspuRelationCreateRequest request = new RspuRelationCreateRequest();
        request.setRelatedRspuId("RSPU-MATTRESS");
        request.setRelationType("official");
        request.setReason("同厂配套");

        when(rspuMapper.selectById("RSPU-BED")).thenReturn(new RspuMaster());
        when(rspuMapper.selectById("RSPU-MATTRESS")).thenReturn(new RspuMaster());
        when(relationMapper.selectOne(any())).thenReturn(null);

        relationService.createRelation("RSPU-BED", request);

        ArgumentCaptor<RspuRelation> captor = ArgumentCaptor.forClass(RspuRelation.class);
        verify(relationMapper).insert(captor.capture());
        assertThat(captor.getValue().getAnchorRspuId()).isEqualTo("RSPU-BED");
        assertThat(captor.getValue().getRelatedRspuId()).isEqualTo("RSPU-MATTRESS");
        assertThat(captor.getValue().getRelationType()).isEqualTo("official");
        assertThat(captor.getValue().getReason()).isEqualTo("同厂配套");
    }

    @Test
    void createRelation_shouldThrowWhenSelfReference() {
        RspuRelationCreateRequest request = new RspuRelationCreateRequest();
        request.setRelatedRspuId("RSPU-BED");

        when(rspuMapper.selectById("RSPU-BED")).thenReturn(new RspuMaster());

        assertThatThrownBy(() -> relationService.createRelation("RSPU-BED", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能与自己");
    }

    @Test
    void createRelation_shouldThrowWhenDuplicate() {
        RspuRelationCreateRequest request = new RspuRelationCreateRequest();
        request.setRelatedRspuId("RSPU-MATTRESS");

        when(rspuMapper.selectById("RSPU-BED")).thenReturn(new RspuMaster());
        when(rspuMapper.selectById("RSPU-MATTRESS")).thenReturn(new RspuMaster());
        when(relationMapper.selectOne(any())).thenReturn(new RspuRelation());

        assertThatThrownBy(() -> relationService.createRelation("RSPU-BED", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已存在");
    }

    @Test
    void createRelation_shouldThrowWhenInvalidType() {
        RspuRelationCreateRequest request = new RspuRelationCreateRequest();
        request.setRelatedRspuId("RSPU-MATTRESS");
        request.setRelationType("invalid");

        when(rspuMapper.selectById("RSPU-BED")).thenReturn(new RspuMaster());
        when(rspuMapper.selectById("RSPU-MATTRESS")).thenReturn(new RspuMaster());

        assertThatThrownBy(() -> relationService.createRelation("RSPU-BED", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无效的关系类型");
    }

    @Test
    void updateRelation_shouldUpdateFields() {
        RspuRelation relation = new RspuRelation();
        relation.setRelationId("REL-001");
        relation.setAnchorRspuId("RSPU-BED");
        relation.setRelatedRspuId("RSPU-MATTRESS");
        relation.setRelationType("official");
        relation.setReason("旧说明");
        relation.setSortOrder(1);
        relation.setStatus("active");

        RspuRelationUpdateRequest request = new RspuRelationUpdateRequest();
        request.setRelationType("ai_verified");
        request.setReason("新说明");
        request.setSortOrder(2);

        when(relationMapper.selectById("REL-001")).thenReturn(relation);

        relationService.updateRelation("RSPU-BED", "REL-001", request);

        assertThat(relation.getRelationType()).isEqualTo("ai_verified");
        assertThat(relation.getReason()).isEqualTo("新说明");
        assertThat(relation.getSortOrder()).isEqualTo(2);
        verify(relationMapper).updateById(relation);
    }

    @Test
    void deleteRelation_shouldSoftDelete() {
        RspuRelation relation = new RspuRelation();
        relation.setRelationId("REL-001");
        relation.setAnchorRspuId("RSPU-BED");
        relation.setRelatedRspuId("RSPU-MATTRESS");
        relation.setStatus("active");

        when(relationMapper.selectById("REL-001")).thenReturn(relation);
        when(relationMapper.deleteById("REL-001")).thenReturn(1);

        relationService.deleteRelation("RSPU-BED", "REL-001");

        verify(relationMapper).deleteById("REL-001");
        verify(auditLogService).logDelete(eq("rspu_relation"), eq("REL-001"), any(), any());
    }

    @Test
    void deleteRelation_shouldThrowWhenAnchorMismatch() {
        RspuRelation relation = new RspuRelation();
        relation.setRelationId("REL-001");
        relation.setAnchorRspuId("RSPU-OTHER");

        when(relationMapper.selectById("REL-001")).thenReturn(relation);

        assertThatThrownBy(() -> relationService.deleteRelation("RSPU-BED", "REL-001"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void toResponse_shouldFillMinPriceAndImageUrl() {
        lenient().when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);


        RspuRelation relation = new RspuRelation();
        relation.setRelationId("REL-001");
        relation.setAnchorRspuId("RSPU-BED");
        relation.setRelatedRspuId("RSPU-MATTRESS");
        relation.setRelationType("official");
        relation.setStatus("active");

        RspuMaster related = new RspuMaster();
        related.setRspuId("RSPU-MATTRESS");
        related.setCategoryPath("家具/卧室/床垫");

        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-MATTRESS");

        RskuSupply rsku = new RskuSupply();
        rsku.setRspuId("RSPU-MATTRESS");
        rsku.setFactoryPrice(new BigDecimal("1500"));

        when(rspuMapper.selectById("RSPU-BED")).thenReturn(new RspuMaster());
        when(relationMapper.selectList(any())).thenReturn(List.of(relation));
        when(rspuMapper.selectBatchIds(List.of("RSPU-MATTRESS"))).thenReturn(List.of(related));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(image));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));

        List<RspuRelationResponse> result = relationService.listByAnchor("RSPU-BED");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetImageUrl()).isEqualTo("/api/v1/images/IMG-001");
        assertThat(result.get(0).getTargetMinPrice()).isEqualTo(new BigDecimal("1500"));
    }
}
