package top.chende.coursemind.api;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.*;
import org.junit.Test;
import top.chende.coursemind.stats.StudentStatsTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ApiClientStatsTest {
    @Test public void todayRequestUsesStudentBearerAndActualWireFields() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> query=new AtomicReference<>(), bearer=new AtomicReference<>();
        server.createContext("/api/v1/student/me/stats",exchange->{
            query.set(exchange.getRequestURI().getQuery()); bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonObject response=new JsonObject();response.addProperty("code",0);response.add("data",StudentStatsTest.fixture());
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var stats=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort()).myStats("test-token");
            assertEquals("range=today&recent_limit=5",query.get());assertEquals("Bearer test-token",bearer.get());
            assertEquals(6,stats.summary().promptCount());assertEquals("codebuddy",stats.sessions().get(0).source());
        }finally{server.stop(0);}
    }
}
