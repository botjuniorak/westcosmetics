package pl.westmc.smp;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Tla menu HD z czcionki westmc:smp (kafelki 3 kolumny: 60/60/56, wiersze po max 60). */
final class Tlo {
    static final Key FONT = Key.key("westmc", "smp");
    static final int SZAFA = 0xE000, TELEPORTY = 0xE020;

    private Tlo() { }

    static String spacja(int n) {
        char[] neg = {'', '', '', '', '', '', '', '', ''};
        char[] pos = {'', '', '', '', '', '', '', ''};
        StringBuilder b = new StringBuilder();
        char[] t = n < 0 ? neg : pos; n = Math.abs(n);
        for (int i = t.length - 1; i >= 0; i--) { int v = 1 << i; while (n >= v) { b.append(t[i]); n -= v; } }
        return b.toString();
    }

    /** Tytul skrzyni = cale tlo; rzedy = ile wierszy kafelkow ma obrazek. */
    static Component tytul(int base, int rzedy) {
        int[] kol = {60, 60, 56};
        StringBuilder b = new StringBuilder(spacja(-8));
        int x = -8;
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < rzedy; r++) {
                b.append((char) (base + r * 3 + c));
                b.append(r < rzedy - 1 ? spacja(-(kol[c] + 1)) : spacja(-1));
            }
            x += kol[c];
        }
        b.append(spacja(-x));
        return Component.text(b.toString()).font(FONT).color(NamedTextColor.WHITE);
    }

    /** Siatka kafelkow ustawiona od x=0 (kolumny o szerokosciach kol, rzedy wierszy, naWiersz znakow na wiersz); konczy na x=szer. */
    static String siatka(int base, int[] kol, int rzedy, int naWiersz, int szer) {
        StringBuilder b = new StringBuilder(); int x = 0, cx = 0;
        for (int c = 0; c < kol.length; c++) {
            for (int r = 0; r < rzedy; r++) { b.append(spacja(cx - x)).append((char) (base + r * naWiersz + c)); x = cx + kol[c] + 1; }
            cx += kol[c];
        }
        b.append(spacja(szer - x));
        return b.toString();
    }

    static Component glify(String s) { return Component.text(s).font(FONT).color(NamedTextColor.WHITE); }
}
