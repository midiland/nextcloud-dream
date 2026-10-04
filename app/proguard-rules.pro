# Les bibliothèques (Coil, OkHttp, WorkManager, security-crypto) fournissent leurs propres règles.
# Annotations de compilation référencées par Tink (security-crypto), absentes à l'exécution
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
