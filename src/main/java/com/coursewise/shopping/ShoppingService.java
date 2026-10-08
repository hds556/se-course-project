package com.coursewise.shopping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class ShoppingService {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long MAX_PRICE_CENTS = (1L << 53) - 1;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Pattern PRICE_PATTERN = Pattern.compile("^(?:0|[1-9]\\d*)(?:\\.\\d{1,2})?$");
    private static final Pattern PASSWORD_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern PASSWORD_DIGIT = Pattern.compile("\\d");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final ObjectMapper mapper;
    private final Path dataFile;
    private final Path uploadDir;
    private final SCryptPasswordEncoder passwordEncoder = SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private ObjectNode db;

    public ShoppingService(ObjectMapper mapper,
                            @Value("${shopping.data-file}") String dataFile,
                            @Value("${shopping.upload-dir}") String uploadDir) {
        this.mapper = mapper;
        this.dataFile = Path.of(dataFile).toAbsolutePath().normalize();
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
            this.db = loadDatabase();
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化数据文件", e);
        }
    }

    public synchronized ObjectNode publicProduct() {
        ObjectNode product = currentProduct();
        ObjectNode result = mapper.createObjectNode();
        if (product == null) {
            result.putNull("product");
            return result;
        }
        ObjectNode view = mapper.createObjectNode();
        copy(view, product, "id", "name", "description", "image", "priceCents", "status");
        view.put("buyable", isBuyable(product));
        result.set("product", view);
        return result;
    }

    public synchronized ObjectNode createIntent(String name, String phone) {
        ObjectNode product = requireBuyableProduct();
        requirePerson(name, phone);
        ObjectNode intent = mapper.createObjectNode();
        intent.put("id", nextId("nextIntentId"));
        intent.put("productId", product.path("id").asInt());
        intent.put("name", name.trim());
        intent.put("phone", phone.trim());
        intent.put("passcode", newPasscode());
        intent.put("submittedAt", now());
        intent.put("stage", "queued");
        intent.putNull("outcome");
        intents().add(intent);
        save();
        return mapper.createObjectNode().put("passcode", intent.path("passcode").asText());
    }

    public synchronized ObjectNode queryIntent(String passcode) {
        ObjectNode[] result = activeIntent(passcode);
        ObjectNode response = mapper.createObjectNode();
        response.put("stage", result[0].path("stage").asText());
        if ("queued".equals(result[0].path("stage").asText())) {
            response.put("position", queuePosition(result[0]));
        } else {
            response.putNull("position");
        }
        return response;
    }

    public synchronized void updateIntent(String passcode, String name, String phone) {
        ObjectNode[] result = activeIntent(passcode);
        requirePerson(name, phone);
        result[0].put("name", name.trim());
        result[0].put("phone", phone.trim());
        save();
    }

    public synchronized void cancelIntent(String passcode) {
        ObjectNode[] result = activeIntent(passcode);
        result[0].put("stage", "closed");
        result[0].put("outcome", "cancelled");
        save();
    }

    public synchronized boolean login(String username, String password, String session) {
        JsonNode seller = db.path("seller");
        if (!Objects.equals(username, seller.path("username").asText())
                || !matchesPassword(password, seller.path("passwordHash").asText())) {
            return false;
        }
        sessions.add(session);
        return true;
    }

    public synchronized void changePassword(String session, String oldPassword, String newPassword) {
        requireSession(session);
        if (!matchesPassword(oldPassword, db.path("seller").path("passwordHash").asText())) {
            throw error(HttpStatus.BAD_REQUEST, "原密码错误");
        }
        if (newPassword == null || newPassword.length() < 8
                || !PASSWORD_LETTER.matcher(newPassword).find()
                || !PASSWORD_DIGIT.matcher(newPassword).find()) {
            throw error(HttpStatus.BAD_REQUEST, "新密码至少 8 位且必须同时包含字母和数字");
        }
        ((ObjectNode) db.path("seller")).put("passwordHash", passwordEncoder.encode(newPassword));
        ((ObjectNode) db.path("seller")).put("passwordChangedAt", now());
        save();
    }

    public synchronized ObjectNode createProduct(String session, String name, String description,
                                                  String price, MultipartFile image) {
        requireSession(session);
        if (currentProduct() != null) throw error(HttpStatus.CONFLICT, "当前已有未售出商品");
        if (image == null || image.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "必须上传 JPG 或 PNG 图片");
        ImageData checked = validateImage(image);
        long cents = parsePrice(price);
        if (name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "商品名称、描述和有效价格均为必填");
        }
        String filename = "p-" + System.currentTimeMillis() + "-" + randomHex(6) + checked.extension();
        Path target = uploadDir.resolve(filename).normalize();
        try {
            if (!target.getParent().equals(uploadDir)) throw new IOException("非法文件路径");
            Files.write(target, checked.content(), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败", e);
        }
        ObjectNode product = mapper.createObjectNode();
        product.put("id", nextId("nextProductId"));
        product.put("name", name.trim());
        product.put("description", description.trim());
        product.put("image", "/uploads/" + filename);
        product.put("priceCents", cents);
        product.put("status", "active");
        product.putNull("tradingIntentId");
        product.put("createdAt", now());
        product.putNull("frozenAt");
        product.putNull("result");
        product.putNull("resultAt");
        products().add(product);
        try {
            save();
        } catch (RuntimeException e) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            throw e;
        }
        return mapper.createObjectNode().put("id", product.path("id").asInt());
    }

    public synchronized ObjectNode adminProduct(String session) {
        requireSession(session);
        ObjectNode result = mapper.createObjectNode();
        ObjectNode product = currentProduct();
        if (product == null) {
            result.putNull("product");
            result.putNull("tradingIntent");
            return result;
        }
        result.set("product", product.deepCopy());
        result.set("tradingIntent", tradingIntent(product));
        return result;
    }

    public synchronized ObjectNode adminIntents(String session) {
        requireSession(session);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode records = mapper.createArrayNode();
        ObjectNode product = currentProduct();
        if (product != null) {
            for (JsonNode item : sortedIntents(product.path("id").asInt())) {
                ObjectNode intent = (ObjectNode) item.deepCopy();
                if ("queued".equals(intent.path("stage").asText())) intent.put("position", queuePosition(intent));
                else intent.putNull("position");
                if (!"closed".equals(intent.path("stage").asText())
                        || (intent.path("id").asInt() == product.path("tradingIntentId").asInt(-1)
                        && "cancelled".equals(intent.path("outcome").asText()))) records.add(intent);
            }
        }
        result.set("intents", records);
        return result;
    }

    public synchronized void freeze(String session) {
        requireSession(session);
        ObjectNode product = currentProduct();
        if (!isBuyable(product)) throw error(HttpStatus.CONFLICT, "当前商品不能手动冻结");
        product.put("status", "frozen");
        product.put("frozenAt", now());
        product.putNull("tradingIntentId");
        save();
    }

    public synchronized void unfreeze(String session) {
        requireSession(session);
        ObjectNode product = currentProduct();
        if (product == null || !"frozen".equals(product.path("status").asText()) || !product.path("tradingIntentId").isNull()) {
            throw error(HttpStatus.CONFLICT, "当前商品不能手动解冻");
        }
        product.put("status", "active");
        save();
    }

    public synchronized ObjectNode startTrade(String session) {
        requireSession(session);
        ObjectNode product = currentProduct();
        if (!isBuyable(product)) throw error(HttpStatus.CONFLICT, "商品当前不能开始交易");
        ObjectNode next = startNextTrade(product);
        if (next == null) throw error(HttpStatus.CONFLICT, "当前没有排队中的购买意向");
        save();
        ObjectNode result = mapper.createObjectNode();
        copy(result, next, "id", "name", "phone");
        result.put("intentId", next.path("id").asInt());
        return result;
    }

    public synchronized void tradeSuccess(String session) {
        requireSession(session);
        ObjectNode product = currentProduct();
        ObjectNode buyer = tradingIntent(product);
        if (buyer == null || !"trading".equals(buyer.path("stage").asText())) {
            throw error(HttpStatus.CONFLICT, "当前没有交易中的意向");
        }
        buyer.put("stage", "closed");
        buyer.put("outcome", "success");
        for (JsonNode item : intents()) {
            if (item.path("productId").asInt() == product.path("id").asInt()
                    && !"closed".equals(item.path("stage").asText())) {
                ((ObjectNode) item).put("stage", "closed");
                ((ObjectNode) item).put("outcome", "unsold");
            }
        }
        product.put("status", "sold");
        product.put("result", "success");
        product.put("resultAt", now());
        product.putNull("tradingIntentId");
        save();
    }

    public synchronized ObjectNode tradeFail(String session, String disposition) {
        requireSession(session);
        if (!"void".equals(disposition) && !"requeue".equals(disposition)) {
            throw error(HttpStatus.BAD_REQUEST, "无效的失败处置方式");
        }
        ObjectNode product = currentProduct();
        ObjectNode buyer = tradingIntent(product);
        if (buyer == null || !"trading".equals(buyer.path("stage").asText())) {
            throw error(HttpStatus.CONFLICT, "当前没有交易中的意向");
        }
        buyer.put("stage", "closed");
        ObjectNode result = mapper.createObjectNode().put("ok", true);
        if ("requeue".equals(disposition)) {
            buyer.put("outcome", "requeued");
            ObjectNode replacement = buyer.deepCopy();
            replacement.put("id", nextId("nextIntentId"));
            replacement.put("passcode", newPasscode());
            replacement.put("submittedAt", now());
            replacement.put("stage", "queued");
            replacement.putNull("outcome");
            intents().add(replacement);
            result.put("newPasscode", replacement.path("passcode").asText());
        } else {
            buyer.put("outcome", "voided");
            result.putNull("newPasscode");
        }
        product.put("resultAt", now());
        product.putNull("tradingIntentId");
        ObjectNode next = startNextTrade(product);
        result.put("nextIntentId", next == null ? null : next.path("id").asInt());
        save();
        return result;
    }

    public synchronized ObjectNode advanceCancelled(String session) {
        requireSession(session);
        ObjectNode product = currentProduct();
        ObjectNode cancelled = tradingIntent(product);
        if (product == null || !"frozen".equals(product.path("status").asText()) || cancelled == null
                || !"closed".equals(cancelled.path("stage").asText())
                || !"cancelled".equals(cancelled.path("outcome").asText())) {
            throw error(HttpStatus.CONFLICT, "当前没有待递补的已撤销交易意向");
        }
        product.putNull("tradingIntentId");
        ObjectNode next = startNextTrade(product);
        save();
        return mapper.createObjectNode().put("ok", true).put("nextIntentId", next == null ? null : next.path("id").asInt());
    }

    public synchronized ObjectNode history(String session) {
        requireSession(session);
        ArrayNode history = mapper.createArrayNode();
        for (JsonNode product : products()) {
            if (!"sold".equals(product.path("status").asText())) continue;
            ObjectNode copy = product.deepCopy();
            ArrayNode records = mapper.createArrayNode();
            for (JsonNode intent : intents()) {
                if (intent.path("productId").asInt() == product.path("id").asInt()) records.add(intent);
            }
            copy.set("intents", records);
            history.add(copy);
        }
        return mapper.createObjectNode().set("products", history);
    }

    public void requireSession(String session) {
        if (session == null || !sessions.contains(session)) throw error(HttpStatus.UNAUTHORIZED, "未登录");
    }

    private ObjectNode[] activeIntent(String passcode) {
        String normalized = passcode == null ? "" : passcode.trim().toUpperCase(Locale.ROOT);
        for (JsonNode item : intents()) {
            if (!normalized.equals(item.path("passcode").asText())) continue;
            ObjectNode product = productById(item.path("productId").asInt());
            if (!"closed".equals(item.path("stage").asText()) && product != null && !"sold".equals(product.path("status").asText())) {
                return new ObjectNode[]{(ObjectNode) item, product};
            }
        }
        throw error(HttpStatus.FORBIDDEN, "口令码无效或已失效");
    }

    private ObjectNode requireBuyableProduct() {
        ObjectNode product = currentProduct();
        if (!isBuyable(product)) throw error(HttpStatus.CONFLICT, "商品当前不接受购买意向");
        return product;
    }

    private ObjectNode currentProduct() {
        for (JsonNode item : products()) if (!"sold".equals(item.path("status").asText())) return (ObjectNode) item;
        return null;
    }

    private ObjectNode productById(int id) {
        for (JsonNode item : products()) if (item.path("id").asInt() == id) return (ObjectNode) item;
        return null;
    }

    private ObjectNode tradingIntent(ObjectNode product) {
        if (product == null || product.path("tradingIntentId").isNull()) return null;
        for (JsonNode item : intents()) if (item.path("id").asInt() == product.path("tradingIntentId").asInt()) return (ObjectNode) item;
        return null;
    }

    private List<JsonNode> sortedIntents(int productId) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode item : intents()) if (item.path("productId").asInt() == productId) result.add(item);
        result.sort(Comparator.comparing((JsonNode item) -> item.path("submittedAt").asText()).thenComparingInt(item -> item.path("id").asInt()));
        return result;
    }

    private ObjectNode startNextTrade(ObjectNode product) {
        for (JsonNode item : sortedIntents(product.path("id").asInt())) {
            if ("queued".equals(item.path("stage").asText())) {
                product.put("status", "frozen");
                product.put("frozenAt", now());
                product.put("tradingIntentId", item.path("id").asInt());
                ((ObjectNode) item).put("stage", "trading");
                return (ObjectNode) item;
            }
        }
        product.put("status", "resumed");
        product.putNull("tradingIntentId");
        return null;
    }

    private int queuePosition(JsonNode intent) {
        int position = 1;
        for (JsonNode item : sortedIntents(intent.path("productId").asInt())) {
            if ("queued".equals(item.path("stage").asText())) {
                if (item.path("id").asInt() == intent.path("id").asInt()) return position;
                position++;
            }
        }
        return 0;
    }

    private String newPasscode() {
        Set<String> existing = new HashSet<>();
        intents().forEach(item -> existing.add(item.path("passcode").asText()));
        StringBuilder code = new StringBuilder();
        do {
            code.setLength(0);
            for (int i = 0; i < 8; i++) code.append(CODE_ALPHABET.charAt(new Random().nextInt(CODE_ALPHABET.length())));
        } while (existing.contains(code.toString()));
        return code.toString();
    }

    private long nextId(String field) {
        int current = db.path(field).asInt();
        db.put(field, current + 1);
        return current;
    }

    private boolean isBuyable(JsonNode product) {
        return product != null && ("active".equals(product.path("status").asText()) || "resumed".equals(product.path("status").asText()));
    }

    private ArrayNode products() { return (ArrayNode) db.withArray("products"); }
    private ArrayNode intents() { return (ArrayNode) db.withArray("intents"); }

    private ObjectNode loadDatabase() throws IOException {
        if (!Files.exists(dataFile)) {
            ObjectNode initial = mapper.createObjectNode();
            ObjectNode seller = initial.putObject("seller");
            seller.put("username", "admin");
            seller.put("passwordHash", passwordEncoder.encode(System.getenv().getOrDefault("DEFAULT_SELLER_PASSWORD", "admin1234")));
            seller.putNull("passwordChangedAt");
            initial.putArray("products");
            initial.putArray("intents");
            initial.put("nextProductId", 1);
            initial.put("nextIntentId", 1);
            db = initial;
            save();
            return initial;
        }
        return (ObjectNode) mapper.readTree(Files.readString(dataFile));
    }

    private void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            Path temp = Files.createTempFile(dataFile.getParent(), "." + dataFile.getFileName(), ".tmp");
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(db), StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("数据保存失败", e);
        }
    }

    private ImageData validateImage(MultipartFile file) {
        if (file.getSize() > MAX_IMAGE_BYTES || (!"image/jpeg".equals(file.getContentType()) && !"image/png".equals(file.getContentType()))) {
            throw error(HttpStatus.BAD_REQUEST, "图片必须为 JPG/PNG 且不超过 5MB");
        }
        try {
            byte[] content = file.getBytes();
            try (InputStream input = file.getInputStream()) {
                BufferedImage image = ImageIO.read(input);
                if (image == null) throw new IOException("无法识别图片");
            }
            return new ImageData(content, file.getContentType().equals("image/jpeg") ? ".jpg" : ".png");
        } catch (IOException | NoSuchElementException e) {
            throw error(HttpStatus.BAD_REQUEST, "图片必须为 JPG/PNG 且不超过 5MB");
        }
    }

    private long parsePrice(String value) {
        if (value == null || value.length() > 18 || !PRICE_PATTERN.matcher(value.trim()).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "商品名称、描述和有效价格均为必填");
        }
        try {
            String[] parts = value.trim().split("\\.", -1);
            long cents = Math.addExact(Math.multiplyExact(Long.parseLong(parts[0]), 100),
                    parts.length == 2 ? Long.parseLong((parts[1] + "00").substring(0, 2)) : 0);
            if (cents <= 0 || cents > MAX_PRICE_CENTS) throw new ArithmeticException();
            return cents;
        } catch (ArithmeticException | NumberFormatException e) {
            throw error(HttpStatus.BAD_REQUEST, "商品名称、描述和有效价格均为必填");
        }
    }

    private void requirePerson(String name, String phone) {
        if (name == null || name.trim().isEmpty() || phone == null || phone.trim().isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "姓名和联系电话均为必填");
        }
    }

    private boolean matchesPassword(String password, String encoded) {
            String plain = password == null ? "" : password;
            try {
                if (encoded.startsWith("scrypt:")) {
                    String[] parts = encoded.split(":", -1);
                    if (parts.length != 3) return false;
                    byte[] expected = hex(parts[2]);
                    byte[] actual = SCrypt.generate(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            parts[1].getBytes(java.nio.charset.StandardCharsets.UTF_8), 16384, 8, 1, 64);
                    return java.security.MessageDigest.isEqual(actual, expected);
                }
                return passwordEncoder.matches(plain, encoded);
            } catch (RuntimeException e) {
                return false;
            }
        }

    private byte[] hex(String value) {
            if (value.length() % 2 != 0) throw new IllegalArgumentException("invalid hash");
            byte[] result = new byte[value.length() / 2];
            for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            return result;
    }

    private String now() { return ISO.format(Instant.now()); }
    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        new java.security.SecureRandom().nextBytes(value);
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format("%02x", item));
        return result.toString();
    }
    private void copy(ObjectNode target, JsonNode source, String... fields) {
        for (String field : fields) target.set(field, source.path(field).deepCopy());
    }
    private ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    private record ImageData(byte[] content, String extension) {}
}
