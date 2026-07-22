package com.rsdp.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
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
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 收藏夹导出服务：按文件夹导出 Excel（明细 + 按文件夹汇总双 sheet）。
 *
 * <p>保护货源：默认仅产品维度；{@code isSup=true} 显示工厂/出厂价需 {@code factory:read} 权限。</p>
 */
@Service
@RequiredArgsConstructor
public class FavoriteExportService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserFavoriteMapper favoriteMapper;
    private final RspuMapper rspuMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final FavoriteFolderMapper favoriteFolderMapper;

    /**
     * 导出文件（内容 + 文件名）。
     *
     * @param content  Excel 字节
     * @param fileName 文件名（含 .xlsx 后缀）
     */
    public record FavoriteExportFile(byte[] content, String fileName) {
    }

    /**
     * 导出当前用户的收藏夹 Excel。
     *
     * @param folderId 按文件夹导出（可选，空为全部收藏）
     * @param isSup    是否显示供应商（工厂/出厂价）；需要 factory:read 权限
     * @return 导出文件
     */
    public FavoriteExportFile export(String folderId, boolean isSup) {
        String userId = currentUserIdRequired();
        if (isSup && !SecurityOperatorContext.hasAuthority(Permissions.FACTORY_READ)) {
            throw new ForbiddenException("显示供应商信息需要 factory:read 权限");
        }

        QueryWrapper<UserFavorite> wrapper = new QueryWrapper<UserFavorite>()
            .eq("user_id", userId)
            .orderByDesc("created_at");
        if (StringUtils.hasText(folderId)) {
            wrapper.eq("folder_id", folderId);
        }
        List<UserFavorite> favorites = favoriteMapper.selectList(wrapper);
        if (favorites.isEmpty()) {
            throw new BusinessException("收藏夹为空，无法导出");
        }

        // 批量预加载：产品 / 文件夹
        List<String> rspuIds = favorites.stream().map(UserFavorite::getRspuId).distinct().toList();
        Map<String, RspuMaster> rspuMap = rspuMapper.selectList(
            new QueryWrapper<RspuMaster>().in("rspu_id", rspuIds)
        ).stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));

        List<String> folderIds = favorites.stream().map(UserFavorite::getFolderId)
            .filter(StringUtils::hasText).distinct().toList();
        Map<String, String> folderNameMap = folderIds.isEmpty()
            ? Map.of()
            : favoriteFolderMapper.selectBatchIds(folderIds).stream()
                .collect(Collectors.toMap(FavoriteFolder::getFolderId, FavoriteFolder::getFolderName, (a, b) -> a));

        String exportName = StringUtils.hasText(folderId)
            ? folderNameMap.getOrDefault(folderId, "收藏夹")
            : "我的收藏夹";

        // 供应商维度：每个产品取最低价 RSKU
        Map<String, RskuSupply> cheapestRskuMap = Map.of();
        Map<String, String> factoryNameByCode = Map.of();
        if (isSup) {
            List<RskuSupply> rskus = rskuSupplyMapper.selectList(
                new QueryWrapper<RskuSupply>().in("rspu_id", rspuIds));
            cheapestRskuMap = rskus.stream()
                .filter(r -> r.getFactoryPrice() != null)
                .collect(Collectors.toMap(
                    RskuSupply::getRspuId, r -> r,
                    (a, b) -> a.getFactoryPrice().compareTo(b.getFactoryPrice()) <= 0 ? a : b));
            List<String> factoryCodes = cheapestRskuMap.values().stream()
                .map(RskuSupply::getFactoryCode).distinct().toList();
            factoryNameByCode = factoryCodes.isEmpty()
                ? Map.of()
                : factoryMasterMapper.selectBatchIds(factoryCodes).stream()
                    .collect(Collectors.toMap(FactoryMaster::getFactoryCode, FactoryMaster::getFactoryName, (a, b) -> a));
        }

        byte[] content = writeExcel(favorites, rspuMap, folderNameMap, cheapestRskuMap, factoryNameByCode, isSup);
        return new FavoriteExportFile(content, exportName + ".xlsx");
    }

    private byte[] writeExcel(List<UserFavorite> favorites,
                              Map<String, RspuMaster> rspuMap,
                              Map<String, String> folderNameMap,
                              Map<String, RskuSupply> cheapestRskuMap,
                              Map<String, String> factoryNameByCode,
                              boolean isSup) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(outputStream)
            .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
            .build()) {

            if (isSup) {
                List<SupplierRow> rows = new ArrayList<>();
                for (int i = 0; i < favorites.size(); i++) {
                    rows.add(toSupplierRow(i + 1, favorites.get(i), rspuMap, folderNameMap, cheapestRskuMap, factoryNameByCode));
                }
                WriteSheet sheet = EasyExcel.writerSheet("收藏明细").head(SupplierRow.class).build();
                writer.write(rows, sheet);
            } else {
                List<ProductRow> rows = new ArrayList<>();
                for (int i = 0; i < favorites.size(); i++) {
                    rows.add(toProductRow(i + 1, favorites.get(i), rspuMap, folderNameMap));
                }
                WriteSheet sheet = EasyExcel.writerSheet("收藏明细").head(ProductRow.class).build();
                writer.write(rows, sheet);
            }

            WriteSheet summarySheet = EasyExcel.writerSheet("按文件夹汇总").head(FolderSummaryRow.class).build();
            writer.write(buildSummaryRows(favorites, folderNameMap), summarySheet);
        }
        return outputStream.toByteArray();
    }

    private ProductRow toProductRow(int seq, UserFavorite favorite,
                                    Map<String, RspuMaster> rspuMap,
                                    Map<String, String> folderNameMap) {
        RspuMaster rspu = rspuMap.get(favorite.getRspuId());
        ProductRow row = new ProductRow();
        row.setSeq(seq);
        row.setProductName(rspu != null ? rspu.getPositioningLabel() : null);
        row.setRspuId(favorite.getRspuId());
        row.setCategoryPath(rspu != null ? rspu.getCategoryPath() : null);
        row.setFolderName(resolveFolderName(favorite, folderNameMap));
        row.setCreatedAt(formatTime(favorite.getCreatedAt()));
        return row;
    }

    private SupplierRow toSupplierRow(int seq, UserFavorite favorite,
                                      Map<String, RspuMaster> rspuMap,
                                      Map<String, String> folderNameMap,
                                      Map<String, RskuSupply> cheapestRskuMap,
                                      Map<String, String> factoryNameByCode) {
        RspuMaster rspu = rspuMap.get(favorite.getRspuId());
        RskuSupply rsku = cheapestRskuMap.get(favorite.getRspuId());
        SupplierRow row = new SupplierRow();
        row.setSeq(seq);
        row.setProductName(rspu != null ? rspu.getPositioningLabel() : null);
        row.setRspuId(favorite.getRspuId());
        row.setCategoryPath(rspu != null ? rspu.getCategoryPath() : null);
        row.setFolderName(resolveFolderName(favorite, folderNameMap));
        row.setCreatedAt(formatTime(favorite.getCreatedAt()));
        row.setFactoryCode(rsku != null ? rsku.getFactoryCode() : "-");
        row.setFactoryName(rsku != null ? factoryNameByCode.getOrDefault(rsku.getFactoryCode(), "-") : "-");
        row.setFactoryPrice(rsku != null ? formatPrice(rsku.getFactoryPrice()) : "-");
        row.setLeadTimeDays(rsku != null ? rsku.getLeadTimeDays() : null);
        return row;
    }

    private List<FolderSummaryRow> buildSummaryRows(List<UserFavorite> favorites, Map<String, String> folderNameMap) {
        Map<String, Long> countByFolder = favorites.stream().collect(Collectors.groupingBy(
            f -> resolveFolderName(f, folderNameMap), LinkedHashMap::new, Collectors.counting()));
        List<FolderSummaryRow> rows = new ArrayList<>();
        countByFolder.forEach((name, count) -> rows.add(new FolderSummaryRow(name, count.intValue())));
        rows.add(new FolderSummaryRow("总计", favorites.size()));
        return rows;
    }

    private String resolveFolderName(UserFavorite favorite, Map<String, String> folderNameMap) {
        if (StringUtils.hasText(favorite.getFolderId())) {
            return folderNameMap.getOrDefault(favorite.getFolderId(), favorite.getGroupName());
        }
        return StringUtils.hasText(favorite.getGroupName()) ? favorite.getGroupName() : "未归档";
    }

    private String formatPrice(BigDecimal price) {
        return "¥" + price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatTime(LocalDateTime time) {
        return time != null ? time.format(DATE_TIME_FORMATTER) : null;
    }

    private String currentUserIdRequired() {
        String userId = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        return userId;
    }

    /**
     * 收藏明细行（产品维度，默认）。
     */
    @Data
    public static class ProductRow {

        @ExcelProperty("序号")
        private Integer seq;

        @ExcelProperty("产品名称")
        private String productName;

        @ExcelProperty("RSPU ID")
        private String rspuId;

        @ExcelProperty("品类")
        private String categoryPath;

        @ExcelProperty("文件夹")
        private String folderName;

        @ExcelProperty("收藏时间")
        private String createdAt;
    }

    /**
     * 收藏明细行（含供应商，需 factory:read 权限）。
     */
    @Data
    public static class SupplierRow {

        @ExcelProperty("序号")
        private Integer seq;

        @ExcelProperty("产品名称")
        private String productName;

        @ExcelProperty("RSPU ID")
        private String rspuId;

        @ExcelProperty("品类")
        private String categoryPath;

        @ExcelProperty("文件夹")
        private String folderName;

        @ExcelProperty("收藏时间")
        private String createdAt;

        @ExcelProperty("工厂编码")
        private String factoryCode;

        @ExcelProperty("工厂名称")
        private String factoryName;

        @ExcelProperty("最低出厂价")
        private String factoryPrice;

        @ExcelProperty("交期(天)")
        private Integer leadTimeDays;
    }

    /**
     * 按文件夹汇总行。
     */
    @Data
    public static class FolderSummaryRow {

        @ExcelProperty("文件夹")
        private String folderName;

        @ExcelProperty("收藏数")
        private Integer favoriteCount;

        public FolderSummaryRow(String folderName, Integer favoriteCount) {
            this.folderName = folderName;
            this.favoriteCount = favoriteCount;
        }
    }
}
