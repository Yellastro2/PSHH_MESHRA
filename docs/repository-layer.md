# Repository Layer

## Назначение

Repository-слой — удобная дверь для будущих ViewModel. Он не реализует протокол комнаты и не работает напрямую с Nearby.

## Файлы

- `app/src/main/java/com/yellastro/btration/repository/ProfileRepository.kt`
- `app/src/main/java/com/yellastro/btration/repository/RoomRepository.kt`
- `app/src/main/java/com/yellastro/btration/repository/RoomSettingsRepository.kt`
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
- `messages`.

Прокидывает команды в `RoomRuntime`, а перед активными сетевыми действиями запускает `RoomServiceController`.

## RoomSettingsRepository

Хранит выбор типа комнаты для следующих диалогов создания:

- `NEARBY_STAR` — прямой star-режим поверх Nearby;
- `MESHRA` — будущий mesh-режим, выбранный по умолчанию.

Значение сохраняется в общих app prefs и попадает в `RoomInfo.roomTransportMode` при создании комнаты.

## IgnoredNearbyRepository

Хранит локальный ignore-list прямых Nearby peer-ов/gateway-ев.

Для `Nearby Star` ignored peer совпадает с host-ом комнаты. Для `MESHRA` ignored peer — это конкретный gateway из рекламы, а не `knownHost` логической комнаты. Поэтому одна и та же mesh-комната может скрыть рекламу gateway A, но остаться доступной через gateway B.

## RoomServiceController

Это тонкая обвязка запуска и остановки `RoomConnectionService`.

Он не содержит бизнес-логику комнаты, не разбирает wire-пакеты и не хранит UI-состояние. Сервис показывает foreground notification о поиске или активной комнате, а также отдельные уведомления о новых чужих сообщениях, когда приложение не на экране.
