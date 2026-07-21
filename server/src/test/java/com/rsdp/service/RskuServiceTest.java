package com.rsdp.service;

import com.rsdp.dto.request.RskuCreateRequest;
import com.rsdp.dto.response.RskuResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.PriceHistory;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.PriceHistoryMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.rsdp.security.datascope.DataScopeHelper;
import org.springframework.security.core.userdetails.User;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;

/**
 * {@link RskuService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RskuServiceTest {

    @BeforeEach
    void setSecurityContext() {
        var user = User.withUsername("admin").password("").roles("ADMIN").build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        lenient().when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private FactoryService factoryService;

    @Mock
    private DictService dictService;

    @Mock
    private RspuVariantService rspuVariantService;

    @Mock
    private PriceHistoryMapper priceHistoryMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private RskuService rskuService;

    private List<CategoryDict> factoryLevelDicts() {
        CategoryDict s = new CategoryDict();
        s.setDictType("factory_level");
        s.setDictCode("S");
        s.setDictName("S级");
        CategoryDict c = new CategoryDict();
        c.setDictType("factory_level");
        c.setDictCode("C");
        c.setDictName("C级");
        return List.of(s, c);
    }

    @Test
    void listByRspu_shouldReturnRskuList() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of());
        when(factoryService.batchListCapableLevels(List.of("F001"))).thenReturn(Map.of("F001", List.of("S", "A")));

        List<RskuResponse> result = rskuService.listByRspu("RSPU-TEST01");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRskuId()).isEqualTo("RSKU-TEST01");
        assertThat(result.get(0).getFactoryCapableLevels()).containsExactly("S", "A");
    }

    @Test
    void listByRspu_shouldReturnEmptyListWhenNoRsku() {
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());

        List<RskuResponse> result = rskuService.listByRspu("RSPU-TEST01");

        assertThat(result).isEmpty();
        verifyNoInteractions(factoryMasterMapper);
        verifyNoInteractions(factoryService);
    }

    @Test
    void listByRspu_shouldBatchQueryCapabilitiesForMultipleFactories() {
        RskuSupply rsku1 = new RskuSupply();
        rsku1.setRskuId("RSKU-TEST01");
        rsku1.setRspuId("RSPU-TEST01");
        rsku1.setFactoryCode("F001");

        RskuSupply rsku2 = new RskuSupply();
        rsku2.setRskuId("RSKU-TEST02");
        rsku2.setRspuId("RSPU-TEST01");
        rsku2.setFactoryCode("F002");

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku1, rsku2));
        when(factoryMasterMapper.selectBatchIds(List.of("F001", "F002"))).thenReturn(List.of());
        when(factoryService.batchListCapableLevels(List.of("F001", "F002")))
            .thenReturn(Map.of("F001", List.of("S"), "F002", List.of("A", "B")));

        List<RskuResponse> result = rskuService.listByRspu("RSPU-TEST01");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFactoryCapableLevels()).containsExactly("S");
        assertThat(result.get(1).getFactoryCapableLevels()).containsExactly("A", "B");
        verify(factoryService, times(1)).batchListCapableLevels(any());
    }

    @Test
    void createRsku_shouldInsertWhenValid() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setProductLevel("S");

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        rskuService.createRsku(request);

        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getProductLevel()).isEqualTo("S");
    }

    @Test
    void createRsku_shouldInheritVariantLevel() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setProductLevel("S");

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");
        variant.setProductLevel("C");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "C"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        rskuService.createRsku(request);

        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).insert(captor.capture());
        assertThat(captor.getValue().getProductLevel()).isEqualTo("C");
    }

    @Test
    void createRsku_shouldThrowWhenFactoryNotCapable() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));
        request.setProductLevel("C");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        assertThatThrownBy(() -> rskuService.createRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未声明 C 级能力");
    }

    @Test
    void createRsku_shouldAutoExtendCapabilityWhenRequested() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));
        request.setProductLevel("C");
        request.setAutoExtendCapability(true);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        rskuService.createRsku(request);

        verify(factoryService).extendCapability("F001", "C");
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).insert(captor.capture());
        assertThat(captor.getValue().getProductLevel()).isEqualTo("C");
    }

    @Test
    void createRsku_shouldThrowWhenVariantNotBelongToRspu() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-OTHER-V001");
        request.setFactoryPrice(new BigDecimal("2500"));

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-OTHER-V001");
        variant.setRspuId("RSPU-OTHER");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(new RspuMaster());
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-OTHER-V001")).thenReturn(variant);

        assertThatThrownBy(() -> rskuService.createRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("变体不属于该产品");
    }

    @Test
    void createRsku_shouldThrowWhenDuplicate() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setProductLevel("S");

        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());
        doThrow(new DataIntegrityViolationException("duplicate")).when(rskuSupplyMapper).insert(any(RskuSupply.class));

        assertThatThrownBy(() -> rskuService.createRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已有报价");
    }

    @Test
    void getRsku_shouldReturnDetailWhenValid() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of());

        RskuResponse result = rskuService.getRsku("RSPU-TEST01", "RSKU-TEST01");

        assertThat(result.getRskuId()).isEqualTo("RSKU-TEST01");
    }

    @Test
    void getRsku_shouldThrowWhenRspuMismatch() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-OTHER");

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);

        assertThatThrownBy(() -> rskuService.getRsku("RSPU-TEST01", "RSKU-TEST01"))
            .isInstanceOf(com.rsdp.exception.ResourceNotFoundException.class)
            .hasMessageContaining("不属于");
    }

    @Test
    void listByFactory_shouldReturnRskuList() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));

        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));
        when(factoryMasterMapper.selectBatchIds(List.of("F001"))).thenReturn(List.of());

        List<RskuResponse> result = rskuService.listByFactory("F001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRskuId()).isEqualTo("RSKU-TEST01");
    }

    @Test
    void updateRskuPrice_shouldUpdatePriceAndRecordHistory() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("1200"));

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);

        rskuService.updateRskuPrice("RSPU-TEST01", "RSKU-TEST01", new BigDecimal("1500"), "原材料涨价");

        ArgumentCaptor<RskuSupply> rskuCaptor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).updateById(rskuCaptor.capture());
        assertThat(rskuCaptor.getValue().getFactoryPrice()).isEqualTo(new BigDecimal("1500"));

        ArgumentCaptor<PriceHistory> historyCaptor = ArgumentCaptor.forClass(PriceHistory.class);
        verify(priceHistoryMapper).insert(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getOldPrice()).isEqualTo(new BigDecimal("1200"));
        assertThat(historyCaptor.getValue().getNewPrice()).isEqualTo(new BigDecimal("1500"));
        assertThat(historyCaptor.getValue().getChangeReason()).isEqualTo("原材料涨价");
    }

    @Test
    void updateRskuPrice_shouldThrowWhenRspuIdMismatch() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-OTHER");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("1200"));

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);

        assertThatThrownBy(() -> rskuService.updateRskuPrice("RSPU-TEST01", "RSKU-TEST01", new BigDecimal("1500"), "原材料涨价"))
            .isInstanceOf(com.rsdp.exception.ResourceNotFoundException.class)
            .hasMessageContaining("RSKU 不属于该产品");
    }

    @Test
    void deleteRsku_shouldSoftDelete() {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);
        when(rskuSupplyMapper.deleteById("RSKU-TEST01")).thenReturn(1);

        rskuService.deleteRsku("RSKU-TEST01");

        verify(rskuSupplyMapper).deleteById("RSKU-TEST01");
        verify(auditLogService).logDelete(eq("rsku_supply"), eq("RSKU-TEST01"), any(), eq("admin"));
    }

    @Test
    void deleteRsku_shouldThrowWhenNotFound() {
        when(rskuSupplyMapper.selectById("RSKU-NOTEXIST")).thenReturn(null);

        assertThatThrownBy(() -> rskuService.deleteRsku("RSKU-NOTEXIST"))
            .isInstanceOf(com.rsdp.exception.ResourceNotFoundException.class);
    }
}
