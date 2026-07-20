package com.rsdp.service.chroma;

import com.rsdp.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * ChromaDB REST API 客户端（v2 API）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChromaDbClient {

    private final RestClient chromaRestClient;
    private final ChromaDbProperties properties;

    private final AtomicReference<String> collectionIdCache = new AtomicReference<>();

    private String baseCollectionPath() {
        return "/api/v2/tenants/default_tenant/databases/default_database/collections";
    }

    /**
     * 获取或创建集合并返回集合 ID。
     */
    private String getOrCreateCollectionId() {
        String cached = collectionIdCache.get();
        if (cached != null) {
            return cached;
        }

        String collectionName = properties.getCollection();
        try {
            Map<String, Object> collection = chromaRestClient.get()
                .uri(baseCollectionPath() + "/{name}", collectionName)
                .retrieve()
                .body(Map.class);
            if (collection == null || collection.get("id") == null) {
                return createCollection(collectionName);
            }
            String id = (String) collection.get("id");
            collectionIdCache.set(id);
            log.debug("ChromaDB 集合已存在: {} -> {}", collectionName, id);
            return id;
        } catch (Exception e) {
            log.info("ChromaDB 集合不存在或查询失败，正在创建: {}", collectionName);
            return createCollection(collectionName);
        }
    }

    private String createCollection(String collectionName) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", collectionName);
        body.put("metadata", Map.of("hnsw:space", "cosine"));

        try {
            Map<String, Object> collection = chromaRestClient.post()
                .uri(baseCollectionPath())
                .body(body)
                .retrieve()
                .body(Map.class);

            if (collection == null || collection.get("id") == null) {
                throw new ExternalServiceException("ChromaDB 创建集合返回为空");
            }
            String id = (String) collection.get("id");
            collectionIdCache.set(id);
            log.info("ChromaDB 集合创建完成: {} -> {}", collectionName, id);
            return id;
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("ChromaDB 创建集合失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断异常是否表示「集合不存在」（404 或错误信息含 does not exist）。
     */
    private boolean isCollectionNotFound(Exception e) {
        if (e instanceof HttpClientErrorException.NotFound) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.contains("does not exist");
    }

    /**
     * 使用缓存的集合 ID 执行请求；若返回「集合不存在」类错误，
     * 清除缓存条目并重新解析集合 ID 后重试一次，仍失败则抛出原异常。
     *
     * @param action 以集合 ID 为参数的请求动作
     * @param <T>    返回类型
     * @return 请求结果
     */
    private <T> T executeWithCollectionRetry(Function<String, T> action) {
        String collectionId = getOrCreateCollectionId();
        try {
            return action.apply(collectionId);
        } catch (Exception e) {
            if (!isCollectionNotFound(e)) {
                throw e;
            }
            log.warn("缓存的 ChromaDB 集合 ID 可能已失效，清除缓存并重试一次: {}", e.getMessage());
        }
        collectionIdCache.set(null);
        String refreshedId = getOrCreateCollectionId();
        return action.apply(refreshedId);
    }

    /**
     * 批量写入或更新向量记录。
     */
    public void upsert(List<String> ids, List<float[]> embeddings, List<Map<String, Object>> metadatas, List<String> documents) {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("embeddings", embeddings);
        body.put("metadatas", metadatas);
        if (documents != null) {
            body.put("documents", documents);
        }

        try {
            executeWithCollectionRetry(collectionId -> {
                chromaRestClient.post()
                    .uri(baseCollectionPath() + "/{id}/upsert", collectionId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
                return null;
            });
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("ChromaDB upsert 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据向量查询相似记录。
     */
    @SuppressWarnings("unchecked")
    public QueryResult query(float[] queryEmbedding, int topK, Map<String, Object> where) {
        Map<String, Object> body = new HashMap<>();
        body.put("query_embeddings", List.of(queryEmbedding));
        body.put("n_results", topK);
        body.put("include", List.of("metadatas", "distances"));
        if (where != null && !where.isEmpty()) {
            body.put("where", where);
        }

        try {
            Map<String, Object> response = executeWithCollectionRetry(collectionId ->
                chromaRestClient.post()
                    .uri(baseCollectionPath() + "/{id}/query", collectionId)
                    .body(body)
                    .retrieve()
                    .body(Map.class));
            return new QueryResult(response);
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("ChromaDB 查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定记录。
     */
    public void delete(List<String> ids) {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);

        try {
            executeWithCollectionRetry(collectionId -> {
                chromaRestClient.post()
                    .uri(baseCollectionPath() + "/{id}/delete", collectionId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
                return null;
            });
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("ChromaDB 删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * ChromaDB 查询结果封装。
     */
    public static class QueryResult {

        private final Map<String, Object> raw;

        public QueryResult(Map<String, Object> raw) {
            this.raw = raw;
        }

        @SuppressWarnings("unchecked")
        public List<List<String>> getIds() {
            return raw != null ? (List<List<String>>) raw.get("ids") : null;
        }

        @SuppressWarnings("unchecked")
        public List<List<Double>> getDistances() {
            return raw != null ? (List<List<Double>>) raw.get("distances") : null;
        }

        @SuppressWarnings("unchecked")
        public List<List<Map<String, Object>>> getMetadatas() {
            return raw != null ? (List<List<Map<String, Object>>>) raw.get("metadatas") : null;
        }
    }
}
