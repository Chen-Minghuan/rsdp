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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AiMatchingService aiMatchingService;

    @BeforeEach
    void setUp() throws Exception {
        Field field = AiMatchingService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(aiMatchingService, objectMapper);
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
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
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
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
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
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(cheapRsku));
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("A", "B"));
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

    private CategoryDict createDict(String code, String name) {
        CategoryDict dict = new CategoryDict();
        dict.setDictCode(code);
        dict.setDictName(name);
        return dict;
    }
}
