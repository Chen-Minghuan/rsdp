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
        lenient().when(rskuCodeService.tryAssignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn("FS-MC-001-M-F001-PE-001");
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

    @Mock
    private RskuCodeService rskuCodeService;

    @Mock
    private RspuPriceSummaryService rspuPriceSummaryService;

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
    void createRsku_shouldInsertWhenValid() {        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));
        request.setMaterialCode("PE");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");
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
        request.setMaterialCode("PE");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");
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
    void createRsku_shouldCreateWithoutCodeWhenRspuHasNoCode() {
        // RSPU 未发号（Excel AI 导入风格缺失容错场景）：tryAssignCode 返回 null，
        // 报价照常创建，rskuCode 为 null，factoryPrice 正常落库，业务编码待补发
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));
        request.setMaterialCode("PE");

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
        when(rskuCodeService.tryAssignCode(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(null);

        String rskuId = rskuService.createRsku(request);

        assertThat(rskuId).isNotBlank();
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getRskuCode()).isNull();
        assertThat(captor.getValue().getFactoryPrice()).isEqualByComparingTo("2500");
    }

    @Test
    void createRsku_shouldThrowWhenFactoryNotCapable() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));
        request.setMaterialCode("PE");
        request.setProductLevel("C");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");

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
        request.setMaterialCode("PE");
        request.setProductLevel("C");
        request.setAutoExtendCapability(true);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");

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
        request.setMaterialCode("PE");

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
        request.setMaterialCode("PE");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");
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
        rsku.setQuoteVersion(1);

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-TEST01")).thenReturn(rsku);
        when(rskuSupplyMapper.bumpQuoteVersion(eq("RSKU-TEST01"), eq(1), any())).thenReturn(1);

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
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("RSKU 不属于该产品");
    }

    @Test
    void createRsku_shouldThrowWhenPriceNotPositive() {
        // 2.9：服务层兜底，0 价与负价均被拒（防绕过 Bean Validation 的内部调用）
        RskuCreateRequest zeroPrice = new RskuCreateRequest();
        zeroPrice.setFactoryPrice(BigDecimal.ZERO);
        assertThatThrownBy(() -> rskuService.createRsku(zeroPrice))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("出厂价必须大于 0");

        RskuCreateRequest negativePrice = new RskuCreateRequest();
        negativePrice.setFactoryPrice(new BigDecimal("-1"));
        assertThatThrownBy(() -> rskuService.createRsku(negativePrice))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("出厂价必须大于 0");
    }

    @Test
    void updateRskuPrice_shouldThrowWhenPriceNotPositive() {
        // 2.9：0 价与负价统一按「必须大于 0」拒绝（原仅拒绝负数）
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("1200"));

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);

        assertThatThrownBy(() -> rskuService.updateRskuPrice("RSPU-TEST01", "RSKU-TEST01", BigDecimal.ZERO, "调价"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("价格必须大于 0");
        assertThatThrownBy(() -> rskuService.updateRskuPrice("RSPU-TEST01", "RSKU-TEST01", new BigDecimal("-100"), "调价"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("价格必须大于 0");
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

    @Test
    void upsertRsku_shouldUpdateExistingAndRecordPriceHistory() {
        // P1-2：已存在（产品+变体+工厂）报价时更新价格/交期/MOQ，价格变化写 price_history
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2600"));
        request.setLeadTimeDays(30);
        request.setMoq(5);
        request.setMaterialDescription("A级布");

        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-EXIST01");
        existing.setRspuId("RSPU-TEST01");
        existing.setVariantId("RSPU-TEST01-V001");
        existing.setFactoryCode("F001");
        existing.setFactoryPrice(new BigDecimal("2500"));
        existing.setSupplierConfigurationKey("BASE");
        existing.setQuoteVersion(3);

        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);
        when(rskuSupplyMapper.bumpQuoteVersion(eq("RSKU-EXIST01"), eq(3), any())).thenReturn(1);

        String rskuId = rskuService.upsertRsku(request);

        assertThat(rskuId).isEqualTo("RSKU-EXIST01");
        verify(rskuSupplyMapper, never()).insert(any(RskuSupply.class));
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getFactoryPrice()).isEqualByComparingTo("2600");
        assertThat(captor.getValue().getLeadTimeDays()).isEqualTo(30);
        assertThat(captor.getValue().getMoq()).isEqualTo(5);
        assertThat(captor.getValue().getMaterialDescription()).isEqualTo("A级布");

        ArgumentCaptor<PriceHistory> historyCaptor = ArgumentCaptor.forClass(PriceHistory.class);
        verify(priceHistoryMapper).insert(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getOldPrice()).isEqualByComparingTo("2500");
        assertThat(historyCaptor.getValue().getNewPrice()).isEqualByComparingTo("2600");
        verify(auditLogService).logUpdate(eq("rsku_supply"), eq("RSKU-EXIST01"), any(), any(), any());
    }

    @Test
    void upsertRsku_shouldNotRecordPriceHistoryWhenPriceUnchanged() {
        // P1-2：价格未变化时不写 price_history，仍更新其他字段
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));
        request.setLeadTimeDays(45);

        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-EXIST01");
        existing.setRspuId("RSPU-TEST01");
        existing.setVariantId("RSPU-TEST01-V001");
        existing.setFactoryCode("F001");
        existing.setFactoryPrice(new BigDecimal("2500"));
        existing.setSupplierConfigurationKey("BASE");
        existing.setQuoteVersion(1);

        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);
        when(rskuSupplyMapper.bumpQuoteVersion(eq("RSKU-EXIST01"), eq(1), any())).thenReturn(1);

        rskuService.upsertRsku(request);

        verify(rskuSupplyMapper).updateById(any(RskuSupply.class));
        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
    }

    @Test
    void upsertRsku_shouldCreateWhenNotExists() {
        // P1-2：不存在既有报价时走 createRsku 新建
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");
        rspu.setProductLevel("S");
        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");
        variant.setMaterialCode("PE");

        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(null);
        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        rskuService.upsertRsku(request);

        verify(rskuSupplyMapper).insert(any(RskuSupply.class));
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    // ---------------- v3.3 P0-D：Supplier Configuration Identity / 人工修正标记 ----------------

    @Test
    void createRsku_shouldWriteExplicitBaseKeyWhenNoGrade() {
        // §34：supplierConfigurationKey 无数据库 DEFAULT，写路径必须显式赋值；
        // 无供应商等级证据 → BASE（§18.2）
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");
        rspu.setProductLevel("S");
        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");
        variant.setMaterialCode("PE");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        rskuService.createRsku(request);

        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).insert(captor.capture());
        assertThat(captor.getValue().getSupplierConfigurationKey()).isEqualTo("BASE");
        assertThat(captor.getValue().getSupplierConfigurationNormalizerVersion()).isEqualTo("1");
    }

    @Test
    void createRsku_shouldDeriveTextKeyFromSupplierGrade() {
        // §18.2：只有原始等级原文 → TEXT:<归一原文>
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("9500"));
        request.setSupplierMaterialGradeText("AA级布");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");
        rspu.setProductLevel("S");
        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");
        variant.setMaterialCode("PE");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        rskuService.createRsku(request);

        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).insert(captor.capture());
        assertThat(captor.getValue().getSupplierConfigurationKey()).isEqualTo("TEXT:AA级布");
        assertThat(captor.getValue().getSupplierMaterialGradeText()).isEqualTo("AA级布");
    }

    @Test
    void createRsku_shouldRejectMalformedExplicitConfigKey() {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2500"));
        request.setSupplierConfigurationKey("WEIRD:foo");

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setProductLevel("S");
        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");
        variant.setMaterialCode("PE");

        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        assertThatThrownBy(() -> rskuService.createRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("supplierConfigurationKey 形态非法");
    }

    @Test
    void upsertRsku_shouldNotOverwriteAcrossDifferentConfigKeys() {
        // §56：横向 A/AA/S 级报价不覆盖——不同 supplierConfigurationKey 各自命中/创建独立 RSKU
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("9500"));
        request.setSupplierMaterialGradeText("AA级布");

        // 同 Variant+Factory 下已有 BASE 与 TEXT:A级布 行，但 TEXT:AA级布 未命中 → 新建
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "TEXT:AA级布"))
            .thenReturn(null);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setRspuCode("FS-MC-001-M");
        rspu.setProductLevel("S");
        RspuVariant variant = new RspuVariant();
        variant.setVariantId("RSPU-TEST01-V001");
        variant.setRspuId("RSPU-TEST01");
        variant.setMaterialCode("PE");
        when(rspuMapper.selectById("RSPU-TEST01")).thenReturn(rspu);
        when(factoryMasterMapper.selectById("F001")).thenReturn(new FactoryMaster());
        when(rspuVariantService.findById("RSPU-TEST01-V001")).thenReturn(variant);
        when(factoryService.getFactoryCapableLevels("F001")).thenReturn(List.of("S", "A"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        rskuService.upsertRsku(request);

        // 走的是新建而非更新既有 BASE/A级 行
        ArgumentCaptor<RskuSupply> captor = ArgumentCaptor.forClass(RskuSupply.class);
        verify(rskuSupplyMapper).insert(captor.capture());
        assertThat(captor.getValue().getSupplierConfigurationKey()).isEqualTo("TEXT:AA级布");
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    @Test
    void upsertRsku_shouldPreservePriceWhenManualOverrideActive() {
        // §34.1/§47.1.5 manualCorrectionPolicy=preserve：活跃人工修正标记不被 Excel/AI 覆盖
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2600"));

        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-EXIST01");
        existing.setRspuId("RSPU-TEST01");
        existing.setVariantId("RSPU-TEST01-V001");
        existing.setFactoryCode("F001");
        existing.setFactoryPrice(new BigDecimal("2500"));
        existing.setSupplierConfigurationKey("BASE");
        existing.setQuoteVersion(2);
        existing.setManualOverrideFields(com.rsdp.util.ManualOverrideFields.markActive(
            null, "/factoryPrice", "admin", "product_ui"));

        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);
        when(rskuSupplyMapper.bumpQuoteVersion(eq("RSKU-EXIST01"), eq(2), any())).thenReturn(1);

        String rskuId = rskuService.upsertRsku(request);

        assertThat(rskuId).isEqualTo("RSKU-EXIST01");
        // 价格保留既有值，不写价格历史
        assertThat(existing.getFactoryPrice()).isEqualByComparingTo("2500");
        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
    }

    @Test
    void upsertRsku_shouldThrowOnQuoteVersionConflict() {
        // §37：乐观版本校验失败 → quote_version_conflict
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal("2600"));

        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-EXIST01");
        existing.setRspuId("RSPU-TEST01");
        existing.setVariantId("RSPU-TEST01-V001");
        existing.setFactoryCode("F001");
        existing.setFactoryPrice(new BigDecimal("2500"));
        existing.setSupplierConfigurationKey("BASE");
        existing.setQuoteVersion(2);

        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);
        when(rskuSupplyMapper.bumpQuoteVersion(eq("RSKU-EXIST01"), eq(2), any())).thenReturn(0);

        assertThatThrownBy(() -> rskuService.upsertRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("quote_version_conflict");
    }

    @Test
    void updateRskuPrice_shouldMarkFactoryPriceManualOverride() {
        // §34.1：人工改价同事务写入 /factoryPrice 活跃标记
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-TEST01");
        rsku.setRspuId("RSPU-TEST01");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("1200"));
        rsku.setQuoteVersion(1);

        when(rskuSupplyMapper.selectById("RSKU-TEST01")).thenReturn(rsku);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-TEST01")).thenReturn(rsku);
        when(rskuSupplyMapper.bumpQuoteVersion(eq("RSKU-TEST01"), eq(1), any())).thenReturn(1);

        rskuService.updateRskuPrice("RSPU-TEST01", "RSKU-TEST01", new BigDecimal("1500"), "调价");

        assertThat(com.rsdp.util.ManualOverrideFields.isActive(
            rsku.getManualOverrideFields(), "/factoryPrice")).isTrue();
        assertThat(com.rsdp.util.ManualOverrideFields.getMark(
            rsku.getManualOverrideFields(), "/factoryPrice").source()).isEqualTo("product_ui");
    }

    // ------------------------------------------------------------ P0-F governed 路径（§47.1.5）

    private RskuSupply governedExisting(java.time.LocalDate quoteDate, String price) {
        RskuSupply existing = new RskuSupply();
        existing.setRskuId("RSKU-EXIST01");
        existing.setRspuId("RSPU-TEST01");
        existing.setVariantId("RSPU-TEST01-V001");
        existing.setFactoryCode("F001");
        existing.setFactoryPrice(new BigDecimal(price));
        existing.setSupplierConfigurationKey("BASE");
        existing.setQuoteVersion(2);
        existing.setQuoteDate(quoteDate);
        return existing;
    }

    private RskuCreateRequest governedRequest(String quotePolicy, java.time.LocalDate quoteDate, String price) {
        RskuCreateRequest request = new RskuCreateRequest();
        request.setRspuId("RSPU-TEST01");
        request.setFactoryCode("F001");
        request.setVariantId("RSPU-TEST01-V001");
        request.setFactoryPrice(new BigDecimal(price));
        request.setQuotePolicy(quotePolicy);
        request.setQuoteDate(quoteDate);
        request.setQuotePolicyNotes(new java.util.ArrayList<>());
        return request;
    }

    @Test
    void upsertRsku_governed_staleQuote_ignoredWithoutWrite() {
        // §47.1.5：incoming 日期严格早于 existing → STALE_QUOTE_IGNORED，不写库不涨版本
        RskuCreateRequest request = governedRequest("replace_if_newer",
            java.time.LocalDate.of(2026, 1, 1), "2600");
        RskuSupply existing = governedExisting(java.time.LocalDate.of(2026, 2, 1), "2500");
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);

        String rskuId = rskuService.upsertRsku(request);

        assertThat(rskuId).isEqualTo("RSKU-EXIST01");
        assertThat(existing.getFactoryPrice()).isEqualByComparingTo("2500");
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
        verify(rskuSupplyMapper, never()).bumpQuoteVersion(anyString(), any(), any());
        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
        assertThat(request.getQuotePolicyNotes()).anyMatch(n -> n.contains("STALE_QUOTE_IGNORED"));
    }

    @Test
    void upsertRsku_governed_sameDateSameValue_noOpNoBump() {
        // §47.1.5 同值 no-op：不增长 quote_version / 不写 History / 不动 updated_at
        RskuCreateRequest request = governedRequest("replace_if_newer",
            java.time.LocalDate.of(2026, 1, 1), "2500");
        RskuSupply existing = governedExisting(java.time.LocalDate.of(2026, 1, 1), "2500");
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);

        String rskuId = rskuService.upsertRsku(request);

        assertThat(rskuId).isEqualTo("RSKU-EXIST01");
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
        verify(rskuSupplyMapper, never()).bumpQuoteVersion(anyString(), any(), any());
        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
    }

    @Test
    void upsertRsku_governed_sameDateDifferentValue_conflict() {
        RskuCreateRequest request = governedRequest("replace_if_newer",
            java.time.LocalDate.of(2026, 1, 1), "2600");
        RskuSupply existing = governedExisting(java.time.LocalDate.of(2026, 1, 1), "2500");
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);

        assertThatThrownBy(() -> rskuService.upsertRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("QUOTE_SAME_DATE_CONFLICT");
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    @Test
    void upsertRsku_governed_bothUndatedDifferentValue_conflict() {
        // NULL 日期语义显式枚举（§47.1.5 条件表末行）：双均无日期且值不同 → QUOTE_UNDATED_CONFLICT
        RskuCreateRequest request = governedRequest("replace_if_newer", null, "2600");
        RskuSupply existing = governedExisting(null, "2500");
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);

        assertThatThrownBy(() -> rskuService.upsertRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("QUOTE_UNDATED_CONFLICT");
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    @Test
    void upsertRsku_governed_keepExisting_skipsWrite() {
        RskuCreateRequest request = governedRequest("keep_existing",
            java.time.LocalDate.of(2026, 3, 1), "2600");
        RskuSupply existing = governedExisting(java.time.LocalDate.of(2026, 1, 1), "2500");
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);

        String rskuId = rskuService.upsertRsku(request);

        assertThat(rskuId).isEqualTo("RSKU-EXIST01");
        assertThat(existing.getFactoryPrice()).isEqualByComparingTo("2500");
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
        verify(rskuSupplyMapper, never()).bumpQuoteVersion(anyString(), any(), any());
        assertThat(request.getQuotePolicyNotes()).anyMatch(n -> n.contains("keep_existing"));
    }

    @Test
    void upsertRsku_governed_newerDateSameValue_validityOnlyNoFakePriceHistory() {
        // §47.1.5：值未变但日期更新 → 只记 validity_update，不记伪价格变更
        RskuCreateRequest request = governedRequest("replace_if_newer",
            java.time.LocalDate.of(2026, 3, 1), "2500");
        RskuSupply existing = governedExisting(java.time.LocalDate.of(2026, 1, 1), "2500");
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);
        when(rskuSupplyMapper.bumpQuoteVersion(eq("RSKU-EXIST01"), eq(2), any())).thenReturn(1);

        rskuService.upsertRsku(request);

        // 报价日期更新，价格不动，不写价格历史
        assertThat(existing.getQuoteDate()).isEqualTo(java.time.LocalDate.of(2026, 3, 1));
        verify(rskuSupplyMapper).updateById(any(RskuSupply.class));
        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
    }

    @Test
    void upsertRsku_governed_manualOverridePreservesPriceEvenWhenNewer() {
        // 优先级：manualCorrectionPolicy=preserve 先于 quotePolicy——
        // 即使 incoming 日期更新，/factoryPrice 活跃人工修正标记仍保留人工值（§47.1.5/§34.1）
        RskuCreateRequest request = governedRequest("replace_if_newer",
            java.time.LocalDate.of(2026, 3, 1), "2600");
        RskuSupply existing = governedExisting(java.time.LocalDate.of(2026, 1, 1), "2500");
        existing.setManualOverrideFields(com.rsdp.util.ManualOverrideFields.markActive(
            null, "/factoryPrice", "admin", "product_ui"));
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);
        when(rskuSupplyMapper.bumpQuoteVersion(eq("RSKU-EXIST01"), eq(2), any())).thenReturn(1);

        rskuService.upsertRsku(request);

        assertThat(existing.getFactoryPrice()).isEqualByComparingTo("2500");
        verify(priceHistoryMapper, never()).insert(any(PriceHistory.class));
        verify(auditLogService).logUpdate(eq("rsku_supply"), eq("RSKU-EXIST01"), any(),
            argThat(v -> v instanceof java.util.Map
                && "manualOverridePreserved".equals(((java.util.Map<?, ?>) v).get("action"))), any());
    }

    @Test
    void upsertRsku_governed_forceReplace_requiresReason() {
        RskuCreateRequest request = governedRequest("force_replace",
            java.time.LocalDate.of(2026, 3, 1), "2600");
        RskuSupply existing = governedExisting(java.time.LocalDate.of(2026, 1, 1), "2500");
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);

        assertThatThrownBy(() -> rskuService.upsertRsku(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("overrideReason");
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    @Test
    void upsertRsku_governed_forceReplace_nonAdmin_forbidden() {
        var user = User.withUsername("editor").password("").roles("EDITOR").build();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        RskuCreateRequest request = governedRequest("force_replace",
            java.time.LocalDate.of(2026, 3, 1), "2600");
        request.setQuoteOverrideReason("客户确认覆盖");
        RskuSupply existing = governedExisting(java.time.LocalDate.of(2026, 1, 1), "2500");
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);

        assertThatThrownBy(() -> rskuService.upsertRsku(request))
            .isInstanceOf(com.rsdp.exception.ForbiddenException.class);
        verify(rskuSupplyMapper, never()).updateById(any(RskuSupply.class));
    }

    @Test
    void upsertRsku_governed_forceReplace_admin_writesWithAudit() {
        // 当前 SecurityContext 为 ADMIN（setSecurityContext）：force_replace + reason → 覆盖并记审计
        RskuCreateRequest request = governedRequest("force_replace",
            java.time.LocalDate.of(2026, 3, 1), "2600");
        request.setQuoteOverrideReason("客户确认涨价覆盖");
        RskuSupply existing = governedExisting(java.time.LocalDate.of(2026, 1, 1), "2500");
        when(rskuSupplyMapper.selectAliveByConfig("RSPU-TEST01-V001", "F001", "BASE")).thenReturn(existing);
        when(rskuSupplyMapper.selectAliveByIdForUpdate("RSKU-EXIST01")).thenReturn(existing);
        when(rskuSupplyMapper.bumpQuoteVersion(eq("RSKU-EXIST01"), eq(2), any())).thenReturn(1);

        rskuService.upsertRsku(request);

        assertThat(existing.getFactoryPrice()).isEqualByComparingTo("2600");
        verify(rskuSupplyMapper).updateById(any(RskuSupply.class));
        verify(auditLogService).logUpdate(eq("rsku_supply"), eq("RSKU-EXIST01"), any(),
            argThat(v -> v instanceof java.util.Map
                && "forceReplace".equals(((java.util.Map<?, ?>) v).get("action"))), any());
    }
}
