package com.rsdp.service.chroma;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
            String id = (String) collection.get("id");
            collectionIdCache.set(id);
            log.debug("ChromaDB 集合已存在: {} -> {}", collectionName, id);
            return id;
        } catch (Exception e) {
            log.info("ChromaDB 集合不存在，正在创建: {}", collectionName);
            return createCollection(collectionName);
        }
    }

    private String createCollection(String collectionName) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", collectionName);
        body.put("metadata", Map.of("hnsw:space", "cosine"));

        Map<String, Object> collection = chromaRestClient.post()
            .uri(baseCollectionPath())
            .body(body)
            .retrieve()
            .body(Map.class);

        String id = (String) collection.get("id");
        collectionIdCache.set(id);
        log.info("ChromaDB 集合创建完成: {} -> {}", collectionName, id);
        return id;
    }

    /**
     * 批量写入或更新向量记录。
     */
    public void upsert(List<String> ids, List<float[]> embeddings, List<Map<String, Object>> metadatas, List<String> documents) {
        String collectionId = getOrCreateCollectionId();

        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("embeddings", embeddings);
        body.put("metadatas", metadatas);
        if (documents != null) {
            body.put("documents", documents);
        }

        chromaRestClient.post()
            .uri(baseCollectionPath() + "/{id}/upsert", collectionId)
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    /**
     * 根据向量查询相似记录。
     */
    @SuppressWarnings("unchecked")
    public QueryResult query(float[] queryEmbedding, int topK, Map<String, Object> where) {
        String collectionId = getOrCreateCollectionId();

        Map<String, Object> body = new HashMap<>();
        body.put("query_embeddings", List.of(queryEmbedding));
        body.put("n_results", topK);
        body.put("include", List.of("metadatas", "distances"));
        if (where != null && !where.isEmpty()) {
            body.put("where", where);
        }

        Map<String, Object> response = chromaRestClient.post()
            .uri(baseCollectionPath() + "/{id}/query", collectionId)
            .body(body)
            .retrieve()
            .body(Map.class);

        return new QueryResult(response);
    }

    /**
     * 删除指定记录。
     */
    public void delete(List<String> ids) {
        String collectionId = getOrCreateCollectionId();

        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);

        chromaRestClient.post()
            .uri(baseCollectionPath() + "/{id}/delete", collectionId)
            .body(body)
            .retrieve()
            .toBodilessEntity();
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
