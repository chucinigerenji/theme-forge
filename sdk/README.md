# 把 android.jar 放这里

本目录用于存放编译所需的 **Android 平台桩** `android.jar`（文件名必须是 `android.jar`）。

获取方式（任选其一）：

1. **从 Android SDK 复制**
   ```bash
   cp $ANDROID_HOME/platforms/android-35/android.jar sdk/android.jar
   ```
2. **只下平台包**
   到 <https://dl.google.com/android/repository/repository2-3.xml> 找 `platform-35` 的 zip，
   解压出里面的 `android.jar`。
3. **不放进本项目**
   `export ANDROID_JAR=/path/to/android.jar` 即可，脚本会优先用它。

> android.jar 有 20~30 MB，且属于 Google 的 SDK，所以**不入库**（已在 .gitignore 里忽略）。
