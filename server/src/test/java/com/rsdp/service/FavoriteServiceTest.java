package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.FavoriteRequest;
import com.rsdp.dto.response.FavoriteResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.UserFavorite;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.UserFavoriteMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FavoriteService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FavoriteServiceTest {

    @Mock
    private UserFavoriteMapper favoriteMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private FavoriteFolderService favoriteFolderService;

    @InjectMocks
    private FavoriteService favoriteService;

    @Test
    void addShouldInsertFavoriteWhenProductExists() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setPositioningLabel("北欧布艺沙发");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);
        when(favoriteMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        FavoriteRequest request = new FavoriteRequest();
        request.setRspuId("RSPU-1");
        request.setGroupName("客厅方案");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            FavoriteResponse response = favoriteService.add(request);

            assertThat(response.getRspuId()).isEqualTo("RSPU-1");
            assertThat(response.getProductName()).isEqualTo("北欧布艺沙发");
            assertThat(response.getGroupName()).isEqualTo("客厅方案");
        }

        ArgumentCaptor<UserFavorite> captor = ArgumentCaptor.forClass(UserFavorite.class);
        verify(favoriteMapper).insert(captor.capture());
        UserFavorite saved = captor.getValue();
        assertThat(saved.getFavoriteId()).startsWith("FAV-");
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getRspuId()).isEqualTo("RSPU-1");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void addShouldRejectDuplicateFavorite() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);
        when(favoriteMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        FavoriteRequest request = new FavoriteRequest();
        request.setRspuId("RSPU-1");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> favoriteService.add(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已收藏");
        }
        verify(favoriteMapper, never()).insert(any(UserFavorite.class));
    }

    @Test
    void addShouldRejectMissingProduct() {
        when(rspuMapper.selectById("RSPU-X")).thenReturn(null);

        FavoriteRequest request = new FavoriteRequest();
        request.setRspuId("RSPU-X");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> favoriteService.add(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("产品不存在");
        }
        verify(favoriteMapper, never()).insert(any(UserFavorite.class));
    }

    @Test
    void removeShouldDeleteOwnFavorite() {
        when(favoriteMapper.delete(any(QueryWrapper.class))).thenReturn(1);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            favoriteService.remove("RSPU-1");
        }
        verify(favoriteMapper).delete(any(QueryWrapper.class));
    }

    @Test
    void removeShouldRejectNotFavoritedProduct() {
        when(favoriteMapper.delete(any(QueryWrapper.class))).thenReturn(0);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> favoriteService.remove("RSPU-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("未收藏");
        }
    }

    @Test
    void listShouldEnrichProductNameAndPrimaryImage() {
        UserFavorite favorite = new UserFavorite();
        favorite.setFavoriteId("FAV-1");
        favorite.setUserId("user-1");
        favorite.setRspuId("RSPU-1");
        when(favoriteMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(favorite));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setPositioningLabel("极简茶几");
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rspu));

        ImageAssets image = new ImageAssets();
        image.setRspuId("RSPU-1");
        image.setImageId("IMG-1");
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(image));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            List<FavoriteResponse> responses = favoriteService.list(null, false);

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getProductName()).isEqualTo("极简茶几");
            assertThat(responses.get(0).getPrimaryImageUrl()).isEqualTo("/api/v1/images/IMG-1");
        }
    }

    @Test
    void addShouldUseFolderWhenFolderIdProvided() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);
        when(favoriteMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        com.rsdp.entity.FavoriteFolder folder = new com.rsdp.entity.FavoriteFolder();
        folder.setFolderId("FAVD-1");
        folder.setUserId("user-1");
        folder.setFolderName("客厅灵感");
        when(favoriteFolderService.getFolderInUser("FAVD-1", "user-1")).thenReturn(folder);

        FavoriteRequest request = new FavoriteRequest();
        request.setRspuId("RSPU-1");
        request.setFolderId("FAVD-1");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            FavoriteResponse response = favoriteService.add(request);

            assertThat(response.getFolderId()).isEqualTo("FAVD-1");
            // 同步轻量文本字段保持向后兼容
            assertThat(response.getGroupName()).isEqualTo("客厅灵感");
        }

        ArgumentCaptor<UserFavorite> captor = ArgumentCaptor.forClass(UserFavorite.class);
        verify(favoriteMapper).insert(captor.capture());
        assertThat(captor.getValue().getFolderId()).isEqualTo("FAVD-1");
    }

    @Test
    void moveShouldUpdateFolderAndSyncGroupName() {
        UserFavorite favorite = new UserFavorite();
        favorite.setFavoriteId("FAV-1");
        favorite.setUserId("user-1");
        favorite.setRspuId("RSPU-1");
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(favorite);
        when(rspuMapper.selectById("RSPU-1")).thenReturn(new RspuMaster());
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        com.rsdp.entity.FavoriteFolder folder = new com.rsdp.entity.FavoriteFolder();
        folder.setFolderId("FAVD-1");
        folder.setUserId("user-1");
        folder.setFolderName("卧室灵感");
        when(favoriteFolderService.getFolderInUser("FAVD-1", "user-1")).thenReturn(folder);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            FavoriteResponse response = favoriteService.move("RSPU-1", "FAVD-1");

            assertThat(response.getFolderId()).isEqualTo("FAVD-1");
            assertThat(response.getGroupName()).isEqualTo("卧室灵感");
            verify(favoriteMapper).update(any(), any(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class));
        }
    }

    @Test
    void moveShouldClearFolderWhenFolderIdNull() {
        UserFavorite favorite = new UserFavorite();
        favorite.setFavoriteId("FAV-1");
        favorite.setUserId("user-1");
        favorite.setRspuId("RSPU-1");
        favorite.setFolderId("FAVD-1");
        favorite.setGroupName("客厅灵感");
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(favorite);
        when(rspuMapper.selectById("RSPU-1")).thenReturn(new RspuMaster());
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            FavoriteResponse response = favoriteService.move("RSPU-1", null);

            assertThat(response.getFolderId()).isNull();
            assertThat(response.getGroupName()).isNull();
        }
    }

    @Test
    void moveShouldRejectNotFavoritedProduct() {
        when(favoriteMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> favoriteService.move("RSPU-X", "FAVD-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("未收藏");
        }
    }

    @Test
    void checkShouldReturnOnlyFavoritedRspuIds() {
        UserFavorite favorite = new UserFavorite();
        favorite.setRspuId("RSPU-1");
        when(favoriteMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(favorite));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            List<String> result = favoriteService.check(List.of("RSPU-1", "RSPU-2"));

            assertThat(result).containsExactly("RSPU-1");
        }
    }

    @Test
    void checkShouldReturnEmptyForEmptyInput() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThat(favoriteService.check(List.of())).isEmpty();
        }
        verify(favoriteMapper, never()).selectList(any(QueryWrapper.class));
    }
}
