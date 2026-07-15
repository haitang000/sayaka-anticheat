package cn.haitang.anticheat.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
