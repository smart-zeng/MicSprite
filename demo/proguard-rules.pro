# MicSprite：不要再混淆本库，防止二次混淆。
-keep class io.github.smartzeng.micsprite.** { *; }
-dontwarn io.github.smartzeng.micsprite.**

-keep interface io.github.smartzeng.micsprite.core.MicSpriteDecoder { *; }
-keep class * implements io.github.smartzeng.micsprite.core.MicSpriteDecoder {
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

