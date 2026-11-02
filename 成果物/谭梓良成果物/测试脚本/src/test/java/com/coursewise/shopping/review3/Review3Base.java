package com.coursewise.shopping.review3;

import com.coursewise.shopping.ShoppingApplication;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

/** 独立临时数据、真实随机端口 HTTP；只在测试副本执行，不写原项目。 */
@SpringBootTest(classes=ShoppingApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(Review3Base.OutcomeLog.class)
public abstract class Review3Base {
    protected static final Path TEST_ROOT;
    protected static final Path UPLOAD_DIR;
    protected static final String PASSWORD="Testpass123";
    private static final String SELLER_HASH=SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8().encode("admin1234");
    static {
        try { TEST_ROOT=Files.createTempDirectory("review3-acceptance-"); UPLOAD_DIR=Files.createDirectories(TEST_ROOT.resolve("uploads")); }
        catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:sqlite:"+TEST_ROOT.resolve("test.db"));
        r.add("shopping.upload-dir",()->UPLOAD_DIR.toString());
        r.add("shopping.legacy-data-file",()->TEST_ROOT.resolve("absent.json").toString());
        r.add("server.address",()->"127.0.0.1");
        r.add("spring.main.banner-mode",()->"off");
        r.add("logging.level.root",()->"WARN");
    }
    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper mapper;
    protected Client seller;
    protected long parentId,leafId;

    @BeforeEach void reset() throws Exception {
        for(String table:List.of("intent","product","category","customer","seller")) jdbc.update("DELETE FROM "+table);
        try(var paths=Files.list(UPLOAD_DIR)){for(Path p:paths.toList()) Files.deleteIfExists(p);}
        jdbc.update("INSERT INTO seller(username,password_hash,password_changed_at) VALUES(?,?,NULL)","admin",SELLER_HASH);
        seller=anonymous();
        expect(seller.post("/api/auth/login",obj("username","admin","password","admin1234")),200);
        parentId=id(expect(seller.post("/api/admin/categories",obj("name","测试一级","parentId",null)),201).json);
        leafId=id(expect(seller.post("/api/admin/categories",obj("name","测试二级","parentId",parentId)),201).json);
    }
    protected Map<String,Object> obj(Object... kv){var m=new LinkedHashMap<String,Object>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    protected Client anonymous(){return new Client();}
    protected Client customer(String username) throws Exception {
        Client c=anonymous();
        expect(c.post("/api/customer/register",obj("username",username,"password",PASSWORD,"phone","13800000000","defaultPlace","校园门口")),201);
        expect(c.post("/api/customer/login",obj("username",username,"password",PASSWORD)),200);return c;
    }
    protected long id(JsonNode n){return n.path("id").asLong();}
    protected JsonNode publish(String name,int stock) throws Exception {
        return expect(multipart(seller,"/api/products",Map.of("name",name,"price","12.50","stock",String.valueOf(stock),"categoryId",String.valueOf(leafId),"description","测试商品描述"),List.of()),201).json;
    }
    protected JsonNode submit(Client c,long productId) throws Exception {return expect(c.post("/api/customer/intents",obj("productId",productId)),201).json;}
    protected JsonNode start(long p) throws Exception {return expect(seller.post("/api/admin/trades/"+p+"/start",obj()),200).json;}
    protected JsonNode success(long p) throws Exception {return expect(seller.post("/api/admin/trades/"+p+"/success",obj()),200).json;}
    protected JsonNode fail(long p,String d) throws Exception {return expect(seller.post("/api/admin/trades/"+p+"/fail",obj("disposition",d)),200).json;}
    protected Response expect(Response r,int status){assertEquals(status,r.status,r.text);return r;}
    protected byte[] png() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(5,5,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    protected record Upload(String field,String filename,String contentType,byte[] bytes){}
    protected Response multipart(Client c,String path,Map<String,String> fields,List<Upload> files) throws Exception {
        String boundary="----review3-"+UUID.randomUUID();var out=new ByteArrayOutputStream();
        for(var e:fields.entrySet()){
            out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+e.getKey()+"\"\r\n\r\n"+e.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
        }
        for(var f:files){
            out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+f.field()+"\"; filename=\""+f.filename()+"\"\r\nContent-Type: "+f.contentType()+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(f.bytes());out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return c.send("POST",path,out.toByteArray(),"multipart/form-data; boundary="+boundary);
    }
    protected class Client {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
        public Response get(String path)throws Exception{return send("GET",path,null,null);}
        public Response post(String path,Object body)throws Exception{return request("POST",path,body);}
        public Response patch(String path,Object body)throws Exception{return request("PATCH",path,body);}
        public Response delete(String path)throws Exception{return send("DELETE",path,null,null);}
        public Response request(String method,String path,Object body)throws Exception{return send(method,path,mapper.writeValueAsBytes(body),"application/json");}
        public Response raw(String method,String path,String body)throws Exception{return send(method,path,body==null?null:body.getBytes(StandardCharsets.UTF_8),"application/json");}
        public String cookie(){return String.join("; ",cookies.getCookieStore().getCookies().stream().map(x->x.getName()+"="+x.getValue()).toList());}
        public Response send(String method,String path,byte[] body,String contentType)throws Exception{
            var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
            if(contentType!=null)req.header("Content-Type",contentType);
            var raw=http.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
            return new Response(raw);
        }
    }
    protected class Response {
        public final int status;public final JsonNode json;public final String text;public final byte[] bytes;public final Map<String,List<String>> headers;
        Response(HttpResponse<byte[]> r)throws Exception {status=r.statusCode();bytes=r.body();text=new String(bytes,StandardCharsets.UTF_8);headers=r.headers().map();JsonNode n;try{n=mapper.readTree(bytes);}catch(Exception e){n=mapper.nullNode();}json=n==null?mapper.nullNode():n;}
    }
    public static class OutcomeLog implements TestWatcher {
        public void testSuccessful(ExtensionContext c){System.out.println("PASS "+c.getDisplayName());}
        public void testFailed(ExtensionContext c,Throwable t){System.out.println("FAIL "+c.getDisplayName()+" :: "+t.getMessage());}
    }
}
