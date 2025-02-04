package io.th0rgal.oraxen.pack.upload;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.api.events.OraxenPackPreUploadEvent;
import io.th0rgal.oraxen.api.events.OraxenPackUploadEvent;
import io.th0rgal.oraxen.config.Message;
import io.th0rgal.oraxen.config.Settings;
import io.th0rgal.oraxen.pack.dispatch.BukkitPackSender;
import io.th0rgal.oraxen.pack.dispatch.PackSender;
import io.th0rgal.oraxen.pack.generation.ResourcePack;
import io.th0rgal.oraxen.pack.receive.PackReceiver;
import io.th0rgal.oraxen.pack.upload.hosts.HostingProvider;
import io.th0rgal.oraxen.pack.upload.hosts.Polymath;
import io.th0rgal.oraxen.utils.AdventureUtils;
import io.th0rgal.oraxen.utils.EventUtils;
import io.th0rgal.oraxen.utils.PlayerProtocol;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.nio.file.ProviderNotFoundException;
import java.util.Locale;
import java.util.TreeMap;

public class UploadManager {

    private static String url;
    private final Plugin plugin;
    private final boolean enabled;
    private final TreeMap<Integer, PackSender> packSenders;
    private PackReceiver receiver;

    public UploadManager(final Plugin plugin) {
        this.plugin = plugin;
        enabled = Settings.UPLOAD.toBool();
        packSenders = new TreeMap<>();
    }

    public HostingProvider getHostingProvider(Player player) {
        return getHostingProvider(PlayerProtocol.of(player));
    }

    public HostingProvider getHostingProvider(int protocol) {
        for (Integer packProtocol : packSenders.descendingKeySet()) {
            if (packProtocol <= protocol) {
                return packSenders.get(packProtocol).getHostingProvider();
            }
        }
        return packSenders.firstEntry().getValue().getHostingProvider();
    }

    public PackSender getSender(Player player) {
        return getSender(PlayerProtocol.of(player));
    }

    public PackSender getSender(int protocol) {
        final PackSender sender = getSenderOrNull(protocol);
        return sender != null ? null : packSenders.firstEntry().getValue();
    }

    public PackSender getSenderOrNull(Player player) {
        return getSenderOrNull(PlayerProtocol.of(player));
    }

    public PackSender getSenderOrNull(int protocol) {
        for (Integer packProtocol : packSenders.descendingKeySet()) {
            if (protocol >= packProtocol) {
                return packSenders.get(packProtocol);
            }
        }
        return null;
    }

    public void uploadAsyncAndSendToPlayers(final ResourcePack resourcePack, final boolean isReload) {
        if (!enabled)
            return;

        if (Settings.RECEIVE_ENABLED.toBool() && receiver == null) {
            receiver = new PackReceiver();
            Bukkit.getPluginManager().registerEvents(receiver, plugin);
        }

        final long time = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(OraxenPlugin.get(), () -> {
            EventUtils.callEvent(new OraxenPackPreUploadEvent());

            Message.PACK_UPLOADING.log();
            PackSender packSender = packSenders.get(resourcePack.getProtocol());
            if (packSender == null) {
                packSender = new BukkitPackSender(createHostingProvider());
                packSenders.put(resourcePack.getProtocol(), packSender);
            }
            HostingProvider hostingProvider = packSender.getHostingProvider();
            if (!hostingProvider.uploadPack(resourcePack.getFile())) {
                Message.PACK_NOT_UPLOADED.log();
                return;
            }

            OraxenPackUploadEvent uploadEvent = new OraxenPackUploadEvent(hostingProvider);
            Bukkit.getScheduler().scheduleSyncDelayedTask(OraxenPlugin.get(), () ->
                    Bukkit.getPluginManager().callEvent(uploadEvent));

            Message.PACK_UPLOADED.log(
                    AdventureUtils.tagResolver("protocol", String.valueOf(resourcePack.getProtocol())),
                    AdventureUtils.tagResolver("url", hostingProvider.getPackURL()),
                    AdventureUtils.tagResolver("delay", String.valueOf(System.currentTimeMillis() - time)));

            if (isReload && !Settings.SEND_ON_RELOAD.toBool()) {
                return;
            }
            if (Settings.SEND_PACK.toBool() || Settings.SEND_JOIN_MESSAGE.toBool()) {
                if (!hostingProvider.getPackURL().equals(url)) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (PlayerProtocol.of(player) >= resourcePack.getProtocol()) {
                            packSender.sendPack(player);
                        }
                    }
                }
                url = hostingProvider.getPackURL();
            }
        });
    }

    private HostingProvider createHostingProvider() {
        HostingProvider provider = switch (Settings.UPLOAD_TYPE.toString().toLowerCase(Locale.ROOT)) {
            case "polymath" -> new Polymath(Settings.POLYMATH_SERVER.toString());
            case "external" -> createExternalProvider();
            default -> null;
        };

        if (provider == null) {
            Logs.logError("Unknown Hosting-Provider type: " + Settings.UPLOAD_TYPE);
            Logs.logError("Polymath will be used instead.");
            provider = new Polymath(Settings.POLYMATH_SERVER.toString());
        }
        return provider;
    }

    private HostingProvider createExternalProvider() {
        final Class<?> target;
        final ConfigurationSection options = (ConfigurationSection) Settings.UPLOAD_OPTIONS.getValue();
        final String klass = options.getString("class");
        if (klass == null)
            throw new ProviderNotFoundException("No provider set.");
        try {
            target = Class.forName(klass);
        } catch (final Exception any) {
            final ProviderNotFoundException error = new ProviderNotFoundException("Provider not found: " + klass);
            error.addSuppressed(any);
            throw error;
        }
        if (!HostingProvider.class.isAssignableFrom(target))
            throw new ProviderNotFoundException(target + " is not a valid HostingProvider.");
        return constructExternalHostingProvider(target, options);
    }

    private HostingProvider constructExternalHostingProvider(final Class<?> target,
                                                             final ConfigurationSection options) {
        Constructor<? extends HostingProvider> constructor = getConstructor(target);

        try {
            return constructor.getParameterCount() == 0 ? constructor.newInstance()
                    : constructor.newInstance(options);
        } catch (final InstantiationException e) {
            throw (ProviderNotFoundException) new ProviderNotFoundException("Cannot alloc instance for " + target)
                    .initCause(e);
        } catch (final IllegalAccessException e) {
            throw (ProviderNotFoundException) new ProviderNotFoundException("Failed to access " + target)
                    .initCause(e);
        } catch (final InvocationTargetException e) {
            throw (ProviderNotFoundException) new ProviderNotFoundException("Exception in allocating instance.")
                    .initCause(e.getCause());
        }
    }

    @NotNull
    @SuppressWarnings("unchecked")
    private static Constructor<? extends HostingProvider> getConstructor(Class<?> target) {
        final Class<? extends HostingProvider> implement = target.asSubclass(HostingProvider.class);
        Constructor<? extends HostingProvider> constructor = null;
        for (final Constructor<?> implementConstructor : implement.getConstructors()) {
            Parameter[] parameters = implementConstructor.getParameters();
            if (parameters.length == 0 || (parameters.length == 1 && parameters[0].getType().equals(ConfigurationSection.class))) {
                constructor = (Constructor<? extends HostingProvider>) implementConstructor;
                break;
            }
        }

        if (constructor == null) throw new ProviderNotFoundException("Invalid provider: " + target);
        return constructor;
    }



}
