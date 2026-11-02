package com.coursewise.shopping.review3;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** 第三次评审：购买意向、交易链路、库存联动和并发。 */
class TradeAcceptanceTest extends Review3Base {
    JsonNode work(long p)throws Exception{return expect(seller.get("/api/admin/trades/"+p),200).json;}
    JsonNode mine(Client c)throws Exception{return expect(c.get("/api/customer/intents"),200).json.path("items");}
    JsonNode orders(Client c)throws Exception{return expect(c.get("/api/customer/orders"),200).json.path("items");}
    JsonNode withdraw(long p,String d)throws Exception{return expect(seller.post("/api/admin/products/"+p+"/withdraw",obj("disposition",d)),200).json;}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    boolean hasId(JsonNode rows,long id){for(JsonNode n:rows)if(n.path("id").asLong()==id)return true;return false;}
    long deplete()throws Exception{JsonNode p=publish("商品",2);long id=id(p);expect(seller.patch("/api/admin/products/"+id,obj("stock",0,"categoryId",leafId,"version",p.path("version").asLong())),200);return id;}
    List<Response> parallel(List<Callable<Response>> tasks)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(tasks.size());CountDownLatch gate=new CountDownLatch(1);
        try{List<Future<Response>> pending=new ArrayList<>();for(Callable<Response> t:tasks)pending.add(pool.submit(()->{gate.await();return t.call();}));gate.countDown();List<Response> rows=new ArrayList<>();for(Future<Response> f:pending)rows.add(f.get(60,TimeUnit.SECONDS));return rows;}finally{pool.shutdownNow();}
    }
    @Test @DisplayName("R3-T001 | 未登录不能提交意向")
    void t001() throws Exception { long p=id(publish("商品",2)); expect(anonymous().post("/api/customer/intents",obj("productId",p)),401); assertEquals(0,count("intent")); }
    @Test @DisplayName("R3-T002 | 缺少商品编号拒绝提交")
    void t002() throws Exception { Client c=customer("buyer"); expect(c.post("/api/customer/intents",obj()),400); assertEquals(0,count("intent")); }
    @Test @DisplayName("R3-T003 | 不存在的商品不能下单")
    void t003() throws Exception { Client c=customer("buyer"); expect(c.post("/api/customer/intents",obj("productId",Long.MAX_VALUE)),404); assertEquals(0,count("intent")); }
    @Test @DisplayName("R3-T004 | 意向身份取登录账号且不预占库存")
    void t004() throws Exception { long p=id(publish("商品",3));Client c=customer("buyer");JsonNode r=expect(c.post("/api/customer/intents",obj("productId",p,"name","伪造","phone","000")),201).json;assertEquals(1,r.path("position").asInt());assertEquals(3,r.path("stock").asInt());JsonNode q=work(p).path("queue").get(0);assertEquals("buyer",q.path("name").asText());assertEquals("13800000000",q.path("phone").asText()); }
    @Test @DisplayName("R3-T005 | 同客户可重复提交同商品意向")
    void t005() throws Exception { long p=id(publish("商品",2));Client c=customer("buyer");JsonNode a=submit(c,p),b=submit(c,p);assertNotEquals(id(a),id(b));assertEquals(1,a.path("position").asInt());assertEquals(2,b.path("position").asInt());assertEquals(2,work(p).path("stock").asInt()); }
    @Test @DisplayName("R3-T006 | 不同商品独立排队")
    void t006() throws Exception { long a=id(publish("A",2)),b=id(publish("B",2));Client c=customer("buyer");assertEquals(1,submit(c,a).path("position").asInt());assertEquals(1,submit(c,b).path("position").asInt()); }
    @Test @DisplayName("R3-T007 | 冻结商品拒绝新意向")
    void t007() throws Exception { long p=id(publish("商品",2));Client a=customer("buyer1"),b=customer("buyer2");submit(a,p);start(p);expect(b.post("/api/customer/intents",obj("productId",p)),409);assertEquals(1,count("intent")); }
    @Test @DisplayName("R3-T008 | 下架商品拒绝新意向")
    void t008() throws Exception { long p=id(publish("商品",2));withdraw(p,"void");Client c=customer("buyer");expect(c.post("/api/customer/intents",obj("productId",p)),409);assertEquals(0,count("intent")); }
    @Test @DisplayName("R3-T009 | 发起交易强制队首并冻结对应商品")
    void t009() throws Exception { long p=id(publish("A",2)),other=id(publish("B",2));Client a=customer("buyer1"),b=customer("buyer2");long first=id(submit(a,p));submit(b,p);assertEquals(first,start(p).path("intentId").asLong());assertEquals("frozen",work(p).path("status").asText());assertTrue(expect(anonymous().get("/api/products/"+p),200).json.path("readOnly").asBoolean());JsonNode list=expect(anonymous().get("/api/products"),200).json.path("items");assertFalse(hasId(list,p));assertTrue(hasId(list,other)); }
    @Test @DisplayName("R3-T010 | 空队列不能发起交易")
    void t010() throws Exception { long p=id(publish("商品",2));expect(seller.post("/api/admin/trades/"+p+"/start",obj()),409);assertEquals("active",work(p).path("status").asText()); }
    @Test @DisplayName("R3-T011 | 不能重复发起当前交易")
    void t011() throws Exception { long p=id(publish("商品",2));submit(customer("buyer"),p);long first=start(p).path("intentId").asLong();expect(seller.post("/api/admin/trades/"+p+"/start",obj()),409);assertEquals(first,work(p).path("trading").path("id").asLong()); }
    @Test @DisplayName("R3-T012 | 未登录卖家不能发起交易")
    void t012() throws Exception { long p=id(publish("商品",2));submit(customer("buyer"),p);expect(anonymous().post("/api/admin/trades/"+p+"/start",obj()),401);assertTrue(work(p).path("trading").isNull()); }
    @Test @DisplayName("R3-T013 | 成交只扣一件且买家进入成功历史")
    void t013() throws Exception { long p=id(publish("商品",2));Client c=customer("buyer");submit(c,p);start(p);JsonNode r=success(p);assertEquals(1,r.path("stock").asInt());assertEquals("active",r.path("status").asText());assertEquals("成功",orders(c).get(0).path("result").asText());assertEquals(0,mine(c).size()); }
    @Test @DisplayName("R3-T014 | 最后一件成交后进入已售终态")
    void t014() throws Exception { long p=id(publish("商品",1));Client a=customer("buyer1"),b=customer("buyer2");submit(a,p);submit(b,p);start(p);JsonNode r=success(p);assertEquals(0,r.path("stock").asInt());assertEquals("sold",r.path("status").asText());assertEquals("失败",orders(b).get(0).path("result").asText());assertEquals(0,mine(b).size());expect(anonymous().get("/api/products/"+p),404); }
    @Test @DisplayName("R3-T015 | 成交客户其余意向作废且不影响其他买家")
    void t015() throws Exception { long p=id(publish("商品",3));Client a=customer("buyer1"),b=customer("buyer2");submit(a,p);submit(a,p);submit(b,p);start(p);success(p);assertEquals(0,mine(a).size());assertEquals(2,orders(a).size());assertEquals(1,mine(b).size());assertEquals(2,work(p).path("stock").asInt()); }
    @Test @DisplayName("R3-T016 | 重复标记成功不能重复扣库存")
    void t016() throws Exception { long p=id(publish("商品",2));submit(customer("buyer"),p);start(p);success(p);expect(seller.post("/api/admin/trades/"+p+"/success",obj()),409);assertEquals(1,work(p).path("stock").asInt()); }
    @Test @DisplayName("R3-T017 | 失败作废后不扣库存且不自动递补")
    void t017() throws Exception { long p=id(publish("商品",2));Client a=customer("buyer1"),b=customer("buyer2");submit(a,p);long bid=id(submit(b,p));start(p);JsonNode r=fail(p,"void");assertEquals(2,r.path("stock").asInt());assertEquals("active",r.path("status").asText());assertTrue(work(p).path("trading").isNull());assertEquals("失败",orders(a).get(0).path("result").asText());assertEquals(bid,start(p).path("intentId").asLong()); }
    @Test @DisplayName("R3-T018 | 失败重排新增记录并排到队尾")
    void t018() throws Exception { long p=id(publish("商品",3));Client a=customer("buyer1"),b=customer("buyer2");long old=id(submit(a,p)),bid=id(submit(b,p));start(p);JsonNode r=fail(p,"requeue");assertNotEquals(old,r.path("newIntentId").asLong());assertEquals(2,r.path("position").asInt());assertEquals("失败",orders(a).get(0).path("result").asText());assertEquals(bid,start(p).path("intentId").asLong()); }
    @Test @DisplayName("R3-T019 | 非法失败处置不改变交易")
    void t019() throws Exception { long p=id(publish("商品",2));submit(customer("buyer"),p);start(p);expect(seller.post("/api/admin/trades/"+p+"/fail",obj("disposition","unknown")),400);assertEquals("frozen",work(p).path("status").asText());assertFalse(work(p).path("trading").isNull()); }
    @Test @DisplayName("R3-T020 | 没有交易时不能标记失败")
    void t020() throws Exception { long p=id(publish("商品",2));expect(seller.post("/api/admin/trades/"+p+"/fail",obj("disposition","void")),409);assertEquals(2,work(p).path("stock").asInt()); }
    @Test @DisplayName("R3-T021 | 手动下架作废全部排队且不进入商品历史")
    void t021() throws Exception { long p=id(publish("商品",2));Client a=customer("buyer1"),b=customer("buyer2");submit(a,p);submit(b,p);JsonNode r=withdraw(p,"void");assertEquals(2,r.path("handledIntents").asInt());assertEquals("withdrawn",r.path("status").asText());assertEquals("失败",orders(a).get(0).path("result").asText());assertEquals("失败",orders(b).get(0).path("result").asText());expect(anonymous().get("/api/products/"+p),404);assertFalse(hasId(expect(seller.get("/api/admin/product-history"),200).json,p)); }
    @Test @DisplayName("R3-T022 | 交易中允许下架并关闭当前意向")
    void t022() throws Exception { long p=id(publish("商品",2));Client a=customer("buyer");submit(a,p);start(p);withdraw(p,"void");assertTrue(work(p).path("trading").isNull());assertEquals(0,mine(a).size());assertEquals("失败",orders(a).get(0).path("result").asText()); }
    @Test @DisplayName("R3-T023 | 下架重排保留新意向且商品仍终态")
    void t023() throws Exception { long p=id(publish("商品",2));Client a=customer("buyer1"),b=customer("buyer2");submit(a,p);submit(b,p);withdraw(p,"requeue");assertEquals(4,count("intent"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM intent WHERE stage='queued'",Integer.class));assertEquals("withdrawn",work(p).path("status").asText());expect(seller.post("/api/admin/trades/"+p+"/start",obj()),409); }
    @Test @DisplayName("R3-T024 | 下架必须明确处置方式")
    void t024() throws Exception { long p=id(publish("商品",2));expect(seller.post("/api/admin/products/"+p+"/withdraw",obj()),400);assertEquals("active",work(p).path("status").asText()); }
    @Test @DisplayName("R3-T025 | 不能重复手动下架")
    void t025() throws Exception { long p=id(publish("商品",2));withdraw(p,"void");expect(seller.post("/api/admin/products/"+p+"/withdraw",obj("disposition","void")),409);assertEquals(0,count("intent")); }
    @Test @DisplayName("R3-T026 | 手动下架终态不能补货或重新上架")
    void t026() throws Exception { long p=id(publish("商品",2));withdraw(p,"void");expect(seller.post("/api/admin/products/"+p+"/restock",obj("stock",3)),409);expect(seller.post("/api/admin/products/"+p+"/relist",obj()),409);expect(anonymous().get("/api/products/"+p),404); }
    @Test @DisplayName("R3-T027 | 售罄补货后必须另行上架")
    void t027() throws Exception { long p=deplete();JsonNode r=expect(seller.post("/api/admin/products/"+p+"/restock",obj("stock",3)),200).json;assertEquals("depleted",r.path("status").asText());expect(anonymous().get("/api/products/"+p),404);expect(seller.post("/api/admin/products/"+p+"/relist",obj()),200);assertEquals(3,expect(anonymous().get("/api/products/"+p),200).json.path("stock").asInt()); }
    @Test @DisplayName("R3-T028 | 在售商品不能使用售罄补货接口")
    void t028() throws Exception { long p=id(publish("商品",2));expect(seller.post("/api/admin/products/"+p+"/restock",obj("stock",3)),409);assertEquals(2,work(p).path("stock").asInt()); }
    @Test @DisplayName("R3-T029 | 补货零和负数均拒绝")
    void t029() throws Exception { long p=deplete();for(int s:List.of(0,-1))expect(seller.post("/api/admin/products/"+p+"/restock",obj("stock",s)),400);assertEquals(0,work(p).path("stock").asInt()); }
    @Test @DisplayName("R3-T030 | 未补货不能重新上架")
    void t030() throws Exception { long p=deplete();expect(seller.post("/api/admin/products/"+p+"/relist",obj()),409);expect(anonymous().get("/api/products/"+p),404); }
    @Test @DisplayName("R3-T031 | 成交归零的已售终态不能恢复")
    void t031() throws Exception { long p=id(publish("商品",1));submit(customer("buyer"),p);start(p);success(p);expect(seller.post("/api/admin/products/"+p+"/restock",obj("stock",2)),409);expect(seller.post("/api/admin/products/"+p+"/relist",obj()),409);assertEquals("sold",work(p).path("status").asText()); }
    @Test @DisplayName("R3-T032 | 重新上架清旧队列并从第一位重新排队")
    void t032() throws Exception { JsonNode item=publish("商品",2);long p=id(item);Client a=customer("buyer");submit(a,p);expect(seller.patch("/api/admin/products/"+p,obj("stock",0,"categoryId",leafId,"version",item.path("version").asLong())),200);expect(seller.post("/api/admin/products/"+p+"/restock",obj("stock",2)),200);JsonNode r=expect(seller.post("/api/admin/products/"+p+"/relist",obj()),200).json;assertEquals(1,r.path("clearedIntents").asInt());assertEquals("失败",orders(a).get(0).path("result").asText());assertEquals(1,submit(a,p).path("position").asInt()); }
    @Test @DisplayName("R3-T033 | 所有卖家交易写入口拒绝客户会话")
    void t033() throws Exception { long p=id(publish("商品",2));Client c=customer("buyer");for(String suffix:List.of("withdraw","restock","relist"))expect(c.post("/api/admin/products/"+p+"/"+suffix,obj("stock",2,"disposition","void")),401);for(String suffix:List.of("start","success","fail"))expect(c.post("/api/admin/trades/"+p+"/"+suffix,obj("disposition","void")),401);assertEquals("active",work(p).path("status").asText()); }
    @Test @DisplayName("R3-T034 | 同时发起同商品交易只成功一次")
    void t034() throws Exception { long p=id(publish("商品",2));Client a=customer("buyer1"),b=customer("buyer2");submit(a,p);submit(b,p);List<Response> rs=parallel(List.of(()->seller.post("/api/admin/trades/"+p+"/start",obj()),()->seller.post("/api/admin/trades/"+p+"/start",obj())));assertEquals(List.of(200,409),rs.stream().map(r->r.status).sorted().toList());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM intent WHERE stage='trading'",Integer.class)); }
    @Test @DisplayName("R3-T035 | 20个并发意向不丢失不重复且不扣库存")
    void t035() throws Exception { long p=id(publish("商品",3));Client c=customer("buyer");List<Callable<Response>> tasks=new ArrayList<>();for(int i=0;i<20;i++)tasks.add(()->c.post("/api/customer/intents",obj("productId",p)));var rs=parallel(tasks);assertTrue(rs.stream().allMatch(r->r.status==201),rs.stream().map(r->r.status+":"+r.text).toList().toString());assertEquals(20,rs.stream().map(r->id(r.json)).distinct().count());assertEquals(IntStream.rangeClosed(1,20).boxed().toList(),rs.stream().map(r->r.json.path("position").asInt()).sorted().toList());assertEquals(3,work(p).path("stock").asInt()); }
    @Test @DisplayName("R3-T036 | 同时成交不超卖不重复记账")
    void t036() throws Exception { long p=id(publish("商品",1));submit(customer("buyer"),p);start(p);var rs=parallel(List.of(()->seller.post("/api/admin/trades/"+p+"/success",obj()),()->seller.post("/api/admin/trades/"+p+"/success",obj())));assertEquals(List.of(200,409),rs.stream().map(r->r.status).sorted().toList());assertEquals(0,work(p).path("stock").asInt());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM intent WHERE outcome='success'",Integer.class)); }
    @Test @DisplayName("R3-T037 | 不同商品可独立发起交易")
    void t037() throws Exception { long a=id(publish("A",2)),b=id(publish("B",2));Client c=customer("buyer");submit(c,a);submit(c,b);var rs=parallel(List.of(()->seller.post("/api/admin/trades/"+a+"/start",obj()),()->seller.post("/api/admin/trades/"+b+"/start",obj())));assertTrue(rs.stream().allMatch(r->r.status==200));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM intent WHERE stage='trading'",Integer.class)); }
    @Test @DisplayName("R3-T038 | 损坏JSON不能创建意向")
    void t038() throws Exception { Client c=customer("buyer");expect(c.raw("POST","/api/customer/intents","{"),400);expect(c.raw("POST","/api/customer/intents","[]"),400);assertEquals(0,count("intent")); }
    @Test @DisplayName("R3-T039 | 旧匿名口令和单商品接口停用")
    void t039() throws Exception { for(String path:List.of("/api/product","/api/intents/OLD12345","/api/admin/trade/start"))expect(anonymous().get(path),410);expect(anonymous().post("/api/intents",obj("name","匿名","phone","13800000000")),410); }
    @Test @DisplayName("R3-T040 | 新页面与脚本资源可加载")
    void t040() throws Exception { for(String path:List.of("/","/register.html","/customer-login.html","/orders.html","/app.js","/admin.js","/customers.js")){Response r=expect(anonymous().get(path),200);assertTrue(r.bytes.length>100,path);} }
}
