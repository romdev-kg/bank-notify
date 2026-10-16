buildscript {
    dependencies {
        // AGP built-in Kotlin supports a newer compiler via its runtime classpath.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
