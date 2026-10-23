package com.company.leave.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

/** Minimal embedded HTTP stub that echoes which backend answered. */
final class StubBackend implements AutoCloseable {

    private final HttpServer server;
    private final String name;

    StubBackend(String name) throws IOException {
        this.name = name;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] in = ex.getRequestBody().readAllBytes();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String body = "{\"backend\":\"" + name + "\",\"method\":\"" + ex.getRequestMethod()
                + "\",\"path\":\"" + ex.getRequestURI().getRawPath()
                + "\",\"query\":\"" + (ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery())
                + "\",\"auth\":\"" + (auth == null ? "" : auth)
                + "\",\"bodyLength\":" + in.length + "}";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"x.csv\"");
        ex.getResponseHeaders().add("Retry-After", "7");
        ex.getResponseHeaders().add("Allow", "GET, POST");
        // a misbehaving service emitting its own (duplicate) CORS header
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "http://stub.example");
        int status = ex.getRequestURI().getPath().endsWith("/boom405") ? 405 : 200;
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
