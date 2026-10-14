# skyTox

**Свободное личное общение без центрального хозяина.**

**Private person-to-person communication without a central owner.**

skyTox — мой вариант того, каким должен быть настоящий мессенджер для свободных людей. Возможность разговаривать друг с другом не должна зависеть от настроения одного владельца, решения одной компании или небольшой группы людей, решивших, что они вправе устанавливать правила общения для всех остальных.

Это независимый Android-форк [aTox](https://github.com/evilcorpltd/aTox), работающий через децентрализованную сеть [Tox](https://tox.chat/). Я развиваю его прежде всего для себя, своих близких и друзей, а исходный код оставляю открытым для всех, кому близка та же идея.

skyTox is my answer to what genuine communication between free people should look like. The ability to talk to one another should not depend on the mood of a single owner, the policy of one corporation, or a small group that believes it may dictate the rules of communication to everyone else.

It is an independent Android fork of [aTox](https://github.com/evilcorpltd/aTox), built on the decentralized [Tox](https://tox.chat/) network. I develop it primarily for myself, my family, and my friends, while keeping the source code open to anyone who shares the same idea.

## Что такое skyTox

В skyTox нет центрального сервера, через который автор пропускает все чаты. Пользователи связываются через Tox, а сообщения, файлы и звонки передаются между участниками сети. Для первого подключения используются bootstrap-ноды, расположенные в разных странах. Любой желающий может поднять собственную Tox bootstrap-ноду и добавить ее в экосистему.

У проекта нет центральной кнопки «Выключить». Автор может прекратить выпуск новых версий или отключить отдельный вспомогательный сервис, но он не владеет сетью Tox и не может одним решением остановить ее. Если когда-нибудь я перестану заниматься skyTox, любой человек сможет взять исходный код, собрать приложение, продолжить разработку или создать собственный форк.

## Возможности

### Личное общение

- Личные P2P-чаты без обязательной регистрации по номеру телефона или электронной почте.
- Добавление контакта по Tox ID и QR-коду.
- Текстовые сообщения, действия, ответы, копирование, пересылка, мультивыделение и удаление.
- Markdown-оформление сообщений и отдельное отображение программного кода.
- Поиск по локальной истории переписки.
- Индикаторы непрочитанных и недоставленных сообщений.
- Согласованное время сообщений между совместимыми клиентами skyTox.
- «Мой skyTox» — личный чат для заметок, файлов и отложенных сообщений самому себе.

### Голос, видео и уведомления

- Голосовые сообщения в формате AAC/M4A с воспроизведением прямо в чате.
- Аудио- и видеозвонки через ToxAV.
- Полноэкранный входящий вызов, уведомления и управление звонком.
- Включение и отключение камеры, переключение между камерами и адаптация качества видео под мобильное устройство.
- Оптимизированный вывод входящего видео и отдельная диагностика проблем звонка.
- Фоновый режим и необязательное push-пробуждение, чтобы устройство могло вовремя принять сообщение или вызов.

### Файлы и документы

- Передача изображений, видео, документов, архивов и других файлов между контактами.
- Миниатюры изображений, видео и документов в истории чата.
- Очередь исходящих файлов для временно недоступного контакта.
- Продолжение прерванной передачи и защита от кратковременных разрывов соединения.
- Публичные папки `skyTox files` для полученных изображений, видео, записей, документов, игр, карт и диагностических файлов.
- Встроенный текстовый редактор и режим чтения с выбором размера шрифта и переходом к странице.
- Сохранение копии документа и отправка отредактированного файла контакту.
- Импорт и экспорт текстовой истории с проверкой принадлежности профилю.

### Профиль и удобство

- Собственный аватар: выбор изображения, центральная обрезка, уменьшение и удаление.
- Синхронизация аватара между клиентами и стабильные сгенерированные аватары, если изображение не выбрано.
- Защита локального профиля паролем и отдельные настройки блокировки.
- Темная тема и интерфейс, приспособленный к старым и новым версиям Android.
- Подписанные сборки для `armeabi-v7a`, `arm64-v8a` и универсальная APK.
- Встроенная проверка обновлений через GitHub Releases.

### Карта и геопозиция

- Однократная отправка местоположения и фоновая трансляция геопозиции выбранному контакту.
- OpenStreetMap как подробная онлайн-карта.
- Встроенная обзорная карта мира, доступная без предварительной загрузки.
- Отдельные слои городов, границ, дорог, железных дорог и треков.
- Ограниченный временный кэш тайлов OpenStreetMap с автоматической очисткой.
- Отображение участников, собственные метки и действия по долгому нажатию.
- Спидометр и навигационный режим с поворотом карты по направлению движения.
- Запись, автоматическое сохранение, импорт и отправка GPX-треков.
- Фильтрация случайных GPS-скачков и защита от повторного импорта одного трека.

### Игры

- Встроенный раздел для запуска пользовательских ROM-файлов `.nes` из папки `skyTox games`.
- Эмулятор на базе компонентов Multiness с экранными кнопками, Turbo A/B, быстрым сохранением и загрузкой.
- Настройка прозрачности или скрытие элементов управления и графические фильтры.
- Локальная игра вдвоем по Wi-Fi: один телефон запускает сервер, второй подключается по QR-коду или IP-адресу.
- ROM-файлы не входят в приложение и не распространяются вместе со skyTox.

### Локальный AI-помощник

- Gemma 4 E2B LiteRT работает локально на поддерживаемых 64-битных `arm64-v8a` устройствах.
- Модель загружается отдельно и после подготовки кэша выполняет запросы непосредственно на телефоне.
- Отдельные AI-диалоги без отправки содержимого в облачный AI-сервис.
- Пользователь сам выбирает до трех предыдущих ответов как контекст следующего вопроса.
- Поддерживаются вложения `JPG`, `PNG`, `PDF` и `TXT`; для PDF анализируется первая страница.
- Распознанный текст и программный код могут быть переданы TXT-файлом в «Мой skyTox».
- На 32-битных устройствах `armeabi-v7a` AI скрыт, но все обычные функции мессенджера продолжают работать.

Локальная небольшая модель может ошибаться, повторяться, пропускать текст на изображениях и уверенно сообщать неверные сведения. Ее ответы, код и распознавание документов необходимо проверять самостоятельно.

## Как развивался проект

- **0.8.1:** появились подписанные APK для разных архитектур Android.
- **0.8.2:** были расширены операции с сообщениями и файлами, работа с документами и инструкции импорта/экспорта.
- **0.8.3:** добавлены аватары и их синхронизация, поиск по чатам, Markdown, метки доставки и встроенное обновление.
- **0.8.4–0.8.5:** интерфейс получил собственную навигацию skyTox и более удобный главный экран.
- **0.8.6–0.8.12:** развивались push-пробуждение, хранение файлов, голосовые сообщения, блокировка профиля, bootstrap-ноды и совместимость со старыми устройствами.
- **0.8.13–0.8.20:** основной упор был сделан на надежность чатов, файловых передач, фоновой работы, входящих вызовов, аудио- и видеосвязи.
- **0.8.21–0.8.22:** появились сообщения с кодом, встроенный текстовый редактор и доработанные импорт, экспорт и работа с документами.
- **0.8.25:** добавлены «Мой skyTox», отложенные сообщения самому себе, новый нижний раздел навигации и локальный NES-мультиплеер.
- **0.8.26:** существенно переработаны аудио- и видеозвонки, рендеринг видео и адаптация качества.
- **0.8.28–0.8.30:** появились геопозиция, онлайн- и офлайн-карты, метки, навигационный режим, запись и обмен GPX-треками.
- **0.8.32:** добавлен локальный AI-помощник с поддержкой текста, изображений и простых документов.

Эта история остается в README только как краткая карта развития. Подробности конкретной сборки находятся в описании соответствующего [релиза](https://github.com/Mark84Hoppus/MarkandDiego.skyTox/releases).

## Почему нет групп и каналов

skyTox сознательно остается мессенджером для личного общения один на один. Публичные группы и каналы не планируются.

Причина не в технической невозможности сделать еще один экран со списком участников. Массовое публичное пространство требует постоянной модерации и юридической ответственности. Независимый автор физически не может следить за каждым созданным сообществом и каждым опубликованным сообщением. В открытых группах любой человек может распространять запрещенные материалы, использовать площадку для травли, пропаганды или других злонамеренных действий. Это неизбежно привлекло бы внимание регулирующих и правоохранительных органов к проекту и его автору, хотя автор не создавал этот контент и не способен контролировать всех пользователей.

Мне не нужна роль владельца публичной платформы, цензора или судьи над чужими разговорами. Поэтому граница проекта ясна: skyTox дает людям инструмент для личной связи, но не строит публичные каналы распространения информации.

## Приватность без красивых сказок

В skyTox нет рекламной аналитики, счетчиков поведения, профилирования аудитории и моей собственной системы регистрации пользователей. Приложение не отправляет мне содержимое ваших чатов, файлы, список контактов или историю сообщений. Я не пытаюсь узнать, кто установил skyTox, сколько времени им пользуется и какие функции открывает. Проект создается не ради статистики, рекламы или продажи пользовательских данных.

При этом важно различать отсутствие сбора данных автором и абсолютную сетевую анонимность:

- Tox является P2P-сетью; сетевые участники и ваш собеседник потенциально могут видеть технические сетевые данные, включая IP-адрес.
- Необязательное push-пробуждение использует инфраструктуру Firebase Cloud Messaging и передает служебный токен, но не текст переписки.
- GitHub может показывать общий счетчик загрузок релиза по собственным правилам. skyTox не использует этот счетчик для идентификации пользователей.
- История, настройки и профиль хранятся локально на устройстве; сохранность резервной копии и доступ к телефону остаются ответственностью владельца.

## Независимость и продолжение проекта

Исходный код открыт по лицензии GPL-3.0. Это означает, что skyTox не заканчивается на одном авторе и одном репозитории. Код можно изучать, собирать, проверять, изменять и развивать с соблюдением условий лицензии.

Если разработка здесь когда-нибудь остановится, проект не обязан исчезнуть вместе с ней. Можно поднять собственные bootstrap-ноды, собрать APK из исходников, заменить вспомогательные сервисы и продолжить развитие в новом форке. Именно в этом смысл свободного проекта: доверие строится не на обещании «я всегда буду рядом», а на том, что никто не получает исключительного права выключить общение для остальных.

---

## What skyTox is

skyTox has no central server through which its author routes every conversation. Users connect through Tox, while messages, files, and calls travel between network participants. Initial discovery relies on bootstrap nodes operated in different countries, and anyone can run another compatible bootstrap node.

There is no central “turn it off” button. The author may stop publishing updates or discontinue an optional supporting service, but does not own the Tox network and cannot shut it down by a single decision. If I ever stop developing skyTox, anyone can take the source code, build the app, continue its development, or create a new fork.

## Features

### Personal communication

- One-to-one P2P chats without mandatory phone-number or email registration.
- Contacts added using a Tox ID or QR code.
- Text messages, actions, replies, copying, forwarding, multi-selection, and deletion.
- Markdown rendering and dedicated formatting for source code.
- Search through locally stored chat history.
- Unread and undelivered message indicators.
- Compatible skyTox clients exchange message-time metadata.
- “My skyTox”, a private self-chat for notes, files, and scheduled messages.

### Voice, video, and notifications

- AAC/M4A voice messages played directly inside the chat.
- ToxAV audio and video calls.
- Full-screen incoming-call UI, notifications, and call controls.
- Camera enable/disable, camera switching, and mobile-oriented video quality adaptation.
- Optimized incoming video rendering and dedicated call diagnostics.
- Background operation and optional push wake-up for timely messages and calls.

### Files and documents

- Images, videos, documents, archives, and other file types.
- Thumbnails for images, videos, and documents in chat history.
- Outgoing queue for temporarily offline contacts.
- Interrupted-transfer recovery and protection against brief connection drops.
- Public `skyTox files` folders for received media, documents, recordings, games, maps, and diagnostics.
- Built-in text editor and reading mode with font sizing and page navigation.
- Save a copy of an edited document and send it to a contact.
- Text-history import and export with profile ownership checks.

### Profile and usability

- Custom profile avatars with center cropping, scaling, synchronization, and removal.
- Stable generated avatars when no custom image is selected.
- Local profile password and lock settings.
- Dark theme and compatibility work for both older and newer Android devices.
- Signed `armeabi-v7a`, `arm64-v8a`, and universal APK builds.
- Built-in update checks through GitHub Releases.

### Maps and location

- One-time location messages and background live-location sharing with a selected contact.
- Detailed OpenStreetMap view.
- Built-in overview world map available without a separate map download.
- Optional city, border, road, railway, and track layers.
- Size-limited temporary OpenStreetMap tile cache with automatic cleanup.
- Participant markers, custom markers, and long-press actions.
- Speedometer and navigation-style map rotation.
- GPX recording, automatic saving, importing, and sharing.
- GPS spike filtering and duplicate-import protection.

### Games

- A section for user-provided `.nes` ROM files stored in `skyTox games`.
- Multiness-based emulator controls, Turbo A/B, quick save/load, and visual filters.
- Local two-player Wi-Fi mode with QR-code or manual IP connection.
- ROM files are not included or distributed with skyTox.

### Local AI assistant

- Gemma 4 E2B LiteRT runs locally on supported 64-bit `arm64-v8a` devices.
- The model is downloaded separately and runs on the phone after its local cache is prepared.
- Separate AI dialogs without sending their content to a cloud AI service.
- The user selects up to three previous AI replies as context for the next request.
- `JPG`, `PNG`, `PDF`, and `TXT` attachments; the first page of a PDF is used for visual analysis.
- Recognized text and generated code can be delivered as TXT files to “My skyTox”.
- Local AI is hidden on 32-bit `armeabi-v7a` devices while normal messenger features remain available.

A small local model can make mistakes, repeat itself, miss text in images, or confidently provide incorrect information. Its facts, code, and document recognition must be independently verified.

## Project evolution

- **0.8.1:** signed APKs for multiple Android architectures.
- **0.8.2:** expanded message/file actions, document handling, and import/export guidance.
- **0.8.3:** avatar synchronization, chat search, Markdown, delivery markers, and in-app updates.
- **0.8.4–0.8.5:** dedicated skyTox navigation and a cleaner main screen.
- **0.8.6–0.8.12:** push wake-up, storage, voice messages, profile locking, bootstrap updates, and older-device compatibility.
- **0.8.13–0.8.20:** reliability work for chats, file transfers, background operation, incoming calls, audio, and video.
- **0.8.21–0.8.22:** code messages, built-in text editing, and improved document import/export.
- **0.8.25:** “My skyTox”, scheduled self-messages, bottom navigation, and local NES multiplayer.
- **0.8.26:** major audio/video call and rendering improvements.
- **0.8.28–0.8.30:** location sharing, online/offline maps, markers, navigation mode, and GPX tracks.
- **0.8.32:** local AI assistant with text, image, and basic document support.

This timeline is only a compact map of the project. Build-specific details are available on the corresponding [GitHub Releases](https://github.com/Mark84Hoppus/MarkandDiego.skyTox/releases) pages.

## Why there are no groups or channels

skyTox deliberately remains a person-to-person messenger. Public groups and broadcast channels are not planned.

The reason is not the technical difficulty of adding another participant list. Public mass communication requires continuous moderation and legal responsibility. A single independent developer cannot monitor every community and every published message. Anyone could misuse an open group to spread prohibited material, harass others, conduct propaganda, or pursue other harmful purposes. That would inevitably bring regulatory and law-enforcement attention to the project and its author, despite the author neither creating that content nor being able to control every user.

I do not want to become the owner of a public platform, a censor, or a judge of private conversations. The boundary is therefore intentional: skyTox provides a tool for personal communication, not a public broadcasting platform.

## Privacy without fairy tales

skyTox includes no advertising analytics, behavioral counters, audience profiling, or author-operated user registration database. The application does not send me your chat contents, files, contact list, or message history. I do not try to learn who installed skyTox, how long they use it, or which screens they open. This project is not built for statistics, advertising, or selling user data.

The absence of author-operated data collection should not be confused with absolute network anonymity:

- Tox is a P2P network; network participants and your peer may potentially observe technical network information, including an IP address.
- Optional push wake-up uses Firebase Cloud Messaging and exchanges a service token, but not the message body.
- GitHub may display aggregate release-download counts under its own policies. skyTox does not use them to identify users.
- History, settings, and profile data are stored locally; protecting the device and its backups remains the owner's responsibility.

## Independence and continuity

The source code is available under GPL-3.0. It can be inspected, built, audited, modified, and continued under the terms of that license.

If development in this repository ever stops, the project does not have to disappear with it. Anyone may operate compatible bootstrap nodes, build APKs from source, replace optional supporting services, and continue development in another fork. That is the point of a free project: trust should not depend on a promise that one person will always remain available, but on the fact that no single person receives the exclusive power to switch communication off for everyone else.

## Downloads and source code

Signed APK builds are published on the [Releases page](https://github.com/Mark84Hoppus/MarkandDiego.skyTox/releases):

- `arm64-v8a` — recommended for 64-bit Android devices and required for the local AI assistant.
- `armeabi-v7a` — for 32-bit Android devices; local AI is intentionally hidden.
- `universal` — convenient for manual installation and in-app updates.

The local AI model is distributed separately and remains subject to its own license and terms. skyTox is not affiliated with or endorsed by Google.

The NES module uses components from [Multiness](https://github.com/kakashidinho/Multiness_public). No commercial game ROMs are included.

License: [GPL-3.0](LICENSE)
