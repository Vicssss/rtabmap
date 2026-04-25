# rtabmap_android_bridge (ROS 2 Humble)

Bridge minimal pour recevoir les trames `StreamOnly` Android (WebSocket JSON) et publier des topics ROS 2.

## Topics publiés

- `/camera/color/image_raw` (`sensor_msgs/Image`)
- `/camera/color/camera_info` (`sensor_msgs/CameraInfo`)
- `/imu/data` (`sensor_msgs/Imu`)
- `/tf` (optionnel, pose caméra)

> Cette première version minimale reçoit **RGB + IMU**.

## Dépendances

Dans l'environnement ROS 2 Humble:

```bash
sudo apt install ros-humble-cv-bridge python3-opencv python3-websockets
```

## Build

Depuis la racine `ros2_ws/`:

```bash
colcon build --packages-select rtabmap_android_bridge
source install/setup.bash
```

## Lancement

```bash
ros2 run rtabmap_android_bridge stream_bridge_node --ros-args -p listen_host:=0.0.0.0 -p listen_port:=8765
```

Paramètres utiles:

- `listen_host` (défaut: `0.0.0.0`)
- `listen_port` (défaut: `8765`)
- `camera_frame_id` (défaut: `camera_color_optical_frame`)
- `imu_frame_id` (défaut: `imu_link`)
- `publish_tf` (défaut: `true`)

## Côté Android

Activer **StreamOnly Mode** dans les Settings RTAB-Map puis configurer:
- `Stream Server IP` = IP du PC
- `Stream Server Port` = port du bridge (ex: `8765`)
