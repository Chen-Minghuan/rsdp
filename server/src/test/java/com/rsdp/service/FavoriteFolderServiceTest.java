package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.dto.request.FavoriteFolderRequest;
import com.rsdp.dto.response.FavoriteFolderResponse;
import com.rsdp.entity.FavoriteFolder;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FavoriteFolderMapper;
import com.rsdp.mapper.UserFavoriteMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FavoriteFolderService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FavoriteFolderServiceTest {

    @Mock
    private FavoriteFolderMapper folderMapper;

    @Mock
    private UserFavoriteMapper favoriteMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FavoriteFolderService favoriteFolderService;

    private FavoriteFolder folder(String id, String name) {
        FavoriteFolder folder = new FavoriteFolder();
        folder.setFolderId(id);
        folder.setUserId("user-1");
        folder.setFolderName(name);
        folder.setSortOrder(0);
        return folder;
    }

    private FavoriteFolderRequest request(String name) {
        FavoriteFolderRequest request = new FavoriteFolderRequest();
        request.setFolderName(name);
        return request;
    }

    @Test
    void listMyFoldersShouldReturnWithCounts() {
        when(folderMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(folder("FAVD-1", "客厅灵感"), folder("FAVD-2", "卧室灵感")));
        when(favoriteMapper.selectMaps(any(QueryWrapper.class))).thenReturn(List.of(
            Map.of("folder_id", "FAVD-1", "cnt", 3L)));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            List<FavoriteFolderResponse> folders = favoriteFolderService.listMyFolders();

            assertThat(folders).hasSize(2);
            assertThat(folders.get(0).getFavoriteCount()).isEqualTo(3);
            assertThat(folders.get(1).getFavoriteCount()).isEqualTo(0);
        }
    }

    @Test
    void createShouldInsertFolder() {
        when(folderMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer");

            FavoriteFolderResponse response = favoriteFolderService.create(request("新文件夹"));

            assertThat(response.getFolderName()).isEqualTo("新文件夹");
            verify(folderMapper).insert(any(FavoriteFolder.class));
            verify(auditLogService).logCreate(eq("favorite_folder"), anyString(), any(FavoriteFolder.class), eq("designer"));
        }
    }

    @Test
    void createShouldRejectDuplicateName() {
        when(folderMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> favoriteFolderService.create(request("客厅灵感")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同名文件夹已存在");
            verify(folderMapper, never()).insert(any(FavoriteFolder.class));
        }
    }

    @Test
    void renameShouldSyncFavoriteGroupName() {
        when(folderMapper.selectById("FAVD-1")).thenReturn(folder("FAVD-1", "旧名"));
        when(folderMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L, 2L);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer");

            FavoriteFolderResponse response = favoriteFolderService.rename("FAVD-1", request("新名"));

            assertThat(response.getFolderName()).isEqualTo("新名");
            verify(folderMapper).updateById(any(FavoriteFolder.class));
            verify(favoriteMapper).update(any(), any(UpdateWrapper.class));
            verify(auditLogService).logUpdate(eq("favorite_folder"), eq("FAVD-1"), any(), any(FavoriteFolder.class), eq("designer"));
        }
    }

    @Test
    void renameShouldRejectOtherUsersFolder() {
        FavoriteFolder other = folder("FAVD-9", "他人文件夹");
        other.setUserId("user-2");
        when(folderMapper.selectById("FAVD-9")).thenReturn(other);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> favoriteFolderService.rename("FAVD-9", request("新名")))
                .isInstanceOf(ResourceNotFoundException.class);
            verify(folderMapper, never()).updateById(any(FavoriteFolder.class));
        }
    }

    @Test
    void deleteShouldSoftDeleteAndUnfileFavorites() {
        when(folderMapper.selectById("FAVD-1")).thenReturn(folder("FAVD-1", "客厅灵感"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer");

            favoriteFolderService.delete("FAVD-1");

            verify(folderMapper).deleteById("FAVD-1");
            verify(favoriteMapper).update(any(), any(UpdateWrapper.class));
            verify(auditLogService).logDelete(eq("favorite_folder"), eq("FAVD-1"), any(), eq("designer"));
        }
    }
}
