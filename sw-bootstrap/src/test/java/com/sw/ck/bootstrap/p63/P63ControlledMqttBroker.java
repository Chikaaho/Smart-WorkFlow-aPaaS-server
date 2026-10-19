package com.sw.ck.bootstrap.p63;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P63 受控隔离 MQTT Broker（测试设施，与测试类同生命周期自启动自清理）。
 *
 * <p>只服务测试契约需要的 MQTT 3.1.1（协议级 4）行为子集：CONNECT/CONNACK、
 * SUBSCRIBE/SUBACK、UNSUBSCRIBE/UNSUBACK、PUBLISH（QoS0/1/2 确认流程）、
 * PINGREQ/PINGRESP、DISCONNECT；每收到一条 PUBLISH 即向接收记录（jsonl）追加密钥
 * {@code ts/topic/qos/retain/payload} 一行——「Broker 实际接收」由该记录承载。</p>
 *
 * <p>生命周期：{@link #start} 绑定 127.0.0.1:port、以真实 CONNECT/CONNACK 往返做协议就绪探测，
 * 任一环节失败都显式抛出（端口占用、探测超时等原文进异常消息），不静默降级、不改测试断言；
 * {@link #close} 关闭监听与全部连接、有界等待处理线程退出。端口/记录路径由测试契约给定，
 * 不依赖外部 broker、真实设备或共享服务。</p>
 */
public final class P63ControlledMqttBroker implements Closeable {

    private static final int TYPE_CONNECT = 1;
    private static final int TYPE_CONNACK = 2;
    private static final int TYPE_PUBLISH = 3;
    private static final int TYPE_PUBACK = 4;
    private static final int TYPE_PUBREC = 5;
    private static final int TYPE_PUBREL = 6;
    private static final int TYPE_PUBCOMP = 7;
    private static final int TYPE_SUBSCRIBE = 8;
    private static final int TYPE_SUBACK = 9;
    private static final int TYPE_UNSUBSCRIBE = 10;
    private static final int TYPE_UNSUBACK = 11;
    private static final int TYPE_PINGREQ = 12;
    private static final int TYPE_PINGRESP = 13;
    private static final int TYPE_DISCONNECT = 14;

    private final String host;
    private final int port;
    private final Path receivedLog;
    private final ServerSocket server;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<Socket> connections = new CopyOnWriteArrayList<>();
    private final Object recordLock = new Object();
    private final Thread acceptThread;

    private P63ControlledMqttBroker(String host, int port, Path receivedLog, ServerSocket server) {
        this.host = host;
        this.port = port;
        this.receivedLog = receivedLog;
        this.server = server;
        this.acceptThread = new Thread(this::acceptLoop, "p63-controlled-mqtt-accept");
        this.acceptThread.setDaemon(true);
    }

    /**
     * 启动受控 broker：绑定 → 接收记录重建（清空历史行）→ 接受循环 → 协议就绪探测。
     *
     * @throws IllegalStateException 端口占用、绑定失败或协议探测失败（原文含具体原因）
     */
    public static P63ControlledMqttBroker start(String host, int port, Path receivedLog) {
        try {
            if (receivedLog.getParent() != null) {
                Files.createDirectories(receivedLog.getParent());
            }
            Files.writeString(receivedLog, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "P63 受控 broker 接收记录不可写: " + receivedLog + " -> " + e.getMessage(), e);
        }
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(host, port), 50);
            server.setSoTimeout(500);
        } catch (IOException e) {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // 绑定失败时关闭失败不影响结论
                }
            }
            throw new IllegalStateException("P63 受控 broker 绑定失败 " + host + ":" + port
                    + "（端口占用或不可用）-> " + e.getMessage(), e);
        }
        P63ControlledMqttBroker broker = new P63ControlledMqttBroker(host, port, receivedLog, server);
        broker.acceptThread.start();
        try {
            broker.probeReady();
        } catch (RuntimeException e) {
            broker.close();
            throw e;
        }
        return broker;
    }

    /** 协议级就绪探测：真实 CONNECT 往返取得 CONNACK(returnCode=0) 才算就绪。 */
    private void probeReady() {
        String clientId = "p63-readiness-probe";
        byte[] idBytes = clientId.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream connect = new ByteArrayOutputStream();
        connect.write(0x00);
        connect.write(0x04);
        byte[] name = "MQTT".getBytes(StandardCharsets.UTF_8);
        connect.writeBytes(name);
        connect.write(0x04);
        connect.write(0x02);
        connect.write(0x00);
        connect.write(0x00);
        connect.write((idBytes.length >> 8) & 0xFF);
        connect.write(idBytes.length & 0xFF);
        connect.writeBytes(idBytes);
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(host, port), 3_000);
            probe.setSoTimeout(3_000);
            writePacket(probe.getOutputStream(), TYPE_CONNECT, 0, connect.toByteArray());
            probe.getOutputStream().flush();
            DataInputStream in = new DataInputStream(probe.getInputStream());
            Packet connack = readPacket(in);
            if (connack == null || connack.type != TYPE_CONNACK || connack.body.length < 2
                    || (connack.body[1] & 0xFF) != 0) {
                throw new IllegalStateException("P63 受控 broker 协议探测失败: CONNACK 未按要求返回 "
                        + (connack == null ? "(读不到响应)"
                        : "type=" + connack.type + " body=" + toHex(connack.body)));
            }
            writePacket(probe.getOutputStream(), TYPE_DISCONNECT, 0, new byte[0]);
            probe.getOutputStream().flush();
        } catch (IOException e) {
            throw new IllegalStateException("P63 受控 broker 协议探测失败 " + host + ":" + port
                    + " -> " + e.getMessage(), e);
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (closed.get()) {
                    return;
                }
                System.out.println("[P63-EV] mqtt-accept 结束: " + e.getMessage());
                return;
            }
            connections.add(socket);
            Thread handler = new Thread(() -> handleConnection(socket), "p63-controlled-mqtt-conn");
            handler.setDaemon(true);
            handler.start();
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket conn = socket) {
            DataInputStream in = new DataInputStream(conn.getInputStream());
            OutputStream out = conn.getOutputStream();
            Packet packet;
            while (!closed.get() && (packet = readPacket(in)) != null) {
                if (packet.type == TYPE_DISCONNECT) {
                    return;
                }
                if (packet.type == TYPE_PINGREQ) {
                    writePacket(out, TYPE_PINGRESP, 0, new byte[0]);
                } else if (packet.type == TYPE_CONNECT) {
                    writePacket(out, TYPE_CONNACK, 0, new byte[]{0x00, 0x00});
                } else if (packet.type == TYPE_SUBSCRIBE) {
                    writePacket(out, TYPE_SUBACK, 0, subscribeAck(packet.body));
                } else if (packet.type == TYPE_UNSUBSCRIBE) {
                    writePacket(out, TYPE_UNSUBACK, 0,
                            new byte[]{packet.body[0], packet.body[1]});
                } else if (packet.type == TYPE_PUBLISH) {
                    Publish publish = parsePublish(packet);
                    if (publish == null) {
                        return;
                    }
                    recordPublish(publish);
                    if (publish.qos == 1) {
                        writePacket(out, TYPE_PUBACK, 0, packetId(publish.packetId));
                    } else if (publish.qos == 2) {
                        writePacket(out, TYPE_PUBREC, 0, packetId(publish.packetId));
                    }
                } else if (packet.type == TYPE_PUBREL) {
                    writePacket(out, TYPE_PUBCOMP, 0, packet.body);
                }
                out.flush();
            }
        } catch (IOException e) {
            // 连接级异常（客户端断开/关闭清理）不改变测试结论；诊断信息保留。
            if (!closed.get()) {
                System.out.println("[P63-EV] mqtt-conn 结束: " + e.getMessage());
            }
        }
    }

    /** SUBSCRIBE → SUBACK：包标识沿用请求，授予 QoS 取请求值（0/1/2）。 */
    private static byte[] subscribeAck(byte[] body) {
        if (body.length < 5) {
            return new byte[]{0x00, 0x00, 0x00};
        }
        List<Byte> granted = new java.util.ArrayList<>();
        int idx = 2;
        while (idx < body.length) {
            int topicLen = ((body[idx] & 0xFF) << 8) | (body[idx + 1] & 0xFF);
            idx += 2 + topicLen;
            if (idx >= body.length) {
                break;
            }
            granted.add(body[idx]);
            idx += 1;
        }
        byte[] ack = new byte[2 + granted.size()];
        ack[0] = body[0];
        ack[1] = body[1];
        for (int i = 0; i < granted.size(); i++) {
            ack[2 + i] = granted.get(i);
        }
        return ack;
    }

    /** 解析 PUBLISH：主题（两字节长度前缀）+ [QoS>0 包标识] + 载荷原文。 */
    private static Publish parsePublish(Packet packet) {
        byte[] body = packet.body;
        int qos = (packet.flags >> 1) & 0x03;
        boolean retain = (packet.flags & 0x01) == 0x01;
        if (body.length < 2) {
            return null;
        }
        int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
        int idx = 2 + topicLen;
        if (idx > body.length) {
            return null;
        }
        String topic = new String(body, 2, topicLen, StandardCharsets.UTF_8);
        int packetId = 0;
        if (qos > 0) {
            if (idx + 2 > body.length) {
                return null;
            }
            packetId = ((body[idx] & 0xFF) << 8) | (body[idx + 1] & 0xFF);
            idx += 2;
        }
        byte[] payload = java.util.Arrays.copyOfRange(body, idx, body.length);
        return new Publish(topic, qos, retain, packetId, payload);
    }

    private static byte[] packetId(int id) {
        return new byte[]{(byte) ((id >> 8) & 0xFF), (byte) (id & 0xFF)};
    }

    private void recordPublish(Publish publish) {
        String line = "{\"ts\":\"" + Instant.now() + "\",\"topic\":\"" + jsonEscape(publish.topic)
                + "\",\"qos\":" + publish.qos + ",\"retain\":" + publish.retain
                + ",\"payload\":\"" + jsonEscape(new String(publish.payload, StandardCharsets.UTF_8))
                + "\"}";
        synchronized (recordLock) {
            try {
                Files.writeString(receivedLog, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("P63 受控 broker 接收记录写入失败: " + receivedLog, e);
            }
        }
        System.out.println("[P63-EV] mqtt.received topic=" + publish.topic
                + " qos=" + publish.qos + " bytes=" + publish.payload.length);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭路径容错
        }
        for (Socket socket : connections) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 连接已断开
            }
        }
        try {
            acceptThread.join(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[P63-EV] mqtt terminated host=" + host + ":" + port
                + " acceptAlive=" + acceptThread.isAlive());
    }

    // ==================== 最小 MQTT 3.1.1 报文编解码 ====================

    private record Packet(int type, int flags, byte[] body) {
    }

    private record Publish(String topic, int qos, boolean retain, int packetId, byte[] payload) {
    }

    private static void writePacket(OutputStream out, int type, int flags, byte[] body)
            throws IOException {
        out.write((type << 4) | (flags & 0x0F));
        int remaining = body.length;
        do {
            int digit = remaining % 128;
            remaining /= 128;
            if (remaining > 0) {
                digit |= 0x80;
            }
            out.write(digit);
        } while (remaining > 0);
        out.write(body);
    }

    private static Packet readPacket(DataInputStream in) throws IOException {
        int header = in.read();
        if (header < 0) {
            return null;
        }
        int remaining = 0;
        int multiplier = 1;
        for (int i = 0; i < 4; i++) {
            int digit = in.read();
            if (digit < 0) {
                return null;
            }
            remaining += (digit & 0x7F) * multiplier;
            if ((digit & 0x80) == 0) {
                break;
            }
            multiplier *= 128;
        }
        byte[] body = new byte[remaining];
        in.readFully(body);
        return new Packet((header >> 4) & 0x0F, header & 0x0F, body);
    }

    private static String jsonEscape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
