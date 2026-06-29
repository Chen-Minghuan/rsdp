package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.RspuVariantCreateRequest;
import com.rsdp.dto.response.RspuVariantResponse;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.entity.CategoryDict;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.VariantCodeMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
}
