# Android artifact অবস্থা

- `nativekit-current-source-debug-2026-08-18.apk` — 2026-08-18-এ সর্বশেষ strict three-tier/maximum-isolation source থেকে সফল `:app:assembleDebug` build; debug key দিয়ে installable।
- `SHA256SUMS.txt` — binary-টির SHA-256 verification।

পুরোনো revision-এর unsigned APK/AAB workspace ছোট ও দ্ব্যর্থহীন রাখতে সরানো হয়েছে। নতুন signed release APK/AAB GitHub Actions বা controlled signing environment-এ বর্তমান source থেকে build করুন।
