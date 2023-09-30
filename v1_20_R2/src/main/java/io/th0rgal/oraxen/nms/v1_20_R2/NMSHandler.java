package io.th0rgal.oraxen.nms.v1_20_R2;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.Attribute;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.config.Settings;
import io.th0rgal.oraxen.font.GlyphTag;
import io.th0rgal.oraxen.nms.NMSHandlers;
import io.th0rgal.oraxen.utils.AdventureUtils;
import io.th0rgal.oraxen.utils.logs.Logs;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_20_R2.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_20_R2.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

public class NMSHandler implements io.th0rgal.oraxen.nms.NMSHandler {

    private final Map<Channel, ChannelHandler> encoder = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Channel, ChannelHandler> decoder = Collections.synchronizedMap(new WeakHashMap<>());


    @Override
    public ItemStack copyItemNBTTags(ItemStack oldItem, ItemStack newItem) {
        CompoundTag oldTag = CraftItemStack.asNMSCopy(oldItem).getOrCreateTag();
        net.minecraft.world.item.ItemStack newNmsItem = CraftItemStack.asNMSCopy(newItem);
        CompoundTag newTag = newNmsItem.getOrCreateTag();
        oldTag.getAllKeys().stream().filter(key -> !vanillaKeys.contains(key)).forEach(key -> newTag.put(key, oldTag.get(key)));
        newNmsItem.setTag(newTag);
        return newNmsItem.asBukkitCopy();
    }

    @Override
    public void setupNmsGlyphs() {
        if (!Settings.NMS_GLYPHS.toBool()) return;
        List<Connection> networkManagers = MinecraftServer.getServer().getConnection().getConnections();
        List<ChannelFuture> channelFutures;

        try {
            Field channelFutureField = ServerConnectionListener.class.getDeclaredField("f");
            channelFutureField.setAccessible(true);

            channelFutures = (List<ChannelFuture>) channelFutureField.get(MinecraftServer.getServer().getConnection());
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e1) {
            channelFutures = new ArrayList<>();
            e1.printStackTrace();
        }

        final List<ChannelFuture> futures = channelFutures;

        // Handle connected channels
        ChannelInitializer<Channel> endInitProtocol = new ChannelInitializer<>() {
            @Override
            protected void initChannel(@NotNull Channel channel) {
                try {
                    // This can take a while, so we need to stop the main thread from interfering
                    synchronized (networkManagers) {
                        // Stop injecting channels
                        channel.eventLoop().submit(() -> inject(channel));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        // This is executed before Minecraft's channel handler
        ChannelInitializer<Channel> beginInitProtocol = new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {
                ChannelHandler handler = null;
                for (Map.Entry<String, ChannelHandler> entry : channel.pipeline()) {
                    if (entry.getValue().getClass().getName().equals("com.viaversion.viaversion.bukkit.handlers.BukkitChannelInitializer")) {
                        handler = entry.getValue();
                    }
                }

                if (handler == null) {
                    channel.pipeline().addLast(endInitProtocol);
                } else {
                    Class<?> clazz = handler.getClass();
                    Method initChannel = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
                    initChannel.setAccessible(true);
                    Field original = clazz.getDeclaredField("original");
                    original.setAccessible(true);
                    ChannelInitializer<Channel> initializer = (ChannelInitializer<Channel>) original.get(handler);
                    ChannelInitializer<Channel> miniInit = new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(@NotNull Channel ch) throws Exception {
                            initChannel.invoke(initializer, ch);

                            inject(ch);
                        }
                    };
                    original.set(handler, miniInit);
                }
            }
        };

        ChannelInboundHandlerAdapter serverChannelHandler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, @NotNull Object msg) {
                // Prepare to initialize ths channel
                ((Channel) msg).pipeline().addFirst(beginInitProtocol);
                ctx.fireChannelRead(msg);
            }
        };

        try {
            bind(futures, serverChannelHandler);
        } catch (IllegalArgumentException ex) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    bind(futures, serverChannelHandler);
                }
            }.runTask(OraxenPlugin.get());
        }
    }

    @Override
    public boolean correctBlockStates(Player player, EquipmentSlot slot, ItemStack itemStack, Block block) {
        BlockHitResult blockHitResult = getBlockHitResult(player, block);
        if (blockHitResult == null) return false;
        InteractionHand hand = slot == EquipmentSlot.HAND ? InteractionHand.MAIN_HAND : slot == EquipmentSlot.OFF_HAND ? InteractionHand.OFF_HAND : null;
        if (hand == null) return false;

        /*if (org.bukkit.Tag.STAIRS.isTagged(itemStack.getType()) || org.bukkit.Tag.SLABS.isTagged(itemStack.getType()))
            BlockHelpers.handleHalfBlocks(block, player);
        else */
        return ((CraftItemStack) itemStack).handle.useOn(new UseOnContext(((CraftPlayer) player).getHandle(), hand, blockHitResult)).consumesAction();

    }

    @Override
    public @Nullable BlockHitResult getBlockHitResult(Player player, Block block) {
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        if (serverPlayer == null) return null;
        Direction direction = serverPlayer.getDirection().getOpposite();
        Location location = player.getEyeLocation();
        return new BlockHitResult(new Vec3(location.getX(), location.getY(), location.getZ()), direction, new BlockPos(block.getX(), block.getY(), block.getZ()), false);
    }


    private void bind(List<ChannelFuture> channelFutures, ChannelInboundHandlerAdapter serverChannelHandler) {
        for (ChannelFuture future : channelFutures) {
            future.channel().pipeline().addFirst(serverChannelHandler);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            inject(player);
        }
    }

    @Override
    public void inject(Player player) {
        if (player == null || !Settings.NMS_GLYPHS.toBool()) return;
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;

        inject(channel);

        for (Map.Entry<String, ChannelHandler> entry : channel.pipeline()) {
            ChannelHandler handler = entry.getValue();
            if (handler instanceof CustomPacketEncoder) {
                ((CustomPacketEncoder) handler).setPlayer(player);
            } else if (handler instanceof CustomPacketDecoder) {
                ((CustomPacketDecoder) handler).setPlayer(player);
            }
        }
    }

    @Override
    public void uninject(Player player) {
        if (player == null || !Settings.NMS_GLYPHS.toBool()) return;
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;

        uninject(channel);
    }

    private void uninject(Channel channel) {
        if (encoder.containsKey(channel)) {
            // Replace our custom packet encoder with the default one that the player had
            ChannelHandler previousHandler = encoder.remove(channel);
            if (previousHandler instanceof PacketEncoder) {
                // PacketEncoder is not shareable, so we can't re-add it back. Instead, we'll have to create a new instance
                channel.pipeline().replace("encoder", "encoder", new PacketEncoder(Connection.ATTRIBUTE_CLIENTBOUND_PROTOCOL));
            } else channel.pipeline().replace("encoder", "encoder", previousHandler);
        }

        if (decoder.containsKey(channel)) {
            ChannelHandler previousHandler = decoder.remove(channel);
            if (previousHandler instanceof PacketDecoder) {
                channel.pipeline().replace("decoder", "decoder", new PacketDecoder(Connection.ATTRIBUTE_SERVERBOUND_PROTOCOL));
            } else {
                channel.pipeline().replace("decoder", "decoder", previousHandler);
            }
        }
    }

    private void inject(Channel channel) {
        if (!encoder.containsKey(channel)) {
            // Replace the vanilla PacketEncoder with our own
            ChannelHandler handler = channel.pipeline().get("encoder");
            if (!(handler instanceof CustomPacketEncoder)) {
                encoder.put(channel, channel.pipeline().replace("encoder", "encoder", new CustomPacketEncoder()));
            }
        }

        if (!decoder.containsKey(channel)) {
            // Replace the vanilla PacketDecoder with our own
            ChannelHandler handler = channel.pipeline().get("decoder");
            if (!(handler instanceof CustomPacketDecoder)) {
                decoder.put(channel, channel.pipeline().replace("decoder", "decoder", new CustomPacketDecoder()));
            }
        }
    }

    private static class CustomDataSerializer extends FriendlyByteBuf {
        private final Supplier<Player> supplier;

        public CustomDataSerializer(Supplier<Player> supplier, ByteBuf bytebuf) {
            super(bytebuf);

            this.supplier = supplier;
        }

        @NotNull
        @Override
        public FriendlyByteBuf writeComponent(@NotNull Component component) {
            return super.writeComponent(AdventureUtils.parseMiniMessage(component, GlyphTag.getResolverForPlayer(supplier.get())));
        }

        @Override
        public @NotNull FriendlyByteBuf writeUtf(@NotNull String string, int maxLength) {
            try {
                JsonElement element = JsonParser.parseString(string);
                if (element.isJsonObject())
                    return super.writeUtf(NMSHandlers.formatJsonString(element.getAsJsonObject()), maxLength);
            } catch (Exception ignored) {

            }

            return super.writeUtf(string, maxLength);
        }

        @NotNull
        @Override
        public FriendlyByteBuf writeNbt(@Nullable Tag tag) {
            /*if (tag != null) {
                transform((CompoundTag) tag, string -> {
                    try {
                        JsonElement element = JsonParser.parseString(string);
                        if (element.isJsonObject())
                            return NMSHandlers.formatJsonString(element.getAsJsonObject());
                    } catch (Exception ignored) {
                    }
                    return string;
                });
            }*/

            return super.writeNbt(tag);
        }

        private void transform(CompoundTag compound, Function<String, String> transformer) {
            for (String key : compound.getAllKeys()) {
                Tag base = compound.get(key);
                if (base instanceof CompoundTag tag) transform(tag, transformer);
                else if (base instanceof ListTag listTag) transform(listTag, transformer);
                else if (base instanceof StringTag) compound.put(key, StringTag.valueOf(transformer.apply(base.getAsString())));
            }
        }

        private void transform(ListTag list, Function<String, String> transformer) {
            for (Tag base : List.copyOf(list)) {
                if (base instanceof CompoundTag tag) transform(tag, transformer);
                else if (base instanceof ListTag listTag) transform(listTag, transformer);
                else if (base instanceof StringTag) {
                    String transformed = transformer.apply(base.getAsString());
                    if (base.getAsString().equals(transformed)) continue;
                    //int index = list.indexOf(base);
                    //list.add(index, StringTag.valueOf(transformed));
                    //list.remove(index + 1);
                }
            }
        }

        @Override
        public @NotNull String readUtf(int i) {
            return NMSHandlers.verifyFor(supplier.get(), super.readUtf(i));
        }

        @Override
        public @Nullable CompoundTag readNbt(@NotNull NbtAccounter nbtAccounter) {
            CompoundTag compound = (CompoundTag) super.readNbt(nbtAccounter);
            if (compound != null) transform(compound, string -> NMSHandlers.verifyFor(supplier.get(), string));

            return compound;
        }
    }

    private static class CustomPacketEncoder extends MessageToByteEncoder<Packet<?>> {
        private final PacketFlow protocolDirection = PacketFlow.CLIENTBOUND;
        private Player player;

        @Override
        protected void encode(ChannelHandlerContext ctx, Packet<?> msg, ByteBuf out) {
            ConnectionProtocol enumProt = ctx.channel().attr(Connection.ATTRIBUTE_SERVERBOUND_PROTOCOL).get().protocol();
            if (enumProt == null) {
                throw new RuntimeException("ConnectionProtocol unknown: " + msg);
            }
            int integer = enumProt.codec(protocolDirection).packetId(msg);

            FriendlyByteBuf packetDataSerializer = new CustomDataSerializer(() -> player, out);
            packetDataSerializer.writeVarInt(integer);

            try {
                int integer2 = packetDataSerializer.writerIndex();
                msg.write(packetDataSerializer);
                int integer3 = packetDataSerializer.writerIndex() - integer2;
                if (integer3 > 8388608) {
                    throw new IllegalArgumentException("Packet too big (is " + integer3 + ", should be less than 8388608): " + msg);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        protected void setPlayer(Player player) {
            this.player = player;
        }
    }

    private static class CustomPacketDecoder extends ByteToMessageDecoder {
        private Player player;

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            if (msg.readableBytes() == 0) return;

            Attribute<ConnectionProtocol.CodecData<?>> attribute = ctx.channel().attr(Connection.ATTRIBUTE_SERVERBOUND_PROTOCOL);
            ConnectionProtocol.CodecData<?> codecData = attribute.get();
            CustomDataSerializer dataSerializer = new CustomDataSerializer(() -> player, msg);
            int packetID = dataSerializer.readVarInt();
            Packet<?> packet = codecData.createPacket(packetID, dataSerializer);

            if (packet == null) {
                Logs.logError("Bad packet id " + packetID);
            } else {
                if (dataSerializer.readableBytes() > 0) {
                    Logs.logError("Packet " + packetID + " (" + packet.getClass().getSimpleName() + ") was larger than I expected, found " + dataSerializer.readableBytes() + " bytes extra whilst reading packet " + packetID);
                }
                out.add(packet);
                ProtocolSwapHandler.swapProtocolIfNeeded(attribute, packet);
            }
        }

        protected void setPlayer(Player player) {
            this.player = player;
        }
    }

    @Override
    public boolean getSupported() {
        return true;
    }
}