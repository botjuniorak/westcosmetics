package pl.westmc.smp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Cena w opisie przedmiotu - TYLKO na ekranie gracza (pakiety przez PacketEvents), sam przedmiot sie nie zmienia.
 * Ladowane tylko gdy na serwerze jest plugin PacketEvents. W trybie kreatywnym wylaczone (gra zapisalaby dopisek na stale).
 */
final class CenyPakiety {
    private CenyPakiety() { }

    static void podepnij(WestSMP pl) {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(PacketSendEvent e) {
                if (!pl.getConfig().getBoolean("sprzedaz.cena-w-opisie", true)) return;
                if (!(e.getPlayer() instanceof Player p) || p.getGameMode() == GameMode.CREATIVE) return;
                try {
                    if (e.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                        WrapperPlayServerSetSlot w = new WrapperPlayServerSetSlot(e);
                        com.github.retrooper.packetevents.protocol.item.ItemStack n = zmien(pl, w.getItem());
                        if (n != null) { w.setItem(n); e.markForReEncode(true); }
                    } else if (e.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                        WrapperPlayServerWindowItems w = new WrapperPlayServerWindowItems(e);
                        List<com.github.retrooper.packetevents.protocol.item.ItemStack> l = new ArrayList<>(w.getItems());
                        boolean zm = false;
                        for (int i = 0; i < l.size(); i++) { var n = zmien(pl, l.get(i)); if (n != null) { l.set(i, n); zm = true; } }
                        if (zm) { w.setItems(l); e.markForReEncode(true); }
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    private static com.github.retrooper.packetevents.protocol.item.ItemStack zmien(WestSMP pl, com.github.retrooper.packetevents.protocol.item.ItemStack pe) {
        if (pe == null || pe.isEmpty()) return null;
        ItemStack it = SpigotConversionUtil.toBukkitItemStack(pe);
        if (it == null || it.getType().isAir()) return null;
        ItemMeta m = it.getItemMeta();
        if (m == null || m.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES) || m.isHideTooltip()) return null;   // ikony menu
        double c = pl.ceny.cena(it);
        if (c <= 0) return null;
        List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        lore.add(WestSMP.c(it.getAmount() > 1
            ? "&7Wartosc: &#FFC93D" + Ceny.fmt(c / it.getAmount()) + " &8(x" + it.getAmount() + ": &#FFC93D" + Ceny.fmt(c) + "&8)"
            : "&7Wartosc: &#FFC93D" + Ceny.fmt(c)));
        m.setLore(lore);
        it.setItemMeta(m);
        return SpigotConversionUtil.fromBukkitItemStack(it);
    }
}
