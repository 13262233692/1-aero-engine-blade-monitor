package com.aero.blademonitor.parser;

import com.aero.blademonitor.config.AppProperties;
import com.aero.blademonitor.model.BladeDataFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class HexStreamParser {

    public static final short FRAME_HEADER = (short) 0xAA55;
    public static final short FRAME_TAILER = (short) 0x55AA;
    public static final int FRAME_SIZE = 24;

    public static final int OFFSET_HEADER = 0;
    public static final int OFFSET_SEQUENCE = 2;
    public static final int OFFSET_RPM = 4;
    public static final int OFFSET_BLADE_INDEX = 7;
    public static final int OFFSET_BLADE_COUNT = 8;
    public static final int OFFSET_STRAIN_ADC = 9;
    public static final int OFFSET_TEMPERATURE = 12;
    public static final int OFFSET_TIMESTAMP = 14;
    public static final int OFFSET_CHECKSUM = 20;
    public static final int OFFSET_TAILER = 22;

    public static final double ADC_FULL_SCALE = 8388607.0;
    public static final double STRAIN_FULL_SCALE = 5000.0;

    private final AtomicLong framesParsed = new AtomicLong(0);
    private final AtomicLong framesInvalid = new AtomicLong(0);
    private final AtomicLong lastSequence = new AtomicLong(-1);
    private final AtomicLong sequenceGaps = new AtomicLong(0);

    private final AppProperties appProperties;

    public HexStreamParser(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public List<BladeDataFrame> parse(byte[] payload) {
        List<BladeDataFrame> frames = new ArrayList<>();

        if (payload == null || payload.length < FRAME_SIZE) {
            return frames;
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int offset = 0;
        while (offset + FRAME_SIZE <= payload.length) {
            int frameStart = findFrameHeader(payload, offset);
            if (frameStart == -1) {
                break;
            }

            if (frameStart + FRAME_SIZE > payload.length) {
                break;
            }

            ByteBuffer frameBuf = ByteBuffer.wrap(payload, frameStart, FRAME_SIZE);
            frameBuf.order(ByteOrder.BIG_ENDIAN);

            BladeDataFrame frame = parseSingleFrame(frameBuf);
            if (frame != null) {
                frames.add(frame);
                framesParsed.incrementAndGet();
            } else {
                framesInvalid.incrementAndGet();
            }

            offset = frameStart + FRAME_SIZE;
        }

        return frames;
    }

    private int findFrameHeader(byte[] data, int startOffset) {
        for (int i = startOffset; i <= data.length - 2; i++) {
            int b1 = data[i] & 0xFF;
            int b2 = data[i + 1] & 0xFF;
            int header = (b1 << 8) | b2;
            if (header == (FRAME_HEADER & 0xFFFF)) {
                return i;
            }
        }
        return -1;
    }

    private BladeDataFrame parseSingleFrame(ByteBuffer buf) {
        try {
            short header = buf.getShort(OFFSET_HEADER);
            short tailer = buf.getShort(OFFSET_TAILER);

            if (header != FRAME_HEADER || tailer != FRAME_TAILER) {
                return createInvalidFrame("Header/tailer mismatch");
            }

            int expectedChecksum = computeCRC16(buf.array(), buf.arrayOffset(), FRAME_SIZE - 4);
            int actualChecksum = buf.getShort(OFFSET_CHECKSUM) & 0xFFFF;
            if (expectedChecksum != actualChecksum) {
                return createInvalidFrame("Checksum mismatch");
            }

            int sequence = buf.getShort(OFFSET_SEQUENCE) & 0xFFFF;

            long last = lastSequence.get();
            if (last != -1 && sequence != (last + 1) % 65536) {
                int gap;
                if (sequence > last) {
                    gap = sequence - (int) last - 1;
                } else {
                    gap = (65536 - (int) last - 1) + sequence;
                }
                if (gap > 0 && gap < 1000) {
                    sequenceGaps.addAndGet(gap);
                }
            }
            lastSequence.set(sequence);

            int rpmRaw = ((buf.get(OFFSET_RPM) & 0xFF) << 16)
                    | ((buf.get(OFFSET_RPM + 1) & 0xFF) << 8)
                    | (buf.get(OFFSET_RPM + 2) & 0xFF);
            double rpm = rpmRaw / 10.0;

            int bladeIndex = buf.get(OFFSET_BLADE_INDEX) & 0xFF;
            int bladeCount = buf.get(OFFSET_BLADE_COUNT) & 0xFF;
            if (bladeCount == 0) bladeCount = appProperties.getData().getBladeCount();

            int strainRaw = ((buf.get(OFFSET_STRAIN_ADC) & 0xFF) << 16)
                    | ((buf.get(OFFSET_STRAIN_ADC + 1) & 0xFF) << 8)
                    | (buf.get(OFFSET_STRAIN_ADC + 2) & 0xFF);

            if ((strainRaw & 0x800000) != 0) {
                strainRaw = strainRaw - 0x1000000;
            }

            double strain = (strainRaw / ADC_FULL_SCALE) * STRAIN_FULL_SCALE;

            int tempRaw = buf.getShort(OFFSET_TEMPERATURE) & 0xFFFF;
            double temperature = (tempRaw / 65535.0) * 200.0 - 40.0;

            long timestamp = 0;
            for (int i = 0; i < 6; i++) {
                timestamp = (timestamp << 8) | (buf.get(OFFSET_TIMESTAMP + i) & 0xFF);
            }

            BladeDataFrame frame = new BladeDataFrame();
            frame.setFrameId(timestamp);
            frame.setTimestamp(System.nanoTime());
            frame.setReceiveTime(java.time.Instant.now());
            frame.setRpm(rpm);
            frame.setBladeIndex(bladeIndex);
            frame.setBladeCount(bladeCount);
            frame.setStrain(strain);
            frame.setTemperature(temperature);
            frame.setRawAdcValue(strainRaw);
            frame.setPacketSequence(sequence);
            frame.setValid(true);

            return frame;

        } catch (Exception e) {
            return createInvalidFrame("Parse exception: " + e.getMessage());
        }
    }

    private BladeDataFrame createInvalidFrame(String note) {
        BladeDataFrame frame = new BladeDataFrame();
        frame.setValid(false);
        frame.setValidationNote(note);
        frame.setTimestamp(System.nanoTime());
        return frame;
    }

    private static int computeCRC16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    public static byte[] buildFrame(int sequence, double rpm, int bladeIndex,
                                     int bladeCount, double strain,
                                     double temperature, long timestampUs) {
        ByteBuffer buf = ByteBuffer.allocate(FRAME_SIZE);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putShort(FRAME_HEADER);
        buf.putShort((short) (sequence & 0xFFFF));

        int rpmRaw = (int) Math.round(rpm * 10);
        rpmRaw = Math.min(rpmRaw, 0xFFFFFF);
        buf.put((byte) ((rpmRaw >> 16) & 0xFF));
        buf.put((byte) ((rpmRaw >> 8) & 0xFF));
        buf.put((byte) (rpmRaw & 0xFF));

        buf.put((byte) (bladeIndex & 0xFF));
        buf.put((byte) (bladeCount & 0xFF));

        double normalizedStrain = Math.max(-1.0, Math.min(1.0, strain / STRAIN_FULL_SCALE));
        int strainRaw = (int) Math.round(normalizedStrain * ADC_FULL_SCALE);
        strainRaw = strainRaw & 0xFFFFFF;
        buf.put((byte) ((strainRaw >> 16) & 0xFF));
        buf.put((byte) ((strainRaw >> 8) & 0xFF));
        buf.put((byte) (strainRaw & 0xFF));

        int tempRaw = (int) Math.round(((temperature + 40.0) / 200.0) * 65535.0);
        tempRaw = Math.max(0, Math.min(65535, tempRaw));
        buf.putShort((short) tempRaw);

        for (int i = 5; i >= 0; i--) {
            buf.put((byte) ((timestampUs >> (i * 8)) & 0xFF));
        }

        int checksum = computeCRC16(buf.array(), 0, FRAME_SIZE - 4);
        buf.putShort((short) checksum);
        buf.putShort(FRAME_TAILER);

        return buf.array();
    }

    public long getFramesParsed() { return framesParsed.get(); }
    public long getFramesInvalid() { return framesInvalid.get(); }
    public long getSequenceGaps() { return sequenceGaps.get(); }

    public void resetCounters() {
        framesParsed.set(0);
        framesInvalid.set(0);
        sequenceGaps.set(0);
        lastSequence.set(-1);
    }
}
