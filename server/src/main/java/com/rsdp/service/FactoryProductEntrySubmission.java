package com.rsdp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.FactoryProductEntryRequest;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.SourceCodeNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Native factory entry with durable retries and the same supplier identity as Excel imports. */
@Service
@RequiredArgsConstructor
public class FactoryProductEntrySubmission {
    private static final TypeReference<Map<String, Object>> RESULT = new TypeReference<>() {};
    private final ProductService products;
    private final SupplierIdentityResolutionService identities;
    private final SupplierProductRefService supplierRefs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Validates the factory first, then commits the product and its retry receipt together. */
    @Transactional(rollbackFor = IOException.class)
    public Map<String, Object> create(FactoryProductEntryRequest request, List<MultipartFile> images,
                                      String requestId) throws IOException {
        products.validateFactoryEntryOwnership(request.getFactoryCode());
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException("请求编号格式不合法");
        }
        String actor = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(actor)) {
            throw new BusinessException(401, "请登录后录入商品");
        }
        String factory = request.getFactoryCode();
        String model = StringUtils.hasText(request.getExternalCode()) ? request.getExternalCode().trim() : null;
        String normalizedModel = SourceCodeNormalizer.normalize(model);
        if (!StringUtils.hasText(normalizedModel) || !StringUtils.hasText(request.getProductName())) {
            throw new BusinessException("请填写厂内型号与商品名称");
        }
        request.setExternalCode(model);
        String fingerprint = fingerprint(request, images);
        lock("factory-entry:request:" + factory + ":" + actor + ":" + requestId);
        List<Receipt> previous = jdbc.query("""
            SELECT request_sha256, result_json FROM factory_product_entry_receipt
            WHERE factory_code = ? AND actor_id = ? AND request_id = ?
            """, (row, index) -> new Receipt(row.getString(1), row.getString(2)), factory, actor, requestId);
        if (!previous.isEmpty()) {
            Receipt receipt = previous.getFirst();
            if (!receipt.hash().equals(fingerprint)) {
                throw new BusinessException(409, "请求编号已用于其他商品资料，请重新提交");
            }
            return mapper.readValue(receipt.result(), RESULT);
        }
        lock("factory-entry:model:" + factory + ":" + normalizedModel);
        var existing = identities.resolve(factory, null, model);
        if (existing.isPresent()) {
            identities.rejectRecycledProduct(existing.get());
            throw new BusinessException(409, "本厂已有该型号，请在产品库更新现有商品");
        }
        Map<String, Object> result = products.createFactoryEntry(request, images);
        supplierRefs.resolveOrCreate(new SupplierProductRefService.ResolveCommand(
            (String) result.get("rspuId"), factory, null, "ROOMVIP_FACTORY", model, request.getProductName(), null));
        jdbc.update("""
            INSERT INTO factory_product_entry_receipt
              (factory_code, actor_id, request_id, request_sha256, result_json)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb))
            """, factory, actor, requestId, fingerprint, mapper.writeValueAsString(result));
        return result;
    }

    private void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rows -> { }, key);
    }

    private String fingerprint(FactoryProductEntryRequest product, List<MultipartFile> images) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(mapper.writeValueAsBytes(product));
            if (images != null) {
                for (MultipartFile image : images) {
                    digest.update(MessageDigest.getInstance("SHA-256").digest(image.getBytes()));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Receipt(String hash, String result) {}
}
