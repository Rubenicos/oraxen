package io.th0rgal.oraxen.utils;

import com.comphenix.protocol.ProtocolLibrary;
import com.google.common.base.Suppliers;
import com.viaversion.viaversion.api.Via;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.function.Function;
import java.util.function.Supplier;

public class PlayerProtocol {

    @SuppressWarnings("unchecked")
    private static final Supplier<Function<Player, Integer>> PROVIDER = Suppliers.memoize(() -> {
        if (Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) {
            try {
                Class.forName("com.viaversion.viaversion.api.Via");
                return player -> Via.getAPI().getPlayerVersion(player);
            } catch (ClassNotFoundException ignored) { }
        }
        return player -> ProtocolLibrary.getProtocolManager().getProtocolVersion(player);
    });

    public static int of(Player player) {
        if (player == null) {
            return 0;
        }
        final int result = PROVIDER.get().apply(player);
        return result;
    }
}
