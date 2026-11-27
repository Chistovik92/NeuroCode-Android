# План версии 0.9.5 — аккаунт и синхронизация между устройствами

Статус: в работе · версия 0.9.5.0 (versionCode 43) · раздел ROADMAP: «0.9.5 — аккаунт и
синхронизация между устройствами».

Текущее состояние: 0.9.4.6 (versionCode 42) выпущен. Синхронизации в приложении нет
(grep «oauth/account» — 0 результатов), есть ручной DI (`AppContainer`), хранение секрета
в Android Keystore (`SecureSecretStore`), журнал сессии 0.8.5 (`SessionJournal` + spill),
`ModelRef` без секретов — готовая основа для синхронизации.

Решения (согласованы 27.11.2026):

- **Бэкенд — Supabase**, без SDK: REST Auth (GoTrue) + PostgREST поверх OkHttp,
  который уже есть в проекте. RLS по `auth.uid()`, сервер видит только шифртекст.
- **Провайдеры входа — Google и GitHub** через OAuth PKCE (Custom Tab +
  `neurocode://` redirect); код входа один и тот же, отличается параметр `provider`.
- **Перенос ключа E2E — ключ синхронизации**: 32 случайных байта генерируются на первом
  устройстве, показываются один раз в виде base64-фразы, вводятся на втором устройстве
  в экране «Аккаунт». Сервер ключ не получает никогда.
  Отступление от формулировки ROADMAP: PBKDF2 не нужен — ключ уже 256-битный случайный,
  а не пароль; AES-256-GCM применяется напрямую к ключу.

---

## Часть 1 — криптография и модели синхронизации

- [x] `sync/SyncCrypto.kt`: генерация ключа (SecureRandom, 32 байта), base64-кодирование
  группы по 4 символа для удобного ввода, AES/GCM/NoPadding (IV 12 байт, тег 128),
  формат конверта `sync-envelope-v1` (base64 iv + ciphertext в JSON);
- [x] `sync/SyncModels.kt`: `SyncPayload` (schemaVersion, deviceId, deviceName, updatedAt,
  chats, projects, settings-часть без секретов, tombstones), `SyncEnvelope`;
  сериализация поверх kotlinx-serialization, все поля уже `@Serializable`;
- [x] `sync/SyncMerge.kt`: LWW по `updatedAt` для диалогов и проектов, tombstone
  (`deletedIds`) для удалений, чтобы удалённое не воскресало; настройки — LWW целиком
  с ручным выбором при сильном расхождении (отложено в 0.9.5.x: сначала цельная запись);
- [x] тесты `SyncCryptoTest`, `SyncMergeTest`.

## Часть 2 — аккаунт и OAuth

- [x] `CredentialKey.Account` в `CredentialStore` — refresh-токен в Keystore
  (`account:refresh-token`);
- [x] `sync/SupabaseAuthClient.kt`: `authorizeUrl(provider, redirect, codeChallenge)` —
  `/auth/v1/authorize?provider=…&redirect_to=…&code_challenge=…&code_challenge_method=s256`;
  `exchangeCode` → `POST /auth/v1/token?grant_type=pkce` (`auth_code` + `code_verifier`);
  `refresh` → `grant_type=refresh_token`; `signOut` → `/auth/v1/logout`;
- [x] PKCE: code_verifier 64 байта → S256 challenge (RFC 7636), хранится в памяти
  до завершения входа;
- [x] `sync/AccountRepository.kt`: состояние `SignedOut / SigningIn / SignedIn(user) / Error`,
  запуск Custom Tab (`ACTION_VIEW` https-ссылка авторизации), обработка редиректа
  `onOauthRedirect(uri)`, обмен кода, сохранение refresh-токена, автообновление
  access-токена при истечении, выход (локально + `logout`);
- [x] конфигурация `SupabaseConfig`: URL проекта и anon-ключ вводятся в «Аккаунт →
  Настройки синхронизации» (anon-ключ публичный по дизайну), хранятся в `AppSettings`;
  с пустым конфигом кнопки входа показывают инструкцию;
- [x] тесты `SupabaseAuthClientTest` (URL, PKCE, разбор ответов без сети).

## Часть 3 — движок синхронизации

- [x] `sync/SyncRepository.kt`: сборка `SyncPayload` из `ChatRepository` (все сессии),
  `ProjectRepository`, `SettingsRepository`; шифрование `SyncCrypto`; таблица
  `sync_blobs` в PostgREST (`user_id, device_id, device_name, payload, updated_at`);
  LWW: если у серверной записи `updated_at` новее локальной и с другого устройства —
  скачиваем и мержим, иначе загружаем;
- [x] tombstones: `SyncPayload.deletedChatIds` / `deletedProjectIds`; при мерже
  удалённые на другом устройстве сессии удаляются локально;
- [x] расписание первой версии: кнопка «Синхронизировать сейчас», автосинк при старте
  приложения и после изменений диалогов (debounce 30 с); фоновый WorkManager — 0.10.6;
- [x] режим «только Wi-Fi» (`SyncSettings.wifiOnly`) — проверка `NetworkCapabilities`
  перед загрузкой; в мобильной сети синк пропускается без ошибки;
- [x] тесты `SyncPayloadTest` (roundtrip, в payload нет секретов: ни `apiKey`, ни
  refresh-токена), `SyncMergeTest` (LWW, tombstones).

## Часть 4 — интерфейс

- [x] раздел «Аккаунт» в настройках: состояние входа (email, провайдер), кнопки
  «Войти через Google» / «Войти через GitHub», поле ключа синхронизации (показать/
  ввести), статус последнего синка, «Синхронизировать сейчас», «только Wi-Fi»,
  выход, «удалить данные аккаунта»;
- [x] анонимный режим остаётся по умолчанию: без входа ничего не меняется,
  синк не запускается;
- [x] deep link `neurocode://oauth` в манифесте + `MainActivity.onNewIntent`.

## Часть 5 — контрольные точки выпуска

- [x] VERSION → 0.9.5.0, versionCode → 43;
- [x] ROADMAP: отметить выполненное в 0.9.5, пометить отложенное;
- [x] `docs/releases/0.9.5.0-RELEASE-NOTES.md`;
- [x] unit-тесты зелёные (`gradlew :app:testDebugUnitTest`, 212 тестов);
- [ ] проверка на устройстве: вход Google/GitHub с реальным Supabase-проектом,
  перенос диалогов между двумя устройствами — после настройки проекта.

## Отложено из 0.9.5 (0.9.5.x)

- [ ] spill-файлы (текст >8000 символов) не синхронизируются в первой версии:
  в сообщении уже есть inline-префикс; полная синхронизация spill — отдельный пункт;
- [ ] выбор при сильном расхождении настроек (сейчас LWW целиком);
- [ ] QR-код для ключа синхронизации (сейчас base64-фраза);
- [ ] периодический фоновый синк через WorkManager (вместе с 0.10.6).

## Настройка Supabase (для проверки)

1. Создать проект на supabase.com; Authentication → Providers: включить Google
   (OAuth-клиент Google Cloud: redirect `https://<ref>.supabase.co/auth/v1/callback`)
   и GitHub (OAuth App с тем же callback);
2. Authentication → URL Configuration → Redirect URLs: добавить `neurocode://oauth`;
3. В приложении: Настройки → Аккаунт → Настройки синхронизации → URL проекта
   (`https://<ref>.supabase.co`) и anon-ключ;
4. SQL: таблица `sync_blobs (user_id uuid, device_id text, device_name text,
   payload text, updated_at timestamptz, primary key (user_id, device_id))` + RLS
   `auth.uid() = user_id`.
