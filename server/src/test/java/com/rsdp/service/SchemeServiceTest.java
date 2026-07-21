package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.CopyFromTemplateRequest;
import com.rsdp.dto.request.QuoteItemRequest;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.request.SchemeItemRequest;
import com.rsdp.dto.request.SchemeTemplateRequest;
import com.rsdp.dto.request.SchemeUpdateRequest;
import com.rsdp.dto.response.CopyFromTemplateResponse;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.dto.response.SchemeSummaryResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.Project;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Mock
    private ProjectService projectService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private SchemeService schemeService;

    @BeforeEach
    void setUp() {
        lenient().when(dataScopeHelper.canAccessRskuFactory(any())).thenReturn(true);
        var user = User.withUsername("testuser").password("").authorities("ROLE_DESIGNER", "scheme:read", "scheme:create", "scheme:update", "scheme:delete").build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createScheme_shouldSaveAndComputeSummary() {
        SchemeItemRequest itemRequest = new SchemeItemRequest();
        itemRequest.setRspuId("RSPU-001");
        itemRequest.setRskuId("RSKU-001");
        itemRequest.setQuantity(2);

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

        SchemeItem savedItem = new SchemeItem();
        savedItem.setSchemeItemId(1L);
        savedItem.setSchemeId("SCHEME-001");
        savedItem.setRspuId("RSPU-001");
        savedItem.setRskuId("RSKU-001");
        savedItem.setFactoryCode("F001");
        savedItem.setFactoryPrice(new BigDecimal("2500"));
        savedItem.setQuantity(2);

        when(rskuSupplyMapper.selectById("RSKU-001")).thenReturn(rsku);
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(savedItem));
        when(schemeMapper.selectById(any())).thenReturn(new Scheme());
        when(schemeMapper.selectCount(any())).thenReturn(0L);

        SchemeResponse response = schemeService.createScheme(request);

        assertThat(response).isNotNull();
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("5000"));
        verify(schemeMapper).insert(any(Scheme.class));
        verify(schemeItemMapper).insertBatchSafe(any(List.class));
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
    void createScheme_shouldRejectDuplicateName() {
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

        when(rskuSupplyMapper.selectById("RSKU-001")).thenReturn(rsku);
        when(schemeMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> schemeService.createScheme(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已存在同名方案");
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
        item.setFactoryPrice(new BigDecimal("2500"));
        item.setQuantity(3);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(schemeMapper.selectById("SCHEME-001")).thenReturn(scheme);
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectList(any())).thenReturn(List.of(factory));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());

        SchemeResponse response = schemeService.getSchemeDetail("SCHEME-001");

        assertThat(response.getSchemeId()).isEqualTo("SCHEME-001");
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(3);
        assertThat(response.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("7500"));
    }

    @Test
    void deleteScheme_shouldSoftDelete() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-001");
        scheme.setStatus("active");
        scheme.setCreatedBy("testuser");

        when(schemeMapper.selectById("SCHEME-001")).thenReturn(scheme);
        when(schemeMapper.deleteById("SCHEME-001")).thenReturn(1);

        schemeService.deleteScheme("SCHEME-001");

        verify(schemeMapper).deleteById("SCHEME-001");
        // 级联软删除方案明细，避免残留孤儿记录
        verify(schemeItemMapper).delete(argThat((QueryWrapper<SchemeItem> w) ->
            w != null && String.valueOf(w.getSqlSegment()).contains("scheme_id")));
    }

    @Test
    void updateScheme_shouldReplaceItemsAndUpdateSummary() {
        Scheme existingScheme = new Scheme();
        existingScheme.setSchemeId("SCHEME-001");
        existingScheme.setSchemeName("旧方案");
        existingScheme.setStatus("active");
        existingScheme.setCreatedBy("testuser");

        SchemeItemRequest itemRequest = new SchemeItemRequest();
        itemRequest.setRspuId("RSPU-001");
        itemRequest.setRskuId("RSKU-002");

        SchemeUpdateRequest request = new SchemeUpdateRequest();
        request.setSchemeName("更新后方案");
        request.setItems(List.of(itemRequest));

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-002");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F002");
        rsku.setFactoryPrice(new BigDecimal("3000"));
        rsku.setLeadTimeDays(30);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F002");
        factory.setFactoryName("新工厂");

        SchemeItem savedItem = new SchemeItem();
        savedItem.setSchemeItemId(1L);
        savedItem.setSchemeId("SCHEME-001");
        savedItem.setRspuId("RSPU-001");
        savedItem.setRskuId("RSKU-002");
        savedItem.setFactoryCode("F002");

        when(schemeMapper.selectById("SCHEME-001")).thenReturn(existingScheme);
        when(rskuSupplyMapper.selectById("RSKU-002")).thenReturn(rsku);
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(savedItem));
        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectList(any())).thenReturn(List.of(factory));
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of());
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(schemeMapper.selectCount(any())).thenReturn(0L);

        SchemeResponse response = schemeService.updateScheme("SCHEME-001", request);

        assertThat(response).isNotNull();
        assertThat(response.getItems()).hasSize(1);
        assertThat(existingScheme.getSchemeName()).isEqualTo("更新后方案");
        assertThat(existingScheme.getTotalPrice()).isEqualTo(new BigDecimal("3000"));
        assertThat(existingScheme.getItemCount()).isEqualTo(1);
        verify(schemeItemMapper).delete(any(QueryWrapper.class));
        verify(schemeMapper).updateById(existingScheme);
        verify(schemeItemMapper).insertBatchSafe(any(List.class));
    }

    @Test
    void generateQuote_shouldDetectPriceChanges() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-001");
        scheme.setStatus("active");

        SchemeItem item = new SchemeItem();
        item.setSchemeItemId(1L);
        item.setSchemeId("SCHEME-001");
        item.setRspuId("RSPU-001");
        item.setRskuId("RSKU-001");
        item.setFactoryPrice(new BigDecimal("2000"));
        item.setQuantity(3);

        RskuSupply currentRsku = new RskuSupply();
        currentRsku.setRskuId("RSKU-001");
        currentRsku.setRspuId("RSPU-001");
        currentRsku.setFactoryPrice(new BigDecimal("2200"));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("中古风");

        QuoteResponse quote = new QuoteResponse();
        quote.setItems(List.of());

        when(schemeMapper.selectById("SCHEME-001")).thenReturn(scheme);
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(quoteService.generateQuote(List.of(req("RSKU-001", 3)))).thenReturn(quote);
        when(rskuSupplyMapper.selectById("RSKU-001")).thenReturn(currentRsku);
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);

        QuoteResponse response = schemeService.generateQuote("SCHEME-001");

        assertThat(response.getPriceChanges()).hasSize(1);
        assertThat(response.getPriceChanges().get(0).getOldPrice()).isEqualTo(new BigDecimal("2000"));
        assertThat(response.getPriceChanges().get(0).getNewPrice()).isEqualTo(new BigDecimal("2200"));
    }

    @Test
    void createScheme_shouldMergeDuplicateRskuQuantities() {
        SchemeItemRequest itemRequest1 = new SchemeItemRequest();
        itemRequest1.setRspuId("RSPU-001");
        itemRequest1.setRskuId("RSKU-001");
        itemRequest1.setQuantity(2);

        SchemeItemRequest itemRequest2 = new SchemeItemRequest();
        itemRequest2.setRspuId("RSPU-001");
        itemRequest2.setRskuId("RSKU-001");
        itemRequest2.setQuantity(3);

        SchemeCreateRequest request = new SchemeCreateRequest();
        request.setSchemeName("合并数量方案");
        request.setItems(List.of(itemRequest1, itemRequest2));

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("1000"));
        rsku.setFactorySku("FACTORY-SKU-001");
        rsku.setLeadTimeDays(20);

        SchemeItem savedItem = new SchemeItem();
        savedItem.setSchemeItemId(1L);
        savedItem.setSchemeId("SCHEME-001");
        savedItem.setRspuId("RSPU-001");
        savedItem.setRskuId("RSKU-001");
        savedItem.setFactoryCode("F001");
        savedItem.setFactoryPrice(new BigDecimal("1000"));
        savedItem.setQuantity(5);

        when(rskuSupplyMapper.selectById("RSKU-001")).thenReturn(rsku);
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(savedItem));
        when(schemeMapper.selectById(any())).thenReturn(new Scheme());
        when(schemeMapper.selectCount(any())).thenReturn(0L);

        SchemeResponse response = schemeService.createScheme(request);

        assertThat(response.getItems()).hasSize(1);
        SchemeItemResponse itemResponse = response.getItems().get(0);
        assertThat(itemResponse.getQuantity()).isEqualTo(5);
        assertThat(itemResponse.getSubtotal()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(itemResponse.getRskuId()).isEqualTo("RSKU-001");
        assertThat(itemResponse.getFactoryCode()).isEqualTo("F001");
        assertThat(itemResponse.getFactorySku()).isEqualTo("FACTORY-SKU-001");
    }

    private QuoteItemRequest req(String rskuId, int quantity) {
        QuoteItemRequest r = new QuoteItemRequest();
        r.setRskuId(rskuId);
        r.setQuantity(quantity);
        return r;
    }

    @Test
    void copyFromTemplate_shouldCopyItemsWithLatestPricesAndKeepTemplateUntouched() {
        Scheme template = new Scheme();
        template.setSchemeId("SCHEME-TPL");
        template.setSchemeName("现代客厅模板");
        template.setIsTemplate(true);
        template.setCreatedBy("testuser");

        Project project = new Project();
        project.setProjectId("PROJ-1");
        when(projectService.getAccessibleProject("PROJ-1")).thenReturn(project);

        SchemeItem tplItem = new SchemeItem();
        tplItem.setSchemeId("SCHEME-TPL");
        tplItem.setRspuId("RSPU-001");
        tplItem.setRskuId("RSKU-001");
        tplItem.setFactoryCode("F001");
        tplItem.setFactoryPrice(new BigDecimal("2000"));
        tplItem.setQuantity(1);
        when(schemeItemMapper.selectList(any())).thenReturn(List.of(tplItem));

        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        rsku.setRspuId("RSPU-001");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("2500"));
        rsku.setLeadTimeDays(20);
        when(rskuSupplyMapper.selectById("RSKU-001")).thenReturn(rsku);
        when(rskuSupplyMapper.selectList(any())).thenReturn(List.of(rsku));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("布艺沙发");
        when(rspuMapper.selectById("RSPU-001")).thenReturn(rspu);
        when(rspuMapper.selectList(any())).thenReturn(List.of(rspu));
        when(factoryMasterMapper.selectList(any())).thenReturn(List.of());
        when(imageAssetsMapper.selectList(any())).thenReturn(List.of());
        when(schemeMapper.selectCount(any())).thenReturn(0L);

        AtomicReference<Scheme> inserted = new AtomicReference<>();
        when(schemeMapper.insert(any(Scheme.class))).thenAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return 1;
        });
        when(schemeMapper.selectById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            if ("SCHEME-TPL".equals(id)) {
                return template;
            }
            Scheme saved = inserted.get();
            return saved != null && saved.getSchemeId().equals(id) ? saved : null;
        });

        CopyFromTemplateRequest request = new CopyFromTemplateRequest();
        request.setProjectId("PROJ-1");

        CopyFromTemplateResponse response = schemeService.copyFromTemplate("SCHEME-TPL", request);

        assertThat(response.getPriceChanges()).hasSize(1);
        assertThat(response.getPriceChanges().get(0).getOldPrice()).isEqualByComparingTo("2000");
        assertThat(response.getPriceChanges().get(0).getNewPrice()).isEqualByComparingTo("2500");
        assertThat(response.getSkippedRskuIds()).isEmpty();
        assertThat(response.getScheme().getProjectId()).isEqualTo("PROJ-1");
        assertThat(response.getScheme().getIsTemplate()).isFalse();
        assertThat(response.getScheme().getTotalPrice()).isEqualByComparingTo("2500");
        assertThat(response.getScheme().getSchemeName()).startsWith("现代客厅模板-套用");

        // 模板自身不被修改
        verify(schemeMapper, never()).updateById(any(Scheme.class));
    }

    @Test
    void copyFromTemplate_shouldRejectNonTemplateScheme() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-1");
        scheme.setIsTemplate(false);
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(scheme);

        CopyFromTemplateRequest request = new CopyFromTemplateRequest();
        request.setProjectId("PROJ-1");

        assertThatThrownBy(() -> schemeService.copyFromTemplate("SCHEME-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不是模板");
    }

    @Test
    void setTemplate_shouldPersistTemplateFlagAndTags() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-1");
        scheme.setSchemeName("客厅方案");
        scheme.setIsTemplate(false);
        scheme.setCreatedBy("testuser");
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(scheme);
        when(schemeItemMapper.selectList(any())).thenReturn(List.of());

        SchemeTemplateRequest request = new SchemeTemplateRequest();
        request.setIsTemplate(true);
        request.setTemplateTags(List.of("客厅", "现代"));

        SchemeResponse response = schemeService.setTemplate("SCHEME-1", request);

        ArgumentCaptor<Scheme> captor = ArgumentCaptor.forClass(Scheme.class);
        verify(schemeMapper).updateById(captor.capture());
        assertThat(captor.getValue().getIsTemplate()).isTrue();
        assertThat(captor.getValue().getTemplateTags()).isEqualTo("[\"客厅\",\"现代\"]");
        assertThat(response.getIsTemplate()).isTrue();
        assertThat(response.getTemplateTags()).containsExactly("客厅", "现代");
    }

    @Test
    void setTemplate_shouldRejectNonOwner() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-1");
        scheme.setCreatedBy("otheruser");
        when(schemeMapper.selectById("SCHEME-1")).thenReturn(scheme);

        SchemeTemplateRequest request = new SchemeTemplateRequest();
        request.setIsTemplate(true);

        assertThatThrownBy(() -> schemeService.setTemplate("SCHEME-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权操作");
        verify(schemeMapper, never()).updateById(any(Scheme.class));
    }

    @Test
    void listSchemes_shouldReturnPagedResultWithCorrectTotal() {
        Scheme s1 = new Scheme();
        s1.setSchemeId("SCHEME-1");
        s1.setSchemeName("方案一");
        s1.setStatus("active");

        Scheme s2 = new Scheme();
        s2.setSchemeId("SCHEME-2");
        s2.setSchemeName("方案二");
        s2.setStatus("active");

        Page<Scheme> page = Page.of(1, 2);
        page.setRecords(List.of(s1, s2));
        page.setTotal(5);
        when(schemeMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        PageResult<SchemeSummaryResponse> result = schemeService.listSchemes(null, null, 1, 2);

        assertThat(result.getTotal()).isEqualTo(5L);
        assertThat(result.getPage()).isEqualTo(1L);
        assertThat(result.getSize()).isEqualTo(2L);
        assertThat(result.getRows())
            .extracting("schemeId")
            .containsExactly("SCHEME-1", "SCHEME-2");
    }

    @Test
    void listSchemes_shouldApplyFiltersBeforePaging() {
        Page<Scheme> page = Page.of(2, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(schemeMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        schemeService.listSchemes(true, "客厅", 2, 10);

        ArgumentCaptor<Page<Scheme>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<QueryWrapper<Scheme>> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(schemeMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        // 筛选条件必须在分页查询的 wrapper 中（先过滤后分页），不能在内存中过滤
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("status")
            .contains("is_template")
            .contains("template_tags");
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
    }

    @Test
    void listSchemes_shouldKeepActiveStatusConditionWhenPaged() {
        Page<Scheme> page = Page.of(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(schemeMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        schemeService.listSchemes(null, null, 1, 10);

        ArgumentCaptor<QueryWrapper<Scheme>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(schemeMapper).selectPage(any(Page.class), captor.capture());
        // 分页不能破坏既有的可见性语义：仅返回 active 方案，已删除方案不出现在任何一页
        assertThat(captor.getValue().getSqlSegment()).contains("status");
        // 分页查询不应走全量 selectList
        verify(schemeMapper, never()).selectList(any(QueryWrapper.class));
    }
}
