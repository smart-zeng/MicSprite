afterEvaluate {
    extensions.configure<org.gradle.api.publish.PublishingExtension>("publishing") {
        publications {
            register<MavenPublication>("release") {
                groupId = "io.github.smart-zeng"
                artifactId = project.name
                version = "1.0.0"
                from(components.getByName("release"))
                pom {
                    name.set(project.name)
                    description.set(
                        "MicSprite: bake SVGA / PAG / GIF / WebP into a sprite atlas and render on mic seats.",
                    )
                    url.set("https://github.com/smart-zeng/MicSprite.git")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("smart-zeng")
                            name.set("smart-zeng")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/smart-zeng/MicSprite.git")
                        developerConnection.set("scm:git:ssh://github.com/smart-zeng/MicSprite.git")
                        url.set("https://github.com/smart-zeng/MicSprite")
                    }
                }
            }
        }
    }
}
