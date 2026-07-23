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
- `app/src/main/java/com/yellastro/btration/voice/VoiceAudioProfile.kt`
- `app/src/main/java/com/yellastro/btration/voice/CompactVoicePacketCodec.kt`
- `app/src/main/java/com/yellastro/btration/voice/VoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/NearbyVoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/WifiDirectVoiceTransport.kt`

## Потоки данных

```text
RoomTransport.events + MeshTransport.events + VoiceTransport.events
  -> RoomRuntime
  -> state / availableRooms / messages / talkingPeerIds / directMeshPeerIds / meshPeerConnectionStates
```

`RoomRuntime` получает `CoroutineScope` снаружи, например из application-level контейнера, и в `init` подписывается на события room signaling из `RoomTransport.events` и media-события `VoiceTransport.events`.

`RoomTransport` держит Nearby Star room-level протокол поверх `NeighborTransport`: декодирует/кодирует `WirePacket`, разбирает короткую визитку комнаты, ведет связь `PeerId <-> linkId` и прячет детали конкретного нижнего транспорта от `RoomRuntime`.
Так как `RoomTransport` и `MeshTransport` слушают один общий `NeighborTransport`, `AppContainer.shouldIgnoreMessage` обязан отфильтровывать не-room payload-ы до JSON decode: Star voice `SV`, `BTME1`, MESHRA voice `MV` и MESHRA heartbeat `H`.

Нижний `NearbyTransport` динамически выбирает физическую topology: общее лобби попеременно сканирует `P2P_STAR` и `P2P_CLUSTER`, реклама/подключение `NEARBY_STAR` фиксируется на Star, а MESHRA gateway/healing — на Cluster. При выборе карточки `RoomTransportMode`, уже полученный из рекламы, передается в connect; если карточка была найдена в прошлой фазе, transport сначала повторно обнаруживает endpoint в требуемой topology.

`MeshTransport` держит MESHRA room-level протокол поверх того же `NeighborTransport`: принимает gateway-рекламу, JSON snapshot-ы/durable room events, отдельные компактные бинарные voice frames и четырехбайтовые heartbeat `PING/PONG`, дедупит voice/events и flood-ит соседям.
Так как `NeighborTransport` общий с Nearby Star, MESHRA link активируется только после исходящего `connectToGateway(...)` или первого валидного mesh envelope на входящем link-е. Обычный Star `LinkConnected` не запускает mesh heartbeat, а heartbeat/voice payload на неактивном mesh link-е игнорируется.
Для live-UI он также держит `directPeerIds` — набор `PeerId`, с которыми есть прямой mesh link. Эта информация строится из gateway-рекламы и служебного `PEER_HELLO`, а не из `endpointId` в UI.
Для диагностики он дополнительно публикует `linkHealth: StateFlow<Map<NeighborLinkId, MeshLinkHealth>>`: Nearby lifecycle дает факт `LinkConnected/LinkDisconnected`, а app-level heartbeat считает `rttMillis`, `lossPercent`, `missedInRow` и статус `CONNECTED/SUSPECT/LOST`. После привязки link-а к `PeerId` это сворачивается в `meshPeerConnectionStates`; старый `directMeshPeerIds` теперь исключает `LOST`, поэтому UI connect-dot гаснет и при Nearby disconnect, и при heartbeat LOST. Mesh data/voice flood тоже исключает `LOST` link-и, но heartbeat продолжает ходить по ним для восстановления статуса.

## StateFlow

- `state: StateFlow<RoomRuntimeState>` — текущая роль, сессия и `DirectAudioStatus` для активной комнаты.
- `availableRooms: StateFlow<List<RoomInfo>>` — комнаты из discovery.
- `messages: StateFlow<List<ChatMessage>>` — чат текущей комнаты.
- `talkingPeerIds: StateFlow<Set<PeerId>>` — участники, чьи voice frames сейчас передаются или локально доигрываются.
- `directMeshPeerIds: StateFlow<Set<PeerId>>` — участники, с которыми есть прямой mesh link без `LOST` heartbeat-статуса; ViewModel использует это для connect-dot на карточке участника.
- `meshPeerConnectionStates: StateFlow<Map<PeerId, MeshPeerConnectionState>>` — ping/status прямых mesh-соседей; ViewModel выводит `rttMillis` в `item_user_ping`, но не показывает промежуточные `SUSPECT`-состояния отдельным UI-цветом.

## Host MVP

Host:

- создает `RoomInfo`;
- перед созданием комнаты получает тип комнаты из диалога создания, сохраняет его в prefs и кладет `roomTransportMode` в `RoomInfo`;
- перед созданием комнаты получает voice transport и длину Opus-фрейма `10/20/40 мс`, сохраняет длительность отдельно для `Nearby Star` и `MESHRA`, а `voiceTransportMode` и `voiceAudioProfile` кладет в `RoomInfo`;
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
- в `Nearby Star` при закрытии комнаты рассылает `ROOM_CLOSED`;
- в `MESHRA` при выходе/команде закрытия публикует только свой `MEMBER_LEFT`, сбрасывает локальную сессию и не удаляет комнату у остальных узлов.
- при локальном PTT отправляет voice frames всем client-участникам с подтвержденным media-plane handshake; неготовый участник не блокирует остальных;
- при входящих Opus voice frames помечает участника говорящим, запускает локальное декодирование/воспроизведение и ретранслирует сжатые frames другим client-участникам без decode/re-encode.

## Client MVP

Client:

- запускает discovery;
- получает из `RoomTransport` временный advertised `RoomId`, потому что короткая транспортная визитка не несет полный `roomId`;
- хранит внутреннюю связь `RoomId -> endpointId`, где до входа `RoomId` может быть временным advertised-id;
- кладет текущий discovery `endpointId` в `RoomInfo.discoveryEndpointId`, чтобы лобби могло показать, через какой физический endpoint/gateway будет вход;
- по `joinRoom(roomId)` подключается к endpoint;
- после успешного connection отправляет `JOIN_REQUEST` без реального `roomId`;
- для `Nearby Star` после `JOIN_ACCEPTED` получает настоящий `RoomInfo`, берет `RoomInfo.roomTransportMode`, `RoomInfo.voiceTransportMode` и `RoomInfo.voiceAudioProfile`, настраивает локальные transport/Opus/capture/playback под meta host-а, заменяет временный `RoomId -> endpointId` на реальный и переходит в `Client`;
- для `MESHRA` после подключения к gateway ждет `ROOM_SNAPSHOT`, читает из него тот же `voiceAudioProfile`, заменяет временный `mesh_room_<token>` на настоящий `RoomInfo`, публикует свой `MEMBER_JOINED` и тоже начинает рекламировать комнату как gateway;
- MESHRA gateway-кандидаты в discovery имеют разные временные `RoomId`, но общий `discoveryGroupId`, чтобы ignore одного gateway не скрывал ту же комнату через другой gateway;
- на экране комнаты MESHRA-создатель выходит обычной командой выхода, а не через диалог закрытия комнаты для всех;
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
- `stopSearch()` реально останавливает discovery только в состоянии `Searching`. Если UI вызывает `stopSearch()` уже после клика входа и runtime перешел в `Joining`, discovery/connect lifecycle не трогается, чтобы не убить pending повторное обнаружение endpoint-а и не оставить клиента висеть в `Joining`.
- Для активной MESHRA-комнаты `stopSearch()` не выключает mesh discovery вслепую: runtime держит healing discovery только пока пригодные active/pending link-и меньше `min(участники - 1, 3)` и не выше `64`. Если связность уже достаточна, например 2 участника и 1 active link без `LOST`, discovery останавливается.
- Ошибка discovery во время активной MESHRA-комнаты считается ошибкой healing-а и не переводит комнату в `Error`; активные link-и и уже полученный snapshot должны жить дальше. После такой ошибки runtime планирует повторный запуск healing discovery через 15 секунд, а `NearbyTransport` сбрасывает zombie discovery-фазу, чтобы следующий `startDiscovery(CLUSTER_ONLY)` реально дошел до Google Nearby.
- Если client внезапно теряет host-а не через `ROOM_CLOSED`, runtime не сбрасывает комнату в `Idle`, а переходит в `Joining` по сохраненной advertised-комнате и пробует переподключиться.
- Если нижний Nearby API сразу после runtime-permission grant возвращает transient `MISSING_PERMISSION` при discovery/advertising, `NearbyTransport` тихо делает короткий retry и через `RoomTransport` отдает ошибку в `RoomRuntime` только после исчерпания попыток. Для активной MESHRA-комнаты такая ошибка превращается в delayed healing retry, а для обычного поиска/входа остается пользовательской ошибкой.

## PTT-индикация

- `RoomRuntime.startTalking()` добавляет локальный `PeerId` в `talkingPeerIds`, только если `VoiceRuntime` реально начал передачу.
- Перед стартом записи `RoomRuntime.startTalking()` выбирает адресатов с готовым handshake. Запись блокируется только когда нет ни одного готового адресата; неготовые участники пропускают текущую передачу.
- Если готовых адресатов нет, `RoomRuntime.startTalking()` сохраняет `DirectAudioStatus.Unavailable("Прямой аудиоканал не установлен")`, и UI отключает повторный PTT до переподключения.
- `RoomRuntime.stopTalking()` убирает локальный `PeerId` из `talkingPeerIds`.
- При `VoiceTransportEvent.FrameReceived` runtime добавляет `originPeerId` отправителя в `talkingPeerIds`, передает Opus frame в `VoiceRuntime.playIncomingFrame(...)` и снимает индикатор через callback после final-frame/EOF.
- `VoiceRuntime` собирает из `AudioRecord` ровно один PCM-фрейм выбранной длительности, кодирует его одним Opus packet и держит playback-очередь примерно на `80 мс` независимо от выбора `10/20/40 мс`.
- Star и MESHRA используют общий `CompactVoicePacketCodec`: 9 байт `magic/control/originNodeId/pttSessionId/sequence`, после которых идет Opus. Magic `SV` принадлежит Star, `MV` — MESHRA; UInt16 sequence циклически переносится внутри одной долгой PTT-сессии.
- Для UDP media-plane есть fallback: каждый non-final voice frame продлевает таймер говорящего, а если final-frame потерялся, runtime гасит индикатор и закрывает входящую frame-сессию по таймауту тишины.
- Для MESHRA media-plane `VoiceRuntime` кодирует Opus frames через callback, а `RoomRuntime` публикует их в `MeshTransport.publishVoiceFrame(...)`. `MeshTransport` упаковывает их в бинарный заголовок `MV` размером 9 байт; входящие `MeshTransportEvent.VoiceFrameReceived` проигрываются через `VoiceRuntime` без старого host relay, потому что relay уже сделан mesh flooding-ом.
- MESHRA link health измеряется отдельными heartbeat-пакетами размером 4 байта: `magic=0x48`, `kind/flags`, `sequence UInt16`. `PING` отправляется примерно раз в секунду по каждому активному прямому link-у, `PONG` возвращает тот же sequence, а RTT считается локально по таблице отправленных sequence. После 10 секунд без живого heartbeat link получает `LOST`; peer пропадает из `directMeshPeerIds`, link исключается из room/event/voice отправки и перестает считаться достаточной связностью для healing. Принудительный disconnect/reconnect пока не запускается: heartbeat продолжает ходить по старому link-у, а runtime включает discovery, чтобы найти замену.
- Ошибка отправки heartbeat в уже закрытый Nearby endpoint (`STATUS_ENDPOINT_UNKNOWN`/`SOCKET_CLOSED`) считается штатным shutdown-noise: она логируется короткой строкой без stack trace и не засоряет выход из комнаты.
- `resetSession()`, `setError()`, disconnect и `MEMBER_LEFT` очищают соответствующие talking-состояния.

## PacketId / TTL

Для всех исходящих пакетов `RoomRuntime` генерирует:

- `packetId`;
- `ttl = 1`;
- `sentAtMillis`.

Входящие `packetId` складываются в `seenPacketIds`, чтобы уже сейчас отбрасывать дубликаты. Relay/mesh-логика пока не реализована.

## Ограничения текущего слоя

- В MESHRA есть flooding/relay для text events и ephemeral voice frames; отдельной политики маршрутизации и приоритетов пока нет.
- MESHRA сейчас покрывает вступление, выход/разрыв, текстовый чат и Opus voice frames через ephemeral mesh payload.
- MESHRA уже измеряет здоровье прямых link-ов через Nearby lifecycle и собственные heartbeat; `LOST` гасит UI connect-dot через `directMeshPeerIds`, запрещает отправку room/event/voice payload-ов в этот link и включает healing discovery, но пока не делает принудительный disconnect/reconnect.
- Nearby Star voice использует `VoiceTransport`; MESHRA voice идет через `MeshTransport` как ephemeral payload. Дефолтный режим в настройках — `WIFI_DIRECT_UDP`, при этом реальный delegate создается только после применения режима конкретной комнаты.
- Старые room meta без `voiceAudioProfile` декодируются как `40 мс`, но новый compact voice wire-format не совместим со старыми сборками, ожидающими `BTVO`.
