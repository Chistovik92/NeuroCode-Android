# План версии 0.9.0 — управление моделями на устройстве

Статус: **в работе** (ветка `0.9.0`). Связанные документы: [ROADMAP.md](ROADMAP.md) (раздел 0.9.0),
[PLAN-0.9-shared-models.md](PLAN-0.9-shared-models.md) (интеграция с LocalDoc AI).

Порядок работ: сначала ошибки устойчивости (часть 1), затем основная дорожная карта 0.9.0
(часть 2), затем закрытие отложенных пунктов из более ранних версий (часть 3). Часть 4 —
контрольные точки и критерии готовности. Часть 5 — что уже готово.

---

## Часть 1. Ошибки устойчивости (по ревью кода)

Найдено двумя независимыми ревью: управление GGUF-моделями и ядро приложения.
Порядок внутри части — по серьёзности.

### Б-1. [КРИТИЧНО] Гонки в `LocalLlamaClient`: delete/load/unload/generate не синхронизированы

`loadedPath`/`loadedConversationKey` — обычные `var` без `@Volatile`/Mutex; читаются и пишутся
из разных корутин и диспатчеров (`deleteModel` на IO, `runLocal` на Main). `deleteModel` не
выгружает загруженную модель (`engine.cleanUp()` не вызывается) — RAM остаётся занятой, а
`delete` во время генерации + последующий `load` другого чата — риск нативного краха.

- [ ] обернуть все операции с `loadedPath`/`engine` в `Mutex` (по образцу `ProotManager`);
- [ ] в `deleteModel` при совпадении пути вызывать `unload()` (с `engine.cleanUp()`) до `file.delete()`;
- [ ] запретить удаление/переключение модели при активной генерации (см. Б-9).

### Б-2. [КРИТИЧНО] Порядок «сброс настроек → удаление файла» + игнорирование результата удаления

`SettingsViewModel.deleteLocalModel` сначала сбрасывает проекты и глобальные настройки, потом
удаляет файл, результат `Boolean` игнорируется. Неудачное удаление = молчаливая порча состояния
(ссылки сброшены, файл на месте).

- [ ] удалять файл первым, проверять результат (`file.delete()` == false → IOException с именем файла);
- [ ] только после успешного удаления сбрасывать проекты/настройки/ссылки диалогов;
- [ ] проверку «активная ли модель» делать внутри `settings.update { }` по актуальному `it`, а не по заранее прочитанному значению.

### Б-3. [КРИТИЧНО] `ChatRepository`: гонка «потерянное обновление» — сообщения диалога теряются

`update`/`persist` без мьютекса; `ChatViewModel.onAgentEvent` пишет TOOL-сообщения из отдельной
корутины параллельно с `answerTurn` → lost update при suspend-точке в `storage.apply`.

- [ ] обернуть `update`/`persist`/`initialize` в `Mutex.withLock` (как в `ChatStorage`);
- [ ] обновление `_sessions` — через атомарный CAS (`MutableStateFlow.update`).

### Б-4. [КРИТИЧНО] `AgentTools.execute` глотает `CancellationException` — «Стоп» не останавливает агента

`runCatching` превращает отмену в текст ошибки, агентный цикл продолжает шаги; также ловит `Error`,
маскируя фатальные сбои.

- [ ] ретроить `CancellationException` в `getOrElse`;
- [ ] тот же паттерн проверить в `runPostChecks` и `onAgentEvent` (ChatViewModel).

### Б-5. [КРИТИЧНО] `ShellSession.runOnce` может зависнуть навсегда

`reader.await()` без тайм-аута: фоновый процесс-внук держит пайп → агентный `run_command`
блокирует диалог навечно, `destroyForcibly()` убивает только прямого потомка.

- [ ] `withTimeoutOrNull` вокруг `reader.await()`;
- [ ] по тайм-ауту закрывать `child.inputStream` (будит читателя) и `destroyForcibly`.

### Б-6. [КРИТИЧНО] tar-slip через цель симлинка в `extractTarGz`

Проверяется только путь записи, но не `linkTarget` — симлинк `../x` выводит следующие записи
за пределы rootfs; сравнение префикса строковое, а не посегментное.

- [ ] валидировать `linkTarget` (запрет абсолютных и `..`);
- [ ] `startsWith(destination.toPath())` вместо строки;
- [ ] записи открывать с `LinkOption.NOFOLLOW_LINKS` (или не создавать симлинки вовсе).

### Б-7. [МАЖОР] Импорт GGUF: утечка `.part`, нет лимита при неизвестном размере, битый fallback

- [ ] `.part` удалять в `finally`, если переименование не состоялось; чистить осиротевшие `*.part` при `listModels`;
- [ ] при `total <= 0` — проверять свободное место с запасом до старта и перепроверять каждые ~64 МБ скопированных;
- [ ] fallback `copyTo` обернуть: при ошибке удалить `target`; сверить `target.length()` с `total`;
- [ ] в цикле копирования проверять `coroutineContext.ensureActive()` (отмена импорта при уходе с экрана);
- [ ] `usedMb` в сводке памяти: показывать также мусор `.part` (или чистить при входе на экран).

### Б-8. [МАЖОР] После удаления модели остаются битые ссылки

Сбрасываются только проекты и глобальная модель; не чистятся `session.model` диалогов,
`recentModels`, `favoriteModels` (чипы «недавних» предлагают удалённую модель).

- [ ] в `deleteLocalModel` чистить `favoriteModels`/`recentModels` по `key` (локальный — по имени);
- [ ] сбрасывать `session.model` диалогов, ссылающихся на удалённую GGUF;
- [ ] в `LocalModelList` окна выбора — безопасная проверка `selected` (`ref?.takeIf { kind == LOCAL }?.key == …`) вместо short-circuit `&&`.

### Б-9. [МАЖОР] Удаление активной загруженной модели без предупреждения

- [ ] в диалоге подтверждения (SettingsScreen) отдельным текстом: «модель сейчас активна и
  загружена — она будет выгружена из памяти, активный диалог переключится на глобальную модель»;
- [ ] `ChatViewModel`: при удалении модели активного диалога — сброс и выгрузка движка.

### Б-10. [МАЖОР] Отмена стрима OpenAI не рвёт соединение

`cancelChat()` помечает job отменённым, но OkHttp-стрим читает до `readTimeout` (90 с) /
`callTimeout` (15 мин); токены расходуются.

- [ ] зарегистрировать `invokeOnCancellation { call.cancel() }` (suspendCancellableCoroutine
  или обёртка вокруг `withContext`), `ensureActive()` в цикле чтения.

### Б-11. [МАЖОР] Main-thread I/O (ANR/jank)

По убыванию заметности:

- [ ] `SessionJournal.append` и `SpillStore.save` — на каждом сообщении из Main (`ChatRepository.append`);
  перенести на `Dispatchers.IO`; держать один экземпляр `SessionJournal` на сессию (сейчас `@Synchronized`
  на новых экземплярах — мьютекс не работает);
- [ ] `ShellSession.start/send` — `ProcessBuilder.start()` и blocking-write из UI-колбэков; запуск в `scope.launch(Dispatchers.IO)`;
- [ ] `FilesystemAccess.roots()` и `fsRootName` — SAF/binder-запросы на Main из агентного цикла; `withContext(IO)` + кэш с TTL;
- [ ] `LocalLlamaClient.isLoadedFor/load` — `File.canonicalPath` и нативная загрузка из `runLocal` на Main; `runLocal` — уже частично IO (`withContext` в load), проверить и довести;
- [ ] `prepareAttachments`: base64 картинок до 8 МБ на Main → `Dispatchers.Default`;
- [ ] `warnIfHeavy` — файловые статы на Main → IO.

### Б-12. [МАЖОР] `AppViewModel`: `container.close()` из onCleared одной ViewModel

Контейнер общий на процесс, а закрывается с UI-ViewModel; вложенные VM (`ChatViewModel`,
`EditorViewModel`) созданы напрямую — их `onCleared` не вызывается никогда.

- [ ] убрать `container.close()` из `onCleared` (закрытие — только при смерти процесса);
- [ ] вложенные VM получать через `ViewModelProvider`/фабрику, либо явно отменять их scope при закрытии.

### Б-13. [МАЖОР] `SettingsRepository.update` — read-modify-write без мьютекса

Параллельные `update` из нескольких ViewModel теряют изменения.

- [ ] `Mutex` вокруг update-цепочки + `_settings.update {}` (CAS); `upsertProvider` — вычислять список внутри блока.

### Б-14. [МАЖОР] `ApprovalGate`: видимость `decision`, вечное удержание мьютекса

- [ ] `decision` — `@Volatile`/доступ под тем же мьютексом;
- [ ] тайм-аут ожидания решения (иначе следующий tool-call с подтверждением зависает навсегда).

### Б-15. [МАЖОР] `OpenAiCompatibleClient`: несинхронизированные кэши-словари

`contextWindows`/`maxOutputTokens` — `HashMap` с записью на IO и чтением из Main/Orchestrator.

- [ ] `ConcurrentHashMap`; `_limits` — атомарный `update {}`.

### Б-16. [МИНОР] Прочее (брать пакетами по файлам)

- [ ] `_streamingResponse.value += event.text` — неатомарный RMW из IO-потоков; аккумулировать в `StringBuilder` внутри хода (как `runLocal`) и публиковать строку;
- [ ] `deleteChat` активной сессии при работающем агенте — сначала `cancelChat()`;
- [ ] двойная инициализация `chats.initialize()` (контейнер + ViewModel) — идемпотентность под мьютексом;
- [ ] непроверенные JSON-касты в `OpenAiCompatibleClient` (`jsonArray`/`jsonPrimitive` на недоверенных данных) → безопасные `as?`;
- [ ] флаг `received` взводится для reasoning при `onReasoning == null` — ретрай запрещён зря;
- [ ] `builders.size` как fallback-индекс tool_call — сливающиеся аргументы;
- [ ] `ChatStorage.checkNotNull(backend)` при гонке запуска — ленивый `loadAll` в `apply`;
- [ ] `JsonFileStore`: ложный ISE при неудачном удалении `.tmp` после успешного copyTo; оригинал не удаляется после copyTo в quarantine;
- [ ] `ProotManager.reset()` вне мьютекса — гонка с `initialize`;
- [ ] `verifyGuestShell`/`verifyExecutable`: читать вывод параллельно waitFor (буфер пайпа);
- [ ] `ShellSession.stop`: `writer?.close()` без `runCatching`;
- [ ] `SpillStore`: неограниченный рост `.neurocode/spill` — чистка по возрасту/лимиту при старте;
- [ ] `AgentOrchestrator`: неограниченный рост payload хода (tool-результаты + data-URL в каждом `complete`) — бюджет символов, спил больших результатов;
- [ ] `modelsFreeBytes` возвращает 0 при сбое StatFs — неотличимо от «диск полон»; вернуть null/`-1` и обрабатывать в UI;
- [ ] `File(path).canonicalPath` без обработки IOException в `load`/`isLoadedFor`;
- [ ] `projectsUsing` в диалоге удаления показывает скрытые песочницы — фильтровать `hidden`;
- [ ] диалог подтверждения удаления: `usedBy` вычисляется из `vm.projectsUsing` (все проекты), а список — из видимых; унифицировать.

---

## Часть 2. Основная дорожная карта 0.9.0 (незакрытые пункты)

Из [ROADMAP.md](ROADMAP.md), раздел 0.9.0.

### 2.1 Очистка кэша загрузок

- [ ] «Очистить кэш» в разделе «Среда выполнения»/«Модели»: размер `cacheDir` (rootfs-download,
  прочие временные) + кнопка очистки; не трогать установленный rootfs (это не кэш);
- [ ] показывать размер перед очисткой.

### 2.2 Общий менеджер загрузок (основа для 0.9.1)

- [ ] `DownloadManager`-подобный модуль: очередь, возобновление (HTTP `Range`), контроль
  свободного места, прогресс;
- [ ] уведомление с прогрессом — foreground service (`foregroundServiceType=dataSync`) для
  больших загрузок (rootfs и будущие модели); WorkManager для повтора после обрыва сети;
- [ ] проверка доступности сети до начала загрузки (перенос из 0.7.6: сбой сети сейчас виден
  как ошибка этапа с адресом);
- [ ] применить к загрузке rootfs в `ProotManager` (замена `downloadTo`), чтобы менеджер был
  рабочим кодом, а не заготовкой.

### 2.3 Совместное использование GGUF с LocalDoc AI (KMP)

По [PLAN-0.9-shared-models.md](PLAN-0.9-shared-models.md). Сторона LocalDoc AI (ContentProvider
`com.localdoc.ai.models`) реализуется в их версии 2.9.0; здесь — клиент NeuroCode.

- [ ] `<queries><package android:name="com.localdoc.ai" /></queries>` в манифесте (+ debug-суффикс
  `com.localdoc.ai.debug`);
- [ ] `LocalDocModelsClient`: `isInstalled()` (PackageManager, `runCatching`), `list()`
  (`contentResolver.query` → `MatrixCursor`-поля `_ID`/`name`/`size`), `open(id)` (openInputStream);
  всё на `Dispatchers.IO`, пустой список при отсутствии приложения;
- [ ] UI: группа «LocalDoc AI» в «Моделях на устройстве» — модели с размером и кнопкой
  «Импортировать»; подсказка «Установите LocalDoc AI…», если не установлен;
- [ ] импорт = переиспользование `LocalLlamaClient.importModel` (потоковое копирование, прогресс,
  проверка magic GGUF, предупреждение о размере, проверка свободного места — закрывается Б-7);
  после импорта модель в общем списке и в окне выбора;
- [ ] запасной путь `ACTION_SEND`: intent-filter на `ACTION_SEND`/`ACTION_VIEW` GGUF-файла
  (`application/octet-stream`) → импорт через существующий `importModel(uri)`;
- [ ] удаление/отсутствие LocalDoc AI не ломает импортированные модели (они в `files/models`);
- [ ] unit-тесты: разбор курсора списка, пустой список при отсутствии, фильтр невалидных путей.

### 2.4 Unit-тесты

- [ ] `LocalLlamaClient`: имя целевого файла (`uniqueTarget`), очистка `.part`, порядок
  delete→reset (после Б-1/Б-2 — через тестируемую обёртку логики);
- [ ] `ChatRepository`: параллельные append/update не теряют сообщения (после Б-3);
- [ ] `AgentTools`: CancellationException пробрасывается (после Б-4);
- [ ] tar: симлинк с `..`/абсолютной целью отклоняется (после Б-6);
- [ ] `LocalDocModelsClient`: разбор списка, пустой список.

---

## Часть 3. Отложенные пункты из более ранних версий

- [ ] **0.7.6 → 0.9.0:** окно контекста модели в списке выбора (`contextWindows` из `/models`,
  получатель — `OpenAiCompatibleClient.contextWindow(model)`); показывать в `ModelRow` subtitle
  облачного списка, если известно;
- [ ] **0.7.6 → 0.9.0:** проверка доступности сети до начала загрузки — закрывается в 2.2;
- [ ] **0.9.0 → 0.9.1 не переносим:** каталог Hugging Face и подбор под устройство остаются в 0.9.1
  (менеджер загрузок 2.2 — его основа).

---

## Часть 4. Контрольные точки и критерии готовности

После каждой части (не после каждого пункта):

- [ ] `gradlew :app:compileDebugKotlin` — зелёный;
- [ ] `gradlew :app:testDebugUnitTest` — зелёный (существующие 17 тест-классов + новые);
- [ ] `gradlew detekt` — зелёный (новый код в стиле проекта, `detekt-baseline.xml` при необходимости);
- [ ] commit в ветку `0.9.0` с осмысленным сообщением.

Выпуск 0.9.0 (versionCode 36):

- [ ] VERSION → `0.9.0`, versionCode 36;
- [ ] ROADMAP: отметить пункты 0.9.0, обновить «Логику версий»;
- [ ] `docs/releases/0.9.0-RELEASE-NOTES.md`;
- [ ] сборка debug+release, полный прогон тестов и detekt;
- [ ] merge в `main`, тег.

## Часть 5. Что уже готово в 0.9.0 (до этого плана)

- [x] экран «Модели на устройстве»: список GGUF с размером, датой и активной моделью
  (`ModelsOnDeviceSection.kt`);
- [x] переключение между импортированными моделями без повторного импорта, удаление;
- [x] у каждой модели видно проекты, использующие её; удаление используемой модели требует
  подтверждения и сбрасывает проекты на глобальную модель (`ProjectRepository.resetLocalModel`);
- [x] сведения о памяти: занято моделями, свободно, рекомендованный размер из `DeviceProfile`
  (`LocalLlamaClient.modelsFreeBytes`);
- [x] `ModelRef` в `Project`/`ChatSession` (0.7.7) — база для «кто использует модель»;
- [x] критерии «подключён» и Keystore вне главного потока (0.8.8) — устойчивая база для этой версии.

Опора для правок: хранилища (`JsonFileStore` — карантина + fsync) и `ProotManager`
(мьютекс-паттерн) — образцы корректного кода в этом же репо.
