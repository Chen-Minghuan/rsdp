package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiSchemeRecommendation;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
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
        rsku.setLeadTimeDays(25);

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of(new ImageAssets()));
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

    private CategoryDict createDict(String code, String name) {
        CategoryDict dict = new CategoryDict();
        dict.setDictCode(code);
        dict.setDictName(name);
        return dict;
    }
}
