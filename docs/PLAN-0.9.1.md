# План версии 0.9.1 — загрузка GGUF с Hugging Face + устойчивость

Статус: **выполнен** (ветка `0.9.1`, versionCode 37). Связанные документы: [ROADMAP.md](ROADMAP.md) (раздел 0.9.1),
[PLAN-0.9.0.md](PLAN-0.9.0.md) (незакрытые пункты, перенесённые сюда).

Порядок работ: сначала незакрытые дефекты устойчивости из 0.9.0 (часть 1), затем основная
дорожная карта 0.9.1 (часть 2), затем контрольные точки (часть 3).

---

## Часть 1. Дефекты устойчивости, перенесённые из 0.9.0

### Б-16. Неатомарный RMW стриминга

`ChatViewModel.onAgentEvent` писал `_streamingResponse.value = _streamingResponse.value + event.text`
(и то же для reasoning) из IO-потоков стрима — потерянные дельты при параллельной эмиссии.

- [x] `_streamingResponse.update { it + event.text }` и `_streamingReasoning.update { it + event.text }`.

### SpillStore — неограниченный рост `.neurocode/spill`

Spill пишется при каждом крупном tool-результате, а чистка была только при удалении диалога —
осиротевшие файлы копились бы вечно.

- [x] `SpillStore.cleanup(olderThanMillis)` удаляет файлы старше 7 дней; вызывается из
  `ChatRepository.initialize()` на IO.

### Б-12. Вложенные ViewModel не получают `onCleared`

`AppViewModel` создаёт `ChatViewModel`/`EditorViewModel`/`GitViewModel`/`ProjectsViewModel`/
`SettingsViewModel`/`TerminalViewModel` напрямую (не через `ViewModelProvider`), поэтому их
`onCleared` не вызывался никогда. Замечание зафиксировано; сами вложенные VM держат только
ссылки на контейнер (ресурсы живут в `AppContainer`, закрываемом по смерти процесса) — утечка
`viewModelScope` ограничена жизнью процесса. Оставлено как осознанное решение (без отдельного
`clear()`), чтобы не раздувать версию; полноценный перевод на `ViewModelProvider` — отдельной
задачей.

### Краш 0.9.0 при запуске (OnePlus 15, Android 17 / ColorOS 17)

Релиз 0.9.0 падал при запуске на OnePlus 15 (Android 17). Стек не был доступен (на рабочей
машине нет adb/JDK/Android SDK), поэтому причина не подтверждена по логу. Ревизия кода 0.9.0
не выявила детерминированного NPE в стартовом пути (`NeuroCodeApplication` → `AppContainer` →
`MainActivity` → `AppViewModel`). Наиболее вероятные гипотезы, подлежащие проверке на устройстве:

- нативные библиотеки (`libproot.so` и др. из `jniLibs/`) на новом Android 17 при `targetSdk 36`;
- изменение поведения zygote/seccomp для 16k page-size устройств (ARM64) — `useLegacyPackaging`,
  `extractNativeLibs`;
- R8-минификация release-сборки с новыми `@Serializable`-типами 0.9.0.

Точная причина будет установлена по `logcat` на устройстве; исправление выносится в ближайший
патч-релиз.

---

## Часть 2. Дорожная карта 0.9.1

- [x] `ModelCatalog.kt` — встроенный каталог из 15 моделей Q4_K_M: `ModelSpec`, `ModelFamily`,
  `ChatTemplate`, оценки, `recommended`, `defaultContext`, `defaultGpuLayers`.
- [x] `ModelRecommender.kt` — подбор под устройство: `recommendedFor`/`fitting`/`rank` по
  `maxModelSizeMb` из `DeviceProfile`, пометка `tooLarge` (не скрывает, а объясняет).
- [x] `HuggingFaceClient.kt` — поиск `/api/models`, список файлов `/api/models/<repo>/tree/main`,
  загрузка `/<repo>/resolve/main/<file>` с `Range`-докачкой, SHA-256 (из LFS), magic GGUF,
  Wi‑Fi-only, `HuggingFaceQuantization` (выбор Q4_K_M → Q5_K_M → … под размер).
- [x] UI: «Рекомендовано для этого телефона» + живой поиск HF в `ModelsOnDeviceSection.kt`,
  включено в `SettingsScreen.kt`.
- [x] Gated-модели: токен Hugging Face в Keystore (`SettingsRepository.saveHuggingFaceToken/
  huggingFaceToken`), поле ввода в настройках.
- [x] Настройка «только по Wi‑Fi» (`AppSettings.wifiOnlyDownloads`).
- [x] После загрузки модель становится активной (`useLocalModel = true`).
- [x] Только HTTPS; характеристики устройства считаются локально и не передаются.
- [x] unit-тесты: `ModelRecommenderTest` (каталог, подбор, выбор квантизации под разные размеры).

---

## Часть 3. Контрольные точки

- [x] `VERSION` → `0.9.1`, versionCode 37 (`app/build.gradle.kts`).
- [x] ROADMAP: раздел 0.9.1 отмечен, «Логика версий» и «Известные проблемы» обновлены.
- [x] `docs/releases/0.9.1-RELEASE-NOTES.md`.
- [x] commit в ветку `0.9.1`.
- [ ] сборка `compileDebugKotlin` / `testDebugUnitTest` / `detekt` — не выполнена (нет JDK/SDK
  на рабочей машине); прогон перед merge обязателен в CI.
