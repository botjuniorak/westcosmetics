package pl.westmc.smp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class WestSMP extends JavaPlugin {
    Szafa szafa;
    Eventowki eventowki;
    Teleporty teleporty;
    Npc npc;
    Kasa kasa;
    Bank bank;
    Narzedzia narzedzia;
    Sklepy sklepy;
    Wymiana wymiana;
    Ksiegi ksiegi;
    Klany klany;
    Ustawka ustawka;
    Hud hud;
    Topki topki;
    Eventy eventy;
    Rynek rynek;
    Ceny ceny;
    Sprzedaz sprzedaz;
    SkrzynieModele skrzynieModele;
    EventowkiMoc eventowkiMoc;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // migracja: stare rangi tekstowe (0.x) -> rangi HD z paczki
        if (getConfig().getString("czat.rangi.admin", "").contains("ADMIN")) {
            getConfig().set("czat.rangi", null);
            getConfig().set("czat.format", "{KLAN}{RANGA}&f{GRACZ} &8» &7");
        }
        if (getConfig().getInt("skrzynie.wersja-nagrod", 1) < 2 && getConfig().getDefaults() != null) {
            for (String t : new String[]{"zwykla", "epicka", "legendarna"}) getConfig().set("skrzynie." + t + ".nagrody", getConfig().getDefaults().getList("skrzynie." + t + ".nagrody"));
            getConfig().set("skrzynie.wersja-nagrod", 2);
        }
        if (getConfig().getInt("topki.zasieg", 128) == 48) getConfig().set("topki.zasieg", 128);
        if (getConfig().getDouble("eventowki.czarna_dziura.promien", 5) == 8.0) getConfig().set("eventowki.czarna_dziura.promien", 5.0);
        if (getConfig().getDouble("eventowki.hydro.promien", 5) == 3.0) { getConfig().set("eventowki.hydro.promien", 5.0); getConfig().set("eventowki.hydro.sekund", 10); }
        if (getConfig().getDouble("skrzynie.wielkosc-modelu", 1.7) == 1.0) getConfig().set("skrzynie.wielkosc-modelu", 1.7);
        getConfig().options().copyDefaults(true);
        saveConfig();
        kasa = new Kasa(this);
        kasa.start();
        for (String c : new String[]{"money", "pay", "eco"}) getCommand(c).setExecutor(kasa);
        bank = new Bank(this);
        getServer().getPluginManager().registerEvents(bank, this);
        for (String c : new String[]{"portfel", "czek", "coinflip", "czas"}) getCommand(c).setExecutor(bank);
        bank.start();
        szafa = new Szafa(this);
        getServer().getPluginManager().registerEvents(szafa, this);
        szafa.start();
        eventowki = new Eventowki(this);
        getServer().getPluginManager().registerEvents(eventowki, this);
        getCommand("eventowka").setExecutor(eventowki);
        teleporty = new Teleporty(this);
        getServer().getPluginManager().registerEvents(teleporty, this);
        for (String c : new String[]{"spawn", "end", "nether", "afk", "warp", "setwarp", "delwarp", "teleporty"}) getCommand(c).setExecutor(teleporty);
        npc = new Npc(this);
        getServer().getPluginManager().registerEvents(npc, this);
        getCommand("npc").setExecutor(npc);
        npc.start();
        narzedzia = new Narzedzia(this);
        getServer().getPluginManager().registerEvents(narzedzia, this);
        for (String c : new String[]{"vanish", "adminchat", "helpop", "gm", "god", "fly", "heal", "feed", "rtp", "kod", "keyall", "czyszczenie", "strefa"}) getCommand(c).setExecutor(narzedzia);
        narzedzia.start();
        sklepy = new Sklepy(this);
        getServer().getPluginManager().registerEvents(sklepy, this);
        for (String c : new String[]{"sklep", "skrzynie", "klucz", "zestawy", "nagrody", "kowal"}) getCommand(c).setExecutor(sklepy);
        wymiana = new Wymiana(this);
        getServer().getPluginManager().registerEvents(wymiana, this);
        getCommand("wymiana").setExecutor(wymiana);
        ksiegi = new Ksiegi(this);
        getServer().getPluginManager().registerEvents(ksiegi, this);
        getCommand("kowadlo").setExecutor(ksiegi); getCommand("ksiega").setExecutor(ksiegi);
        ksiegi.start();
        klany = new Klany(this);
        getServer().getPluginManager().registerEvents(klany, this);
        getCommand("klan").setExecutor(klany);
        ustawka = new Ustawka(this);
        getServer().getPluginManager().registerEvents(ustawka, this);
        getCommand("ustawka").setExecutor(ustawka);
        hud = new Hud(this);
        getServer().getPluginManager().registerEvents(hud, this);
        hud.start();
        topki = new Topki(this);
        getServer().getPluginManager().registerEvents(topki, this);
        getCommand("topki").setExecutor(topki); getCommand("topki").setTabCompleter(topki);
        topki.sprzatnij();
        topki.start();
        eventy = new Eventy(this);
        getServer().getPluginManager().registerEvents(eventy, this);
        getCommand("smok").setExecutor(eventy); getCommand("minievent").setExecutor(eventy);
        eventy.start();
        rynek = new Rynek(this);
        getServer().getPluginManager().registerEvents(rynek, this);
        getCommand("rynek").setExecutor(rynek);
        rynek.start();
        ceny = new Ceny(this);
        ceny.wczytaj();
        sprzedaz = new Sprzedaz(this);
        getServer().getPluginManager().registerEvents(sprzedaz, this);
        getCommand("sell").setExecutor(sprzedaz); getCommand("cena").setExecutor(sprzedaz);
        eventowkiMoc = new EventowkiMoc(this);
        getServer().getPluginManager().registerEvents(eventowkiMoc, this);
        skrzynieModele = new SkrzynieModele(this);
        skrzynieModele.start();
        getServer().getPluginManager().registerEvents(skrzynieModele, this);
        if (getServer().getPluginManager().getPlugin("packetevents") != null) {
            try { CenyPakiety.podepnij(this); getLogger().info("Ceny w opisie itemow: wlaczone (PacketEvents)."); }
            catch (Throwable t) { getLogger().warning("PacketEvents: " + t); }
        } else getLogger().info("Brak PacketEvents - ceny tylko pod /cena i w /sell.");
    }

    @Override
    public void onDisable() { if (szafa != null) szafa.stop(); if (npc != null) npc.stop(); if (bank != null) bank.stop(); if (kasa != null) kasa.zapisz(); if (narzedzia != null) narzedzia.stop(); if (hud != null) hud.stop(); if (topki != null) topki.stop(); if (eventy != null) eventy.stop(); if (rynek != null) rynek.stop(); if (sprzedaz != null) sprzedaz.stop(); if (skrzynieModele != null) skrzynieModele.stop(); if (eventowkiMoc != null) eventowkiMoc.stop(); }

    /** Zasieg widzenia bytow (kratki z configu) -> view_range dla display entity. */
    float zasieg(String k, int dom) { return Math.max(16, getConfig().getInt("zasieg-widzenia." + k, dom)) / 64f; }

    static String c(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '&' && i + 7 < s.length() + 0 && s.charAt(i + 1) == '#' && i + 8 <= s.length()) {
                String hex = s.substring(i + 2, i + 8);
                b.append('§').append('x');
                for (char h : hex.toCharArray()) b.append('§').append(h);
                i += 7;
            } else if (ch == '&' && i + 1 < s.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                b.append('§');
            } else b.append(ch);
        }
        return b.toString();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Tylko w grze."); return true; }
        String tab = switch (label.toLowerCase()) { case "kostiumy" -> "kostiumy"; case "papugi" -> "papugi"; default -> "pety"; };
        szafa.open(p, tab, 0);
        return true;
    }
}
