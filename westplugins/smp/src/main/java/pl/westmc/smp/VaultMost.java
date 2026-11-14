package pl.westmc.smp;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.util.List;

/** Laczy WestSMP z Vault (ladowane tylko, gdy Vault jest na serwerze). */
final class VaultMost {
    private VaultMost() { }

    static void podepnij(WestSMP pl, Kasa k) {
        RegisteredServiceProvider<Economy> r = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (r != null && !r.getPlugin().equals(pl)) {
            Economy e = r.getProvider();
            k.obca = new Kasa.Obca() {
                public double saldo(OfflinePlayer p) { return e.getBalance(p); }
                public boolean zabierz(OfflinePlayer p, double v) { return e.withdrawPlayer(p, v).transactionSuccess(); }
                public void dodaj(OfflinePlayer p, double v) { e.depositPlayer(p, v); }
            };
            pl.getLogger().info("Ekonomia: " + e.getName() + " (przez Vault).");
            return;
        }
        Bukkit.getServicesManager().register(Economy.class, new Vaultowa(pl, k), pl, ServicePriority.Normal);
        pl.getLogger().info("Ekonomia: wlasna WestMC, zarejestrowana w Vault.");
    }

    static final class Vaultowa extends AbstractEconomy {
        private final WestSMP pl; private final Kasa k;
        Vaultowa(WestSMP pl, Kasa k) { this.pl = pl; this.k = k; }

        @Override public boolean isEnabled() { return pl.isEnabled(); }
        @Override public String getName() { return "WestMC"; }
        @Override public boolean hasBankSupport() { return false; }
        @Override public int fractionalDigits() { return 2; }
        @Override public String format(double v) { return k.fmt(v); }
        @Override public String currencyNamePlural() { return pl.getConfig().getString("kasa.symbol", "$"); }
        @Override public String currencyNameSingular() { return currencyNamePlural(); }
        private OfflinePlayer op(String n) { return Bukkit.getOfflinePlayer(n); }
        @Override public boolean hasAccount(String n) { return true; }
        @Override public boolean hasAccount(String n, String w) { return true; }
        @Override public double getBalance(String n) { return k.saldo(op(n)); }
        @Override public double getBalance(String n, String w) { return getBalance(n); }
        @Override public boolean has(String n, double v) { return k.ma(op(n), v); }
        @Override public boolean has(String n, String w, double v) { return has(n, v); }
        @Override public EconomyResponse withdrawPlayer(String n, double v) {
            boolean ok = k.zabierz(op(n), v);
            return new EconomyResponse(v, k.saldo(op(n)), ok ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, ok ? "" : "Za malo pieniedzy");
        }
        @Override public EconomyResponse withdrawPlayer(String n, String w, double v) { return withdrawPlayer(n, v); }
        @Override public EconomyResponse depositPlayer(String n, double v) { k.dodaj(op(n), v); return new EconomyResponse(v, k.saldo(op(n)), EconomyResponse.ResponseType.SUCCESS, ""); }
        @Override public EconomyResponse depositPlayer(String n, String w, double v) { return depositPlayer(n, v); }
        private EconomyResponse brak() { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Brak bankow"); }
        @Override public EconomyResponse createBank(String a, String b) { return brak(); }
        @Override public EconomyResponse deleteBank(String a) { return brak(); }
        @Override public EconomyResponse bankBalance(String a) { return brak(); }
        @Override public EconomyResponse bankHas(String a, double b) { return brak(); }
        @Override public EconomyResponse bankWithdraw(String a, double b) { return brak(); }
        @Override public EconomyResponse bankDeposit(String a, double b) { return brak(); }
        @Override public EconomyResponse isBankOwner(String a, String b) { return brak(); }
        @Override public EconomyResponse isBankMember(String a, String b) { return brak(); }
        @Override public List<String> getBanks() { return List.of(); }
        @Override public boolean createPlayerAccount(String n) { return true; }
        @Override public boolean createPlayerAccount(String n, String w) { return true; }
    
    }
}
