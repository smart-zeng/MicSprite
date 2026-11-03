# MicSprite

把 SVGA / PAG / GIF / WebP 按帧打成图集，用 OpenGL ES 画在麦位上。

## 依赖

```kotlin
dependencies {
    implementation("io.github.smart-zeng:micsprite-all:1.0.0")
    implementation("io.github.smart-zeng:micsprite-compose:1.0.0")
    // 或 View：
    // implementation("io.github.smartzeng:micsprite-view:1.0.0")
}

android {
    packaging {
        resources {
            merges += "META-INF/services/io.github.smartzeng.micsprite.core.MicSpriteDecoder"
        }
    }
}
```

仓库需要 [JitPack](https://jitpack.io)（SVGAPlayer 走 JitPack）。App 需要 `INTERNET` 权限。

Decoder 对重 SDK 使用 `implementation`，不会传到接入方的编译 classpath；运行时仍会打进 APK。

## 三方库

只列出各模块自己引入的库。`micsprite-view`、`micsprite-all` 没有直接三方依赖。

### micsprite-core

| 坐标 | 来源 | 用途 |
|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 烘焙、下载协程 |

其余为 Android SDK（含 OpenGL ES）。下载默认 `HttpURLConnection`，无 OkHttp。

### micsprite-decoder-svga

| 坐标 | 来源 | 用途 |
|---|---|---|
| `com.github.yyued:SVGAPlayer-Android:2.6.1` | [SVGAPlayer-Android](https://github.com/svga/SVGAPlayer-Android) | 解析 SVGA，用 `SVGADrawable` 后台抽帧 |

### micsprite-decoder-pag

| 坐标 | 来源 | 用途 |
|---|---|---|
| `com.tencent.tav:libpag:4.5.98` | [libpag](https://github.com/Tencent/libpag) | 解析 PAG 并逐帧拷到 Bitmap |

### micsprite-decoder-frames

| 坐标 | 来源 | 用途 |
|---|---|---|
| `com.github.penfeizhou.android.animation:gif:3.0.5` | [APNG4Android](https://github.com/penfeizhou/APNG4Android) | GIF 逐帧解码 |
| `com.github.penfeizhou.android.animation:awebp:3.0.5` | 同上 | Animated WebP 逐帧解码 |

GIF 解析失败时回退系统 `Movie`。只在引入本模块或 `micsprite-all` 时带入。

### micsprite-compose

| 坐标 | 来源 | 用途 |
|---|---|---|
| `androidx.compose.foundation` / `runtime` | [AndroidX Compose](https://developer.android.com/jetpack/androidx/releases/compose) | `AndroidEmbeddedExternalSurface` 接到控制器 |

### demo（不随 SDK 发布）

| 坐标 | 来源 | 用途 |
|---|---|---|
| `io.coil-kt:coil` / `coil-compose` | [Coil](https://github.com/coil-kt/coil) | 示例麦位头像 |
| `com.squareup.okhttp3:okhttp` | [OkHttp](https://github.com/square/okhttp) | 示例工程依赖，库本体不用 |
| `com.google.android.material:material` | [Material Components](https://github.com/material-components/material-components-android) | View 示例 |

## 混淆

各模块 AAR 已带 `consumer-rules.pro`，接入方开启 `minifyEnabled` 时一般会自动合并。为避免对本库二次混淆（ServiceLoader、`Class.forName`、SVGA 反射字段依赖类名），请把下面规则放进 App 的 `proguard-rules.pro`：

```proguard
# MicSprite：不要再混淆本库
-keep class io.github.smartzeng.micsprite.** { *; }
-dontwarn io.github.smartzeng.micsprite.**

-keep interface io.github.smartzeng.micsprite.core.MicSpriteDecoder { *; }
-keep class * implements io.github.smartzeng.micsprite.core.MicSpriteDecoder {
    public <init>();
}

# SVGA 抽帧反射
-keep class com.opensource.svgaplayer.** { *; }
-keepclassmembers class com.opensource.svgaplayer.SVGADrawable {
    boolean cleared;
    int currentFrame;
}
-dontwarn com.opensource.svgaplayer.**

# PAG
-keep class org.libpag.** { *; }
-dontwarn org.libpag.**

# GIF / WebP（penfeizhou APNG4Android）
-keep class com.github.penfeizhou.animation.** { *; }
-dontwarn com.github.penfeizhou.animation.**
```

## 用法

麦位布局完成后上报头像矩形（图层左上角为原点，单位 px）。之后加减头饰只传麦位 id 和 url。头饰按 `headwearScale`（默认 `64/48`）以中心放大。

```kotlin
controller.setSeats(
    listOf(
        MicSpriteSeat(micId = 0, left = l, top = t, right = r, bottom = b),
    ),
)
controller.setHeadwear(micId = 0, url = "https://example.com/angel.svga")
controller.removeHeadwear(micId = 0)
```

单个麦位布局完成时也可以 `setSeat(...)`。

### Compose

```kotlin
val controller = rememberMicSpriteController()

Box(Modifier.fillMaxSize().onGloballyPositioned { layer = it }) {
    MicSeat(
        Modifier.onGloballyPositioned { seat ->
            val topLeft = layer.windowToLocal(seat.localToWindow(Offset.Zero))
            val bottomRight = layer.windowToLocal(
                seat.localToWindow(Offset(seat.size.width.toFloat(), seat.size.height.toFloat())),
            )
            controller.setSeat(
                MicSpriteSeat(0, topLeft.x, topLeft.y, bottomRight.x, bottomRight.y),
            )
        },
    )
    MicSpriteLayer(controller, Modifier.fillMaxSize())
}

controller.setHeadwear(0, url)
controller.removeHeadwear(0)
```

### View

```kotlin
val overlay = MicSpriteSurfaceView(context)
overlay.setSeats(seats)          // 布局完成后上报
overlay.setHeadwear(micId, url)  // 戴上
overlay.removeHeadwear(micId)    // 摘掉
```

锚点相对于 `MicSpriteSurfaceView`。可用 `getLocationOnScreen` 做差。离开页面时会自动释放内存；磁盘缓存保留。

## 替换下载

默认用 `HttpURLConnection` 下载到 `cacheDir/micsprite`。

Kotlin 覆盖挂起的 `download`：

```kotlin
MicSpriteConfig.fileProvider = object : MicSpriteFileProvider {
    override suspend fun download(
        context: Context,
        url: String,
        destination: File,
    ): Boolean {
        // 把 url 写到 destination，成功返回 true
        return true
    }
}
```

```java
MicSpriteConfig.setFileProvider(new MicSpriteFileProvider() {
    @Override
    public boolean downloadSync(Context context, String url, File destination) {
        // 把 url 写到 destination，成功返回 true
        return true;
    }
});
```