package com.rsdp.dto.request;

import com.rsdp.dto.FloorPlanBBox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 户型图人工校正提交请求（接口 3，整体替换语义）。
 *
 * <p>提交的空间列表为最终生效数据：带 roomId 的为就地更新，不带 roomId 的为新增，
 * 已存在但未提交的空间视为误识别并软删。</p>
 */
@Data
public class FloorPlanConfirmRequest {

    /**
     * 确认后的空间列表（增删改整体提交）。
     */
    @NotNull(message = "空间列表不能为空")
    private List<@Valid RoomItem> rooms;

    /**
     * 人工确认的比例尺（像素:实际mm），可空。
     */
    private BigDecimal scaleRatio;

    /**
     * 单个空间校正项。
     */
    @Data
    public static class RoomItem {

        /** 已识别空间 ID；为空表示人工新增空间。 */
        private String roomId;

        /** 空间类型，引用 room_type 字典码（LIVING_ROOM/BEDROOM/...）。 */
        @NotBlank(message = "空间类型不能为空")
        @Size(max = 32, message = "空间类型长度不能超过 32")
        private String roomType;

        /** 开间 mm。 */
        @Min(value = 1, message = "开间必须为正数（mm）")
        private Integer widthMm;

        /** 进深 mm。 */
        @Min(value = 1, message = "进深必须为正数（mm）")
        private Integer depthMm;

        /** 空间框（归一化坐标），可空。 */
        @Valid
        private FloorPlanBBox bbox;
    }
}
