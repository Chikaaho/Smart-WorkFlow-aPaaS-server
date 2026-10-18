import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 验证码逐字形增强：二值化后按渲染槽位（14 + (W-24)/4 * i）裁剪 4 个字形，
 * 各自最近邻放大 10x，横向拼版输出单图，便于人工逐字识读。
 */
public class p64p2_glyphs {
    public static void main(String[] args) throws Exception {
        String in = args[0];
        String out = args.length > 1 ? args[1] : in.replace(".png", "-glyphs.png");
        int threshold = args.length > 2 ? Integer.parseInt(args[2]) : 150;
        BufferedImage src = ImageIO.read(new File(in));
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage bin = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                bin.setRGB(x, y, lum < threshold ? 0x000000 : 0xffffff);
            }
        }
        int slots = 4;
        int slotWidth = (w - 24) / slots;
        int factor = 10;
        int pad = 4;
        int cw = slotWidth + pad * 2, ch = h + pad * 2;
        int W = cw * factor * slots + (slots - 1) * 12;
        int H = ch * factor;
        BufferedImage dst = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = dst.createGraphics();
        gr.setColor(Color.WHITE);
        gr.fillRect(0, 0, W, H);
        gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (int i = 0; i < slots; i++) {
            int x0 = Math.max(0, 14 + slotWidth * i - pad);
            int x1 = Math.min(w, 14 + slotWidth * (i + 1) + pad);
            BufferedImage crop = bin.getSubimage(x0, 0, x1 - x0, h);
            int dx = i * (cw * factor + 12);
            gr.drawImage(crop, dx, pad * factor, crop.getWidth() * factor, crop.getHeight() * factor, null);
            gr.setColor(Color.LIGHT_GRAY);
            gr.drawLine(dx - 6, 0, dx - 6, H);
        }
        gr.dispose();
        ImageIO.write(dst, "png", new File(out));
        System.out.println("glyph montage -> " + out + " (" + W + "x" + H + ")");
    }
}
