package cn.haitang.anticheat.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerTest {

    @Test
    void usesConfiguredPublicUrlForDisplay() {
        assertEquals("https://sac.example.com/",
                WebServer.formatDisplayUrl(" https://sac.example.com ", "0.0.0.0", 8080));
    }

    @Test
    void preservesConfiguredPublicUrlTrailingSlash() {
        assertEquals("https://sac.example.com/",
                WebServer.formatDisplayUrl("https://sac.example.com/", "127.0.0.1", 9090));
    }

    @Test
    void fallsBackToBindAndPortWhenPublicUrlIsBlank() {
        assertEquals("http://<服务器IP>:8080/",
                WebServer.formatDisplayUrl("", "0.0.0.0", 8080));
        assertEquals("http://127.0.0.1:9090/",
                WebServer.formatDisplayUrl(null, "127.0.0.1", 9090));
    }

    @Test
    void oneTimeLoginUsesDedicatedAdminEntryPoint() {
        assertEquals("https://sac.example.com/admin#admin-login=ticket-123",
                WebServer.formatOneTimeLoginUrl("https://sac.example.com/", "ticket-123"));
    }

    @Test
    void versionsAppJavascriptByItsContent() {
        String html = "<script src=\"/app.js\"></script>";
        String first = WebServer.versionAppJavascript(html, "first build");
        String second = WebServer.versionAppJavascript(html, "second build");

        assertNotEquals(first, second);
        assertTrue(first.matches("<script src=\"/app\\.js\\?v=[0-9a-f-]{36}\"></script>"));
    }
}
