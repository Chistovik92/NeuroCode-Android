# Архитектура NeuroCode Android

Актуально для версии **0.8.1**. Планируемые изменения — в [ROADMAP.md](ROADMAP.md).

## Модули

| Каталог | Назначение |
|---------|------------|
| `app/.../ai` | `AgentOrchestrator` (цикл агента), `AgentTools` (инструменты), `FsAgentTools` (доступ к файлам устройства), `OpenAiCompatibleClient` (облако, SSE), `LocalLlamaClient` (GGUF), `ProviderCatalog`, `CommandPolicy`, `ReasoningSplitter` |
| `app/.../data` | `ProjectRepository`, `ChatRepository`, `SettingsRepository`, `SecureSecretStore`, `JsonFileStore`, `PathGuard`, `FilesystemAccess` (корни доступа 0.8.2) |
| `app/.../terminal` | `ShellSession`, `ProotManager` (Linux-окружение), `ApprovalGate` |
| `app/.../git`, `lsp`, `device` | JGit, LSP-клиент, профиль устройства |
| `app/.../ui` | ViewModel'и, `NeuroCodeApp`, `ModernShell`, экраны (`screens`), Markdown и редактор (`components`) |
| `llama/` | Kotlin/JNI binding llama.cpp |

## Поток облачного агента

1. Пользователь отправляет задачу (текст и вложения). Проект берётся из самой сессии диалога
   (`ChatSession.projectId`), а не из текущего выбора в интерфейсе.
2. `ChatViewModel` собирает `CloudRequest`: история, вложения, активные скиллы (`AgentSkill`) и
   внешние инструменты (`ExternalAgentTool`).
3. `AgentOrchestrator` формирует системный промпт (проект, краткий контекст, блок скиллов) и схемы
   инструментов (`AgentTools.definitions`).
4. `OpenAiCompatibleClient` выполняет HTTPS Chat Completions запрос со стримингом SSE; размышления
   (`reasoning_content` / `<think>`) отделяются `ReasoningSplitter`.
5. Tool call выполняет `AgentTools`: пути проверяет `ProjectRepository.resolve` → `PathGuard`;
   запись, удаление и shell ждут `ApprovalGate`. Инструменты `fs_*` работают через
   `FilesystemAccess` и разрешённые пользователем корни (0.8.2).
6. Результат возвращается модели; цикл идёт до финального ответа или лимита шагов.
7. После правок могут выполняться пост-проверки (lint/test). При ошибке основного провайдера запрос
   повторяется через резервного (`fallbackProviderId`).

Инструменты: `list_files`, `read_file`, `write_file`, `replace_in_file`, `delete_file`
(с подтверждением, помечается как деструктивный), `search_text`, `run_command`, `git_status`,
`git_diff`, `fs_list`/`fs_search`/`fs_read`/`fs_import` (файлы устройства вне проекта, 0.8.2)
и внешние инструменты пользователя.

### Скиллы и внешние инструменты

- **Скилл** — `AgentSkill(name, prompt, enabled)`. Текст добавляется в системный промпт и
  сохраняется при смене модели. Код не выполняет.
- **Внешний инструмент** — `ExternalAgentTool(name, description, command, enabled)`. Регистрируется
  для модели как вызов фиксированной команды; запуск проходит через `ApprovalGate`.
- Оба механизма сейчас формируются только в облачном запросе; локальная модель их не получает.

## Локальная модель

GGUF выбирается через системный picker, проверяется по magic header, копируется в `files/models` и
загружается Android binding llama.cpp (JNI и CPU backends собираются NDK вместе с APK). Модель в APK
не упаковывается. Локальная модель работает в режиме чата и получает содержимое открытого файла
(до 20 000 символов) и текстовые вложения. Надёжный function calling для произвольных локальных
моделей намеренно не имитируется. Профиль устройства (`DeviceProfile`) рекомендует размер модели и
сокращает бюджет генерации на слабых устройствах.

## Хранилище

- `files/workspaces/<UUID>` — рабочие копии проектов (путь не зависит от названия, поэтому проекты
  с одинаковыми именами не пересекаются);
- `files/state/projects.json`, `settings.json` — метаданные проектов (включая скрытые «песочницы»
  диалогов без проекта) и настройки;
- `files/state/chats/<id>.json` — один диалог на файл, `chats/index.json` — лёгкий индекс; повреждённый
  файл уходит в `<имя>.corrupt-<время>` (`JsonFileStore`), индекс пересобирается из файлов;
- `files/state/chats.json.bak-0.8.0`, `chats.json.migrated` — следы переноса из формата 0.7.x
  (`ChatMigration`: резервная копия, проверка числа диалогов и сообщений, откат при сбое);
- `files/secure` — AES-GCM ciphertext API-ключей и токенов Git;
- `files/models` — импортированные GGUF;
- `files/linux` — proot и Alpine rootfs (если установлено), загрузки временно в `cacheDir`;
- `project/.neurocode/history` — снимки файлов до перезаписи;
- `project/.neurocode/attachments` — вложения диалога (не попадают в экспорт и синхронизацию).

Диалог привязан к проекту (`projectId`), список чатов фильтруется по активному проекту. Папка внешней
синхронизации привязывается только к одному проекту. Импорт через Storage Access Framework создаёт
рабочую копию; внешняя папка меняется только при явной синхронизации/экспорте.

## Терминал и Linux-окружение

`ShellSession` запускает `/system/bin/sh` под UID приложения. Если установлено Linux-окружение,
команда оборачивается в `proot` (`ProotManager.command`): rootfs Alpine, workspace как `/workspace`,
`/dev`, `/proc`, `/sys` пробрасываются. `proot` берётся из `nativeLibraryDir/libproot.so` (бинарник входит в APK для arm64-v8a, armeabi-v7a и
x86_64, `packaging.jniLibs.useLegacyPackaging = true`, см. `third_party/proot`); скачивание из репозитория
Alpine в `files/linux` осталось запасным путём, но запустить скачанный бинарник на Android 10+ нельзя
(ограничение SELinux). `ProotManager.command` строит команду через `ProotCommand`: оболочка хоста
(`/system/bin/sh -c`) задаёт `PROOT_TMP_DIR` и `PROOT_NO_SECCOMP`, переменные гостя — `env -i` внутри rootfs
(у proot нет параметра `--env`). При подготовке права на исполнение в rootfs восстанавливаются (`ExecutableBits`),
после чего реально запускается `/bin/sh` в гостевой системе: только после успеха состояние `READY`. Причина сбоя
(`failure`) содержит этап и описание сигнала. `ShellSession` не пробрасывает исключение запуска, а откатывается на
Android shell. На устройствах, где Android блокирует proot (SIGSYS), Linux-окружение недоступно.

## Выбор модели

Модель выбирается по цепочке «диалог → проект → глобально» (`ModelResolver.resolve`): `ChatSession.model`,
`Project.model`, затем `AppSettings` (`selectedProviderId` / `localModelPath`). `ModelRef` описывает
облачную (провайдер + модель) или локальную (GGUF) модель и не содержит секретов. Результат —
`ResolvedModel` с причиной недоступности (`ModelProblem`), подмены чужой моделью нет. `ChatViewModel`
публикует `activeModel`, а каждый ответ ассистента хранит `ChatMessage.generatedBy`. При смене модели
`HistoryAdapter` сворачивает результаты инструментов, убирает размышления и картинки (для моделей без
vision) и сокращает историю под окно контекста; локальная модель после перехода получает прежний
диалог текстом. Выбор проекта переносится в экспорте ZIP (`neurocode-project.json`, без секретов).

## Git

JGit работает с обычным `.git` внутри внутренней копии проекта. Терминал и Git UI независимы:
отсутствие бинарного git в shell не мешает status, diff, stage, commit и HTTPS clone/pull/push.

## Интерфейс

- два дизайна: классический (GitHub-dark, нижняя навигация) и современный (Material 3 Expressive в
  стиле Gemini: шторка, шапка с выбором модели, Material You);
- темы `SYSTEM` / `LIGHT` / `DARK`; на широких окнах `NavigationRail`;
- общая логика чата в `ChatViewModel`, оболочки `ClassicTopBar`/`ModernTopBar`/`ModernShell`;
- локализация ru/en через `strings.xml`.

## Ограничения 0.8.1

- агент видит только файлы проекта: доступ к остальной файловой системе Android не реализован;
- авторизация провайдеров только по API-ключу; вход по аккаунту подписок сторонним приложениям
  запрещён условиями вендоров (см. ROADMAP, 0.10.3);
- скиллы и внешние инструменты не работают с локальной моделью, автоматических тестов на них нет;
- proot на части устройств (например, эмулятор Android 15) убивается системой (SIGSYS): Linux-окружение там недоступно;
- GGUF-модель можно только импортировать из файла: автоматической загрузки с Hugging Face и
  списка нескольких моделей нет (хранится один `localModelPath`);
- нет коннекторов (MCP), плагинов и управления телефоном моделью;
- SSH-remote для Git не поддерживаются;
- синхронизация SAF не удаляет локальные файлы, отсутствующие во внешней папке; конфликт решается в
  пользу внешней папки, отдельного diff-диалога нет;
- облачный API использует OpenAI-compatible Chat Completions.
