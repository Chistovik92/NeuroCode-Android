# proot (Termux bionic build)

В `app/src/main/jniLibs/<abi>/` лежит набор библиотек, из которых собирается работающий proot:

| Файл | Назначение |
|------|-----------|
| `libproot.so` | сам proot, Termux fork `termux/proot` **5.1.107.102**, собранный против bionic |
| `libproot_loader.so` | внешний загрузчик (`PROOT_LOADER`), 64-битный freestanding ELF |
| `libproot_loader32.so` | 32-битный загрузчик (`PROOT_LOADER_32`), есть только для arm64-v8a/x86_64 |
| `libtalloc.so` | `libtalloc` 2.5.0 (переименован из `libtalloc.so.2`) |
| `libandroid-shmem.so` | `libandroid-shmem` 0.7 (реализация `shm_*` для Android) |

## Зачем

Старая сборка (`proot-static` 5.4.0-r2 из Alpine, musl) вызывала legacy `stat`(4)/`lstat`(6),
которых нет в seccomp-allowlist приложения на Android 15 (bionic отображает их в `newfstatat`(262)),
и proot умирал с SIGSYS. Termux-сборка собрана против bionic и использует только
`newfstatat`/`fstat`, которые разрешены.

Termux-сборка динамически связана и собрана с `PROOT_UNBUNDLE_LOADER`: загрузчик — отдельный файл,
на который proot указывает переменной `PROOT_LOADER`. В `ProotManager.resolveBundledLoader()` путь к
нему берётся из `nativeLibraryDir` рядом с `libproot.so`.

## Перепаковка

Termux `.deb` раскладывает файлы по `/data/data/com.termux/files/usr/...`, а libtalloc называется
`libtalloc.so.2` — Android из `jniLibs` вытаскивает только `lib*.so`. Скрипт скачивает `.deb`,
распаковывает их и патчит ELF:

- RUNPATH `/data/data/com.termux/files/usr/lib` → `$ORIGIN`;
- NEEDED `libtalloc.so.2` → `libtalloc.so`;
- SONAME в `libtalloc.so.2` → `libtalloc.so`.

Все правки in-place (новая строка короче или равна старой). Запуск:

```sh
python3 third_party/proot/fetch_termux_proot.py
```

Скрипт пишет в `app/src/main/jniLibs/<abi>/`; скачанные `.deb` кешируются в
`third_party/proot/.fetch-cache/` (не коммитится).

## Источники

- proot: <https://github.com/termux/proot> (fork от proot-me/proot), GPL-2.0-or-later;
  `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_<arch>.deb`
- libtalloc: `.../libt/libtalloc/libtalloc_2.5.0_<arch>.deb`
- libandroid-shmem: `.../liba/libandroid-shmem/libandroid-shmem_0.7_<arch>.deb`

`<arch>` — `aarch64`, `arm`, `x86_64` (соответствуют ABI `arm64-v8a`, `armeabi-v7a`, `x86_64`).

## Почему не из filesDir

Запуск файлов из `filesDir` был бы проблемой при `targetSdk 29+`, поэтому proot и загрузчик
кладутся в `nativeLibraryDir` (тип `apk_data_file`), где exec разрешён. Для этого в
`app/build.gradle.kts` выставлен `packaging.jniLibs.useLegacyPackaging = true` — библиотеки
распаковываются на диск, а не маппятся из APK.
