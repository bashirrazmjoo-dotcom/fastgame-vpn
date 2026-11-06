# OkHttp and Compose ship their own consumer rules. The app uses no reflection:
# JSON is parsed manually (org.json), so nothing in com.fastgame.vpn needs to be kept.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
