package com.coursewise.shopping.catalog;

import com.coursewise.shopping.ShoppingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
public class CatalogController {
    private static final long MAX_EXACT_BROWSER_STOCK = 9_007_199_254_740_991L;
    private final CatalogService catalog;
    private final ShoppingService auth;
    private final com.coursewise.shopping.order.OrderService orders;
    public CatalogController(CatalogService catalog, ShoppingService auth, com.coursewise.shopping.order.OrderService orders) { this.catalog=catalog; this.auth=auth; this.orders=orders; }

    @GetMapping("/api/products")
    public ObjectNode products(@RequestParam(defaultValue="") String q, @RequestParam(required=false) Long categoryId,
                               @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size) {
        return catalog.list(q, categoryId, page, size, false, null);
    }
    @GetMapping("/api/products/{id}") public ObjectNode detail(@PathVariable long id) { return catalog.detail(id,false,null); }
    @GetMapping("/api/products/{id}/favorite-detail")
    public ObjectNode favoriteDetail(@PathVariable long id,
            @CookieValue(name="customer_session", required=false) String session) {
        if (!orders.favoriteState(session, id).path("favorited").asBoolean())
            throw new CatalogException(HttpStatus.NOT_FOUND, "product", "请从本人的收藏查看商品");
        return catalog.favoriteDetail(id);
    }
    @PutMapping(value="/api/admin/products/{id}/content", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObjectNode editContent(@PathVariable long id,
            @CookieValue(name="seller_session", required=false) String session,
            @RequestParam(required=false) Long version, @RequestParam(required=false) String name,
            @RequestParam(required=false) String price, @RequestParam(required=false) Long categoryId,
            @RequestParam(defaultValue="") String description,
            @RequestParam(required=false) List<String> retainedImages,
            @RequestPart(required=false) List<MultipartFile> images) {
        return catalog.editContent(session,id,version,name,price,categoryId,description,retainedImages,images);
    }
    @GetMapping("/api/categories") public ArrayNode categories() { return catalog.categoryTree(); }
    @GetMapping("/api/admin/products")
    public ObjectNode adminProducts(@CookieValue(name="seller_session",required=false) String session,
                                   @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size) {
        return catalog.list("",null,page,size,true,session);
    }
    @GetMapping("/api/admin/products/{id}")
    public ObjectNode adminDetail(@CookieValue(name="seller_session",required=false) String session, @PathVariable long id) {
        return catalog.detail(id,true,session);
    }
    @GetMapping("/api/admin/products/offline")
    public ArrayNode offlineProducts(@CookieValue(name="seller_session", required=false) String session) {
        return catalog.offlineProducts(session);
    }
    @PatchMapping("/api/admin/products/{id}/stock")
    public ObjectNode adjustStock(@PathVariable long id,
            @CookieValue(name="seller_session", required=false) String session,
            @RequestBody(required=false) JsonNode body) {
        auth.requireSession(session);
        if (body == null || !body.isObject() || !body.has("stock")
                || body.size() != (body.has("version") ? 2 : 1))
            throw new CatalogException(HttpStatus.BAD_REQUEST, "stock", "只接受库存与可选的商品版本");
        JsonNode stock = body.get("stock");
        if (!stock.isIntegralNumber() || !stock.canConvertToLong()
                || stock.longValue() < 0 || stock.longValue() > MAX_EXACT_BROWSER_STOCK)
            throw new CatalogException(HttpStatus.BAD_REQUEST, "stock", "库存须为浏览器可精确表示的非负整数");
        return catalog.adjustStock(session, id, stock.longValue(), optionalVersion(body));
    }
    @PostMapping("/api/admin/products/{id}/withdraw")
    public ObjectNode withdraw(@PathVariable long id,
            @CookieValue(name="seller_session", required=false) String session,
            @RequestBody(required=false) JsonNode body) {
        auth.requireSession(session);
        if (body != null && (!body.isObject() || body.size() != (body.has("version") ? 1 : 0)))
            throw new CatalogException(HttpStatus.BAD_REQUEST, "version", "下架请求只接受可选的商品版本");
        return catalog.withdraw(session, id, optionalVersion(body));
    }
    @PostMapping(value="/api/products", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode publish(@CookieValue(name="seller_session",required=false) String session,
                              @RequestParam(required=false) String name, @RequestParam(required=false) String price,
                              @RequestParam(required=false) Integer stock, @RequestParam(required=false) Long categoryId,
                              @RequestParam(defaultValue="") String description,
                              @RequestPart(required=false) List<MultipartFile> images) {
        return catalog.publish(session,name,price,stock,categoryId,description,images);
    }
    @PatchMapping("/api/admin/products/{id}")
    public ObjectNode edit(@CookieValue(name="seller_session",required=false) String session, @PathVariable long id,
                           @RequestBody Map<String,Object> body) {
        auth.requireSession(session);
        if (!Set.of("stock","categoryId","version").equals(body.keySet()))
            throw new CatalogException(HttpStatus.BAD_REQUEST,"product","仅允许编辑库存与类别，须携带 version");
        try {
            return catalog.edit(session,id,new CatalogService.EditInput(integer(body.get("stock")),
                    whole(body.get("categoryId")),whole(body.get("version"))));
        } catch (ArithmeticException | NumberFormatException e) {
            throw new CatalogException(HttpStatus.BAD_REQUEST,"stock","库存、类别和版本必须为有效整数");
        }
    }
    @GetMapping("/api/admin/categories")
    public ArrayNode adminCategories(@CookieValue(name="seller_session",required=false) String session) {
        auth.requireSession(session); return catalog.categoryTree();
    }
    @PostMapping("/api/admin/categories") @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode addCategory(@CookieValue(name="seller_session",required=false) String session,
                                  @RequestBody CatalogService.CategoryInput body) { return catalog.addCategory(session,body); }
    @PatchMapping("/api/admin/categories/{id}")
    public ObjectNode rename(@CookieValue(name="seller_session",required=false) String session,
                             @PathVariable long id,@RequestBody Map<String,Object> body) { return catalog.renameCategory(session,id,body); }
    @DeleteMapping("/api/admin/categories/{id}")
    public Map<String,Boolean> delete(@CookieValue(name="seller_session",required=false) String session,@PathVariable long id) {
        catalog.deleteCategory(session,id); return Map.of("ok",true);
    }
    @PostMapping(value="/api/admin/description-images", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode descriptionImage(@CookieValue(name="seller_session",required=false) String session,
                                       @RequestPart MultipartFile image) { return catalog.uploadDescriptionImage(session,image); }

    private Long whole(Object value) {
        if (!(value instanceof Number)) throw new NumberFormatException();
        return new java.math.BigDecimal(value.toString()).longValueExact();
    }
    private Integer integer(Object value) { return Math.toIntExact(whole(value)); }

    private Long optionalVersion(JsonNode body) {
        if (body == null || !body.has("version")) return null;
        JsonNode version = body.get("version");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0)
            throw new CatalogException(HttpStatus.BAD_REQUEST, "version", "商品版本必须是非负整数");
        return version.longValue();
    }
}
