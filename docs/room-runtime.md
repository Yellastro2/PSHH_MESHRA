# RoomRuntime

## Назначение

`RoomRuntime` — обычный Kotlin-класс, который управляет текущей комнатой поверх signaling-транспорта `NearbyTransport` и голосового `VoiceTransport`.

Он не является Android Service, ViewModel или Repository. Его задача — слушать события транспорта, принимать protocol-решения и отдавать состояние выше через `StateFlow`.

## Файлы

- `app/src/main/java/com/yellastro/btration/domain/runtime/RoomRuntime.kt`
- `app/src/main/java/com/yellastro/btration/domain/runtime/RoomRuntimeState.kt`
- `app/src/main/java/com/yellastro/btration/repository/ProfileRepository.kt`
- `app/src/main/java/com/yellastro/btration/domain/util/IdGenerator.kt`
- `app/src/main/java/com/yellastro/btration/voice/VoiceRuntime.kt`
- `app/src/main/java/com/yellastro/btration/voice/VoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/NearbyVoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/WifiDirectVoiceTransport.kt`

## Потоки данных

```text
NearbyTransport.events + VoiceTransport.events
  -> RoomRuntime
  -> state / availableRooms / messages / talkingPeerIds
```

`RoomRuntime` получает `CoroutineScope` снаружи, например из application-level контейнера, и в `init` подписывается на signaling-события `NearbyTransport.events` и media-события `VoiceTransport.events`.

## StateFlow

- `state: StateFlow<RoomRuntimeState>` — текущая роль и сессия.
- `availableRooms: StateFlow<List<RoomInfo>>` — комнаты из discovery.
- `messages: StateFlow<List<ChatMessage>>` — чат текущей комнаты.
- `talkingPeerIds: StateFlow<Set<PeerId>>` — участники, чьи voice frames сейчас передаются или локально доигрываются.

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
- при локальном PTT отправляет voice frames всем client-участникам через выбранный `VoiceTransport`;
- при входящих Opus voice frames помечает участника говорящим, запускает локальное декодирование/воспроизведение и ретранслирует сжатые frames другим client-участникам без decode/re-encode.

## Client MVP

Client:

- запускает discovery;
- получает из `NearbyRoomAdvertisement` временный advertised `RoomId`, потому что `endpointName` не несет полный `roomId`;
- хранит внутреннюю связь `RoomId -> endpointId`, где до входа `RoomId` может быть временным advertised-id;
- по `joinRoom(roomId)` подключается к endpoint;
- после успешного connection отправляет `JOIN_REQUEST` без реального `roomId`;
- после `JOIN_ACCEPTED` получает настоящий `RoomInfo`, берет `RoomInfo.host.peerId` для Wi-Fi Direct DNS-SD matching, заменяет временный `RoomId -> endpointId` на реальный `RoomId -> endpointId` и переходит в `Client`;
- по `MEMBER_LIST` обновляет участников;
- свои сообщения добавляет локально и отправляет host-у;
- сообщения от host-а добавляет в чат;
- по `ROOM_CLOSED` сбрасывает сессию в `Idle`.
- при локальном PTT отправляет voice frames host-у через выбранный `VoiceTransport`;
- при входящих Opus voice frames от host-а помечает исходного участника говорящим и запускает декодирование/воспроизведение.

## Reconnect

- `STATUS_ENDPOINT_UNKNOWN` считается recoverable stale endpoint: runtime удаляет комнату из `availableRooms`, запускает clean discovery и при повторном `EndpointFound` автоматически повторяет connection.
- Если client внезапно теряет host-а не через `ROOM_CLOSED`, runtime не сбрасывает комнату в `Idle`, а переходит в `Joining` по сохраненной advertised-комнате и пробует переподключиться.

## PTT-индикация

- `RoomRuntime.startTalking()` добавляет локальный `PeerId` в `talkingPeerIds`, только если `VoiceRuntime` реально начал передачу.
- `RoomRuntime.stopTalking()` убирает локальный `PeerId` из `talkingPeerIds`.
- При `VoiceTransportEvent.FrameReceived` runtime добавляет `originPeerId` отправителя в `talkingPeerIds`, передает Opus frame в `VoiceRuntime.playIncomingFrame(...)` и снимает индикатор через callback после final-frame/EOF.
- Для UDP media-plane есть fallback: каждый non-final voice frame продлевает таймер говорящего, а если final-frame потерялся, runtime гасит индикатор и закрывает входящую frame-сессию по таймауту тишины.
- `resetSession()`, `setError()`, disconnect и `MEMBER_LEFT` очищают соответствующие talking-состояния.

## PacketId / TTL

Для всех исходящих пакетов `RoomRuntime` генерирует:

- `packetId`;
- `ttl = 1`;
- `sentAtMillis`.

Входящие `packetId` складываются в `seenPacketIds`, чтобы уже сейчас отбрасывать дубликаты. Relay/mesh-логика пока не реализована.

## Ограничения текущего слоя

- Нет mesh relay, только задел через `packetId` и `ttl`.
- Voice MVP использует `VoiceTransport`; текущий выбранный режим в `AppContainer` — `WIFI_DIRECT_UDP`, при этом `NEARBY_BYTES` оставлен как альтернативная реализация.
