plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 の組み込み Kotlin で使う KGP のバージョンを揃えるために classpath へ載せる（apply はしない）
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
