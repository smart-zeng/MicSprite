-keep class io.github.smartzeng.micsprite.decoder.svga.** { *; }
-keep class io.github.smartzeng.micsprite.decoder.svga.SvgaDecoder { public <init>(); }

# 抽帧通过反射写 SVGADrawable.cleared / currentFrame
-keep class com.opensource.svgaplayer.** { *; }
-keepclassmembers class com.opensource.svgaplayer.SVGADrawable {
    boolean cleared;
    int currentFrame;
}
-dontwarn com.opensource.svgaplayer.**
