# ARCHITECTURE_ANDROID_ROS2_STREAMING

## Objectif
Transformer l'app Android RTAB-Map en **client de streaming capteurs** (RGB, profondeur/ToF, IMU, timestamps, calibration), et déplacer le SLAM/RTAB-Map ROS 2 sur PC.

---

## 1) État actuel (analyse du dépôt)

### 1.1 Capture RGB / profondeur / pose / calibration côté Android

#### Entrée principale Android (Java)
- `app/android/src/com/introlab/rtabmap/RTABMapActivity.java`
  - Démarre les drivers caméra via `RTABMapLib.startCamera(...)`.
  - Pilote la logique d'état (camera/mapping/pause).

#### Capture ARCore Java + ToF
- `app/android/src/com/introlab/rtabmap/ARCoreSharedCamera.java`
  - Boucle frame ARCore dans `updateGL()`.
  - Récupère:
    - image RGB (`frame.acquireCameraImage()`, format YUV_420_888),
    - pose + timestamp ARCore,
    - point cloud ARCore,
    - intrinsics RGB (`camera.getImageIntrinsics()`),
    - extrinsics RGB/depth (via `CameraCharacteristics.LENS_POSE_*`),
    - intrinsics depth (`LENS_INTRINSIC_CALIBRATION`) si caméra depth dispo.
  - Envoie actuellement les données au natif via:
    - `RTABMapLib.postOdometryEvent(...)` (sans depth),
    - `RTABMapLib.postOdometryEventDepth(...)` (avec depth).

- `app/android/src/com/introlab/rtabmap/TOF_ImageReader.java`
  - Capture depth caméra (DEPTH16) avec `ImageReader`.
  - Stocke `depth16_raw` + `timestamp` pour synchronisation avec RGB.

#### Pont Java -> JNI
- `app/android/src/com/introlab/rtabmap/RTABMapLib.java`
  - Déclare les natives `postOdometryEvent` et `postOdometryEventDepth`.

- `app/android/jni/jni_interface.cpp`
  - Traduit les ByteBuffer Java vers pointeurs C++.
  - Appelle `RTABMapApp::postOdometryEvent(...)`.

#### Assemblage des données côté C++
- `app/android/jni/RTABMapApp.cpp`
  - `postOdometryEvent(...)`:
    - conversion YUV -> BGR,
    - décodage DEPTH16 + confiance,
    - registration depth->rgb,
    - création `SensorData` puis `camera_->update(...)`.
  - Le flux est ensuite injecté dans la chaîne RTAB-Map locale via threads/events.

### 1.2 IMU côté Android

- **Actuellement, il n'existe pas de publication IMU brute dédiée** (accel/gyro complets) dans le pipeline ARCore Java utilisé pour `postOdometryEvent`.
- `RTABMapActivity` écoute bien des capteurs Android, mais seulement:
  - accéléromètre + magnétomètre pour le compas/GPS,
  - capteurs d'environnement (température, pression, lumière, humidité).
- En pratique, la pose ARCore (VIO) intègre déjà l'IMU en interne, mais l'app ne sort pas `/imu/data` explicite vers ROS 2.

### 1.3 Où le SLAM/local mapping est lancé

- `app/android/jni/RTABMapApp.cpp`
  - `openDatabase(...)` crée:
    - `rtabmap_ = new rtabmap::Rtabmap();`
    - `rtabmapThread_ = new rtabmap::RtabmapThread(rtabmap_);`
  - `setPausedMapping(false)` démarre `rtabmapThread_->start()`.
- `app/android/src/com/introlab/rtabmap/RTABMapActivity.java`
  - `startMapping()` appelle `RTABMapLib.setPausedMapping(..., false)`.
  - `stopMapping()` appelle `RTABMapLib.setPausedMapping(..., true)`.

---

## 2) Plan de transformation (sans modifier le code pour l'instant)

## Phase A — Introduire un mode "Sensor Streamer" Android

1. **Ajouter un mode runtime** (préférence ou flag compile) `sensor_streamer_mode`.
2. En mode streamer:
   - démarrer caméra/ToF comme aujourd'hui,
   - **ne pas démarrer la partie mapping RTAB-Map locale** (pas de `RtabmapThread::start`),
   - conserver uniquement acquisition + sérialisation + envoi réseau.
3. Garder compatibilité descendante: mode classique inchangé.

## Phase B — Découpler l’export capteurs de `postOdometryEvent`

1. Créer un composant Android dédié (ex: `SensorStreamingClient`):
   - ingestion RGB/depth/pose/intrinsics/extrinsics depuis `ARCoreSharedCamera` et `TOF_ImageReader`;
   - ajout IMU brute (accel + gyro + éventuellement orientation) via `SensorManager`.
2. Encodage des messages capteurs (format recommandé):
   - enveloppe binaire + header timestamp + frame_id,
   - payload image compressé (JPEG/PNG ou NV12 brut selon bande passante).
3. Transport:
   - **option 1**: WebRTC DataChannel (faible latence, NAT-friendly),
   - **option 2**: WebSocket binaire (simple à intégrer/debug).
4. Ajouter mécanisme de synchronisation:
   - horloge monotonic Android + offset epoch,
   - sequence numbers RGB/depth/IMU.

## Phase C — Package ROS 2 Humble côté PC (récepteur)

Créer un nouveau package (nom suggéré: `rtabmap_android_stream_bridge`):

1. Nœud `stream_receiver_node` (Python ou C++):
   - écoute WebSocket/WebRTC,
   - décode les trames,
   - publie:
     - `/camera/color/image_raw` (`sensor_msgs/Image`),
     - `/camera/depth/image_raw`,
     - `/camera/color/camera_info`,
     - `/imu/data` (`sensor_msgs/Imu`),
     - `/tf` si pose/extrinsics disponibles (`tf2_msgs/TFMessage`).
2. Paramétrage:
   - `frame_id` (`camera_color_optical_frame`, `camera_depth_optical_frame`, `imu_link`),
   - QoS (sensor data profile),
   - mode compressé/non compressé.
3. Ajouter publisher de `CameraInfo` avec `K`, `D`, `R`, `P` issus des intrinsics Android.

## Phase D — Compatibilité RTAB-Map ROS 2 PC

1. Respect strict des conventions topics/frame_ids pour brancher:
   - `rtabmap_sync` / `rgbd_sync` / `rtabmap`.
2. Fournir launch files:
   - `android_stream_receiver.launch.py`,
   - `rtabmap_from_android_stream.launch.py`.
3. S'assurer que:
   - timestamps ROS reflètent les stamps capteurs Android,
   - depth encodé en `16UC1` (mm) ou conversion documentée.

---

## 3) Fichiers concernés (première passe)

### Android Java
- `app/android/src/com/introlab/rtabmap/RTABMapActivity.java`
- `app/android/src/com/introlab/rtabmap/ARCoreSharedCamera.java`
- `app/android/src/com/introlab/rtabmap/TOF_ImageReader.java`
- `app/android/src/com/introlab/rtabmap/RTABMapLib.java`

### Android JNI/C++
- `app/android/jni/jni_interface.cpp`
- `app/android/jni/RTABMapApp.cpp`
- potentiellement `app/android/jni/CameraMobile.cpp` (si extraction/abstraction du flux capteurs)

### Nouveau côté PC (à créer)
- `ros2_ws/src/rtabmap_android_stream_bridge/` (nouveau package)
  - `package.xml`
  - `CMakeLists.txt` ou `setup.py/pyproject.toml`
  - `src/stream_receiver_node.(cpp|py)`
  - `launch/*.launch.py`
  - `config/*.yaml`

---

## 4) Proposition d’implémentation incrémentale

1. **Milestone 1 (Android only)**
   - Mode streamer + logs détaillés + sérialisation locale (fichier) sans réseau.
2. **Milestone 2 (Transport)**
   - Ajout WebSocket binaire (plus rapide à valider), puis option WebRTC.
3. **Milestone 3 (ROS 2 bridge)**
   - Réception + publication des 4 topics demandés.
4. **Milestone 4 (RTAB-Map ROS 2 integration)**
   - Launch complet PC + validation sur séquences réelles.
5. **Milestone 5 (perf & robustesse)**
   - adaptation débit/résolution,
   - buffer circulaire,
   - détection perte paquets/reconnexion.

---

## 5) Risques techniques et décisions à figer

1. **Transport prioritaire**: WebSocket (simplicité) vs WebRTC (latence/réseau réel).
2. **Format profondeur**: conserver DEPTH16 brut vs normaliser float32 côté PC.
3. **Source IMU**:
   - Android `SensorManager` accel/gyro,
   - ou seulement pose VIO ARCore (insuffisant pour `/imu/data` strict).
4. **Sync temporelle**:
   - alignement timestamps Android -> ROS clock.
5. **Charge CPU mobile**:
   - éviter conversion coûteuse YUV->BGR côté Android si pas nécessaire.

---

## 6) Recommandation immédiate

- Commencer par **WebSocket binaire + ROS 2 C++ receiver** (diagnostic facile), puis itérer vers WebRTC si besoin.
- Garder les formats bruts au début (YUV/DEPTH16 + calibration + pose + IMU), décode/convert côté PC.
- Préserver les chemins existants RTAB-Map Android derrière un flag pour minimiser le risque de régression.
