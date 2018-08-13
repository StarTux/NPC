package com.cavetale.npc;

import java.util.List;
import lombok.Data;

@Data
public final class PlayerSkin {
    public final String texture;
    public final String signature;
    public final List<String> tags;
}
