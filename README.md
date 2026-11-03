# MicSprite

把 SVGA / PAG / GIF / WebP 按帧打成图集，用 OpenGL ES 画在麦位上。

## 依赖

不必须用 `micsprite-all`。各模块独立发布，按格式和 UI 壳按需组合即可。

**全量（SVGA + PAG + GIF/WebP）：**

```kotlin
dependencies {
    implementation("io.github.smart-zeng:micsprite-all:1.0.0")
    implementation("io.github.smart-zeng:micsprite-compose:1.0.0")
    // 或 View：
    // implementation("io.github.smart-zeng:micsprite-view:1.0.0")
}
```

**按格式拆分（示例：只要 SVGA + Compose）：**

```kotlin
dependencies {
    implementation("io.github.smart-zeng:micsprite-decoder-svga:1.0.0")
    implementation("io.github.smart-zeng:micsprite-compose:1.0.0")
    // 需要 PAG / GIF / WebP 时再加：
    // implementation("io.github.smart-zeng:micsprite-decoder-pag:1.0.0")
    // implementation("io.github.smart-zeng:micsprite-decoder-frames:1.0.0")
    // View 壳：
    // implementation("io.github.smart-zeng:micsprite-view:1.0.0")
}
```

| 模块 | 说明 |
|---|---|
| `micsprite-core` | 烘焙、缓存、OpenGL；被其它模块 `api` 带入，一般不用单独写 |
| `micsprite-decoder-svga` | SVGA |
| `micsprite-decoder-pag` | PAG |
| `micsprite-decoder-frames` | GIF / Animated WebP |
| `micsprite-all` | 上面三个 decoder 的聚合 |
| `micsprite-compose` | Compose 壳 |
| `micsprite-view` | View 壳 |

```kotlin
android {
    packaging {
        resources {
            merges += "META-INF/services/io.github.smartzeng.micsprite.core.MicSpriteDecoder"
        }
    }
}
```

仓库需要 [JitPack](https://jitpack.io)（SVGAPlayer 走 JitPack）。App 需要 `INTERNET` 权限。

Decoder 对重 SDK 使用 `implementation`，不会传到接入方的编译 classpath；运行时仍会打进 APK。只引某个 decoder 时，对应三方库才会进包。

也可以只引 `micsprite-core` + Compose/View 壳，自己实现 `MicSpriteDecoder` 并注册，不必依赖官方 decoder 模块。见下方「注入解码器」。

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

锚点相对于 `MicSpriteSurfaceView`。可用 `getLocationOnScreen` 做差。离开页面时调用 `release()`（Compose 的 `rememberMicSpriteController` 会在 `onDispose` 自动调用）；最后一个 Controller 释放后会自动清掉内存图集，磁盘 WebP 保留。

## 释放内存缓存

内存紧张时可主动清掉进程内图集 Bitmap，不影响磁盘缓存，下次再用会重新从磁盘加载或重新烘焙：

```kotlin
MicSpriteBaker.clearMemoryCache()
```

适合挂在 `ComponentCallbacks2.onTrimMemory` / `onLowMemory`，例如：

```kotlin
override fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
        MicSpriteBaker.clearMemoryCache()
    }
}
```

## 注入解码器

官方 `micsprite-decoder-*` 不是必须的。实现 `MicSpriteDecoder`，在首次烘焙前调用 `MicSpriteDecoderRegistry.register` 即可；也可把实现类名写进 `META-INF/services/io.github.smartzeng.micsprite.core.MicSpriteDecoder`，由 ServiceLoader 自动发现。

`supports` 决定这个 decoder 认哪些 url；`bake` 返回网格图集 `MicSpriteRaster`（像素需预乘 alpha）。可用 `MicSpriteBaker.materialize` 下载到本地，再用 `MicSpriteBaker.buildStrip` / `drawFitCenter` 抽帧。

```kotlin
class MyDecoder : MicSpriteDecoder {
    override fun supports(url: String): Boolean =
        url.endsWith(".mysprite", ignoreCase = true)

    override suspend fun bake(
        context: Context,
        url: String,
        maxTextureSize: Int,
        frameSizePx: Int,
    ): MicSpriteRaster? {
        val file = MicSpriteBaker.materialize(context, url) ?: return null
        // 自行解码 file，抽成图集；也可走 MicSpriteBaker.buildStrip { ... }
        return MicSpriteBaker.buildStrip(
            totalFrames = 30,
            originalFps = 15,
            maxTextureSize = maxTextureSize,
            yieldEvery = MicSpriteBaker.backgroundYieldEvery,
        ) { srcIndex, canvas ->
            // 把第 srcIndex 帧画到 canvas（边长 frameSizePx）
        }
    }
}

// Application.onCreate 或进入直播间前注册一次
MicSpriteDecoderRegistry.register(MyDecoder())
```

注册后只依赖：

```kotlin
implementation("io.github.smart-zeng:micsprite-core:1.0.0")
implementation("io.github.smart-zeng:micsprite-compose:1.0.0") // 或 micsprite-view
```

官方 decoder 与自定义 decoder 可并存；`resolve` 按注册顺序取第一个 `supports(url) == true` 的实现。

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