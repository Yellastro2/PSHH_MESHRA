# RoomRuntime

## Назначение

`RoomRuntime` — обычный Kotlin-класс, который управляет текущей комнатой поверх `RoomTransport` для Nearby Star, `MeshTransport` для MESHRA и переключаемого голосового `VoiceTransport`.

Он не является Android Service, ViewModel или Repository. Его задача — слушать события транспорта, принимать protocol-решения и отдавать состояние выше через `StateFlow`.
Статус голосового media-plane также идет через `RoomRuntimeState`, чтобы UI видел не только одноразовый snackbar, но и устойчивое состояние `Connecting` / `Ready` / `Unavailable`.
Переключаемый voice transport создает конкретный media-plane delegate лениво, только после выбора режима комнаты. Это важно для Nearby-комнат: Wi-Fi Direct не должен регистрировать callbacks, чистить группы или трогать системный P2P-слой, если host выбрал `NEARBY_BYTES`.

## Файлы

- `app/src/main/java/com/yellastro/btration/domain/runtime/RoomRuntime.kt`
- `app/src/main/java/com/yellastro/btration/domain/runtime/RoomTransport.kt`
- `app/src/main/java/com/yellastro/btration/domain/mesh/MeshTransport.kt`
- `app/src/main/java/com/yellastro/btration/domain/runtime/RoomRuntimeState.kt`
- `app/src/main/java/com/yellastro/btration/domain/transport/NeighborTransport.kt`
- `app/src/main/java/com/yellastro/btration/domain/transport/PeerLinkResolver.kt`
- `app/src/main/java/com/yellastro/btration/repository/ProfileRepository.kt`
- `app/src/main/java/com/yellastro/btration/domain/util/IdGenerator.kt`
- `app/src/main/java/com/yellastro/btration/voice/VoiceRuntime.kt`
- `app/src/main/java/com/yellastro/btration/voice/VoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/NearbyVoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/WifiDirectVoiceTransport.kt`

## Потоки данных

```text
RoomTransport.events + VoiceTransport.events
  -> RoomRuntime
  -> state / availableRooms / messages / talkingPeerIds
```

`RoomRuntime` получает `CoroutineScope` снаружи, например из application-level контейнера, и в `init` подписывается на события room signaling из `RoomTransport.events` и media-события `VoiceTransport.events`.

`RoomTransport` держит Nearby Star room-level протокол поверх `NeighborTransport`: декодирует/кодирует `WirePacket`, разбирает короткую визитку комнаты, ведет связь `PeerId <-> linkId` и прячет детали конкретного нижнего транспорта от `RoomRuntime`.

`MeshTransport` держит MESHRA room-level протокол поверх того же `NeighborTransport`: принимает gateway-рекламу, snapshot-ы и durable room events, дедупит их по `eventId` и flood-ит соседям.

## StateFlow

- `state: StateFlow<RoomRuntimeState>` — текущая роль, сессия и `DirectAudioStatus` для активной комнаты.
- `availableRooms: StateFlow<List<RoomInfo>>` — комнаты из discovery.
- `messages: StateFlow<List<ChatMessage>>` — чат текущей комнаты.
- `talkingPeerIds: StateFlow<Set<PeerId>>` — участники, чьи voice frames сейчас передаются или локально доигрываются.

## Host MVP

Host:

- создает `RoomInfo`;
- перед созданием комнаты получает тип комнаты из диалога создания, сохраняет его в prefs и кладет `roomTransportMode` в `RoomInfo`;
- перед созданием комнаты получает voice-настройку из диалога создания, сохраняет ее в prefs, выбирает `voiceTransportMode` и кладет его в `RoomInfo`;
- фиксирует выбранный room transport через `activateRoomTransportMode(...)`;
- для `Nearby Star` запускает обычный `RoomTransport.startAdvertising(room)`;
- для `MESHRA` публикует локальный `MEMBER_JOINED`, применяет mesh snapshot и запускает `MeshTransport.startAdvertising(snapshot, self)` как gateway;
- держит `RoomInfo.isDirectAudioReady=false`, пока первый peer не завершил UDP `HELLO/HELLO_ACK` handshake;
- останавливает discovery;
- запускает advertising;
- рассылает host `VOICE_TRANSPORT_INFO` для запуска handshake независимо от флага готовности;
- по `VoiceTransportEvent.DirectAudioReady` после handshake помечает комнату готовой к выбранному voice transport, показывает snackbar с актуальным режимом (`Direct`/`Nearby`) и рассылает обновленный `RoomInfo`;
- по `VoiceTransportEvent.TransportUnavailable` сохраняет `DirectAudioStatus.Unavailable`, но не закрывает комнату и не затирает уже готовый direct-аудиоканал;
- принимает `JOIN_REQUEST`;
- добавляет участника в `members`;
- отправляет `JOIN_ACCEPTED`;
- отправляет `MEMBER_LIST`;
- принимает `CHAT_MESSAGE`;
- добавляет сообщение себе;
- рассылает сообщение остальным участникам;
- при закрытии комнаты рассылает `ROOM_CLOSED`.
- при локальном PTT отправляет voice frames всем client-участникам с подтвержденным media-plane handshake; неготовый участник не блокирует остальных;
- при входящих Opus voice frames помечает участника говорящим, запускает локальное декодирование/воспроизведение и ретранслирует сжатые frames другим client-участникам без decode/re-encode.

## Client MVP

Client:

- запускает discovery;
- получает из `RoomTransport` временный advertised `RoomId`, потому что короткая транспортная визитка не несет полный `roomId`;
- хранит внутреннюю связь `RoomId -> endpointId`, где до входа `RoomId` может быть временным advertised-id;
- по `joinRoom(roomId)` подключается к endpoint;
- после успешного connection отправляет `JOIN_REQUEST` без реального `roomId`;
- для `Nearby Star` после `JOIN_ACCEPTED` получает настоящий `RoomInfo`, берет `RoomInfo.roomTransportMode` как режим room transport комнаты, берет `RoomInfo.voiceTransportMode`, переключает локальный voice transport на режим host-а, берет `RoomInfo.host.peerId` для Wi-Fi Direct DNS-SD matching, заменяет временный `RoomId -> endpointId` на реальный `RoomId -> endpointId` и переходит в `Client`;
- для `MESHRA` после подключения к gateway ждет `ROOM_SNAPSHOT`, заменяет временный `mesh_room_<token>` на настоящий `RoomInfo`, публикует свой `MEMBER_JOINED` и тоже начинает рекламировать комнату как gateway;
- MESHRA gateway-кандидаты в discovery имеют разные временные `RoomId`, но общий `discoveryGroupId`, чтобы ignore одного gateway не скрывал ту же комнату через другой gateway;
- на экране комнаты видит выбранный host-ом voice transport как read-only пункт меню настроек; host видит такой же read-only пункт, потому что активный transport комнаты после старта не переключается;
- получает host `VOICE_TRANSPORT_INFO` сразу после входа и запускает direct-audio handshake;
- по `MEMBER_LIST` обновляет участников;
- по `VoiceTransportEvent.TransportUnavailable` получает `DirectAudioStatus.Unavailable`, который ViewModel показывает как состояние выбранного голосового транспорта;
- свои сообщения добавляет локально и отправляет host-у;
- сообщения от host-а добавляет в чат;
- по `ROOM_CLOSED` сбрасывает сессию в `Idle`.
- при локальном PTT отправляет voice frames host-у через выбранный `VoiceTransport`;
- при входящих Opus voice frames от host-а помечает исходного участника говорящим и запускает декодирование/воспроизведение.

## Reconnect

- `STATUS_ENDPOINT_UNKNOWN` считается recoverable stale endpoint: runtime удаляет комнату из `availableRooms`, запускает clean discovery и при повторном `EndpointFound` автоматически повторяет connection.
- Для MESHRA такой же recovery идет через `MeshTransport.GatewayFound`: runtime повторяет connection только к тому же временному `RoomId` выбранного gateway, чтобы ignore одного gateway/host-а не превратился в автоматическое подключение через него.
- Если client внезапно теряет host-а не через `ROOM_CLOSED`, runtime не сбрасывает комнату в `Idle`, а переходит в `Joining` по сохраненной advertised-комнате и пробует переподключиться.
- Если нижний Nearby API сразу после runtime-permission grant возвращает transient `MISSING_PERMISSION` при discovery/advertising, `NearbyTransport` тихо делает короткий retry и через `RoomTransport` отдает ошибку в `RoomRuntime` только после исчерпания попыток.

## PTT-индикация

- `RoomRuntime.startTalking()` добавляет локальный `PeerId` в `talkingPeerIds`, только если `VoiceRuntime` реально начал передачу.
- Перед стартом записи `RoomRuntime.startTalking()` выбирает адресатов с готовым handshake. Запись блокируется только когда нет ни одного готового адресата; неготовые участники пропускают текущую передачу.
- Если готовых адресатов нет, `RoomRuntime.startTalking()` сохраняет `DirectAudioStatus.Unavailable("Прямой аудиоканал не установлен")`, и UI отключает повторный PTT до переподключения.
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
- MESHRA сейчас покрывает вступление, выход/разрыв и текстовый чат. Voice в MESHRA-комнатах намеренно помечается как недоступный до отдельной realtime-политики.
- Voice MVP использует `VoiceTransport`; дефолтный режим в настройках — `WIFI_DIRECT_UDP`, при этом реальный delegate создается только после применения режима конкретной комнаты.
