package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.UserFavorite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户收藏 Mapper。
 */
@Mapper
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {
}
