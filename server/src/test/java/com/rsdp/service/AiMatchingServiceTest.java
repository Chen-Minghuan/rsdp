package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiSchemeRecommendation;
import com.rsdp.dto.request.AnchorMatchingRequest;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.response.AnchorMatchingResponse;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScope;
import com.rsdp.security.datascope.DataScopeContext;
import com.rsdp.security.datascope.DataScopeHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiMatchingService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AiMatchingServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private DictService dictService;

    @Mock
    private VisionService visionService;

    @Mock
    private FactoryService factoryService;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Mock
    private PricingService pricingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AiMatchingService aiMatchingService;

    @BeforeEach
    void setUp() throws Exception {
        Field field = AiMatchingService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(aiMatchingService, objectMapper);
        lenient().when(dataScopeHelper.canAccessFactory(any())).thenReturn(true);
        lenient().when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void generateRoomScheme_shouldReturnScheme() throws Exception {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");
        rspu.setColorPrimaryName("原木色");
        rspu.setMaterialTags("[\"实木\"]");
        rspu.setSceneTags("[\"客厅\"]");

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setProductLevel("S");
        rsku.setLeadTimeDays(25);

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");

        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(rskuSupplyMapper.selectCapableByRspuIds(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(image));
        when(dictService.listByType("room_type")).thenReturn(List.of(createDict("LIVING_ROOM", "客厅")));
        when(dictService.listByType("style")).thenReturn(List.of(createDict("MC", "中古风")));

        AiSchemeRecommendation rec = new AiSchemeRecommendation();
        rec.setRspuIds(List.of("RSPU-001"));
        rec.setReasoning("风格统一");
        when(visionService.chatText(any(), any())).thenReturn(objectMapper.writeValueAsString(rec));

        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType("LIVING_ROOM");
        request.setBudgetLimit(new BigDecimal("10000"));
        request.setStylePreference("MC");

        RoomSchemeResponse response = aiMatchingService.generateRoomScheme(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getTotalPrice()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(response.getReasoning()).isEqualTo("风格统一");
    }

    @Test
    void generateRoomScheme_shouldHandleInvalidAiResponse() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(dictService.listByType("room_type")).thenReturn(List.of(createDict("LIVING_ROOM", "客厅")));
        when(dictService.listByType("style")).thenReturn(List.of(createDict("MC", "中古风")));
        when(visionService.chatText(any(), any())).thenReturn("invalid json");

        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType("LIVING_ROOM");
        request.setBudgetLimit(new BigDecimal("10000"));
        request.setStylePreference("MC");

        RoomSchemeResponse response = aiMatchingService.generateRoomScheme(request);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getReasoning()).contains("格式异常");
    }

    @Test
    void recommendByAnchor_shouldReturnRecommendations() throws Exception {
        RspuMaster anchor = new RspuMaster();
        anchor.setRspuId("RSPU-ANCHOR");
        anchor.setCategoryCode("SF");
        anchor.setPositioningLabel("中古风");
        anchor.setColorPrimaryName("原木色");

        RspuMaster candidate = new RspuMaster();
        candidate.setRspuId("RSPU-001");
        candidate.setCategoryCode("DT");
        candidate.setPositioningLabel("中古风");
        candidate.setColorPrimaryName("原木色");

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("1500"));
        rsku.setProductLevel("S");
        rsku.setLeadTimeDays(20);

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-001");
        image.setRspuId("RSPU-001");

        when(rspuMapper.selectById("RSPU-ANCHOR")).thenReturn(anchor);
        when(rspuMapper.selectList(any())).thenReturn(List.of(candidate));
        when(rskuSupplyMapper.selectCapableByRspuIds(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(image));

        AiSchemeRecommendation rec = new AiSchemeRecommendation();
        rec.setRspuIds(List.of("RSPU-001"));
        rec.setReasoning("风格与颜色统一");
        when(visionService.chatText(any(), any())).thenReturn(objectMapper.writeValueAsString(rec));

        AnchorMatchingRequest request = new AnchorMatchingRequest();
        request.setExistingRspuId("RSPU-ANCHOR");
        request.setTargetCategoryCode("DT");

        AnchorMatchingResponse response = aiMatchingService.recommendByAnchor(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getRspuId()).isEqualTo("RSPU-001");
        assertThat(response.getReasoning()).isEqualTo("风格与颜色统一");
    }

    @Test
    void generateRoomScheme_shouldSkipRskuWhenFactoryNotCapable() throws Exception {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");
        rspu.setColorPrimaryName("原木色");
        rspu.setMaterialTags("[\"实木\"]");
        rspu.setSceneTags("[\"客厅\"]");

        RskuSupply cheapRsku = new RskuSupply();
        cheapRsku.setRskuId("RSKU-001");
        cheapRsku.setRspuId("RSPU-001");
        cheapRsku.setFactoryCode("F001");
        cheapRsku.setFactoryPrice(new BigDecimal("1500"));
        cheapRsku.setProductLevel("S");
        cheapRsku.setLeadTimeDays(25);

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(rskuSupplyMapper.selectCapableByRspuIds(any())).thenReturn(List.of());
        when(dictService.listByType("room_type")).thenReturn(List.of(createDict("LIVING_ROOM", "客厅")));
        when(dictService.listByType("style")).thenReturn(List.of(createDict("MC", "中古风")));

        AiSchemeRecommendation rec = new AiSchemeRecommendation();
        rec.setRspuIds(List.of("RSPU-001"));
        rec.setReasoning("风格统一");
        when(visionService.chatText(any(), any())).thenReturn(objectMapper.writeValueAsString(rec));

        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType("LIVING_ROOM");
        request.setBudgetLimit(new BigDecimal("10000"));
        request.setStylePreference("MC");

        RoomSchemeResponse response = aiMatchingService.generateRoomScheme(request);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void recommendByAnchor_shouldHandleAnchorNotFound() {
        when(rspuMapper.selectById("RSPU-NOTEXIST")).thenReturn(null);

        AnchorMatchingRequest request = new AnchorMatchingRequest();
        request.setExistingRspuId("RSPU-NOTEXIST");
        request.setTargetCategoryCode("DT");

        assertThatThrownBy(() -> aiMatchingService.recommendByAnchor(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void recommendByAnchor_shouldHandleInvalidAiResponse() {
        RspuMaster anchor = new RspuMaster();
        anchor.setRspuId("RSPU-ANCHOR");
        anchor.setCategoryCode("SF");
        anchor.setPositioningLabel("中古风");

        RspuMaster candidate = new RspuMaster();
        candidate.setRspuId("RSPU-001");
        candidate.setCategoryCode("DT");
        candidate.setPositioningLabel("中古风");

        when(rspuMapper.selectById("RSPU-ANCHOR")).thenReturn(anchor);
        when(rspuMapper.selectList(any())).thenReturn(List.of(candidate));
        when(visionService.chatText(any(), any())).thenReturn("invalid json");

        AnchorMatchingRequest request = new AnchorMatchingRequest();
        request.setExistingRspuId("RSPU-ANCHOR");
        request.setTargetCategoryCode("DT");

        AnchorMatchingResponse response = aiMatchingService.recommendByAnchor(request);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getReasoning()).contains("格式异常");
    }

    @Test
    void generateRoomScheme_withDimensions_shouldInjectDimensionContextIntoPrompt() throws Exception {
        RspuMaster sofa = new RspuMaster();
        sofa.setRspuId("RSPU-SF");
        sofa.setCategoryCode("SF");
        sofa.setPositioningLabel("现代简约");

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-SF");
        rsku.setRspuId("RSPU-SF");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rskuSupplyMapper.selectCapableByRspuIds(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(dictService.listByType("room_type")).thenReturn(List.of(createDict("LIVING", "客厅")));
        when(dictService.listByType("style")).thenReturn(List.of(createDict("MC", "中古风")));

        AiSchemeRecommendation rec = new AiSchemeRecommendation();
        rec.setRspuIds(List.of("RSPU-SF"));
        rec.setReasoning("风格统一");
        when(visionService.chatText(any(), any())).thenReturn(objectMapper.writeValueAsString(rec));

        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType("LIVING");
        request.setBudgetLimit(new BigDecimal("10000"));
        request.setStylePreference("MC");
        request.setWidthMm(4200);
        request.setDepthMm(3800);

        // 重载：候选由调用方（规则引擎）提供，不应再触发候选取数
        RoomSchemeResponse response = aiMatchingService.generateRoomScheme(request, List.of(sofa));

        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(visionService).chatText(any(), promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("4200mm × 3800mm");
        assertThat(prompt).contains("16.0㎡");
        assertThat(prompt).contains("均已通过尺寸校验");
        assertThat(prompt).contains("品类：SF");
        org.mockito.Mockito.verify(rspuMapper, org.mockito.Mockito.never()).selectList(any());
        assertThat(response.getItems()).hasSize(1);
    }

    @Test
    void generateRoomScheme_llmReturnsEmpty_shouldFallbackToRuleBasedSelection() throws Exception {
        RspuMaster sofa = new RspuMaster();
        sofa.setRspuId("RSPU-SF");
        sofa.setCategoryCode("SF");
        sofa.setPositioningLabel("现代简约");
        RspuMaster teaTable = new RspuMaster();
        teaTable.setRspuId("RSPU-TB");
        teaTable.setCategoryCode("TB");
        teaTable.setPositioningLabel("现代简约");
        RspuMaster chair1 = new RspuMaster();
        chair1.setRspuId("RSPU-FS1");
        chair1.setCategoryCode("FS");
        RspuMaster chair2 = new RspuMaster();
        chair2.setRspuId("RSPU-FS2");
        chair2.setCategoryCode("FS");
        RspuMaster chair3 = new RspuMaster();
        chair3.setRspuId("RSPU-FS3");
        chair3.setCategoryCode("FS");

        List<RspuMaster> candidates = List.of(sofa, teaTable, chair1, chair2, chair3);
        // 每个候选一条可报价 RSKU
        when(rskuSupplyMapper.selectCapableByRspuIds(any())).thenAnswer(invocation -> {
            List<RskuSupply> result = new java.util.ArrayList<>();
            for (RspuMaster rspu : candidates) {
                RskuSupply rsku = new RskuSupply();
                rsku.setRskuId("RSKU-" + rspu.getRspuId());
                rsku.setRspuId(rspu.getRspuId());
                rsku.setFactoryCode("F001");
                rsku.setFactoryPrice(new BigDecimal("1000"));
                result.add(rsku);
            }
            return result;
        });
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of());
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(dictService.listByType("room_type")).thenReturn(List.of(createDict("LIVING", "客厅")));

        // LLM 返回空数组 → 规则兜底：每品类取最前者（SF×1 + TB×1 + FS×2）
        when(visionService.chatText(any(), any()))
            .thenReturn("{\"rspuIds\": [], \"reasoning\": \"没有合适组合\"}");

        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType("LIVING");
        request.setBudgetLimit(new BigDecimal("10000"));
        request.setWidthMm(4200);
        request.setDepthMm(3800);

        RoomSchemeResponse response = aiMatchingService.generateRoomScheme(request, candidates);

        assertThat(response.getItems())
            .extracting(com.rsdp.dto.response.SchemeItemResponse::getRspuId)
            .containsExactly("RSPU-SF", "RSPU-TB", "RSPU-FS1", "RSPU-FS2");
        assertThat(response.getReasoning()).contains("规则推荐");
    }

    // ---------- 批次 2：预算口径出厂价 → 销售价 ----------

    @Test
    void generateRoomScheme_promptShouldUseSalePriceAndNeverExposeFactoryPrice() throws Exception {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(rskuSupplyMapper.selectCapableByRspuIds(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(dictService.listByType("room_type")).thenReturn(List.of(createDict("LIVING_ROOM", "客厅")));
        when(pricingService.resolveSalePrice(any(), any())).thenReturn(new BigDecimal("3999"));

        AiSchemeRecommendation rec = new AiSchemeRecommendation();
        rec.setRspuIds(List.of("RSPU-001"));
        rec.setReasoning("风格统一");
        when(visionService.chatText(any(), any())).thenReturn(objectMapper.writeValueAsString(rec));

        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType("LIVING_ROOM");
        request.setBudgetLimit(new BigDecimal("10000"));

        RoomSchemeResponse response = aiMatchingService.generateRoomScheme(request);

        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(visionService).chatText(any(), promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        // 销售价口径进 prompt：参考售价 + 预算上限注明售价口径
        assertThat(prompt).contains("参考售价：3999");
        assertThat(prompt).contains("预算上限：10000 元（指客户应付的销售价总额，请确保所选产品参考售价之和不超过预算）");
        // 红线：prompt 不得出现出厂价数值与「出厂价/成本/最低报价」字样
        assertThat(prompt).doesNotContain("出厂价", "成本", "最低报价", "2500");
        // 响应销售价口径
        assertThat(response.getTotalSalePrice()).isEqualByComparingTo(new BigDecimal("3999"));
        assertThat(response.getItems().get(0).getSalePrice()).isEqualByComparingTo(new BigDecimal("3999"));
        assertThat(response.isHasUnpricedItems()).isFalse();
    }

    @Test
    void generateRoomScheme_unpricedCandidate_shouldMarkPricePendingButNotBlock() throws Exception {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");

        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(rskuSupplyMapper.selectCapableByRspuIds(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(dictService.listByType("room_type")).thenReturn(List.of(createDict("LIVING_ROOM", "客厅")));
        // resolveSalePrice 未 stub（返回 null）：三级链解析不出售价

        AiSchemeRecommendation rec = new AiSchemeRecommendation();
        rec.setRspuIds(List.of("RSPU-001"));
        rec.setReasoning("风格统一");
        when(visionService.chatText(any(), any())).thenReturn(objectMapper.writeValueAsString(rec));

        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType("LIVING_ROOM");
        request.setBudgetLimit(new BigDecimal("10000"));

        RoomSchemeResponse response = aiMatchingService.generateRoomScheme(request);

        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(visionService).chatText(any(), promptCaptor.capture());
        // 无售价候选标注「价格待定」
        assertThat(promptCaptor.getValue()).contains("参考售价：价格待定");
        // 不阻止入选：LLM 选中后仍出现在方案项中
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getSalePrice()).isNull();
        assertThat(response.getTotalSalePrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.isHasUnpricedItems()).isTrue();
    }

    @Test
    void generateRoomScheme_designer_shouldMaskFactoryPriceButExposeSalePrice() throws Exception {
        // 真实 DataScopeHelper + DESIGNER 角色上下文（参照 ProductQueryServiceTest.authenticateWithRoles）
        AiMatchingService service = serviceWithRealDataScope("designer", "DESIGNER");
        stubStandardFixture();
        when(pricingService.resolveSalePrice(any(), any())).thenReturn(new BigDecimal("3999"));

        AiSchemeRecommendation rec = new AiSchemeRecommendation();
        rec.setRspuIds(List.of("RSPU-001"));
        rec.setReasoning("风格统一");
        when(visionService.chatText(any(), any())).thenReturn(objectMapper.writeValueAsString(rec));

        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType("LIVING_ROOM");
        request.setBudgetLimit(new BigDecimal("10000"));

        RoomSchemeResponse response = service.generateRoomScheme(request);

        // 销售价全角色可见
        assertThat(response.getTotalSalePrice()).isEqualByComparingTo(new BigDecimal("3999"));
        assertThat(response.getItems().get(0).getSalePrice()).isEqualByComparingTo(new BigDecimal("3999"));
        // 成本口径掩码：totalPrice / factoryPrice / subtotal 均为 null
        assertThat(response.getTotalPrice()).isNull();
        assertThat(response.getItems().get(0).getFactoryPrice()).isNull();
        assertThat(response.getItems().get(0).getSubtotal()).isNull();

        // 序列化断言：JSON 中不含 factoryPrice/costPrice/subtotal/totalPrice 非空值
        String json = objectMapper.writeValueAsString(response);
        assertThat(json).doesNotContain("costPrice");
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("totalSalePrice").isNumber()).isTrue();
        assertThat(root.path("totalPrice").isNull()).isTrue();
        for (com.fasterxml.jackson.databind.JsonNode item : root.get("items")) {
            assertThat(item.path("factoryPrice").isNull()).isTrue();
            assertThat(item.path("subtotal").isNull()).isTrue();
            assertThat(item.path("salePrice").isNumber()).isTrue();
        }
    }

    @Test
    void generateRoomScheme_admin_shouldSeeBothCostAndSalePrice() throws Exception {
        AiMatchingService service = serviceWithRealDataScope("admin", "ADMIN");
        stubStandardFixture();
        when(pricingService.resolveSalePrice(any(), any())).thenReturn(new BigDecimal("3999"));

        AiSchemeRecommendation rec = new AiSchemeRecommendation();
        rec.setRspuIds(List.of("RSPU-001"));
        rec.setReasoning("风格统一");
        when(visionService.chatText(any(), any())).thenReturn(objectMapper.writeValueAsString(rec));

        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType("LIVING_ROOM");
        request.setBudgetLimit(new BigDecimal("10000"));

        RoomSchemeResponse response = service.generateRoomScheme(request);

        // ADMIN 视角：成本口径与销售价口径皆有
        assertThat(response.getTotalPrice()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(response.getTotalSalePrice()).isEqualByComparingTo(new BigDecimal("3999"));
        assertThat(response.getItems().get(0).getFactoryPrice()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(response.getItems().get(0).getSalePrice()).isEqualByComparingTo(new BigDecimal("3999"));
    }

    /** 标准单产品夹具：RSPU-001（出厂价 2500 的 RSKU + 工厂），用于角色视角用例。 */
    private void stubStandardFixture() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(rskuSupplyMapper.selectCapableByRspuIds(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        lenient().when(dictService.listByType("room_type"))
            .thenReturn(List.of(createDict("LIVING_ROOM", "客厅")));
    }

    /**
     * 以真实 {@link DataScopeHelper} + 指定角色上下文构造 service（数据范围固定 ALL，
     * 使 canAccessFactory 通过；canViewFactoryPrice 走真实角色判定）。
     */
    private AiMatchingService serviceWithRealDataScope(String username, String... roles) {
        authenticateWithRoles(username, roles);
        DataScopeContext dataScopeContext = mock(DataScopeContext.class);
        lenient().when(dataScopeContext.currentDataScope()).thenReturn(DataScope.ALL);
        DataScopeHelper realHelper = new DataScopeHelper(dataScopeContext, rskuSupplyMapper);
        return new AiMatchingService(rspuMapper, rskuSupplyMapper, factoryMasterMapper,
            imageAssetsMapper, dictService, visionService, objectMapper, realHelper, pricingService);
    }

    private void authenticateWithRoles(String username, String... roles) {
        SecurityContextHolder.clearContext();
        var user = User.withUsername(username).password("").roles(roles).build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private CategoryDict createDict(String code, String name) {
        CategoryDict dict = new CategoryDict();
        dict.setDictCode(code);
        dict.setDictName(name);
        return dict;
    }
}
