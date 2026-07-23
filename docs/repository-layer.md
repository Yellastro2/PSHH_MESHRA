# Repository Layer

## Назначение

Repository-слой — удобная дверь для будущих ViewModel. Он не реализует протокол комнаты и не работает напрямую с Nearby.

## Файлы

- `app/src/main/java/com/yellastro/btration/repository/ProfileRepository.kt`
- `app/src/main/java/com/yellastro/btration/repository/RoomRepository.kt`
- `app/src/main/java/com/yellastro/btration/repository/RoomSettingsRepository.kt`
- `app/src/main/java/com/yellastro/btration/repository/VoiceSettingsRepository.kt`
- `app/src/main/java/com/yellastro/btration/repository/IgnoredNearbyRepository.kt`
- `app/src/main/java/com/yellastro/btration/service/RoomServiceController.kt`

## ProfileRepository

Хранит локальный профиль:

- `peerId` — UUID, создается один раз;
- `peerName` — имя, видимое другим участникам.

Методы:

- `getOrCreatePeerId()`;
- `getPeerName()`;
- `setPeerName(name)`;
- `getSelfPeer()`.

## RoomRepository

Прокидывает состояние из `RoomRuntime`:

- `runtimeState`;
- `availableRooms`;
- `messages`;
- `isTalking` — подтвержденная локальная передача микрофона;
- `isMicrophoneLocked` — закрепление PTT без удержания кнопки.

Прокидывает команды в `RoomRuntime`, а перед активными сетевыми действиями запускает `RoomServiceController`.

Состояния `isTalking` и `isMicrophoneLocked` общие для `RoomViewModel` и `RoomConnectionService`. Поэтому notification action `Откл. микро` останавливает runtime-запись и одновременно обновляет экран комнаты. Старт и остановка микрофона сериализованы, чтобы короткое нажатие или быстрое отключение не оставляло поздно запустившийся `AudioRecord` активным.

## RoomSettingsRepository

Хранит выбор типа комнаты для следующих диалогов создания:

- `NEARBY_STAR` — прямой star-режим поверх Nearby;
- `MESHRA` — будущий mesh-режим, выбранный по умолчанию.

Значение сохраняется в общих app prefs и попадает в `RoomInfo.roomTransportMode` при создании комнаты.

## VoiceSettingsRepository

Хранит выбранный voice transport и две независимые настройки длины Opus-фрейма: одну для будущих `Nearby Star` комнат, вторую для `MESHRA`. Если значение еще не сохранено, используются defaults `10 мс` для Star и `20 мс` для MESHRA. При входе гость не использует локальный preference: `RoomRuntime` применяет `RoomInfo.voiceAudioProfile`, выбранный создателем комнаты.

## IgnoredNearbyRepository

Хранит локальный ignore-list прямых Nearby peer-ов/gateway-ев.

Для `Nearby Star` ignored peer совпадает с host-ом комнаты. Для `MESHRA` ignored peer — это конкретный gateway из рекламы, а не `knownHost` логической комнаты. Поэтому одна и та же mesh-комната может скрыть рекламу gateway A, но остаться доступной через gateway B.

## RoomServiceController

Это тонкая обвязка запуска и остановки `RoomConnectionService`.

Он не содержит бизнес-логику комнаты, не разбирает wire-пакеты и не хранит UI-состояние. Сервис показывает foreground notification о поиске или активной комнате, отражает закрепленный микрофон и дает отключить его без выхода из комнаты, а также показывает отдельные уведомления о новых чужих сообщениях, когда приложение не на экране. Сервис запускается как non-sticky: после убийства процесса Android не должен поднимать пустую service-сессию без сохраненного `RoomRuntime`.

`RoomRepository.stopSearch()` может вызываться UI при уходе с лобби уже после `joinRoom()`. Поэтому `RoomRuntime.stopSearch()` обязан быть no-op для `Joining`/активной комнаты: иначе он гасит discovery, который нижний Nearby transport использует для pending повторного обнаружения endpoint-а перед connect.
