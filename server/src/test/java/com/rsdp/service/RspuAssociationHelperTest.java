package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RspuAssociationHelper} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RspuAssociationHelperTest {

    @Mock
    private RspuStyleMapper rspuStyleMapper;
    @Mock
    private RspuSceneMapper rspuSceneMapper;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private RspuAssociationHelper helper;

    private RspuStyle styleRow(String code, boolean primary) {
        RspuStyle s = new RspuStyle();
        s.setRspuId("RSPU-1");
        s.setDictType("style");
        s.setStyleCode(code);
        s.setIsPrimary(primary);
        return s;
    }

    private RspuScene sceneRow(String code) {
        RspuScene s = new RspuScene();
        s.setRspuId("RSPU-1");
        s.setDictType("scene");
        s.setSceneCode(code);
        return s;
    }

    @Test
    void replaceStyles_withAudit_shouldSnapshotDeleteInsertAndLog() {
        when(rspuStyleMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(styleRow("OLD", true)));

        helper.replaceStyles("RSPU-1", List.of(styleRow("MC", true), styleRow("CR", false)), "admin", true);

        verify(rspuStyleMapper).delete(any(QueryWrapper.class));
        verify(rspuStyleMapper, org.mockito.Mockito.times(2)).insert(any(RspuStyle.class));
        verify(auditLogService).logUpdate(eq("rspu_style"), eq("RSPU-1"),
            eq(Map.of("styleCodes", List.of("OLD"))),
            eq(Map.of("styleCodes", List.of("MC", "CR"))), eq("admin"));
    }

    @Test
    void replaceStyles_withoutAudit_shouldSkipOldSelectAndLog() {
        helper.replaceStyles("RSPU-1", List.of(styleRow("MC", true)), null, false);

        verify(rspuStyleMapper, never()).selectList(any(QueryWrapper.class));
        verify(rspuStyleMapper).delete(any(QueryWrapper.class));
        verify(rspuStyleMapper).insert(any(RspuStyle.class));
        verify(auditLogService, never()).logUpdate(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void replaceScenes_withAudit_shouldSnapshotDeleteInsertAndLog() {
        when(rspuSceneMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(sceneRow("OLD")));

        helper.replaceScenes("RSPU-1", List.of(sceneRow("LIVING")), "admin", true);

        verify(rspuSceneMapper).delete(any(QueryWrapper.class));
        verify(rspuSceneMapper).insert(any(RspuScene.class));
        verify(auditLogService).logUpdate(eq("rspu_scene"), eq("RSPU-1"),
            eq(Map.of("sceneCodes", List.of("OLD"))),
            eq(Map.of("sceneCodes", List.of("LIVING"))), eq("admin"));
    }

    @Test
    void replaceScenes_withoutAudit_shouldSkipOldSelectAndLog() {
        helper.replaceScenes("RSPU-1", List.of(), null, false);

        verify(rspuSceneMapper, never()).selectList(any(QueryWrapper.class));
        verify(rspuSceneMapper).delete(any(QueryWrapper.class));
        verify(auditLogService, never()).logUpdate(anyString(), anyString(), any(), any(), any());
    }
}
