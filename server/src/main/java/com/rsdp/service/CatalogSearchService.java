package com.rsdp.service;

import com.rsdp.exception.BusinessException;
import com.rsdp.service.vector.ProductVectorStore;
import com.rsdp.service.vector.VectorHit;
import com.rsdp.util.ImageUploadValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** 为商品目录提供向量候选。列表服务负责最终的权限和筛选。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogSearchService {

    private static final int IMAGE_MAX_BYTES = 10 * 1024 * 1024;
    private static final int PHOTO_LIMIT = 200;
    private static final int SEMANTIC_PRODUCT_LIMIT = 60;
    private static final int CACHE_LIMIT = 256;
    private static final long IMAGE_TTL_MS = Duration.ofMinutes(30).toMillis();
    private static final long TEXT_TTL_MS = Duration.ofMinutes(2).toMillis();

    private final EmbeddingService embeddingService;
    private final ProductVectorStore vectorStore;
    private final ImageUploadValidator imageUploadValidator;

    private final LinkedHashMap<RecallKey, Recall> textRecalls = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<RecallKey, Recall> imageRecalls = new LinkedHashMap<>(16, 0.75f, true);

    /** 上传参考图，返回临时搜索 ID。仅保存召回的款式 ID。 */
    public String startImageSearch(MultipartFile image, boolean activeOnly) {
        imageUploadValidator.validate(image, IMAGE_MAX_BYTES);
        try (var stream = image.getInputStream()) {
            List<String> ids = recall(embeddingService.embedImage(stream), activeOnly);
            String id = UUID.randomUUID().toString();
            remember(imageRecalls, new RecallKey(id, activeOnly), ids, IMAGE_TTL_MS);
            return id;
        } catch (IOException e) {
            throw new BusinessException("读取参考图失败，请重新上传");
        }
    }

    /** 解析图搜 ID 或生成文本候选。文本模型不可用时仍保留名称匹配。 */
    public Match match(String keyword, String imageSearchId, boolean activeOnly) {
        if (StringUtils.hasText(imageSearchId)) {
            Recall image = lookup(imageRecalls, new RecallKey(imageSearchId, activeOnly));
            if (image == null) {
                throw new BusinessException("图片搜索已过期，请重新上传参考图");
            }
            return new Match(null, image.ids());
        }
        String text = keyword.trim();
        RecallKey key = new RecallKey(text, activeOnly);
        Recall cached = lookup(textRecalls, key);
        if (cached != null) {
            return new Match(text, cached.ids());
        }
        try {
            List<String> ids = recall(embeddingService.embedText(text), activeOnly);
            remember(textRecalls, key, ids, TEXT_TTL_MS);
            return new Match(text, ids);
        } catch (RuntimeException e) {
            log.warn("商品语义搜索失败，回退字段匹配", e);
            return new Match(text, List.of());
        }
    }

    private List<String> recall(float[] vector, boolean activeOnly) {
        return vectorStore.search(vector, PHOTO_LIMIT, null, activeOnly).stream()
            .map(VectorHit::rspuId).filter(StringUtils::hasText).distinct().limit(SEMANTIC_PRODUCT_LIMIT).toList();
    }

    private Recall lookup(LinkedHashMap<RecallKey, Recall> cache, RecallKey key) {
        synchronized (cache) {
            Recall entry = cache.get(key);
            if (entry != null && entry.expiresAt() > System.currentTimeMillis()) {
                return entry;
            }
            cache.remove(key);
            return null;
        }
    }

    private void remember(LinkedHashMap<RecallKey, Recall> cache, RecallKey key, List<String> ids, long ttl) {
        synchronized (cache) {
            cache.put(key, new Recall(ids, System.currentTimeMillis() + ttl));
            if (cache.size() > CACHE_LIMIT) {
                cache.pollFirstEntry();
            }
        }
    }

    /** 用户输入的文字和按相似度排列的款式 ID。纯图片查询的文字为空。 */
    public record Match(String keyword, List<String> rankedIds) {}

    private record RecallKey(String input, boolean activeOnly) {}

    private record Recall(List<String> ids, long expiresAt) {}
}
