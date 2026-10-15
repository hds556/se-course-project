package com.coursewise.shopping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.nio.file.Path;
import java.util.UUID;

@RestController
public class ShoppingController {
    private static final String SESSION_COOKIE = "seller_session";
    private final ShoppingService service;
    private final ObjectMapper mapper;
    private final Path uploadRoot;

    public ShoppingController(ShoppingService service, ObjectMapper mapper,
                              @Value("${shopping.upload-dir}") String uploadDir) {
        this.service = service;
        this.mapper = mapper;
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(readPublicFile("index.html"));
    }

    @GetMapping(value = "/admin.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> adminPage() {
        return fileResponse("admin.html");
    }

    @GetMapping(value = "/style.css", produces = "text/css")
    public ResponseEntity<byte[]> stylesheet() {
        return fileResponse("style.css");
    }

    @GetMapping(value = "/app.js", produces = "application/javascript")
    public ResponseEntity<byte[]> appScript() {
        return fileResponse("app.js");
    }

    @GetMapping(value = "/admin.js", produces = "application/javascript")
    public ResponseEntity<byte[]> adminScript() {
        return fileResponse("admin.js");
    }

    @GetMapping(value = "/uploads/{filename:.+}", produces = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> upload(@PathVariable String filename) {
        return fileResponse("../uploads/" + filename);
    }

    @GetMapping("/api/product")
    public JsonNode product() {
        return service.publicProduct();
    }

    @PostMapping("/api/intents")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode createIntent(@RequestBody Map<String, String> body) {
        return service.createIntent(body.get("name"), body.get("phone"));
    }

    @PostMapping("/api/intents/query")
    public JsonNode queryIntent(@RequestBody Map<String, String> body) {
        return service.queryIntent(body.get("passcode"));
    }

    @PutMapping("/api/intents")
    public JsonNode updateIntent(@RequestBody Map<String, String> body) {
        service.updateIntent(body.get("passcode"), body.get("name"), body.get("phone"));
        return mapper.createObjectNode().put("ok", true);
    }

    @PostMapping("/api/intents/cancel")
    public JsonNode cancelIntent(@RequestBody Map<String, String> body) {
        service.cancelIntent(body.get("passcode"));
        return mapper.createObjectNode().put("ok", true);
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<JsonNode> login(@RequestBody Map<String, String> body) {
        String token = UUID.randomUUID().toString().replace("-", "");
        if (!service.login(body.get("username"), body.get("password"), token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true).sameSite("Strict").path("/").build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(mapper.createObjectNode().put("ok", true));
    }

    @PostMapping("/api/auth/password")
    public JsonNode changePassword(@CookieValue(name = SESSION_COOKIE, required = false) String session,
                                   @RequestBody Map<String, String> body) {
        service.changePassword(session, body.get("oldPassword"), body.get("newPassword"));
        return mapper.createObjectNode().put("ok", true);
    }

    @PostMapping(value = "/api/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode createProduct(@CookieValue(name = SESSION_COOKIE, required = false) String session,
                                  @RequestParam String name,
                                  @RequestParam String description,
                                  @RequestParam String price,
                                  @RequestPart("image") MultipartFile image) {
        return service.createProduct(session, name, description, price, image);
    }

    @GetMapping("/api/admin/product")
    public JsonNode adminProduct(@CookieValue(name = SESSION_COOKIE, required = false) String session) {
        return service.adminProduct(session);
    }

    @GetMapping("/api/admin/intents")
    public JsonNode adminIntents(@CookieValue(name = SESSION_COOKIE, required = false) String session) {
        return service.adminIntents(session);
    }

    @PostMapping("/api/admin/freeze")
    public JsonNode freeze(@CookieValue(name = SESSION_COOKIE, required = false) String session) {
        service.freeze(session);
        return mapper.createObjectNode().put("ok", true);
    }

    @PostMapping("/api/admin/unfreeze")
    public JsonNode unfreeze(@CookieValue(name = SESSION_COOKIE, required = false) String session) {
        service.unfreeze(session);
        return mapper.createObjectNode().put("ok", true);
    }

    @PostMapping("/api/admin/trade/start")
    public JsonNode startTrade(@CookieValue(name = SESSION_COOKIE, required = false) String session) {
        return service.startTrade(session);
    }

    @PostMapping("/api/admin/trade/success")
    public JsonNode tradeSuccess(@CookieValue(name = SESSION_COOKIE, required = false) String session) {
        service.tradeSuccess(session);
        return mapper.createObjectNode().put("ok", true);
    }

    @PostMapping("/api/admin/trade/fail")
    public JsonNode tradeFail(@CookieValue(name = SESSION_COOKIE, required = false) String session,
                              @RequestBody Map<String, String> body) {
        return service.tradeFail(session, body.get("disposition"));
    }

    @PostMapping("/api/admin/trade/advance")
    public JsonNode advance(@CookieValue(name = SESSION_COOKIE, required = false) String session) {
        return service.advanceCancelled(session);
    }

    @GetMapping("/api/admin/history")
    public JsonNode history(@CookieValue(name = SESSION_COOKIE, required = false) String session) {
        return service.history(session);
    }

    private String readPublicFile(String filename) {
        byte[] content = readPublicBytes(filename);
        if (content == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
        return new String(content, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 读取 public 静态文件：优先文件系统（开发模式），找不到时回退到 jar 内 classpath:/static/ */
    private byte[] readPublicBytes(String filename) {
        try {
            return java.nio.file.Files.readAllBytes(java.nio.file.Path.of("public", filename).toAbsolutePath().normalize());
        } catch (java.io.IOException ignored) {
            // 文件系统不存在，尝试 classpath（打包部署时前端已打进 jar）
        }
        try (java.io.InputStream in = getClass().getResourceAsStream("/static/" + filename)) {
            return in == null ? null : in.readAllBytes();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private ResponseEntity<byte[]> fileResponse(String relativePath) {
        try {
            Path publicRoot = Path.of("public").toAbsolutePath().normalize();
            boolean upload = relativePath.startsWith("../uploads/");
            Path root = upload ? uploadRoot : publicRoot;
            Path requested = (upload
                    ? root.resolve(relativePath.substring("../uploads/".length()))
                    : root.resolve(relativePath)).toAbsolutePath().normalize();
            if (!requested.startsWith(root)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            byte[] content;
            try {
                content = java.nio.file.Files.readAllBytes(requested);
            } catch (java.io.IOException e) {
                if (upload) throw e; // 上传图片只存文件系统，无 classpath 回退
                content = readPublicBytes(relativePath); // 前端文件回退到 jar 内
                if (content == null) throw e;
            }
            MediaType type = MediaTypeFactory.getMediaType(requested.getFileName().toString())
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().contentType(type).body(content);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在", e);
        }
    }

    private static final class MediaTypeFactory {
        private static java.util.Optional<MediaType> getMediaType(String name) {
            if (name.endsWith(".html")) return java.util.Optional.of(MediaType.TEXT_HTML);
            if (name.endsWith(".css")) return java.util.Optional.of(MediaType.valueOf("text/css"));
            if (name.endsWith(".js")) return java.util.Optional.of(MediaType.valueOf("application/javascript"));
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return java.util.Optional.of(MediaType.IMAGE_JPEG);
            if (name.endsWith(".png")) return java.util.Optional.of(MediaType.IMAGE_PNG);
            return java.util.Optional.empty();
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode().value())
                .body(Map.of("error", exception.getReason() == null ? "操作失败" : exception.getReason()));
    }
}
