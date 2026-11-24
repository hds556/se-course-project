package com.coursewise.shopping.order;
import com.coursewise.shopping.ShopWriteTransaction;
import com.coursewise.shopping.customer.CustomerService;
import com.coursewise.shopping.domain.*;
import com.coursewise.shopping.repository.AddressRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
/** 下单的最小地址接入。保留 /api/addresses 给开发C的完整地址管理模块。 */
@Service
public class CheckoutAddressService {
 private final AddressRepository addresses; private final CustomerService customers;
 private final ObjectMapper mapper; private final ShopWriteTransaction writes;
 public CheckoutAddressService(AddressRepository a,CustomerService c,ObjectMapper m,ShopWriteTransaction w){addresses=a;customers=c;mapper=m;writes=w;}
 private List<AddressEntity> mine(CustomerEntity c){
  var found=addresses.findByCustomerIdOrderByCreatedAtDescIdDesc(c.getId());
  // 兼容注册时填写的旧 defaultPlace，仅在地址簿为空时迁入一次。
  if(found.isEmpty() && c.getDefaultPlace()!=null && !c.getDefaultPlace().isBlank()){
   var a=new AddressEntity();a.setCustomerId(c.getId());a.setAddressText(c.getDefaultPlace().strip());
   a.setDefaultAddress(true);a.setCreatedAt(Instant.now().toString());addresses.saveAndFlush(a);return List.of(a);
  }
  return found;
 }
 public ObjectNode list(String session){return writes.execute(()->{
  var found=mine(customers.requireCustomer(session));ArrayNode items=mapper.createArrayNode();
  found.forEach(a->items.add(mapper.createObjectNode().put("id",a.getId()).put("addressText",a.getAddressText()).put("isDefault",a.isDefaultAddress())));
  return mapper.createObjectNode().set("items",items);
 });}
 public ObjectNode add(String session,String text){return writes.execute(()->{
  var c=customers.requireCustomer(session);
  if(text==null||text.isBlank()||text.strip().length()>200)throw new OrderException(HttpStatus.BAD_REQUEST,"addressText","收货地址必填，最多200字");
  var found=mine(c);var a=new AddressEntity();a.setCustomerId(c.getId());a.setAddressText(text.strip());a.setDefaultAddress(found.isEmpty());a.setCreatedAt(Instant.now().toString());addresses.saveAndFlush(a);
  return mapper.createObjectNode().put("id",a.getId()).put("addressText",a.getAddressText()).put("isDefault",a.isDefaultAddress());
 });}
 public String snapshot(CustomerEntity c,Long addressId){
  var found=mine(c);AddressEntity chosen;
  if(addressId!=null)chosen=found.stream().filter(a->a.getId().equals(addressId)).findFirst().orElseThrow(()->new OrderException(HttpStatus.NOT_FOUND,"addressId","收货地址不存在或无权使用"));
  else chosen=found.stream().filter(AddressEntity::isDefaultAddress).findFirst().orElseGet(()->found.stream().findFirst().orElseThrow(()->new OrderException(HttpStatus.BAD_REQUEST,"addressId","请先添加并选择收货地址")));
  if(chosen.getAddressText()==null||chosen.getAddressText().isBlank())throw new OrderException(HttpStatus.BAD_REQUEST,"addressId","收货地址不能为空");
  return chosen.getAddressText();
 }
}
