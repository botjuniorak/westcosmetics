package pl.westmc.cosmetics;

import java.util.ArrayList;
import java.util.List;

/** Lista kosmetykow. slot: SKRZYDLA / GLOWA / AUREOLA (jeden z kazdego slotu na raz). */
public enum Cosmetic {
    SKRZYDLA_ANIELSKIE("skrzydla_anielskie", Slot.SKRZYDLA, "skrzydla_anielskie"),
    SKRZYDLA_DEMONA("skrzydla_demona", Slot.SKRZYDLA, "skrzydla_demona"),
    SKRZYDLA_WEST("skrzydla_west", Slot.SKRZYDLA, "skrzydla_west"),
    CYLINDER("cylinder", Slot.GLOWA, "cylinder"),
    KORONA("korona", Slot.GLOWA, "korona"),
    AUREOLA("aureola", Slot.AUREOLA, "aureola");

    enum Slot { SKRZYDLA, GLOWA, AUREOLA }

    final String id;
    final Slot slot;
    final String texture;

    Cosmetic(String id, Slot slot, String texture) {
        this.id = id;
        this.slot = slot;
        this.texture = texture;
    }

    static Cosmetic byId(String id) {
        for (Cosmetic c : values()) if (c.id.equals(id)) return c;
        return null;
    }

    static List<String> ids() {
        List<String> l = new ArrayList<>();
        for (Cosmetic c : values()) l.add(c.id);
        return l;
    }
}
