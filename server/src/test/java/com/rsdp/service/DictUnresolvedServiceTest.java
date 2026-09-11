package com.rsdp.service;

import com.rsdp.entity.DictUnresolvedValue;
import com.rsdp.mapper.DictUnresolvedValueMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DictUnresolvedService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class DictUnresolvedServiceTest {

    @Mock
    private DictUnresolvedValueMapper mapper;

    @InjectMocks
    private DictUnresolvedService service;

    @Test
    void record_firstSeen_shouldInsert() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.record("size", "贵妃A位", "BATCH-1", "admin");

        ArgumentCaptor<DictUnresolvedValue> captor = ArgumentCaptor.forClass(DictUnresolvedValue.class);
        verify(mapper).insert(captor.capture());
        DictUnresolvedValue record = captor.getValue();
        assertEquals("size", record.getDictType());
        assertEquals("贵妃A位", record.getRawValue());
        assertEquals(1, record.getOccurrenceCount());
        assertEquals("pending", record.getStatus());
        assertEquals("BATCH-1", record.getLastBatchId());
    }

    @Test
    void record_duplicate_shouldIncrementCount() {
        DictUnresolvedValue existing = new DictUnresolvedValue();
        existing.setId(1L);
        existing.setDictType("material");
        existing.setRawValue("A级布");
        existing.setOccurrenceCount(3);
        existing.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(existing);

        service.record("material", "A级布", "BATCH-2", "editor");

        assertEquals(4, existing.getOccurrenceCount());
        assertEquals("BATCH-2", existing.getLastBatchId());
        assertNotNull(existing.getLastSeenAt());
        verify(mapper).updateById(existing);
        verify(mapper, never()).insert(any(DictUnresolvedValue.class));
    }

    @Test
    void record_blankOrOverlong_shouldSkip() {
        service.record("size", "  ", null, null);
        service.record("size", "x".repeat(200), null, null);
        service.record("", "有效值", null, null);

        verify(mapper, never()).insert(any(DictUnresolvedValue.class));
    }

    @Test
    void record_mapperFailure_shouldNotPropagate() {
        when(mapper.selectOne(any())).thenThrow(new RuntimeException("DB down"));

        // 采集失败不阻断主流程：不抛异常
        service.record("color", "焦糖棕", null, null);
    }

    @Test
    void record_resolvedValue_shouldKeepStatusButCount() {
        DictUnresolvedValue existing = new DictUnresolvedValue();
        existing.setId(2L);
        existing.setDictType("size");
        existing.setRawValue("全套尺寸");
        existing.setOccurrenceCount(1);
        existing.setStatus("ignored");
        when(mapper.selectOne(any())).thenReturn(existing);

        service.record("size", "全套尺寸", null, null);

        assertEquals(2, existing.getOccurrenceCount());
        assertEquals("ignored", existing.getStatus());
        verify(mapper).updateById(existing);
    }

    @Test
    void recordOccurrences_firstSeen_shouldInsertWithCount() {
        // 3.4：批次聚合落库——首次出现按聚合计数插入，与逐条 record N 次的最终计数一致
        when(mapper.selectOne(any())).thenReturn(null);

        service.recordOccurrences("material", "半青皮", 5, "BATCH-1", "admin");

        ArgumentCaptor<DictUnresolvedValue> captor = ArgumentCaptor.forClass(DictUnresolvedValue.class);
        verify(mapper).insert(captor.capture());
        assertEquals(5, captor.getValue().getOccurrenceCount());
        assertEquals("pending", captor.getValue().getStatus());
        assertEquals("BATCH-1", captor.getValue().getLastBatchId());
    }

    @Test
    void recordOccurrences_duplicate_shouldIncrementByCount() {
        DictUnresolvedValue existing = new DictUnresolvedValue();
        existing.setId(3L);
        existing.setDictType("color");
        existing.setRawValue("焦糖棕");
        existing.setOccurrenceCount(2);
        existing.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(existing);

        service.recordOccurrences("color", "焦糖棕", 7, null, "editor");

        assertEquals(9, existing.getOccurrenceCount());
        verify(mapper).updateById(existing);
        verify(mapper, never()).insert(any(DictUnresolvedValue.class));
    }

    @Test
    void recordOccurrences_invalidCountOrBlank_shouldSkip() {
        service.recordOccurrences("size", "值", 0, null, null);
        service.recordOccurrences("size", "  ", 3, null, null);
        service.recordOccurrences("", "值", 3, null, null);

        verify(mapper, never()).insert(any(DictUnresolvedValue.class));
    }
}
