package com.coursewise.shopping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用入口。
 *
 * <p>这里额外做一件事：在 Spring 建好 JPA {@code EntityManagerFactory} <b>之前</b>
 * 创建好 SQLite 数据库文件所在目录与图片上传目录。
 *
 * <p><b>为什么必须做：</b>SQLite 驱动只创建<b>数据库文件</b>，不创建它的<b>父目录</b>。
 * 若 {@code ./data/} 不存在，连接会直接抛
 * {@code path to './data/shop.db': '...\data' does not exist} 并导致启动失败。
 * 全新克隆的项目里没有 {@code data/}，因此这一步决定了
 * "拷走源码即可运行"能否成立。
 *
 * <p><b>为什么用事件监听而不是在 run() 之后调用：</b>
 * {@code SpringApplication.run()} 在<b>上下文刷新阶段</b>就会构建 JPA
 * {@code EntityManagerFactory} 并执行 DDL，这发生在 {@code run()} 返回<b>之前</b>。
 * 因此"先 run、再建目录"来不及，必须在更早的
 * {@link ApplicationEnvironmentPreparedEvent}（环境已就绪、Bean 尚未创建）里完成。
 */
@SpringBootApplication
public class ShoppingApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ShoppingApplication.class);
        app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                ensureRuntimeDirectories(event.getEnvironment()));
        app.run(args);
    }

    /**
     * 创建运行时目录（SQLite 数据库所在目录、图片上传目录）。
     *
     * <p>两个目录都从已解析的配置读取，因此 {@code SHOP_DB_FILE} /
     * {@code SHOP_UPLOAD_DIR} 指定的自定义路径同样会被创建。
     */
    private static void ensureRuntimeDirectories(ConfigurableEnvironment env) {
        // 1) SQLite 数据库文件的父目录
        String url = env.getProperty("spring.datasource.url", "jdbc:sqlite:./data/shop.db");
        String prefix = "jdbc:sqlite:";
        if (url.startsWith(prefix)) {
            String path = url.substring(prefix.length()).trim();
            // 内存库（:memory:）不落盘，无需建目录
            if (!path.isEmpty() && !path.startsWith(":") && !path.startsWith("file:")) {
                Path parent = Path.of(path).toAbsolutePath().normalize().getParent();
                createDirectories(parent);
            }
        }

        // 2) 图片上传目录
        String uploadDir = env.getProperty("shopping.upload-dir", "./uploads");
        if (uploadDir != null && !uploadDir.isBlank()) {
            createDirectories(Path.of(uploadDir).toAbsolutePath().normalize());
        }
    }

    private static void createDirectories(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建运行时目录: " + dir, e);
        }
    }
}
