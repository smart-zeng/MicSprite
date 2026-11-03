# Keep MicSprite for ServiceLoader / Class.forName.
-keep class com.github.smartzeng.micsprite.core.** { *; }
-dontwarn com.github.smartzeng.micsprite.core.**

-keep interface com.github.smartzeng.micsprite.core.MicSpriteDecoder { *; }
-keep class * implements com.github.smartzeng.micsprite.core.MicSpriteDecoder {
    public <init>();
}

-keep class com.github.smartzeng.micsprite.decoder.svga.SvgaDecoder { public <init>(); }
-keep class com.github.smartzeng.micsprite.decoder.pag.PagDecoder { public <init>(); }
-keep class com.github.smartzeng.micsprite.decoder.frames.FrameSequenceDecoder { public <init>(); }
