# sx2libra Android

基于 2libra 的 Android 版本，源于个人在使用 2libra 过程中的实际需求，重点优化首页导航、页面组织、图片浏览和评论发图体验。

## 下载

可前往 [GitHub Releases](https://github.com/KeepDreamHCQ/sx2libra-android/releases) 下载 APK。

## 本地构建

### 环境要求

- Android Studio
- JDK 17
- Android SDK 34

### 构建 Release APK

在项目根目录执行：

```bash
./gradlew assembleRelease
```

构建产物位于：

```text
app/build/outputs/apk/release/app-release.apk
```
