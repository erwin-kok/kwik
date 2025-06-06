/*
 * Copyright © 2019, 2020, 2021, 2022, 2023, 2024, 2025 Peter Doornbosch
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
package tech.kwik.core.impl;

import tech.kwik.core.receive.Receiver;
import tech.kwik.core.util.Bytes;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;

public class TransportParameters {

    private byte[] originalDestinationConnectionId;
    private long maxIdleTimeout;
    private long initialMaxData;
    private long initialMaxStreamDataBidiLocal;
    private long initialMaxStreamDataBidiRemote;
    private long initialMaxStreamDataUni;
    private long initialMaxStreamsBidi;
    private long initialMaxStreamsUni;
    private int ackDelayExponent;
    private boolean disableMigration;
    private PreferredAddress preferredAddress;
    private int maxAckDelay;
    private int activeConnectionIdLimit;
    private byte[] initialSourceConnectionId;
    private byte[] retrySourceConnectionId;
    private int maxUdpPayloadSize;
    private byte[] statelessResetToken;
    private VersionInformation versionInformation;
    // https://www.rfc-editor.org/rfc/rfc9221.html#name-transport-parameter
    private long maxDatagramFrameSize;

    public TransportParameters() {
        setDefaults();
        maxUdpPayloadSize = Receiver.MAX_DATAGRAM_SIZE;
    }

    public TransportParameters(int maxIdleTimeoutInSeconds, int initialMaxStreamData, int initialMaxStreamsBidirectional, int initialMaxStreamsUnidirectional) {
        setDefaults();
        this.maxIdleTimeout = maxIdleTimeoutInSeconds * 1000L;
        setInitialMaxStreamDataBidiLocal(initialMaxStreamData);
        setInitialMaxStreamDataBidiRemote(initialMaxStreamData);
        setInitialMaxStreamDataUni(initialMaxStreamData);
        initialMaxData = 10L * initialMaxStreamData;
        initialMaxStreamsBidi = initialMaxStreamsBidirectional;
        initialMaxStreamsUni = initialMaxStreamsUnidirectional;
        maxUdpPayloadSize = Receiver.MAX_DATAGRAM_SIZE;
    }

    private void setDefaults() {
        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-18.2
        // "The default for this parameter is the maximum permitted UDP payload of 65527"
        maxUdpPayloadSize = 65527;
        // "If this value is absent, a default value of 3 is assumed (indicating a multiplier of 8)."
        ackDelayExponent = 3;
        // "If this value is absent, a default of 25 milliseconds is assumed."
        maxAckDelay = 25;
        // "If this transport parameter is absent, a default of 2 is assumed."
        activeConnectionIdLimit = 2;

        // https://www.rfc-editor.org/rfc/rfc9221.html#name-transport-parameter
        // "The default for this parameter is 0, which indicates that the endpoint does not support DATAGRAM frames."
        maxDatagramFrameSize = 0;
    }

    public byte[] getOriginalDestinationConnectionId() {
        return originalDestinationConnectionId;
    }

    public void setOriginalDestinationConnectionId(byte[] initialSourceConnectionId) {
        this.originalDestinationConnectionId = initialSourceConnectionId;
    }

    public void setAckDelayExponent(int ackDelayExponent) {
        this.ackDelayExponent = ackDelayExponent;
    }

    public int getAckDelayExponent() {
        return ackDelayExponent;
    }

    public PreferredAddress getPreferredAddress() {
        return preferredAddress;
    }

    public void setPreferredAddress(PreferredAddress preferredAddress) {
        this.preferredAddress = preferredAddress;
    }

    public long getMaxIdleTimeout() {
        return maxIdleTimeout;
    }

    public void setMaxIdleTimeout(long idleTimeout) {
        maxIdleTimeout = idleTimeout;
    }

    public long getInitialMaxData() {
        return initialMaxData;
    }

    public void setInitialMaxData(long initialMaxData) {
        this.initialMaxData = initialMaxData;
    }

    public long getInitialMaxStreamDataBidiLocal() {
        return initialMaxStreamDataBidiLocal;
    }

    public void setInitialMaxStreamDataBidiLocal(long initialMaxStreamDataBidiLocal) {
        this.initialMaxStreamDataBidiLocal = initialMaxStreamDataBidiLocal;
    }

    public long getInitialMaxStreamDataBidiRemote() {
        return initialMaxStreamDataBidiRemote;
    }

    public void setInitialMaxStreamDataBidiRemote(long initialMaxStreamDataBidiRemote) {
        this.initialMaxStreamDataBidiRemote = initialMaxStreamDataBidiRemote;
    }

    public long getInitialMaxStreamDataUni() {
        return initialMaxStreamDataUni;
    }

    public void setInitialMaxStreamDataUni(long initialMaxStreamDataUni) {
        this.initialMaxStreamDataUni = initialMaxStreamDataUni;
    }

    public long getInitialMaxStreamsBidi() {
        return initialMaxStreamsBidi;
    }

    public void setInitialMaxStreamsBidi(long initialMaxStreamsBidi) {
        this.initialMaxStreamsBidi = initialMaxStreamsBidi;
    }

    public long getInitialMaxStreamsUni() {
        return initialMaxStreamsUni;
    }

    public void setInitialMaxStreamsUni(long initialMaxStreamsUni) {
        this.initialMaxStreamsUni = initialMaxStreamsUni;
    }

    public void setMaxAckDelay(int maxAckDelay) {
        this.maxAckDelay = maxAckDelay;
    }

    /**
     * Retrieve the max ack delay in milliseconds
     * @return
     */
    public int getMaxAckDelay() {
        return maxAckDelay;
    }

    public int getActiveConnectionIdLimit() {
        return activeConnectionIdLimit;
    }

    public void setActiveConnectionIdLimit(int activeConnectionIdLimit) {
        this.activeConnectionIdLimit = activeConnectionIdLimit;
    }

    public void setDisableMigration(boolean disableMigration) {
        this.disableMigration = disableMigration;
    }

    public boolean getDisableMigration() {
        return disableMigration;
    }

    public byte[] getInitialSourceConnectionId() {
        return initialSourceConnectionId;
    }

    public void setInitialSourceConnectionId(byte[] initialSourceConnectionId) {
        this.initialSourceConnectionId = initialSourceConnectionId;
    }

    public byte[] getRetrySourceConnectionId() {
        return retrySourceConnectionId;
    }

    public void setRetrySourceConnectionId(byte[] retrySourceConnectionId) {
        this.retrySourceConnectionId = retrySourceConnectionId;
    }

    public int getMaxUdpPayloadSize() {
        return maxUdpPayloadSize;
    }

    public void setMaxUdpPayloadSize(int maxUdpPayloadSize) {
        this.maxUdpPayloadSize = maxUdpPayloadSize;
    }

    public byte[] getStatelessResetToken() {
        return statelessResetToken;
    }

    public void setStatelessResetToken(byte[] statelessResetToken) {
        this.statelessResetToken = statelessResetToken;
    }

    @Override
    public String toString() {
        return "\n- original destination connection id\t" + formatCid(originalDestinationConnectionId) +
                "\n- max idle timeout\t" + (maxIdleTimeout / 1000) +
                "\n- max udp payload size\t" + maxUdpPayloadSize +
                "\n- initial max data\t\t\t" + initialMaxData +
                "\n- initial max stream data bidi local\t" + initialMaxStreamDataBidiLocal +
                "\n- initial max stream data bidi remote\t" + initialMaxStreamDataBidiRemote +
                "\n- initial max stream data uni\t\t" + initialMaxStreamDataUni +
                "\n- initial max streams bidi\t\t" + initialMaxStreamsBidi +
                "\n- initial max streams uni\t\t" + initialMaxStreamsUni +
                "\n- ack delay exponent\t\t\t" + ackDelayExponent +
                "\n- max ack delay\t\t\t\t" + maxAckDelay +
                "\n- disable migration\t\t\t" + disableMigration +
                "\n- active connection id limit\t\t" + activeConnectionIdLimit +
                "\n- initial source connection id\t\t" + formatCid(initialSourceConnectionId) +
                "\n- retry source connection id\t\t" + formatCid(retrySourceConnectionId) +
                "\n- max datagram frame size\t\t" + maxDatagramFrameSize;
    }

    private String formatCid(byte[] data) {
        if (data != null) {
            return Bytes.bytesToHex(data);
        }
        else {
            return "null";
        }
    }

    public VersionInformation getVersionInformation() {
        return versionInformation;
    }

    public void setVersionInformation(VersionInformation versionInfo) {
        versionInformation = versionInfo;
    }

    public long getMaxDatagramFrameSize() {
        return maxDatagramFrameSize;
    }

    public void setMaxDatagramFrameSize(long maxDatagramFrameSize) {
        this.maxDatagramFrameSize = maxDatagramFrameSize;
    }

    public static class PreferredAddress {
        InetAddress ip4;
        int ip4Port;
        InetAddress ip6;
        int ip6Port;
        byte[] connectionId;
        byte[] statelessResetToken;

        public InetAddress getIp4() {
            return ip4;
        }

        public void setIp4(InetAddress ip4) {
            this.ip4 = ip4;
        }

        public int getIp4Port() {
            return ip4Port;
        }

        public void setIp4Port(int ip4Port) {
            this.ip4Port = ip4Port;
        }

        public InetAddress getIp6() {
            return ip6;
        }

        public void setIp6(InetAddress ip6) {
            this.ip6 = ip6;
        }

        public int getIp6Port() {
            return ip6Port;
        }

        public void setIp6Port(int ip6Port) {
            this.ip6Port = ip6Port;
        }

        public byte[] getConnectionId() {
            return connectionId;
        }

        public byte[] getStatelessResetToken() {
            return statelessResetToken;
        }

        public void setConnectionId(ByteBuffer buffer, int connectionIdSize) {
             connectionId = new byte[connectionIdSize];
             buffer.get(connectionId);
        }

        public void setStatelessResetToken(ByteBuffer buffer, int size) {
            statelessResetToken = new byte[size];
            buffer.get(statelessResetToken);
        }
    }

    public static class VersionInformation {

        private final Version chosenVersion;
        private final List<Version> otherVersions;

        public VersionInformation(Version chosenVersion, List<Version> otherVersions) {
            this.chosenVersion = chosenVersion;
            this.otherVersions = otherVersions;
        }

        public Version getChosenVersion() {
            return chosenVersion;
        }

        public List<Version> getOtherVersions() {
            return otherVersions;
        }

        @Override
        public String toString() {
            return String.format("%s|%s", chosenVersion, otherVersions);
        }
    }
}
