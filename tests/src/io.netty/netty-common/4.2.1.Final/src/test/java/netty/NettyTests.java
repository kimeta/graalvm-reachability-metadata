/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.CharsetUtil;
import org.awaitility.Awaitility;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class NettyTests {
    private int port;
    private static final String HOST = "127.0.0.1"; // Force IPv4 for CI consistency

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS) // Hard JUnit timeout
    public void noSsl() throws Exception {
        test(false);
    }

    private void test(boolean ssl) throws Exception {
        // Use 1 thread for boss to simplify log tracking
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            startServer(bossGroup, workerGroup, ssl);

            AtomicReference<Response> responseRef = new AtomicReference<>();
            startClient(workerGroup, ssl, responseRef::set);

            // Fail fast if the response doesn't arrive
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAtomic(responseRef, CoreMatchers.notNullValue());

        } finally {
            // CRITICAL: Set quietPeriod to 0.
            // Default Netty shutdown waits 2 seconds for 'quiet' which can hang CI.
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    private void startServer(EventLoopGroup boss, EventLoopGroup worker, boolean ssl) throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO)) // Logs Server Parent
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new LoggingHandler(LogLevel.DEBUG)); // Logs Child Traffic
                        p.addLast(new HttpRequestDecoder());
                        p.addLast(new HttpObjectAggregator(1048576));
                        p.addLast(new HttpResponseEncoder());
                        p.addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                FullHttpResponse res = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                        io.netty.buffer.Unpooled.copiedBuffer("Hello World", CharsetUtil.UTF_8));
                                res.headers().set(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
                                // Explicitly close connection after response to prevent CI hangs
                                ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
                            }
                        });
                    }
                });

        // Bind to 127.0.0.1 explicitly instead of 0.0.0.0
        Channel ch = b.bind(HOST, 0).sync().channel();
        this.port = ((java.net.InetSocketAddress) ch.localAddress()).getPort();
    }

    private void startClient(EventLoopGroup group, boolean ssl, java.util.function.Consumer<Response> callback) throws Exception {
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(1048576));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
                                callback.accept(new Response(
                                        msg.status().code(),
                                        msg.protocolVersion().toString(),
                                        msg.content().toString(CharsetUtil.UTF_8)));
                            }
                        });
                    }
                });

        Channel ch = b.connect(HOST, port).sync().channel();
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.HOST, HOST);
        ch.writeAndFlush(request);

        // Wait for the server to close the connection
        ch.closeFuture().await(5, TimeUnit.SECONDS);
    }

    // Reuse your existing Response record/class here...
    private static class Response {
        final int status;
        final String protocol;
        final String content;
        Response(int s, String p, String c) { this.status = s; this.protocol = p; this.content = c; }
    }
}