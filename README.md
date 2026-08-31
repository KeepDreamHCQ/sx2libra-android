# sx2libra Android

基于 2libra 的 Android 版本，源于个人在使用 2libra 过程中的实际需求，重点优化首页导航、页面组织、图片浏览和评论发图体验。

## 当前版本

当前首个版本为 `1.0.0`，主要包含以下功能调整：

1. **首页导航**
   - 默认提供【帖子】、【消息】和【我的】三个主要入口。

2. **帖子多 Tab**
   - 【帖子】支持多 Tab 页面。
   - 支持添加、编辑和排序 Tab。
   - 支持自定义 2libra 中的任意页面。

3. **图片资源拦截与原生查看**
   - 拦截页面中的图片资源，并使用原生页面查看大图。
   - 支持图片缩放和保存。

4. **页面渲染优化**
   - 页面加载改为新的页面渲染方式，优化页面打开体验。

5. **评论区选图上传**
   - 评论时可直接从手机选择图片，并上传至图床。

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
