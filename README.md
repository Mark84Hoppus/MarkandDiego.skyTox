# skyTox

P2P-мессенджер на базе Tox: текстовые и голосовые сообщения, отправка файлов, аудиозвонки, локальный импорт/экспорт данных, карта геопозиции и локальный AI-помощник на поддерживаемых устройствах.

P2P messenger based on Tox: text and voice messaging, file sharing, audio calls, local data import/export, location map, and a local AI assistant on supported devices.

## Что добавлено в версии 0.8.32

- Локальный AI-помощник Gemma 4 E2B LiteRT для 64-битных устройств `arm64-v8a`.
- Отдельные AI-диалоги внутри skyTox.
- Возможность выбрать до 3 прошлых ответов AI как контекст для следующего вопроса.
- Поддержка вложений `JPG`, `PNG`, `PDF` и `TXT` в чате с AI.
- Для PDF используется первая страница, преобразованная в изображение.
- Распознавание текста на изображениях и PDF работает как примерная помощь, а не как точный OCR-сканер.
- Ответы с распознанным текстом и кодом могут отправляться `txt`-файлом в «Мой skyTox».
- Добавлена подробная инструкция внутри AI-раздела.
- На 32-битных `arm v7` устройствах AI-кнопка скрыта: обычный мессенджер продолжает работать, но локальная нейронка не предлагается.
- Обновление приложения доступно через кнопку обновления в настройках skyTox.

### Важно про AI

Локальная модель работает прямо на телефоне и не является облачной нейросетью. Она может ошибаться, повторяться, пропускать текст на изображениях или уверенно писать неверные сведения. Важные факты, код, распознанный текст и советы нужно проверять самостоятельно.

Чтобы AI работал быстрее и стабильнее, skyTox использует локальный кэш модели. При первом запуске AI-раздела кэш может подготавливаться несколько минут. Если кэш поврежден из-за принудительного закрытия приложения или отключения питания, приложение пересоздаст его автоматически.

## Version 0.8.32 changes

- Local Gemma 4 E2B LiteRT AI assistant for 64-bit `arm64-v8a` devices.
- Separate AI dialogs inside skyTox.
- Select up to 3 previous AI replies as context for the next question.
- `JPG`, `PNG`, `PDF`, and `TXT` attachments in the AI chat.
- PDF support uses the first page rendered as an image.
- Image/PDF text recognition is approximate assistance, not a precise OCR scanner.
- Recognized text and code answers can be sent as `txt` files to “My skyTox”.
- Detailed in-app instruction added to the AI section.
- On 32-bit `arm v7` devices the AI button is hidden: the normal messenger continues to work, but local AI is not offered.
- App updates are available through the update button in skyTox settings.

### Important AI Note

The local model runs directly on the phone and is not a cloud AI service. It can make mistakes, repeat itself, miss text in images, or state wrong information confidently. Important facts, code, recognized text, and advice should be checked independently.

For better speed and stability, skyTox uses a local model cache. The first AI launch can take a few minutes while the cache is prepared. If the cache is damaged after a forced app close or power loss, the app recreates it automatically.

## Downloads

Signed APK files are published in GitHub Releases:

- `arm64-v8a`: recommended for 64-bit Android devices with local AI support.
- `armeabi-v7a`: for 32-bit Android devices; local AI is hidden on these devices.
- `universal`: convenient build for manual installation and in-app updates.

## Third-Party Components

skyTox is based on the Tox ecosystem and includes third-party open-source components. The local AI model is provided separately and remains subject to its own license and terms. skyTox is not affiliated with or endorsed by Google.
