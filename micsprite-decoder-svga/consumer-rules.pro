-keep class com.github.smartzeng.micsprite.decoder.svga.** { *; }
-keep class com.github.smartzeng.micsprite.decoder.svga.SvgaDecoder { public <init>(); }

-keep class com.opensource.svgaplayer.** { *; }
-keepclassmembers class com.opensource.svgaplayer.SVGADrawable {
    boolean cleared;
    int currentFrame;
}
-dontwarn com.opensource.svgaplayer.**
