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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// third party imports
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.junit.*;

// my imports
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServerConfiguration;
import com.cloudhopper.smpp.SmppServerHandler;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.channel.SmppChannelConstants;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.pdu.BindTransceiver;
import com.cloudhopper.smpp.transcoder.DefaultPduTranscoder;
import com.cloudhopper.smpp.transcoder.DefaultPduTranscoderContext;
import com.cloudhopper.smpp.type.SmppProcessingException;

/**
 * Tests the PROXY protocol support of {@link DefaultSmppServer} through a raw
 * socket, so the header bytes are exactly what a proxy sends.
 */
public class ProxyProtocolServerTest {

    private static final int TIMEOUT_MS = 5000;
    private static final byte[] V2_SIGNATURE = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };

    // a bind request as seen by the server handler
    private static final class BindSeen {
        final String host;
        final int port;

        BindSeen(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static final class RecordingHandler implements SmppServerHandler {
        final BlockingQueue<BindSeen> binds = new LinkedBlockingQueue<>();

        @Override
        public void sessionBindRequested(Long sessionId, SmppSessionConfiguration sessionConfiguration, BaseBind bindRequest) throws SmppProcessingException {
            binds.add(new BindSeen(sessionConfiguration.getHost(), sessionConfiguration.getPort()));
        }

        @Override
        public void sessionCreated(Long sessionId, SmppServerSession session, BaseBindResp preparedBindResponse) {
            session.serverReady(new DefaultSmppSessionHandler());
        }

        @Override
        public void sessionDestroyed(Long sessionId, SmppServerSession session) {
        }
    }

    private RecordingHandler handler;
    private DefaultSmppServer server;
    private int port;
    // closes a connection that never sends a valid bind (e.g. an unparsed header)
    private long bindTimeoutMs = 1000;

    @Before
    public void setUp() {
        handler = new RecordingHandler();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.destroy();
        }
    }

    private void startServer(boolean proxyEnabled, String... trustedProxies) throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        SmppServerConfiguration configuration = new SmppServerConfiguration();
        configuration.setHost("127.0.0.1");
        configuration.setPort(port);
        configuration.setName("ProxyProtocolTest");
        configuration.setSystemId("cloudhopper");
        configuration.setBindTimeout(bindTimeoutMs);
        configuration.getProxyProtocol().setEnabled(proxyEnabled);
        configuration.getProxyProtocol().setTrustedProxies(List.of(trustedProxies));
        server = new DefaultSmppServer(configuration, handler);
        server.start();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
        socket.setSoTimeout(TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static byte[] bindRequest() throws Exception {
        BindTransceiver bind = new BindTransceiver();
        bind.setSequenceNumber(1);
        bind.setSystemId("smppclient1");
        bind.setPassword("password");
        ByteBuf buffer = new DefaultPduTranscoder(new DefaultPduTranscoderContext()).encode(bind);
        try {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] v2Header(int command, int family, byte[] addresses) {
        byte[] fixed = new byte[4];
        fixed[0] = (byte) (0x20 | command);
        fixed[1] = (byte) family;
        fixed[2] = (byte) (addresses.length >> 8);
        fixed[3] = (byte) addresses.length;
        return concat(V2_SIGNATURE, fixed, addresses);
    }

    private static byte[] v2Tcp4Header(int[] source, int sourcePort, int[] destination, int destinationPort) {
        byte[] addresses = new byte[12];
        for (int i = 0; i < 4; i++) {
            addresses[i] = (byte) source[i];
            addresses[4 + i] = (byte) destination[i];
        }
        addresses[8] = (byte) (sourcePort >> 8);
        addresses[9] = (byte) sourcePort;
        addresses[10] = (byte) (destinationPort >> 8);
        addresses[11] = (byte) destinationPort;
        return v2Header(0x1, 0x11, addresses);
    }

    // reads the bind response and returns its command status
    private static int readBindStatus(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        byte[] header = new byte[16];
        int read = 0;
        while (read < header.length) {
            int n = in.read(header, read, header.length - read);
            Assert.assertTrue("connection closed before a bind response", n >= 0);
            read += n;
        }
        int commandId = ((header[4] & 0xFF) << 24) | ((header[5] & 0xFF) << 16) | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        Assert.assertEquals(SmppConstants.CMD_ID_BIND_TRANSCEIVER_RESP, commandId);
        return ((header[8] & 0xFF) << 24) | ((header[9] & 0xFF) << 16) | ((header[10] & 0xFF) << 8) | (header[11] & 0xFF);
    }

    // waits until the server closes the connection (a reset counts as closed)
    private static void assertClosed(Socket socket) throws IOException {
        try {
            while (socket.getInputStream().read() >= 0) {
                // drain anything the server sends before it closes
            }
        } catch (SocketException e) {
            // connection reset
        }
    }

    private BindSeen awaitBind() throws InterruptedException {
        BindSeen seen = handler.binds.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Assert.assertNotNull("no bind reached the server handler", seen);
        return seen;
    }

    private void assertNoBind() throws InterruptedException {
        Assert.assertNull("a bind reached the server handler", handler.binds.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    public void v1HeaderFromTrustedPeerSetsClientHost() throws Exception {
        startServer(true, "127.0.0.1/32");
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(concat(ascii("PROXY TCP4 203.0.113.7 172.16.24.10 50000 2775\r\n"), bindRequest()));
            out.flush();
            Assert.assertEquals(SmppConstants.STATUS_OK, readBindStatus(socket));
        }
        BindSeen seen = awaitBind();
        Assert.assertEquals("203.0.113.7", seen.host);
        Assert.assertEquals(50000, seen.port);
    }

    @Test
    public void v2HeaderFromTrustedPeerSetsClientHost() throws Exception {
        startServer(true, "127.0.0.1/32");
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(concat(v2Tcp4Header(new int[]{198, 51, 100, 9}, 40000, new int[]{172, 16, 24, 10}, 2775), bindRequest()));
            out.flush();
            Assert.assertEquals(SmppConstants.STATUS_OK, readBindStatus(socket));
        }
        BindSeen seen = awaitBind();
        Assert.assertEquals("198.51.100.9", seen.host);
        Assert.assertEquals(40000, seen.port);
    }

    @Test
    public void tcp6HeaderWithIpv4MappedSourceIsStoredAsIpv4() throws Exception {
        startServer(true, "127.0.0.1/32");
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(concat(ascii("PROXY TCP6 ::ffff:203.0.113.7 ::ffff:172.16.24.10 50000 2775\r\n"), bindRequest()));
            out.flush();
            Assert.assertEquals(SmppConstants.STATUS_OK, readBindStatus(socket));
        }
        BindSeen seen = awaitBind();
        Assert.assertEquals("203.0.113.7", seen.host);
        Assert.assertEquals(50000, seen.port);
    }

    @Test
    public void trustedPeerWithoutHeaderUsesSocketAddress() throws Exception {
        startServer(true, "127.0.0.1/32");
        try (Socket socket = connect()) {
            socket.getOutputStream().write(bindRequest());
            socket.getOutputStream().flush();
            Assert.assertEquals(SmppConstants.STATUS_OK, readBindStatus(socket));
        }
        Assert.assertEquals("127.0.0.1", awaitBind().host);
    }

    @Test
    public void trustedPeerWithoutHeaderSplitAcrossWritesIsDecodedUnchanged() throws Exception {
        startServer(true, "127.0.0.1/32");
        byte[] bind = bindRequest();
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            // fewer bytes than the detector needs to decide
            out.write(bind, 0, 5);
            out.flush();
            Thread.sleep(50);
            out.write(bind, 5, bind.length - 5);
            out.flush();
            Assert.assertEquals(SmppConstants.STATUS_OK, readBindStatus(socket));
        }
        Assert.assertEquals("127.0.0.1", awaitBind().host);
    }

    @Test
    public void untrustedPeerHeaderIsNotHonoured() throws Exception {
        startServer(true, "10.0.0.0/8");
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(concat(ascii("PROXY TCP4 203.0.113.7 172.16.24.10 50000 2775\r\n"), bindRequest()));
            out.flush();
            assertClosed(socket);
        }
        assertNoBind();
    }

    @Test
    public void untrustedPeerWithoutHeaderBindsWithSocketAddress() throws Exception {
        startServer(true, "10.0.0.0/8");
        try (Socket socket = connect()) {
            socket.getOutputStream().write(bindRequest());
            socket.getOutputStream().flush();
            Assert.assertEquals(SmppConstants.STATUS_OK, readBindStatus(socket));
        }
        Assert.assertEquals("127.0.0.1", awaitBind().host);
    }

    @Test
    public void malformedHeaderFromTrustedPeerIsClosed() throws Exception {
        // far longer than the socket read timeout: only the detector can close the connection in time
        bindTimeoutMs = 30000;
        startServer(true, "127.0.0.1/32");
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(concat(ascii("PROXY TCP4 999.1.1.1 x\r\n"), bindRequest()));
            out.flush();
            assertClosed(socket);
        }
        assertNoBind();
    }

    @Test
    public void headerSplitAcrossWrites() throws Exception {
        startServer(true, "127.0.0.1/32");
        byte[] header = ascii("PROXY TCP4 203.0.113.7 172.16.24.10 50000 2775\r\n");
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(header, 0, 20);
            out.flush();
            Thread.sleep(50);
            out.write(concat(Arrays.copyOfRange(header, 20, header.length), bindRequest()));
            out.flush();
            Assert.assertEquals(SmppConstants.STATUS_OK, readBindStatus(socket));
        }
        Assert.assertEquals("203.0.113.7", awaitBind().host);
    }

    @Test
    public void headerSplitBeforeItCanBeDetected() throws Exception {
        startServer(true, "127.0.0.1/32");
        byte[] header = ascii("PROXY TCP4 203.0.113.7 172.16.24.10 50000 2775\r\n");
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            // fewer than the 12 bytes needed to recognise a header: the detector must wait
            out.write(header, 0, 6);
            out.flush();
            Thread.sleep(50);
            out.write(concat(Arrays.copyOfRange(header, 6, header.length), bindRequest()));
            out.flush();
            Assert.assertEquals(SmppConstants.STATUS_OK, readBindStatus(socket));
        }
        Assert.assertEquals("203.0.113.7", awaitBind().host);
    }

    @Test
    public void unknownProtocolFallsBackToSocket() throws Exception {
        startServer(true, "127.0.0.1/32");
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(concat(ascii("PROXY UNKNOWN\r\n"), bindRequest()));
            out.flush();
            Assert.assertEquals(SmppConstants.STATUS_OK, readBindStatus(socket));
        }
        Assert.assertEquals("127.0.0.1", awaitBind().host);
    }

    @Test
    public void localCommandIsClosed() throws Exception {
        // far longer than the socket read timeout: only the detector can close the connection in time
        bindTimeoutMs = 30000;
        startServer(true, "127.0.0.1/32");
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(concat(v2Header(0x0, 0x00, new byte[0]), bindRequest()));
            out.flush();
            assertClosed(socket);
        }
        assertNoBind();
    }

    @Test
    public void disabledServerHasTodaysPipeline() throws Exception {
        startServer(false);
        try (Socket socket = connect()) {
            List<String> names = awaitPipelineNames();
            Assert.assertEquals(List.of(
                    SmppChannelConstants.PIPELINE_SERVER_CONNECTOR_NAME,
                    SmppChannelConstants.PIPELINE_SESSION_THREAD_RENAMER_NAME,
                    SmppChannelConstants.PIPELINE_SESSION_PDU_DECODER_NAME,
                    SmppChannelConstants.PIPELINE_SESSION_WRAPPER_NAME), names);
        }
        // a header is not recognised when the feature is off: the bind fails as before
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(concat(ascii("PROXY TCP4 203.0.113.7 172.16.24.10 50000 2775\r\n"), bindRequest()));
            out.flush();
            assertClosed(socket);
        }
        assertNoBind();
    }

    // the pipeline is completed when the channel becomes active, shortly after accept
    private List<String> awaitPipelineNames() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            for (Channel channel : server.getChannels()) {
                List<String> names = new ArrayList<>(channel.pipeline().names());
                names.removeIf(name -> name.startsWith("DefaultChannelPipeline$"));
                if (names.contains(SmppChannelConstants.PIPELINE_SESSION_WRAPPER_NAME)) {
                    return names;
                }
            }
            Thread.sleep(10);
        }
        Assert.fail("no channel with a complete pipeline");
        return null;
    }
}
