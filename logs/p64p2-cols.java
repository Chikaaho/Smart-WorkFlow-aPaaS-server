import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 验证码字形连通域 x 范围分析（核对槽位映射，避免误读顺序/重叠）。 */
public class p64p2_cols {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        int w = src.getWidth(), h = src.getHeight();
        int threshold = args.length > 1 ? Integer.parseInt(args[1]) : 150;
        boolean[] colDark = new boolean[w];
        for (int x = 0; x < w; x++) {
            int count = 0;
            for (int y = 0; y < h; y++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                if (lum < threshold) count++;
            }
            colDark[x] = count >= 3; // 至少3个暗像素才算字形列（滤掉干扰点/线）
        }
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int x = 0; x < w; x++) {
            if (colDark[x] && start < 0) start = x;
            if (!colDark[x] && start >= 0) { runs.add(new int[]{start, x - 1}); start = -1; }
        }
        if (start >= 0) runs.add(new int[]{start, w - 1});
        int slotWidth = (w - 24) / 4;
        System.out.println("image " + w + "x" + h + " slotWidth=" + slotWidth + " slots@ " + 14 + "," + (14 + slotWidth) + "," + (14 + 2 * slotWidth) + "," + (14 + 3 * slotWidth));
        for (int[] run : runs) {
            if (run[1] - run[0] < 2) continue;
            int center = (run[0] + run[1]) / 2;
            int slot = center < 14 + slotWidth ? 1 : center < 14 + 2 * slotWidth ? 2 : center < 14 + 3 * slotWidth ? 3 : 4;
            System.out.println("run x=[" + run[0] + "," + run[1] + "] width=" + (run[1] - run[0] + 1) + " center=" + center + " -> slot " + slot);
        }
    }
}
