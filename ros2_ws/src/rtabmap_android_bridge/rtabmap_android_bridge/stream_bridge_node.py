import asyncio
import base64
import json
import threading

import cv2
import numpy as np
import rclpy
from cv_bridge import CvBridge
from geometry_msgs.msg import TransformStamped
from rclpy.node import Node
from sensor_msgs.msg import CameraInfo, Image, Imu
from tf2_ros import TransformBroadcaster


class StreamBridgeNode(Node):
    def __init__(self):
        super().__init__('rtabmap_android_stream_bridge')

        self.declare_parameter('listen_host', '0.0.0.0')
        self.declare_parameter('listen_port', 8765)
        self.declare_parameter('camera_frame_id', 'camera_color_optical_frame')
        self.declare_parameter('imu_frame_id', 'imu_link')
        self.declare_parameter('publish_tf', True)

        self.listen_host = self.get_parameter('listen_host').get_parameter_value().string_value
        self.listen_port = self.get_parameter('listen_port').get_parameter_value().integer_value
        self.camera_frame_id = self.get_parameter('camera_frame_id').get_parameter_value().string_value
        self.imu_frame_id = self.get_parameter('imu_frame_id').get_parameter_value().string_value
        self.publish_tf = self.get_parameter('publish_tf').get_parameter_value().bool_value

        self.bridge = CvBridge()
        self.image_pub = self.create_publisher(Image, '/camera/color/image_raw', 10)
        self.camera_info_pub = self.create_publisher(CameraInfo, '/camera/color/camera_info', 10)
        self.imu_pub = self.create_publisher(Imu, '/imu/data', 100)
        self.tf_broadcaster = TransformBroadcaster(self)

        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._thread.start()

        self._schedule_server()
        self.get_logger().info(f'Listening websocket on ws://{self.listen_host}:{self.listen_port}')

    def _run_loop(self):
        asyncio.set_event_loop(self._loop)
        self._loop.run_forever()

    def _schedule_server(self):
        async def _start():
            import websockets
            return await websockets.serve(self._on_ws_message, self.listen_host, self.listen_port, max_size=8 * 1024 * 1024)

        fut = asyncio.run_coroutine_threadsafe(_start(), self._loop)
        fut.result()

    async def _on_ws_message(self, websocket):
        self.get_logger().info('Android client connected.')
        try:
            async for message in websocket:
                if isinstance(message, bytes):
                    try:
                        message = message.decode('utf-8')
                    except Exception:
                        continue
                self._handle_payload(message)
        except Exception as e:
            self.get_logger().warn(f'WebSocket session ended: {e}')

    def _handle_payload(self, message: str):
        try:
            data = json.loads(message)
        except Exception:
            return

        if data.get('type') != 'rgb_imu_frame':
            return

        stamp = self.get_clock().now().to_msg()

        image_b64 = data.get('image', '')
        if image_b64:
            jpeg = base64.b64decode(image_b64)
            np_arr = np.frombuffer(jpeg, dtype=np.uint8)
            bgr = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
            if bgr is not None:
                image_msg = self.bridge.cv2_to_imgmsg(bgr, encoding='bgr8')
                image_msg.header.stamp = stamp
                image_msg.header.frame_id = self.camera_frame_id
                self.image_pub.publish(image_msg)

                intr = data.get('intrinsics', {})
                cam_info = CameraInfo()
                cam_info.header = image_msg.header
                cam_info.width = int(data.get('width', bgr.shape[1]))
                cam_info.height = int(data.get('height', bgr.shape[0]))
                fx = float(intr.get('fx', 0.0))
                fy = float(intr.get('fy', 0.0))
                cx = float(intr.get('cx', 0.0))
                cy = float(intr.get('cy', 0.0))
                cam_info.k = [fx, 0.0, cx,
                              0.0, fy, cy,
                              0.0, 0.0, 1.0]
                cam_info.p = [fx, 0.0, cx, 0.0,
                              0.0, fy, cy, 0.0,
                              0.0, 0.0, 1.0, 0.0]
                cam_info.r = [1.0, 0.0, 0.0,
                              0.0, 1.0, 0.0,
                              0.0, 0.0, 1.0]
                self.camera_info_pub.publish(cam_info)

        imu_data = data.get('imu', {})
        imu_msg = Imu()
        imu_msg.header.stamp = stamp
        imu_msg.header.frame_id = self.imu_frame_id
        imu_msg.linear_acceleration.x = float(imu_data.get('ax', 0.0))
        imu_msg.linear_acceleration.y = float(imu_data.get('ay', 0.0))
        imu_msg.linear_acceleration.z = float(imu_data.get('az', 0.0))
        imu_msg.angular_velocity.x = float(imu_data.get('gx', 0.0))
        imu_msg.angular_velocity.y = float(imu_data.get('gy', 0.0))
        imu_msg.angular_velocity.z = float(imu_data.get('gz', 0.0))
        self.imu_pub.publish(imu_msg)

        if self.publish_tf and 'pose' in data:
            pose = data.get('pose', {})
            tf_msg = TransformStamped()
            tf_msg.header.stamp = stamp
            tf_msg.header.frame_id = 'map'
            tf_msg.child_frame_id = self.camera_frame_id
            tf_msg.transform.translation.x = float(pose.get('tx', 0.0))
            tf_msg.transform.translation.y = float(pose.get('ty', 0.0))
            tf_msg.transform.translation.z = float(pose.get('tz', 0.0))
            tf_msg.transform.rotation.x = float(pose.get('qx', 0.0))
            tf_msg.transform.rotation.y = float(pose.get('qy', 0.0))
            tf_msg.transform.rotation.z = float(pose.get('qz', 0.0))
            tf_msg.transform.rotation.w = float(pose.get('qw', 1.0))
            self.tf_broadcaster.sendTransform(tf_msg)


def main(args=None):
    rclpy.init(args=args)
    node = StreamBridgeNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    node.destroy_node()
    rclpy.shutdown()


if __name__ == '__main__':
    main()
