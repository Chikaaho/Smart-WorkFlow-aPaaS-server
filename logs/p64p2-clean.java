import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 验证码图像清洗：二值化 + 形态学开运算（3x3 腐蚀→膨胀），去除细干扰线/噪点，保留粗字形笔画。
 * 用法：java p64p2_clean.java <in.png> [out.png] [threshold]
 */
public class p64p2_clean {
    public static void main(String[] args) throws Exception {
        String in = args[0];
        String out = args.length > 1 ? args[1] : in.replace(".png", "-clean.png");
        int threshold = args.length > 2 ? Integer.parseInt(args[2]) : 150;
        BufferedImage src = ImageIO.read(new File(in));
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
        boolean[][] eroded = new boolean[w][h];
        for (int x = 1; x < w - 1; x++) {
            for (int y = 1; y < h - 1; y++) {
                boolean all = true;
                for (int dx = -1; dx <= 1 && all; dx++) {
                    for (int dy = -1; dy <= 1 && all; dy++) {
                        if (!dark[x + dx][y + dy]) all = false;
                    }
                }
                eroded[x][y] = all;
            }
        }
        boolean[][] opened = new boolean[w][h];
        for (int x = 1; x < w - 1; x++) {
            for (int y = 1; y < h - 1; y++) {
                boolean any = false;
                for (int dx = -1; dx <= 1 && !any; dx++) {
                    for (int dy = -1; dy <= 1 && !any; dy++) {
                        if (eroded[x + dx][y + dy]) any = true;
                    }
                }
                opened[x][y] = any;
            }
        }
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                dst.setRGB(x, y, opened[x][y] ? 0x000000 : 0xffffff);
            }
        }
        ImageIO.write(dst, "png", new File(out));
        System.out.println("cleaned -> " + out);
    }
}
