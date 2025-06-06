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
package tech.kwik.core.frame;

import org.junit.jupiter.api.Test;
import tech.kwik.core.generic.VariableLengthInteger;
import tech.kwik.core.impl.TransportError;
import tech.kwik.core.impl.Version;
import tech.kwik.core.log.Logger;

import java.nio.ByteBuffer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static tech.kwik.core.frame.AckFrame.FIXED_SENDER_ACK_DELAY_EXPONENT;

class AckFrameTest extends FrameTest {

    //region parse frame
    @Test
    void testParse() throws Exception {
        byte[] data = new byte[] { 0x02, 0x00, 0x00, 0x00, 0x00 };

        AckFrame ack = new AckFrame().parse(ByteBuffer.wrap(data), mock(Logger.class));
        assertThat(ack.getLargestAcknowledged()).isEqualTo(0);
        assertThat(ack.getAckDelay()).isEqualTo(0);
        assertThat(ack.getAckedPacketNumbers()).containsOnly(0L);
        assertThat(ack.toString()).contains("[0|");
    }

    @Test
    void testParseAckRangeWithSingleGap() throws Exception {
        //                         ackframe   largest  delay ack-block-count #acked-below largest gap (size) #acked-below
        byte[] data = new byte[] { 0x02,      0x02,    0x00, 0x01,           0x00,                0x00,      0x00 };

        AckFrame ack = new AckFrame().parse(ByteBuffer.wrap(data), mock(Logger.class));
        assertThat(ack.getLargestAcknowledged()).isEqualTo(2);
        assertThat(ack.getAckDelay()).isEqualTo(0);

        assertThat(ack.getAckedPacketNumbers()).containsOnly(2L, 0L);
        assertThat(ack.toString()).contains("[2,0|");
    }

    @Test
    void testParseAckRangeWithLargerGap() throws Exception {
        //                         ackframe   largest  delay ack-block-count #acked-below largest gap (size) #acked-below
        byte[] data = new byte[] { 0x02,      0x08,    0x00, 0x01,           0x01,                0x03,      0x01 };

        AckFrame ack = new AckFrame().parse(ByteBuffer.wrap(data), mock(Logger.class));
        assertThat(ack.getLargestAcknowledged()).isEqualTo(8);
        assertThat(ack.getAckDelay()).isEqualTo(0);

        assertThat(ack.getAckedPacketNumbers()).containsOnly(8L, 7L, 2L, 1L);
        assertThat(ack.toString()).contains("[8-7,2-1|");
    }

    @Test
    void testParseAckRangeWithTwoAckBlocks() throws Exception {
        //                         ackframe   largest  delay ack-block-count #acked-below largest gap (size) #acked-below gap (size) #acked-below
        byte[] data = new byte[] { 0x02,      0x0a,    0x00, 0x02,           0x02,                0x01,      0x01,        0x00,      0x02 };

        AckFrame ack = new AckFrame().parse(ByteBuffer.wrap(data), mock(Logger.class));
        assertThat(ack.getLargestAcknowledged()).isEqualTo(10);
        assertThat(ack.getAckDelay()).isEqualTo(0);

        assertThat(ack.getAckedPacketNumbers()).containsOnly(10L, 9L, 8L, /* gap: 7, 6 */ 5L, 4L, /* gap: 3 */ 2L, 1L, 0L);
        assertThat(ack.toString()).contains("[10-8,5-4,2-0|");
    }

    @Test
    void testGenerateAckWithSinglePacketNumber() throws Exception {
        AckFrame ackFrame = new AckFrame(3);
        assertThat(ackFrame.getAckedPacketNumbers()).containsExactly(3L);

        //                                ackframe largest delay #blocks #acked-below
        byte[] binaryFrame = new byte[] { 0x02,    0x03,   0x00, 0x00,   0x00 };
        assertThat(new AckFrame().parse(ByteBuffer.wrap(binaryFrame), mock(Logger.class)).getAckedPacketNumbers()).containsExactly(3L);
        assertThat(getBytes(ackFrame)).isEqualTo(binaryFrame);
        assertThat(ackFrame.toString()).contains("[3|");
    }

    @Test
    void testGenerateAckWithSinglePacketNumberAsList() throws Exception {
        AckFrame ackFrame = new AckFrame(new Range(3L));
        assertThat(ackFrame.getAckedPacketNumbers()).containsExactly(3L);

        //                                ackframe largest delay #blocks #acked-below
        byte[] binaryFrame = new byte[] { 0x02,    0x03,   0x00, 0x00,   0x00 };
        assertThat(new AckFrame().parse(ByteBuffer.wrap(binaryFrame), mock(Logger.class)).getAckedPacketNumbers()).containsExactly(3L);
        assertThat(getBytes(ackFrame)).isEqualTo(binaryFrame);
        assertThat(ackFrame.toString()).contains("[3|");
    }

    @Test
    void testGenerateAckWithListOfConsecutivePacketNumbers() throws Exception {
        AckFrame ackFrame = new AckFrame(new Range(0L, 4L));
        assertThat(ackFrame.getAckedPacketNumbers()).containsExactly(4L, 3L, 2L, 1L, 0L);

        //                                ackframe largest delay #blocks #acked-below
        byte[] binaryFrame = new byte[] { 0x02,    0x04,   0x00, 0x00,   0x04 };
        assertThat(new AckFrame().parse(ByteBuffer.wrap(binaryFrame), mock(Logger.class)).getAckedPacketNumbers()).containsExactly(4L, 3L, 2L, 1L, 0L);
        assertThat(getBytes(ackFrame)).isEqualTo(binaryFrame);
        assertThat(ackFrame.toString()).contains("[4-0|");
    }

    @Test
    void testGenerateAckWithListWithOneGap() throws Exception {
        AckFrame ackFrame = new AckFrame(List.of(new Range(4L, 5L), new Range(0L, 1L)));
        assertThat(ackFrame.getAckedPacketNumbers()).containsExactly(5L, 4L, 1L, 0L);

        //                                ackframe largest delay #blocks #acked-below gap-1 below
        byte[] binaryFrame = new byte[] { 0x02,    0x05,   0x00, 0x01,   0x01,        0x01, 0x01 };
        assertThat(new AckFrame().parse(ByteBuffer.wrap(binaryFrame), mock(Logger.class)).getAckedPacketNumbers()).containsExactly(5L, 4L, 1L, 0L);
        assertThat(getBytes(ackFrame)).isEqualTo(binaryFrame);
        assertThat(ackFrame.toString()).contains("[5-4,1-0|");
    }

    @Test
    void testGenerateAckWithListWithSmallGap() throws Exception {
        AckFrame ackFrame = new AckFrame(List.of(new Range(4L, 5L), new Range(0L, 2L)));
        assertThat(ackFrame.getAckedPacketNumbers()).containsExactly(5L, 4L, 2L, 1L, 0L);

        //                                ackframe largest delay #blocks #acked-below gap-1 below
        byte[] binaryFrame = new byte[] { 0x02,    0x05,   0x00, 0x01,   0x01,        0x00, 0x02 };
        assertThat(new AckFrame().parse(ByteBuffer.wrap(binaryFrame), mock(Logger.class)).getAckedPacketNumbers()).containsExactly(5L, 4L, 2L, 1L, 0L);
        assertThat(getBytes(ackFrame)).isEqualTo(binaryFrame);
        assertThat(ackFrame.toString()).contains("[5-4,2-0|");
    }

    @Test
    void parseAckFrameWithECNCounts() throws Exception {
        //                         ackframe   largest  delay ack-block-count #acked-below largest gap (size) #acked-below ecn-counts
        byte[] data = new byte[] { 0x03,      0x02,    0x00, 0x01,           0x00,                0x00,      0x00,        0x70, 0x39, 0x70, 0x39, 0x70, 0x39 };

        ByteBuffer buffer = ByteBuffer.wrap(data);
        AckFrame ack = new AckFrame().parse(buffer, mock(Logger.class));
        assertThat(buffer.position()).isEqualTo(data.length);
        assertThat(ack.getLargestAcknowledged()).isEqualTo(2);
        assertThat(ack.getAckDelay()).isEqualTo(0);

        assertThat(ack.getAckedPacketNumbers()).containsOnly(2L, 0L);
        assertThat(ack.toString()).contains("[2,0|");
    }

    @Test
    void parseAckFrameThatImpliesNegativePacketNumber() {
        //                         ackframe   largest  delay ack-block-count #acked-below largest gap (size) #acked-below ecn-counts
        byte[] data = new byte[] { 0x03,      0x02,    0x00, 0x01,           0x00,                0x01,      0x00,        0x70, 0x39, 0x70, 0x39, 0x70, 0x39 };

        assertThatThrownBy(() -> new AckFrame().parse(ByteBuffer.wrap(data), mock(Logger.class)))
            .isInstanceOf(TransportError.class)
            .hasMessageContaining("negative packet number");
    }
    //endregion

    //region serialize frame
    @Test
    void checkAckDelayInEncodedFrameIsInMicroSecondsAndTakesAckDelayExponentIntoAccount() throws Exception {
        // Given
        int ackDelayInMillis = 25;
        AckFrame ackFrame = new AckFrame(Version.getDefault(), List.of(new Range(5, 5)), ackDelayInMillis);
        ByteBuffer buffer = ByteBuffer.allocate(6);
        int senderAckDelayFactor = (int) Math.pow(2, FIXED_SENDER_ACK_DELAY_EXPONENT);

        // When
        ackFrame.serialize(buffer);
        buffer.flip();

        // Then
        assertThat(VariableLengthInteger.parse(buffer)).isEqualTo(0x02);  // frame type
        assertThat(VariableLengthInteger.parse(buffer)).isEqualTo(0x05);  // largest ack
        long ackDelay = VariableLengthInteger.parseLong(buffer);
        long expectedEncodedAckDelayValue = ackDelayInMillis * 1000 / senderAckDelayFactor;
        assertThat(ackDelay).isEqualTo(expectedEncodedAckDelayValue);
    }
    //endregion
}