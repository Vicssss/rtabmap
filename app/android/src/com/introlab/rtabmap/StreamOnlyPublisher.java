package com.introlab.rtabmap;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Base64;
import android.util.Log;

import com.google.ar.core.Pose;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class StreamOnlyPublisher {
    private static final String TAG = StreamOnlyPublisher.class.getSimpleName();

    private static final Object LOCK = new Object();
    private static boolean enabled = false;
    private static String host = "127.0.0.1";
    private static int port = 8765;

    private static Socket socket = null;
    private static OutputStream outputStream = null;

    private static float ax = 0.0f;
    private static float ay = 0.0f;
    private static float az = 0.0f;
    private static float gx = 0.0f;
    private static float gy = 0.0f;
    private static float gz = 0.0f;
    private static long imuStampNs = 0L;

    private static long frameId = 0L;

    public static void configure(boolean streamEnabled, String serverHost, int serverPort) {
        synchronized (LOCK) {
            enabled = streamEnabled;
            host = serverHost == null || serverHost.trim().isEmpty() ? "127.0.0.1" : serverHost.trim();
            port = serverPort;
            if (!enabled) {
                closeLocked();
            }
        }
    }

    public static boolean isEnabled() {
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static void updateAccel(float x, float y, float z, long stampNs) {
        synchronized (LOCK) {
            ax = x;
            ay = y;
            az = z;
            imuStampNs = stampNs;
        }
    }

    public static void updateGyro(float x, float y, float z, long stampNs) {
        synchronized (LOCK) {
            gx = x;
            gy = y;
            gz = z;
            imuStampNs = stampNs;
        }
    }

    public static void close() {
        synchronized (LOCK) {
            closeLocked();
        }
    }

    private static void closeLocked() {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        outputStream = null;
        socket = null;
    }

    private static boolean ensureConnectedLocked() {
        if (socket != null && socket.isConnected() && !socket.isClosed() && outputStream != null) {
            return true;
        }
        closeLocked();
        try {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            outputStream = socket.getOutputStream();

            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String secKey = Base64.encodeToString(nonce, Base64.NO_WRAP);
            String request = "GET / HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: " + secKey + "\r\n\r\n";
            outputStream.write(request.getBytes("UTF-8"));
            outputStream.flush();

            byte[] responseBuffer = new byte[2048];
            int n = in.read(responseBuffer);
            if (n <= 0) {
                throw new Exception("empty handshake response");
            }
            String response = new String(responseBuffer, 0, n, "UTF-8");
            if (!response.startsWith("HTTP/1.1 101")) {
                throw new Exception("websocket upgrade failed: " + response);
            }

            String accept = null;
            String[] lines = response.split("\\r\\n");
            for (String line : lines) {
                if (line.toLowerCase().startsWith("sec-websocket-accept:")) {
                    accept = line.substring(line.indexOf(':') + 1).trim();
                    break;
                }
            }
            if (accept != null) {
                String expected = Base64.encodeToString(
                        MessageDigest.getInstance("SHA-1")
                                .digest((secKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")),
                        Base64.NO_WRAP);
                if (!expected.equals(accept)) {
                    throw new Exception("invalid Sec-WebSocket-Accept");
                }
            }
            Log.i(TAG, "Connected to websocket server " + host + ":" + port);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "WebSocket connection failed", e);
            closeLocked();
            return false;
        }
    }

    private static void sendTextFrameLocked(String payload) throws Exception {
        byte[] data = payload.getBytes("UTF-8");
        ByteArrayOutputStream frame = new ByteArrayOutputStream();

        frame.write(0x81); // FIN + text frame
        int length = data.length;
        if (length <= 125) {
            frame.write(0x80 | length);
        } else if (length <= 65535) {
            frame.write(0x80 | 126);
            frame.write((length >> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; --i) {
                frame.write((length >> (8 * i)) & 0xFF);
            }
        }

        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        frame.write(mask);

        for (int i = 0; i < data.length; ++i) {
            frame.write(data[i] ^ mask[i % 4]);
        }

        outputStream.write(frame.toByteArray());
        outputStream.flush();
    }

    private static byte[] yuv420888ToNv21(
            int width,
            int height,
            ByteBuffer y,
            ByteBuffer u,
            ByteBuffer v,
            int yRowStride,
            int uRowStride,
            int vRowStride,
            int uPixelStride,
            int vPixelStride) {
        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer yBuf = y.duplicate();
        ByteBuffer uBuf = u.duplicate();
        ByteBuffer vBuf = v.duplicate();

        int pos = 0;
        for (int row = 0; row < height; row++) {
            int yRowStart = row * yRowStride;
            for (int col = 0; col < width; col++) {
                nv21[pos++] = yBuf.get(yRowStart + col);
            }
        }

        int uvPos = width * height;
        int uvHeight = height / 2;
        int uvWidth = width / 2;
        for (int row = 0; row < uvHeight; row++) {
            int uRowStart = row * uRowStride;
            int vRowStart = row * vRowStride;
            for (int col = 0; col < uvWidth; col++) {
                nv21[uvPos++] = vBuf.get(vRowStart + col * vPixelStride);
                nv21[uvPos++] = uBuf.get(uRowStart + col * uPixelStride);
            }
        }

        return nv21;
    }

    public static void publishFrame(
            double stamp,
            int width,
            int height,
            ByteBuffer y,
            ByteBuffer u,
            ByteBuffer v,
            int yRowStride,
            int uRowStride,
            int vRowStride,
            int uPixelStride,
            int vPixelStride,
            Pose pose,
            float fx,
            float fy,
            float cx,
            float cy) {

        synchronized (LOCK) {
            if (!enabled) {
                return;
            }
            if (!ensureConnectedLocked()) {
                return;
            }
            try {
                byte[] nv21 = yuv420888ToNv21(width, height, y, u, v, yRowStride, uRowStride, vRowStride, uPixelStride, vPixelStride);
                YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
                ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, width, height), 70, jpegOut);
                String jpegB64 = Base64.encodeToString(jpegOut.toByteArray(), Base64.NO_WRAP);

                JSONObject root = new JSONObject();
                root.put("type", "rgb_imu_frame");
                root.put("frame_id", ++frameId);
                root.put("stamp", stamp);
                root.put("width", width);
                root.put("height", height);
                root.put("encoding", "jpeg_base64");
                root.put("image", jpegB64);

                JSONObject intrinsics = new JSONObject();
                intrinsics.put("fx", fx);
                intrinsics.put("fy", fy);
                intrinsics.put("cx", cx);
                intrinsics.put("cy", cy);
                root.put("intrinsics", intrinsics);

                JSONObject poseObj = new JSONObject();
                poseObj.put("tx", pose.tx());
                poseObj.put("ty", pose.ty());
                poseObj.put("tz", pose.tz());
                poseObj.put("qx", pose.qx());
                poseObj.put("qy", pose.qy());
                poseObj.put("qz", pose.qz());
                poseObj.put("qw", pose.qw());
                root.put("pose", poseObj);

                JSONObject imu = new JSONObject();
                imu.put("ax", ax);
                imu.put("ay", ay);
                imu.put("az", az);
                imu.put("gx", gx);
                imu.put("gy", gy);
                imu.put("gz", gz);
                imu.put("stamp_ns", imuStampNs);
                root.put("imu", imu);

                sendTextFrameLocked(root.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to publish streaming frame", e);
                closeLocked();
            }
        }
    }
}
