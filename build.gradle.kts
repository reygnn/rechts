// Top-level build file where you can add configuration options common to all sub-projects/modules.
// AGP 9 ships Kotlin built-in — do NOT add the org.jetbrains.kotlin.android plugin here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose)      apply false
}
