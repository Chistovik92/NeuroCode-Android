# Архитектура NeuroCode Android

Актуально для версии **0.7.5**. Планируемые изменения — в [ROADMAP.md](ROADMAP.md).

## Модули

| Каталог | Назначение |
|---------|------------|
| `app/.../ai` | `AgentOrchestrator` (цикл агента), `AgentTools` (инструменты), `OpenAiCompatibleClient` (облако, SSE), `LocalLlamaClient` (GGUF), `ProviderCatalog`, `CommandPolicy`, `ReasoningSplitter` |
| `app/.../data` | `ProjectRepository`, `ChatRepository`, `SettingsRepository`, `SecureSecretStore`, `JsonFileStore`, `PathGuard` |
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
   запись, удаление и shell ждут `ApprovalGate`.
6. Результат возвращается модели; цикл идёт до финального ответа или лимита шагов.
7. После правок могут выполняться пост-проверки (lint/test). При ошибке основного провайдера запрос
   повторяется через резервного (`fallbackProviderId`).

Инструменты: `list_files`, `read_file`, `write_file`, `replace_in_file`, `delete_file`
(с подтверждением, помечается как деструктивный), `search_text`, `run_command`, `git_status`,
`git_diff` и внешние инструменты пользователя.

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
- `files/state/projects.json`, `chats.json`, `settings.json` — метаданные проектов, **все** диалоги
  одним файлом и настройки;
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
`/dev`, `/proc`, `/sys` пробрасываются. `proot` берётся из `nativeLibraryDir/libproot.so`, если он
есть в APK, иначе скачивается из репозитория Alpine в `files/linux`. Скачанный бинарник на Android 10+
запустить нельзя (ограничение SELinux), поэтому в 0.7.5 установка на таких устройствах завершается
состоянием `UNAVAILABLE` без указания причины — исправление в 0.7.6. При сбое терминал откатывается
на Android shell.

## Git

JGit работает с обычным `.git` внутри внутренней копии проекта. Терминал и Git UI независимы:
отсутствие бинарного git в shell не мешает status, diff, stage, commit и HTTPS clone/pull/push.

## Интерфейс

- два дизайна: классический (GitHub-dark, нижняя навигация) и современный (Material 3 Expressive в
  стиле Gemini: шторка, шапка с выбором модели, Material You);
- темы `SYSTEM` / `LIGHT` / `DARK`; на широких окнах `NavigationRail`;
- общая логика чата в `ChatViewModel`, оболочки `ClassicTopBar`/`ModernTopBar`/`ModernShell`;
- локализация ru/en через `strings.xml`.

## Ограничения 0.7.5

- Linux-окружение не устанавливается на устройствах, где запрещён запуск скачанных бинарников;
- агент видит только файлы проекта: доступ к остальной файловой системе Android не реализован;
- все диалоги хранятся в одном `chats.json`;
- провайдер и модель выбираются глобально (`selectedProviderId`, `localModelPath`), а не на проект или
  диалог; локальная модель не входит в окно выбора, сообщение не хранит, какая модель его сгенерировала
  (цепочка «диалог → проект → глобально» запланирована в 0.7.7);
- авторизация провайдеров только по API-ключу; вход по аккаунту подписок сторонним приложениям
  запрещён условиями вендоров (см. ROADMAP, 0.10.3);
- скиллы и внешние инструменты не работают с локальной моделью, автоматических тестов на них нет;
- GGUF-модель можно только импортировать из файла: автоматической загрузки с Hugging Face и
  списка нескольких моделей нет (хранится один `localModelPath`);
- нет коннекторов (MCP), плагинов и управления телефоном моделью;
- SSH-remote для Git не поддерживаются;
- синхронизация SAF не удаляет локальные файлы, отсутствующие во внешней папке; конфликт решается в
  пользу внешней папки, отдельного diff-диалога нет;
- облачный API использует OpenAI-compatible Chat Completions.
