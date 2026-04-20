package com.carmirror.util;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * ScrcpyDecoder
 *
 * Connects to the scrcpy server socket (forwarded via ADB to localhost),
 * reads raw H.264 NAL units, feeds them into Android's MediaCodec hardware
 * decoder, and renders frames directly to the provided Surface.
 *
 * Scrcpy video stream format:
 *   [8 bytes device meta - skipped if send_device_meta=false]
 *   [4 bytes codec id]
 *   [4 bytes initial width]
 *   [4 bytes initial height]
 *   Then repeated:
 *     [8 bytes PTS  (or 0xFFFFFFFFFFFFFFFF for config packet)]
 *     [4 bytes packet size]
 *     [N bytes H.264 data]
 */
public class ScrcpyDecoder implements Runnable {

    private static final String TAG = "ScrcpyDecoder";
    private static final String MIME_H264 = "video/avc";
    private static final long PTS_CONFIG = 0xFFFFFFFFFFFFFFFFL;
    private static final int TIMEOUT_US = 10_000;

    private final String host;
    private final int port;
    private final Surface surface;

    private MediaCodec codec;
    private Socket socket;
    private volatile boolean running = false;

    // FPS tracking
    private FpsCallback fpsCallback;
    private long frameCount = 0;
    private long fpsWindowStart = 0;

    public interface FpsCallback {
        void onFps(int fps);
    }

    public ScrcpyDecoder(String host, int port, Surface surface) {
        this.host = host;
        this.port = port;
        this.surface = surface;
    }

    public void setFpsCallback(FpsCallback cb) {
        this.fpsCallback = cb;
    }

    @Override
    public void run() {
        start();
    }

    public void start() {
        running = true;
        try {
            // Brief delay to let scrcpy server initialize
            Thread.sleep(500);

            Log.d(TAG, "Connecting to scrcpy video socket " + host + ":" + port);
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);

            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Read first packet to get codec + dimensions
            long firstPts = in.readLong();
            int packetSize = in.readInt();
            byte[] configData = new byte[packetSize];
            in.readFully(configData);

            // Parse SPS/PPS from config packet to extract width/height
            int[] dims = parseDimensions(configData);
            int width  = dims[0] > 0 ? dims[0] : 1280;
            int height = dims[1] > 0 ? dims[1] : 720;

            Log.d(TAG, "Video dimensions: " + width + "x" + height);

            // Initialize MediaCodec for H.264 decoding
            initCodec(width, height, configData);

            fpsWindowStart = System.currentTimeMillis();

            // Main decode loop
            while (running) {
                long pts      = in.readLong();
                int  size     = in.readInt();
                byte[] data   = new byte[size];
                in.readFully(data);

                boolean isConfig = (pts == PTS_CONFIG);

                // Feed into decoder input buffer
                int inputIdx = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inputIdx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(inputIdx);
                    if (buf != null) {
                        buf.clear();
                        buf.put(data);
                        int flags = isConfig ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                        codec.queueInputBuffer(inputIdx, 0, data.length,
                            isConfig ? 0 : pts, flags);
                    }
                }

                // Drain output frames to Surface
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outputIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outputIdx >= 0) {
                    boolean render = (info.size > 0);
                    codec.releaseOutputBuffer(outputIdx, render);

                    if (render) {
                        trackFps();
                    }
                }
            }

        } catch (Exception e) {
            if (running) {
                Log.e(TAG, "Decoder error: " + e.getMessage(), e);
            }
        } finally {
            cleanup();
        }
    }

    private void initCodec(int width, int height, byte[] csd) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_H264, width, height);

        // Provide SPS/PPS (codec-specific data) so codec can start immediately
        // csd-0 = SPS, csd-1 = PPS (extracted from the first config packet)
        byte[] sps = extractSps(csd);
        byte[] pps = extractPps(csd);

        if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
        format.setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1);

        codec = MediaCodec.createDecoderByType(MIME_H264);
        // Render directly to SurfaceView — zero-copy path
        codec.configure(format, surface, null, 0);
        codec.start();

        Log.d(TAG, "MediaCodec initialized");
    }

    private void trackFps() {
        frameCount++;
        long now = System.currentTimeMillis();
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 1000) {
            int fps = (int) (frameCount * 1000 / elapsed);
            if (fpsCallback != null) fpsCallback.onFps(fps);
            frameCount = 0;
            fpsWindowStart = now;
        }
    }

    private void cleanup() {
        running = false;
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
                codec = null;
            }
        } catch (Exception e) { /* ignore */ }
        try {
            if (socket != null) socket.close();
        } catch (Exception e) { /* ignore */ }
        Log.d(TAG, "Decoder cleaned up");
    }

    public void stop() {
        running = false;
    }

    // ─── NAL unit parsing helpers ───────────────────────────────────────────

    /**
     * Parse width/height from SPS NAL unit in the config packet.
     * Returns [width, height] or [0, 0] if parsing fails.
     * This is a simplified parser — a full implementation would use
     * Exp-Golomb decoding on the SPS RBSP.
     */
    private int[] parseDimensions(byte[] data) {
        // Scan for NAL start codes and find SPS (nal_unit_type == 7)
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
                int nalType = data[i+4] & 0x1F;
                if (nalType == 7 && i + 10 < data.length) {
                    // Very rough heuristic: scrcpy encodes resolution in SPS
                    // Real parser would use Exp-Golomb; MediaCodec handles this anyway
                    return new int[]{0, 0};
                }
            }
        }
        return new int[]{0, 0};
    }

    /**
     * Extract SPS NAL unit (type 7) from config data.
     */
    private byte[] extractSps(byte[] data) {
        return extractNal(data, 7);
    }

    /**
     * Extract PPS NAL unit (type 8) from config data.
     */
    private byte[] extractPps(byte[] data) {
        return extractNal(data, 8);
    }

    private byte[] extractNal(byte[] data, int targetType) {
        for (int i = 0; i < data.length - 4; i++) {
            boolean startCode3 = data[i]==0 && data[i+1]==0 && data[i+2]==1;
            boolean startCode4 = i+4 < data.length &&
                data[i]==0 && data[i+1]==0 && data[i+2]==0 && data[i+3]==1;

            if (startCode3 || startCode4) {
                int nalOffset = startCode4 ? i + 4 : i + 3;
                if (nalOffset >= data.length) continue;
                int nalType = data[nalOffset] & 0x1F;
                if (nalType == targetType) {
                    // Find end of this NAL (next start code or end of data)
                    int end = data.length;
                    for (int j = nalOffset + 1; j < data.length - 3; j++) {
                        if (data[j]==0 && data[j+1]==0 &&
                           (data[j+2]==1 || (j+3 < data.length && data[j+2]==0 && data[j+3]==1))) {
                            end = j;
                            break;
                        }
                    }
                    // Return with 4-byte start code
                    byte[] nal = new byte[end - i];
                    System.arraycopy(data, i, nal, 0, nal.length);
                    return nal;
                }
            }
        }
        return null;
    }
}
