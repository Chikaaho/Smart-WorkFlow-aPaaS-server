import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 验证码模板匹配识读器（同字体 MONOSPACED BOLD，含旋转/字号枚举与 IoU 匹配）。
 * 仅使用图像像素与公开字体信息，不涉及服务端答案反推。
 * 用法：java p64p2_ocr.java <captcha.png> [threshold]
 */
public class p64p2_ocr {
    static final String CHARSET = "23456789abcdefghjkmnpqrstuvwxyz";
    static final int GW = 24, GH = 32;

    public static void main(String[] args) throws Exception {
        String path = args[0];
        int threshold = args.length > 1 ? Integer.parseInt(args[1]) : 150;
        BufferedImage src = ImageIO.read(new File(path));
        int w = src.getWidth(), h = src.getHeight();
        boolean[][] dark = new boolean[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                dark[x][y] = lum < threshold;
            }
        }
        List<int[]> comps = components(dark, w, h);
        comps.sort((a, b) -> Integer.compare(b[4], a[4]));
        List<int[]> glyphs = new ArrayList<>();
        for (int[] c : comps) {
            if (glyphs.size() >= 4) break;
            if (c[4] >= 25) glyphs.add(c); // area 过滤（去噪点/细线）
        }
        glyphs.sort(Comparator.comparingInt(a -> a[0]));
        System.out.println("glyph boxes (x0,y0,x1,y1,area):");
        for (int[] c : glyphs) System.out.println("  " + c[0] + "," + c[1] + " - " + c[2] + "," + c[3] + " area=" + c[4]);
        if (glyphs.size() != 4) { System.out.println("WARN: expected 4 glyphs, got " + glyphs.size()); }
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < glyphs.size(); i++) {
            boolean[] mask = normalize(dark, glyphs.get(i)[0], glyphs.get(i)[1], glyphs.get(i)[2], glyphs.get(i)[3]);
            List<double[]> scores = new ArrayList<>(); // [score, charIndex]
            for (int ci = 0; ci < CHARSET.length(); ci++) {
                char ch = CHARSET.charAt(ci);
                double best = 0;
                for (int size = 20; size <= 30; size += 2) {
                    for (int angle = -28; angle <= 28; angle += 4) {
                        boolean[] t = template(ch, size, angle);
                        if (t == null) continue;
                        double iou = iou(mask, t);
                        if (iou > best) best = iou;
                    }
                }
                scores.add(new double[]{best, ci});
            }
            scores.sort((a, b) -> Double.compare(b[0], a[0]));
            StringBuilder top = new StringBuilder();
            for (int k = 0; k < 3 && k < scores.size(); k++) {
                top.append(CHARSET.charAt((int) scores.get(k)[1])).append("=")
                   .append(String.format("%.3f", scores.get(k)[0])).append(" ");
            }
            answer.append(CHARSET.charAt((int) scores.get(0)[1]));
            System.out.println("pos" + (i + 1) + " top3: " + top);
        }
        System.out.println("ANSWER: " + answer);
    }

    static List<int[]> components(boolean[][] dark, int w, int h) {
        boolean[][] seen = new boolean[w][h];
        List<int[]> out = new ArrayList<>();
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1}, dy = {0, 0, 1, -1, 1, -1, 1, -1};
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (!dark[x][y] || seen[x][y]) continue;
                List<int[]> stack = new ArrayList<>();
                stack.add(new int[]{x, y});
                seen[x][y] = true;
                int minX = x, maxX = x, minY = y, maxY = y, area = 0;
                while (!stack.isEmpty()) {
                    int[] p = stack.remove(stack.size() - 1);
                    area++;
                    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
                    for (int d = 0; d < 8; d++) {
                        int nx = p[0] + dx[d], ny = p[1] + dy[d];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && dark[nx][ny] && !seen[nx][ny]) {
                            seen[nx][ny] = true;
                            stack.add(new int[]{nx, ny});
                        }
                    }
                }
                out.add(new int[]{minX, minY, maxX, maxY, area});
            }
        }
        return out;
    }

    /** 抽取区域并按包围盒缩放到 GW×GH 的位掩码。 */
    static boolean[] normalize(boolean[][] dark, int x0, int y0, int x1, int y1) {
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        boolean[] out = new boolean[GW * GH];
        for (int gx = 0; gx < GW; gx++) {
            for (int gy = 0; gy < GH; gy++) {
                int sx = x0 + Math.min(bw - 1, gx * bw / GW);
                int sy = y0 + Math.min(bh - 1, gy * bh / GH);
                out[gy * GW + gx] = dark[sx][sy];
            }
        }
        return out;
    }

    /** 渲染单字符模板（同字体，指定字号与旋转），返回 GW×GH 掩码。 */
    static boolean[] template(char ch, int size, int angle) {
        int canvas = 80;
        BufferedImage img = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, canvas, canvas);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, size));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.rotate(Math.toRadians(angle), canvas / 2.0, canvas / 2.0);
        g.drawString(String.valueOf(ch), canvas / 2 - size / 3, canvas / 2 + size / 3);
        g.dispose();
        boolean[][] dark = new boolean[canvas][canvas];
        int minX = canvas, maxX = -1, minY = canvas, maxY = -1;
        for (int x = 0; x < canvas; x++) {
            for (int y = 0; y < canvas; y++) {
                int lum = img.getRGB(x, y) & 0xff;
                if (lum < 128) {
                    dark[x][y] = true;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) return null;
        return normalize(dark, minX, minY, maxX, maxY);
    }

    static double iou(boolean[] a, boolean[] b) {
        int inter = 0, union = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] && b[i]) inter++;
            if (a[i] || b[i]) union++;
        }
        return union == 0 ? 0 : (double) inter / union;
    }
}
