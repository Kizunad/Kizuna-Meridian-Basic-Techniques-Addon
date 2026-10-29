package dev.kizuna.meridian.human.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.impl.networking.ChannelInfoHolder;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 经过真实登录、频道协商和断线事件的测试玩家，网络输出记录在内存中。 */
public final class BasicTestPlayer implements AutoCloseable {
    private static final int NAME_ID_LENGTH = 8;
    private static final double VELOCITY_PACKET_SCALE = 8000;
    final ServerPlayerEntity player;
    final RecordingConnection connection;

    public BasicTestPlayer(TestContext context) {
        // 每个普通夹具使用独立玩家，防止施放与费用在测试间共享。
        this(context, UUID.randomUUID());
    }

    BasicTestPlayer(TestContext context, UUID playerId) {
        // 只替换传输层，玩家列表、Human 登录和 Fabric 网络事件均使用正式实现。
        var server = context.getWorld().getServer();
        var profile = new GameProfile(playerId, "tech_" + playerId.toString().substring(0, NAME_ID_LENGTH));
        player = new ServerPlayerEntity(server, context.getWorld(), profile);
        connection = new RecordingConnection();
        var channels = ((ChannelInfoHolder) (ClientConnection) connection).getPendingChannelsNames();
        channels.addAll(List.of(new Identifier("meridian:body_view"),
                new Identifier("meridian:gathering_response"), new Identifier("meridian:opening_response"),
                new Identifier("meridian_progression:event"), new Identifier("kizuna_players_animation:play"),
                new Identifier("kizuna_players_animation:stop"), CombatChannels.KNOWN));
        server.getPlayerManager().onPlayerConnect(connection, player);
        player.changeGameMode(GameMode.CREATIVE);
        player.setNoGravity(true /* noGravity */);
    }

    HumanPlayerBody body() {
        // 读取 Human 正式绑定的世界状态，不创建第二份身体或替换宿主绑定。
        return state().find(player.getUuid());
    }

    public ServerPlayerEntity player() {
        // 生物整合测试复用真实在线玩家，不依赖 Human 测试夹具的内部身体接口。
        return player;
    }

    void press(TechniquePress request) {
        // 经过正式网络处理器和全局接收器，验证战斗协议到真实宿主的接线。
        var buffer = PacketByteBufs.create();
        buffer.writeBytes(request.toByteArray());
        try {
            player.networkHandler.onCustomPayload(new CustomPayloadC2SPacket(CombatChannels.PRESS, buffer));
        } finally {
            buffer.release();
        }
    }

    Vec3d receiveVelocityAndMove() {
        // 无客户端的夹具确认登录传送，再把真实速度包转为客户端一帧移动回报。
        var teleport = connection.packets.stream()
                .filter(PlayerPositionLookS2CPacket.class::isInstance)
                .map(PlayerPositionLookS2CPacket.class::cast)
                .reduce((previous, latest) -> latest)
                .orElseThrow();
        player.networkHandler.onTeleportConfirm(new TeleportConfirmC2SPacket(teleport.getTeleportId()));
        var packet = connection.packets.stream()
                .filter(EntityVelocityUpdateS2CPacket.class::isInstance)
                .map(EntityVelocityUpdateS2CPacket.class::cast)
                .filter(update -> update.getId() == player.getId())
                .reduce((previous, latest) -> latest)
                .orElseThrow(() -> new AssertionError("轻步未向本人同步速度包"));
        Vec3d velocity = new Vec3d(packet.getVelocityX() / VELOCITY_PACKET_SCALE,
                packet.getVelocityY() / VELOCITY_PACKET_SCALE, packet.getVelocityZ() / VELOCITY_PACKET_SCALE);
        Vec3d destination = player.getPos().add(velocity);
        player.networkHandler.onPlayerMove(new PlayerMoveC2SPacket.PositionAndOnGround(
                destination.x, destination.y, destination.z, false /* onGround */));
        return velocity;
    }

    HumanWorldState state() {
        // 与生产登录和 tick 使用同一个 PersistentState 实例。
        return player.getServer().getOverworld().getPersistentStateManager()
                .get(HumanWorldState::read, "meridian_human");
    }

    @Override
    public void close() {
        // 真实断线入口保证功法取消先于 Human 撤销认领，并从在线列表移除玩家。
        if (player.getServer().getPlayerManager().getPlayer(player.getUuid()) != null) {
            player.networkHandler.onDisconnected(Text.literal("GameTest 完成"));
        }
        connection.disconnect(Text.literal("GameTest 完成"));
    }

    static final class RecordingConnection extends ClientConnection {
        final List<Packet<?>> packets = new ArrayList<>();
        private boolean open = true;

        RecordingConnection() {
            // 接收端仍为服务端，Fabric 的网络附加状态与真实连接一致。
            super(NetworkSide.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
            // 保留发送的原始包供同步断言，不复制任何业务协议。
            packets.add(packet);
        }

        @Override
        public void send(Packet<?> packet, PacketCallbacks callbacks) {
            // 传输成功回调按正常连接执行，业务层无需特殊测试分支。
            send(packet);
            if (callbacks != null) {
                callbacks.onSuccess();
            }
        }

        @Override
        public boolean isOpen() {
            // 网络入口据此检查连接仍有效。
            return open;
        }

        @Override
        public boolean isLocal() {
            // 本地测试不经过压缩和加密握手。
            return true;
        }

        @Override
        public SocketAddress getAddress() {
            // 登录日志和玩家管理器需要可打印的连接地址。
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public void disconnect(Text reason) {
            // 连接关闭与玩家移除分别由真实网络处理器和测试传输层负责。
            open = false;
        }
    }
}
