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
package tech.kwik.core.tls;

import tech.kwik.agent15.TlsProtocolException;
import tech.kwik.agent15.alert.DecodeErrorException;
import tech.kwik.agent15.extension.Extension;
import tech.kwik.core.QuicConstants;
import tech.kwik.core.generic.InvalidIntegerEncodingException;
import tech.kwik.core.generic.VariableLengthInteger;
import tech.kwik.core.impl.Role;
import tech.kwik.core.impl.TransportError;
import tech.kwik.core.impl.TransportParameters;
import tech.kwik.core.impl.Version;
import tech.kwik.core.log.Logger;
import tech.kwik.core.util.Bytes;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static tech.kwik.core.QuicConstants.TransportParameterId.*;
import static tech.kwik.core.impl.Role.Server;

/**
 * Quic transport parameter TLS extension.
 * see https://www.rfc-editor.org/rfc/rfc9001.html#name-quic-transport-parameters-e
 */
public class QuicTransportParametersExtension extends Extension {

    // https://www.rfc-editor.org/rfc/rfc9221.html#name-quic-transport-parameter
    public static final int MAX_DATAGRAM_FRAME_SIZE = 0x20;

    private static final int MINIMUM_EXTENSION_LENGTH = 2;
    public static final int CODEPOINT_IETFDRAFT = 0xffa5;
    public static final int CODEPOINT_V1 = 0x39;

    private final Version quicVersion;
    private Role senderRole;
    private byte[] data;
    private TransportParameters params;
    private Integer discardTransportParameterSize;


    public static boolean isCodepoint(Version quicVersion, int extensionType) {
        if (quicVersion.isV1V2()) {
            return extensionType == CODEPOINT_V1;
        }
        else {
            return extensionType == CODEPOINT_IETFDRAFT;
        }
    }

    public QuicTransportParametersExtension() {
        this(Version.getDefault());
    }

    public QuicTransportParametersExtension(Version quicVersion) {
        this.quicVersion = quicVersion;
        params = new TransportParameters();
    }

    /**
     * Creates a Quic Transport Parameters Extension for use in a Client Hello.
     * @param quicVersion
     * @param senderRole
     */
    public QuicTransportParametersExtension(Version quicVersion, TransportParameters params, Role senderRole) {
        this.quicVersion = quicVersion;
        this.params = params;
        this.senderRole = senderRole;
    }

    @Override
    public byte[] getBytes() {
        if (data == null) {
            serialize();
        }
        return data;
    }

    public void addDiscardTransportParameter(int parameterSize) {
        // https://github.com/quicwg/base-drafts/wiki/Quantum-Readiness-test
        discardTransportParameterSize = parameterSize;
    }

    protected void serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(1024 + (discardTransportParameterSize != null? discardTransportParameterSize: 0));

        // https://tools.ietf.org/html/draft-ietf-quic-tls-32#section-8.2
        // "quic_transport_parameters(0xffa5)"
        buffer.putShort((short) (quicVersion.equals(Version.QUIC_version_1) || quicVersion.isV2()? CODEPOINT_V1: CODEPOINT_IETFDRAFT));

        // Format is same as any TLS extension, so next are 2 bytes length
        buffer.putShort((short) 0);  // PlaceHolder, will be correctly set at the end of this method.

        // https://tools.ietf.org/html/draft-ietf-quic-transport-32#section-18.2
        // "Those transport parameters that are identified as integers use a variable-length integer encoding (...) and
        //  have a default value of 0 if the transport parameter is absent, unless otherwise stated."

        if (senderRole == Server) {
            // "The value of the Destination Connection ID field from the first Initial packet sent by the client (...)
            // This transport parameter is only sent by a server."
            addTransportParameter(buffer, original_destination_connection_id, params.getOriginalDestinationConnectionId());
        }

        // "The max idle timeout is a value in milliseconds that is encoded as an integer"
        addTransportParameter(buffer, max_idle_timeout, params.getMaxIdleTimeout());

        if (senderRole == Server && params.getStatelessResetToken() != null) {
            // "A stateless reset token is used in verifying a stateless reset (...). This parameter is a sequence of 16
            //  bytes. This transport parameter MUST NOT be sent by a client, but MAY be sent by a server."
            addTransportParameter(buffer, stateless_reset_token, params.getStatelessResetToken());
        }

        // "The maximum UDP payload size parameter is an integer value that limits the size of UDP payloads that the
        //  endpoint is willing to receive.  UDP datagrams with payloads larger than this limit are not likely to be
        //  processed by the receiver."
        addTransportParameter(buffer, max_udp_payload_size, params.getMaxUdpPayloadSize());

        // "The initial maximum data parameter is an integer value that contains the initial value for the maximum
        //  amount of data that can be sent on the connection.  This is equivalent to sending a MAX_DATA for the
        //  connection immediately after completing the handshake."
        addTransportParameter(buffer, initial_max_data, params.getInitialMaxData());

        // "This parameter is an integer value specifying the initial flow control limit for locally-initiated
        //  bidirectional streams. This limit applies to newly created bidirectional streams opened by the endpoint that
        //  sends the transport parameter."
        addTransportParameter(buffer, initial_max_stream_data_bidi_local, params.getInitialMaxStreamDataBidiLocal());

        // "This parameter is an integer value specifying the initial flow control limit for peer-initiated bidirectional
        //  streams. This limit applies to newly created bidirectional streams opened by the endpoint that receives
        //  the transport parameter."
        addTransportParameter(buffer, initial_max_stream_data_bidi_remote, params.getInitialMaxStreamDataBidiRemote());

        // "This parameter is an integer value specifying the initial flow control limit for unidirectional streams.
        //  This limit applies to newly created bidirectional streams opened by the endpoint that receives the transport
        //  parameter."
        addTransportParameter(buffer, initial_max_stream_data_uni, params.getInitialMaxStreamDataUni());

        // "The initial maximum bidirectional streams parameter is an integer value that contains the initial maximum
        //  number of bidirectional streams the peer may initiate.  If this parameter is absent or zero, the peer cannot
        //  open bidirectional streams until a MAX_STREAMS frame is sent."
        addTransportParameter(buffer, initial_max_streams_bidi, params.getInitialMaxStreamsBidi());

        // "The initial maximum unidirectional streams parameter is an integer value that contains the initial maximum
        //  number of unidirectional streams the peer may initiate. If this parameter is absent or zero, the peer cannot
        //  open unidirectional streams until a MAX_STREAMS frame is sent."
        addTransportParameter(buffer, initial_max_streams_uni, params.getInitialMaxStreamsUni());

        // "The acknowledgement delay exponent is an integer value indicating an exponent used to decode the ACK Delay
        // field in the ACK frame"
        addTransportParameter(buffer, ack_delay_exponent, params.getAckDelayExponent());

        // "The maximum acknowledgement delay is an integer value indicating the maximum amount of time in milliseconds
        //  by which the endpoint will delay sending acknowledgments."
        addTransportParameter(buffer, max_ack_delay, params.getMaxAckDelay());

        // "The disable active migration transport parameter is included if the endpoint does not support active
        //  connection migration (Section 9) on the address being used during the handshake. "
        if (params.getDisableMigration()) {
            addTransportParameter(buffer, disable_active_migration);
        }

        // Intentionally omitted (kwik server does not support preferred address)
        // preferred_address

        // "The maximum number of connection IDs from the peer that an endpoint is willing to store."
        addTransportParameter(buffer, active_connection_id_limit, params.getActiveConnectionIdLimit());

        // "The value that the endpoint included in the Source Connection ID field of the first Initial packet it
        //  sends for the connection"
        addTransportParameter(buffer, initial_source_connection_id, params.getInitialSourceConnectionId());

        if (senderRole == Server) {
            // "The value that the the server included in the Source Connection ID field of a Retry packet"
            // "This transport parameter is only sent by a server."
            if (params.getRetrySourceConnectionId() != null) {
                addTransportParameter(buffer, retry_source_connection_id, params.getRetrySourceConnectionId());
            }
        }

        if (discardTransportParameterSize != null) {
            // See https://github.com/quicwg/base-drafts/wiki/Quantum-Readiness-test
            addTransportParameter(buffer, (short) 0x173e, new byte[discardTransportParameterSize]);
        }

        if (params.getVersionInformation() != null) {
            TransportParameters.VersionInformation versions = params.getVersionInformation();
            ByteBuffer data = ByteBuffer.allocate(4 + versions.getOtherVersions().size() * 4);
            data.put(versions.getChosenVersion().getBytes());
            versions.getOtherVersions().forEach(v -> data.put(v.getBytes()));
            addTransportParameter(buffer, version_information, data.array());
        }

        if (params.getMaxDatagramFrameSize() > 0) {
            addTransportParameter(buffer, MAX_DATAGRAM_FRAME_SIZE, params.getMaxDatagramFrameSize());
        }

        int length = buffer.position();
        buffer.limit(length);

        int extensionsSize = length - 2 - 2;  // 2 bytes for the length itself and 2 for the type
        buffer.putShort(2, (short) extensionsSize);

        data = new byte[length];
        buffer.flip();
        buffer.get(data);
    }

    public QuicTransportParametersExtension parse(ByteBuffer buffer, Role senderRole, Logger log) throws TlsProtocolException {
        int extensionType = buffer.getShort() & 0xffff;
        if (!isCodepoint(quicVersion, extensionType)) {
            throw new RuntimeException();  // Must be programming error
        }
        int extensionLength = buffer.getShort();
        int startPosition = buffer.position();
        Set<Long> parsedParameters = new HashSet<>();
        log.debug("Transport parameters: ");
        while (buffer.position() - startPosition < extensionLength) {
            try {
                parseTransportParameter(buffer, parsedParameters, log);
            }
            catch (InvalidIntegerEncodingException e) {
                throw new DecodeErrorException("invalid integer encoding in transport parameter extension");
            }
            catch (TransportError e) {
                throw new TlsProtocolException(e.getMessage(), e);
            }
        }

        int realSize = buffer.position() - startPosition;
        if (realSize != extensionLength) {
            throw new DecodeErrorException("inconsistent size in transport parameter extension");
        }
        return this;
    }

    void parseTransportParameter(ByteBuffer buffer, Set<Long> parsedParameters, Logger log) throws DecodeErrorException, InvalidIntegerEncodingException, TransportError {
        long parameterId = VariableLengthInteger.parseLong(buffer);
        if (!parsedParameters.add(parameterId)) {
            // https://www.rfc-editor.org/rfc/rfc9000.html#section-7.4
            // "An endpoint SHOULD treat receipt of duplicate transport parameters as a connection error of type TRANSPORT_PARAMETER_ERROR."
            throw new TransportError(QuicConstants.TransportErrorCode.TRANSPORT_PARAMETER_ERROR, "duplicate transport parameter");
        }
        int size = VariableLengthInteger.parse(buffer);
        if (buffer.remaining() < size) {
            throw new DecodeErrorException("Invalid transport parameter extension");
        }
        int startPosition = buffer.position();

        if (parameterId == original_destination_connection_id.value) {
            byte[] destinationCid = new byte[size];
            buffer.get(destinationCid);
            log.debug("- original destination connection id: ", destinationCid);
            params.setOriginalDestinationConnectionId(destinationCid);
        }
        else if (parameterId == max_idle_timeout.value) {
            long idleTimeout = VariableLengthInteger.parseLong(buffer);
            log.debug("- max idle timeout: " + idleTimeout);
            params.setMaxIdleTimeout(idleTimeout);
        }
        else if (parameterId == stateless_reset_token.value) {
            byte[] resetToken = new byte[16];
            buffer.get(resetToken);
            log.debug("- stateless reset token: " + Bytes.bytesToHex(resetToken));
            params.setStatelessResetToken(resetToken);
        }
        else if (parameterId == max_udp_payload_size.value) {
            int maxPacketSize = VariableLengthInteger.parse(buffer);
            log.debug("- max udp payload size: " + maxPacketSize);
            params.setMaxUdpPayloadSize(maxPacketSize);
        }
        else if (parameterId == initial_max_data.value) {
            long maxData = VariableLengthInteger.parseLong(buffer);
            log.debug("- initial max data: " + maxData);
            params.setInitialMaxData(maxData);
        }
        else if (parameterId == initial_max_stream_data_bidi_local.value) {
            long maxStreamDataBidiLocal = VariableLengthInteger.parseLong(buffer);
            log.debug("- initial max stream data bidi local: " + maxStreamDataBidiLocal);
            params.setInitialMaxStreamDataBidiLocal(maxStreamDataBidiLocal);
        }
        else if (parameterId == initial_max_stream_data_bidi_remote.value) {
            long maxStreamDataBidiRemote = VariableLengthInteger.parseLong(buffer);
            log.debug("- initial max stream data bidi remote: " + maxStreamDataBidiRemote);
            params.setInitialMaxStreamDataBidiRemote(maxStreamDataBidiRemote);
        }
        else if (parameterId == initial_max_stream_data_uni.value) {
            long maxStreamDataUni = VariableLengthInteger.parseLong(buffer);
            log.debug("- initial max stream data uni: " + maxStreamDataUni);
            params.setInitialMaxStreamDataUni(maxStreamDataUni);
        }
        else if (parameterId == initial_max_streams_bidi.value) {
            long maxBidiStreams = VariableLengthInteger.parseLong(buffer);
            log.debug("- initial max bidi streams: " + maxBidiStreams);
            params.setInitialMaxStreamsBidi(maxBidiStreams);
        }
        else if (parameterId == initial_max_streams_uni.value) {
            long maxUniStreams = VariableLengthInteger.parseLong(buffer);
            log.debug("- max uni streams: " + maxUniStreams);
            params.setInitialMaxStreamsUni(maxUniStreams);
        }
        else if (parameterId == ack_delay_exponent.value) {
            int ackDelayExponent = VariableLengthInteger.parse(buffer);
            log.debug("- ack delay exponent: " + ackDelayExponent);
            params.setAckDelayExponent(ackDelayExponent);
        }
        else if (parameterId == max_ack_delay.value) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-30#section-18.2
            // "The maximum acknowledgement delay is an integer value indicating the maximum amount of time in
            //  milliseconds by which the endpoint will delay sending acknowledgments. "
            int maxAckDelay = VariableLengthInteger.parse(buffer);
            log.debug("- max ack delay: " + maxAckDelay);
            params.setMaxAckDelay(maxAckDelay);
        }
        else if (parameterId == disable_active_migration.value) {
            log.debug("- disable migration");
            params.setDisableMigration(true);
        }
        else if (parameterId == preferred_address.value) {
            parsePreferredAddress(buffer, log);
        }
        else if (parameterId == active_connection_id_limit.value) {
            long activeConnectionIdLimit = VariableLengthInteger.parseLong(buffer);
            log.debug("- active connection id limit: " + activeConnectionIdLimit);
            params.setActiveConnectionIdLimit((int) activeConnectionIdLimit);
        }
        else if (parameterId == initial_source_connection_id.value) {
            byte[] initialSourceCid = new byte[size];
            buffer.get(initialSourceCid);
            log.debug("- initial source connection id: " + Bytes.bytesToHex(initialSourceCid));
            params.setInitialSourceConnectionId(initialSourceCid);
        }
        else if (parameterId == retry_source_connection_id.value) {
            byte[] retrySourceCid = new byte[size];
            buffer.get(retrySourceCid);
            log.debug("- retry source connection id: " + Bytes.bytesToHex(retrySourceCid));
            params.setRetrySourceConnectionId(retrySourceCid);
        }
        else if (parameterId == version_information.value) {
            // Óhttps://www.ietf.org/archive/id/draft-ietf-quic-version-negotiation-05.html#name-version-information
            if (size % 4 != 0 || size < 4) {
                throw new DecodeErrorException("invalid parameters size");
            }
            int chosenVersion = buffer.getInt();
            List<Version> otherVersions = new ArrayList<>();
            for (int i = 0; i < size/4 - 1; i++) {
                int otherVersion = buffer.getInt();
                otherVersions.add(Version.parse(otherVersion));
            }
            params.setVersionInformation(new TransportParameters.VersionInformation(Version.parse(chosenVersion), otherVersions));
        }
        else if (parameterId == MAX_DATAGRAM_FRAME_SIZE) {
            long datagramMaxFrameSize = VariableLengthInteger.parseLong(buffer);
            log.debug("- max datagram frame size: " + datagramMaxFrameSize);
            params.setMaxDatagramFrameSize(datagramMaxFrameSize);
        }
        else {
            String extension = "";
            if (parameterId == 0x0020) extension = "datagram";
            if (parameterId == 0x0040) extension = "multi-path";
            if (parameterId == 0x1057) extension = "loss-bits";
            if (parameterId == 0x173e) extension = "discard";
            if (parameterId == 0x2ab2) extension = "grease-quic-bit";
            if (parameterId == 0x7157) extension = "timestamp";  // https://datatracker.ietf.org/doc/html/draft-huitema-quic-ts-02#section-5
            if (parameterId == 0x7158) extension = "timestamp";  // https://datatracker.ietf.org/doc/html/draft-huitema-quic-ts-05#section-5
            if (parameterId == 0x73db) extension = "version-negotiation";  // https://datatracker.ietf.org/doc/draft-ietf-quic-version-negotiation/02/
            if (parameterId == 0xde1a) extension = "delayed-ack";  // https://datatracker.ietf.org/doc/html/draft-iyengar-quic-delayed-ack-01#section-3
            if (parameterId == 0xff73db) extension = "version-information-4-13";   // https://datatracker.ietf.org/doc/draft-ietf-quic-version-negotiation/4/
            if (parameterId == 0xff02de1aL) extension = "delayed-ack";  // https://datatracker.ietf.org/doc/html/draft-iyengar-quic-delayed-ack-02#section-3
            String msg;
            if (extension.isBlank()) {
                msg = String.format("- unknown transport parameter 0x%04x, size %d", parameterId, size);
            }
            else {
                msg = String.format("- unsupported transport parameter 0x%04x, size %d (%s)", parameterId, size, extension);
            }
            log.warn(msg);
            buffer.get(new byte[size]);
        }

        int realSize = buffer.position() - startPosition;
        if (realSize != size) {
            throw new DecodeErrorException("inconsistent size in transport parameter");
        }
    }

    private void parsePreferredAddress(ByteBuffer buffer, Logger log) throws DecodeErrorException {
        try {
            TransportParameters.PreferredAddress preferredAddress = new TransportParameters.PreferredAddress();

            byte[] ip4 = new byte[4];
            buffer.get(ip4);
            if (!Bytes.allZero(ip4)) {
                preferredAddress.setIp4(InetAddress.getByAddress(ip4));
            }
            preferredAddress.setIp4Port((buffer.get() << 8) | buffer.get());
            byte[] ip6 = new byte[16];
            buffer.get(ip6);
            if (!Bytes.allZero(ip6)) {
                preferredAddress.setIp6(InetAddress.getByAddress(ip6));
            }
            preferredAddress.setIp6Port((buffer.get() << 8) | buffer.get());

            if (preferredAddress.getIp4() == null && preferredAddress.getIp6() == null) {
                throw new DecodeErrorException("Preferred address: no valid IP address");
            }

            int connectionIdSize = buffer.get();
            preferredAddress.setConnectionId(buffer, connectionIdSize);
            preferredAddress.setStatelessResetToken(buffer, 16); //

            params.setPreferredAddress(preferredAddress);
        }
        catch (UnknownHostException invalidIpAddressLength) {
            // Impossible
            throw new RuntimeException();
        }
    }

    private void addTransportParameter(ByteBuffer buffer, QuicConstants.TransportParameterId id, long value) {
        addTransportParameter(buffer, id.value, value);
    }

    private void addTransportParameter(ByteBuffer buffer, QuicConstants.TransportParameterId id) {
        VariableLengthInteger.encode(id.value, buffer);
        int valueLength = 0;
        VariableLengthInteger.encode(valueLength, buffer);
    }

    private void addTransportParameter(ByteBuffer buffer, int id, long value) {
        VariableLengthInteger.encode(id, buffer);
        buffer.mark();
        int encodedValueLength = VariableLengthInteger.encode(value, buffer);
        buffer.reset();
        VariableLengthInteger.encode(encodedValueLength, buffer);
        VariableLengthInteger.encode(value, buffer);
    }

    protected void addTransportParameter(ByteBuffer buffer, QuicConstants.TransportParameterId id, byte[] value) {
        addTransportParameter(buffer, id.value, value);
    }

    private void addTransportParameter(ByteBuffer buffer, int id, byte[] value) {
        VariableLengthInteger.encode(id, buffer);
        VariableLengthInteger.encode(value.length, buffer);
        buffer.put(value);
    }

    public TransportParameters getTransportParameters() {
        return params;
    }
}
