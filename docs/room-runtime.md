# RoomRuntime

## Назначение

`RoomRuntime` — обычный Kotlin-класс, который управляет текущей комнатой поверх `NearbyTransport`.

Он не является Android Service, ViewModel или Repository. Его задача — слушать события транспорта, принимать protocol-решения и отдавать состояние выше через `StateFlow`.

## Файлы

- `app/src/main/java/com/yellastro/btration/domain/runtime/RoomRuntime.kt`
- `app/src/main/java/com/yellastro/btration/domain/runtime/RoomRuntimeState.kt`
- `app/src/main/java/com/yellastro/btration/repository/ProfileRepository.kt`
- `app/src/main/java/com/yellastro/btration/domain/util/IdGenerator.kt`

## Потоки данных

```text
NearbyTransport.events
  -> RoomRuntime
  -> state / availableRooms / messages
```

`RoomRuntime` получает `CoroutineScope` снаружи, например из application-level контейнера, и в `init` подписывается на `NearbyTransport.events`.

## StateFlow

- `state: StateFlow<RoomRuntimeState>` — текущая роль и сессия.
- `availableRooms: StateFlow<List<RoomInfo>>` — комнаты из discovery.
- `messages: StateFlow<List<ChatMessage>>` — чат текущей комнаты.

## Host MVP

Host:

- создает `RoomInfo`;
- останавливает discovery;
- запускает advertising;
- принимает `JOIN_REQUEST`;
- добавляет участника в `members`;
- отправляет `JOIN_ACCEPTED`;
- отправляет `MEMBER_LIST`;
- принимает `CHAT_MESSAGE`;
- добавляет сообщение себе;
- рассылает сообщение остальным участникам;
- при закрытии комнаты рассылает `ROOM_CLOSED`.

## Client MVP

Client:

- запускает discovery;
- хранит внутреннюю связь `RoomId -> endpointId`;
- по `joinRoom(roomId)` подключается к endpoint;
- после успешного connection отправляет `JOIN_REQUEST`;
- после `JOIN_ACCEPTED` переходит в `Client`;
- по `MEMBER_LIST` обновляет участников;
- свои сообщения добавляет локально и отправляет host-у;
- сообщения от host-а добавляет в чат;
- по `ROOM_CLOSED` сбрасывает сессию в `Idle`.

## PacketId / TTL

Для всех исходящих пакетов `RoomRuntime` генерирует:

- `packetId`;
- `ttl = 1`;
- `sentAtMillis`.

Входящие `packetId` складываются в `seenPacketIds`, чтобы уже сейчас отбрасывать дубликаты. Relay/mesh-логика пока не реализована.

## Ограничения текущего слоя

- Нет Android Service.
- Нет ViewModel.
- Нет Repository.
- Нет UI-связки.
- Нет настоящего PTT-голоса.
- Нет mesh relay, только задел через `packetId` и `ttl`.
