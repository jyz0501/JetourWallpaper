# 捷途动态壁纸 (JetourWallpaper)

车机动态壁纸 / 悬浮窗系统应用。

## 基本信息

- 包名：`com.qianxian.JetourWallpaper`
- 应用名：`捷途动态壁纸`
- 最低 SDK：26（Android 8.0）
- 编译 SDK：34

## 构建

```bash
./gradlew assembleDebug      # 调试版
./gradlew assembleRelease    # 正式版（默认未签名）
```

产物位于 `app/build/outputs/apk/{debug,release}/`，文件名格式：

`捷途动态壁纸_v<版本>_build<code>_<类型>.apk`

例如：`捷途动态壁纸_v2.9_build209_release-unsigned.apk`、`捷途动态壁纸_v2.9_build209_debug.apk`。

## 签名

`release` 构建产物默认**未签名**，需自行签名后方可安装。

### 平台签名（车机系统应用）

使用与车机固件一致的 platform 私钥/证书（`sign/platform.pk8` + `sign/platform.x509.pem`）：

```bash
# 1) 由 pk8 / pem 生成 PKCS12 密钥库
openssl pkcs8 -inform DER -nocrypt -in sign/platform.pk8 -out /tmp/platform_key.pem
openssl pkcs12 -export -in sign/platform.x509.pem -inkey /tmp/platform_key.pem \
  -name platform -out /tmp/platform.p12 -passout pass:android

# 2) apksigner 签名
apksigner sign --ks /tmp/platform.p12 --ks-pass pass:android --ks-key-alias platform \
  --out app-release-signed.apk \
  app/build/outputs/apk/release/捷途动态壁纸_v2.9_build209_release-unsigned.apk
```

> 也可使用 `signapk.jar`（`java -jar signapk.jar platform.x509.pem platform.pk8 in.apk out.apk`）。
> 只有车机固件采用同一把 platform 密钥时，才能以**系统应用**身份安装。

### 自签名（普通设备测试）

生成自有密钥库后，以 **v1 + v2** 方案签名即可作为常规应用安装：

```bash
keytool -genkeypair -v -keystore sign/selfsign.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias jetour -storepass android -keypass android \
  -dname "CN=Jetour, O=Jetour, C=CN"

apksigner sign --ks sign/selfsign.jks --ks-pass pass:android --ks-key-alias jetour \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
  --out app-selfsigned.apk <未签名APK>
```

## 签名密钥说明

`sign/` 目录保存签名密钥（platform 平台密钥 / selfsign 自签名密钥），**已加入 `.gitignore`，不纳入版本库**，仅保留在本地。

## 目录结构

```
app/src/main/java/com/qianxian/JetourWallpaper/   源码
app/src/main/res/raw/su7.mp4                       内置示例视频
sign/                                             本地签名密钥（不入库）
```
