package com.coursewise.shopping.review3;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Third review account acceptance tests: real HTTP, independently reset fixtures. */
public class AccountAcceptanceTest extends Review3Base {
    private static final String PW="Testpass123";
    private Response register(Client c,String username,String password,String phone,String place) throws Exception {
        Map<String,Object> body=obj("username",username,"password",password,"phone",phone);
        if(place!=null) body.put("defaultPlace",place);
        return c.post("/api/customer/register",body);
    }
    private JsonNode history(Client c,String query) throws Exception {
        return expect(c.get("/api/customer/orders"+query),200).json;
    }
    private JsonNode directory(long cid,String query) throws Exception {
        return expect(seller.get("/api/admin/customers/"+cid+"/orders"+query),200).json;
    }
    private Set<String> fields(JsonNode node) {
        Set<String> names=new HashSet<>(); node.fieldNames().forEachRemaining(names::add); return names;
    }

    @Test @DisplayName("R3-A001 | 合法客户注册成功且不自动登录")
    void a001() throws Exception {
        Client c = anonymous();
        Response r = expect(register(c,"alice",PW,"13800000001","图书馆门口"),201);
        assertTrue(r.json.path("ok").asBoolean()); assertTrue(id(r.json)>0);
        assertEquals(Set.of("id","ok"), fields(r.json));
        expect(c.get("/api/customer/me"),401);
    }

    @Test @DisplayName("R3-A002 | 空白用户名拒绝注册且不新增客户")
    void a002() throws Exception {
        expect(register(anonymous(),"   ",PW,"13800000000",null),400);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM customer",Integer.class));
    }

    @Test @DisplayName("R3-A003 | 七位密码拒绝注册")
    void a003() throws Exception {
        expect(register(anonymous(),"short","1234567","13800000000",null),400);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM customer",Integer.class));
    }

    @Test @DisplayName("R3-A004 | 恰好八位纯数字密码允许注册登录")
    void a004() throws Exception {
        Client c=anonymous(); expect(register(c,"eight","12345678","13800000000",null),201);
        expect(c.post("/api/customer/login",obj("username","eight","password","12345678")),200);
        assertEquals("eight",expect(c.get("/api/customer/me"),200).json.path("username").asText());
    }

    @Test @DisplayName("R3-A005 | 缺少联系电话拒绝注册")
    void a005() throws Exception {
        expect(anonymous().post("/api/customer/register",obj("username","nophone","password",PW)),400);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM customer",Integer.class));
    }

    @Test @DisplayName("R3-A006 | 默认交易地点可不填")
    void a006() throws Exception {
        Client c=anonymous(); expect(register(c,"noplace",PW,"123",null),201);
        expect(c.post("/api/customer/login",obj("username","noplace","password",PW)),200);
        assertEquals("未设置",expect(c.get("/api/customer/me"),200).json.path("defaultPlace").asText());
    }

    @Test @DisplayName("R3-A007 | 重复用户名返回冲突且只有一条记录")
    void a007() throws Exception {
        customer("alice"); expect(register(anonymous(),"alice",PW,"other-phone",null),409);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE username='alice'",Integer.class));
    }

    @Test @DisplayName("R3-A008 | 同一电话允许注册两个不同用户名")
    void a008() throws Exception {
        expect(register(anonymous(),"phone_a",PW,"13800000000",null),201);
        expect(register(anonymous(),"phone_b",PW,"13800000000",null),201);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM customer",Integer.class));
    }

    @Test @DisplayName("R3-A009 | 用户名首尾空格规范化并保持唯一")
    void a009() throws Exception {
        customer("trimmed"); expect(register(anonymous(),"  trimmed  ",PW,"13800000000",null),409);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM customer",Integer.class));
    }

    @Test @DisplayName("R3-A010 | 客户可与卖家使用相同用户名")
    void a010() throws Exception {
        Client c=customer("admin");
        assertEquals("admin",expect(c.get("/api/customer/me"),200).json.path("username").asText());
        expect(seller.get("/api/auth/session"),200);
    }

    @Test @DisplayName("R3-A011 | 未登录读取当前客户信息被拒绝")
    void a011() throws Exception {
        expect(anonymous().get("/api/customer/me"),401);
    }

    @Test @DisplayName("R3-A012 | 登录设置独立且受保护的客户Cookie")
    void a012() throws Exception {
        customer("cookie_user"); Client c=anonymous();
        Response r=expect(c.post("/api/customer/login",obj("username","cookie_user","password",PW)),200);
        String cookie=r.headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("set-cookie"))
            .flatMap(e->e.getValue().stream()).reduce("",(a,b)->a+";"+b);
        assertTrue(cookie.contains("customer_session=")); assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict")); assertTrue(cookie.contains("Path=/"));
        assertEquals("cookie_user",expect(c.get("/api/customer/me"),200).json.path("username").asText());
    }

    @Test @DisplayName("R3-A013 | 错误密码与不存在账户采用相同提示")
    void a013() throws Exception {
        customer("known"); Client c=anonymous();
        Response wrong=expect(c.post("/api/customer/login",obj("username","known","password","wrongpass")),401);
        Response absent=expect(c.post("/api/customer/login",obj("username","unknown","password","wrongpass")),401);
        assertEquals(wrong.json.path("error"),absent.json.path("error"));
        assertEquals("用户名或密码错误",wrong.json.path("error").asText()); expect(c.get("/api/customer/me"),401);
    }

    @Test @DisplayName("R3-A014 | 电话号码不能代替用户名登录")
    void a014() throws Exception {
        expect(register(anonymous(),"not_phone",PW,"13800000000",null),201);
        expect(anonymous().post("/api/customer/login",obj("username","13800000000","password",PW)),401);
    }

    @Test @DisplayName("R3-A015 | 退出后客户会话失效")
    void a015() throws Exception {
        Client c=customer("logout_user"); expect(c.post("/api/customer/logout",obj()),200);
        expect(c.get("/api/customer/me"),401); expect(c.get("/api/customer/orders"),401);
    }

    @Test @DisplayName("R3-A016 | 客户与卖家会话不能互相越权")
    void a016() throws Exception {
        Client c=customer("role_user"); expect(seller.get("/api/customer/me"),401);
        expect(c.get("/api/admin/customers"),401); expect(c.get("/api/customer/me"),200);
        expect(seller.get("/api/auth/session"),200);
    }

    @Test @DisplayName("R3-A017 | 客户资料只返回公开字段且密码哈希存储")
    void a017() throws Exception {
        Client c=customer("profile"); JsonNode me=expect(c.get("/api/customer/me"),200).json;
        assertEquals(Set.of("id","username","phone","defaultPlace","registeredAt"),fields(me));
        java.time.Instant.parse(me.path("registeredAt").asText());
        String stored=jdbc.queryForObject("SELECT password_hash FROM customer WHERE username=?",String.class,"profile");
        assertNotEquals(PW,stored);
        assertTrue(org.springframework.security.crypto.scrypt.SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8().matches(PW,stored));
    }

    @Test @DisplayName("R3-A018 | 新注册但未下单客户也出现在卖家列表")
    void a018() throws Exception {
        customer("never_ordered_a"); customer("never_ordered_b");
        JsonNode list=expect(seller.get("/api/admin/customers"),200).json;
        assertEquals(2,list.path("total").asInt());
        Set<String> names=new HashSet<>(); list.path("items").forEach(x->names.add(x.path("username").asText()));
        assertEquals(Set.of("never_ordered_a","never_ordered_b"),names);
    }

    @Test @DisplayName("R3-A019 | 匿名访问卖家客户列表被拒绝")
    void a019() throws Exception {
        customer("private_list"); expect(anonymous().get("/api/admin/customers"),401);
    }

    @Test @DisplayName("R3-A020 | 仅登录客户不能查看全部注册用户")
    void a020() throws Exception {
        Client c=customer("only_customer"); expect(c.get("/api/admin/customers"),401);
    }

    @Test @DisplayName("R3-A021 | 卖家客户列表字段完整且不返回密码和姓名")
    void a021() throws Exception {
        customer("list_fields"); JsonNode row=expect(seller.get("/api/admin/customers"),200).json.path("items").get(0);
        assertEquals(Set.of("id","username","phone","defaultPlace","registeredAt"),fields(row));
        assertEquals("list_fields",row.path("username").asText()); assertEquals("13800000000",row.path("phone").asText());
    }

    @Test @DisplayName("R3-A022 | 卖家列表可显示未设置地点及非手机号格式电话")
    void a022() throws Exception {
        expect(register(anonymous(),"extension",PW,"分机-123",null),201);
        JsonNode row=expect(seller.get("/api/admin/customers"),200).json.path("items").get(0);
        assertEquals("分机-123",row.path("phone").asText()); assertEquals("未设置",row.path("defaultPlace").asText());
    }

    @Test @DisplayName("R3-A023 | 未登录客户不能读取历史订单")
    void a023() throws Exception {
        expect(anonymous().get("/api/customer/orders"),401);
    }

    @Test @DisplayName("R3-A024 | 无下单客户历史返回空列表")
    void a024() throws Exception {
        Client c=customer("empty_history"); JsonNode h=history(c,"");
        assertEquals(0,h.path("total").asInt()); assertTrue(h.path("items").isEmpty());
    }

    @Test @DisplayName("R3-A025 | 排队中和交易中的意向不进入终态历史")
    void a025() throws Exception {
        Client c=customer("ongoing"); long cid=id(expect(c.get("/api/customer/me"),200).json);
        long p=id(publish("进行中商品",2)); submit(c,p);
        assertEquals(0,history(c,"").path("total").asInt()); assertEquals(0,directory(cid,"").path("total").asInt());
        start(p); assertEquals(0,history(c,"").path("total").asInt()); assertEquals(0,directory(cid,"").path("total").asInt());
    }

    @Test @DisplayName("R3-A026 | 真实成交后客户历史显示成功和完成时间")
    void a026() throws Exception {
        Client c=customer("success_history"); long p=id(publish("成功纪念杯",2)); JsonNode intent=submit(c,p);
        start(p); success(p); JsonNode h=history(c,""); assertEquals(1,h.path("total").asInt());
        JsonNode row=h.path("items").get(0); assertEquals(id(intent),id(row));
        assertEquals("成功纪念杯",row.path("productName").asText()); assertEquals("成功",row.path("result").asText());
        java.time.Instant.parse(row.path("submittedAt").asText()); java.time.Instant.parse(row.path("closedAt").asText());
    }

    @Test @DisplayName("R3-A027 | 交易失败作废进入客户历史且不扣库存")
    void a027() throws Exception {
        Client c=customer("void_history"); long p=id(publish("失败作废商品",2)); submit(c,p); start(p); fail(p,"void");
        JsonNode row=history(c,"").path("items").get(0); assertEquals("失败",row.path("result").asText());
        java.time.Instant.parse(row.path("closedAt").asText());
        assertEquals(2,expect(seller.get("/api/admin/products/"+p),200).json.path("stock").asInt());
    }

    @Test @DisplayName("R3-A028 | 失败重新排队保留失败历史并新增进行中记录")
    void a028() throws Exception {
        Client c=customer("requeue_history"); long p=id(publish("重排商品",2)); long old=id(submit(c,p)); start(p); fail(p,"requeue");
        JsonNode h=history(c,""); assertEquals(1,h.path("total").asInt()); assertEquals(old,id(h.path("items").get(0)));
        assertEquals("失败",h.path("items").get(0).path("result").asText());
        JsonNode current=expect(c.get("/api/customer/intents"),200).json.path("items"); assertEquals(1,current.size());
        assertNotEquals(old,id(current.get(0))); assertEquals("queued",current.get(0).path("stage").asText());
    }

    @Test @DisplayName("R3-A029 | 客户历史严格隔离不能看到其他客户记录")
    void a029() throws Exception {
        Client alice=customer("owner_alice"), bob=customer("owner_bob"); long p=id(publish("隔离商品",1));
        submit(alice,p); start(p); success(p); assertEquals(1,history(alice,"").path("total").asInt());
        JsonNode other=history(bob,""); assertEquals(0,other.path("total").asInt()); assertTrue(other.path("items").isEmpty());
    }

    @Test @DisplayName("R3-A030 | 买家历史和卖家该客户购买历史记录集合一致")
    void a030() throws Exception {
        Client c=customer("both_sides"); long cid=id(expect(c.get("/api/customer/me"),200).json);
        long p=id(publish("双方成功",2)); submit(c,p); start(p); success(p);
        long q=id(publish("双方失败",2)); submit(c,q); start(q); fail(q,"void");
        JsonNode own=history(c,""), admin=directory(cid,""); assertEquals(2,own.path("total").asInt());
        assertEquals(own.path("items"),admin.path("items")); assertEquals(cid,admin.path("customerId").asLong());
        assertEquals("both_sides",admin.path("username").asText());
    }

    @Test @DisplayName("R3-A031 | 客户历史倒序分页无重复漏项")
    void a031() throws Exception {
        Client c=customer("paged_history"); long p=id(publish("先下单",2)); long first=id(submit(c,p)); start(p); success(p);
        Thread.sleep(3); long q=id(publish("后下单",2)); long second=id(submit(c,q)); start(q); success(q);
        JsonNode one=history(c,"?page=1&size=1"), two=history(c,"?page=2&size=1");
        assertEquals(2,one.path("total").asInt()); assertEquals(2,one.path("totalPages").asInt());
        assertEquals(second,id(one.path("items").get(0))); assertEquals(first,id(two.path("items").get(0)));
    }

    @Test @DisplayName("R3-A032 | 库存归零后未成交买家的历史记失败")
    void a032() throws Exception {
        Client a=customer("sold_winner"),b=customer("sold_unsold"); long bid=id(expect(b.get("/api/customer/me"),200).json);
        long p=id(publish("限量一件",1)); submit(a,p); long loser=id(submit(b,p)); start(p); success(p);
        assertEquals("成功",history(a,"").path("items").get(0).path("result").asText());
        JsonNode bh=history(b,""); assertEquals(loser,id(bh.path("items").get(0)));
        assertEquals("失败",bh.path("items").get(0).path("result").asText());
        assertTrue(expect(b.get("/api/customer/intents"),200).json.path("items").isEmpty());
        assertEquals(bh.path("items"),directory(bid,"").path("items"));
    }

    @Test @DisplayName("R3-A033 | 历史记录只读且匿名或客户不能读卖家购买历史")
    void a033() throws Exception {
        Client c=customer("readonly"); long cid=id(expect(c.get("/api/customer/me"),200).json);
        long p=id(publish("只读商品",1)); submit(c,p); start(p); success(p);
        JsonNode before=history(c,"").path("items"); expect(c.delete("/api/customer/orders"),405);
        assertEquals(before,history(c,"").path("items"));
        expect(anonymous().get("/api/admin/customers/"+cid+"/orders"),401);
        expect(c.get("/api/admin/customers/"+cid+"/orders"),401);
    }

    @Test @DisplayName("R3-A034 | 商品下架后客户已有历史仍可查询")
    void a034() throws Exception {
        Client c=customer("withdraw_history"); long cid=id(expect(c.get("/api/customer/me"),200).json);
        long p=id(publish("下架后仍保留历史",2)); submit(c,p); start(p); success(p);
        JsonNode before=history(c,"").path("items");
        expect(seller.post("/api/admin/products/"+p+"/withdraw",obj("disposition","void")),200);
        expect(anonymous().get("/api/products/"+p),404);
        assertEquals(before,history(c,"").path("items")); assertEquals(before,directory(cid,"").path("items"));
    }

    @Test @DisplayName("R3-A035 | 注册字段长度边界及未知客户历史")
    void a035() throws Exception {
        expect(register(anonymous(),"u".repeat(64),PW,"1".repeat(32),"地".repeat(200)),201);
        expect(register(anonymous(),"u".repeat(65),PW,"13800000000",null),400);
        expect(register(anonymous(),"long_phone",PW,"1".repeat(33),null),400);
        expect(register(anonymous(),"long_place",PW,"13800000000","地".repeat(201)),400);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM customer",Integer.class));
        expect(seller.get("/api/admin/customers/9223372036854775807/orders"),404);
    }
}
