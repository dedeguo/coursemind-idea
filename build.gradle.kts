import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

/**
 * 剖面参数读取顺序：命令行 -P 参数 > gradle.properties.local（不入库）> gradle.properties。
 *
 * 剖面由 courseMindProfile 显式选择，默认 release；不能仅凭 localIdePath 是否存在判断，
 * 因为用户级 GRADLE_USER_HOME/gradle.properties 可能已配置 localIdePath。
 *
 * - release（默认）：platformType=IU、platformVersion=2025.1.3、javaVersion=21
 * - local：读取 localIdePath 指向的本机 IDE，javaVersion 默认 25
 */
val localProperties = Properties().apply {
    val file = rootProject.file("gradle.properties.local")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

// 优先级：命令行 -P > gradle.properties.local > gradle.properties / 用户级 gradle.properties
val cliProperties = gradle.startParameter.projectProperties

fun setting(name: String): String? =
    cliProperties[name]
        ?: localProperties.getProperty(name)
        ?: providers.gradleProperty(name).orNull

val courseMindProfile = (setting("courseMindProfile") ?: "release").lowercase()
require(courseMindProfile == "release" || courseMindProfile == "local") {
    "courseMindProfile 只支持 release 或 local，当前值为 '$courseMindProfile'"
}
val isLocalProfile = courseMindProfile == "local"

val platformType = setting("platformType") ?: "IU"
val platformVersion = setting("platformVersion") ?: "2025.1.3"
val localIdePath = setting("localIdePath")?.takeIf { it.isNotBlank() }
// toolchain 由剖面决定默认值：release=21（IU 2025.1.3 class 版本 65），local=25（读取 2026.2 平台 class 版本 69）。
// 显式 javaVersion（-P 或 gradle.properties.local）优先，仓库内不写死，避免 local 剖面被 17 覆盖。
val javaVersion = (setting("javaVersion") ?: if (isLocalProfile) "25" else "21").toInt()
val pluginSince = setting("pluginSinceBuild") ?: "251"

// 平台获取方式：installer=true 走 download.jetbrains.com 安装包（默认，实测可下载）；
// false 走 IntelliJ Maven 仓库归档（cache-redirector），本机代理下该路径会挂起，见 M0 报告 D-15。
// 如需切换：-PplatformUseInstaller=false。
val platformUseInstaller = (setting("platformUseInstaller") ?: "true").toBoolean()

require(!isLocalProfile || localIdePath != null) {
    "local 剖面需要 localIdePath（指向本机 IntelliJ IDEA 安装目录）"
}

// local 剖面加后缀，避免同版本 ZIP 互相覆盖，也便于在插件列表中区分调试产物。
val basePluginVersion = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")
version = if (isLocalProfile) "$basePluginVersion-local" else basePluginVersion

// 发布基线是 Java 17 字节码；本地剖面用更高 JDK 读取新版平台 class 文件，但字节码保持 17。
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    implementation("org.commonmark:commonmark:0.31.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.31.0")
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        if (isLocalProfile) {
            logger.lifecycle("CourseMind 构建剖面：本地调试 local($localIdePath)，JDK $javaVersion")
            local(localIdePath!!)
        } else {
            logger.lifecycle("CourseMind 构建剖面：发布基线 $platformType $platformVersion，JDK $javaVersion，useInstaller=$platformUseInstaller")
            create(platformType, platformVersion) {
                useInstaller.set(platformUseInstaller)
            }
        }

        testFramework(TestFrameworkType.Platform)

        // Ultimate 平台测试需要该模块；显式切换到 Community 时不引入。
        if (isLocalProfile || platformType == "IU") {
            testBundledPlugins("com.intellij.modules.ultimate")
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = pluginSince
        }
    }
}
