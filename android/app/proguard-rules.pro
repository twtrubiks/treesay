# anthropic-java 依賴的 OpenTelemetry API 用 @AutoValue 標註 class。那是編譯期才用的註解，
# 執行時不需要，App 裡也不會有這個 class
-dontwarn com.google.auto.value.AutoValue
