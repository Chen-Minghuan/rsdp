package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.SysUser;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.springframework.util.StringUtils;

/** Records the immutable identity of the person who first put a product into the catalog. */
final class ProductEntrySource {
    private ProductEntrySource() {}

    static ObjectNode actor(ObjectMapper mapper, SysUserMapper users, String id) {
        ObjectNode result = mapper.createObjectNode();
        if (!StringUtils.hasText(id)) {
            return result;
        }
        SysUser user = users.selectById(id);
        result.put("actorId", id);
        if (user != null) {
            result.put("actorUsername", user.getUsername());
            result.put("actorName", StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername());
        } else if (id.equals(SecurityOperatorContext.currentUserId())) {
            result.put("actorUsername", SecurityOperatorContext.currentUsername());
        }
        result.put("actorKind", "USER");
        return result;
    }

    static void apply(RspuMaster product, ObjectNode actor, String channel, String batchId, String uploadedBy) {
        product.setSourceChannel(channel);
        ObjectNode source = actor.deepCopy();
        source.put("recordedAt", product.getCreatedAt().toString());
        source.put("factoryCode", product.getOwnerFactoryCode());
        if (batchId != null) {
            source.put("batchId", batchId);
            source.put("uploadedBy", uploadedBy);
        }
        product.setEntryProvenance(source.toString());
    }
}
