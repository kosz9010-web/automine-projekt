AutoMine - Fabric 1.21.4 (klient)

JAK ZBUDOWAĆ .JAR (najłatwiej, bez instalowania czegokolwiek):
1. Załóż konto na github.com i utwórz nowe repozytorium (prywatne może być).
2. Wgraj całą zawartość tego folderu (razem z ukrytym folderem .github).
3. Wejdź w zakładkę Actions -> "Build mod" -> poczekaj ~2 min.
4. Na dole przebiegu pobierz artefakt "automine-jar" - w środku jest automine-1.0.0.jar.

LOKALNIE: zainstaluj JDK 21 i Gradle 8.12, w tym folderze uruchom:  gradle build
Plik jar: build/libs/automine-1.0.0.jar

INSTALACJA: wrzuć jar do .minecraft/mods razem z Fabric API (1.21.4) i Fabric Loader.

UŻYCIE:
 M - lista bloków (wyszukiwarka, klik = zaznacz/odznacz)
 K - start / stop kopania
