package com.coursewise.shopping.catalog;

import com.coursewise.shopping.ShoppingService;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
public class CatalogController {
    private final CatalogService catalog;
    private final ShoppingService auth;
    public CatalogController(CatalogService catalog, ShoppingService auth) { this.catalog=catalog; this.auth=auth; }

    @GetMapping("/api/products")
    public ObjectNode products(@RequestParam(defaultValue="") String q, @RequestParam(required=false) Long categoryId,
                               @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size) {
        return catalog.list(q, categoryId, page, size, false, null);
    }
    @GetMapping("/api/products/{id}") public ObjectNode detail(@PathVariable long id) { return catalog.detail(id,false,null); }
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
}
