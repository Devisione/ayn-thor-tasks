# Инструкции для агента

## Проверка после правок (без устройства)

Агент **не ставит APK на Thor** и **не публикует GitHub Release**, пока пользователь явно не попросит.

Единственный обязательный шаг проверки — unit-тесты на JVM.

**Windows (PowerShell):**
```powershell
.\test.ps1
```
или:
```powershell
.\gradlew.bat test
```
Если `JAVA_HOME` не задан — `test.ps1` сам найдёт JBR из Android Studio.

**Git Bash / WSL / Linux / macOS:**
```bash
./gradlew test
```

Это JVM-симуляция: жесты, маппинг кнопок, pairing дисплеев, парсинг dumpsys, сценарии
переноса/обмена через fake shell (`DisplaySwapperSimulationTest`). Установка и эмулятор
для обычной проверки **не нужны**.

Сборка APK / установка / релиз — **только по явной просьбе пользователя**.

## Версия

Менять `versionCode` / `versionName` в `app/build.gradle.kts` и строку `vX.Y.Z` в `MainActivity.kt`
только когда пользователь просит релиз или явное поднятие версии.
- bugfix → минор: `1.0.0` → `1.1.0`
- новая фича → мажор: `1.0.0` → `2.0.0`

## ADB-бэкап (на устройстве у пользователя)

При pair/connect пишется `Documents/ThorDisplaySwapper/adb_backup.json` (cert/key + порт),
чтобы полный uninstall не требовал сопряжения с нуля. Код: `AdbIdentityStore`.

## Требования окружения

- JDK 17+
- Android SDK (compileSdk 36) — для Gradle

## Проект

- Kotlin + Jetpack Compose, minSdk 30
- Жесты: `input/ButtonGestures.kt`
- Маппинг кнопок: `input/ThorKeyMapper.kt`
- Accessibility: `TaskSwapService.kt`
- Обмен экранами: `DisplaySwapper.kt`, `DisplayPairing.kt`, `DisplayTransferRules.kt`

---

## Кнопки AYN Thor — инварианты (НЕ ЛОМАТЬ)

### Маппинг keycode

| Кнопка | KeyEvent | Логический ключ |
|--------|----------|-----------------|
| Назад | `KEYCODE_BACK` (4) | `BACK` |
| Home (геймпад) | `KEYCODE_F24` / `BUTTON_MODE` / `KEYCODE_HOME` с controller/gamepad device | `HOME` |
| AYN (под экраном) | `KEYCODE_HOME` с gpio / без gamepad source | `AYN` |

**Запрещено:**
- Считать `KEYCODE_HOME` = Home при любом source (включая gamepad).  
  Регрессия: AYN тогда делает MinimizeAll / «открывает главную».
- Менять маппинг без обновления `ThorKeyMapper` **и** `ThorKeyMapperTest`.

Источник: `ThorKeyMapper.map(keyCode, source, deviceName)`.

### Поведение жестов (`ButtonGestures`)

| Кнопка | Короткое | Двойное | Долгое (1 сек, таймер на DOWN) |
|--------|----------|---------|--------------------------------|
| Back | система Back | обмен **или** перенос одного app | Push активного на другой экран |
| Home | система Home (inject) | — | Minimize / Home на обоих экранах |
| AYN | система AYN (inject) | ничего (consume) | **список приложений** (`ACTION_ALL_APPS`, не «Запущенные») |

**Правила long-press:**
1. `postDelayed(1000)` на DOWN → вибрация + действие до отпускания.
2. UP после long — только consume.
3. Home и AYN: **consume DOWN** всегда.
4. Back long ≠ Home long: Push vs MinimizeAll.
5. AYN long = `OpenAllAppsList` (`ACTION_ALL_APPS`), **не** MinimizeAll и не «главная».

### Обмен экранами

1. Одно приложение обязано переноситься (двойной Back / long Back).  
   Тесты: `DisplayPairingTest`, `DisplayTransferRulesTest`, `DisplaySwapperSimulationTest`.
2. Пара дисплеев: `DisplayPairing.pickSwapDisplays` — `0` + экран с app.
3. Accessibility-scan **не** `clear()` карту при пустом scan.
4. После переноса **одного** app **двойным Back** — **не** вызывать `launchHomeOnDisplay`.
   Симуляция: `DisplaySwapperSimulationTest` проверяет отсутствие `launchHome`.
5. **Долгий Back** = push: сначала Home на исходном экране (пока фокус там),
   затем перенос app на другой. Home после переноса на Thor чистит целевой экран.
6. Long Back никогда не MinimizeAll.

---

## Чек-лист логики (покрыто unit-тестами)

- [ ] `.\test.ps1` / `.\gradlew.bat test` зелёный (на Unix: `./gradlew test`)
- [ ] AYN long → список приложений (`ButtonGesturesTest`)
- [ ] `KEYCODE_HOME` всегда AYN (`ThorKeyMapperTest`)
- [ ] Home long → MinimizeAll, не на UP
- [ ] Back double / long — swap / push, не minimize-all
- [ ] Одно app двойной Back — move без `launchHome` (`DisplaySwapperSimulationTest`)
- [ ] Долгий Back — Home на source **до** move (`DisplaySwapperSimulationTest`)

Финальный чек на реальном Thor — только у пользователя, когда он сам поставит сборку.
