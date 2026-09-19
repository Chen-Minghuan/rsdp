package com.rsdp.agent.tool;

import com.rsdp.agent.domain.ProductSearchCriteria;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.domain.ProductSearchResult;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuRelation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 可用的只读工具门面（权限铁律的类型级落地）。
 *
 * <p>Skill 唯一的外部出口是 {@code SkillContext.tools()}；写能力（confirm/quote/scheme/order）
 * 不在本类型内出现，因此 Skill 在编译期就无法触发任何写操作。</p>
 */
public interface AgentReadTools {

    /** 工具 ID：产品检索。 */
    String TOOL_SEARCH_PRODUCTS = "search_products";

    /** 工具 ID：搭配关系查询。 */
    String TOOL_LIST_RELATIONS = "list_relations";

    /** 工具 ID：产品事实查询。 */
    String TOOL_FIND_PRODUCTS = "find_products";

    /** 按条件检索在售产品（可见性/审核过滤已在领域层收口）。 */
    ProductSearchResult searchProducts(ProductSearchCriteria criteria);

    /** 锚点产品的搭配关系（rspu_relation，official/ai_verified，status=active，按 sortOrder 升序）。 */
    List<RspuRelation> listCompanionRelations(String anchorRspuId);

    /** 锚点产品的互斥产品 ID 集合（relation_type=exclude，status=active）。 */
    Set<String> listExcludedRspuIds(String anchorRspuId);

    /** 按 ID 批量取产品事实（主档原值，Skill 内部使用，不直接出参）。 */
    Map<String, RspuMaster> findProducts(Collection<String> rspuIds);

    /** 按 ID 批量取产品卡片项（过可见性过滤 + 主图/尺寸装配，可直接进推荐卡片）。 */
    List<ProductSearchItem> findProductItems(Collection<String> rspuIds);
}
