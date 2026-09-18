// Build racine — les versions sont volontairement récentes mais stables ;
// Android Studio Canary proposera les mises à jour AGP/Kotlin si besoin.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
}
