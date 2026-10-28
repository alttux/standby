# AGENTS.md — как работать с этим репозиторием

Инструкция для ИИ-агентов (Claude Code, Codex и др.), которые дорабатывают проект. Пользовательская документация — в [README.md](README.md).

## Проект в двух словах
- `android/` — лаунчер StandBy для Android 6.0+ (minSdk 23, targetSdk 36), Kotlin, **только framework API**.
- `companion/` — компаньон для macOS 13+ в строке меню, Swift Package (AppKit + SwiftUI), собирается в `.app` скриптом.
- Они общаются JSON-строками по TCP через `adb forward tcp:47800 tcp:47800` (USB или adb по Wi-Fi).
  Протокол описан в README → «Протокол лаунчер ⇄ компаньон». **Меняя протокол, меняйте обе стороны и таблицу в README.**

## Окружение и команды

```bash
# JDK: JBR из Android Studio (системной Java на этой машине нет)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Лаунчер
cd android && ./gradlew assembleRelease          # APK: app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug                          # тот же ключ подписи, отлаживается через run-as
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n dev.standby/.MainActivity
adb logcat -s StandBy                            # все логи лаунчера идут с тегом StandBy (data/Logx.kt)

# Компаньон
cd companion && swift build -c release           # быстрая проверка компиляции
./build.sh                                       # .app в build/ + dist/StandBy-Companion-macOS.zip
pkill -f "StandBy Companion.app/Contents/MacOS/StandByCompanion"; open "build/StandBy Companion.app"
tail -f ~/Library/Logs/StandByCompanion.log
open "build/StandBy Companion.app" --args --settings --logs --snapshot <dir>   # окна → PNG, без кликов
```

Версии: Gradle 9.8.2 (wrapper), AGP 9.4.1 со встроенным Kotlin, compileSdk 37, Swift tools 5.9 (язык Swift 5).
Сеть для загрузки зависимостей Gradle нужна только при первой сборке.

## Правила кода

**Лаунчер**
- Не добавлять AndroidX, Compose или другие библиотеки: APK весит около 450 КБ и обязан работать на слабом Android 6 (armv7).
  Всё рисуется через `View`/`Canvas`; контролы в стиле iOS лежат в `ui/Widgets.kt` и `settings/Rows.kt`.
- Строки интерфейса пишутся прямо в коде через `L("English", "Русский")` (`App.kt`), ресурсов `strings.xml` нет.
- Цвета — только из `Themes.p` (текущая `Palette`), никаких захардкоженных цветов в интерфейсе.
  Исключение — фон и иконки погоды: это «реальное» небо.
- Настройки — `data/Prefs.kt` (делегаты над SharedPreferences). Новая настройка = свойство в `Prefs`
  + строка в `settings/SettingsPane.kt`. Если ключ требует пересоздать интерфейс (тема, состав карточек),
  обработайте его в `MainActivity.onPref`.
- Новая карточка: класс-наследник `cards/Card` → ключ в `Prefs.CARD_KEYS` → `MainActivity.createCard` →
  название в `SettingsPane.cardNames` → раздел настроек (`page(id)`, ключ раздела = ключу карточки,
  чтобы работало долгое нажатие).
- Анимации и тики только пока `isActiveCard == true`; частота кадров ограничена (`postInvalidateDelayed`).
- Раскладка Touch Bar (`TouchBarCard.splitIntoRows`) режет только по промежуткам ≥ 12 px (между группами кнопок);
  пороги взяты из реальных кадров, см. `docs/touchbar/ADAPTATION.md`.
- Касания внутри карточек — через `TapDetector`, чтобы не ломать свайпы `CardPager`/`Navigator`.
  Контрол, который тянут по горизонтали, вызывает `parent.requestDisallowInterceptTouchEvent(true)` на `ACTION_DOWN`.
- Не называйте объекты `Companion` (конфликт с `companion object` в Kotlin) — объект подключения называется `Mac`.
- Не переопределяйте случайно методы `View` свойствами (так было с `scrollY` → `detailScrollY`).

**Компаньон**
- `Process.terminationStatus` нельзя читать, пока процесс жив: это Objective-C исключение и падение
  (см. `ADB.run`, там есть таймаут и SIGKILL).
- `AVAudioNode.installTap` бросает исключение, если формат не совпадает с железом (переключение Bluetooth-профиля):
  проверяйте `inputFormat` и `outputFormat` до установки (см. `MicSource.start`).
- Долгие вызовы adb (`adb connect` — до 6 с) не делаются на очереди `Connection.q`: через неё идёт отправка данных
  (см. `probeQ`).
- `adb tcpip` перезапускает adbd и рвёт USB — вызывать только если TCP-режим на телефоне выключен.
- В AppleScript `st` — зарезервированное слово (порядковые числа: `1st`). Ошибки AppleScript пишутся
  в лог (`MediaController.run`), не глушите их.
- Логи: `Log.i`/`Log.w` — окно Logs, stdout и `~/Library/Logs/StandByCompanion.log`.
- Новые разрешения macOS = ключ `NS…UsageDescription` в `companion/Resources/Info.plist`.
- Touch Bar (`TouchBar.swift`) — приватные DFRFoundation и SkyLight через `dlsym`, плюс `CGDisplayStreamStart/Stop`
  через `dlsym` (в SDK macOS 15 они недоступны). Всегда возвращайте `DFRSetStatus` к исходному значению.
  Нажатия работают только с «Универсальным доступом», а у ad-hoc-сборки он сбрасывается после каждой пересборки.
  Не убивайте компаньон через `kill -9` во время трансляции: ControlStrip зависнет, и поток будет
  возвращать `1000`, пока его не перезапустить (`killall ControlStrip`; компаньон делает это сам).
  Исследование — в `docs/touchbar/RESEARCH.md`, проверочная утилита — `docs/touchbar/probe/main.swift`.
- Подписки в `subscribe` независимы (`viz`, `touchbar`): меняйте только ключи, которые пришли в сообщении.

**Текущие решения пользователя — не менять без запроса**
- Визуализатор созвонов берёт **только микрофон** (`Visualizer.update`); `SystemAudioSource` оставлен, но не используется.
- Порядок карточек и настройки на тестовом телефоне выставлены пользователем; при переустановке не сбрасывайте
  данные (`adb install -r`, не `uninstall`).

## Тестовое устройство
- Jinga Storm, Android 6.0 (API 23), 720×1280 @ 320 dpi → в альбомной ориентации 640×360 dp, armeabi-v7a.
  USB-серийник `0123456789ABCDEF`, Wi-Fi `192.168.1.150:5555` (если включён adb по TCP).
- Интернета на телефоне может не быть: погода тогда идёт через прокси компаньона.
- На телефоне стоит другой лаунчер `com.standby.clock`; кнопка HOME может открыть выбор лаунчера.
  Для тестов запускайте `adb shell am start -n dev.standby/.MainActivity`.
- **Пользователь может одновременно трогать телефон.** Перед выводами по свайпам сверяйтесь со скриншотом
  (`adb exec-out screencap -p > shot.png`), а не с ожидаемым порядком карточек.
- Координаты для `adb shell input` — в пикселях альбомного экрана 1280×720.
- Release-сборка не отлаживается (`run-as` не работает); чтобы прочитать настройки, поставьте debug-сборку
  (тот же ключ) и выполните `adb shell run-as dev.standby cat shared_prefs/standby.xml`.

## Чек-лист перед сдачей
1. `./gradlew assembleRelease` и `swift build -c release` без ошибок.
2. APK установлен на телефон, лаунчер запускается: в `adb logcat -s StandBy` есть «Launcher started», нет `AndroidRuntime`.
3. Компаньон подключается: в логе есть «Connected to … · USB/Wi-Fi».
4. Затронутые карточки проверены скриншотами.
5. Обновлены `dist/` (`cp …/app-release.apk dist/StandBy.apk`, `./build.sh`), README и, если менялся протокол, его таблица.
6. Не коммитить `build/`, `.build/`, `.gradle/`, `local.properties` (см. `.gitignore`).
