package com.coursewise.shopping.address;

import com.coursewise.shopping.ShopWriteTransaction;
import com.coursewise.shopping.customer.CustomerService;
import com.coursewise.shopping.domain.AddressEntity;
import com.coursewise.shopping.domain.CustomerEntity;
import com.coursewise.shopping.order.OrderException;
import com.coursewise.shopping.repository.AddressRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 14260 收货地址管理（SRS FR-C-15 / BR-61~BR-63 / IC-08）。
 *
 * <p>与下单适配层共用 {@link AddressEntity} 与 {@code address} 表，不重复建表；
 * 本类负责<b>完整地址簿</b>：新增、编辑、删除、设默认。所有写操作都经过
 * {@link ShopWriteTransaction}，保证「至多一条默认」在单实例 SQLite 上串行执行。
 *
 * <p>地址只被下单服务复制为 {@code order.address_snapshot}，本模块<b>不修改任何订单字段</b>，
 * 因此修改或删除地址不会影响历史订单。
 */
@Service
public class AddressService {
    private static final int MAX_LENGTH = 200;

    private final AddressRepository addresses;
    private final CustomerService customers;
    private final ObjectMapper mapper;
    private final ShopWriteTransaction writes;

    public AddressService(AddressRepository addresses, CustomerService customers,
                          ObjectMapper mapper, ShopWriteTransaction writes) {
        this.addresses = addresses;
        this.customers = customers;
        this.mapper = mapper;
        this.writes = writes;
    }

    /** 地址簿：按创建时间倒序，同时间按 id 倒序，与「删除默认后落到最近一条」口径一致。 */
    public ObjectNode list(String session) {
        CustomerEntity customer = customers.requireCustomer(session);
        List<AddressEntity> found = addresses.findByCustomerIdOrderByCreatedAtDescIdDesc(customer.getId());
        ArrayNode items = mapper.createArrayNode();
        found.forEach(address -> items.add(view(address)));
        return mapper.createObjectNode().set("items", items);
    }

    /** 新增地址：首条自动默认；显式请求设默认时清除本人的其他默认标记。 */
    public ObjectNode add(String session, String addressText, Boolean setDefault) {
        CustomerEntity customer = customers.requireCustomer(session);
        String text = validate(addressText);
        return writes.execute(() -> {
            List<AddressEntity> existing = addresses.findByCustomerIdOrderByCreatedAtDescIdDesc(customer.getId());
            AddressEntity address = new AddressEntity();
            address.setCustomerId(customer.getId());
            address.setAddressText(text);
            address.setCreatedAt(Instant.now().toString());
            address.setDefaultAddress(existing.isEmpty() || Boolean.TRUE.equals(setDefault));
            if (address.isDefaultAddress()) clearDefaults(customer.getId());
            return view(addresses.saveAndFlush(address));
        });
    }

    /** 编辑地址：地址文本可改；显式设默认时清除其他默认标记。 */
    public ObjectNode edit(String session, long id, String addressText, Boolean setDefault) {
        CustomerEntity customer = customers.requireCustomer(session);
        String text = addressText == null ? null : validate(addressText);
        return writes.execute(() -> {
            AddressEntity address = owned(customer.getId(), id);
            if (text != null) address.setAddressText(text);
            if (Boolean.TRUE.equals(setDefault)) {
                clearDefaults(customer.getId());
                address.setDefaultAddress(true);
            } else if (Boolean.FALSE.equals(setDefault) && address.isDefaultAddress()) {
                // 至多一条默认，但地址簿不空时保留一条可用默认，便于结算页自动带入。
                promoteNewest(customer.getId(), id);
                address.setDefaultAddress(false);
            }
            return view(addresses.saveAndFlush(address));
        });
    }

    /** 设默认：单人单条，清除其余标记后写入。 */
    public ObjectNode makeDefault(String session, long id) {
        CustomerEntity customer = customers.requireCustomer(session);
        return writes.execute(() -> {
            AddressEntity address = owned(customer.getId(), id);
            clearDefaults(customer.getId());
            address.setDefaultAddress(true);
            return view(addresses.saveAndFlush(address));
        });
    }

    /** 删除地址：删掉默认地址后，自动把最近添加的一条设为默认（S-17）。 */
    public ObjectNode remove(String session, long id) {
        CustomerEntity customer = customers.requireCustomer(session);
        return writes.execute(() -> {
            AddressEntity address = owned(customer.getId(), id);
            boolean wasDefault = address.isDefaultAddress();
            addresses.delete(address);
            addresses.flush();
            ObjectNode result = mapper.createObjectNode().put("ok", true).put("id", id);
            if (wasDefault) {
                AddressEntity newest = promoteNewest(customer.getId(), null);
                if (newest == null) result.putNull("newDefaultId");
                else result.put("newDefaultId", newest.getId());
            } else {
                result.putNull("newDefaultId");
            }
            return result;
        });
    }

    /** 只按「会话客户 + 地址 id」定位，他人地址一律 404，不泄露是否存在。 */
    private AddressEntity owned(long customerId, long id) {
        return addresses.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "id", "收货地址不存在或无权访问"));
    }

    private void clearDefaults(long customerId) {
        for (AddressEntity address : addresses.findByCustomerIdOrderByCreatedAtDescIdDesc(customerId)) {
            if (address.isDefaultAddress()) {
                address.setDefaultAddress(false);
                addresses.saveAndFlush(address);
            }
        }
    }

    /** 把除 {@code exceptId} 之外最近的一条设为默认；没有候选时返回 null。 */
    private AddressEntity promoteNewest(long customerId, Long exceptId) {
        List<AddressEntity> candidates = addresses.findByCustomerIdOrderByCreatedAtDescIdDesc(customerId);
        for (AddressEntity candidate : candidates) {
            if (exceptId != null && exceptId.equals(candidate.getId())) continue;
            candidate.setDefaultAddress(true);
            return addresses.saveAndFlush(candidate);
        }
        return null;
    }

    private String validate(String value) {
        if (value == null || value.isBlank()) {
            throw new OrderException(HttpStatus.BAD_REQUEST, "addressText", "收货地址不能为空");
        }
        String text = value.strip();
        if (text.length() > MAX_LENGTH) {
            throw new OrderException(HttpStatus.BAD_REQUEST, "addressText", "收货地址最多 200 个字");
        }
        return text;
    }

    private ObjectNode view(AddressEntity address) {
        return mapper.createObjectNode()
                .put("id", address.getId() == null ? 0L : address.getId())
                .put("addressText", address.getAddressText())
                .put("isDefault", address.isDefaultAddress())
                .put("createdAt", address.getCreatedAt());
    }
}
