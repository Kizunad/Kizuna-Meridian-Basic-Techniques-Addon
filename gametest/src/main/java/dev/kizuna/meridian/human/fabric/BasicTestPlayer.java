package dev.kizuna.meridian.human.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.protocol.pb.CombatMessages.TechniquePress;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapNetworking;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.impl.networking.ChannelInfoHolder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 经过真实登录、频道协商和断线事件的测试玩家，网络输出记录在内存中。 */
public final class BasicTestPlayer implements AutoCloseable {
    private static final int NAME_ID_LENGTH = 8;
    final ServerPlayer player;
    final RecordingConnection connection;

    public BasicTestPlayer(GameTestHelper context) {
        // 每个普通夹具使用独立玩家，防止施放与费用在测试间共享。
        this(context, UUID.randomUUID());
    }

    BasicTestPlayer(GameTestHelper context, UUID playerId) {
        // 只替换传输层，玩家列表、Human 登录和 Fabric 网络事件均使用正式实现。
        var world = context.getLevel();
        var profile = new GameProfile(playerId, "tech_" + playerId.toString().substring(0, NAME_ID_LENGTH));
        player = new ServerPlayer(world.getServer(), world, profile, ClientInformation.createDefault());
        connection = new RecordingConnection(world::getGameTime, List.of(Identifier.parse("meridian:body_view"),
                Identifier.parse("meridian:gathering_response"), Identifier.parse("meridian:opening_response"),
                Identifier.parse("meridian_progression:event"), Identifier.parse("kizuna_players_animation:play"),
                Identifier.parse("kizuna_players_animation:stop"), CombatChannels.KNOWN.type().id(),
                TechniqueLeapNetworking.CHANNEL.type().id()));
        TestPlayers.logIn(player, connection);
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
        player.connection.handleCustomPayload(
                new ServerboundCustomPayloadPacket(CombatChannels.PRESS.payload(request.toByteArray())));
    }

    Vec3 receiveVelocityAndMove() {
        // 无客户端的夹具确认登录传送，再把真实速度包转为客户端一帧移动回报。
        var teleport = connection.packets.stream()
                .filter(ClientboundPlayerPositionPacket.class::isInstance)
                .map(ClientboundPlayerPositionPacket.class::cast)
                .reduce((previous, latest) -> latest)
                .orElseThrow();
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.id()));
        var packet = connection.packets.stream()
                .filter(ClientboundSetEntityMotionPacket.class::isInstance)
                .map(ClientboundSetEntityMotionPacket.class::cast)
                .filter(update -> update.id() == player.getId())
                .reduce((previous, latest) -> latest)
                .orElseThrow(() -> new AssertionError("轻步未向本人同步速度包"));
        // 26.2 的速度包到编码时才量化，包对象里就是服务端下发的速度。
        Vec3 velocity = packet.movement();
        Vec3 destination = player.position().add(velocity);
        player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(
                destination.x, destination.y, destination.z, false /* onGround */, false /* horizontalCollision */));
        return velocity;
    }

    HumanWorldState state() {
        // 与生产登录和 tick 使用同一个世界状态实例。
        return HumanTechniqueHost.state();
    }

    @Override
    public void close() {
        // 真实断线入口保证功法取消先于 Human 撤销认领，并从在线列表移除玩家。
        connection.disconnectFromClient();
    }

    /** 功法库的公共记录连接不能扩展，这里另记一份带世界 tick 的声音包。 */
    static final class RecordingConnection extends Connection {
        final List<Packet<?>> packets = new ArrayList<>();
        /** 声音包另按世界 tick 记录，供逐击时刻断言。 */
        final List<HeardSound> sounds = new ArrayList<>();
        private final EmbeddedChannel channel;
        private final LongSupplier clock;

        RecordingConnection(LongSupplier clock, Collection<Identifier> clientChannels) {
            // 接收端仍为服务端；挂内存通道让连接处于已连接状态，并预先声明客户端能收的通道。
            super(PacketFlow.SERVERBOUND);
            this.clock = clock;
            channel = new EmbeddedChannel(this);
            ((ChannelInfoHolder) (Connection) this).fabric_getPendingChannelsNames(ConnectionProtocol.PLAY)
                    .addAll(clientChannels);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            // 26.2 的各个发送入口都汇到这里；保留原始包供断言，并像传输成功那样执行回调。
            packets.add(packet);
            if (packet instanceof ClientboundSoundPacket sound) {
                sounds.add(new HeardSound(clock.getAsLong(), sound));
            }
            if (listener != null) {
                completeSend(listener);
            }
        }

        private void completeSend(ChannelFutureListener listener) {
            // 踢人时原版在断线包发完的回调里关闭连接，回调必须照常执行。
            try {
                listener.operationComplete(channel.newSucceededFuture());
            } catch (Exception exception) {
                throw new IllegalStateException("测试连接的发送回调失败", exception);
            }
        }

        void disconnectFromClient() {
            // 模拟客户端退出：关闭传输层后像服务端网络循环那样处理一次断线；已断开时不重复。
            if (isConnected()) {
                disconnect(Component.literal("GameTest 完成"));
                handleDisconnection();
            }
        }
    }

    /** 收到声音包时的世界 tick 与原始包。 */
    record HeardSound(long tick, ClientboundSoundPacket packet) {
        String soundId() {
            // 未注册的事件随包内联下发，同样带着目录里写的 ID。
            return packet.getSound().value().location().toString();
        }

        Vec3 position() {
            // 声音包坐标按 1/8 格定点数传输，比较时要留同等容差。
            return new Vec3(packet.getX(), packet.getY(), packet.getZ());
        }
    }
}
