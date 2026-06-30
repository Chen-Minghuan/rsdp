package com.rsdp.service.chroma;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ChromaDB REST API 客户端。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChromaDbClient {

    private final RestClient chromaRestClient;
    private final ChromaDbProperties properties;

    /**
     * 确保集合存在，不存在则创建。
     */
    public void ensureCollection() {
        try {
            chromaRestClient.get()
                .uri("/api/v1/collections/{name}", properties.getCollection())
                .retrieve()
                .toBodilessEntity();
            log.debug("ChromaDB 集合已存在: {}", properties.getCollection());
        } catch (Exception e) {
            log.info("ChromaDB 集合不存在，正在创建: {}", properties.getCollection());
            createCollection();
        }
    }

    private void createCollection() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", properties.getCollection());
        body.put("metadata", Map.of("hnsw:space", "cosine"));

        chromaRestClient.post()
            .uri("/api/v1/collections")
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    /**
     * 批量写入或更新向量记录。
     *
     * @param ids          记录 ID 列表
     * @param embeddings   向量列表
     * @param metadatas    元数据列表
     * @param documents    文档列表（可为 null）
     */
    public void upsert(List<String> ids, List<float[]> embeddings, List<Map<String, Object>> metadatas, List<String> documents) {
        ensureCollection();

        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("embeddings", embeddings);
        body.put("metadatas", metadatas);
        if (documents != null) {
            body.put("documents", documents);
        }

        chromaRestClient.post()
            .uri("/api/v1/collections/{name}/upsert", properties.getCollection())
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    /**
     * 根据向量查询相似记录。
     *
     * @param queryEmbedding 查询向量
     * @param topK           返回数量
     * @param where          元数据过滤条件（可为 null）
     * @return 查询结果
     */
    @SuppressWarnings("unchecked")
    public QueryResult query(float[] queryEmbedding, int topK, Map<String, Object> where) {
        ensureCollection();

        Map<String, Object> body = new HashMap<>();
        body.put("query_embeddings", List.of(queryEmbedding));
        body.put("n_results", topK);
        body.put("include", List.of("metadatas", "distances"));
        if (where != null && !where.isEmpty()) {
            body.put("where", where);
        }

        Map<String, Object> response = chromaRestClient.post()
            .uri("/api/v1/collections/{name}/query", properties.getCollection())
            .body(body)
            .retrieve()
            .body(Map.class);

        return new QueryResult(response);
    }

    /**
     * 删除指定记录。
     *
     * @param ids 记录 ID 列表
     */
    public void delete(List<String> ids) {
        ensureCollection();

        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);

        chromaRestClient.post()
            .uri("/api/v1/collections/{name}/delete", properties.getCollection())
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
            return (List<List<String>>) raw.get("ids");
        }

        @SuppressWarnings("unchecked")
        public List<List<Double>> getDistances() {
            return (List<List<Double>>) raw.get("distances");
        }

        @SuppressWarnings("unchecked")
        public List<List<Map<String, Object>>> getMetadatas() {
            return (List<List<Map<String, Object>>>) raw.get("metadatas");
        }
    }
}
