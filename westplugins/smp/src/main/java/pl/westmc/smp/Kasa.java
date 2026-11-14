package pl.westmc.smp;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pieniadze ($). Jesli na serwerze jest juz ekonomia przez Vault (np. EssentialsX) - uzywamy jej.
 * Jesli jest Vault bez ekonomii - rejestrujemy wlasna (WestMC), wiec inne pluginy (sklepy) tez ja widza.
 * Bez Vaulta - dziala sama wewnatrz WestSMP.
 */
final class Kasa implements CommandExecutor {
    private final WestSMP pl;
    private final File plik;
    private final YamlConfiguration dane;
    interface Obca { double saldo(OfflinePlayer p); boolean zabierz(OfflinePlayer p, double v); void dodaj(OfflinePlayer p, double v); }
    Obca obca;   // ekonomia innego pluginu przez Vault (jesli jest)

    Kasa(WestSMP pl) {
        this.pl = pl;
        plik = new File(pl.getDataFolder(), "kasa.yml");
        dane = YamlConfiguration.loadConfiguration(plik);
    }

    void start() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) { pl.getLogger().info("Ekonomia: wlasna WestMC (brak Vault)."); return; }
        try { VaultMost.podepnij(pl, this); } catch (Throwable t) { pl.getLogger().warning("Vault: " + t.getMessage()); }
    }

    void zapisz() { try { dane.save(plik); } catch (Exception e) { pl.getLogger().warning("kasa.yml: " + e.getMessage()); } }

    double saldo(OfflinePlayer p) {
        if (obca != null) return obca.saldo(p);
        return dane.getDouble(p.getUniqueId() + ".saldo", pl.getConfig().getDouble("kasa.start", 0));
    }

    boolean ma(OfflinePlayer p, double ile) { return saldo(p) + 1e-9 >= ile; }

    boolean zabierz(OfflinePlayer p, double ile) {
        if (ile < 0 || !ma(p, ile)) return false;
        if (obca != null) return obca.zabierz(p, ile);
        ustaw(p, saldo(p) - ile); return true;
    }

    void dodaj(OfflinePlayer p, double ile) {
        if (ile <= 0) return;
        if (obca != null) { obca.dodaj(p, ile); return; }
        ustaw(p, saldo(p) + ile);
    }

    private void ustaw(OfflinePlayer p, double v) {
        dane.set(p.getUniqueId() + ".saldo", Math.round(v * 100) / 100.0);
        if (p.getName() != null) dane.set(p.getUniqueId() + ".nick", p.getName());
        zapisz();
    }

    String fmt(double v) {
        String s = String.format(Locale.ROOT, v == Math.floor(v) ? "%,.0f" : "%,.2f", v).replace(',', ' ');
        return s + pl.getConfig().getString("kasa.symbol", "$");
    }

    static double kwota(String s) {
        try {
            s = s.toLowerCase(Locale.ROOT).replace(",", ".").replace("$", "");
            double m = 1;
            if (s.endsWith("k")) { m = 1e3; s = s.substring(0, s.length() - 1); }
            else if (s.endsWith("m")) { m = 1e6; s = s.substring(0, s.length() - 1); }
            double v = Double.parseDouble(s) * m;
            return Double.isFinite(v) && v > 0 ? Math.floor(v * 100) / 100.0 : -1;
        } catch (Exception e) { return -1; }
    }

    // ===================================================================== komendy
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase()) {
            case "money" -> {
                if (a.length > 0 && s.hasPermission("westsmp.admin")) {
                    OfflinePlayer t = Bukkit.getOfflinePlayer(a[0]);
                    s.sendMessage(WestSMP.c("&7Saldo &f" + a[0] + "&7: &#FFC93D&l" + fmt(saldo(t))));
                } else if (s instanceof Player p) p.sendMessage(WestSMP.c("&7Twoje saldo: &#FFC93D&l" + fmt(saldo(p))));
            }
            case "pay" -> {
                if (!(s instanceof Player p)) return true;
                if (a.length < 2) { p.sendMessage(WestSMP.c("&7/pay <gracz> <kwota>  &8(np. 1500, 2k, 1.5m)")); return true; }
                Player t = Bukkit.getPlayerExact(a[0]);
                double v = kwota(a[1]);
                if (t == null || t == p) { p.sendMessage(WestSMP.c("&cGracz musi byc online.")); return true; }
                if (v <= 0) { p.sendMessage(WestSMP.c("&cZla kwota.")); return true; }
                if (!zabierz(p, v)) { p.sendMessage(WestSMP.c("&cNie masz tyle pieniedzy.")); return true; }
                dodaj(t, v);
                p.sendMessage(WestSMP.c("&aWyslano &#FFC93D" + fmt(v) + " &adla &f" + t.getName()));
                t.sendMessage(WestSMP.c("&aDostales &#FFC93D" + fmt(v) + " &aod &f" + p.getName()));
            }
            case "eco" -> {
                if (!s.hasPermission("westsmp.admin") || a.length < 3) { s.sendMessage(WestSMP.c("&7/eco <daj|zabierz|ustaw> <gracz> <kwota>")); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]); double v = kwota(a[2]);
                if (v < 0 && !a[0].equalsIgnoreCase("ustaw")) { s.sendMessage(WestSMP.c("&cZla kwota.")); return true; }
                switch (a[0].toLowerCase()) {
                    case "daj" -> dodaj(t, v);
                    case "zabierz" -> zabierz(t, Math.min(v, saldo(t)));
                    case "ustaw" -> { double cur = saldo(t); double nv = Math.max(0, kwota(a[2]) < 0 ? 0 : kwota(a[2])); if (nv > cur) dodaj(t, nv - cur); else zabierz(t, cur - nv); }
                    default -> { }
                }
                s.sendMessage(WestSMP.c("&aSaldo &f" + a[1] + "&a: &#FFC93D" + fmt(saldo(t))));
            }
            default -> { }
        }
        return true;
    }

    /** Top N do topek (tylko wlasna ekonomia). */
    List<String[]> top(int n) {
        List<String[]> l = new ArrayList<>();
        for (String k : dane.getKeys(false)) l.add(new String[]{dane.getString(k + ".nick", k.substring(0, 8)), String.valueOf(dane.getDouble(k + ".saldo"))});
        l.sort((x, y) -> Double.compare(Double.parseDouble(y[1]), Double.parseDouble(x[1])));
        return l.subList(0, Math.min(n, l.size()));
    }
}
