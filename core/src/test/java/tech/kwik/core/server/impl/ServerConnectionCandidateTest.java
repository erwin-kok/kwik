/*
 * Copyright © 2021, 2022, 2023, 2024, 2025 Peter Doornbosch
 *
 * This file is part of Kwik, an implementation of the QUIC protocol in Java.
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package tech.kwik.core.server.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kwik.agent15.engine.TlsServerEngineFactory;
import tech.kwik.core.common.EncryptionLevel;
import tech.kwik.core.crypto.ConnectionSecrets;
import tech.kwik.core.crypto.CryptoStream;
import tech.kwik.core.frame.*;
import tech.kwik.core.impl.Role;
import tech.kwik.core.impl.TestUtils;
import tech.kwik.core.impl.Version;
import tech.kwik.core.impl.VersionHolder;
import tech.kwik.core.log.Logger;
import tech.kwik.core.packet.InitialPacket;
import tech.kwik.core.packet.PacketMetaData;
import tech.kwik.core.send.SenderImpl;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnectionFactory;
import tech.kwik.core.server.ServerConnectionRegistry;
import tech.kwik.core.test.FieldReader;
import tech.kwik.core.test.TestClock;
import tech.kwik.core.test.TestScheduledExecutor;
import tech.kwik.core.tls.ClientHelloBuilder;

import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;


class ServerConnectionCandidateTest {

    private Logger logger;
    private TestClock clock;
    private ServerConnectionImpl createdServerConnection;
    private ServerConnectionFactory serverConnectionFactory;
    private Context context;
    private TestScheduledExecutor testExecutor;
    private ServerConnectionRegistry connectionRegistry;
    private InetSocketAddress clientAddress;
    private byte[] scid;
    private byte[] odcid;
    private Version version = Version.getDefault();

    @BeforeEach
    void initObjectUnderTest() throws Exception {
        logger = mock(Logger.class);
        clock = new TestClock();
        InputStream certificate = getClass().getResourceAsStream("localhost.pem");
        InputStream privateKey = getClass().getResourceAsStream("localhost.key");
        TlsServerEngineFactory tlsServerEngineFactory = new TlsServerEngineFactory(certificate, privateKey);
        serverConnectionFactory = new TestServerConnectionFactory(16, mock(DatagramSocket.class), tlsServerEngineFactory,
                false, mock(ApplicationProtocolRegistry.class), 100, cid -> {}, logger);
        context = mock(Context.class);
        testExecutor = new TestScheduledExecutor(clock);
        when(context.getSharedServerExecutor()).thenReturn(testExecutor);
        when(context.getSharedScheduledExecutor()).thenReturn(testExecutor);
        connectionRegistry = mock(ServerConnectionRegistry.class);
        clientAddress = new InetSocketAddress("localhost", 55333);
        scid = new byte[0];
        odcid = new byte[8];
    }

    @Test
    void firstInitialPacketShouldSetAntiAmplificationLimit() throws Exception {
        // Given
        byte[] initialPacketBytes = TestUtils.createValidInitial(version);
        odcid = Arrays.copyOfRange(initialPacketBytes, 6, 6 + 8);
        scid = Arrays.copyOfRange(initialPacketBytes, 6 + 8 + 1, 6 + 8 + 1 + 8);
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(initialPacketBytes), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNotNull();
        Integer antiAmplificationLimit = (Integer) new FieldReader(createdServerConnection.getSender(), SenderImpl.class.getDeclaredField("antiAmplificationLimit")).read();
        assertThat(antiAmplificationLimit).isEqualTo(3 * 1200);
    }

    @Test
    void whenClientHelloIsSplitOverMultiplePacketsAllShouldContributeToAntiAmplificationLimit() throws Exception {
        // Given
        CryptoFrame[] cryptoFrames = createSplitClientHelloCryptoFrames();
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte datagram1[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[0], new Padding(1200 - cryptoFrames[0].getFrameLength())));
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram1), clientAddress);
        testExecutor.check();
        byte datagram2[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[1], new Padding(1200 - cryptoFrames[1].getFrameLength())));
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram2), clientAddress);
        testExecutor.check();

        // Then
        Integer antiAmplificationLimit = (Integer) new FieldReader(createdServerConnection.getSender(), SenderImpl.class.getDeclaredField("antiAmplificationLimit")).read();
        assertThat(antiAmplificationLimit).isGreaterThan(3 * 2 * 1200);
    }

    @Test
    void firstInitialCarriedInSmallDatagramShouldBeDiscarded() throws Exception {
        byte[] initialPacketBytes = TestUtils.createValidInitialNoPadding(version);
        odcid = Arrays.copyOfRange(initialPacketBytes, 6, 6 + 8);
        scid = Arrays.copyOfRange(initialPacketBytes, 6 + 8 + 1, 6 + 8 + 1 + 8);
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(initialPacketBytes), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
        verify(connectionRegistry, never()).registerConnection(any(ServerConnectionProxy.class), any(byte[].class));
    }

    @Test
    void firstInitialWithPaddingInDatagramShouldCreateConnection() throws Exception {
        byte[] initialPacketBytes = TestUtils.createValidInitialNoPadding(version);
        odcid = Arrays.copyOfRange(initialPacketBytes, 6, 6 + 8);
        scid = Arrays.copyOfRange(initialPacketBytes, 6 + 8 + 1, 6 + 8 + 1 + 8);
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        ByteBuffer datagramBytes = ByteBuffer.allocate(1200);
        datagramBytes.put(initialPacketBytes);
        datagramBytes.rewind();
        connectionCandidate.parsePackets(0, Instant.now(), datagramBytes, clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNotNull();
    }

    @Test
    void whenDatagramContainsCoalescedPacketsConnectionProxyShouldReceivedRemainingData() throws Exception {
        // Given
        byte[] initialPacketBytes = TestUtils.createValidInitial(version);
        odcid = Arrays.copyOfRange(initialPacketBytes, 6, 6 + 8);
        scid = Arrays.copyOfRange(initialPacketBytes, 6 + 8 + 1, 6 + 8 + 1 + 8);
        byte[] datagramData = new byte[1500];  // Simulating a second 300-byte packet in the same datagram.
        System.arraycopy(initialPacketBytes, 0, datagramData, 0, initialPacketBytes.length);
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagramData), clientAddress);
        testExecutor.check();

        // Then
        ByteBuffer remainingDatagramData = ((TestServerConnectionFactory) serverConnectionFactory).getRemainingDatagramData();
        assertThat(remainingDatagramData.position()).isEqualTo(1200);
        assertThat(remainingDatagramData.remaining()).isEqualTo(1500 - initialPacketBytes.length);
    }

    @Test
    void firstInitialPacketWithoutCryptoFrameShouldNotCreateConnection() throws Exception {
        // Given
        List<QuicFrame> frames = List.of(new PingFrame(), new Padding(1164));
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte data[] = createInitialPacketBytes(scid, odcid, frames);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(data), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
    }

    @Test
    void firstInitialPacketWithCryptoAndAckFrameShouldNotCreateConnection() throws Exception {
        // Given
        byte[] validClientHelloBytes = new ClientHelloBuilder().buildBinary();
        CryptoFrame firstCryptoFrame = new CryptoFrame(version, validClientHelloBytes);
        AckFrame ackFrame = new AckFrame(0);
        List<QuicFrame> frames = List.of(firstCryptoFrame, ackFrame, new Padding(1200 - firstCryptoFrame.getFrameLength() - ackFrame.getFrameLength()));
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte data[] = createInitialPacketBytes(scid, odcid, frames);
        assertThat(data.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(data), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
    }

    @Test
    void firstInitialPacketWithCryptoAndConnectionCloseFrameShouldNotCreateConnection() throws Exception {
        // Given
        byte[] validClientHelloBytes = new ClientHelloBuilder().buildBinary();
        CryptoFrame firstCryptoFrame = new CryptoFrame(version, validClientHelloBytes);
        ConnectionCloseFrame ccFrame = new ConnectionCloseFrame(Version.getDefault());
        List<QuicFrame> frames = List.of(firstCryptoFrame, ccFrame, new Padding(1200 - firstCryptoFrame.getFrameLength() - ccFrame.getFrameLength()));
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte data[] = createInitialPacketBytes(scid, odcid, frames);
        assertThat(data.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(data), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
    }

    @Test
    void firstInitialPacketWithCryptoAndPathChallengeFrameShouldNotCreateConnection() throws Exception {
        // Given
        byte[] validClientHelloBytes = new ClientHelloBuilder().buildBinary();
        CryptoFrame firstCryptoFrame = new CryptoFrame(version, validClientHelloBytes);
        PathChallengeFrame pathChallengeFrame = new PathChallengeFrame(Version.getDefault(), new byte[8]);
        List<QuicFrame> frames = List.of(firstCryptoFrame, pathChallengeFrame, new Padding(1200 - firstCryptoFrame.getFrameLength() - pathChallengeFrame.getFrameLength()));
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte data[] = createInitialPacketBytes(scid, odcid, frames);
        assertThat(data.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(data), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
    }

    @Test
    void firstInitialPacketWithoutCompleteClientHelloShouldNotCreateConnection() throws Exception {
        // Given
        byte[] firstHalfOfClientHello = new byte[1165];
        ByteBuffer.wrap(firstHalfOfClientHello).putInt(0x010007d0); // 0x01 = handshake, 0x0007d0 = length (2000 bytes)
        CryptoFrame firstCryptoFrame = new CryptoFrame(version, firstHalfOfClientHello);
        List<QuicFrame> frames = List.of(firstCryptoFrame);
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte data[] = createInitialPacketBytes(scid, odcid, frames);
        assertThat(data.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(data), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
    }

    @Test
    void whenClientHelloIsSplitOverTwoPacketsThenLastPacketShouldCreateConnection()  throws Exception {
        // Given
        CryptoFrame[] cryptoFrames = createSplitClientHelloCryptoFrames();
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte datagram1[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[0], new Padding(1200 - cryptoFrames[0].getFrameLength())));
        assertThat(datagram1.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram1), clientAddress);
        testExecutor.check();
        assertThat(createdServerConnection).isNull();
        byte datagram2[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[1], new Padding(1200 - cryptoFrames[1].getFrameLength())));
        assertThat(datagram2.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram2), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNotNull();
    }

    @Test
    void whenClientHelloIsSplitOverTwoPacketsWithSmallCryptoFramesThenNoConnectionShouldBeCreated()  throws Exception {
        // Given
        CryptoFrame[] cryptoFrames = createSplitClientHelloCryptoFrames(650);
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte datagram1[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[0], new Padding(1200 - cryptoFrames[0].getFrameLength())));
        assertThat(datagram1.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram1), clientAddress);
        testExecutor.check();
        assertThat(createdServerConnection).isNull();
        byte datagram2[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[1], new Padding(1200 - cryptoFrames[1].getFrameLength())));
        assertThat(datagram2.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram2), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
    }

    @Test
    void whenClientHelloIsSplitOverTwoPacketsWithDifferentVersionsThenNoConnectionShouldBeCreated()  throws Exception {
        // Given
        CryptoFrame[] cryptoFrames = createSplitClientHelloCryptoFrames();
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte datagram1[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[0], new Padding(1200 - cryptoFrames[0].getFrameLength())));
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram1), clientAddress);
        testExecutor.check();

        Version differentVersion = new Version(0x3343cafe);
        byte datagram2[] = createInitialPacketBytes(differentVersion, scid, odcid, List.of(cryptoFrames[1], new Padding(1200 - cryptoFrames[1].getFrameLength())));
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram2), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
    }

    @Test
    void whenClientHelloIsSplitOverTwoPacketsWithDifferentScidsThenNoConnectionShouldBeCreated()  throws Exception {
        // Given
        CryptoFrame[] cryptoFrames = createSplitClientHelloCryptoFrames();
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte datagram1[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[0], new Padding(1200 - cryptoFrames[0].getFrameLength())));
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram1), clientAddress);
        testExecutor.check();

        byte[] differentScid = new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
        byte datagram2[] = createInitialPacketBytes(differentScid, odcid, List.of(cryptoFrames[1], new Padding(1200 - cryptoFrames[1].getFrameLength())));
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram2), clientAddress);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
    }

    @Test
    void whenClientHelloIsSplitOverMultiplePacketsConnectionProxyShouldReceiveCoalescedPacketsInLastDatagram() throws Exception {
        // Given
        CryptoFrame[] cryptoFrames = createSplitClientHelloCryptoFrames();

        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, clientAddress, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte[] datagram1 = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[0], new Padding(1200 - cryptoFrames[0].getFrameLength())));
        assertThat(datagram1.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram1), clientAddress);
        testExecutor.check();
        assertThat(createdServerConnection).isNull();

        byte[] initial2 = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[1]));
        byte[] coalesced = new byte[1200 - initial2.length];
        for (int i = 0; i < coalesced.length; i++) {
            coalesced[i] = (byte) i;
        }
        byte[] datagram2 = new byte[1200];
        System.arraycopy(initial2, 0, datagram2, 0, initial2.length);
        System.arraycopy(coalesced, 0, datagram2, initial2.length, coalesced.length);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram2), clientAddress);
        testExecutor.check();

        // Then
        ByteBuffer remainingDatagramData = ((TestServerConnectionFactory) serverConnectionFactory).getRemainingDatagramData();
        assertThat(remainingDatagramData.position()).isEqualTo(initial2.length);
        assertThat(remainingDatagramData.remaining()).isEqualTo(1200 - initial2.length);
        assertThat(remainingDatagramData.get()).isEqualTo((byte) 0);
        assertThat(remainingDatagramData.get()).isEqualTo((byte) 1);
        assertThat(remainingDatagramData.get()).isEqualTo((byte) 2);
        assertThat(remainingDatagramData.get()).isEqualTo((byte) 3);
    }

    @Test
    void whenInitialPacketsHaveDifferentSourceAddressAllButTheFirstShouldBeIgnored()  throws Exception {
        // Given
        CryptoFrame[] cryptoFrames = createSplitClientHelloCryptoFrames();

        InetSocketAddress address1 = new InetSocketAddress("localhost", 55333);
        InetSocketAddress address2 = new InetSocketAddress("localhost", 41975);
        ServerConnectionCandidate connectionCandidate = new ServerConnectionCandidate(context, version, address1, scid, odcid, serverConnectionFactory, connectionRegistry, logger);

        // When
        byte datagram1[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[0], new Padding(1200 - cryptoFrames[0].getFrameLength())));
        assertThat(datagram1.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram1), address1);
        testExecutor.check();
        assertThat(createdServerConnection).isNull();
        byte datagram2[] = createInitialPacketBytes(scid, odcid, List.of(cryptoFrames[1], new Padding(1200 - cryptoFrames[1].getFrameLength())));
        assertThat(datagram2.length).isGreaterThanOrEqualTo(1200);
        connectionCandidate.parsePackets(0, Instant.now(), ByteBuffer.wrap(datagram2), address2);
        testExecutor.check();

        // Then
        assertThat(createdServerConnection).isNull();
    }
    byte[] createInitialPacketBytes(byte[] scid, byte[] odcid, List<QuicFrame> frames) throws Exception {
        return createInitialPacketBytes(version, scid, odcid, frames);
    }

    byte[] createInitialPacketBytes(Version version, byte[] scid, byte[] odcid, List<QuicFrame> frames) throws Exception {
        InitialPacket initialPacket = new InitialPacket(version, scid, odcid, null, frames);
        initialPacket.setPacketNumber(0);
        ConnectionSecrets secrets = new ConnectionSecrets(VersionHolder.with(version), Role.Client, null, mock(Logger.class));
        secrets.computeInitialKeys(odcid);
        return initialPacket.generatePacketBytes(secrets.getOwnAead(EncryptionLevel.Initial));
    }

    private CryptoFrame[] createSplitClientHelloCryptoFrames() {
        return createSplitClientHelloCryptoFrames(1100);
    }

    private CryptoFrame[] createSplitClientHelloCryptoFrames(int firstCryptoLength) {
        int extensionLength = 1125;  // without this extension, the client hello would be around 175 bytes
        String fakeExtensionType = "fa7e";
        String veryLargeExtension = fakeExtensionType + String.format("%04x", extensionLength) + "00".repeat(extensionLength);
        byte[] validClientHelloBytes = new ClientHelloBuilder().withExtension(veryLargeExtension).buildBinary();
        int firstHalfLength = firstCryptoLength;
        CryptoFrame frame1 = new CryptoFrame(version, 0, Arrays.copyOfRange(validClientHelloBytes, 0, firstHalfLength));
        CryptoFrame frame2 = new CryptoFrame(version, firstHalfLength, Arrays.copyOfRange(validClientHelloBytes, firstHalfLength, validClientHelloBytes.length));
        return new CryptoFrame[] {frame1, frame2};
    }

    static ServerConnectionConfig getDefaultConfiguration(int connectionIdLength) {
        return ServerConnectionConfig.builder()
                .maxIdleTimeoutInSeconds(30)
                .maxUnidirectionalStreamBufferSize(1_000_000)
                .maxBidirectionalStreamBufferSize(1_000_000)
                .maxConnectionBufferSize(10_000_000)
                .maxOpenPeerInitiatedUnidirectionalStreams(10)
                .maxOpenPeerInitiatedBidirectionalStreams(100)
                .connectionIdLength(connectionIdLength)
                .build();
    }

    class TestServerConnectionFactory extends ServerConnectionFactory {
        private ByteBuffer remainingDatagramData;

        public TestServerConnectionFactory(int connectionIdLength, DatagramSocket serverSocket, TlsServerEngineFactory tlsServerEngineFactory, boolean requireRetry, ApplicationProtocolRegistry applicationProtocolRegistry, int initalRtt, Consumer<ServerConnectionImpl> closeCallback, Logger log) {
            super(serverSocket, tlsServerEngineFactory, getDefaultConfiguration(connectionIdLength), applicationProtocolRegistry, null, closeCallback, log);
        }

        @Override
        public ServerConnectionImpl createNewConnection(Version version, InetSocketAddress clientAddress, byte[] originalScid, byte[] originalDcid, CryptoStream cryptoStream) {
            ServerConnectionImpl newConnection = super.createNewConnection(version, clientAddress, originalScid, originalDcid, cryptoStream);
            createdServerConnection = newConnection;
            return newConnection;
        }

        @Override
        public ServerConnectionProxy createServerConnectionProxy(ServerConnectionImpl connection, List<InitialPacket> initialPackets, ByteBuffer data, PacketMetaData metaData) {
            remainingDatagramData = data;
            return new ServerConnectionThreadDummy(connection, initialPackets.get(0), metaData);
        }

        public ByteBuffer getRemainingDatagramData() {
            return remainingDatagramData;
        }
    }
}