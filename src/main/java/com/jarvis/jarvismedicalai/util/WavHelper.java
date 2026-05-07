package com.jarvis.jarvismedicalai.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WavHelper — Converts raw PCM audio bytes into a valid WAV file.
 *
 * Gemini's audio/wav MIME type requires a proper RIFF/WAV header.
 * Without the header the API returns a 400 "invalid audio" error.
 *
 * Assumed audio format (must match your microphone capture settings):
 *   - Sample rate : 16,000 Hz  (16kHz — standard for speech recognition)
 *   - Channels    : 1          (Mono)
 *   - Bit depth   : 16-bit PCM (Little Endian)
 *
 * If your hardware captures at a different rate (e.g., 44100 Hz),
 * change SAMPLE_RATE below to match — otherwise Gemini will
 * misinterpret the audio and return unreliable results.
 */
public class WavHelper {


    private static final int    SAMPLE_RATE   = 16000;
    private static final short  CHANNELS      = 1;
    private static final short  BITS_PER_SAMPLE = 16;


    private static final short  AUDIO_FORMAT  = 1;
    private static final int    BYTE_RATE     = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8);
    private static final short  BLOCK_ALIGN   = (short)(CHANNELS * (BITS_PER_SAMPLE / 8));


    private static final int    HEADER_SIZE   = 44;

    /**
     * Prepends a valid 44-byte RIFF/WAV header to raw PCM audio data.
     *
     * @param pcmData  Raw PCM audio bytes (no header).
     * @return         Complete WAV file bytes (header + PCM data).
     * @throws IllegalArgumentException if pcmData is null or empty.
     */
    public static byte[] addWavHeader(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) {
            throw new IllegalArgumentException("[WavHelper] Cannot create WAV: PCM data is null or empty.");
        }

        int pcmDataLen    = pcmData.length;
        int totalFileSize = HEADER_SIZE + pcmDataLen;


        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);


        header.put((byte)'R');
        header.put((byte)'I');
        header.put((byte)'F');
        header.put((byte)'F');


        header.putInt(totalFileSize - 8);


        header.put((byte)'W');
        header.put((byte)'A');
        header.put((byte)'V');
        header.put((byte)'E');

        header.put((byte)'f');
        header.put((byte)'m');
        header.put((byte)'t');
        header.put((byte)' ');

        header.putInt(16);

        header.putShort(AUDIO_FORMAT);

        header.putShort(CHANNELS);

        header.putInt(SAMPLE_RATE);

        header.putInt(BYTE_RATE);

        header.putShort(BLOCK_ALIGN);

        header.putShort(BITS_PER_SAMPLE);

        header.put((byte)'d');
        header.put((byte)'a');
        header.put((byte)'t');
        header.put((byte)'a');

        header.putInt(pcmDataLen);

        byte[] wavFile = new byte[totalFileSize];
        byte[] headerBytes = header.array();

        System.arraycopy(headerBytes, 0, wavFile, 0,          HEADER_SIZE);
        System.arraycopy(pcmData,     0, wavFile, HEADER_SIZE, pcmDataLen);

        return wavFile;
    }

    /**
     * Returns the duration of the audio in seconds, based on PCM data length.
     * Useful for logging and validating minimum recording length.
     *
     * @param pcmDataLength  Length of the raw PCM byte array.
     * @return               Duration in seconds (floating point).
     */
    public static double getDurationSeconds(int pcmDataLength) {
        return (double) pcmDataLength / BYTE_RATE;
    }
}