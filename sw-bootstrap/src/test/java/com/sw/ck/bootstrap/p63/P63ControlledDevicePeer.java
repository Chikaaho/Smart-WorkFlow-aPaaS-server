package com.sw.ck.bootstrap.p63;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P63 受控设备对端（测试设施，与测试类同生命周期自启动自清理）。
 *
 * <p>只监听 127.0.0.1：{@code POST /device} 一律 200 受理并把请求原文追加到接收记录（jsonl，
 * 含 mode 标注，回包携带对端 requestId）；{@code GET /healthz} 只做就绪探测、不写记录。
 * {@code noreceipt} 模式语义：受理但不由本设施回发设备回执——命令真实停留 SENT，
 * 由产品补偿链以 expiry 收敛 UNKNOWN（被测业务断言不变）。</p>
 *
 * <p>生命周期：{@link #start} 绑定即服务，随后 {@code /healthz} 真实请求做协议就绪探测，
 * 失败（端口占用/探测不达）显式抛出原文；{@link #close} 停止服务并有界等待执行器退出。
 * 不依赖真实设备或共享服务。</p>
 */
final class P63ControlledDevicePeer implements Closeable {

    private final String host;
    private final int port;
    private final Path receivedLog;
    private final String mode;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Object recordLock = new Object();

    private P63ControlledDevicePeer(String host, int port, Path receivedLog, String mode,
                                    HttpServer server) {
        this.host = host;
        this.port = port;
        this.receivedLog = receivedLog;
        this.mode = mode;
        this.server = server;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "p63-controlled-peer-http");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 启动受控对端：接收记录重建（清空历史行）→ 绑定 → /healthz 就绪探测。
     *
     * @throws IllegalStateException 端口占用、绑定失败或就绪探测失败（原文含具体原因）
     */
    static P63ControlledDevicePeer start(String host, int port, Path receivedLog, String mode) {
        try {
            if (receivedLog.getParent() != null) {
                Files.createDirectories(receivedLog.getParent());
            }
            Files.writeString(receivedLog, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "P63 受控对端接收记录不可写: " + receivedLog + " -> " + e.getMessage(), e);
        }
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("P63 受控对端绑定失败 " + host + ":" + port
                    + "（端口占用或不可用）-> " + e.getMessage(), e);
        }
        P63ControlledDevicePeer peer = new P63ControlledDevicePeer(host, port, receivedLog, mode, server);
        server.createContext("/device", peer::handleDevice);
        server.createContext("/healthz", peer::handleHealth);
        server.setExecutor(peer.executor);
        server.start();
        peer.probeReady();
        return peer;
    }

    /** 就绪探测：真实 HTTP GET /healthz 期望 200；不写接收记录。 */
    private void probeReady() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + host + ":" + port + "/healthz"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("P63 受控对端就绪探测失败: /healthz HTTP "
                        + response.statusCode());
            }
        } catch (IOException e) {
            throw new IllegalStateException("P63 受控对端就绪探测失败 " + host + ":" + port
                    + " -> " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("P63 受控对端就绪探测被中断", e);
        }
    }

    private void handleDevice(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String requestId = extractRequestId(body);
        record(exchange, body, requestId);
        byte[] response = ("{\"accepted\":true,\"mode\":\"" + mode + "\",\"requestId\":\""
                + requestId + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    /** 对端回包沿用请求自带 requestId（产品侧首选字段）；缺失时生成受控标识。 */
    private static String extractRequestId(String body) {
        String marker = "\"requestId\"";
        int at = body.indexOf(marker);
        if (at >= 0) {
            int colon = body.indexOf(':', at + marker.length());
            int open = colon < 0 ? -1 : body.indexOf('"', colon + 1);
            int close = open < 0 ? -1 : body.indexOf('"', open + 1);
            if (open >= 0 && close > open) {
                return body.substring(open + 1, close);
            }
        }
        return "peer-" + java.util.UUID.randomUUID().toString().substring(0, 12);
    }

    private void record(HttpExchange exchange, String body, String requestId) {
        String line = "{\"ts\":\"" + Instant.now() + "\",\"mode\":\"" + mode + "\",\"method\":\""
                + exchange.getRequestMethod() + "\",\"path\":\"" + exchange.getRequestURI().getPath()
                + "\",\"remote\":\"" + exchange.getRemoteAddress() + "\",\"requestId\":\""
                + requestId + "\",\"body\":" + (body.isBlank() ? "null" : body) + "}";
        synchronized (recordLock) {
            try {
                Files.writeString(receivedLog, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("P63 受控对端接收记录写入失败: " + receivedLog, e);
            }
        }
        System.out.println("[P63-EV] peer.received mode=" + mode + " requestId=" + requestId
                + " bytes=" + body.length());
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                System.out.println("[P63-EV] peer executor 未在限时内退出（守护线程，进程退出兜底）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[P63-EV] peer terminated host=" + host + ":" + port + " mode=" + mode);
    }
}
