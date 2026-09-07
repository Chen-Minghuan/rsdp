package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 复合主键 Mapper 防护与显式复合键方法语义测试。
 *
 * <p>使用 CALLS_REAL_METHODS 让接口 default 方法真实执行、抽象 BaseMapper 方法走 mock，
 * 从而验证：① 显式复合键方法生成的 Wrapper 包含全部主键列；
 * ② 伪 @TableId 下的 updateById/deleteById/selectById 被代码层禁用（抛 UOE）。</p>
 */
class CompositeKeyMapperTest {

    private final CategoryDictMapper categoryDictMapper = mock(CategoryDictMapper.class, CALLS_REAL_METHODS);
    private final RspuStyleMapper rspuStyleMapper = mock(RspuStyleMapper.class, CALLS_REAL_METHODS);
    private final RspuSceneMapper rspuSceneMapper = mock(RspuSceneMapper.class, CALLS_REAL_METHODS);

    @Test
    void categoryDict_deleteByCompositeKey_shouldUseFullCompositeWhere() {
        categoryDictMapper.deleteByCompositeKey("material", "PE");

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(categoryDictMapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .contains("dict_type")
            .contains("dict_code");
    }

    @Test
    void categoryDict_selectByCompositeKey_shouldUseFullCompositeWhere() {
        categoryDictMapper.selectByCompositeKey("scene", "living_room");

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(categoryDictMapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .contains("dict_type")
            .contains("dict_code");
    }

    @Test
    void categoryDict_updateByCompositeKey_shouldPassEntityAndFullCompositeWhere() {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("material");
        dict.setDictCode("PE");
        dict.setDictName("真皮");

        categoryDictMapper.updateByCompositeKey(dict);

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(categoryDictMapper).update(any(CategoryDict.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .contains("dict_type")
            .contains("dict_code");
    }

    @Test
    void rspuStyle_compositeKeyMethods_shouldUseFullCompositeWhere() {
        rspuStyleMapper.deleteByCompositeKey("RSPU-1", "MC");
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuStyleMapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .contains("rspu_id")
            .contains("style_code");

        RspuStyle style = new RspuStyle();
        style.setRspuId("RSPU-1");
        style.setStyleCode("MC");
        style.setIsPrimary(true);
        rspuStyleMapper.updateByCompositeKey(style);
        ArgumentCaptor<QueryWrapper> updateCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuStyleMapper).update(any(RspuStyle.class), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSegment())
            .contains("rspu_id")
            .contains("style_code");
    }

    @Test
    void rspuScene_compositeKeyMethods_shouldUseFullCompositeWhere() {
        rspuSceneMapper.deleteByCompositeKey("RSPU-1", "living_room");
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuSceneMapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .contains("rspu_id")
            .contains("scene_code");

        RspuScene scene = new RspuScene();
        scene.setRspuId("RSPU-1");
        scene.setSceneCode("living_room");
        rspuSceneMapper.updateByCompositeKey(scene);
        ArgumentCaptor<QueryWrapper> updateCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rspuSceneMapper).update(any(RspuScene.class), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSegment())
            .contains("rspu_id")
            .contains("scene_code");
    }

    @Test
    void categoryDict_idBasedMethods_shouldBeBlocked() {
        assertThatThrownBy(() -> categoryDictMapper.updateById(new CategoryDict()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> categoryDictMapper.deleteById(new CategoryDict()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> categoryDictMapper.selectById("material"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rspuStyle_idBasedMethods_shouldBeBlocked() {
        assertThatThrownBy(() -> rspuStyleMapper.updateById(new RspuStyle()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rspuStyleMapper.deleteById(new RspuStyle()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rspuStyleMapper.selectById("RSPU-1"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rspuScene_idBasedMethods_shouldBeBlocked() {
        assertThatThrownBy(() -> rspuSceneMapper.updateById(new RspuScene()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rspuSceneMapper.deleteById(new RspuScene()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rspuSceneMapper.selectById("RSPU-1"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
