# План — совместное использование GGUF-моделей из LocalDoc AI (KMP)

Статус: **проект** (реализация планируется на 0.9.0–0.9.1). Связанные документы:
[ROADMAP.md](ROADMAP.md) (раздел 0.9) и [LocalDoc AI (KMP)](https://github.com/Chistovik92/LocalDoc-AI---KMP).

## Цель

Брать локальные GGUF-модели, уже скачанные приложением **LocalDoc AI (KMP)**, и использовать их в
NeuroCode **без повторной загрузки из сети**. Оба приложения наши, поэтому мы можем доработать обе стороны.

## Что есть сейчас (проверено по коду обоих проектов)

| Область | NeuroCode | LocalDoc AI (KMP) |
|---------|-----------|-------------------|
| Пакет | `com.secrethero.neurocode` (debug `.debug`) | `com.localdoc.ai` (debug `.debug`) |
| Где лежат GGUF | `files/models` (внутренняя память, app-private) | `getExternalFilesDir(null)/models/<id>.gguf` (внешняя app-private) |
| Имя файла | имя файла после импорта | `<id>.gguf` (`id` — стабильный ключ каталога) |
| Список моделей | `LocalLlamaClient.listModels()` сканирует каталог | `ModelDownloader.modelsDir()` + `ModelCatalog` (15 моделей) |
| Импорт | `LocalLlamaClient.importModel(uri)` через SAF, проверка magic GGUF | скачивание по HTTP `Range` с докачкой |
| Загрузка движком | по пути файла (`arm.aichat` / llama.cpp) | по пути файла (`cpp_core` / llama.cpp) |
| Отдача файлов наружу | нет | `FileProvider` только для APK (`com.localdoc.ai.updates`) |

Ключевое ограничение — **песочница Android**. Модели LocalDoc AI лежат в
`/sdcard/Android/data/com.localdoc.ai/files/models/`, и NeuroCode не имеет к ним прямого доступа
(scoped storage, Android 11+: даже `MANAGE_EXTERNAL_STORAGE` не открывает `Android/data` чужих приложений).

## Подходы

1. **ContentProvider в LocalDoc AI** (рекомендуется) — LocalDoc AI публикует список и байты моделей,
   NeuroCode читает и импортирует копированием.
2. **SAF ACTION_SEND** (запасной, без провайдера) — LocalDoc AI отдаёт одну модель через Intent,
   NeuroCode принимает `content://`-URI и импортирует как обычный GGUF.
3. **Общая SAF-папка** (без копии, но сложнее) — обе стороны пишут/читают общую папку, выбранную
   пользователем. Требует доработки LocalDoc AI под внешнюю папку и решения «движку нужен путь файла,
   а SAF даёт `content://`». Откладываем.

## Рекомендуемый подход — ContentProvider (1)

### Сторона LocalDoc AI (KMP)

Добавить в `androidMain` провайдер моделей:

```xml
<provider
    android:name=".ModelProvider"
    android:authorities="com.localdoc.ai.models"
    android:exported="true"
    android:grantUriPermissions="true" />
```

`ModelProvider`:

- `query(content://com.localdoc.ai.models)` → `MatrixCursor` со списком **скачанных** моделей:
  `_ID` = `id`, `name` (из `ModelCatalog`), `size` (байт), `url` (опционально). Сканирует
  `ModelDownloader().modelsDir()` на `*.gguf`.
- `openFile(content://com.localdoc.ai.models/<id>)` → `ParcelFileDescriptor.open(file, MODE_READ_ONLY)`.
  Работает без `FileProvider`-paths: провайдер выполняется в процессе LocalDoc AI и открывает свой
  собственный файл напрямую по `File`.
- Только `.gguf` внутри `modelsDir`, никаких путей вне каталога (проверка канонического пути).
- Модели — не пользовательские данные (книги, пароли, cookie лежат в других каталогах и не отдаются).

### Сторона NeuroCode

1. Разрешить видимость пакета:
   ```xml
   <queries><package android:name="com.localdoc.ai" /></queries>
   ```
2. Новый `LocalDocModelsClient`:
   - `isInstalled()` → `PackageManager.getPackageInfo("com.localdoc.ai", 0)` в `runCatching`;
   - `list()` → `contentResolver.query("content://com.localdoc.ai.models")` → `List<LocalDocModel>`;
   - `open(id)` → `contentResolver.openInputStream("content://com.localdoc.ai.models/$id")`.
3. UI: в экране «Модели на устройстве» (0.9.0) отдельная группа **«LocalDoc AI»** — список моделей с
   размером и кнопкой «Импортировать». Импорт = потоковое копирование в `files/models` с переиспользованием
   существующего прогресса, проверки свободного места, предупреждения о размере (`DeviceProfile`) и проверки
   magic GGUF (`GgufMetadataReader`). После импорта модель появляется в обычном списке и в окне выбора (0.8.7).
4. Отсутствие LocalDoc AI → группа скрыта или показывает подсказку «Установите LocalDoc AI, чтобы взять его
   модели без повторного скачивания».

### Запасной путь — SAF ACTION_SEND (2)

- LocalDoc AI: добавить в существующий `FileProvider` путь `<external-files-path name="models" path="models/" />`
  и кнопку «Поделиться моделью» (`ACTION_SEND`, `content://com.localdoc.ai.updates/models/<id>.gguf`,
  `FLAG_GRANT_READ_URI_PERMISSION`).
- NeuroCode: `intent-filter` на `ACTION_SEND`/`ACTION_VIEW` с `.gguf` (`application/octet-stream`), импорт
  через уже готовый `importModel(uri)`. Одна модель за раз, без списка — годится как резерв, пока нет провайдера.

## Компромисс и альтернатива

Копирование дублирует место (модель живёт в обоих приложениях). Модели 0.5–7 ГБ, для удобства «не качать
заново» это приемлемо. Настоящее «без копии» требует общей SAF-папки (подход 3), но движок NeuroCode грузит
модель по пути файла, а SAF даёт `content://` — пришлось бы копировать в кэш при каждом запуске, что не лучше
копирования. Поэтому подход 3 не берём в 0.9.

## Критерии готовности

- [ ] LocalDoc AI: `ModelProvider` отдаёт список и байты, доступ к файлам вне `modelsDir` запрещён.
- [ ] NeuroCode: установленный LocalDoc AI виден; список моделей показывается в «Моделях на устройстве».
- [ ] импорт модели копированием работает, показывает прогресс и предупреждение о размере, проверяет magic GGUF.
- [ ] удаление/отсутствие LocalDoc AI не ломает уже импортированные модели (они лежат в `files/models`).
- [ ] unit-тесты: разбор списка провайдера, отказ на путь вне `modelsDir`, пустой список при отсутствии приложения.
- [ ] проверка на устройстве с обеими установленными версиями.
