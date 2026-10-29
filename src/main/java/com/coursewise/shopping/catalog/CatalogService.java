package com.coursewise.shopping.catalog;

import com.coursewise.shopping.ShoppingService;
import com.coursewise.shopping.domain.*;
import com.coursewise.shopping.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import jakarta.persistence.criteria.Predicate;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** SRS V2.0：目录、搜索、详情、类别、发布、库存/类别编辑。 */
@Service
public class CatalogService {
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ShoppingService auth;
    private final ObjectMapper mapper;
    private final Path uploads;
    private final TransactionTemplate transaction;
    // SQLite 单实例部署：写锁覆盖事务提交，类别删除与发布/改挂共用锁。
    private final ReentrantLock writeLock = new ReentrantLock(true);

    public CatalogService(ProductRepository products, CategoryRepository categories, ShoppingService auth,
                          ObjectMapper mapper, PlatformTransactionManager manager,
                          @Value("${shopping.upload-dir}") String uploadDir) {
        this.products = products; this.categories = categories; this.auth = auth; this.mapper = mapper;
        uploads = Path.of(uploadDir).toAbsolutePath().normalize();
        transaction = new TransactionTemplate(manager);
    }

    public record CategoryInput(String name, Long parentId) {}
    public record EditInput(Integer stock, Long categoryId, Long version) {}

    @Transactional(readOnly = true)
    public ObjectNode list(String query, Long categoryId, int page, int size, boolean admin, String session) {
        if (admin) auth.requireSession(session);
        if (page < 1) throw bad("page", "页码必须大于等于 1");
        if (size < 1 || size > 60) throw bad("size", "每页条数须为 1 至 60");
        if (categoryId != null) requireLeaf(categoryId);
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Specification<ProductEntity> spec = (root, criteria, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            filters.add(cb.isNotNull(root.get("categoryId")));
            filters.add(cb.isNotNull(root.get("stock")));
            if (!admin) {
                filters.add(root.get("status").in(ProductStatus.active, ProductStatus.resumed));
                filters.add(cb.greaterThan(root.get("stock"), 0));
            }
            if (categoryId != null) filters.add(cb.equal(root.get("categoryId"), categoryId));
            if (!keyword.isEmpty()) {
                String like = "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                filters.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.like(cb.lower(root.get("descriptionText")), like, '\\')));
            }
            return cb.and(filters.toArray(Predicate[]::new));
        };
        Page<ProductEntity> found = products.findAll(spec, PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        ArrayNode items = mapper.createArrayNode();
        for (ProductEntity product : found) items.add(view(product, admin));
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        result.put("page", page).put("size", size).put("total", found.getTotalElements())
                .put("totalPages", found.getTotalPages());
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode detail(long id, boolean admin, String session) {
        if (admin) auth.requireSession(session);
        ProductEntity p = requireProduct(id);
        if (!admin && !p.isBuyable() && p.getStatus() != ProductStatus.frozen) throw missing();
        ObjectNode result = view(p, true);
        result.put("readOnly", p.getStatus() == ProductStatus.frozen);
        return result;
    }

    @Transactional(readOnly = true)
    public ArrayNode categoryTree() {
        List<CategoryEntity> all = categories.findAllByOrderByIdAsc();
        ArrayNode tree = mapper.createArrayNode();
        for (CategoryEntity parent : all) {
            if (parent.getParentId() != null) continue;
            ObjectNode node = categoryView(parent);
            ArrayNode children = mapper.createArrayNode();
            all.stream().filter(c -> Objects.equals(c.getParentId(), parent.getId()))
                    .forEach(c -> children.add(categoryView(c)));
            node.set("children", children); tree.add(node);
        }
        return tree;
    }

    public ObjectNode addCategory(String session, CategoryInput input) {
        auth.requireSession(session);
        return write(() -> {
            String name = categoryName(input.name());
            if (input.parentId() != null && requireCategory(input.parentId()).getParentId() != null)
                throw bad("parentId", "最多支持两级类别，请选择一级类别作为父类别");
            CategoryEntity item = new CategoryEntity();
            item.setName(name); item.setParentId(input.parentId());
            return categoryView(categories.saveAndFlush(item));
        });
    }

    public ObjectNode renameCategory(String session, long id, Map<String, Object> body) {
        auth.requireSession(session);
        return write(() -> {
            if (!body.keySet().equals(Set.of("name"))) throw bad("name", "重命名只接受 name，不能更换类别归属");
            CategoryEntity item = requireCategory(id);
            item.setName(categoryName(body.get("name") instanceof String s ? s : null));
            return categoryView(categories.saveAndFlush(item));
        });
    }

    public void deleteCategory(String session, long id) {
        auth.requireSession(session);
        write(() -> {
            requireCategory(id);
            if (categories.existsByParentId(id)) throw conflict("categoryId", "请先删除下属二级类别");
            if (products.existsByCategoryId(id)) throw conflict("categoryId", "类别下仍有商品，请先将商品改挂到其他二级类别");
            categories.deleteById(id); categories.flush(); return null;
        });
    }

    public ObjectNode publish(String session, String name, String price, Integer stock, Long categoryId,
                              String description, List<MultipartFile> images) {
        auth.requireSession(session);
        return write(() -> {
            if (name == null || name.isBlank() || name.strip().codePointCount(0, name.strip().length()) > 128)
                throw bad("name", "商品名称必填，最多 128 个字符");
            if (price == null || !price.matches("(?:0|[1-9]\\d{0,6})(?:\\.\\d{1,2})?"))
                throw bad("price", "价格须大于 0 且不超过 9,999,999.99 元，最多两位小数");
            long cents = new BigDecimal(price).movePointRight(2).longValueExact();
            if (cents < 1 || cents > 999999999L) throw bad("price", "价格须大于 0 且不超过 9,999,999.99 元");
            if (stock == null || stock < 1) throw bad("stock", "发布库存必须为大于等于 1 的整数");
            CategoryEntity category = requireLeaf(categoryId);
            List<MultipartFile> files = images == null ? List.of() : images;
            if (files.size() > 9) throw bad("images", "商品图片最多 9 张");
            List<ImageData> checked = files.stream().map(this::validateImage).toList();
            String html = cleanDescription(description);
            ArrayNode paths = mapper.createArrayNode();
            for (ImageData image : checked) paths.add(saveImage(image));
            ProductEntity p = new ProductEntity();
            p.setName(name.strip()); p.setPriceCents(cents); p.setStock(stock); p.setCategoryId(category.getId());
            p.setDescription(html); p.setDescriptionText(Jsoup.parse(html).text().toLowerCase(Locale.ROOT));
            p.setImagesJson(paths.toString()); p.setImage(paths.isEmpty() ? "" : paths.get(0).asText());
            p.setStatus(ProductStatus.active); p.setCreatedAt(Instant.now().toString());
            return view(products.saveAndFlush(p), true);
        });
    }

    public ObjectNode edit(String session, long id, EditInput input) {
        auth.requireSession(session);
        return write(() -> {
            ProductEntity p = requireProduct(id);
            if (input.version() == null || input.version() != p.getVersion())
                throw conflict("version", "商品已更新，请刷新后再编辑");
            if (input.stock() == null || input.stock() < 0) throw bad("stock", "库存必须为非负整数");
            CategoryEntity category = requireLeaf(input.categoryId());
            // 交易处理中调零需要队列模块先按 BR-34 取得卖家的逐条处置决定。
            if (p.getTradingIntentId() != null && input.stock() == 0)
                throw conflict("stock", "请先完成当前交易及未成交意向处置，再将库存调为 0");
            if ((p.isBuyable() || p.getStatus() == ProductStatus.frozen) && input.stock() == 0) {
                p.setStatus(ProductStatus.depleted); p.setResultAt(Instant.now().toString());
            }
            p.setStock(input.stock()); p.setCategoryId(category.getId());
            // depleted / sold / withdrawn 均不会因补货自动上架。
            return view(products.saveAndFlush(p), true);
        });
    }

    public ObjectNode uploadDescriptionImage(String session, MultipartFile file) {
        auth.requireSession(session);
        return write(() -> mapper.createObjectNode().put("url", saveImage(validateImage(file))));
    }

    private ObjectNode view(ProductEntity p, boolean full) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", p.getId()).put("name", p.getName()).put("priceCents", p.getPriceCents());
        node.put("image", p.getImage().isEmpty() ? "/placeholder.svg" : p.getImage());
        CategoryEntity leaf = requireCategory(p.getCategoryId());
        ObjectNode category = categoryView(leaf);
        category.put("parentName", requireCategory(leaf.getParentId()).getName());
        node.set("category", category);
        if (full) {
            node.put("description", p.getDescription()).put("stock", p.getStock())
                    .put("status", p.getStatus().code()).put("createdAt", p.getCreatedAt()).put("version", p.getVersion());
            try { node.set("images", mapper.readTree(p.getImagesJson() == null ? "[]" : p.getImagesJson())); }
            catch (IOException e) { throw new IllegalStateException("商品图片数据异常", e); }
        }
        return node;
    }

    private String cleanDescription(String value) {
        Safelist rules = new Safelist().addTags("p", "br", "div", "strong", "b", "em", "i", "u", "s",
                "ul", "ol", "li", "h2", "h3", "blockquote", "img").addAttributes("img", "src", "alt");
        var document = Jsoup.parseBodyFragment(Jsoup.clean(value == null ? "" : value, rules));
        for (var img : document.select("img")) {
            String src = img.attr("src");
            if (!src.matches("/uploads/[a-f0-9-]+\\.(?:png|jpg)") || !Files.isRegularFile(uploads.resolve(src.substring(9))))
                img.remove();
        }
        document.outputSettings().prettyPrint(false);
        return document.body().html();
    }

    private ImageData validateImage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 5L * 1024 * 1024)
            throw bad("images", "图片不能为空，单张不得超过 5MB");
        String mime = file.getContentType();
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        boolean png = "image/png".equals(mime) && filename.endsWith(".png");
        boolean jpg = "image/jpeg".equals(mime) && (filename.endsWith(".jpg") || filename.endsWith(".jpeg"));
        if (!png && !jpg) throw bad("images", "仅支持 JPG/PNG 图片，请检查文件扩展名与类型");
        try {
            byte[] bytes = file.getBytes();
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IOException();
                var reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    if (!(png ? format.equalsIgnoreCase("png") : format.equalsIgnoreCase("jpeg"))) throw new IOException();
                    reader.setInput(stream); if (reader.read(0) == null) throw new IOException();
                } finally { reader.dispose(); }
            }
            return new ImageData(bytes, png ? ".png" : ".jpg");
        } catch (IOException e) { throw bad("images", "图片内容损坏或实际格式不是 JPG/PNG"); }
    }

    private String saveImage(ImageData image) {
        String filename = UUID.randomUUID() + image.extension();
        Path target = uploads.resolve(filename);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { Files.deleteIfExists(target); } catch (IOException e) {
                        org.slf4j.LoggerFactory.getLogger(CatalogService.class).warn("回滚图片清理失败 {}", target, e);
                    }
                }
            }
        });
        try { Files.write(target, image.bytes(), StandardOpenOption.CREATE_NEW); }
        catch (IOException e) { throw new IllegalStateException("图片保存失败", e); }
        return "/uploads/" + filename;
    }

    private <T> T write(Supplier<T> action) {
        writeLock.lock();
        try { return transaction.execute(status -> action.get()); }
        finally { writeLock.unlock(); }
    }
    private ProductEntity requireProduct(long id) {
        ProductEntity p = products.findById(id).orElseThrow(this::missing);
        if (p.getCategoryId() == null || p.getStock() == null) throw missing();
        return p;
    }
    private CategoryEntity requireCategory(Long id) {
        if (id == null) throw bad("categoryId", "请选择二级类别");
        return categories.findById(id).orElseThrow(() -> bad("categoryId", "类别不存在"));
    }
    private CategoryEntity requireLeaf(Long id) {
        CategoryEntity category = requireCategory(id);
        if (category.getParentId() == null) throw bad("categoryId", "商品必须归属二级类别");
        return category;
    }
    private String categoryName(String value) {
        if (value == null || value.isBlank()) throw bad("name", "类别名称不能为空");
        return value.strip();
    }
    private ObjectNode categoryView(CategoryEntity c) {
        ObjectNode node = mapper.createObjectNode().put("id", c.getId()).put("name", c.getName());
        if (c.getParentId() == null) node.putNull("parentId"); else node.put("parentId", c.getParentId());
        return node;
    }
    private CatalogException bad(String field, String message) { return new CatalogException(HttpStatus.BAD_REQUEST, field, message); }
    private CatalogException conflict(String field, String message) { return new CatalogException(HttpStatus.CONFLICT, field, message); }
    private CatalogException missing() { return new CatalogException(HttpStatus.NOT_FOUND, "product", "商品不存在或已下架"); }
    private record ImageData(byte[] bytes, String extension) {}
}
