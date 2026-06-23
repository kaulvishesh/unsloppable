# ProGuard rules for SlopRadar
# Add project specific rules here.

# Keep the AIDetector and MockAIDetector interfaces/classes intact to facilitate easy reflection or testing
-keep public interface com.example.slopradar.AIDetector { *; }
-keep class com.example.slopradar.MockAIDetector { *; }
-keep class com.example.slopradar.ScreenCaptureService { *; }
-keep class com.example.slopradar.FloatingWindowManager { *; }

# TensorFlow Lite specific rules
-keep class org.tensorflow.lite.** { *; }
