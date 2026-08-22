# Дорожная карта NeuroCode Android

Статусы: ✅ готово · 🚧 в работе · ⬜ запланировано

## 0.1.0 — базовая версия ✅

- [x] Чат с OpenAI-compatible провайдерами (OpenAI, OpenRouter, DeepSeek, Groq, Mistral + свои endpoint);
- [x] Агентный цикл: list_files, read_file, write_file, search_text, run_command, git_status, git_diff;
- [x] Подтверждение записи файлов и опасных shell-команд (ApprovalGate);
- [x] Проекты-песочницы, импорт папки через SAF;
- [x] Редактор Sora Editor, терминал /system/bin/sh;
- [x] Git через JGit: init, status, diff, stage, commit, история;
- [x] Локальный GGUF-inference (llama.cpp binding), шифрование ключей в Keystore;
- [x] CI: юнит-тесты + debug APK в GitHub Actions.

## 0.1.1 — экспорт проекта ✅

- [x] `ProjectRepository.exportTree`: копирование проекта в любую папку устройства через SAF
  с прогрессом, merge/overwrite, пропуск служебной `.neurocode/` и временных файлов;
- [x] UI: пункт меню «Экспортировать проект», диалог прогресса, snackbar-уведомления (`notice`);
- [x] Обновлены README и ARCHITECTURE (ручной экспорт вместо «нет возврата во внешнюю папку»).

## 0.1.2 — Git remote (HTTPS) ✅

- [x] `GitRepository`: `clone`, `setRemoteUrl`, `remoteUrl`; доработаны `pull`/`push`
  (явный `origin`, pull по имени ветки без upstream, push `HEAD:refs/heads/<branch>`);
- [x] Токен remote хранится в `SecureSecretStore` (AES-GCM, Keystore), username — в настройках по проекту;
- [x] UI GitScreen: карточка Remote (URL, пользователь, токен, Pull/Push/Подключить) и диалог клонирования
  в новый проект; клонирование доступно даже без локального `git init`;
- [x] Только HTTPS-адреса remote — HTTP запрещён, как и у API-провайдеров.

## 0.1.3 — надёжность агента и качество кода ⬜

- [ ] Стриминг облачных ответов (SSE `stream: true`, дельта-токены) — убирает тайм-аут 150 с на длинных генерациях;
- [ ] Сохранение хода агента в историю диалога (tool calls и результаты в `ChatSession`,
  поля `toolName`/`toolCallId` уже есть в модели) — модель не теряет контекст между запусками;
- [ ] Вынос политики безопасности shell (`commandRisk`/`isSafeReadOnlyCommand`) в тестируемый `CommandPolicy`
  + юнит-тесты на неё и на path traversal в `ProjectRepository.resolve`;
- [ ] Инструмент точечного редактирования `replace_in_file` (search/replace) + `delete_file`;
- [ ] Разделение `AppViewModel` на экранные ViewModel, удаление неиспользуемых зависимостей
  (navigation.compose, datastore).

## 0.2 и дальше — идеи без даты ⬜

- [ ] proot-окружение с полноценным Linux-окружением (отдельный модуль);
- [ ] Language Server Protocol / подсветка по языкам в редакторе;
- [ ] Автоматическая двусторонняя синхронизация с внешней папкой (сейчас — ручной экспорт);
- [ ] Release-подпись и публикация APK в CI;
- [ ] Локализация интерфейса (strings.xml, en).

## Версионирование

Номер версии ведётся в `VERSION` и `app/build.gradle.kts` (`versionName`/`versionCode`).
Версия помечается готовой, когда закрыты все её пункты и сборка зелёная.
