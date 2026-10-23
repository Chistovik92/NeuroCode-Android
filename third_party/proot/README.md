# proot-static (libproot.so)

Бинарные файлы `app/src/main/jniLibs/<abi>/libproot.so` — это неизменённый `proot.static`
из пакета Alpine Linux `proot-static` **5.4.0-r2** (`edge/community`), лицензия GPL-2.0-or-later.

| ABI | Архитектура Alpine | Пакет |
|-----|--------------------|-------|
| `arm64-v8a` | `aarch64` | `https://dl-cdn.alpinelinux.org/alpine/edge/community/aarch64/proot-static-5.4.0-r2.apk` |
| `armeabi-v7a` | `armv7` | `https://dl-cdn.alpinelinux.org/alpine/edge/community/armv7/proot-static-5.4.0-r2.apk` |
| `x86_64` | `x86_64` | `https://dl-cdn.alpinelinux.org/alpine/edge/community/x86_64/proot-static-5.4.0-r2.apk` |

Файл извлекается из `usr/bin/proot.static` пакета. Исходный код proot: <https://github.com/proot-me/proot>,
сборочные файлы Alpine: <https://gitlab.alpinelinux.org/alpine/aports/-/tree/master/community/proot>.

Зачем в APK: запуск исполняемых файлов из `filesDir` запрещён политикой SELinux при `targetSdk 29+`,
а из `nativeLibraryDir` разрешён. `ProotManager.resolveBundledBinary()` находит библиотеку первой,
скачивание остаётся запасным путём. Для работы нужен `packaging.jniLibs.useLegacyPackaging = true`.

Обновление: скачать новый `.apk`, извлечь `proot.static` в `libproot.so` для каждого ABI, поменять версию здесь.
