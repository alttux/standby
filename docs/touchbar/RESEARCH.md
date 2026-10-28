# Исследование: работает ли touch-bar-simulator на новых macOS

Проект: [sindresorhus/touch-bar-simulator](https://github.com/sindresorhus/touch-bar-simulator),
последний коммит `d5d05fe` (6 марта 2024, «Discontinue app»). В README автор пишет, что приложение больше не работает
из-за изменений в macOS.

Стенд: Mac mini (Apple M4, без физического Touch Bar), **macOS 15.8.1 (24H32)**, Command Line Tools (Swift 6.1), без полного Xcode.

## 1. Как устроен оригинал

Вся эмуляция — около 75 строк в `TouchBarView.swift`. Остальное (окно, док, слайдер прозрачности, горячие клавиши,
меню) — обвязка. Используются пять приватных функций (объявлены в `Touch Bar Simulator-Bridging-Header.h`):

| Функция | Фреймворк | Назначение |
|---|---|---|
| `DFRGetStatus()` | DFRFoundation | текущий статус Touch Bar (бит 0 — физический Touch Bar активен) |
| `DFRSetStatus(2)` | DFRFoundation | включает **виртуальный** Touch Bar, который macOS рендерит без железа |
| `DFRGetScreenSize()` | DFRFoundation | размер Touch Bar в точках |
| `SLSDFRDisplayStreamCreate(0, queue, handler)` | SkyLight | `CGDisplayStream` с кадрами Touch Bar (IOSurface) |
| `DFRFoundationPostEventWithMouseActivity(type, point)` | DFRFoundation | передаёт в Touch Bar нажатие (`leftMouseDown/Dragged/Up`) в точках, начало координат слева внизу |

Алгоритм: если физического Touch Bar нет, вызвать `DFRSetStatus(2)`, создать поток, на каждый
`frameComplete` выставить IOSurface в `layer.contents`, мышь передавать через
`DFRFoundationPostEventWithMouseActivity`. При остановке вернуть исходный статус.

Это не самодельный Touch Bar, а **тот же механизм, что у симулятора Touch Bar в Xcode**: macOS рисует настоящий
системный интерфейс — кнопки активного приложения из его `NSTouchBar` и Control Strip.

## 2. Проверка на macOS 15.8.1

Xcode-проект с зависимостями SwiftPM (Defaults, KeyboardShortcuts и др.) без полного Xcode не собрать.
Поэтому ядро — код `TouchBarView.start()` и `mouseEvent()` — перенесено один к одному в консольную утилиту
[`probe/main.swift`](probe/main.swift).

### Результаты

| Шаг | Результат |
|---|---|
| Фреймворки в `/System/Library/PrivateFrameworks` | ✓ DFRFoundation, DFRDisplay, SkyLight на месте |
| Все 5 символов через `dlsym` | ✓ найдены |
| `DFRGetStatus()` | `0` (физического Touch Bar нет) |
| `DFRGetScreenSize()` | `1085 × 30` pt |
| `DFRSetStatus(2)` → `DFRGetStatus()` | `2` — виртуальный Touch Bar включён |
| `SLSDFRDisplayStreamCreate` + старт | ✓ поток создан, `CGDisplayStreamStart = 0` |
| Кадры | ✓ IOSurface **2170 × 60** px, формат `BGRA` (1111970369), кадры приходят только при изменениях |
| Содержимое | ✓ настоящий системный Touch Bar (ниже) |
| `DFRFoundationPostEventWithMouseActivity` | вызывается без ошибок; **без «Универсального доступа» нажатия игнорируются** |

Кадры, снятые на этом Mac:

Нет активного приложения с Touch Bar — `esc` и Control Strip:
![idle](raw-idle.png)

Finder — назад/вперёд, вид, Quick Look, «Поделиться», теги:
![finder](raw-finder.png)

Music — перемотка и воспроизведение (за 7 секунд 22 кадра анимации):
![music](raw-music.png)

Позже на телефоне так же отобразились Firefox (навигация, адресная строка, новая вкладка) и кнопка
«Сейчас играет» в Control Strip, когда играла музыка.

## 3. Что сломалось и почему проект «больше не работает»

1. **Сборка на новом SDK.** В SDK macOS 15 `CGDisplayStream.start()` и `stop()` помечены как недоступные
   («Please use ScreenCaptureKit instead»), и код оригинала не компилируется.
   **Обход:** символы `CGDisplayStreamStart` и `CGDisplayStreamStop` в CoreGraphics во время выполнения
   остались — достаточно получить их через `dlsym`.
2. **Линковка приватных фреймворков.** Начиная с macOS 11 они лежат в общем кэше dyld,
   а в Command Line Tools нет `.tbd`-заглушек для DFRFoundation и SkyLight. Оригинал собирался полным Xcode.
   **Обход:** `dlopen` + `dlsym` для всех пяти функций.
3. **Ввод требует «Универсального доступа».** Это было и раньше (FAQ оригинала); `AXIsProcessTrusted() == false` —
   нажатия молча отбрасываются. У приложения, подписанного ad-hoc, разрешение слетает после каждой пересборки:
   меняется хэш подписи.
4. **Зависание ControlStrip после аварийного завершения клиента.** Если процесс, включивший виртуальный Touch Bar,
   убит без восстановления статуса (`kill -9`, падение), ControlStrip — системный процесс, который рисует Touch Bar, —
   остаётся в сломанном состоянии: `DFRGetStatus` залипает на `2`, `DFRSetStatus(0)` не помогает, а
   `CGDisplayStreamStart` у **любого** процесса возвращает `1000` (kCGErrorFailure). Воспроизведено:
   `kill -9` компаньона → проверочная утилита получает `1000`. Лечится перезапуском ControlStrip
   (`killall ControlStrip`; его поднимает launchd, прав администратора не нужно), после этого поток сразу стартует.
   TouchBarServer (root) трогать не требуется.
5. Окно-оболочка оригинала опирается на приватный `NSWindow._setPreventsActivation`; для нашей задачи окно
   не нужно, поэтому это не проверялось.

**Вывод:** механизм эмуляции Apple на macOS 15.8.1 **жив**. Рендеринг виртуального Touch Bar и поток кадров
работают полностью. «Не работает» относится к сборке оригинала на новом SDK и к его упаковке, а не к API.
Риск в том, что API приватный и Apple может убрать его в любом обновлении.

## 4. Решение для StandBy

Переносить окно симулятора не нужно: кадры отправляются на телефон, нажатия возвращаются с телефона.
Подробности — в [ADAPTATION.md](ADAPTATION.md).
