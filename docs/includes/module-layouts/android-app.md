```shell
my-android-app/
├─ assets/ # (1)!
├─ jniLibs/ # (2)!
│  ╰─ arm64-v8a/
│     ╰─ libfoo.so
├─ res/ # (3)!
│  ├─ drawable/
│  │  ╰─ graphic.png
│  ├─ layout/
│  │  ├─ main.xml
│  │  ╰─ info.xml
│  ╰─ ...
├─ resources/
├─ src/
│  ├─ AndroidManifest.xml # (4)!
│  ╰─ MainActivity.kt # (5)!
├─ test/
│  ╰─ MainTest.kt
├─ module.yaml
╰─ proguard-rules.pro # (6)!
```

1. `assets` and `res` are standard Android resource directories. See the [official Android docs](https://developer.android.com/guide/topics/resources/providing-resources).
2. Pre-compiled native libraries (`.so` files) organized by ABI (e.g. `arm64-v8a`, `x86_64`). See the [official Android docs](https://developer.android.com/studio/projects/gradle-external-native-builds#jniLibs).
3. `assets` and `res` are standard Android resource directories. See the [official Android docs](https://developer.android.com/guide/topics/resources/providing-resources).
4. The manifest file of your application.
5. An activity (screen) of your application.
6. Optional configuration for R8 code shrinking and obfuscation. See [code shrinking](/user-guide/product-types/android-app.md#code-shrinking).