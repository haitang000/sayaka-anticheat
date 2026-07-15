package cn.haitang.anticheat.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPanelAssetsTest {

    @Test
    void panelLoadsPrecompiledJavascriptWithoutBrowserBabel() throws IOException {
        String html = resource("web/index.html");
        String javascript = resource("web/app.js");

        assertTrue(html.contains("<script src=\"/app.js\"></script>"));
        assertFalse(html.contains("@babel/standalone"));
        assertFalse(html.contains("text/babel"));
        assertFalse(javascript.matches("(?s).*\\bimport\\s+(?:[({*]|[A-Za-z_$]).*"));
        assertTrue(javascript.contains("ReactDOM.createRoot"));
        assertTrue(javascript.contains("admin-login"));
        assertTrue(javascript.contains("/api/admin/login/exchange"));
        assertTrue(javascript.contains("hashchange"));
        assertTrue(javascript.contains("history.replaceState"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = WebPanelAssetsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "插件资源中缺少 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
