# Used by build.sh only to drop the parts of the Kotlin standard library that Lumen never
# calls. No obfuscation and no optimisation: Lumen's own code is kept exactly as written.
-dontobfuscate
-dontoptimize
-dontpreverify
-dontnote **
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keep class io.github.realkhfan.lumen.** { *; }
