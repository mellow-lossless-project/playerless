# Media3 and AndroidX ship their own consumer rules.

# The YouTube pages call back into JsPage by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
