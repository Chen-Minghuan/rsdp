package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.FloorPlanRulesProperties;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.dto.request.PublicAiMatchSchemeRequest;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.response.PublicAiMatchAnalyzeResponse;
import com.rsdp.dto.response.PublicAiMatchSchemeResponse;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProductStyleMatchMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.util.ImageUploadValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PublicAiMatchService} 单元测试。
 *
 * <p>脱敏红线：序列化断言不含 factoryCode/factoryName/factoryPrice/totalPrice/rskuId 等；
 * P0-B 修复验证：widthMm/depthMm 不再被丢弃（透传 {@link FloorPlanMatchingService}），
 * R2 尺寸规则在 public 链路生效。</p>
 */
@ExtendWith(MockitoExtension.class)
class PublicAiMatchServiceTest {

    @Mock
    private ImageUploadValidator imageUploadValidator;

    @Mock
    private VisionService visionService;

    @Mock
    private FloorPlanMatchingService floorPlanMatchingService;

    @Mock
    private FloorPlanService floorPlanService;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @InjectMocks
    private PublicAiMatchService publicAiMatchService;

    // 尺寸解析用例已随公共方法迁移至 com.rsdp.util.DimensionsTest

    // ---------- analyze ----------

    @Test
    void analyze_shouldMapRoomsWithConfidenceAndArea() {
        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setScaleText("1:50");
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", "4200×3800", 0.1, 0.2, 0.4, 0.3),
            new FloorPlanDetectResult.Room("bedroom", "主卧", null, 0.5, 0.2, 0.3, 0.3)
        ));
        when(visionService.detectFloorPlanRooms(any(byte[].class), any())).thenReturn(detected);

        MockMultipartFile file = new MockMultipartFile(
            "file", "plan.jpg", "image/jpeg", "fake-plan".getBytes());

        PublicAiMatchAnalyzeResponse response = publicAiMatchService.analyze(file, "三室两厅");

        verify(imageUploadValidator).validate(file, 10L * 1024 * 1024);
        assertThat(response.getRooms()).hasSize(2);

        PublicAiMatchAnalyzeResponse.RoomItem living = response.getRooms().get(0);
        assertThat(living.getRoomType()).isEqualTo("living_room");
        assertThat(living.getRoomName()).isEqualTo("客厅");
        assertThat(living.getWidthMm()).isEqualTo(4200);
        assertThat(living.getDepthMm()).isEqualTo(3800);
        assertThat(living.getAreaM2()).isEqualByComparingTo(new BigDecimal("15.96"));
        assertThat(living.getConfidence()).isEqualTo("high");

        PublicAiMatchAnalyzeResponse.RoomItem bedroom = response.getRooms().get(1);
        assertThat(bedroom.getRoomName()).isEqualTo("卧室");
        assertThat(bedroom.getWidthMm()).isNull();
        assertThat(bedroom.getDepthMm()).isNull();
        assertThat(bedroom.getAreaM2()).isNull();
        assertThat(bedroom.getConfidence()).isEqualTo("low");
    }

    @Test
    void analyze_shouldPersistPublicAnalysisAndReturnAnalysisId() {
        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", "4200×3800", 0.1, 0.2, 0.4, 0.3)));
        when(visionService.detectFloorPlanRooms(any(byte[].class), any())).thenReturn(detected);
        when(floorPlanService.savePublicAnalysis(any(byte[].class), any(), any()))
            .thenReturn("FPA-PUB01");

        MockMultipartFile file = new MockMultipartFile(
            "file", "plan.jpg", "image/jpeg", "fake-plan".getBytes());

        PublicAiMatchAnalyzeResponse response = publicAiMatchService.analyze(file, null);

        // 官网匿名分析落库（v3.0 §4.6 策略 B）：响应追加 analysisId
        assertThat(response.getAnalysisId()).isEqualTo("FPA-PUB01");
        assertThat(response.getRooms()).hasSize(1);
        verify(floorPlanService).savePublicAnalysis(
            any(byte[].class),
            org.mockito.ArgumentMatchers.eq("plan.jpg"),
            org.mockito.ArgumentMatchers.same(detected));
    }

    @Test
    void analyze_persistFails_shouldDegradeWithoutAnalysisId() {
        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setRooms(List.of(
            new FloorPlanDetectResult.Room("living_room", "客厅", "4200×3800", 0.1, 0.2, 0.4, 0.3)));
        when(visionService.detectFloorPlanRooms(any(byte[].class), any())).thenReturn(detected);
        when(floorPlanService.savePublicAnalysis(any(byte[].class), any(), any()))
            .thenThrow(new RuntimeException("DB 不可用"));

        MockMultipartFile file = new MockMultipartFile(
            "file", "plan.jpg", "image/jpeg", "fake-plan".getBytes());

        PublicAiMatchAnalyzeResponse response = publicAiMatchService.analyze(file, null);

        // 落库失败不阻断公开接口：识别结果照常返回，analysisId 降级为 null
        assertThat(response.getAnalysisId()).isNull();
        assertThat(response.getRooms()).hasSize(1);
        assertThat(response.getRooms().get(0).getWidthMm()).isEqualTo(4200);
    }

    // ---------- generateScheme ----------

    @Test
    void generateScheme_shouldSanitizeFactoryFieldsAndSumRetailPrice() throws Exception {
        // 内部方案项带敏感工厂字段，公开响应必须全部丢弃
        SchemeItemResponse item1 = new SchemeItemResponse();
        item1.setRspuId("RSPU-001");
        item1.setFactoryCode("A004");
        item1.setFactoryName("某工厂");
        item1.setFactoryPrice(new BigDecimal("1200.00"));
        SchemeItemResponse item2 = new SchemeItemResponse();
        item2.setRspuId("RSPU-002");
        item2.setFactoryCode("A005");
        item2.setFactoryPrice(new BigDecimal("800.00"));

        RoomSchemeResponse scheme = new RoomSchemeResponse();
        scheme.setRoomType("LIVING");
        scheme.setTotalPrice(new BigDecimal("2000.00"));
        scheme.setReasoning("现代简约客厅搭配");
        scheme.setItems(List.of(item1, item2));
        when(floorPlanMatchingService.matchRoomScheme(isNull(), isNull(), eq("MC"), any()))
            .thenReturn(scheme);

        RspuMaster rspu1 = new RspuMaster();
        rspu1.setRspuId("RSPU-001");
        rspu1.setProductName("云朵沙发");
        rspu1.setCategoryPath("家具/沙发");
        rspu1.setPositioningLabel("现代简约");
        rspu1.setRetailPrice(new BigDecimal("3999.00"));
        RspuMaster rspu2 = new RspuMaster();
        rspu2.setRspuId("RSPU-002");
        rspu2.setProductName("岩板茶几");
        rspu2.setCategoryPath("家具/茶几");
        rspu2.setPositioningLabel("现代简约");
        rspu2.setRetailPrice(new BigDecimal("1299.00"));
        when(rspuMapper.selectBatchIds(anyList())).thenReturn(List.of(rspu1, rspu2));

        ImageAssets image = new ImageAssets();
        image.setRspuId("RSPU-001");
        image.setImageId("IMG-1");
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(image));

        PublicAiMatchSchemeRequest request = new PublicAiMatchSchemeRequest();
        request.setStylePreference("MC");
        PublicAiMatchSchemeResponse response = publicAiMatchService.generateScheme(request);

        // 缺省预算按 999999 装配给匹配入口
        verify(floorPlanMatchingService).matchRoomScheme(
            isNull(), isNull(), eq("MC"), eq(new BigDecimal("999999")));

        assertThat(response.getReasoning()).isEqualTo("现代简约客厅搭配");
        assertThat(response.getTotalRetailPrice()).isEqualByComparingTo(new BigDecimal("5298.00"));
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getRspuId()).isEqualTo("RSPU-001");
        assertThat(response.getItems().get(0).getProductName()).isEqualTo("云朵沙发");
        assertThat(response.getItems().get(0).getRetailPrice())
            .isEqualByComparingTo(new BigDecimal("3999.00"));
        assertThat(response.getItems().get(0).getPrimaryImageUrl()).isEqualTo("/api/v1/images/IMG-1");
        assertThat(response.getItems().get(1).getPrimaryImageUrl()).isNull();

        // 脱敏确认：序列化后的 JSON 不得出现任何工厂/出厂价字段
        String json = new ObjectMapper().writeValueAsString(response);
        assertThat(json).doesNotContain("factoryCode", "factoryName", "factoryPrice",
            "factorySku", "totalPrice", "rskuId", "subtotal", "moq", "leadTimeDays");
    }

    @Test
    void generateScheme_withDimensions_shouldForwardDimensionsToMatchingService() {
        RoomSchemeResponse scheme = new RoomSchemeResponse();
        scheme.setReasoning("ok");
        scheme.setItems(List.of());
        when(floorPlanMatchingService.matchRoomScheme(eq(4200), eq(3800), eq("WJ"), any()))
            .thenReturn(scheme);

        PublicAiMatchSchemeRequest request = new PublicAiMatchSchemeRequest();
        request.setStylePreference("WJ");
        request.setWidthMm(4200);
        request.setDepthMm(3800);
        request.setBudgetLimit(new BigDecimal("30000"));

        publicAiMatchService.generateScheme(request);

        // 尺寸不再被丢弃：widthMm/depthMm 透传双端唯一匹配出口（规则引擎入口）
        verify(floorPlanMatchingService).matchRoomScheme(
            4200, 3800, "WJ", new BigDecimal("30000"));
    }

    @Test
    void generateScheme_withDimensions_r2ShouldTakeEffectInPublicChain() {
        // 真实 FloorPlanMatchingService（真实规则引擎 + mock 数据层）串起 public 链路：
        // 5000×4200 = 21㎡（大客厅），R2 上限 min(5000×0.75, 5000-600) = 3750，3800 沙发必须被剔除
        FloorPlanAnalysisMapper analysisMapper = org.mockito.Mockito.mock(FloorPlanAnalysisMapper.class);
        FloorPlanRoomMapper roomMapper = org.mockito.Mockito.mock(FloorPlanRoomMapper.class);
        RspuVariantMapper variantMapper = org.mockito.Mockito.mock(RspuVariantMapper.class);
        RskuSupplyMapper rskuSupplyMapper = org.mockito.Mockito.mock(RskuSupplyMapper.class);
        ProductStyleMatchMapper styleMatchMapper = org.mockito.Mockito.mock(ProductStyleMatchMapper.class);
        SchemeMapper schemeMapper = org.mockito.Mockito.mock(SchemeMapper.class);
        AiMatchingService aiMatchingService = org.mockito.Mockito.mock(AiMatchingService.class);
        SchemeService schemeService = org.mockito.Mockito.mock(SchemeService.class);
        DataScopeHelper dataScopeHelper = org.mockito.Mockito.mock(DataScopeHelper.class);

        FloorPlanMatchingService realMatchingService = new FloorPlanMatchingService(
            analysisMapper, roomMapper, rspuMapper, variantMapper, rskuSupplyMapper,
            styleMatchMapper, schemeMapper, aiMatchingService, schemeService,
            dataScopeHelper, new FloorPlanRulesProperties(), new ObjectMapper());
        PublicAiMatchService service = new PublicAiMatchService(
            imageUploadValidator, visionService, realMatchingService, floorPlanService,
            rspuMapper, imageAssetsMapper);

        RspuMaster oversize = buildSofa("RSPU-BIG");
        RspuMaster fit = buildSofa("RSPU-FIT");
        when(rspuMapper.selectList(any())).thenReturn(List.of(oversize, fit));
        when(rspuMapper.selectBatchIds(anyList())).thenReturn(List.of(fit));
        when(variantMapper.selectList(any())).thenReturn(List.of(
            buildVariant("RSPU-BIG", 3800, 1000),
            buildVariant("RSPU-FIT", 3000, 1000)));
        when(rskuSupplyMapper.selectCapableByRspuIds(anyList())).thenReturn(List.of(
            buildRsku("RSPU-BIG"), buildRsku("RSPU-FIT")));
        lenient().when(dataScopeHelper.canAccessFactory(any())).thenReturn(true);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        // LLM 终审原样选中规则引擎给出的候选
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class), anyList()))
            .thenAnswer(invocation -> {
                List<RspuMaster> candidates = invocation.getArgument(1);
                List<SchemeItemResponse> items = candidates.stream().map(rspu -> {
                    SchemeItemResponse item = new SchemeItemResponse();
                    item.setRspuId(rspu.getRspuId());
                    return item;
                }).toList();
                RoomSchemeResponse scheme = new RoomSchemeResponse();
                scheme.setReasoning("风格统一");
                scheme.setItems(items);
                return scheme;
            });

        PublicAiMatchSchemeRequest request = new PublicAiMatchSchemeRequest();
        request.setWidthMm(5000);
        request.setDepthMm(4200);

        PublicAiMatchSchemeResponse response = service.generateScheme(request);

        // R2 在 public 链路生效：放不下的 3800 沙发不出现在官网推荐结果中
        assertThat(response.getItems())
            .extracting(PublicAiMatchSchemeResponse.Item::getRspuId)
            .containsExactly("RSPU-FIT");
    }

    // ---------- 辅助 ----------

    private RspuMaster buildSofa(String rspuId) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode("SF");
        rspu.setStatus("active");
        rspu.setRetailPrice(new BigDecimal("3999.00"));
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
}
