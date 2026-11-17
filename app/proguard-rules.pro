# Keep rules for the release build (R8).
#
# Media3 and TagLib both ship their own rules inside their packages, and R8 applies
# those automatically. The TagLib rule is repeated here on purpose: its native code
# (libtaglib.so) looks these classes, fields and methods up by name through JNI, so a
# renamed or removed one fails only at run time, when a tag is read or saved.
-keep class com.kyant.taglib.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The download engine (youtubedl-android) finds its own classes by name and reads JSON
# with Jackson, which looks fields up by reflection.
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn java.beans.**

# Keep file names and line numbers in crash reports readable once the mapping file
# is uploaded to Play.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
