package com.coursewise.shopping;

import com.coursewise.shopping.domain.SellerEntity;
import com.coursewise.shopping.repository.SellerRepository;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 卖家认证、会话与密码管理。 */
@Service
public class ShoppingService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final SellerRepository sellers;
    private final SCryptPasswordEncoder passwordEncoder = SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8();
    /** 登录会话存内存，重启失效需重新登录。 */
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    public ShoppingService(SellerRepository sellers, @Value("${shopping.upload-dir}") String uploadDir) {
        this.sellers = sellers;
        // Spring 测试上下文也需要上传目录，此时不会调用应用的 main 方法。
        try {
            Files.createDirectories(Path.of(uploadDir).toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化图片目录", e);
        }
    }

    /** FR-S-01 卖家登录；失败提示不区分账号/密码错误。 */
    @Transactional(readOnly = true)
    public synchronized boolean login(String username, String password, String session) {
        SellerEntity seller = sellers.findByUsername(username == null ? "" : username).orElse(null);
        if (seller == null || !matchesPassword(password, seller.getPasswordHash())) {
            return false;
        }
        sessions.add(session);
        return true;
    }

    /** FR-S-02 修改密码，核对原密码后保存新密码的哈希。 */
    @Transactional
    public synchronized void changePassword(String session, String oldPassword, String newPassword) {
        requireSession(session);
        SellerEntity seller = requireSeller();
        if (!matchesPassword(oldPassword, seller.getPasswordHash())) {
            throw error(HttpStatus.BAD_REQUEST, "原密码错误");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw error(HttpStatus.BAD_REQUEST, "新密码至少 8 位");
        }
        seller.setPasswordHash(passwordEncoder.encode(newPassword));
        seller.setPasswordChangedAt(now());
        sellers.save(seller);
    }

    public void logout(String session) {
        if (session != null) sessions.remove(session);
    }

    public void requireSession(String session) {
        if (session == null || !sessions.contains(session)) throw error(HttpStatus.UNAUTHORIZED, "未登录");
    }

    private SellerEntity requireSeller() {
        return sellers.findTheSeller()
                .orElseThrow(() -> new IllegalStateException("卖家账号不存在"));
    }

    /** 兼容两种哈希格式：scrypt:&lt;salt&gt;:&lt;hex&gt;（旧数据）与 Spring Security 编码。 */
    private boolean matchesPassword(String password, String encoded) {
        String plain = password == null ? "" : password;
        if (encoded == null) return false;
        try {
            if (encoded.startsWith("scrypt:")) {
                String[] parts = encoded.split(":", -1);
                if (parts.length != 3) return false;
                byte[] expected = hex(parts[2]);
                byte[] salt = parts[1].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] actual = SCrypt.generate(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        salt, 16384, 8, 1, 64);
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
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
