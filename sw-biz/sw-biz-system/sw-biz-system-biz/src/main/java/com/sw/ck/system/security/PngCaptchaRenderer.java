package com.sw.ck.system.security;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 像素级 PNG 验证码渲染器（P45 补充方向 §1：有效验证码挑战）。
 * <p>
 * 将验证码答案光栅化为 PNG 位图（随机旋转/位移/配色 + 干扰线 + 干扰点），
 * Base64 编码为 {@code data:image/png} 载荷。答案只以像素形态存在：
 * 响应不包含独立答案字段、文本节点或任何可直接读取的答案元数据；
 * 服务端权威状态只保留答案摘要。
 */
@Component
public class PngCaptchaRenderer {

    private static final int WIDTH = 130;
    private static final int HEIGHT = 44;
    private static final Color[] PALETTE = {
        new Color(0x4a3f8f), new Color(0x7e306b), new Color(0x2f6f4f),
        new Color(0x8a5a2a), new Color(0x33475b),
    };

    private final SecureRandom random = new SecureRandom();

    /**
     * 渲染验证码图像载荷（data URL，前端 {@code <img src>} 直接可用）。
     *
     * @param code 答案原文（仅用于光栅化，响应不携带任何答案语义元数据）
     */
    public String render(String code) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(renderPng(code));
    }

    byte[] renderPng(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(0xf5f7fa));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            // 干扰线
            for (int i = 0; i < 5; i++) {
                g.setColor(color(rand(0, PALETTE.length), 120));
                g.setStroke(new BasicStroke(1 + random.nextInt(2)));
                g.drawLine(rand(0, WIDTH / 3), rand(4, HEIGHT - 4),
                        rand(WIDTH / 2, WIDTH), rand(4, HEIGHT - 4));
            }
            // 逐字符旋转绘制（光栅化后不可作为文本提取）
            int charCount = code.length();
            int slotWidth = (WIDTH - 24) / charCount;
            for (int i = 0; i < charCount; i++) {
                int x = 14 + slotWidth * i + rand(0, Math.max(1, slotWidth / 4));
                int y = HEIGHT / 2 + rand(4, 10);
                g.setColor(color(rand(0, PALETTE.length), 255));
                g.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, rand(22, 28)));
                double rotation = Math.toRadians(rand(-28, 28));
                g.rotate(rotation, x, y);
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.rotate(-rotation, x, y);
            }
            // 干扰点
            for (int i = 0; i < 40; i++) {
                g.setColor(color(rand(0, PALETTE.length), 150));
                g.fillRect(rand(0, WIDTH), rand(0, HEIGHT), 2, 2);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("验证码 PNG 渲染失败", e);
        }
    }

    private Color color(int index, int alpha) {
        Color base = PALETTE[index];
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    private int rand(int min, int maxExclusive) {
        return min + random.nextInt(Math.max(1, maxExclusive - min));
    }
}
