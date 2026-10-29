package com.coursewise.shopping;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 页面、卖家认证与旧版 API 边界。新版商品路由见 CatalogController。 */
@RestController
public class ShoppingController {
    private final ShoppingService service;
    private final Path uploads;
    public ShoppingController(ShoppingService service, @Value("${shopping.upload-dir}") String directory) {
        this.service = service; uploads = Path.of(directory).toAbsolutePath().normalize();
    }
    @GetMapping("/") public ResponseEntity<byte[]> index() { return page("index.html"); }
    @GetMapping("/login.html") public ResponseEntity<byte[]> loginPage() { return page("login.html"); }
    @GetMapping("/admin.html")
    public ResponseEntity<byte[]> admin(@CookieValue(name="seller_session", required=false) String session) {
        try { service.requireSession(session); return page("admin.html"); }
        catch (ResponseStatusException e) { return ResponseEntity.status(302).header("Location", "/login.html").build(); }
    }
    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Boolean>> login(@RequestBody Map<String,String> body) {
        String token = UUID.randomUUID().toString();
        if (!service.login(body.get("username"), body.get("password"), token))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(token, false).toString()).body(Map.of("ok",true));
    }
    @GetMapping("/api/auth/session")
    public Map<String,Boolean> session(@CookieValue(name="seller_session", required=false) String session) {
        service.requireSession(session); return Map.of("ok",true);
    }
    @PostMapping("/api/auth/logout")
    public ResponseEntity<Map<String,Boolean>> logout(@CookieValue(name="seller_session", required=false) String session) {
        service.logout(session);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", true).toString()).body(Map.of("ok",true));
    }
    @PostMapping("/api/auth/password")
    public Map<String,Boolean> password(@CookieValue(name="seller_session", required=false) String session,
                                        @RequestBody Map<String,String> body) {
        service.changePassword(session,body.get("oldPassword"),body.get("newPassword")); return Map.of("ok",true);
    }
    @GetMapping("/uploads/{filename:.+}")
    public ResponseEntity<byte[]> image(@PathVariable String filename) {
        Path target = uploads.resolve(filename).normalize();
        if (!target.startsWith(uploads) || !filename.matches("[A-Za-z0-9.-]+\\.(?:png|jpe?g)"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在");
        try {
            return ResponseEntity.ok().header("X-Content-Type-Options", "nosniff")
                    .contentType(filename.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                    .body(Files.readAllBytes(target));
        } catch (java.io.IOException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在"); }
    }
    // 旧匿名意向、口令码与单商品交易接口不兼容 V2.0，禁止借旧路由操作新商品。
    @RequestMapping({"/api/product", "/api/intents", "/api/intents/**", "/api/admin/product",
            "/api/admin/intents", "/api/admin/freeze", "/api/admin/unfreeze", "/api/admin/trade/**", "/api/admin/history"})
    public void legacy() { throw new ResponseStatusException(HttpStatus.GONE, "旧版接口已停用，请使用 V2.0 商品接口"); }

    private ResponseCookie cookie(String value, boolean expired) {
        var builder = ResponseCookie.from("seller_session",value).httpOnly(true).sameSite("Strict").path("/");
        if (expired) builder.maxAge(0); return builder.build();
    }
    private ResponseEntity<byte[]> page(String name) {
        byte[] bytes;
        try { bytes = Files.readAllBytes(Path.of("public",name)); }
        catch (java.io.IOException e) {
            try (var stream = getClass().getResourceAsStream("/static/"+name)) {
                if (stream == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                bytes = stream.readAllBytes();
            } catch (java.io.IOException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        }
        return ResponseEntity.ok().contentType(new MediaType("text","html",StandardCharsets.UTF_8))
                .header("Cache-Control","no-store").body(bytes);
    }
}
