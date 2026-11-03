# Keep MicSprite; avoid double obfuscation.
-keep class com.github.smartzeng.micsprite.** { *; }
-dontwarn com.github.smartzeng.micsprite.**

-keep interface com.github.smartzeng.micsprite.core.MicSpriteDecoder { *; }
-keep class * implements com.github.smartzeng.micsprite.core.MicSpriteDecoder {
    public <init>();
}

-keep class com.opensource.svgaplayer.** { *; }
-keepclassmembers class com.opensource.svgaplayer.SVGADrawable {
    boolean cleared;
    int currentFrame;
}
-dontwarn com.opensource.svgaplayer.**

-keep class org.libpag.** { *; }
-dontwarn org.libpag.**

-keep class com.github.penfeizhou.animation.** { *; }
-dontwarn com.github.penfeizhou.animation.**
