# BeautyCamera 美颜相机

Android 实时美颜相机 Demo。Java 7 语法编写，CameraX + OpenGL ES + ML Kit。

## 功能

- 实时预览：磨皮（边缘保持 + 肤色掩模）、美白、红润、锐化、饱和度调节
- 美型：大眼 / 瘦脸 / 小下巴（ML Kit 人脸轮廓 + GPU 液态变形，含姿态安全校验）
- 6 款滤镜（原图 / 白皙 / 暖阳 / 冷调 / 胶片 / 初恋）
- 左右分屏对比模式（原图 | 美颜）
- 拍照保存到相册 `Pictures/BeautyCamera`（所见即所得，含美颜效果）
- 前后摄像头切换、人脸朝向自适应（重力 + 人脸 roll 角补偿）、手动翻转

## 构建

```
gradle assembleDebug   # 需要 JDK 17 与 Android SDK 34
```

详见 [AGENTS.md](AGENTS.md)（项目规则与调试经验）。

## 架构

```
CameraX Preview ──> SurfaceTexture(OES) ──> OpenGL ES 管线 ──> 屏幕 / 拍照
                                            ├─ pass1: 人脸液态变形 (warp)
                                            ├─ pass2: 半分辨率高斯模糊 x2
                                            └─ pass3: 边缘保持磨皮 + 调色 + 滤镜
ML Kit ImageAnalysis ──> 人脸轮廓/欧拉角 ──> warp 参数 & 方向补偿
```

| 文件 | 职责 |
|---|---|
| `CameraRenderer` | 全部 shader 与多 pass 渲染管线、分屏、拍照 |
| `FaceTracker` | ML Kit 人脸轮廓提取、几何安全校验 |
| `CameraController` | CameraX 绑定与切换 |
| `OrientationSensor` | 重力姿态检测（方向补偿） |
| `MainActivity` | 滑杆/滤镜/拍照/对比 UI |
