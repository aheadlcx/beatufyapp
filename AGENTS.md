# 项目规则（BeautyCamera 美颜相机）

本项目为 Android 美颜相机 Demo：CameraX 取流 + OpenGL ES 多 pass 渲染（磨皮/美白/红润/锐化/滤镜/大眼/瘦脸/下巴）+ ML Kit 人脸轮廓检测。**源码使用 Java 7 语法编写**（无 lambda / Stream / 方法引用），compileOptions 字节码级别为 1.8（AndroidX 库强制要求）。

## 必须遵守的工作流规则

**每次有大的修改（功能新增、明显的行为/渲染修复），必须完成以下三步后才算完成：**

1. **编译 APK**：
   ```
   JAVA_HOME=D:\soft\develop\as\jbr gradle assembleDebug
   ```
   （Gradle 使用 `~/.gradle/wrapper/dists/gradle-8.7-bin` 中的本地 Gradle；Android SDK 位于 `D:\soft\develop\Sdk`）

2. **把新 APK 拷贝到网络共享目录**，文件名带类型与编译时间（月日-时分秒）：
   ```
   \\192.168.0.104\work\demo\apk
   命名格式：BeautyCamera_<说明>_<MMDD-HHMMSS>.apk
   ```

3. **提交并推送到 GitHub**：https://github.com/aheadlcx/beatufyapp
   - 同时把最新 APK 复制到仓库 `apk/` 目录（覆盖 `BeautyCamera-latest.apk`，并保留一份带时间戳的 `apk/BeautyCamera_<说明>_<MMDD-HHMMSS>.apk`）
   - commit message 用中文简述本次修改
   - `git push` 到 `main` 分支

小的改动（注释、调试日志）可酌情跳过 APK 交付，但仍应提交代码。

## 调试注意事项（历史经验）

- 真机华为设备会过滤三方 app 的 logcat D 级日志，调试优先使用**屏幕上的调试 TextView**（当前为 `tvDebug`，问题确认后可移除）。
- SurfaceTexture 的 `getTransformMatrix()` 已包含把画面转正所需的全部旋转，**不要**再叠加 sensorOrientation 公式；持机方向自适应通过 `renderer.rotationOverride`（重力 + 人脸 eulerX 四档补偿）实现。
- 所有 fragment shader 必须显式声明 `precision mediump float;`，否则在部分设备上崩溃。
- 最终 pass 的 centerCrop 系数必须 ≤1（缩小采样范围），>1 会越界采样导致边缘拉丝。
- 真机调试：`adb` 位于 `D:\soft\develop\Sdk\platform-tools`；华为设备 uiautomator dump 会因 GL 连续渲染报 idle 失败，取控件坐标需多重试几次。
