package com.rsdp.vector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 真实 PG16 + pgvector 集成测试（非 Mock/H2）。
 *
 * <p>验证距离算子、HNSW 排序、幂等 upsert、版本/删除过滤等 Mapper SQL 的真实行为。
 * 用例在单事务内完成并回滚，不污染开发库。运行方式：
 * {@code RSDP_IT_PG=true mvn test -Dtest=PgvectorStorageIT}，
 * 连接参数可用 {@code RSDP_IT_JDBC_URL / RSDP_IT_DB_USER / RSDP_IT_DB_PASSWORD} 覆盖。</p>
 */
@EnabledIfEnvironmentVariable(named = "RSDP_IT_PG", matches = "true")
class PgvectorStorageIT {

    private static final String URL = System.getenv().getOrDefault(
        "RSDP_IT_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/rsdp");
    private static final String USER = System.getenv().getOrDefault("RSDP_IT_DB_USER", "rsdp");
    private static final String PASSWORD = System.getenv().getOrDefault("RSDP_IT_DB_PASSWORD", "rsdp");
    private static final String PROFILE = "mm-emb-v1-1024-cosine";

    private Connection connect() throws Exception {
        Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
        conn.setAutoCommit(false);
        return conn;
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /** pgvector 扩展类型需要 PGobject 封装，否则 pgjdbc 会按 varchar 发送。 */
    private static PGobject pgVector(float[] vector) throws Exception {
        PGobject obj = new PGobject();
        obj.setType("vector");
        obj.setValue(vectorLiteral(vector));
        return obj;
    }

    private static float[] basis(int dim, int oneAt) {
        float[] v = new float[dim];
        v[oneAt] = 1.0f;
        return v;
    }

    @Test
    void cosineDistanceOperatorAndOrder() throws Exception {
        try (Connection conn = connect()) {
            ensureFixture(conn);
            float[] query = basis(1024, 0);
            // 与 Mapper.search 同形的排序 SQL（去掉业务过滤，纯验证算子与升序）
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT e.image_id, e.embedding <=> ? AS distance "
                    + "FROM product_image_embedding e WHERE e.profile_id = ? ORDER BY e.embedding <=> ? LIMIT 10")) {
                ps.setObject(1, pgVector(query));
                ps.setString(2, PROFILE);
                ps.setObject(3, pgVector(query));
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> ids = new ArrayList<>();
                    List<Double> distances = new ArrayList<>();
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                        distances.add(rs.getDouble(2));
                    }
                    assertThat(ids).hasSize(3);
                    // 查询向量本身距离为 0 且排第一；其余按距离升序
                    assertThat(ids.get(0)).isEqualTo("IT-VEC-A");
                    assertThat(distances.get(0)).isCloseTo(0.0, within(1e-6));
                    for (int i = 1; i < distances.size(); i++) {
                        assertThat(distances.get(i)).isGreaterThanOrEqualTo(distances.get(i - 1));
                    }
                    assertThat(distances.get(2)).isCloseTo(1.0, within(1e-6));
                }
            }
            conn.rollback();
        }
    }

    @Test
    void upsertOnConflictOverwrites() throws Exception {
        try (Connection conn = connect()) {
            ensureFixture(conn);
            upsert(conn, "IT-VEC-A", 1, "hash-v1", basis(1024, 5));
            upsert(conn, "IT-VEC-A", 2, "hash-v2", basis(1024, 9));
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT source_revision, input_hash FROM product_image_embedding WHERE image_id = 'IT-VEC-A'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(2L);
                    assertThat(rs.getString(2)).isEqualTo("hash-v2");
                }
            }
            conn.rollback();
        }
    }

    @Test
    void revisionMismatchExcludedFromSearch() throws Exception {
        try (Connection conn = connect()) {
            ensureFixture(conn);
            // 把 A 的向量版本改旧，模拟"向量是旧内容生成的"——应按 source_revision = content_revision 过滤排除
            try (Statement st = conn.createStatement()) {
                st.execute("UPDATE product_image_embedding SET source_revision = 99 WHERE image_id = 'IT-VEC-A'");
            }
            float[] query = basis(1024, 0);
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT e.image_id FROM product_image_embedding e "
                    + "JOIN image_assets i ON i.image_id = e.image_id "
                    + "WHERE e.profile_id = ? AND e.source_revision = i.content_revision "
                    + "AND i.deleted_at IS NULL ORDER BY e.embedding <=> ? LIMIT 10")) {
                ps.setString(1, PROFILE);
                ps.setObject(2, pgVector(query));
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> ids = new ArrayList<>();
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                    assertThat(ids).doesNotContain("IT-VEC-A");
                }
            }
            conn.rollback();
        }
    }

    @Test
    void hnswIndexUsable() throws Exception {
        try (Connection conn = connect()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pg_indexes WHERE indexname = 'idx_product_image_embedding_hnsw'")) {
                assertThat(rs.next()).isTrue();
            }
            // 执行计划可生成（空表/小表走 Seq Scan 属正常，不强制索引扫描）
            try (Statement st = conn.createStatement()) {
                st.executeQuery("EXPLAIN SELECT image_id FROM product_image_embedding "
                    + "ORDER BY embedding <=> '" + vectorLiteral(basis(1024, 0)) + "' LIMIT 5").close();
            }
            conn.rollback();
        }
    }

    /** 准备三条测试向量（依赖 fixture 图片行，见下）。 */
    private void ensureFixture(Connection conn) throws Exception {
        String rspuId;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT rspu_id FROM rspu_master LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            rspuId = rs.getString(1);
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO image_assets (image_id, rspu_id, image_type, storage_path, content_revision) "
                + "VALUES (?, ?, 'white_bg', '/tmp/it.jpg', 1) ON CONFLICT (image_id) DO NOTHING")) {
            for (String id : new String[] {"IT-VEC-A", "IT-VEC-B", "IT-VEC-C"}) {
                ps.setString(1, id);
                ps.setString(2, rspuId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        upsert(conn, "IT-VEC-A", 1, "h-a", basis(1024, 0));
        upsert(conn, "IT-VEC-B", 1, "h-b", basis(1024, 1));
        upsert(conn, "IT-VEC-C", 1, "h-c", basis(1024, 512));
    }

    private void upsert(Connection conn, String imageId, long revision, String hash, float[] vector)
        throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO product_image_embedding "
                + "(image_id, profile_id, source_revision, input_hash, embedding, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
                + "ON CONFLICT (image_id) DO UPDATE SET profile_id = EXCLUDED.profile_id, "
                + "source_revision = EXCLUDED.source_revision, input_hash = EXCLUDED.input_hash, "
                + "embedding = EXCLUDED.embedding, updated_at = CURRENT_TIMESTAMP")) {
            ps.setString(1, imageId);
            ps.setString(2, PROFILE);
            ps.setLong(3, revision);
            ps.setString(4, hash);
            ps.setObject(5, pgVector(vector));
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }
}
