package io.github.smartzeng.micsprite.demo

/**
 * 公开可访问的测试素材。网络异常时 Demo 仍会显示麦位，只是头饰烘焙失败。
 */
object DemoAnchors {
    const val SVGA_URL = "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/angel.svga"
    /** 腾讯 PAG 国内站官方示例，避免 GitHub LFS 指针和 pag.io 跨境延迟。 */
    const val PAG_URL = "https://pag.qq.com/file/like.pag"
    /** Pillow 开源测试动图，走 jsdmirror；不要用 GitHub/W3C 直链。 */
    const val GIF_URL =
        "https://cdn.jsdmirror.com/gh/python-pillow/Pillow@10.4.0/Tests/images/dispose_bgnd.gif"

    /**
     * [SVGA-Samples](https://github.com/svga/SVGA-Samples) 开源示例。
     * 走国内 jsDelivr 镜像 [jsdmirror](https://cdn.jsdmirror.com)，避免直连 cdn.jsdelivr.net 首包过慢。
     * 版权归 YY Inc.，仅学习演示，禁止商用。
     */
    val TestHeadwearUrls = listOf(
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/angel.svga",
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/heartbeat.svga",
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/kingset.svga",
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/rose.svga",
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/posche.svga",
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/halloween.svga",
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/TwitterHeart.svga",
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/Walkthrough.svga",
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/PinJump.svga",
        "https://cdn.jsdmirror.com/gh/svga/SVGA-Samples@master/HamburgerArrow.svga",
    )

    const val SEAT_COLUMNS = 4

    val AVATARS = listOf(
        "https://i.pravatar.cc/200?img=11",
        "https://i.pravatar.cc/200?img=12",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
        "https://i.pravatar.cc/200?img=13",
    )

    fun randomHeadwearUrl(): String = TestHeadwearUrls.random()

    /** 把素材随机铺满所有麦位。素材不够时循环使用。每个 micId 都会挂上。 */
    fun randomFilledHeadwears(seatCount: Int): MutableMap<Int, String> {
        val filled = LinkedHashMap<Int, String>()
        if (seatCount <= 0 || TestHeadwearUrls.isEmpty()) return filled
        val urls = TestHeadwearUrls.shuffled()
        for (micId in 0 until seatCount) {
            filled[micId] = urls[micId % urls.size]
        }
        return filled
    }
}

enum class DemoFormat(val label: String, val url: String) {
    SVGA("SVGA", DemoAnchors.SVGA_URL),
    PAG("PAG", DemoAnchors.PAG_URL),
    GIF("GIF", DemoAnchors.GIF_URL),
}
