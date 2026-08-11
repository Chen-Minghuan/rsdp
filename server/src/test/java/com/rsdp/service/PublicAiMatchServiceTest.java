package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.dto.request.PublicAiMatchSchemeRequest;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.response.PublicAiMatchAnalyzeResponse;
import com.rsdp.dto.response.PublicAiMatchSchemeResponse;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PublicAiMatchService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PublicAiMatchServiceTest {

    @Mock
    private ImageUploadValidator imageUploadValidator;

    @Mock
    private VisionService visionService;

    @Mock
    private AiMatchingService aiMatchingService;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @InjectMocks
    private PublicAiMatchService publicAiMatchService;

    // ---------- 尺寸解析 ----------

    @Test
    void parseDimensionMm_mmNotation_shouldParseAsMm() {
        assertThat(PublicAiMatchService.parseDimensionMm("4200×3800"))
            .containsExactly(4200, 3800);
        assertThat(PublicAiMatchService.parseDimensionMm("4200*3800"))
            .containsExactly(4200, 3800);
        assertThat(PublicAiMatchService.parseDimensionMm("4200x3800"))
            .containsExactly(4200, 3800);
    }

    @Test
    void parseDimensionMm_meterNotation_shouldConvertToMm() {
        assertThat(PublicAiMatchService.parseDimensionMm("4.2m*3.8m"))
            .containsExactly(4200, 3800);
        assertThat(PublicAiMatchService.parseDimensionMm("4.2m×3.8"))
            .containsExactly(4200, 3800);
    }

    @Test
    void parseDimensionMm_noDimension_shouldReturnNull() {
        assertThat(PublicAiMatchService.parseDimensionMm(null)).isNull();
        assertThat(PublicAiMatchService.parseDimensionMm("")).isNull();
        assertThat(PublicAiMatchService.parseDimensionMm("客厅")).isNull();
        assertThat(PublicAiMatchService.parseDimensionMm("4200")).isNull();
    }

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
        when(aiMatchingService.generateRoomScheme(any(RoomSchemeRequest.class))).thenReturn(scheme);

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

        // 缺省预算按 999999 装配给内部请求
        ArgumentCaptor<RoomSchemeRequest> captor = ArgumentCaptor.forClass(RoomSchemeRequest.class);
        verify(aiMatchingService).generateRoomScheme(captor.capture());
        assertThat(captor.getValue().getRoomType()).isEqualTo("LIVING");
        assertThat(captor.getValue().getBudgetLimit()).isEqualByComparingTo(new BigDecimal("999999"));
        assertThat(captor.getValue().getStylePreference()).isEqualTo("MC");

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
}
