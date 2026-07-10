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
  -> state / availableRooms / messages / talkingPeerIds
```

`RoomRuntime` получает `CoroutineScope` снаружи, например из application-level контейнера, и в `init` подписывается на `NearbyTransport.events`.

## StateFlow

- `state: StateFlow<RoomRuntimeState>` — текущая роль и сессия.
- `availableRooms: StateFlow<List<RoomInfo>>` — комнаты из discovery.
- `messages: StateFlow<List<ChatMessage>>` — чат текущей комнаты.
- `talkingPeerIds: StateFlow<Set<PeerId>>` — участники, чей voice stream сейчас передается или локально доигрывается.

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
- при локальном PTT отправляет voice stream всем client-участникам;
- при входящем voice stream помечает участника говорящим и запускает воспроизведение.

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
- при локальном PTT отправляет voice stream host-у;
- при входящем voice stream от известного участника помечает его говорящим и запускает воспроизведение.

## PTT-индикация

- `RoomRuntime.startTalking()` добавляет локальный `PeerId` в `talkingPeerIds`, только если `VoiceRuntime` реально начал передачу.
- `RoomRuntime.stopTalking()` убирает локальный `PeerId` из `talkingPeerIds`.
- При `NearbyEvent.StreamReceived` runtime добавляет `peerId` отправителя в `talkingPeerIds`, передает stream в `VoiceRuntime.playIncoming(...)` и снимает индикатор через callback после окончания воспроизведения.
- `resetSession()`, `setError()`, disconnect и `MEMBER_LEFT` очищают соответствующие talking-состояния.

## PacketId / TTL

Для всех исходящих пакетов `RoomRuntime` генерирует:

- `packetId`;
- `ttl = 1`;
- `sentAtMillis`.

Входящие `packetId` складываются в `seenPacketIds`, чтобы уже сейчас отбрасывать дубликаты. Relay/mesh-логика пока не реализована.

## Ограничения текущего слоя

- Нет mesh relay, только задел через `packetId` и `ttl`.
- Voice MVP пока использует Nearby STREAM и PCM16 без кодека/джиттер-буфера.
