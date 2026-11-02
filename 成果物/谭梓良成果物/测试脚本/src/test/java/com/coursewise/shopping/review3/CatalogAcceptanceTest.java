package com.coursewise.shopping.review3;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** 第3次评审商品模块验收。HTTP真实服务；隔离数据由Review3Base管理。 */
class CatalogAcceptanceTest extends Review3Base {
    @Test @DisplayName("R3-P001 | 游客访问空商品列表")
    void p001() throws Exception {

        var r=expect(anonymous().get("/api/products"),200).json;
        assertEquals(0,r.path("items").size()); assertEquals(0,r.path("total").asInt());
        assertEquals(1,r.path("page").asInt()); assertEquals(12,r.path("size").asInt());
    }

    @Test @DisplayName("R3-P002 | 多商品列表卡片字段与排序")
    void p002() throws Exception {

        var first=publish("第一件",3); var second=publish("第二件",2);
        var items=expect(anonymous().get("/api/products"),200).json.path("items");
        assertEquals(2,items.size()); assertEquals(id(second),items.get(0).path("id").asLong());
        assertEquals(id(first),items.get(1).path("id").asLong());
        var keys=new HashSet<String>();items.get(0).fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("id","name","priceCents","image","category"),keys);
        assertEquals("/placeholder.svg",items.get(0).path("image").asText());
    }

    @Test @DisplayName("R3-P003 | 14件商品默认分页及越界空页")
    void p003() throws Exception {

        for(int i=0;i<14;i++)publish("分页商品"+i,1);
        assertEquals(12,expect(anonymous().get("/api/products"),200).json.path("items").size());
        var p=expect(anonymous().get("/api/products?page=2"),200).json;
        assertEquals(2,p.path("items").size());assertEquals(14,p.path("total").asInt());
        assertEquals(2,p.path("totalPages").asInt());
        assertEquals(0,expect(anonymous().get("/api/products?page=3"),200).json.path("items").size());
    }

    @Test @DisplayName("R3-P004 | 非法分页参数返回客户端错误")
    void p004() throws Exception {

        for(String q:List.of("page=0","page=-1","size=0","size=61","page=abc"))
            expect(anonymous().get("/api/products?"+q),400);
    }

    @Test @DisplayName("R3-P005 | 二级类别过滤与一级类别拒绝")
    void p005() throws Exception {

        var a=publish("杯子",1);long other=category("其他",parentId);
        var b=expect(raw(seller,Map.of("name","笔","categoryId",Long.toString(other)),List.of()),201).json;
        var x=expect(anonymous().get("/api/products?categoryId="+leafId),200).json;
        assertEquals(1,x.path("total").asInt());assertEquals(id(a),x.path("items").get(0).path("id").asLong());
        var y=expect(anonymous().get("/api/products?categoryId="+other),200).json;
        assertEquals(id(b),y.path("items").get(0).path("id").asLong());
        expect(anonymous().get("/api/products?categoryId="+parentId),400);
    }

    @Test @DisplayName("R3-P006 | 中文名称模糊搜索")
    void p006() throws Exception {

        var p=publish("手工陶瓷杯",2);publish("钢笔",1);
        var r=search("陶瓷");assertEquals(1,r.path("total").asInt());
        assertEquals(id(p),r.path("items").get(0).path("id").asLong());
    }

    @Test @DisplayName("R3-P007 | 描述纯文本搜索不区分英文大小写")
    void p007() throws Exception {

        expect(raw(seller,Map.of("description","<p>Classic 陶瓷</p>"),List.of()),201);
        assertEquals(1,search("CLASSIC").path("total").asInt());
        assertEquals(1,search("陶瓷").path("total").asInt());
    }

    @Test @DisplayName("R3-P008 | HTML标记及仅类别名称不参与关键词搜索")
    void p008() throws Exception {

        expect(raw(seller,Map.of("description","<p>Classic</p>"),List.of()),201);
        String leaf=expect(seller.get("/api/admin/categories"),200).json.get(0).path("children").get(0).path("name").asText();
        for(String q:List.of("<p>",leaf,"绝无此关键词"))assertEquals(0,search(q).path("total").asInt());
    }

    @Test @DisplayName("R3-P009 | 百分号下划线与反斜杠按字面搜索")
    void p009() throws Exception {

        publish("优惠100%",1);publish("型号_A",1);publish("路径\\A",1);publish("普通商品",1);
        for(String q:List.of("%","_","\\"))assertEquals(1,search(q).path("total").asInt(),q);
    }

    @Test @DisplayName("R3-P010 | 空白搜索与搜索类别分页组合")
    void p010() throws Exception {

        publish("杯甲",1);publish("杯乙",1);long other=category("外类",parentId);
        expect(raw(seller,Map.of("name","杯丙","categoryId",Long.toString(other)),List.of()),201);
        assertEquals(3,search("   ").path("total").asInt());
        var p=expect(anonymous().get("/api/products?q="+enc("杯")+"&categoryId="+leafId+"&size=1&page=2"),200).json;
        assertEquals(2,p.path("total").asInt());assertEquals(1,p.path("items").size());
        assertEquals("杯甲",p.path("items").get(0).path("name").asText());
    }

    @Test @DisplayName("R3-P011 | 详情展示价格库存类别且不泄露客户信息")
    void p011() throws Exception {

        var p=publish("详情商品",3);var d=expect(anonymous().get("/api/products/"+id(p)),200).json;
        assertEquals(1250,d.path("priceCents").asLong());assertEquals(3,d.path("stock").asInt());
        assertEquals("active",d.path("status").asText());assertFalse(d.path("readOnly").asBoolean());
        assertEquals(leafId,d.path("category").path("id").asLong());
        for(String f:List.of("customers","intents","password","passwordHash","phone","passcode"))assertFalse(d.has(f));
    }

    @Test @DisplayName("R3-P012 | 不存在的商品详情返回404")
    void p012() throws Exception {

        expect(anonymous().get("/api/products/999999999"),404);
    }

    @Test @DisplayName("R3-P013 | 真实交易冻结商品列表隐藏且直链只读")
    void p013() throws Exception {

        var p=publish("冻结目标",3);publish("其他商品",1);submit(customer("freezeBuyer"),id(p));start(id(p));
        var list=expect(anonymous().get("/api/products"),200).json;
        assertEquals(1,list.path("total").asInt());assertEquals("其他商品",list.path("items").get(0).path("name").asText());
        assertEquals(0,search("冻结目标").path("total").asInt());
        var d=expect(anonymous().get("/api/products/"+id(p)),200).json;
        assertEquals("frozen",d.path("status").asText());assertTrue(d.path("readOnly").asBoolean());
    }

    @Test @DisplayName("R3-P014 | 手动下架商品公开不可访问后台仍可查")
    void p014() throws Exception {

        var p=publish("下架商品",1);expect(seller.post("/api/admin/products/"+id(p)+"/withdraw",obj("disposition","void")),200);
        expect(anonymous().get("/api/products/"+id(p)),404);
        assertEquals(0,expect(anonymous().get("/api/products"),200).json.path("total").asInt());
        assertEquals("withdrawn",expect(seller.get("/api/admin/products/"+id(p)),200).json.path("status").asText());
    }

    @Test @DisplayName("R3-P015 | 无描述无图片仍可发布")
    void p015() throws Exception {

        var p=expect(raw(seller,Map.of(),List.of()),201).json;
        assertEquals(3,p.path("stock").asInt());assertEquals(1250,p.path("priceCents").asInt());
        assertEquals(0,p.path("images").size());assertEquals("/placeholder.svg",p.path("image").asText());
    }

    @Test @DisplayName("R3-P016 | 名称128字符边界与129字符拒绝")
    void p016() throws Exception {

        expect(raw(seller,Map.of("name","杯".repeat(128)),List.of()),201);
        expect(raw(seller,Map.of("name","杯".repeat(129)),List.of()),400);assertEquals(1,countProducts());
    }

    @Test @DisplayName("R3-P017 | 空白名称拒绝且首尾空格去除")
    void p017() throws Exception {

        expect(raw(seller,Map.of("name","   "),List.of()),400);
        var p=expect(raw(seller,Map.of("name","  合法商品  "),List.of()),201).json;
        assertEquals("合法商品",p.path("name").asText());assertEquals(1,countProducts());
    }

    @Test @DisplayName("R3-P018 | 金额一分及最大金额精确保存")
    void p018() throws Exception {

        assertEquals(1,expect(raw(seller,Map.of("price","0.01"),List.of()),201).json.path("priceCents").asLong());
        assertEquals(999999999L,expect(raw(seller,Map.of("price","9999999.99"),List.of()),201).json.path("priceCents").asLong());
    }

    @Test @DisplayName("R3-P019 | 非法金额和5000位数字拒绝且不落库")
    void p019() throws Exception {

        for(String v:List.of("0","-1","1.234","1e2","NaN","10000000","9".repeat(5000)))
            expect(raw(seller,Map.of("price",v),List.of()),400);
        assertEquals(0,countProducts());
    }

    @Test @DisplayName("R3-P020 | 库存零负数和小数禁止发布")
    void p020() throws Exception {

        for(String v:List.of("0","-1","1.5"))expect(raw(seller,Map.of("stock",v),List.of()),400);
        assertEquals(0,countProducts());
    }

    @Test @DisplayName("R3-P021 | 发布必须绑定存在的二级类别")
    void p021() throws Exception {

        expect(raw(seller,Map.of("categoryId",Long.toString(parentId)),List.of()),400);
        expect(raw(seller,Map.of("categoryId","999999999"),List.of()),400);
        var f=fields();f.remove("categoryId");expect(multipart(seller,"/api/products",f,List.of()),400);
        assertEquals(0,countProducts());
    }

    @Test @DisplayName("R3-P022 | 九张商品图上限及顺序主图")
    void p022() throws Exception {

        var files=new ArrayList<Upload>();var contents=new ArrayList<byte[]>();
        for(int i=0;i<9;i++){byte[] b=colouredPng(i);contents.add(b);files.add(new Upload("images","p"+i+".png","image/png",b));}
        var p=expect(raw(seller,Map.of(),files),201).json;assertEquals(9,p.path("images").size());
        assertEquals(p.path("images").get(0).asText(),p.path("image").asText());
        for(int i=0;i<9;i++)assertArrayEquals(contents.get(i),expect(anonymous().get(p.path("images").get(i).asText()),200).bytes);
    }

    @Test @DisplayName("R3-P023 | 十张商品图超限禁止发布")
    void p023() throws Exception {

        var files=new ArrayList<Upload>();for(int i=0;i<10;i++)files.add(new Upload("images","p"+i+".png","image/png",png()));
        expect(raw(seller,Map.of(),files),400);assertEquals(0,countProducts());assertNoUploaded();
    }

    @Test @DisplayName("R3-P024 | 伪造和空PNG被拒绝且整组不保存")
    void p024() throws Exception {

        expect(raw(seller,Map.of(),List.of(new Upload("images","ok.png","image/png",png()),new Upload("images","fake.png","image/png","not png".getBytes(StandardCharsets.UTF_8)))),400);
        expect(raw(seller,Map.of(),List.of(new Upload("images","empty.png","image/png",new byte[0]))),400);
        assertEquals(0,countProducts());assertNoUploaded();
    }

    @Test @DisplayName("R3-P025 | 图片MIME扩展名真实内容必须一致")
    void p025() throws Exception {

        expect(raw(seller,Map.of(),List.of(new Upload("images","fake.jpg","image/jpeg",png()))),400);
        expect(raw(seller,Map.of(),List.of(new Upload("images","fake.gif","image/gif",png()))),400);
        assertEquals(0,countProducts());assertNoUploaded();
    }

    @Test @DisplayName("R3-P026 | 恰好5MiB图片允许发布")
    void p026() throws Exception {

        byte[] b=Arrays.copyOf(png(),5*1024*1024);
        var p=expect(raw(seller,Map.of(),List.of(new Upload("images","limit.png","image/png",b))),201).json;
        assertArrayEquals(b,expect(anonymous().get(p.path("image").asText()),200).bytes);
    }

    @Test @DisplayName("R3-P027 | 图片超过上限1字节拒绝")
    void p027() throws Exception {

        byte[] b=Arrays.copyOf(png(),5*1024*1024+1);
        expect(raw(seller,Map.of(),List.of(new Upload("images","oversized.png","image/png",b))),400);
        assertEquals(0,countProducts());assertNoUploaded();
    }

    @Test @DisplayName("R3-P028 | 描述保留允许格式并删除脚本事件和外链")
    void p028() throws Exception {

        String html="<p onclick='alert(1)'>Classic <b>杯</b><script>alert(1)</script><a href='https://example.com'>文字</a><img src='https://example.com/a.png'></p>";
        var p=expect(raw(seller,Map.of("description",html),List.of()),201).json;
        String d=expect(anonymous().get("/api/products/"+id(p)),200).json.path("description").asText();
        assertTrue(d.contains("<b>杯</b>"));assertTrue(d.contains("Classic"));
        for(String v:List.of("<script","onclick","https://example.com","<a "))assertFalse(d.contains(v),d);
    }

    @Test @DisplayName("R3-P029 | 公开类别树体现两级归属及重命名")
    void p029() throws Exception {

        long child=category("旧名称",parentId);expect(seller.patch("/api/admin/categories/"+child,obj("name","新名称")),200);
        var tree=expect(anonymous().get("/api/categories"),200).json;
        JsonNode found=null;for(var p:tree)for(var c:p.path("children"))if(c.path("id").asLong()==child)found=c;
        assertNotNull(found);assertEquals("新名称",found.path("name").asText());assertEquals(parentId,found.path("parentId").asLong());
    }

    @Test @DisplayName("R3-P030 | 禁止三级类别和不存在的父类别")
    void p030() throws Exception {

        expect(seller.post("/api/admin/categories",obj("name","三级","parentId",leafId)),400);
        expect(seller.post("/api/admin/categories",obj("name","坏父级","parentId",999999999)),400);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM category",Integer.class));
    }

    @Test @DisplayName("R3-P031 | 重命名不能同时更换父类别且空名称拒绝")
    void p031() throws Exception {

        String before=expect(anonymous().get("/api/categories"),200).text;
        expect(seller.patch("/api/admin/categories/"+leafId,obj("name","移位","parentId",null)),400);
        expect(seller.patch("/api/admin/categories/"+leafId,obj("name","  ")),400);
        assertEquals(before,expect(anonymous().get("/api/categories"),200).text);
    }

    @Test @DisplayName("R3-P032 | 有下属类别的一级类别禁止删除")
    void p032() throws Exception {

        expect(seller.delete("/api/admin/categories/"+parentId),409);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM category",Integer.class));
    }

    @Test @DisplayName("R3-P033 | 关联在售或下架商品的类别禁止删除")
    void p033() throws Exception {

        var p=publish("关联商品",1);expect(seller.delete("/api/admin/categories/"+leafId),409);
        expect(seller.post("/api/admin/products/"+id(p)+"/withdraw",obj("disposition","void")),200);
        expect(seller.delete("/api/admin/categories/"+leafId),409);
    }

    @Test @DisplayName("R3-P034 | 空二级类别允许删除")
    void p034() throws Exception {

        long unused=category("可删",parentId);expect(seller.delete("/api/admin/categories/"+unused),200);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE id=?",Integer.class,unused));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE id=?",Integer.class,leafId));
    }

    @Test @DisplayName("R3-P035 | 库存类别编辑后公开查询同步")
    void p035() throws Exception {

        var p=publish("编辑商品",3);long other=category("改挂目标",parentId);
        var updated=expect(edit(p,5,other,p.path("version").asLong()),200).json;
        assertEquals(5,updated.path("stock").asInt());assertEquals(other,updated.path("category").path("id").asLong());
        assertEquals(0,expect(anonymous().get("/api/products?categoryId="+leafId),200).json.path("total").asInt());
        assertEquals(1,expect(anonymous().get("/api/products?categoryId="+other),200).json.path("total").asInt());
    }

    @Test @DisplayName("R3-P036 | 名称价格等不可编辑字段被拒绝")
    void p036() throws Exception {

        var p=publish("只读商品",3);var body=obj("stock",4,"categoryId",leafId,"version",p.path("version").asLong(),"name","篡改");
        expect(seller.patch("/api/admin/products/"+id(p),body),400);
        expect(seller.patch("/api/admin/products/"+id(p),obj("price","1.00")),400);
        var now=expect(seller.get("/api/admin/products/"+id(p)),200).json;
        assertEquals("只读商品",now.path("name").asText());assertEquals(1250,now.path("priceCents").asInt());assertEquals(3,now.path("stock").asInt());
    }

    @Test @DisplayName("R3-P037 | 非法库存类型及非二级类别编辑拒绝")
    void p037() throws Exception {

        var p=publish("库存校验",3);long v=p.path("version").asLong();
        for(Object stock:List.of(-1,1.5,"3"))expect(edit(p,stock,leafId,v),400);
        expect(edit(p,3,parentId,v),400);assertEquals(3,expect(seller.get("/api/admin/products/"+id(p)),200).json.path("stock").asInt());
    }

    @Test @DisplayName("R3-P038 | 陈旧版本不能覆盖新库存")
    void p038() throws Exception {

        var p=publish("版本控制",3);long v=p.path("version").asLong();
        expect(edit(p,4,leafId,v),200);expect(edit(p,5,leafId,v),409);
        assertEquals(4,expect(seller.get("/api/admin/products/"+id(p)),200).json.path("stock").asInt());
    }

    @Test @DisplayName("R3-P039 | 库存调零售罄且补成正数不自动上架")
    void p039() throws Exception {

        var p=publish("售罄商品",3);var zero=expect(edit(p,0,leafId,p.path("version").asLong()),200).json;
        assertEquals("depleted",zero.path("status").asText());expect(anonymous().get("/api/products/"+id(p)),404);
        var replenished=expect(edit(zero,5,leafId,zero.path("version").asLong()),200).json;
        assertEquals("depleted",replenished.path("status").asText());expect(anonymous().get("/api/products/"+id(p)),404);
        assertEquals(0,expect(anonymous().get("/api/products"),200).json.path("total").asInt());
    }

    @Test @DisplayName("R3-P040 | 交易处理中库存调零必须拒绝")
    void p040() throws Exception {

        var p=publish("交易保护",3);submit(customer("stockGuard"),id(p));start(id(p));
        var current=expect(seller.get("/api/admin/products/"+id(p)),200).json;
        expect(edit(current,0,leafId,current.path("version").asLong()),409);
        var after=expect(seller.get("/api/admin/products/"+id(p)),200).json;
        assertEquals(3,after.path("stock").asInt());assertEquals("frozen",after.path("status").asText());
    }

    @Test @DisplayName("R3-P041 | 未登录不能访问管理或写商品类别")
    void p041() throws Exception {

        var p=publish("权限保护",3);var guest=anonymous();var page=expect(guest.get("/admin.html"),302);
        assertTrue(page.headers.values().stream().flatMap(List::stream).anyMatch(s->s.equals("/login.html")));
        expect(guest.get("/api/admin/products"),401);expect(guest.get("/api/admin/categories"),401);
        expect(guest.post("/api/admin/categories",obj("name","越权")),401);
        expect(raw(guest,Map.of(),List.of()),401);
        expect(guest.patch("/api/admin/products/"+id(p),obj("stock",9,"categoryId",leafId,"version",p.path("version").asLong())),401);
        assertEquals(1,countProducts());
    }

    @Test @DisplayName("R3-P042 | 上传文件名目录跳转被安全重命名")
    void p042() throws Exception {

        var p=expect(raw(seller,Map.of(),List.of(new Upload("images","../outside.png","image/png",png()))),201).json;
        String path=p.path("image").asText();assertTrue(path.startsWith("/uploads/"));assertFalse(path.contains(".."));
        assertArrayEquals(png(),expect(anonymous().get(path),200).bytes);
    }

    @Test @DisplayName("R3-P043 | 编辑类别及版本小数类型拒绝")
    void p043() throws Exception {

        var p=publish("整数参数",3);String url="/api/admin/products/"+id(p);long v=p.path("version").asLong();
        expect(seller.patch(url,obj("stock",3,"categoryId",1.5,"version",v)),400);
        expect(seller.patch(url,obj("stock",3,"categoryId",leafId,"version",0.5)),400);
        expect(seller.patch(url,obj("stock",3,"categoryId",leafId,"version",Long.toString(v))),400);
        var now=expect(seller.get(url),200).json;assertEquals(v,now.path("version").asLong());
        assertEquals(leafId,now.path("category").path("id").asLong());
    }

    private Map<String,String> fields() {
        var f=new LinkedHashMap<String,String>();
        f.put("name","基础商品");f.put("price","12.50");f.put("stock","3");
        f.put("categoryId",Long.toString(leafId));f.put("description","");return f;
    }
    private Response raw(Client c,Map<String,String> changes,List<Upload> files) throws Exception {
        var f=fields();f.putAll(changes);return multipart(c,"/api/products",f,files);
    }
    private JsonNode search(String q) throws Exception {
        return expect(anonymous().get("/api/products?q="+enc(q)),200).json;
    }
    private String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
    private long category(String name,Long parent) throws Exception {
        return expect(seller.post("/api/admin/categories",obj("name",name,"parentId",parent)),201).json.path("id").asLong();
    }
    private Response edit(JsonNode p,Object stock,long category,long version) throws Exception {
        return seller.patch("/api/admin/products/"+id(p),obj("stock",stock,"categoryId",category,"version",version));
    }
    private long countProducts() { return jdbc.queryForObject("SELECT COUNT(*) FROM product",Long.class); }
    private void assertNoUploaded() throws Exception {try(var paths=Files.list(UPLOAD_DIR)){assertEquals(0,paths.count());}}
    private byte[] colouredPng(int i) throws Exception {
        var image=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);
        for(int x=0;x<3;x++)for(int y=0;y<3;y++)image.setRGB(x,y,(i+1)*17777);
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
}
