package com.coursewise.shopping.customer;

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
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户账号服务（FR-C-04 注册、FR-C-05 登录、FR-C-07 历史下单记录）。
 *
 * <p>口径遵循《需求分析文档 V3.5》：
 * <ul>
 *   <li>BR-14：用户名/密码/电话必填，默认交易地点选填；用户名唯一、电话不唯一；无验证码。</li>
 *   <li>T-9：密码只校验长度 ≥ 8；T-10：用户名仅要求非空且唯一；T-11：电话只要求必填。</li>
 *   <li>T-12：注册后需手动登录；提供退出登录；会话存内存，重启失效。</li>
 *   <li>T-13：登录失败统一提示"用户名或密码错误"，不做次数限制与锁定。</li>
 *   <li>T-24：用户名重复统一提示"该用户名已被占用"。</li>
 *   <li>BR-16 / FR-C-07：历史只含终态（成功/失败），按时间倒序、只读、支持翻页。</li>
 * </ul>
 */
@Service
public class CustomerService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final ObjectMapper mapper;
    private final CustomerRepository customers;
    private final IntentRepository intents;
    private final ProductRepository products;
    private final SCryptPasswordEncoder passwordEncoder = SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8();

    /** 客户会话：token -> customerId，存内存，重启失效需重新登录（与卖家会话一致，T-12）。 */
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public CustomerService(ObjectMapper mapper, CustomerRepository customers,
                           IntentRepository intents, ProductRepository products) {
        this.mapper = mapper;
        this.customers = customers;
        this.intents = intents;
        this.products = products;
    }

    // ==================== FR-C-04 注册 ====================

    @Transactional
    public ObjectNode register(String username, String password, String phone, String defaultPlace) {
        if (username == null || username.trim().isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "username", "用户名为必填项");
        }
        username = username.trim();
        if (username.length() > 64) {
            throw error(HttpStatus.BAD_REQUEST, "username", "用户名不能超过 64 个字符");
        }
        if (password == null || password.length() < 8) {
            throw error(HttpStatus.BAD_REQUEST, "password", "密码不少于 8 位");
        }
        if (phone == null || phone.trim().isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "phone", "电话为必填项");
        }
        phone = phone.trim();
        if (phone.length() > 32) {
            throw error(HttpStatus.BAD_REQUEST, "phone", "电话不能超过 32 个字符");
        }
        if (defaultPlace != null && defaultPlace.trim().length() > 200) {
            throw error(HttpStatus.BAD_REQUEST, "defaultPlace", "默认交易地点最多 200 字符");
        }
        if (customers.existsByUsername(username)) {
            // T-24：统一提示，不泄露账号是否存在以外的信息
            throw error(HttpStatus.CONFLICT, "username", "该用户名已被占用");
        }
        CustomerEntity customer = new CustomerEntity();
        customer.setUsername(username);
        customer.setPasswordHash(passwordEncoder.encode(password));
        customer.setPhone(phone);
        customer.setDefaultPlace(defaultPlace == null || defaultPlace.trim().isEmpty() ? null : defaultPlace.trim());
        customer.setRegisteredAt(now());
        customers.save(customer);
        return mapper.createObjectNode().put("id", customer.getId()).put("ok", true);
    }

    // ==================== FR-C-05 登录 / 会话 ====================

    /** 凭用户名 + 密码登录；失败统一提示（T-13）。成功返回会话 token。 */
    @Transactional(readOnly = true)
    public String login(String username, String password) {
        CustomerEntity customer = customers.findByUsername(username == null ? "" : username.trim()).orElse(null);
        if (customer == null || !passwordEncoder.matches(password == null ? "" : password, customer.getPasswordHash())) {
            throw error(HttpStatus.UNAUTHORIZED, null, "用户名或密码错误");
        }
        String token = java.util.UUID.randomUUID().toString();
        sessions.put(token, customer.getId());
        return token;
    }

    public void logout(String session) {
        if (session != null) sessions.remove(session);
    }

    public CustomerEntity requireCustomer(String session) {
        Long id = session == null ? null : sessions.get(session);
        if (id == null) throw error(HttpStatus.UNAUTHORIZED, null, "未登录");
        return customers.findById(id).orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, null, "未登录"));
    }

    /** 当前登录客户的公开视图（不含密码哈希）。 */
    @Transactional(readOnly = true)
    public ObjectNode me(String session) {
        CustomerEntity customer = requireCustomer(session);
        ObjectNode view = mapper.createObjectNode();
        view.put("id", customer.getId());
        view.put("username", customer.getUsername());
        view.put("phone", customer.getPhone());
        view.put("defaultPlace", customer.getDefaultPlace() == null ? "未设置" : customer.getDefaultPlace());
        view.put("registeredAt", customer.getRegisteredAt());
        return view;
    }

    // ==================== FR-C-07 历史下单记录 ====================

    /**
     * 当前客户的终态下单记录：只含 stage=closed 且已有交易结果的意向（BR-16）。
     * 排队中、交易中的记录不进历史；匿名意向（customer_id 为空，T-38）天然查不到。
     * 商品名取商品表当前名称——商品名发布后不可改（BR-29），即下单时快照；
     * 商品下架不删历史记录，照常展示（FR-C-07）。
     */
    @Transactional(readOnly = true)
    public ObjectNode history(String session, int page, int size) {
        CustomerEntity customer = requireCustomer(session);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 50);
        var all = intents.findByCustomerIdAndStageOrderBySubmittedAtDescIdDesc(customer.getId(), IntentStage.closed);
        int total = all.size();
        int totalPages = (int) Math.ceil(total / (double) safeSize);
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        ArrayNode items = mapper.createArrayNode();
        for (IntentEntity intent : all.subList(from, to)) {
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
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("totalPages", Math.max(1, totalPages));
        return result;
    }

    /** 历史只有成功/失败两档（BR-34/BR-37）：成功之外（作废、重排、撤销、未成交）都记为失败。 */
    private String toResult(String outcome) {
        return "success".equals(outcome) ? "成功" : "失败";
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    private ResponseStatusException error(HttpStatus status, String field, String message) {
        return new CustomerException(status, field, message);
    }
}
