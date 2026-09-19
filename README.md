# skyTox

P2P-мессенджер на базе Tox: текстовые и голосовые сообщения, отправка файлов,
аудиозвонки, локальный импорт/экспорт данных и встроенный модуль `.nes` игр.

P2P messenger based on Tox: text and voice messages, file transfer, audio calls,
local import/export, and an embedded `.nes` games module.

## Версия 0.8.25

- Встроенный NES-эмулятор заменен на модуль на базе Multiness.
- Добавлен локальный мультиплеер по Wi-Fi: один телефон запускает сервер, второй подключается к нему.
- В разделе `Игры` отображаются `.nes` файлы из папки `skyTox games`; ROM-файлы не распространяются вместе с приложением.
- Добавлен QR-код для подключения второго телефона и ручной ввод IP-адреса, если сканер недоступен.
- В эмулятор добавлены `Выход`, меню настроек, сброс игры, прозрачность/скрытие кнопок, Turbo A/B, быстрое сохранение и быстрая загрузка.
- Улучшена работа игрового стрима и управления второго игрока.
- Сохраняется обновление приложения через кнопку `Обновить skyTox` в настройках.

### Как включить мультиплеер

1. Один игрок раздает Wi-Fi или оба телефона подключаются к одной локальной Wi-Fi сети.
2. На первом телефоне откройте `Игры`, нажмите меню с тремя точками и выберите `Запуск сервера`.
3. На втором телефоне откройте `Игры`, выберите `Присоединиться` и отсканируйте QR-код с первого телефона.
4. После подключения первый игрок выбирает и запускает игру. Второй телефон подключается как второй игрок.

## Version 0.8.25

- Replaced the embedded NES emulator with a Multiness-based module.
- Added local Wi-Fi multiplayer: one phone starts a server, the second phone joins it.
- The `Games` screen lists user-provided `.nes` files from the `skyTox games` folder; ROM files are not distributed with the app.
- Added QR-code joining and manual IP entry when a QR scanner is unavailable.
- Added emulator controls: `Exit`, settings menu, game reset, button opacity/hide toggle, Turbo A/B, quick save, and quick load.
- Improved game streaming and second-player controls.
- The in-app `Update skyTox` button in settings remains the update path.

### How to start multiplayer

1. One player shares Wi-Fi, or both phones connect to the same local Wi-Fi network.
2. On the first phone, open `Games`, tap the three-dot menu, and choose `Start server`.
3. On the second phone, open `Games`, choose `Join`, and scan the QR code from the first phone.
4. After the connection is established, the first player chooses and starts the game. The second phone joins as player two.

## Third-party emulator

The NES module uses Multiness components:
https://github.com/kakashidinho/Multiness_public

No `.nes` ROM files are included in skyTox. Users provide their own game files.
