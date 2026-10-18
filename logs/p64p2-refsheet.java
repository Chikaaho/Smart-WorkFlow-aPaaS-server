import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 生成验证码字符集参考字表：与服务端同字体（MONOSPACED BOLD），供人工比对识读。 */
public class p64p2_refsheet {
    public static void main(String[] args) throws Exception {
        String charset = "23456789abcdefghjkmnpqrstuvwxyz";
        int size = 26;
        int scale = 4;
        int cell = 40;
        int perRow = 8;
        int rows = (charset.length() + perRow - 1) / perRow;
        BufferedImage img = new BufferedImage(cell * perRow, cell * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, size));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        for (int i = 0; i < charset.length(); i++) {
            int cx = (i % perRow) * cell + cell / 2;
            int cy = (i / perRow) * cell + cell / 2 + size / 3;
            String ch = String.valueOf(charset.charAt(i));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(ch, cx - fm.stringWidth(ch) / 2, cy);
        }
        g.dispose();
        BufferedImage big = new BufferedImage(img.getWidth() * scale, img.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = big.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(img, 0, 0, big.getWidth(), big.getHeight(), null);
        g2.dispose();
        String out = args.length > 0 ? args[0] : "E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs/captcha-refsheet.png";
        ImageIO.write(big, "png", new File(out));
        System.out.println("refsheet -> " + out);
    }
}
