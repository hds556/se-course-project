package com.coursewise.shopping;
import com.coursewise.shopping.domain.*;
import com.coursewise.shopping.repository.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderCIntegrationTest {
 static final Path TEMP;
 static {try{TEMP=Files.createTempDirectory("order-c-");}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",()->"jdbc:sqlite:"+TEMP.resolve("test.db"));
  r.add("shopping.upload-dir",()->TEMP.resolve("uploads").toString());r.add("shopping.legacy-data-file",()->TEMP.resolve("absent.json").toString());
  r.add("shopping.payment.timeout-scan-initial-delay-ms",()->"3600000");
 }
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
 @Autowired OrderRepository orders; @Autowired OrderItemRepository lines;
 @Autowired CartItemRepository cart; @Autowired FavoriteRepository favorites;
 @Autowired CustomerRepository customers; @Autowired CategoryRepository categories;
 @Autowired AddressRepository addresses; @Autowired RefundRepository refunds;
 @SpyBean ProductRepository products;
 Cookie seller,alice,bob;long leaf;
 @BeforeAll void login()throws Exception{seller=mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"admin1234\"}")).andReturn().getResponse().getCookie("seller_session");}
 @BeforeEach void setup()throws Exception{
  reset(products);refunds.deleteAll();addresses.deleteAll();lines.deleteAll();orders.deleteAll();cart.deleteAll();favorites.deleteAll();products.deleteAll();categories.deleteAll();customers.deleteAll();
  long parent=category("生活",null);leaf=category("文具",parent);alice=register("alice","杭州默认收货地址");bob=register("bob","上海默认地址");
 }
 JsonNode read(MvcResult r)throws Exception{return mapper.readTree(r.getResponse().getContentAsByteArray());}
 long category(String name,Long parent)throws Exception{var b=mapper.createObjectNode().put("name",name);if(parent!=null)b.put("parentId",parent);return read(mvc.perform(post("/api/admin/categories").cookie(seller).contentType("application/json").content(b.toString())).andReturn()).path("id").asLong();}
 Cookie register(String username,String address)throws Exception{
  var b=mapper.createObjectNode().put("username",username).put("password","password1").put("phone","13800000000").put("defaultPlace",address);
  mvc.perform(post("/api/customer/register").contentType("application/json").content(b.toString())).andExpect(status().isCreated());
  return mvc.perform(post("/api/customer/login").contentType("application/json").content("{\"username\":\""+username+"\",\"password\":\"password1\"}")).andReturn().getResponse().getCookie("customer_session");
 }
 long product(String name,int stock)throws Exception{return read(mvc.perform(multipart("/api/products").cookie(seller).param("name",name).param("price","12.50").param("stock",String.valueOf(stock)).param("categoryId",String.valueOf(leaf))).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
 long item(Cookie who,long product,int qty)throws Exception{return read(mvc.perform(post("/api/cart").cookie(who).contentType("application/json").content("{\"productId\":"+product+",\"quantity\":"+qty+"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
 JsonNode submit(Cookie who,long... ids)throws Exception{return read(mvc.perform(post("/api/orders").cookie(who).contentType("application/json").content(mapper.createObjectNode().set("cartItemIds",mapper.valueToTree(ids)).toString())).andExpect(status().isCreated()).andReturn());}
 void stage(long id,OrderStatus status,PayChannel channel){var o=orders.findById(id).orElseThrow();o.setStatus(status);o.setPayChannel(channel);orders.saveAndFlush(o);}
 JsonNode detail(Cookie who,long id)throws Exception{return read(mvc.perform(get("/api/orders/"+id).cookie(who)).andExpect(status().isOk()).andReturn());}
 @Test void addressRequiredDefaultSelectionAlternateSnapshotAndOwnership()throws Exception{
  var list=read(mvc.perform(get("/api/checkout/addresses").cookie(alice)).andExpect(status().isOk()).andReturn());
  assertEquals("杭州默认收货地址",list.path("items").get(0).path("addressText").asText());assertTrue(list.path("items").get(0).path("isDefault").asBoolean());
  var added=read(mvc.perform(post("/api/checkout/addresses").cookie(alice).contentType("application/json").content("{\"addressText\":\"宁波备用地址\"}")).andExpect(status().isCreated()).andReturn());
  long id=added.path("id").asLong(),p=product("快照笔",5),i=item(alice,p,2);
  var order=read(mvc.perform(post("/api/orders").cookie(alice).contentType("application/json").content("{\"cartItemIds\":["+i+"],\"addressId\":"+id+"}")).andExpect(status().isCreated()).andReturn());
  var a=addresses.findById(id).orElseThrow();a.setAddressText("地址簿改过的新地址");addresses.saveAndFlush(a);addresses.deleteById(id);
  var goods=products.findById(p).orElseThrow();goods.setName("改名");goods.setPriceCents(9999);products.saveAndFlush(goods);
  var snapshot=detail(alice,order.path("id").asLong());assertEquals("宁波备用地址",snapshot.path("addressSnapshot").asText());assertEquals(2500,snapshot.path("totalCents").asInt());assertEquals("快照笔",snapshot.path("items").get(0).path("productName").asText());
  var other=read(mvc.perform(get("/api/checkout/addresses").cookie(bob)).andReturn()).path("items").get(0).path("id").asLong();
  long next=item(alice,p,1);mvc.perform(post("/api/orders").cookie(alice).contentType("application/json").content("{\"cartItemIds\":["+next+"],\"addressId\":"+other+"}")).andExpect(status().isNotFound());
  Cookie empty=register("empty","");long emptyItem=item(empty,p,1);mvc.perform(post("/api/orders").cookie(empty).contentType("application/json").content("{\"cartItemIds\":["+emptyItem+"]}")).andExpect(status().isBadRequest());
  assertEquals(1,orders.count());
 }
 @Test void mixedShortageDoesNotCreatePartialOrderAndHealthyItemsCanBeResubmitted()throws Exception{
  long a=product("足量",5),b=product("缺货",1),ai=item(alice,a,2),bi=item(alice,b,3);
  var payload="{\"cartItemIds\":["+ai+","+bi+"]}";
  mvc.perform(post("/api/orders/preview").cookie(alice).contentType("application/json").content(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.canSubmit").value(false)).andExpect(jsonPath("$.readyCartItemIds[0]").value(ai));
  mvc.perform(post("/api/orders").cookie(alice).contentType("application/json").content(payload)).andExpect(status().isConflict()).andExpect(jsonPath("$.itemErrors."+b).exists());
  assertEquals(0,orders.count());assertEquals(5,products.findById(a).orElseThrow().getStock());assertEquals(2,cart.count());
  submit(alice,ai);assertEquals(1,orders.count());assertEquals(3,products.findById(a).orElseThrow().getStock());assertTrue(cart.findById(bi).isPresent());
 }
 @Test void secondDeductionFailureRollsBackFirstAndKeepsWholeCart()throws Exception{
  long a=product("第一件",5),b=product("第二件",5),ai=item(alice,a,2),bi=item(alice,b,2);
  AtomicInteger calls=new AtomicInteger();
  doAnswer(invocation->{if(calls.incrementAndGet()==2)return 0;int qty=invocation.getArgument(1);long id=invocation.getArgument(0);return jdbc.update("update product set stock=stock-?, version=version+1 where id=? and stock>=?",qty,id,qty);}).when(products).deductStock(anyLong(),anyInt(),anyString());
  mvc.perform(post("/api/orders").cookie(alice).contentType("application/json").content("{\"cartItemIds\":["+ai+","+bi+"]}")).andExpect(status().isConflict());
  assertEquals(2,calls.get());assertEquals(0,orders.count());assertEquals(0,lines.count());assertEquals(2,cart.count());assertEquals(5,products.findById(a).orElseThrow().getStock());assertEquals(5,products.findById(b).orElseThrow().getStock());
 }
 @ParameterizedTest @EnumSource(PayChannel.class) void paidCancellationRefundsOriginalChannelExactlyOnce(PayChannel channel)throws Exception{
  long p=product("末件",2),id=submit(alice,item(alice,p,2)).path("id").asLong();
  mvc.perform(post("/api/orders/"+id+"/pay").cookie(alice).contentType("application/json").content("{\"payChannel\":\""+channel.code()+"\",\"success\":true}")).andExpect(status().isOk());
  mvc.perform(post("/api/orders/"+id+"/cancel").cookie(alice)).andExpect(status().isOk()).andExpect(jsonPath("$.refunded").value(true)).andExpect(jsonPath("$.refundChannel").value(channel.code()));
  assertEquals(1,refunds.count());var r=refunds.findByOrderId(id).orElseThrow();assertEquals(channel,r.getPayChannel());assertEquals(2500,r.getAmountCents());
  assertEquals(2,products.findById(p).orElseThrow().getStock());assertEquals(ProductStatus.active,products.findById(p).orElseThrow().getStatus());
  mvc.perform(post("/api/orders/"+id+"/cancel").cookie(alice)).andExpect(status().isConflict());assertEquals(1,refunds.count());assertEquals(2,products.findById(p).orElseThrow().getStock());
  var view=detail(alice,id);assertEquals("cancelled",view.path("status").asText());assertEquals(channel.code(),view.path("refund").path("channel").asText());
  // C负责的卖家取消也不能让B的订单列表丢失已支付渠道。
  long sellerCancelled=submit(alice,item(alice,p,1)).path("id").asLong();
  mvc.perform(post("/api/orders/"+sellerCancelled+"/pay").cookie(alice).contentType("application/json").content("{\"payChannel\":\""+channel.code()+"\",\"success\":true}")).andExpect(status().isOk());
  mvc.perform(post("/api/admin/orders/"+sellerCancelled+"/cancel").cookie(seller)).andExpect(status().isOk());
  assertEquals(channel.code(),detail(alice,sellerCancelled).path("payChannel").asText());

 }
 @ParameterizedTest @EnumSource(OrderStatus.class) void cancellationRulesAndConditionalSummariesForEveryState(OrderStatus state)throws Exception{
  long p=product("状态笔",4),id=submit(alice,item(alice,p,1)).path("id").asLong();stage(id,state,PayChannel.wechat);
  // 即使存在脏物流字段，未发货阶段也不应泄露物流信息。
  var o=orders.findById(id).orElseThrow();o.setTrackingNo("SF123456");orders.saveAndFlush(o);
  var view=detail(alice,id);assertEquals(state.isCancellableByCustomer(),view.path("cancellable").asBoolean());
  if(state.isUnpaidStage()){assertTrue(view.path("payChannel").isNull());assertTrue(view.path("trackingNo").isNull());}
  if(state==OrderStatus.paid||state==OrderStatus.confirmed||state==OrderStatus.packed)assertTrue(view.path("trackingNo").isNull());
  int expected=state.isCancellableByCustomer()?200:409;
  assertEquals(expected,mvc.perform(post("/api/orders/"+id+"/cancel").cookie(alice)).andReturn().getResponse().getStatus());
  assertEquals(state.isCancellableByCustomer()&&state.isPaidStage()?1:0,refunds.count());
 }
 @Test void concurrentCancellationCannotDuplicateRefundOrRestock()throws Exception{
  long p=product("并发",2),id=submit(alice,item(alice,p,2)).path("id").asLong();stage(id,OrderStatus.paid,PayChannel.alipay);
  var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);List<Future<Integer>> futures=new ArrayList<>();
  try{for(int i=0;i<6;i++)futures.add(pool.submit(()->{start.await();return mvc.perform(post("/api/orders/"+id+"/cancel").cookie(alice)).andReturn().getResponse().getStatus();}));start.countDown();int ok=0;for(var f:futures){int status=f.get(20,TimeUnit.SECONDS);assertTrue(status==200||status==409);if(status==200)ok++;}assertEquals(1,ok);}finally{pool.shutdownNow();}
  assertEquals(1,refunds.count());assertEquals(2,products.findById(p).orElseThrow().getStock());
 }
 @Test void ownOrdersArePagedStableAndReadOnly()throws Exception{
  long p=product("分页笔",30);List<Long> ids=new ArrayList<>();for(int i=0;i<12;i++)ids.add(submit(alice,item(alice,p,1)).path("id").asLong());submit(bob,item(bob,p,1));
  var page=read(mvc.perform(get("/api/orders?page=1&size=5").cookie(alice)).andExpect(status().isOk()).andReturn());assertEquals(12,page.path("total").asInt());assertEquals(3,page.path("totalPages").asInt());assertEquals(ids.get(11).longValue(),page.path("items").get(0).path("id").asLong());
  mvc.perform(get("/api/orders/"+ids.get(0)).cookie(bob)).andExpect(status().isNotFound());mvc.perform(post("/api/orders/"+ids.get(0)+"/cancel").cookie(bob)).andExpect(status().isNotFound());
  mvc.perform(delete("/api/orders/"+ids.get(0)).cookie(alice)).andExpect(status().isMethodNotAllowed());mvc.perform(patch("/api/orders/"+ids.get(0)).cookie(alice).contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());
 }
 @Test void foreignCartDuplicateIdsAndInvalidAddressAreRejected()throws Exception{
  long p=product("权限",8),a=item(alice,p,1),b=item(bob,p,1);
  mvc.perform(post("/api/orders").cookie(alice).contentType("application/json").content("{\"cartItemIds\":["+a+","+b+"]}")).andExpect(status().isNotFound());
  mvc.perform(post("/api/orders").cookie(alice).contentType("application/json").content("{\"cartItemIds\":["+a+","+a+"]}")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/orders").cookie(alice).contentType("application/json").content("{\"cartItemIds\":["+a+"],\"addressId\":\"invalid\"}")).andExpect(status().isBadRequest());assertEquals(0,orders.count());
 }
}
