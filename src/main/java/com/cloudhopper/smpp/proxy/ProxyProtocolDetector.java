package com.cloudhopper.smpp.proxy;

/*
 * #%L
 * ch-smpp
 * %%
 * Copyright (C) 2009 - 2015 Cloudhopper by Twitter
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

// java imports
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

// third party imports
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ProtocolDetectionResult;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// my imports
import com.cloudhopper.smpp.channel.SmppChannelConstants;

/**
 * Detects a PROXY protocol (v1 or v2) header at the start of a server channel
 * and records the client address it carries, so a server behind a TCP proxy
 * sees the real client address.
 * <p>
 * Only peers inside the trusted networks may send a header: any other peer is
 * left alone and the detector removes itself without looking at the bytes. For
 * a trusted peer the detector only inspects the buffered bytes until it can
 * tell whether a header is present, never consuming them:
 * <ul>
 * <li>no header: the detector removes itself and the buffered bytes reach the
 * next handler unchanged (the peer is treated as a direct connection)</li>
 * <li>header: the header is decoded and its source address stored in the
 * {@link SmppChannelConstants#ATTR_PROXIED_REMOTE_ADDRESS} channel attribute
 * ({@code UNKNOWN} protocols keep the socket address). A {@code LOCAL}
 * command (a proxy health check) or a malformed header closes the channel.</li>
 * </ul>
 * One instance per channel; the trusted networks are shared and immutable.
 */
public class ProxyProtocolDetector extends ByteToMessageDecoder {
    private static final Logger logger = LoggerFactory.getLogger(ProxyProtocolDetector.class);

    static final String PIPELINE_DECODER_NAME = "smppProxyProtocolDecoder";
    static final String PIPELINE_HEADER_HANDLER_NAME = "smppProxyProtocolHeader";

    private final TrustedNetworks trustedNetworks;

    public ProxyProtocolDetector(TrustedNetworks trustedNetworks) {
        this.trustedNetworks = trustedNetworks;
    }

    /**
     * Decides whether a peer may send a PROXY protocol header. An IPv4-mapped
     * IPv6 address (::ffff:a.b.c.d, as a dual-stack socket reports an IPv4
     * peer) is compared as the IPv4 address it stands for. Anything that is
     * not a resolved IP socket address is not trusted.
     */
    static boolean isTrusted(TrustedNetworks trustedNetworks, SocketAddress peer) {
        if (!(peer instanceof InetSocketAddress)) {
            return false;
        }
        InetAddress address = ((InetSocketAddress) peer).getAddress();
        if (address == null) {
            return false;
        }
        return trustedNetworks.contains(unmapIpv4(address));
    }

    private static InetAddress unmapIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] bytes = address.getAddress();
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return address;
            }
        }
        if (bytes[10] != (byte) 0xFF || bytes[11] != (byte) 0xFF) {
            return address;
        }
        try {
            return InetAddress.getByAddress(new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] });
        } catch (java.net.UnknownHostException e) {
            // cannot happen: the array has four bytes
            return address;
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!isTrusted(trustedNetworks, ctx.channel().remoteAddress())) {
            // not a proxy: no parsing, the bytes go on untouched
            ctx.pipeline().remove(this);
            return;
        }

        ProtocolDetectionResult<HAProxyProtocolVersion> result = HAProxyMessageDecoder.detectProtocol(in);
        switch (result.state()) {
            case NEEDS_MORE_DATA:
                // keep the bytes buffered and look again when more arrive
                return;
            case INVALID:
                // no header: a direct connection from a trusted peer
                logger.debug("No PROXY protocol header from trusted peer [{}]", ctx.channel().remoteAddress());
                ctx.pipeline().remove(this);
                return;
            default:
                // a header: let the decoder consume it and a handler apply it; the buffered bytes
                // are passed to them when this handler is removed
                ctx.pipeline().addAfter(ctx.name(), PIPELINE_DECODER_NAME, new HAProxyMessageDecoder(true));
                ctx.pipeline().addAfter(PIPELINE_DECODER_NAME, PIPELINE_HEADER_HANDLER_NAME, new HeaderHandler());
                ctx.pipeline().remove(this);
        }
    }

    /**
     * Applies the decoded header to the channel, then steps out of the pipeline.
     */
    private static final class HeaderHandler extends ChannelInboundHandlerAdapter {

        private boolean closed;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof HAProxyMessage)) {
                if (closed) {
                    ReferenceCountUtil.release(msg);
                } else {
                    ctx.fireChannelRead(msg);
                }
                return;
            }
            HAProxyMessage header = (HAProxyMessage) msg;
            try {
                apply(ctx, header);
            } finally {
                header.release();
            }
        }

        private void apply(ChannelHandlerContext ctx, HAProxyMessage header) {
            if (header.command() == HAProxyCommand.LOCAL) {
                logger.info("Closing PROXY protocol LOCAL connection from [{}]", ctx.channel().remoteAddress());
                close(ctx);
                return;
            }
            HAProxyProxiedProtocol protocol = header.proxiedProtocol();
            if (protocol == HAProxyProxiedProtocol.TCP4 || protocol == HAProxyProxiedProtocol.TCP6) {
                InetAddress source = NetUtil.createInetAddressFromIpAddressString(header.sourceAddress());
                if (source == null) {
                    logger.warn("Closing connection from [{}]: invalid source address in PROXY protocol header", ctx.channel().remoteAddress());
                    close(ctx);
                    return;
                }
                ctx.channel().attr(SmppChannelConstants.ATTR_PROXIED_REMOTE_ADDRESS)
                        .set(new InetSocketAddress(source, header.sourcePort()));
            } else {
                logger.debug("PROXY protocol header from [{}] carries no TCP address ({}), keeping the socket address",
                        ctx.channel().remoteAddress(), protocol);
            }
            ctx.pipeline().remove(this);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.warn("Closing connection from [{}]: malformed PROXY protocol header: {}",
                    ctx.channel().remoteAddress(), cause.getMessage());
            close(ctx);
        }

        private void close(ChannelHandlerContext ctx) {
            closed = true;
            ctx.close();
        }
    }
}
