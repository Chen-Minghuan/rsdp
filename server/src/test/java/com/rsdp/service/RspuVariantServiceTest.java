package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.RspuVariantCreateRequest;
import com.rsdp.dto.response.RspuVariantResponse;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import com.rsdp.entity.CategoryDict;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.VariantCodeMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RspuVariantServiceTest {

    @Mock
    private RspuVariantMapper variantMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private VariantCodeMapper variantCodeMapper;

    @Mock
    private DictService dictService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private DataScopeHelper dataScopeHelper;

    @Mock
    private com.rsdp.service.DictAliasService dictAliasService;

    @Mock
    private com.rsdp.service.DictUnresolvedService dictUnresolvedService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RspuVariantService variantService;

    private List<CategoryDict> materialDicts(String... codes) {
        return java.util.Arrays.stream(codes)
            .map(c -> {
                CategoryDict d = new CategoryDict();
                d.setDictType("material");
                d.setDictCode(c);
                d.setDictName(c);
                return d;
            })
            .toList();
    }

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

    private String rspuId;

    @BeforeEach
    void setUp() {
        rspuId = "RSPU-TEST01";
        lenient().when(dataScopeHelper.canAccessRspu(any())).thenReturn(true);
        // 默认无重复维度组合；重复场景在单个用例中覆盖
        lenient().when(variantMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void createVariant_shouldSucceed_whenRspuExists() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(variantCodeMapper.allocateSequence(rspuId)).thenReturn(1L);
        when(dictService.listByType("material")).thenReturn(materialDicts("LI"));

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("兰卡沙发 2450mm 布艺版");
        request.setMaterialCode("LI");

        RspuVariantResponse response = variantService.createVariant(rspuId, request);

        assertNotNull(response);
        assertEquals("RSPU-TEST01-V001", response.getVariantId());
        assertEquals("兰卡沙发 2450mm 布艺版", response.getDisplayName());
        assertEquals("LI", response.getMaterialCode());
        assertEquals("active", response.getStatus());
        verify(auditLogService).logCreate(any(), any(), any(), any());
    }

    @Test
    void createVariant_shouldThrow_whenRspuNotFound() {
        when(rspuMapper.selectById(rspuId)).thenReturn(null);

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("测试变体");
        request.setMaterialCode("PE");

        assertThrows(ResourceNotFoundException.class, () -> variantService.createVariant(rspuId, request));
    }

    @Test
    void createVariant_shouldGenerateSequentialIds() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(variantCodeMapper.allocateSequence(rspuId)).thenReturn(3L);
        when(dictService.listByType("material")).thenReturn(materialDicts("TN"));

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("第三个变体");
        request.setMaterialCode("TN");

        RspuVariantResponse response = variantService.createVariant(rspuId, request);

        assertEquals("RSPU-TEST01-V003", response.getVariantId());
    }

    @Test
    void createVariant_shouldSetProductLevel() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(variantCodeMapper.allocateSequence(rspuId)).thenReturn(1L);
        when(dictService.listByType("material")).thenReturn(materialDicts("LI"));
        when(dictService.listByType("factory_level")).thenReturn(factoryLevelDicts());

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("C 级床垫标准版");
        request.setMaterialCode("LI");
        request.setProductLevel("C");

        RspuVariantResponse response = variantService.createVariant(rspuId, request);

        assertEquals("C", response.getProductLevel());
    }

    @Test
    void listVariantsByRspu_shouldReturnActiveVariants() {
        RspuVariant v1 = new RspuVariant();
        v1.setVariantId(rspuId + "-V001");
        v1.setRspuId(rspuId);
        v1.setDisplayName("变体一");
        v1.setCreatedAt(LocalDateTime.now());

        RspuVariant v2 = new RspuVariant();
        v2.setVariantId(rspuId + "-V002");
        v2.setRspuId(rspuId);
        v2.setDisplayName("变体二");
        v2.setCreatedAt(LocalDateTime.now().plusSeconds(1));

        when(variantMapper.selectList(any())).thenReturn(List.of(v1, v2));

        List<RspuVariantResponse> result = variantService.listVariantsByRspu(rspuId);

        assertEquals(2, result.size());
        assertEquals("变体一", result.get(0).getDisplayName());
    }

    @Test
    void createVariant_shouldRetryWhenGeneratedIdConflicts() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(variantCodeMapper.allocateSequence(rspuId)).thenReturn(1L, 2L);
        when(dictService.listByType("material")).thenReturn(materialDicts("LI"));

        RspuVariant existing = new RspuVariant();
        existing.setVariantId(rspuId + "-V001");
        when(variantMapper.selectById(rspuId + "-V001")).thenReturn(existing);
        when(variantMapper.selectById(rspuId + "-V002")).thenReturn(null);

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("自动跳过冲突 ID");
        request.setMaterialCode("LI");

        RspuVariantResponse response = variantService.createVariant(rspuId, request);

        assertEquals("RSPU-TEST01-V002", response.getVariantId());
    }

    @Test
    void createVariant_shouldThrow_whenDuplicateDimensions() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(dictService.listByType("material")).thenReturn(materialDicts("LI"));
        // 应用层"码或原文"判重：已有同材质码变体
        RspuVariant existing = new RspuVariant();
        existing.setVariantId("V-EXIST");
        existing.setRspuId(rspuId);
        existing.setMaterialCode("LI");
        when(variantMapper.selectList(any())).thenReturn(List.of(existing));

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("重复维度变体");
        request.setMaterialCode("LI");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> variantService.createVariant(rspuId, request));
        assertEquals("相同尺寸/颜色/材质的变体已存在", ex.getMessage());
        verify(variantMapper, org.mockito.Mockito.never()).insert(any(RspuVariant.class));
    }

    @Test
    void createVariant_shouldThrow_whenDimsUniqueIndexConflict() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(variantCodeMapper.allocateSequence(rspuId)).thenReturn(1L);
        when(dictService.listByType("material")).thenReturn(materialDicts("LI"));
        when(variantMapper.insert(any(RspuVariant.class)))
            .thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"idx_variant_unique_dims\""));

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("并发重复维度");
        request.setMaterialCode("LI");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> variantService.createVariant(rspuId, request));
        assertEquals("相同尺寸/颜色/材质的变体已存在", ex.getMessage());
    }

    @Test
    void createVariant_unknownMaterialCode_shouldDowngradeToText() {
        // V19：材质码未识别不再报错——码置空、原文保留到 materialText，并采集待治理
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(variantCodeMapper.allocateSequence(rspuId)).thenReturn(1L);
        when(dictService.listByType("material")).thenReturn(materialDicts("LI"));
        when(dictAliasService.resolveAlias(eq("material"), anyString())).thenReturn(null);

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("A级布版本");
        request.setMaterialCode("A级布");

        RspuVariantResponse response = variantService.createVariant(rspuId, request);

        assertNull(response.getMaterialCode());
        assertEquals("A级布", response.getMaterialText());
        verify(dictUnresolvedService).record(eq("material"), eq("A级布"), isNull(), any());
    }

    @Test
    void createVariant_aliasHit_shouldResolveToDictCode() {
        // V19：dict_alias 别名命中时自动归一为字典码，不产生原文与采集
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(variantCodeMapper.allocateSequence(rspuId)).thenReturn(1L);
        when(dictAliasService.resolveAlias("material", "头层牛皮")).thenReturn("LI");

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("真皮版");
        request.setMaterialCode("头层牛皮");

        RspuVariantResponse response = variantService.createVariant(rspuId, request);

        assertEquals("LI", response.getMaterialCode());
        assertNull(response.getMaterialText());
        verify(dictUnresolvedService, never()).record(any(), any(), any(), any());
    }

    @Test
    void createVariant_shouldThrow_whenDuplicateByText() {
        // V19：判重采用"码或原文"语义——已有变体 materialText=A级布，新变体同原文应判重
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(dictService.listByType("material")).thenReturn(materialDicts("LI"));
        when(dictAliasService.resolveAlias(eq("material"), anyString())).thenReturn(null);
        RspuVariant existing = new RspuVariant();
        existing.setVariantId("V-EXIST");
        existing.setRspuId(rspuId);
        existing.setMaterialText("A级布");
        when(variantMapper.selectList(any())).thenReturn(List.of(existing));

        RspuVariantCreateRequest request = new RspuVariantCreateRequest();
        request.setDisplayName("又一个A级布");
        request.setMaterialCode("A级布");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> variantService.createVariant(rspuId, request));
        assertEquals("相同尺寸/颜色/材质的变体已存在", ex.getMessage());
    }
}
