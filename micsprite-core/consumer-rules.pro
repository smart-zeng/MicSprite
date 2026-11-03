# 接入方 minify 时不要再混淆本库。ServiceLoader / Class.forName 依赖类名。
-keep class io.github.smartzeng.micsprite.core.** { *; }
-dontwarn io.github.smartzeng.micsprite.core.**

-keep interface io.github.smartzeng.micsprite.core.MicSpriteDecoder { *; }
-keep class * implements io.github.smartzeng.micsprite.core.MicSpriteDecoder {
    public <init>();
}

-keep class io.github.smartzeng.micsprite.decoder.svga.SvgaDecoder { public <init>(); }
-keep class io.github.smartzeng.micsprite.decoder.pag.PagDecoder { public <init>(); }
-keep class io.github.smartzeng.micsprite.decoder.frames.FrameSequenceDecoder { public <init>(); }
