package pl.westmc.lobby;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Grafiki HD z paczki (czcionka westmc:lobby). Kazda grafika jest pocieta na kafelki <= 240 px,
 * bo wieksze obrazki gra rysuje jako puste kwadraty. Kafelki skladamy spacjami o ujemnej/dodatniej szerokosci.
 */
final class Glify {
    static final Key FONT = Key.key("westmc", "lobby");
    private static final char[] NEG = {'', '', '', '', '', '', '', '', ''}; // -1..-256
    private static final char[] POS = {'', '', '', '', '', '', '', ''};           // +1..+128

    private Glify() { }

    /** Przesuniecie kursora o n jednostek (ujemne = w lewo). */
    static String spacja(int n) {
        StringBuilder b = new StringBuilder();
        if (n < 0) {
            n = -n;
            for (int i = NEG.length - 1; i >= 0; i--) { int v = 1 << i; while (n >= v) { b.append(NEG[i]); n -= v; } }
        } else {
            for (int i = POS.length - 1; i >= 0; i--) { int v = 1 << i; while (n >= v) { b.append(POS[i]); n -= v; } }
        }
        return b.toString();
    }

    /** Sklada tekst z obrazkow ustawianych w konkretnym miejscu (x liczony od poczatku tekstu). */
    static final class Tytul {
        private final StringBuilder sb = new StringBuilder();
        private int x;

        /** Obrazek o szerokosci w (jednostki) postawiony tak, by jego lewa krawedz byla na x. */
        Tytul obraz(int at, char znak, int w) {
            sb.append(spacja(at - x)).append(znak);
            x = at + w + 1;
            return this;
        }

        Tytul idz(int at) { sb.append(spacja(at - x)); x = at; return this; }

        int x() { return x; }

        String tekst() { return sb.toString(); }
    }

    /** Siatka kafelkow (kolumny x wiersze) o wspolnym poczatku "start". */
    static Tytul siatka(Tytul t, int start, int base, int[] kolumny, int wierszy, int naWiersz) {
        int cx = start;
        for (int c = 0; c < kolumny.length; c++) {
            for (int r = 0; r < wierszy; r++) t.obraz(cx, (char) (base + r * naWiersz + c), kolumny[c]);
            cx += kolumny[c];
        }
        return t;
    }

    static Component lobby(String glify) { return Component.text(glify).font(FONT); }

    /** Tekst z kolorami & / &#RRGGBB -> komponent zwyklej czcionki. */
    static TextComponent kolor(String s) { return LegacyComponentSerializer.legacySection().deserialize(WestLobby.c(s)); }

    // ---------------------------------------------------------------- uklad z paczki (gen_pack.py)
    static final int WYBOR = 0xE000;            // 3 kolumny x 4 wiersze, 60/60/56 x 51
    static final int[] WYBOR_KOL = {60, 60, 56};
    static final char STATUS_ZIELONY = '', STATUS_ZOLTY = '', STATUS_CZERWONY = '';   // 35 x 4
    static final int TABLICA = 0xE020;          // 3 x 3: 60/60/12
    static final int[] TABLICA_KOL = {60, 60, 12};
    static final int TAB_GORA = 0xE030;         // 4 x 2: 60 x (60, 6)
    static final int TAB_DOL = 0xE038;          // 4 x 1: 60 x 22
    static final int[] TAB_KOL = {60, 60, 60, 60};
}
