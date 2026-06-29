package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.request.SchemeItemRequest;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SchemeService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SchemeServiceTest {

    @Mock
    private SchemeMapper schemeMapper;

    @Mock
    private SchemeItemMapper schemeItemMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private QuoteService quoteService;

    @InjectMocks
    private SchemeService schemeService;

    @Test
    void createScheme_shouldSaveAndComputeSummary() {
        SchemeItemRequest itemRequest = new SchemeItemRequest();
        itemRequest.setRspuId("RSPU-001");
        itemRequest.setRskuId("RSKU-001");

        SchemeCreateRequest request = new SchemeCreateRequest();
        request.setSchemeName("客厅搭配方案");
        request.setItems(List.of(itemRequest));

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setLeadTimeDays(25);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(rskuSupplyMapper.selectById("RSKU-001")).thenReturn(rsku);
        when(schemeItemMapper.selectList(any())).thenReturn(List.of());
        when(schemeMapper.selectById(any())).thenReturn(new Scheme());

        SchemeResponse response = schemeService.createScheme(request);

        assertThat(response).isNotNull();
        verify(schemeMapper).insert(any(Scheme.class));
        verify(schemeItemMapper).insert(any(SchemeItem.class));
    }

    @Test
    void createScheme_shouldThrowWhenRskuNotFound() {
        SchemeItemRequest itemRequest = new SchemeItemRequest();
        itemRequest.setRspuId("RSPU-001");
        itemRequest.setRskuId("RSKU-NOTEXIST");

        SchemeCreateRequest request = new SchemeCreateRequest();
        request.setSchemeName("测试方案");
        request.setItems(List.of(itemRequest));

        when(rskuSupplyMapper.selectById("RSKU-NOTEXIST")).thenReturn(null);

        assertThatThrownBy(() -> schemeService.createScheme(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSchemeDetail_shouldReturnScheme() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-001");
        scheme.setSchemeName("测试方案");
        scheme.setTotalPrice(new BigDecimal("2500"));
        scheme.setItemCount(1);

        SchemeItem item = new SchemeItem();
        item.setSchemeItemId(1L);
        item.setSchemeId("SCHEME-001");
        item.setRspuId("RSPU-001");
        item.setRskuId("RSKU-001");
        item.setFactoryCode("F001");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(schemeMapper.selectById("SCHEME-001")).thenReturn(scheme);
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        SchemeResponse response = schemeService.getSchemeDetail("SCHEME-001");

        assertThat(response.getSchemeId()).isEqualTo("SCHEME-001");
        assertThat(response.getItems()).hasSize(1);
    }

    @Test
    void deleteScheme_shouldSoftDelete() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-001");
        scheme.setStatus("active");

        when(schemeMapper.selectById("SCHEME-001")).thenReturn(scheme);

        schemeService.deleteScheme("SCHEME-001");

        assertThat(scheme.getStatus()).isEqualTo("deleted");
        assertThat(scheme.getDeletedAt()).isNotNull();
        verify(schemeMapper).updateById(scheme);
    }
}
