package com.coursewise.shopping;

import com.coursewise.shopping.domain.*;
import com.coursewise.shopping.repository.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogIntegrationTest {
    static final Path TEMP;
    static { try { TEMP=Files.createTempDirectory("catalog-integration-"); } catch(Exception e){throw new RuntimeException(e);} }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:sqlite:"+TEMP.resolve("test.db"));
        r.add("shopping.upload-dir",()->TEMP.resolve("uploads").toString());
        r.add("shopping.legacy-data-file",()->TEMP.resolve("absent.json").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    Cookie cookie;
    long parent,leaf,otherLeaf;
    @BeforeAll void login() throws Exception {
        var response=mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"admin1234\"}")).andExpect(status().isOk()).andReturn().getResponse();
        cookie=response.getCookie("seller_session"); assertNotNull(cookie);
    }
    @BeforeEach void setup() throws Exception {
        products.deleteAll();categories.deleteAll();
        parent=category("生活",null); leaf=category("文具",parent);otherLeaf=category("家居",parent);
    }
    long category(String name,Long parentId) throws Exception {
        var body=mapper.createObjectNode().put("name",name);if(parentId==null)body.putNull("parentId");else body.put("parentId",parentId);
        return read(mvc.perform(post("/api/admin/categories").cookie(cookie).contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }
    JsonNode publish(String name,String description,List<MockMultipartFile> files) throws Exception {
        var request=publishRequest(name,"19.90");
        request.param("description",description);
        files.forEach(request::file);
        return read(mvc.perform(request).andExpect(status().isCreated()).andReturn());
    }
    JsonNode publish() throws Exception { return publish("手工杯","<p>Classic 陶瓷</p>",List.of()); }
    JsonNode read(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsByteArray()); }
    String editBody(JsonNode p,int stock,long category) {return "{\"stock\":"+stock+",\"categoryId\":"+category+",\"version\":"+p.path("version").asLong()+"}";}
    MockMultipartFile image(String name,String type,String format) throws Exception {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(5,5,BufferedImage.TYPE_INT_RGB),format,bytes);
        return new MockMultipartFile("images",name,type,bytes.toByteArray());
    }
    org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder publishRequest(String name,String price) {
        var request=multipart("/api/products");
        request.cookie(cookie).param("name",name).param("price",price).param("stock","3").param("categoryId",Long.toString(leaf));
        return request;
    }
    @Test void anonymousAdminAndWritesAreBlocked() throws Exception {
        mvc.perform(get("/admin.html")).andExpect(status().isFound()).andExpect(header().string("Location","/login.html"));
        mvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/categories").contentType("application/json").content("{\"name\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/products")).andExpect(status().isUnauthorized());
    }
    @Test void multipleProductsOptionalFieldsAndCardFields() throws Exception {
        publish("第一件","",List.of());publish("第二件","",List.of());
        var items=read(mvc.perform(get("/api/products")).andExpect(status().isOk()).andReturn()).path("items");
        assertEquals(2,items.size());assertEquals("第二件",items.get(0).path("name").asText());
        assertEquals(Set.of("id","name","priceCents","image","category"),mapper.convertValue(items.get(0),Map.class).keySet());
        assertEquals("/placeholder.svg",items.get(0).path("image").asText());
    }
    @Test void plainTextSearchCaseInsensitivityAndNoCategoryMatching() throws Exception {
        publish();
        for(String q:List.of("CLASSIC","陶瓷","手工","")) mvc.perform(get("/api/products").param("q",q)).andExpect(jsonPath("$.total").value(1));
        for(String q:List.of("文具","<p>","%","_")) mvc.perform(get("/api/products").param("q",q)).andExpect(jsonPath("$.total").value(0));
    }
    @Test void categoryFilterPaginationAndStableSort() throws Exception {
        for(int i=0;i<14;i++)publish("商品"+i,"",List.of());
        mvc.perform(get("/api/products").param("page","2")).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/products").param("categoryId",Long.toString(otherLeaf))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/products").param("categoryId",Long.toString(parent))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("page","0")).andExpect(status().isBadRequest());
    }
    @Test void frozenDirectLinkAndAllOffShelfStates() throws Exception {
        var p=publish();long id=p.path("id").asLong();
        var entity=products.findById(id).orElseThrow();entity.setStatus(ProductStatus.frozen);products.saveAndFlush(entity);
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/products/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
        for(var state:List.of(ProductStatus.sold,ProductStatus.depleted,ProductStatus.withdrawn)) {
            entity=products.findById(id).orElseThrow();entity.setStatus(state);products.saveAndFlush(entity);
            mvc.perform(get("/api/products/"+id)).andExpect(status().isNotFound());
        }
    }
    @Test void updateStockCategoryAndZeroDepletion() throws Exception {
        var p=publish();long id=p.path("id").asLong();
        var updated=read(mvc.perform(patch("/api/admin/products/"+id).cookie(cookie).contentType("application/json")
                .content(editBody(p,5,otherLeaf))).andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(5)).andReturn());
        mvc.perform(get("/api/products").param("categoryId",Long.toString(otherLeaf))).andExpect(jsonPath("$.total").value(1));
        var zero=read(mvc.perform(patch("/api/admin/products/"+id).cookie(cookie).contentType("application/json")
                .content(editBody(updated,0,otherLeaf))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("depleted")).andReturn());
        mvc.perform(get("/api/products/"+id)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/admin/products/"+id).cookie(cookie).contentType("application/json").content(editBody(zero,3,otherLeaf)))
                .andExpect(jsonPath("$.status").value("depleted"));
    }
    @Test void immutableFieldsAndStaleUpdatesRejected() throws Exception {
        var p=publish();String url="/api/admin/products/"+p.path("id").asLong();
        mvc.perform(patch(url).cookie(cookie).contentType("application/json").content("{\"name\":\"换名字\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch(url).cookie(cookie).contentType("application/json").content(editBody(p,4,leaf))).andExpect(status().isOk());
        mvc.perform(patch(url).cookie(cookie).contentType("application/json").content(editBody(p,5,leaf))).andExpect(status().isConflict());
        mvc.perform(delete(url).cookie(cookie)).andExpect(status().isMethodNotAllowed());
    }
    @Test void soldAndWithdrawnCannotReappearByEditingStock() throws Exception {
        for(var state:List.of(ProductStatus.sold,ProductStatus.withdrawn)){
            var p=publish();long id=p.path("id").asLong();var entity=products.findById(id).orElseThrow();entity.setStatus(state);products.saveAndFlush(entity);
            var current=read(mvc.perform(get("/api/admin/products/"+id).cookie(cookie)).andReturn());
            mvc.perform(patch("/api/admin/products/"+id).cookie(cookie).contentType("application/json").content(editBody(current,10,leaf)))
                    .andExpect(jsonPath("$.status").value(state.code()));
            mvc.perform(get("/api/products/"+id)).andExpect(status().isNotFound());
        }
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","10000000","0.001","1e2","NaN","","9,999.99"})
    void invalidPricesRejected(String price) throws Exception {
        mvc.perform(multipart("/api/products").cookie(cookie).param("name","杯").param("price",price).param("stock","1").param("categoryId",Long.toString(leaf)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.price").exists());assertEquals(0,products.count());
    }
    @Test void priceMaximumAndStockValidation() throws Exception {
        mvc.perform(multipart("/api/products").cookie(cookie).param("name","杯").param("price","9999999.99").param("stock","1").param("categoryId",Long.toString(leaf)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.priceCents").value(999999999));
        for(String stock:List.of("0","-1","1.5","2147483648")) mvc.perform(multipart("/api/products").cookie(cookie)
                .param("name","杯").param("price","1").param("stock",stock).param("categoryId",Long.toString(leaf))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/products").cookie(cookie).param("name","杯").param("price","1").param("stock","1").param("categoryId",Long.toString(parent))).andExpect(status().isBadRequest());
    }
    @Test void categoryDepthRenameDeleteAndFixedParent() throws Exception {
        mvc.perform(post("/api/admin/categories").cookie(cookie).contentType("application/json").content("{\"name\":\"三级\",\"parentId\":"+leaf+"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/admin/categories/"+leaf).cookie(cookie).contentType("application/json").content("{\"name\":\"纸品\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/admin/categories/"+leaf).cookie(cookie).contentType("application/json").content("{\"name\":\"纸品\",\"parentId\":null}"))
                .andExpect(status().isBadRequest());
        publish();mvc.perform(delete("/api/admin/categories/"+leaf).cookie(cookie)).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/categories/"+parent).cookie(cookie)).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/categories/"+otherLeaf).cookie(cookie)).andExpect(status().isOk());
    }
    @Test void multiImageOrderingAndInlineImageSanitizing() throws Exception {
        var first=image("a.png","image/png","png");var second=image("b.jpg","image/jpeg","jpg");
        var inline=new MockMultipartFile("image","inline.png","image/png",first.getBytes());
        String url=read(mvc.perform(multipart("/api/admin/description-images").file(inline).cookie(cookie)).andExpect(status().isCreated()).andReturn()).path("url").asText();
        var p=publish("图片杯","<p onclick='x()'>说明<strong>加粗</strong></p><img src='"+url+"' onerror='x()'><script>alert(1)</script><input><table><tr><td>文本</td></tr></table><a href='https://example.com'>链接</a><img src='https://example.com/x.png'>",List.of(first,second));
        assertEquals(2,p.path("images").size());assertEquals(p.path("image"),p.path("images").get(0));
        String html=p.path("description").asText();assertTrue(html.contains("<strong>加粗</strong>"));assertTrue(html.contains(url));
        for(String forbidden:List.of("onclick","onerror","<script","<input","<table","href=","example.com")) assertFalse(html.contains(forbidden),forbidden);
        mvc.perform(get(p.path("image").asText())).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
    }
    @Test void invalidImageBatchLeavesNoProductsOrFiles() throws Exception {
        long before;try(var files=Files.list(TEMP.resolve("uploads"))){before=files.count();}
        mvc.perform(publishRequest("杯","1")
                .file(image("ok.png","image/png","png")).file(image("fake.png","image/png","gif"))).andExpect(status().isBadRequest());
        var request=publishRequest("杯","1");
        for(int i=0;i<10;i++)request.file(image("x.png","image/png","png"));
        mvc.perform(request).andExpect(status().isBadRequest());
        mvc.perform(publishRequest("杯","1")
                .file(new MockMultipartFile("images","huge.png","image/png",new byte[5*1024*1024+1]))).andExpect(status().isBadRequest());
        assertEquals(0,products.count());try(var files=Files.list(TEMP.resolve("uploads"))){assertEquals(before,files.count());}
    }
    @Test void oldRoutesDisabledAndLegacyProductsExcluded() throws Exception {
        for(String path:List.of("/api/product","/api/intents/query","/api/admin/freeze","/api/admin/trade/success"))
            mvc.perform(post(path).cookie(cookie)).andExpect(status().isGone());
        ProductEntity p=new ProductEntity();p.setName("旧商品");p.setDescription("旧描述");p.setImage("");p.setPriceCents(100);p.setStatus(ProductStatus.active);p.setCreatedAt("2026-01-01");products.saveAndFlush(p);
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.total").value(0));
    }
    @Test void nineImagesAcceptedAndDeletionAfterReassignment() throws Exception {
        var images=new ArrayList<MockMultipartFile>();
        for(int i=0;i<9;i++)images.add(image("p"+i+".png","image/png","png"));
        var p=publish("九图商品","",images);assertEquals(9,p.path("images").size());
        mvc.perform(patch("/api/admin/products/"+p.path("id").asLong()).cookie(cookie).contentType("application/json")
                .content(editBody(p,3,otherLeaf))).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/categories/"+leaf).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/products/"+p.path("id").asLong())).andExpect(status().isOk())
                .andExpect(jsonPath("$.category.id").value(otherLeaf));
    }
    @Test void editedFrozenStockCannotCorruptBoundTrade() throws Exception {
        var p=publish();long id=p.path("id").asLong();var entity=products.findById(id).orElseThrow();
        entity.setStatus(ProductStatus.frozen);entity.setTradingIntentId(999L);products.saveAndFlush(entity);
        var current=read(mvc.perform(get("/api/admin/products/"+id).cookie(cookie)).andReturn());
        mvc.perform(patch("/api/admin/products/"+id).cookie(cookie).contentType("application/json").content(editBody(current,0,leaf)))
                .andExpect(status().isConflict());
        assertEquals(3,products.findById(id).orElseThrow().getStock());
    }
}
