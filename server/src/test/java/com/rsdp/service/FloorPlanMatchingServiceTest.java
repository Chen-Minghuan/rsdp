package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.FloorPlanRulesProperties;
import com.rsdp.dto.request.FloorPlanSchemeRequest;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.Scheme;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import com.rsdp.mapper.ProductStyleMatchMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeMapper;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FloorPlanMatchingService} 单元测试（户型图链路 v3.0 §9）。
 *
 * <p>覆盖：双端一致性（管理端与官网同一输入走同一规则引擎、候选筛选结果一致）、
 * R2 在匹配链路生效、管理端落 scheme 校验（confirmed 状态机 / 空间归属 / 归属校验 /
 * scheme.analysis_id 回填）、无尺寸时退化为原 AI 选品行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class FloorPlanMatchingServiceTest {

    @Mock
    private FloorPlanAnalysisMapper analysisMapper;

    @Mock
    private FloorPlanRoomMapper roomMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RspuVariantMapper rspuVariantMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private ProductStyleMatchMapper productStyleMatchMapper;

    @Mock
    private SchemeMapper schemeMapper;

    @Mock
    private AiMatchingService aiMatchingService;

    @Mock
    private SchemeService schemeService;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    private FloorPlanRulesProperties rulesProperties = new FloorPlanRulesProperties();

    @InjectMocks
    private FloorPlanMatchingService floorPlanMatchingService;

    // ---------- 公共匹配入口 ----------

    @Test
    void matchRoomScheme_withoutDimensions_shouldDelegateToLegacyBehavior() {
        RoomSchemeResponse expected = new RoomSchemeResponse();
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class))).thenReturn(expected);

        RoomSchemeResponse response = floorPlanMatchingService.matchRoomScheme(
            null, 3800, "MC", new BigDecimal("10000"));

        assertThat(response).isSameAs(expected);
        ArgumentCaptor<RoomSchemeRequest> captor = ArgumentCaptor.forClass(RoomSchemeRequest.class);
        verify(aiMatchingService).generateRoomScheme(captor.capture());
        assertThat(captor.getValue().getRoomType()).isEqualTo("LIVING");
        assertThat(captor.getValue().getStylePreference()).isEqualTo("MC");
        // 无尺寸：不走规则引擎取数
        verify(rspuMapper, never()).selectList(any());
    }

    @Test
    void matchRoomScheme_withDimensions_r2ShouldFilterOversizeSofa() {
        // 4200×5000 = 21㎡（大客厅，无分档上限）：R2 上限 min(4200×0.75, 4200-600) = 3150
        RspuMaster oversize = buildSofa("RSPU-BIG");
        RspuMaster fit = buildSofa("RSPU-FIT");
        when(rspuMapper.selectList(any())).thenReturn(List.of(oversize, fit));
        when(rspuMapper.selectBatchIds(anyList())).thenReturn(List.of(fit));
        when(rspuVariantMapper.selectList(any())).thenReturn(List.of(
            buildVariant("RSPU-BIG", 3200, 1000),
            buildVariant("RSPU-FIT", 3000, 1000)));
        when(rskuSupplyMapper.selectCapableByRspuIds(anyList())).thenReturn(List.of(
            buildRsku("RSPU-BIG"), buildRsku("RSPU-FIT")));
        lenient().when(dataScopeHelper.canAccessFactory(any())).thenReturn(true);

        RoomSchemeResponse matched = new RoomSchemeResponse();
        matched.setReasoning("风格统一");
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class), anyList()))
            .thenReturn(matched);

        RoomSchemeResponse response = floorPlanMatchingService.matchRoomScheme(
            4200, 5000, null, new BigDecimal("30000"));

        assertThat(response).isSameAs(matched);
        ArgumentCaptor<RoomSchemeRequest> requestCaptor = ArgumentCaptor.forClass(RoomSchemeRequest.class);
        ArgumentCaptor<List<RspuMaster>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiMatchingService).generateRoomScheme(requestCaptor.capture(), candidatesCaptor.capture());
        assertThat(requestCaptor.getValue().getWidthMm()).isEqualTo(4200);
        assertThat(requestCaptor.getValue().getDepthMm()).isEqualTo(5000);
        // R2 生效：3200 > 3150 的沙发被剔除
        assertThat(candidatesCaptor.getValue())
            .extracting(RspuMaster::getRspuId).containsExactly("RSPU-FIT");
    }

    // ---------- 双端一致性 ----------

    @Test
    void bothEntries_sameDimensions_shouldProduceSameFilteredCandidates() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, "alice");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom room = buildRoom("FPR-1", "FPA-1", 4200, 5000);
        when(roomMapper.selectById("FPR-1")).thenReturn(room);

        RspuMaster oversize = buildSofa("RSPU-BIG");
        RspuMaster fit = buildSofa("RSPU-FIT");
        when(rspuMapper.selectList(any())).thenReturn(List.of(oversize, fit));
        when(rspuMapper.selectBatchIds(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return List.of(oversize, fit).stream().filter(r -> ids.contains(r.getRspuId())).toList();
        });
        when(rspuVariantMapper.selectList(any())).thenReturn(List.of(
            buildVariant("RSPU-BIG", 3200, 1000),
            buildVariant("RSPU-FIT", 3000, 1000)));
        when(rskuSupplyMapper.selectCapableByRspuIds(anyList())).thenReturn(List.of(
            buildRsku("RSPU-BIG"), buildRsku("RSPU-FIT")));
        lenient().when(dataScopeHelper.canAccessFactory(any())).thenReturn(true);

        SchemeItemResponse matchedItem = new SchemeItemResponse();
        matchedItem.setRspuId("RSPU-FIT");
        matchedItem.setRskuId("RSKU-RSPU-FIT");
        RoomSchemeResponse matched = new RoomSchemeResponse();
        matched.setReasoning("风格统一");
        matched.setItems(List.of(matchedItem));
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class), anyList()))
            .thenReturn(matched);

        SchemeResponse created = new SchemeResponse();
        created.setSchemeId("SCH-1");
        when(schemeService.createScheme(any())).thenReturn(created);
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCH-1");
        when(schemeMapper.selectById("SCH-1")).thenReturn(scheme);

        // 官网入口
        floorPlanMatchingService.matchRoomScheme(4200, 5000, null, new BigDecimal("30000"));
        // 管理端入口（同尺寸）
        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomId("FPR-1");
        request.setBudgetLimit(new BigDecimal("30000"));
        String schemeId = floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice");

        assertThat(schemeId).isEqualTo("SCH-1");

        // 两端喂给 LLM 终审的候选完全一致（同一规则引擎证明）
        ArgumentCaptor<List<RspuMaster>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiMatchingService, org.mockito.Mockito.times(2))
            .generateRoomScheme(any(RoomSchemeRequest.class), candidatesCaptor.capture());
        List<List<RspuMaster>> allCalls = candidatesCaptor.getAllValues();
        assertThat(allCalls.get(0)).extracting(RspuMaster::getRspuId)
            .isEqualTo(allCalls.get(1).stream().map(RspuMaster::getRspuId).toList());

        // 管理端落 scheme：明细来自终审结果，analysis_id 回填溯源
        ArgumentCaptor<SchemeCreateRequest> createCaptor = ArgumentCaptor.forClass(SchemeCreateRequest.class);
        verify(schemeService).createScheme(createCaptor.capture());
        assertThat(createCaptor.getValue().getRoomType()).isEqualTo("LIVING");
        assertThat(createCaptor.getValue().getItems()).hasSize(1);
        assertThat(createCaptor.getValue().getItems().get(0).getRspuId()).isEqualTo("RSPU-FIT");
        assertThat(createCaptor.getValue().getSchemeName()).startsWith("客厅方案-");

        ArgumentCaptor<Scheme> schemeCaptor = ArgumentCaptor.forClass(Scheme.class);
        verify(schemeMapper).updateById(schemeCaptor.capture());
        assertThat(schemeCaptor.getValue().getAnalysisId()).isEqualTo("FPA-1");
    }

    // ---------- 管理端接口 4 校验链 ----------

    @Test
    void generateSchemeForAnalysis_notConfirmed_shouldThrow() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_AWAITING_CONFIRM, "alice");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomId("FPR-1");

        assertThatThrownBy(() -> floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未确认");
        verify(schemeService, never()).createScheme(any());
    }

    @Test
    void generateSchemeForAnalysis_roomNotBelongingToAnalysis_shouldThrow() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, "alice");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom room = buildRoom("FPR-OTHER", "FPA-2", 4200, 3800);
        when(roomMapper.selectById("FPR-OTHER")).thenReturn(room);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomId("FPR-OTHER");

        assertThatThrownBy(() -> floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice"))
            .isInstanceOf(BusinessException.class);
        verify(schemeService, never()).createScheme(any());
    }

    @Test
    void generateSchemeForAnalysis_nonOwner_shouldThrowNotFound() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, "bob");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomId("FPR-1");

        assertThatThrownBy(() -> floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice"))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(schemeService, never()).createScheme(any());
    }

    // ---------- 辅助 ----------

    private RspuMaster buildSofa(String rspuId) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode("SF");
        rspu.setStatus("active");
        return rspu;
    }

    private RspuVariant buildVariant(String rspuId, int widthMm, int depthMm) {
        RspuVariant variant = new RspuVariant();
        variant.setVariantId("VAR-" + rspuId);
        variant.setRspuId(rspuId);
        variant.setDimensions("{\"w\":" + widthMm + ",\"d\":" + depthMm + ",\"unit\":\"mm\"}");
        return variant;
    }

    private RskuSupply buildRsku(String rspuId) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-" + rspuId);
        rsku.setRspuId(rspuId);
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        return rsku;
    }

    private FloorPlanAnalysis buildAnalysis(String analysisId, String status, String createdBy) {
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setStatus(status);
        analysis.setSource("admin");
        analysis.setCreatedBy(createdBy);
        return analysis;
    }

    private FloorPlanRoom buildRoom(String roomId, String analysisId, int widthMm, int depthMm) {
        FloorPlanRoom room = new FloorPlanRoom();
        room.setRoomId(roomId);
        room.setAnalysisId(analysisId);
        room.setRoomType("LIVING_ROOM");
        room.setWidthMm(widthMm);
        room.setDepthMm(depthMm);
        return room;
    }
}
