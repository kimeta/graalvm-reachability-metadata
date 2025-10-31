/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package netty;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;
import org.awaitility.Awaitility;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;

public class NettyTests {
    @Test
    public void noSsl() throws Exception {
        test(false);
    }

    private void test(boolean sslIgnored) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Channel serverChannel = null;
        try {
            serverChannel = startServer(bossGroup, workerGroup);
            int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

            AtomicReference<String> response = new AtomicReference<>();
            startClient(workerGroup, port, response::set);

            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .untilAtomic(response, CoreMatchers.equalTo("Hello World"));
        } finally {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    private void startClient(EventLoopGroup group, int port, Consumer<String> callback) throws InterruptedException {
        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                    @Override
                    protected void messageReceived(ChannelHandlerContext ctx, ByteBuf msg) {
                        String content = msg.toString(CharsetUtil.UTF_8);
                        callback.accept(content);
                        ctx.close();
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        cause.printStackTrace();
                        ctx.close();
                    }
                });
            }
        });
        Channel ch = b.connect("localhost", port).sync().channel();
        ch.closeFuture().sync();
    }

    private Channel startServer(EventLoopGroup bossGroup, EventLoopGroup workerGroup) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ctx.writeAndFlush(Unpooled.copiedBuffer("Hello World", CharsetUtil.UTF_8))
                                        .addListener(ChannelFutureListener.CLOSE);
                            }

                            @Override
                            protected void messageReceived(ChannelHandlerContext ctx, ByteBuf msg) {
                                // No-op: server just sends a greeting on connect.
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                cause.printStackTrace();
                                ctx.close();
                            }
                        });
                    }
                });
        // Bind to port 0 to pick an ephemeral free port and return the bound channel.
        return b.bind(new InetSocketAddress("localhost", 0)).sync().channel();
    }
}
