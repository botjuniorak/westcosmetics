package pl.westmc.osiagniecia;

/**
 * Tytul okna z teksturami z paczki WestMC: obrazki z fontu ustawiane w pikselach.
 * Poczatek tytulu (x=0) to 8 px od lewej krawedzi okna. Spacje ujemne/dodatnie z paczki: .., ...
 */
final class Tytul {
    private final StringBuilder sb = new StringBuilder("§f");
    private int x;   // gdzie stoi kursor (wzgledem poczatku tytulu)

    static String spacja(int px) {
        StringBuilder s = new StringBuilder();
        int left = Math.abs(px);
        boolean neg = px < 0;
        int[] v = {256, 128, 64, 32, 16, 8, 4, 2, 1};
        for (int i = 0; i < v.length; i++) {
            int n = v[i];
            if (!neg && n == 256) continue;   // dodatnie maja maks 128
            while (left >= n) {
                left -= n;
                s.append(neg ? (char) (0xF809 - i) : (char) (0xF828 - (i - 1)));
            }
        }
        return s.toString();
    }

    /** Kladzie obrazek (znak z paczki) o szerokosci w px tak, by jego lewa krawedz byla w guiX okna. */
    Tytul obraz(int guiX, char znak, int szerokosc) {
        int target = guiX - 8;
        sb.append(spacja(target - x)).append(znak);
        x = target + szerokosc + 1;
        return this;
    }

    /** Wraca na poczatek i dopisuje zwykly tekst tytulu. */
    String tekst(String t) {
        sb.append(spacja(-x));
        x = 0;
        return sb.append(t).toString();
    }
}
