package com.rsdp.service;

import com.rsdp.config.CacheConfig;
import com.rsdp.entity.CategoryDict;
import com.rsdp.mapper.CategoryDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 字典服务。
 */
@Service
@RequiredArgsConstructor
public class DictService {

    private final CategoryDictMapper categoryDictMapper;

    /**
     * 按类型查询有效字典项。
     *
     * @param dictType 字典类型
     * @return 字典列表
     */
    @Cacheable(value = CacheConfig.CACHE_NAME_DICTS, keyGenerator = "simpleKeyGenerator")
    public List<CategoryDict> listByType(String dictType) {
        return categoryDictMapper.selectByType(dictType);
    }
}
