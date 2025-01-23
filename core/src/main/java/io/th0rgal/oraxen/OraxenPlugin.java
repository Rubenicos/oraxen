package io.th0rgal.oraxen;

import com.comphenix.protocol.ProtocolLibrary;
import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPIBukkitConfig;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.api.events.OraxenItemsLoadedEvent;
import io.th0rgal.oraxen.commands.CommandsManager;
import io.th0rgal.oraxen.compatibilities.CompatibilitiesManager;
import io.th0rgal.oraxen.config.*;
import io.th0rgal.oraxen.font.FontManager;
import io.th0rgal.oraxen.font.packets.InventoryPacketListener;
import io.th0rgal.oraxen.font.packets.TitlePacketListener;
import io.th0rgal.oraxen.hud.HudManager;
import io.th0rgal.oraxen.items.ItemUpdater;
import io.th0rgal.oraxen.mechanics.MechanicsManager;
import io.th0rgal.oraxen.mechanics.provided.gameplay.furniture.FurnitureFactory;
import io.th0rgal.oraxen.nms.GlyphHandlers;
import io.th0rgal.oraxen.nms.NMSHandlers;
import io.th0rgal.oraxen.pack.dispatch.PackSender;
import io.th0rgal.oraxen.pack.generation.ResourcePack;
import io.th0rgal.oraxen.pack.upload.UploadManager;
import io.th0rgal.oraxen.recipes.RecipesManager;
import io.th0rgal.oraxen.sound.SoundManager;
import io.th0rgal.oraxen.utils.*;
import io.th0rgal.oraxen.utils.actions.ClickActionManager;
import io.th0rgal.oraxen.utils.armorequipevent.ArmorEquipEvent;
import io.th0rgal.oraxen.utils.breaker.BreakerSystem;
import io.th0rgal.oraxen.utils.customarmor.CustomArmorListener;
import io.th0rgal.oraxen.utils.inventories.InvManager;
import io.th0rgal.oraxen.utils.logs.Logs;
import io.th0rgal.protectionlib.ProtectionLib;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarFile;

public class OraxenPlugin extends JavaPlugin implements Listener {

    private static OraxenPlugin oraxen;
    private ConfigsManager configsManager;
    private ResourcesManager resourceManager;
    private BukkitAudiences audience;
    private UploadManager uploadManager;
    private FontManager fontManager;
    private HudManager hudManager;
    private SoundManager soundManager;
    private InvManager invManager;
    private List<ResourcePack> resourcePacks = new ArrayList<>();
    private ClickActionManager clickActionManager;
    public static boolean supportsDisplayEntities;

    public OraxenPlugin() {
        oraxen = this;
    }

    public static OraxenPlugin get() {
        return oraxen;
    }

    @Nullable
    public static JarFile getJarFile() {
        try {
            return new JarFile(oraxen.getFile());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void onLoad() {
        CommandAPI.onLoad(new CommandAPIBukkitConfig(this).silentLogs(true).skipReloadDatapacks(true));
    }

    @Override
    public void onEnable() {
        CommandAPI.onEnable();
        ProtectionLib.init(this);
        audience = BukkitAudiences.create(this);
        clickActionManager = new ClickActionManager(this);
        supportsDisplayEntities = VersionUtil.atOrAbove("1.19.4");
        reloadConfigs();
        ProtectionLib.setDebug(Settings.DEBUG.toBool());

        if (Settings.KEEP_UP_TO_DATE.toBool())
            new SettingsUpdater().handleSettingsUpdate();
        if (PluginUtils.isEnabled("ProtocolLib")) {
            new BreakerSystem().registerListener();
            if (Settings.FORMAT_INVENTORY_TITLES.toBool())
                ProtocolLibrary.getProtocolManager().addPacketListener(new InventoryPacketListener());
            ProtocolLibrary.getProtocolManager().addPacketListener(new TitlePacketListener());
        } else Logs.logWarning("ProtocolLib is not on your server, some features will not work");
        Bukkit.getPluginManager().registerEvents(new CustomArmorListener(), this);
        NMSHandlers.setup();


        uploadManager = new UploadManager(this);
        resourcePacks.add(new ResourcePack(ResourcePack.V_1_21_3));
        resourcePacks.add(new ResourcePack(Settings.SEND_PACK_MIN_PROTOCOL.toInt()));
        MechanicsManager.registerNativeMechanics();
        //CustomBlockData.registerListener(this); //Handle this manually
        hudManager = new HudManager(configsManager);
        fontManager = new FontManager(configsManager);
        soundManager = new SoundManager(configsManager.getSound());
        OraxenItems.loadItems();
        fontManager.registerEvents();
        fontManager.verifyRequired(); // Verify the required glyph is there
        hudManager.registerEvents();
        hudManager.registerTask();
        hudManager.parsedHudDisplays = hudManager.generateHudDisplays();
        Bukkit.getPluginManager().registerEvents(new ItemUpdater(), this);
        resourcePack(pack -> pack.generate(false));
        Bukkit.getPluginManager().registerEvents(this, this);
        RecipesManager.load(this);
        invManager = new InvManager();
        if (!VersionUtil.atOrAbove("1.21.2")) ArmorEquipEvent.registerListener(this);
        new CommandsManager().loadCommands();
        postLoading();
        try {
            Message.PLUGIN_LOADED.log(AdventureUtils.tagResolver("os", OS.getOs().getPlatformName()));
        } catch (Exception ignore) {
        }
        CompatibilitiesManager.enableNativeCompatibilities();
        if (VersionUtil.isCompiled()) NoticeUtils.compileNotice();
        if (VersionUtil.isLeaked()) NoticeUtils.leakNotice();
    }

    private void postLoading() {
        new Metrics(this, 5371);
        new LU().l();
        Bukkit.getScheduler().runTask(this, () ->
                Bukkit.getPluginManager().callEvent(new OraxenItemsLoadedEvent()));
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Plugin) this);
        FurnitureFactory.unregisterEvolution();
        for (Player player : Bukkit.getOnlinePlayers())
            if (GlyphHandlers.isNms()) NMSHandlers.getHandler().glyphHandler().uninject(player);

        CompatibilitiesManager.disableCompatibilities();
        CommandAPI.onDisable();
        Message.PLUGIN_UNLOADED.log();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerConnect(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        final PackSender sender = uploadManager.getSenderOrNull(player);
        if (sender == null) {
            return;
        }
        if (Settings.SEND_JOIN_MESSAGE.toBool()) sender.sendWelcomeMessage(player, true);
        if (!Settings.SEND_PACK.toBool()) return;
        int delay = (int) Settings.SEND_PACK_DELAY.getValue();
        if (delay <= 0) sender.sendPack(player);
        else Bukkit.getScheduler().runTaskLaterAsynchronously(OraxenPlugin.get(), () ->
                sender.sendPack(player), delay * 20L);
    }

    public ResourcesManager getResourceManager() {
        return resourceManager;
    }

    public BukkitAudiences getAudience() {
        return audience;
    }

    public void reloadConfigs() {
        configsManager = new ConfigsManager(this);
        configsManager.validatesConfig();
        resourceManager = new ResourcesManager(this);
    }

    public ConfigsManager getConfigsManager() {
        return configsManager;
    }

    public UploadManager getUploadManager() {
        return uploadManager;
    }

    public FontManager getFontManager() {
        return fontManager;
    }

    public void setFontManager(final FontManager fontManager) {
        this.fontManager.unregisterEvents();
        this.fontManager = fontManager;
        fontManager.registerEvents();
    }

    public HudManager getHudManager() {
        return hudManager;
    }

    public void setHudManager(final HudManager hudManager) {
        this.hudManager.unregisterEvents();
        this.hudManager = hudManager;
        hudManager.registerEvents();
    }

    public SoundManager getSoundManager() {
        return soundManager;
    }

    public void setSoundManager(final SoundManager soundManager) {
        this.soundManager = soundManager;
    }

    public InvManager getInvManager() {
        return invManager;
    }

    public void resourcePack(final Consumer<ResourcePack> consumer) {
        for (ResourcePack resourcePack : resourcePacks) {
            consumer.accept(resourcePack);
        }
    }

    public ResourcePack getResourcePack(int protocol) {
        for (ResourcePack pack : resourcePacks) {
            if (protocol >= pack.getProtocol()) {
                return pack;
            }
        }
        throw new IllegalArgumentException();
    }

    public List<ResourcePack> getResourcePacks() {
        return resourcePacks;
    }

    public ClickActionManager getClickActionManager() {
        return clickActionManager;
    }
}
