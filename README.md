PaiPai-App 安卓打包工程

执行步骤:

### 1. PaiPai-App 安卓打包
- 首先安装Android SDK和NDK，并修改项目 android 配置:
 
cocos creator 配置 Preferences -> Program Manager -> 

    Java Home: 版本 jdk-17
    Android NDK: 版本 r23c (23.2.8568313) 
    Android SDK: 版本 35

- 项目打包(build)

  - Platform: Android
  - Build Path: build/android  (默认)
  - Start Scene: db://assets/Scene/start.scene
  - Target API Level: android-35
  - Screen Orientation: Portrait 
   
  其他默认, 点击构建, 生成工程目录为 build/android/proj 
    
### 2. 更新工程配置

- 将 PaiPai-Android 目录下的文件拷贝到 PaiPai-App 工程目录下(主要是native和build下文件) 

- 修改工程配置文件 build\android\proj\gradle.properties 内容如下:

```text
# Android SDK version that will be used as the compile project
PROP_COMPILE_SDK_VERSION=35

# Android SDK version that will be used as the earliest version of android this application can run on
PROP_MIN_SDK_VERSION=21

# Android SDK version that will be used as the latest version of android this application has been tested on
PROP_TARGET_SDK_VERSION=35

# Android Build Tools version that will be used as the compile project
PROP_BUILD_TOOLS_VERSION=30.0.3

# Android Application Name
PROP_APP_NAME=PaiPai

```

### 3. 编译工程, 生成apk

    Build -> Rebuild Project
    Build -> Build Bundle(s) / APK(s) -> Build APK(s)
  