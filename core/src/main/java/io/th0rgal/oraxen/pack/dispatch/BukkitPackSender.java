package io.th0rgal.oraxen.pack.dispatch;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.config.Settings;
import io.th0rgal.oraxen.pack.upload.hosts.HostingProvider;
import io.th0rgal.oraxen.utils.AdventureUtils;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.entity.Player;

public class BukkitPackSender extends PackSender {

    private static final String prompt = Settings.SEND_PACK_PROMPT.toString();
    private static final boolean mandatory = Settings.SEND_PACK_MANDATORY.toBool();

    public BukkitPackSender(HostingProvider hostingProvider) {
        super(hostingProvider);
    }

    @Override
    public void sendPack(Player player) {
        OraxenPlugin.get().getLogger().info("Sending '" + hostingProvider.getPackURL() + "' to player: " + player.getName());
        if (VersionUtil.atOrAbove("1.20.3")) {
            if (VersionUtil.isPaperServer()) player.setResourcePack(hostingProvider.getPackUUID(), hostingProvider.getPackURL(), hostingProvider.getSHA1(), AdventureUtils.MINI_MESSAGE.deserialize(prompt), mandatory);
            else player.setResourcePack(hostingProvider.getPackUUID(), hostingProvider.getPackURL(), hostingProvider.getSHA1(), AdventureUtils.parseLegacy(prompt), mandatory);
        }
        else if (VersionUtil.isPaperServer()) player.setResourcePack(hostingProvider.getPackURL(), hostingProvider.getSHA1(), AdventureUtils.MINI_MESSAGE.deserialize(prompt), mandatory);
        else player.setResourcePack(hostingProvider.getPackURL(), hostingProvider.getSHA1(), AdventureUtils.parseLegacy(prompt), mandatory);
    }
}
