package com.coursewise.shopping.customer;

import com.coursewise.shopping.ShoppingService;
import com.coursewise.shopping.domain.CustomerEntity;
import com.coursewise.shopping.domain.IntentEntity;
import com.coursewise.shopping.domain.IntentStage;
import com.coursewise.shopping.domain.ProductEntity;
import com.coursewise.shopping.repository.CustomerRepository;
import com.coursewise.shopping.repository.IntentRepository;
import com.coursewise.shopping.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 卖家端客户档案服务（FR-S-13 查看客户列表、FR-S-14 查看客户购买历史）。
 *
 * <p>本类为新增文件，不改动任何既有类：只读地复用 {@code customer / intent / product} 三张表，
 * 卖家身份沿用 {@code ShoppingService.requireSession} 校验，与其他卖家接口（{@code /api/admin/**}）
 * 共用同一套 {@code seller_session} 会话。
 *
 * <p>口径严格照《需求规格说明书 V2.0》：
 * <ul>
 *   <li>BR-22：全部客户 = 全部注册账号，注册即入列，与是否下单无关；不含未注册游客。</li>
 *   <li>FR-S-13：字段只有用户名、电话、默认交易地点、注册时间；<b>没有"姓名"列</b>——注册时未设该字段。</li>
 *   <li>FR-S-13：不做账号编辑 / 禁用 / 删除、导出、搜索与筛选（EX-06），故本类不提供任何写操作。</li>
 *   <li>FR-S-14：只含终态（成功 + 失败）记录；排队中与交易中的意向不出现（那部分走 FR-S-08 按商品意向队列）。</li>
 *   <li>FR-S-14：字段只有商品名、下单时间、交易结果、交易时间，<b>不含价格、数量、地点</b>。</li>
 *   <li>T-21：排序按注册时间倒序、支持翻页（本类实现的唯一"排序/翻页"约定，搜索与筛选本轮不做）。</li>
 * </ul>
 *
 * <p>购买历史的查询条件与结果映射，与 {@link CustomerService#history}（买家端 FR-C-07）<b>逐字一致</b>：
 * 同为 {@code stage = closed}、同为按下单时间倒序、同为"成功之外一律记失败"。
 * 这是 FR-S-14 验收标准第 3 条"与该客户自己看到的记录集合一致"的实现保证。
 */
@Service
public class CustomerDirectoryService {

    private final ObjectMapper mapper;
    private final CustomerRepository customers;
    private final IntentRepository intents;
    private final ProductRepository products;
    private final ShoppingService auth;

    public CustomerDirectoryService(ObjectMapper mapper, CustomerRepository customers,
                                    IntentRepository intents, ProductRepository products,
                                    ShoppingService auth) {
        this.mapper = mapper;
        this.customers = customers;
        this.intents = intents;
        this.products = products;
        this.auth = auth;
    }

    // ==================== FR-S-13 查看客户列表 ====================

    /**
     * 全部注册客户，按注册时间倒序（同时间用 id 倒序兜底），支持翻页。
     *
     * <p>排序放在内存里做而不是交给 SQL：{@code registered_at} 是 ISO-8601 字符串，
     * 毫秒为 0 时会少一段小数，交给数据库排序会让"同秒注册"的两条记录排错序；
     * 内存排序可以稳定地用 id 兜底。客户量在本项目规模下不需要担心性能。
     */
    @Transactional(readOnly = true)
    public ObjectNode list(String session, int page, int size) {
        auth.requireSession(session);
        List<CustomerEntity> all = new java.util.ArrayList<>(customers.findAll());
        all.sort(Comparator.comparing(CustomerEntity::getRegisteredAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CustomerEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())));

        int total = all.size();
        int[] bounds = bounds(total, page, size);
        ArrayNode items = mapper.createArrayNode();
        for (CustomerEntity customer : all.subList(bounds[2], bounds[3])) {
            ObjectNode item = mapper.createObjectNode();
            item.put("id", customer.getId());
            item.put("username", customer.getUsername());
            // 电话全量明文可见（FR-S-13 规则 4），约束只是不得向第三方泄露
            item.put("phone", customer.getPhone());
            // 未填写默认交易地点时显示"未设置"（验收标准 3），与买家端 /api/customer/me 的口径一致
            item.put("defaultPlace", customer.getDefaultPlace() == null ? "未设置" : customer.getDefaultPlace());
            item.put("registeredAt", customer.getRegisteredAt());
            items.add(item);
        }
        return pageNode(items, bounds[0], bounds[1], total);
    }

    // ==================== FR-S-14 查看客户购买历史 ====================

    /**
     * 某客户的终态下单记录：只含 {@code stage = closed} 且已有交易结果的意向。
     *
     * <p>商品名取商品表的当前名称——商品名发布后不可改（BR-29），因此它天然就是下单时的快照；
     * 商品下架也不删历史记录，照常展示。
     */
    @Transactional(readOnly = true)
    public ObjectNode purchases(String session, long customerId, int page, int size) {
        auth.requireSession(session);
        CustomerEntity customer = customers.findById(customerId)
                .orElseThrow(() -> new CustomerException(HttpStatus.NOT_FOUND, null, "客户不存在"));

        List<IntentEntity> all = intents.findByCustomerIdAndStageOrderBySubmittedAtDescIdDesc(
                customer.getId(), IntentStage.closed);
        int total = all.size();
        int[] bounds = bounds(total, page, size);
        ArrayNode items = mapper.createArrayNode();
        for (IntentEntity intent : all.subList(bounds[2], bounds[3])) {
            ObjectNode item = mapper.createObjectNode();
            item.put("id", intent.getId());
            item.put("productId", intent.getProductId());
            ProductEntity product = products.findById(intent.getProductId()).orElse(null);
            item.put("productName", product == null ? "（商品记录缺失）" : product.getName());
            item.put("submittedAt", intent.getSubmittedAt());
            item.put("result", intent.getOutcome() == null ? "失败" : toResult(intent.getOutcome().code()));
            putNullable(item, "closedAt", intent.getClosedAt());
            items.add(item);
        }
        ObjectNode result = pageNode(items, bounds[0], bounds[1], total);
        result.put("customerId", customer.getId());
        result.put("username", customer.getUsername());
        return result;
    }

    // ==================== 内部实现 ====================

    /** 历史只有成功 / 失败两档（BR-34 / BR-37）：成功之外（作废、重排、撤销、未成交）都记为失败。 */
    private String toResult(String outcome) {
        return "success".equals(outcome) ? "成功" : "失败";
    }

    /** 分页口径与 {@link CustomerService#history} 完全一致：页码从 1 起，每页 1~50 条。返回 {page,size,from,to}。 */
    private int[] bounds(int total, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 50);
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return new int[]{safePage, safeSize, from, to};
    }

    private ObjectNode pageNode(ArrayNode items, int page, int size, int total) {
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        result.put("page", page);
        result.put("size", size);
        result.put("total", total);
        result.put("totalPages", Math.max(1, (int) Math.ceil(total / (double) size)));
        return result;
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }
}
