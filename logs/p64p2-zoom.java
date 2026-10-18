import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 验证码 PNG 增强：亮度阈值二值化（字形 alpha=255 深色保留，干扰线 alpha≈120 被滤除）
 * + 最近邻放大，提高人工识读准确率。
 */
public class p64p2_zoom {
    public static void main(String[] args) throws Exception {
        String in = args[0];
        String out = args.length > 1 ? args[1] : in.replace(".png", "-zoom.png");
        int factor = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        int threshold = args.length > 3 ? Integer.parseInt(args[3]) : 150;
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
        int W = w * factor, H = h * factor;
        BufferedImage dst = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = dst.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        gr.drawImage(bin, 0, 0, W, H, null);
        gr.dispose();
        ImageIO.write(dst, "png", new File(out));
        System.out.println("binarized(threshold=" + threshold + ") + zoomed " + w + "x" + h + " -> " + W + "x" + H + " : " + out);
    }
}
