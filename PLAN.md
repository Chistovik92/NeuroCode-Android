# План устранения ошибок и добавления функциональности NeuroCode-Android

**Дата**: 2026-10-04  
**Статус**: В разработке

---

## 📋 Резюме критических проблем

1. **UI ошибка выбора моделей** — Экран настроек показывает неправильное отображение при выборе моделей провайдеров
2. **Linux окружение не устанавливается** — ProotManager.initialize() возвращает false, возможны ошибки при загрузке rootfs или proot бинарика
3. **Скиллы не протестированы** — AgentSkill система реализована, но требует тестирования и функциональности
4. **Отсутствуют Коннекторы (MCP)** — Нужна интеграция MCP протокола для работы с внешними инструментами
5. **Отсутствуют Плагины/Расширения** — Система расширяемости пока не реализована

**Приоритет**: 1️⃣ UI + Filesystem > 2️⃣ Linux env > 3️⃣ Project isolation > 4️⃣ Skills > 5️⃣ Connectors > 6️⃣ Plugins

---

## 📁 0. ДОСТУП К ФАЙЛОВОЙ СИСТЕМЕ ANDROID

### Проблема
Модель может работать только с файлами в разрешённой папке (обычно `app.cacheDir` или `app.filesDir`). 
Нужна возможность:
- Поиска файлов по всей файловой системе устройства
- Работы с файлами в любых папках (с разрешением пользователя)
- Обхода ограничений Android Scoped Storage (API 30+)

### Текущее состояние
- `PathGuard.kt` — базовая проверка путей
- `SettingsRepository.kt` — сохранение только в filesDir
- Ограничен доступ к внешним папкам (DCIM, Download, Documents, Music, etc.)

### Решение

#### 0.1 Интеграция с Android Storage Access Framework (SAF)
- [ ] Использовать `ActivityResultContracts.OpenDocument()` для доступа к файлам
- [ ] Поддержка `Uri` вместо только File paths
- [ ] Кеширование разрешённых URI через SharedPreferences

#### 0.2 Расширить PathGuard для работы с Uri
```kotlin
// Новые методы в PathGuard
fun isValidUri(uri: Uri): Boolean
fun getFilePathFromUri(uri: Uri): String?
fun requestFileAccess(uri: Uri): Boolean
```

#### 0.3 Создать FilesystemManager для поиска
- [ ] Создать `app/src/main/java/com/secrethero/neurocode/filesystem/FilesystemManager.kt`
- [ ] Поддержка поиска файлов по названию/расширению
- [ ] Поддержка работы с Uri и обычными paths
- [ ] Кэширование результатов поиска

#### 0.4 Интегрировать в AgentOrchestrator
- [ ] Доступ агента к поиску файлов по FS
- [ ] Использование SAF для открытия/чтения файлов
- [ ] Запрос разрешений у пользователя при необходимости

#### 0.5 UI для управления разрешениями
- [ ] Экран "Разрешения доступа к файлам"
- [ ] Список предоставленных разрешений
- [ ] Возможность отозвать доступ

### Файлы для создания/модификации
- `app/src/main/java/com/secrethero/neurocode/filesystem/FilesystemManager.kt` — новый класс
- `app/src/main/java/com/secrethero/neurocode/PathGuard.kt` — расширить для Uri
- `app/src/main/java/com/secrethero/neurocode/ai/AgentOrchestrator.kt` — интегрировать FS доступ

---

## 🐛 1. UI ОШИБКА ВЫБОРА МОДЕЛЕЙ И УПРАВЛЕНИЕ МОДЕЛЯМИ

### Проблема
Экран выбора модели в dialogs/provider отображается неправильно. На скриншотах видна проблема:
- Диалог "Выбор модели" показывает провайдеры но поле модели может быть некорректно оформлено
- Возможны проблемы с загрузкой списка моделей от провайдера
- Поле поиска может не работать правильно
- **Нет функции управления моделями на устройстве** (локальные модели GGUF, кэш, удаление)

### Файлы для проверки
- `app/src/main/java/com/secrethero/neurocode/ui/screens/SettingsScreen.kt` — главный экран
- `app/src/main/java/com/secrethero/neurocode/ui/screens/dialogs/*` — диалоги (нужна проверка)
- `app/src/main/java/com/secrethero/neurocode/SettingsViewModel.kt` — логика загрузки моделей
- `app/src/main/java/com/secrethero/neurocode/ai/ProviderCatalog.kt` — каталог провайдеров
- `app/src/main/java/com/secrethero/neurocode/model/Models.kt` — модели данных

### Решение

#### 1.1 Исправление выбора моделей
- [ ] Проверить `loadProviderModels()` в SettingsViewModel — правильно ли обрабатываются ошибки
- [ ] Проверить UI компонент выбора моделей — правильное ли отображение в dialogs
- [ ] Проверить параллельную загрузку моделей — правильно ли обновляется состояние
- [ ] Добавить обработку пустого списка моделей
- [ ] Добавить loading state для визуализации процесса загрузки
- [ ] Протестировать с разными провайдерами (OpenAI, Groq, Mistral)

#### 1.2 Управление моделями на устройстве
- [ ] Создать `ModelManager.kt` класс для управления локальными моделями:
  - Список загруженных GGUF моделей
  - Размер каждой модели
  - Дата загрузки
  - Удаление моделей
  - Очистка кэша
  
- [ ] Добавить UI экран управления моделями (`ModelsManagerScreen.kt`):
  - Список загруженных локальных моделей
  - Кнопка удаления для каждой модели
  - Общий размер занимаемого места
  - Очистка кэша сетевых запросов
  - Показать место на диске (доступно/использовано)

- [ ] Интегрировать управление моделями в SettingsViewModel:
  - `getLocalModels()` — список моделей
  - `deleteLocalModel(path)` — удаление модели
  - `getModelSize(path)` — размер модели
  - `clearCache()` — очистка кэша
  - `getStorageInfo()` — информация о памяти

- [ ] Добавить в SettingsScreen новый раздел:
  ```
  "Управление моделями на устройстве"
  - Список загруженных моделей
  - Размер диска, используемый моделями
  - Кнопки удаления
  - Очистка кэша
  ```

---

## 🗂️ 1.5. ИЗОЛЯЦИЯ ДИАЛОГОВ И ПРОЕКТОВ

### Проблема
Разные диалоги, запросы и проекты могут смешиваться в одну папку, если у них:
- Похожие названия
- Одинаковые названия (дублирование)
- Одна и та же категория

**Текущее состояние:**
- `ChatRepository.kt` — сохраняет в единую папку
- `ProjectRepository.kt` — использует `projectId` но может быть путаница при создании
- Нет уникальных идентификаторов для диалогов

### Решение

#### 1.5.1 Создать уникальные идентификаторы
- [ ] Добавить UUID для каждого диалога/проекта/запроса
- [ ] Структура хранения:
```
app.filesDir/
├── chats/
│   ├── {uuid-dialog-1}/
│   │   ├── metadata.json (name, created, modified, type)
│   │   └── messages.json
│   ├── {uuid-dialog-2}/
│   │   ├── metadata.json
│   │   └── messages.json
├── projects/
│   ├── {uuid-project-1}/
│   │   ├── metadata.json
│   │   ├── files/
│   │   └── git/
│   ├── {uuid-project-2}/
│   │   ├── metadata.json
│   │   ├── files/
│   │   └── git/
└── requests/
    ├── {uuid-request-1}/
    │   ├── metadata.json
    │   └── response.json
```

#### 1.5.2 Создать DialogMetadata класс
```kotlin
data class DialogMetadata(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val displayName: String, // Может быть дублирующимся
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val type: DialogType, // CHAT, REQUEST, PROJECT
    val parentId: String? = null, // Для вложенных диалогов
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
)

enum class DialogType {
    CHAT,      // Обычный чат
    REQUEST,   // Одиночный запрос
    PROJECT,   // Проект
    THREAD,    // Ветка в чате
}
```

#### 1.5.3 Модифицировать Repository классы
- [ ] **ChatRepository** — использовать UUID вместо названия
  ```kotlin
  fun createDialog(name: String, type: DialogType): String {
      val id = UUID.randomUUID().toString()
      val metadata = DialogMetadata(id, name, type = type)
      val folder = File(getChatsDir(), id)
      folder.mkdirs()
      // Сохранить metadata.json
      return id
  }
  ```

- [ ] **ProjectRepository** — использовать UUID для изоляции
  ```kotlin
  fun createProject(name: String): String {
      val id = UUID.randomUUID().toString()
      val folder = File(getProjectsDir(), id)
      folder.mkdirs()
      // Сохранить metadata.json
      return id
  }
  ```

#### 1.5.4 Создать DialogManager
- [ ] Новый класс `app/src/main/java/com/secrethero/neurocode/data/DialogManager.kt`
- [ ] Методы:
  ```kotlin
  // Создание
  fun createDialog(displayName: String, type: DialogType): DialogMetadata
  
  // Получение
  fun getDialog(id: String): DialogMetadata?
  fun getAllDialogs(type: DialogType? = null): List<DialogMetadata>
  
  // Обновление
  fun renameDialog(id: String, newName: String)
  fun updateDialog(id: String, metadata: DialogMetadata)
  
  // Удаление
  fun deleteDialog(id: String)
  fun deleteAllDialogs(type: DialogType)
  
  // Поиск
  fun searchDialogs(query: String, type: DialogType? = null): List<DialogMetadata>
  fun findDuplicateNames(name: String): List<DialogMetadata>
  ```

#### 1.5.5 Обновить UI для отображения UUID
- [ ] В `ChatScreen.kt` показать:
  - Display name (название введённое пользователем)
  - Дату создания
  - Последнего обновления
  - Уникальный ID (мини-версия UUID для идентификации)

#### 1.5.6 Миграция существующих данных
- [ ] При обновлении приложения:
  - Найти все старые диалоги в папке
  - Создать UUID для каждого
  - Переместить в новую структуру с UUID
  - Сохранить mapping старый-путь → новый-UUID

### Файлы для создания/модификации
- `app/src/main/java/com/secrethero/neurocode/data/DialogManager.kt` — новый
- `app/src/main/java/com/secrethero/neurocode/data/ChatRepository.kt` — модификация
- `app/src/main/java/com/secrethero/neurocode/data/ProjectRepository.kt` — модификация
- `app/src/main/java/com/secrethero/neurocode/model/Models.kt` — добавить DialogMetadata, DialogType

---

## 🖥️ 2. УСТАНОВКА LINUX ОКРУЖЕНИЯ

### Проблема
На скриншоте видно: "Linux-окружение: недоступно на этом устройстве"
```
Установка proot с базой Alpine Linux, чтобы терминал и команды агента работали внутри 
полноценного Linux-окружения.
```

Возможные причины:
1. **Архитектура процессора не поддерживается** — проверить `alpineArch()` в ProotManager
2. **Ошибка при загрузке файлов** — проверить URL rootfs или proot бинарика
3. **Недостаточно места** — rootfs требует ~100-300MB свободного места
4. **Проблема с правами доступа** — невозможно установить права на бинарик (setExecutable)
5. **Неправильная обработка сетевых ошибок** — загрузка не обрабатывается правильно

### Файлы для проверки
- `app/src/main/java/com/secrethero/neurocode/terminal/ProotManager.kt` (строки 43-90)
  - `initialize()` — инициализация proot
  - `prepare()` — подготовка окружения
  - `installProotStatic()` — установка proot бинарика (строки 119+)
  - `downloadAndExtract()` — загрузка rootfs (нужно посмотреть полное содержимое)

### Решение
- [ ] Добавить детальный логирование в `prepare()` функцию
- [ ] Проверить поддерживаемые архитектуры (arm64-v8a, armeabi-v7a, x86_64)
- [ ] Добавить проверку свободного места перед загрузкой
- [ ] Улучшить обработку сетевых ошибок при загрузке
- [ ] Добавить retry логику для загрузки файлов
- [ ] Добавить прогресс-бар для загрузки в UI
- [ ] Тестировать на эмуляторе и реальных устройствах

---

## 🛠️ 3. ПОДДЕРЖКА СКИЛЛОВ АГЕНТА (уже частично реализовано)

### ✅ Что уже реализовано
- ✅ `AgentSkill` модель (id, name, description, command, enabled)
- ✅ `SettingsViewModel` методы: `saveSkill()`, `deleteSkill()`, `toggleSkill()`, `exportSkills()`, `importSkills()`
- ✅ UI в `SettingsScreen` для управления скиллами (строки 368-399)
- ✅ `AgentOrchestrator.run()` принимает `activeSkills: List<String>` параметр
- ✅ `systemPrompt()` включает скиллы в промпт агента
- ✅ Скиллы сохраняются в настройках (settings)
- ✅ Импорт/экспорт скиллов через JSON файл

### 🔨 Что нужно доделать
- [ ] **Выполнение скиллов** — реальное выполнение команд скиллов в AgentTools
  - Интеграция с ShellSession или ModernShell
  - Обработка результатов выполнения
  - Обработка ошибок выполнения

- [ ] **Тестирование скиллов** — Unit тесты для выполнения скиллов
  - Тесты на успешное выполнение
  - Тесты на обработку ошибок
  - Тесты на параметризованные команды

- [ ] **Библиотека встроенных скиллов** — добавить предустановленные скиллы
  - Git скиллы (commit, push, pull, branch, log)
  - NPM/Yarn скиллы (install, build, test, run)
  - Android скиллы (build, install, test)
  - Shell скиллы (find, grep, sed, awk)
  - Файловые операции (cp, mv, rm, mkdir)

- [ ] **Параметризированные скиллы** — поддержка переменных
  - Синтаксис ${arg0}, ${arg1}, ${arg2}...
  - Валидация параметров
  - Документирование параметров

- [ ] **Предпросмотр вывода скилла** — показать результат в UI
  - История выполнения скиллов
  - Output каждого скилла
  - Время выполнения

- [ ] **Документирование скиллов** — улучшить помощь в UI
  - Поле description (уже есть)
  - Примеры использования
  - Список параметров
  - Возможные ошибки

### Файлы для модификации
- ✅ `app/src/main/java/com/secrethero/neurocode/ai/AgentOrchestrator.kt` — уже передает activeSkills
- ✅ `app/src/main/java/com/secrethero/neurocode/model/Models.kt` — AgentSkill уже реализована
- ✅ `app/src/main/java/com/secrethero/neurocode/ui/screens/SettingsScreen.kt` — UI уже есть
- 🔨 `app/src/main/java/com/secrethero/neurocode/terminal/AgentTools.kt` — добавить выполнение скиллов
- 🔨 `app/src/main/java/com/secrethero/neurocode/terminal/ShellSession.kt` — использовать для выполнения

---

## 🔗 4. КОННЕКТОРЫ (MCP - MODEL CONTEXT PROTOCOL)

### Что нужно реализовать
MCP протокол для интеграции с внешними сервисами. Основные компоненты:

1. **MCP Client** — отправка запросов и инструментов
2. **Коннекторы для популярных сервисов**:
   - GitHub (repository tools, PR management)
   - Linear (issue tracking)
   - Slack (messaging)
   - Google Drive (file access)
   - Notion (database access)

### Архитектура
```
MCPConnectorManager
├── MCPClient (HTTP/WebSocket к MCP серверу)
├── ConnectorRegistry (регистрация доступных коннекторов)
└── ConnectorUI (экран управления коннекторами)
```

### Файлы для создания
- `app/src/main/java/com/secrethero/neurocode/connectors/MCPConnectorManager.kt` — менеджер MCP
- `app/src/main/java/com/secrethero/neurocode/connectors/MCPClient.kt` — MCP клиент
- `app/src/main/java/com/secrethero/neurocode/connectors/ConnectorRegistry.kt` — регистрация
- `app/src/main/java/com/secrethero/neurocode/model/MCPConnector.kt` — модель коннектора
- `app/src/main/java/com/secrethero/neurocode/ui/screens/ConnectorsScreen.kt` — UI

### Решение
- [ ] Разработать MCPConnectorManager класс
- [ ] Реализовать базовый MCP клиент (HTTP)
- [ ] Создать примеры коннекторов (GitHub, Linear)
- [ ] Интегрировать в SettingsViewModel
- [ ] Создать UI экран для управления коннекторами
- [ ] Добавить тестирование коннекторов

---

## 🧩 5. ПЛАГИНЫ/РАСШИРЕНИЯ

### Что нужно реализовать
Система плагинов для расширения функциональности:

1. **Plugin API** — интерфейс для разработки плагинов
2. **Plugin Loader** — динамическая загрузка плагинов
3. **Plugin System** — управление жизненным циклом плагинов

### Архитектура
```
PluginManager
├── PluginLoader (загрузка .jar файлов)
├── PluginRegistry (регистрация плагинов)
└── PluginUI (экран управления плагинами)
```

### Типы плагинов
- **Command Plugins** — добавить новые команды
- **UI Plugins** — добавить новые экраны/панели
- **Agent Plugins** — расширить возможности агента
- **Provider Plugins** — добавить новых провайдеров AI

### Файлы для создания
- `app/src/main/java/com/secrethero/neurocode/plugins/PluginManager.kt` — менеджер плагинов
- `app/src/main/java/com/secrethero/neurocode/plugins/PluginApi.kt` — API для плагинов
- `app/src/main/java/com/secrethero/neurocode/plugins/PluginLoader.kt` — загрузчик
- `app/src/main/java/com/secrethero/neurocode/plugins/PluginRegistry.kt` — регистрация
- `app/src/main/java/com/secrethero/neurocode/ui/screens/PluginsScreen.kt` — UI

### Решение
- [ ] Определить PluginApi интерфейс
- [ ] Реализовать PluginLoader для загрузки .jar файлов
- [ ] Создать PluginManager для управления плагинами
- [ ] Разработать примеры плагинов
- [ ] Создать UI для управления плагинами
- [ ] Документировать API для разработчиков плагинов

---

## 📅 План реализации по фазам

### Фаза 0: Инфраструктура (неделя 0-1)
1. ✅ Доступ к файловой системе Android (FilesystemManager)
2. ✅ Изоляция диалогов/проектов через UUID (DialogManager)
3. Миграция существующих данных на UUID базис

### Фаза 1: Критические исправления (неделя 1-2)
1. Диагностика и исправление UI ошибки выбора моделей
2. Создание ModelManager для управления локальными моделями
3. UI экран управления моделями и кэшем
4. Диагностика и исправление Linux окружения
5. Добавить detailed logging для обеих проблем

### Фаза 2: Скиллы (неделя 3)
1. Интегрировать скиллы в AgentOrchestrator
2. Добавить встроенные скиллы
3. Протестировать скиллы

### Фаза 3: Коннекторы MCP (неделя 4-5)
1. Реализовать MCPConnectorManager
2. Добавить примеры коннекторов (GitHub, Linear)
3. UI экран для коннекторов
4. Тестирование

### Фаза 4: Плагины (неделя 6-7)
1. Определить PluginApi
2. Реализовать PluginLoader
3. Создать примеры плагинов
4. UI экран для плагинов

---

## 🔍 Диагностика и тестирование

### Каждое исправление должно включать:
1. **Unit тесты** для новой функциональности
2. **Integration тесты** для взаимодействия компонентов  
3. **UI тесты** на эмуляторе или реальном устройстве
4. **Логирование** для отладки проблем

### Команды для запуска тестов
```bash
# Unit тесты
./gradlew test

# Instrument тесты на эмуляторе
./gradlew connectedAndroidTest

# Проверка кода
./gradlew detekt

# Сборка
./gradlew build
```

---

## 📝 Следующие шаги

1. ✅ Создать этот план
2. ⏭️ **Фаза 0**: Реализовать FilesystemManager и DialogManager (инфраструктура)
3. ⏭️ **Фаза 1**: Исправить UI моделей, создать ModelManager, fix Linux env
4. ⏭️ **Фаза 2-4**: Реализовать Skills, Connectors, Plugins согласно приоритету

## 🔑 Ключевые архитектурные решения

### Dialog Isolation Example
```
Before (Проблема):
app.filesDir/
├── my_dialog/
│   └── messages.json
└── my_dialog_v2/  ← Путаница!
    └── messages.json

After (Решение):
app.filesDir/chats/
├── 550e8400-e29b-41d4-a716-446655440001/
│   ├── metadata.json (name: "my_dialog")
│   └── messages.json
└── 550e8400-e29b-41d4-a716-446655440002/  ← Уникален!
    ├── metadata.json (name: "my_dialog_v2")
    └── messages.json
```

### Filesystem Access Pattern
```kotlin
// Пользователь выбирает файл → SAF предоставляет Uri
val uri = documentPicker.launch(...)

// FilesystemManager обрабатывает Uri
val file = filesystemManager.openFile(uri)
val content = file.readText()

// AgentOrchestrator использует для поиска
val results = filesystemManager.search("*.pdf")
```
