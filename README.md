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

详见 [AGENTS.md](AGENTS.md)（项目规则与调试经验）、[docs/滤镜实现原理.md](docs/滤镜实现原理.md)（写给 GL 新手的滤镜算法讲解）。

## 架构

```
CameraX Preview ──> SurfaceTexture(OES) ──> OpenGL ES 管线 ──> 屏幕 / 拍照
                                            ├─ pass1: 人脸液态变形 (warp)
                                            ├─ pass2: GuidedFilter 引导滤波磨皮 (半分辨率)
                                            └─ pass3: 磨皮混合/祛黑眼圈/修容/肤色/虚化/滤镜
ML Kit 人脸检测 ──> FaceData 关键点 ──> FaceWarpBuilder 变形参数 + 方向补偿
ML Kit 自拍分割 ──> 人像 mask ──> GL 纹理 ──> 背景虚化
```

| 文件 | 职责 |
|---|---|
| `CameraRenderer` | GL 管线编排：变形 pass、调用磨皮链、最终合成、拍照 |
| `Shaders` | 全部 GLSL 源码（滤镜/磨皮/变形的算法本体） |
| `GuidedFilter` | 引导滤波磨皮链（自持半分辨率 FBO 池） |
| `QuadDrawer` | 全屏绘制样板（mesh + 纹理绑定 + 视口） |
| `Fbo` | 离屏渲染目标 |
| `GlProgram` | GLSL program 最小封装 |
| `FaceTracker` | ML Kit 人脸关键点提取与几何安全校验 |
| `FaceData` | 人脸关键点数组的类型化封装（跨线程载体） |
| `FaceWarpBuilder` | 关键点 + 滑杆参数 → 变形槽位参数 |
| `SegmentationAnalyzer` | ML Kit 自拍分割 → 8bit 人像 mask |
| `OrientationSensor` / `OrientationBlender` | 持机姿势检测 / 方向补偿决策 |
| `CameraController` | CameraX 绑定、切换、变焦 |
| `MainActivity` | UI 编排（页签/滑杆/拍照/倒计时/补光） |
