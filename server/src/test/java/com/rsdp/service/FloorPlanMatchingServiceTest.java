package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.FloorPlanRulesProperties;
import com.rsdp.dto.request.FloorPlanSchemeRequest;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.request.SchemeItemRequest;
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

    // ---------- 沙发墙朝向（v3.0 §8 P1） ----------

    @Test
    void matchRoomScheme_depthSofaWall_shouldApplyDepthWallCap() {
        // 4200×5000 = 21㎡（大客厅）：默认 width 朝向 R2 上限 3150；depth 朝向上限 3750，
        // 3200 的沙发仅在 depth 朝向下保留
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

        RoomSchemeResponse matched = new RoomSchemeResponse();
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class), anyList()))
            .thenReturn(matched);

        floorPlanMatchingService.matchRoomScheme(
            4200, 5000, null, new BigDecimal("30000"), "depth");

        ArgumentCaptor<List<RspuMaster>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiMatchingService).generateRoomScheme(any(RoomSchemeRequest.class), candidatesCaptor.capture());
        assertThat(candidatesCaptor.getValue())
            .extracting(RspuMaster::getRspuId).containsExactly("RSPU-BIG", "RSPU-FIT");
    }

    @Test
    void matchRoomScheme_invalidSofaWall_shouldThrowBadRequest() {
        assertThatThrownBy(() -> floorPlanMatchingService.matchRoomScheme(
            4200, 5000, null, new BigDecimal("30000"), "diagonal"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("沙发墙朝向仅支持");
        verify(rspuMapper, never()).selectList(any());
    }

    @Test
    void generateSchemeForAnalysis_sofaWallDepth_shouldPassThroughToRules() {
        // 管理端接口 4：sofaWall=depth 透传到规则引擎（3200 沙发在 depth 朝向下不被 R2 剔除）
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
        matchedItem.setRspuId("RSPU-BIG");
        matchedItem.setRskuId("RSKU-RSPU-BIG");
        RoomSchemeResponse matched = new RoomSchemeResponse();
        matched.setItems(List.of(matchedItem));
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class), anyList()))
            .thenReturn(matched);

        SchemeResponse created = new SchemeResponse();
        created.setSchemeId("SCH-1");
        when(schemeService.createScheme(any())).thenReturn(created);
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCH-1");
        when(schemeMapper.selectById("SCH-1")).thenReturn(scheme);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomId("FPR-1");
        request.setSofaWall("depth");
        String schemeId = floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice");

        assertThat(schemeId).isEqualTo("SCH-1");
        // 朝向透传生效：depth 朝向下 3200 的沙发通过 R2，进入 LLM 终审候选
        ArgumentCaptor<List<RspuMaster>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiMatchingService).generateRoomScheme(any(RoomSchemeRequest.class), candidatesCaptor.capture());
        assertThat(candidatesCaptor.getValue())
            .extracting(RspuMaster::getRspuId).containsExactly("RSPU-BIG", "RSPU-FIT");
    }

    @Test
    void generateSchemeForAnalysis_noSofaWall_shouldBehaveLikeWidth() {
        // 不传朝向：行为与默认 width 完全一致（3200 > 3150 的沙发仍被 R2 剔除）
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, "alice");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom room = buildRoom("FPR-1", "FPA-1", 4200, 5000);
        when(roomMapper.selectById("FPR-1")).thenReturn(room);

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

        SchemeItemResponse matchedItem = new SchemeItemResponse();
        matchedItem.setRspuId("RSPU-FIT");
        matchedItem.setRskuId("RSKU-RSPU-FIT");
        RoomSchemeResponse matched = new RoomSchemeResponse();
        matched.setItems(List.of(matchedItem));
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class), anyList()))
            .thenReturn(matched);

        SchemeResponse created = new SchemeResponse();
        created.setSchemeId("SCH-1");
        when(schemeService.createScheme(any())).thenReturn(created);
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCH-1");
        when(schemeMapper.selectById("SCH-1")).thenReturn(scheme);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomId("FPR-1");
        String schemeId = floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice");

        assertThat(schemeId).isEqualTo("SCH-1");
        ArgumentCaptor<List<RspuMaster>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiMatchingService).generateRoomScheme(any(RoomSchemeRequest.class), candidatesCaptor.capture());
        assertThat(candidatesCaptor.getValue())
            .extracting(RspuMaster::getRspuId).containsExactly("RSPU-FIT");
    }

    // ---------- 多空间批量搭配（v3.0 §8 P2） ----------

    @Test
    void generateSchemeForAnalysis_multiRoom_shouldMergeIntoOneScheme() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, "alice");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom living = buildRoom("FPR-L", "FPA-1", "LIVING_ROOM", 4200, 5000);
        FloorPlanRoom dining = buildRoom("FPR-D", "FPA-1", "DINING_ROOM", 3000, 2800);
        when(roomMapper.selectById("FPR-L")).thenReturn(living);
        when(roomMapper.selectById("FPR-D")).thenReturn(dining);

        // 两次候选装配：第一次客厅（SF），第二次餐厅（DT）
        RspuMaster sofa = buildProduct("RSPU-SF", "SF");
        RspuMaster table = buildProduct("RSPU-DT", "DT");
        when(rspuMapper.selectList(any())).thenReturn(List.of(sofa), List.of(table));
        when(rspuMapper.selectBatchIds(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return List.of(sofa, table).stream().filter(r -> ids.contains(r.getRspuId())).toList();
        });
        when(rspuVariantMapper.selectList(any())).thenReturn(
            List.of(buildVariant("RSPU-SF", 3000, 1000)),
            List.of(buildVariant("RSPU-DT", 2200, 1100)));
        when(rskuSupplyMapper.selectCapableByRspuIds(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return ids.stream().map(this::buildRsku).toList();
        });
        lenient().when(dataScopeHelper.canAccessFactory(any())).thenReturn(true);

        // LLM 终审（每空间独立调用）：原样选中规则引擎候选
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class), anyList()))
            .thenAnswer(invocation -> {
                List<RspuMaster> candidates = invocation.getArgument(1);
                RoomSchemeResponse response = new RoomSchemeResponse();
                response.setItems(candidates.stream().map(r -> {
                    SchemeItemResponse item = new SchemeItemResponse();
                    item.setRspuId(r.getRspuId());
                    item.setRskuId("RSKU-" + r.getRspuId());
                    return item;
                }).toList());
                return response;
            });

        SchemeResponse created = new SchemeResponse();
        created.setSchemeId("SCH-M1");
        when(schemeService.createScheme(any())).thenReturn(created);
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCH-M1");
        when(schemeMapper.selectById("SCH-M1")).thenReturn(scheme);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomIds(List.of("FPR-L", "FPR-D"));
        String schemeId = floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice");

        assertThat(schemeId).isEqualTo("SCH-M1");
        // 每空间独立 LLM 终审（两次调用，候选各自 ≤30）
        ArgumentCaptor<RoomSchemeRequest> requestCaptor = ArgumentCaptor.forClass(RoomSchemeRequest.class);
        verify(aiMatchingService, org.mockito.Mockito.times(2))
            .generateRoomScheme(requestCaptor.capture(), anyList());
        assertThat(requestCaptor.getAllValues())
            .extracting(RoomSchemeRequest::getRoomType)
            .containsExactly("LIVING_ROOM", "DINING_ROOM");

        // 合并落一个 scheme：名称「多空间搭配方案-」，明细含两空间选品
        ArgumentCaptor<SchemeCreateRequest> createCaptor = ArgumentCaptor.forClass(SchemeCreateRequest.class);
        verify(schemeService).createScheme(createCaptor.capture());
        assertThat(createCaptor.getValue().getSchemeName()).startsWith("多空间搭配方案-");
        assertThat(createCaptor.getValue().getItems())
            .extracting(SchemeItemRequest::getRspuId)
            .containsExactly("RSPU-SF", "RSPU-DT");
        verify(schemeMapper).updateById(org.mockito.ArgumentMatchers
            .argThat((Scheme s) -> "FPA-1".equals(s.getAnalysisId())));
    }

    @Test
    void generateSchemeForAnalysis_multiRoom_llmFailureShouldFallbackToRuleForThatRoom() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, "alice");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom living = buildRoom("FPR-L", "FPA-1", "LIVING_ROOM", 4200, 5000);
        FloorPlanRoom dining = buildRoom("FPR-D", "FPA-1", "DINING_ROOM", 3000, 2800);
        when(roomMapper.selectById("FPR-L")).thenReturn(living);
        when(roomMapper.selectById("FPR-D")).thenReturn(dining);

        RspuMaster sofa = buildProduct("RSPU-SF", "SF");
        RspuMaster table = buildProduct("RSPU-DT", "DT");
        when(rspuMapper.selectList(any())).thenReturn(List.of(sofa), List.of(table));
        when(rspuMapper.selectBatchIds(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return List.of(sofa, table).stream().filter(r -> ids.contains(r.getRspuId())).toList();
        });
        when(rspuVariantMapper.selectList(any())).thenReturn(
            List.of(buildVariant("RSPU-SF", 3000, 1000)),
            List.of(buildVariant("RSPU-DT", 2200, 1100)));
        when(rskuSupplyMapper.selectCapableByRspuIds(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return ids.stream().map(this::buildRsku).toList();
        });
        lenient().when(dataScopeHelper.canAccessFactory(any())).thenReturn(true);

        // 客厅 LLM 正常；餐厅 LLM 抛异常 → 该空间规则兜底
        SchemeItemResponse sofaItem = new SchemeItemResponse();
        sofaItem.setRspuId("RSPU-SF");
        sofaItem.setRskuId("RSKU-RSPU-SF");
        RoomSchemeResponse livingResponse = new RoomSchemeResponse();
        livingResponse.setItems(List.of(sofaItem));
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class), anyList()))
            .thenReturn(livingResponse)
            .thenThrow(new RuntimeException("LLM 超时"));
        SchemeItemResponse tableItem = new SchemeItemResponse();
        tableItem.setRspuId("RSPU-DT");
        tableItem.setRskuId("RSKU-RSPU-DT");
        RoomSchemeResponse fallbackResponse = new RoomSchemeResponse();
        fallbackResponse.setItems(List.of(tableItem));
        when(aiMatchingService.ruleFallbackScheme(any(RoomSchemeRequest.class), anyList()))
            .thenReturn(fallbackResponse);

        SchemeResponse created = new SchemeResponse();
        created.setSchemeId("SCH-M2");
        when(schemeService.createScheme(any())).thenReturn(created);
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCH-M2");
        when(schemeMapper.selectById("SCH-M2")).thenReturn(scheme);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomIds(List.of("FPR-L", "FPR-D"));
        String schemeId = floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice");

        // 单空间失败不拖垮整批：方案仍落库，餐厅明细来自规则兜底
        assertThat(schemeId).isEqualTo("SCH-M2");
        ArgumentCaptor<SchemeCreateRequest> createCaptor = ArgumentCaptor.forClass(SchemeCreateRequest.class);
        verify(schemeService).createScheme(createCaptor.capture());
        assertThat(createCaptor.getValue().getItems())
            .extracting(SchemeItemRequest::getRspuId)
            .containsExactly("RSPU-SF", "RSPU-DT");
    }

    @Test
    void generateSchemeForAnalysis_multiRoom_allRoomsEmpty_shouldThrow() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, "alice");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom living = buildRoom("FPR-L", "FPA-1", "LIVING_ROOM", 4200, 5000);
        FloorPlanRoom dining = buildRoom("FPR-D", "FPA-1", "DINING_ROOM", 3000, 2800);
        when(roomMapper.selectById("FPR-L")).thenReturn(living);
        when(roomMapper.selectById("FPR-D")).thenReturn(dining);

        // 无任何候选产品（产品库为空）
        when(rspuMapper.selectList(any())).thenReturn(List.of());
        RoomSchemeResponse empty = new RoomSchemeResponse();
        empty.setItems(List.of());
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class), anyList()))
            .thenReturn(empty);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomIds(List.of("FPR-L", "FPR-D"));

        assertThatThrownBy(() -> floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无法生成方案");
        verify(schemeService, never()).createScheme(any());
    }

    @Test
    void generateSchemeForAnalysis_multiRoom_unsupportedRoomType_shouldThrow() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, "alice");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom kitchen = buildRoom("FPR-K", "FPA-1", "KITCHEN", 3000, 2800);
        when(roomMapper.selectById("FPR-K")).thenReturn(kitchen);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomIds(List.of("FPR-K"));

        assertThatThrownBy(() -> floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("暂不支持");
        verify(schemeService, never()).createScheme(any());
    }

    @Test
    void generateSchemeForAnalysis_multiRoom_roomNotBelongingToAnalysis_shouldThrow() {
        FloorPlanAnalysis analysis = buildAnalysis("FPA-1", FloorPlanService.STATUS_CONFIRMED, "alice");
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanRoom other = buildRoom("FPR-OTHER", "FPA-2", "BEDROOM", 3300, 3000);
        when(roomMapper.selectById("FPR-OTHER")).thenReturn(other);

        FloorPlanSchemeRequest request = new FloorPlanSchemeRequest();
        request.setRoomIds(List.of("FPR-OTHER"));

        assertThatThrownBy(() -> floorPlanMatchingService.generateSchemeForAnalysis("FPA-1", request, "alice"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不属于该分析");
        verify(schemeService, never()).createScheme(any());
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
        return buildProduct(rspuId, "SF");
    }

    private RspuMaster buildProduct(String rspuId, String categoryCode) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode(categoryCode);
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
        return buildRoom(roomId, analysisId, "LIVING_ROOM", widthMm, depthMm);
    }

    private FloorPlanRoom buildRoom(String roomId, String analysisId, String roomType,
                                    int widthMm, int depthMm) {
        FloorPlanRoom room = new FloorPlanRoom();
        room.setRoomId(roomId);
        room.setAnalysisId(analysisId);
        room.setRoomType(roomType);
        room.setWidthMm(widthMm);
        room.setDepthMm(depthMm);
        return room;
    }
}
