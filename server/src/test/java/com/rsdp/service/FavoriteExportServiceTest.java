package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.FavoriteFolder;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.UserFavorite;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.FavoriteFolderMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.UserFavoriteMapper;
import com.rsdp.security.Permissions;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link FavoriteExportService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FavoriteExportServiceTest {

    @Mock
    private UserFavoriteMapper favoriteMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private FavoriteFolderMapper favoriteFolderMapper;

    @Mock
    private com.rsdp.security.datascope.DataScopeHelper dataScopeHelper;

    @InjectMocks
    private FavoriteExportService favoriteExportService;

    private UserFavorite favorite() {
        UserFavorite favorite = new UserFavorite();
        favorite.setFavoriteId("FAV-1");
        favorite.setUserId("user-1");
        favorite.setRspuId("RSPU-1");
        favorite.setFolderId("FAVD-1");
        favorite.setGroupName("客厅灵感");
        favorite.setCreatedAt(LocalDateTime.now());
        return favorite;
    }

    private void stubProduct() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setPositioningLabel("北欧布艺沙发");
        rspu.setCategoryPath("沙发");
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rspu));
        FavoriteFolder folder = new FavoriteFolder();
        folder.setFolderId("FAVD-1");
        folder.setFolderName("客厅灵感");
        when(favoriteFolderMapper.selectBatchIds(anyList())).thenReturn(List.of(folder));
    }

    @Test
    void exportShouldProduceExcelWithoutSupplierByDefault() {
        when(favoriteMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(favorite()));
        stubProduct();

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            FavoriteExportService.FavoriteExportFile file = favoriteExportService.export(null, false);

            assertThat(file.content()).isNotEmpty();
            assertThat(file.fileName()).isEqualTo("我的收藏夹.xlsx");
        }
    }

    @Test
    void exportShouldUseFolderNameAsFileNameWhenFolderIdGiven() {
        when(favoriteMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(favorite()));
        stubProduct();

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            FavoriteExportService.FavoriteExportFile file = favoriteExportService.export("FAVD-1", false);

            assertThat(file.content()).isNotEmpty();
            assertThat(file.fileName()).isEqualTo("客厅灵感.xlsx");
        }
    }

    @Test
    void exportShouldRejectSupplierWhenNoFactoryReadPermission() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.hasAuthority(Permissions.FACTORY_READ)).thenReturn(false);

            assertThatThrownBy(() -> favoriteExportService.export(null, true))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("factory:read");
        }
    }

    @Test
    void exportShouldIncludeSupplierWhenPermitted() {
        when(favoriteMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(favorite()));
        stubProduct();
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-1");
        rsku.setRspuId("RSPU-1");
        rsku.setFactoryCode("F001");
        rsku.setFactoryPrice(new BigDecimal("888.00"));
        rsku.setLeadTimeDays(25);
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rsku));
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");
        when(factoryMasterMapper.selectBatchIds(anyList())).thenReturn(List.of(factory));
        when(dataScopeHelper.canViewFactoryPrice(any())).thenReturn(true);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.hasAuthority(Permissions.FACTORY_READ)).thenReturn(true);

            FavoriteExportService.FavoriteExportFile file = favoriteExportService.export(null, true);

            assertThat(file.content()).isNotEmpty();
        }
    }

    @Test
    void exportShouldRejectEmptyFavorites() {
        when(favoriteMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> favoriteExportService.export(null, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收藏夹为空");
        }
    }
}
