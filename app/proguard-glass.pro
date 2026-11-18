# Glass flavour: MediaPipe's face landmarker (eye tracking for the SpatialGlass film).
# Its Java side is reached from native code and protobuf by name, so it must survive R8
# untouched; its AutoValue/JavaPoet build-time leftovers reference javax.lang.model, which
# does not exist on Android and is never called at run time.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.mediapipe.proto.**
