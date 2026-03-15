After making any code changes to the Android app (`android/` directory), always build and install on the connected device:

```sh
# Build
cd android && ./gradlew assembleDebug

# Install (use adb directly - the device is too slow for Gradle's installDebug timeout)
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Do **not** use `./gradlew :app:installDebug` - it times out on the connected device (Moto E2, Android 6.0).
