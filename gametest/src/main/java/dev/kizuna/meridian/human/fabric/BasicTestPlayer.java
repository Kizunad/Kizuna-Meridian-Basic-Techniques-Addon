package dev.kizuna.meridian.human.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.impl.networking.ChannelInfoHolder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 经过真实登录、频道协商和断线事件的测试玩家，网络输出记录在内存中。 */
public final class BasicTestPlayer implements AutoCloseable {
    private static final int NAME_ID_LENGTH = 8;
    private static final double VELOCITY_PACKET_SCALE = 8000;
    final ServerPlayer player;
    final RecordingConnection connection;

    public BasicTestPlayer(GameTestHelper context) {
        // 每个普通夹具使用独立玩家，防止施放与费用在测试间共享。
        this(context, UUID.randomUUID());
    }

    BasicTestPlayer(GameTestHelper context, UUID playerId) {
        // 只替换传输层，玩家列表、Human 登录和 Fabric 网络事件均使用正式实现。
        var server = context.getLevel().getServer();
        var profile = new GameProfile(playerId, "tech_" + playerId.toString().substring(0, NAME_ID_LENGTH));
        player = new ServerPlayer(server, context.getLevel(), profile);
        connection = new RecordingConnection(context.getLevel()::getGameTime);
        var channels = ((ChannelInfoHolder) (Connection) connection).getPendingChannelsNames();
        channels.addAll(List.of(new ResourceLocation("meridian:body_view"),
                new ResourceLocation("meridian:gathering_response"), new ResourceLocation("meridian:opening_response"),
                new ResourceLocation("meridian_progression:event"), new ResourceLocation("kizuna_players_animation:play"),
                new ResourceLocation("kizuna_players_animation:stop"), CombatChannels.KNOWN,
                TechniqueLeapNetworking.CHANNEL));
        server.getPlayerList().placeNewPlayer(connection, player);
        player.setGameMode(GameType.CREATIVE);
        player.setNoGravity(true /* noGravity */);
    }

    HumanPlayerBody body() {
        // 读取 Human 正式绑定的世界状态，不创建第二份身体或替换宿主绑定。
        return state().find(player.getUUID());
    }

    public ServerPlayer player() {
        // 生物整合测试复用真实在线玩家，不依赖 Human 测试夹具的内部身体接口。
        return player;
    }

    void press(TechniquePress request) {
        // 经过正式网络处理器和全局接收器，验证战斗协议到真实宿主的接线。
        var buffer = PacketByteBufs.create();
        buffer.writeBytes(request.toByteArray());
        try {
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(CombatChannels.PRESS, buffer));
        } finally {
            buffer.release();
        }
    }

    Vec3 receiveVelocityAndMove() {
        // 无客户端的夹具确认登录传送，再把真实速度包转为客户端一帧移动回报。
        var teleport = connection.packets.stream()
                .filter(ClientboundPlayerPositionPacket.class::isInstance)
                .map(ClientboundPlayerPositionPacket.class::cast)
                .reduce((previous, latest) -> latest)
                .orElseThrow();
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.getId()));
        var packet = connection.packets.stream()
                .filter(ClientboundSetEntityMotionPacket.class::isInstance)
                .map(ClientboundSetEntityMotionPacket.class::cast)
                .filter(update -> update.getId() == player.getId())
                .reduce((previous, latest) -> latest)
                .orElseThrow(() -> new AssertionError("轻步未向本人同步速度包"));
        Vec3 velocity = new Vec3(packet.getXa() / VELOCITY_PACKET_SCALE,
                packet.getYa() / VELOCITY_PACKET_SCALE, packet.getZa() / VELOCITY_PACKET_SCALE);
        Vec3 destination = player.position().add(velocity);
        player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(
                destination.x, destination.y, destination.z, false /* onGround */));
        return velocity;
    }

    HumanWorldState state() {
        // 与生产登录和 tick 使用同一个 SavedData 实例。
        return player.getServer().overworld().getDataStorage()
                .get(HumanWorldState::read, "meridian_human");
    }

    @Override
    public void close() {
        // 真实断线入口保证功法取消先于 Human 撤销认领，并从在线列表移除玩家。
        if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != null) {
            player.connection.onDisconnect(Component.literal("GameTest 完成"));
        }
        connection.disconnect(Component.literal("GameTest 完成"));
    }

    static final class RecordingConnection extends Connection {
        final List<Packet<?>> packets = new ArrayList<>();
        /** 声音包另按世界 tick 记录，供逐击时刻断言。 */
        final List<HeardSound> sounds = new ArrayList<>();
        private final LongSupplier clock;
        private boolean open = true;

        RecordingConnection(LongSupplier clock) {
            // 接收端仍为服务端，Fabric 的网络附加状态与真实连接一致。
            super(PacketFlow.SERVERBOUND);
            this.clock = clock;
        }

        @Override
        public void send(Packet<?> packet) {
            // 保留发送的原始包供同步断言，不复制任何业务协议。
            packets.add(packet);
            if (packet instanceof ClientboundSoundPacket sound) {
                sounds.add(new HeardSound(clock.getAsLong(), sound));
            }
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener callbacks) {
            // 传输成功回调按正常连接执行，业务层无需特殊测试分支。
            send(packet);
            if (callbacks != null) {
                callbacks.onSuccess();
            }
        }

        @Override
        public boolean isConnected() {
            // 网络入口据此检查连接仍有效。
            return open;
        }

        @Override
        public boolean isMemoryConnection() {
            // 本地测试不经过压缩和加密握手。
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            // 登录日志和玩家管理器需要可打印的连接地址。
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public void disconnect(Component reason) {
            // 连接关闭与玩家移除分别由真实网络处理器和测试传输层负责。
            open = false;
        }
    }

    /** 收到声音包时的世界 tick 与原始包。 */
    record HeardSound(long tick, ClientboundSoundPacket packet) {
        String soundId() {
            // 未注册的事件随包内联下发，同样带着目录里写的 ID。
            return packet.getSound().value().getLocation().toString();
        }

        Vec3 position() {
            // 声音包坐标按 1/8 格定点数传输，比较时要留同等容差。
            return new Vec3(packet.getX(), packet.getY(), packet.getZ());
        }
    }
}
