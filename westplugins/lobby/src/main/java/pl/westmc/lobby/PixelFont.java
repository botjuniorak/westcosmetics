package pl.westmc.lobby;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/** Pikselowa czcionka 5x7 (jak na banerach) do dopisywania tekstu na mapach. */
final class PixelFont {
    private static final Map<String, String[]> G = new HashMap<>();
    static {
        G.put("A", new String[]{"01110","10001","10001","11111","10001","10001","10001"});
        G.put("B", new String[]{"11110","10001","10001","11110","10001","10001","11110"});
        G.put("C", new String[]{"01111","10000","10000","10000","10000","10000","01111"});
        G.put("D", new String[]{"11110","10001","10001","10001","10001","10001","11110"});
        G.put("E", new String[]{"11111","10000","10000","11110","10000","10000","11111"});
        G.put("F", new String[]{"11111","10000","10000","11110","10000","10000","10000"});
        G.put("G", new String[]{"01111","10000","10000","10011","10001","10001","01111"});
        G.put("H", new String[]{"10001","10001","10001","11111","10001","10001","10001"});
        G.put("I", new String[]{"111","010","010","010","010","010","111"});
        G.put("J", new String[]{"00111","00001","00001","00001","10001","10001","01110"});
        G.put("K", new String[]{"10001","10010","10100","11000","10100","10010","10001"});
        G.put("L", new String[]{"10000","10000","10000","10000","10000","10000","11111"});
        G.put("M", new String[]{"10001","11011","10101","10101","10001","10001","10001"});
        G.put("N", new String[]{"10001","11001","10101","10011","10001","10001","10001"});
        G.put("O", new String[]{"01110","10001","10001","10001","10001","10001","01110"});
        G.put("Ó", new String[]{"00010","01110","10001","10001","10001","10001","01110"});
        G.put("P", new String[]{"11110","10001","10001","11110","10000","10000","10000"});
        G.put("R", new String[]{"11110","10001","10001","11110","10100","10010","10001"});
        G.put("S", new String[]{"01111","10000","10000","01110","00001","00001","11110"});
        G.put("T", new String[]{"11111","00100","00100","00100","00100","00100","00100"});
        G.put("U", new String[]{"10001","10001","10001","10001","10001","10001","01110"});
        G.put("V", new String[]{"10001","10001","10001","10001","10001","01010","00100"});
        G.put("W", new String[]{"10001","10001","10001","10101","10101","10101","01010"});
        G.put("Y", new String[]{"10001","10001","01010","00100","00100","00100","00100"});
        G.put("Z", new String[]{"11111","00001","00010","00100","01000","10000","11111"});
        G.put("Ę", new String[]{"11111","10000","11110","10000","11111","00010","00001"});
        G.put("0", new String[]{"01110","10011","10101","10101","10101","11001","01110"});
        G.put("1", new String[]{"00100","01100","00100","00100","00100","00100","01110"});
        G.put("2", new String[]{"01110","10001","00001","00110","01000","10000","11111"});
        G.put("3", new String[]{"11110","00001","00001","01110","00001","00001","11110"});
        G.put("4", new String[]{"10010","10010","10010","11111","00010","00010","00010"});
        G.put("5", new String[]{"11111","10000","11110","00001","00001","10001","01110"});
        G.put("6", new String[]{"01110","10000","10000","11110","10001","10001","01110"});
        G.put("7", new String[]{"11111","00001","00010","00100","01000","01000","01000"});
        G.put("8", new String[]{"01110","10001","10001","01110","10001","10001","01110"});
        G.put("9", new String[]{"01110","10001","10001","01111","00001","00001","01110"});
        G.put(":", new String[]{"0","1","1","0","1","1","0"});
        G.put(".", new String[]{"0","0","0","0","0","0","1"});
        G.put(" ", new String[]{"000","000","000","000","000","000","000"});
        G.put("?", new String[]{"01110","10001","00001","00110","00100","00000","00100"});
        G.put("!", new String[]{"1","1","1","1","1","0","1"});
        G.put("/", new String[]{"00001","00010","00010","00100","01000","01000","10000"});
        G.put("-", new String[]{"000","000","000","111","000","000","000"});
        G.put("|", new String[]{"1","1","1","1","1","1","1"});
    }
    private static String[] glyph(char c) {
        String[] g = G.get(String.valueOf(Character.toUpperCase(c)));
        if (g == null) g = G.get(String.valueOf(Character.toUpperCase(c)).replace("Ą","A").replace("Ć","C").replace("Ł","L").replace("Ń","N").replace("Ś","S").replace("Ź","Z").replace("Ż","Z"));
        return g == null ? G.get(" ") : g;
    }
    static int width(String s, int px) {
        int w = 0;
        for (char c : s.toCharArray()) w += (glyph(c)[0].length() + 1) * px;
        return Math.max(0, w - px);
    }
    /** Rysuje tekst wysrodkowany w poziomie na cx, gora na y. */
    static void draw(BufferedImage img, String s, int cx, int y, int px, Color col) {
        int x = cx - width(s, px) / 2, rgb = col.getRGB();
        for (char c : s.toCharArray()) {
            String[] g = glyph(c);
            for (int r = 0; r < g.length; r++)
                for (int k = 0; k < g[r].length(); k++)
                    if (g[r].charAt(k) == '1')
                        for (int a = 0; a < px; a++) for (int b = 0; b < px; b++) {
                            int xx = x + k * px + a, yy = y + r * px + b;
                            if (xx >= 0 && yy >= 0 && xx < img.getWidth() && yy < img.getHeight()) img.setRGB(xx, yy, rgb);
                        }
            x += (g[0].length() + 1) * px;
        }
    }
}
