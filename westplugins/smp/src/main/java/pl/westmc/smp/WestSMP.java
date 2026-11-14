package pl.westmc.smp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class WestSMP extends JavaPlugin {
    Szafa szafa;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        szafa = new Szafa(this);
        getServer().getPluginManager().registerEvents(szafa, this);
        szafa.start();
    }

    @Override
    public void onDisable() { if (szafa != null) szafa.stop(); }

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
