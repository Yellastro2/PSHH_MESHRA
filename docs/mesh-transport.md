# Mesh transport

## Назначение

`MeshTransport` — экспериментальный слой текстового и voice mesh-протокола поверх `NeighborTransport`.

Он подключен к основному `AppContainer` и включается `RoomRuntime`, когда комната создана или найдена с типом `MESHRA`.
Обычный `Nearby Star` продолжает работать через `RoomTransport`.

## Файлы

- `app/src/main/java/com/yellastro/btration/domain/mesh/MeshModels.kt`
- `app/src/main/java/com/yellastro/btration/domain/mesh/MeshCodec.kt`
- `app/src/main/java/com/yellastro/btration/voice/CompactVoicePacketCodec.kt`
- `app/src/main/java/com/yellastro/btration/voice/VoiceAudioProfile.kt`
- `app/src/main/java/com/yellastro/btration/domain/mesh/MeshRoomAdvertisement.kt`
- `app/src/main/java/com/yellastro/btration/domain/mesh/MeshTransport.kt`

## Модель

Комната в mesh MVP — это:

- текущий `MeshRoomSnapshot`;
- известный формальный host `knownHost`;
- список участников;
- список текстовых сообщений;
- список известных `MeshRoomEvent`.

События комнаты:

- `MEMBER_JOINED`;
- `MEMBER_LEFT`;
- `PEER_DISCONNECTED`;
- `CHAT_MESSAGE`.

События дедупятся по `MeshRoomEventId`. Отдельного `envelopeId` сейчас нет: JSON mesh envelope несет событие, snapshot или служебный `PEER_HELLO`. Голос этим envelope не оборачивается.

## Payload

`MeshCodec` добавляет сигнатуру `BTME1\n` перед JSON `MeshEnvelope`.

Это позволяет mesh-слою отличать управляющие сообщения от обычных room/voice bytes.

`PEER_HELLO` — ephemeral payload вне event-log комнаты. Он отправляется сразу после `LinkConnected`, содержит `previousHopPeerId=selfPeerId` и нужен только для live-мапы `NeighborLinkId -> PeerId`. Если hello не отправился, комната не падает: связь может определиться позже по любому входящему mesh envelope от этого же link-а.

MESHRA voice DATA — отдельный бинарный payload с magic `MV` и заголовком ровно `9` байт:

- byte `0..1`: magic `MV`;
- byte `2`: версия `1`, тип DATA, final-флаг и TTL `0..15`;
- byte `3..4`: `originNodeId` как UInt16;
- byte `5..6`: `pttSessionId` как UInt16;
- byte `7..8`: sequence внутри PTT как UInt16;
- остальные bytes: Opus packet без Base64, JSON, UUID, полного `PeerId`, `roomId`, времени и `previousHopPeerId`.

Этот layout реализует общий `CompactVoicePacketCodec`; Star использует тот же layout с magic `SV`, а правила TTL, дедупликации и маршрутизации остаются в соответствующем transport-е.

`originNodeId` вычисляется как младшие 16 бит CRC32 от полного `PeerId`. Полные идентификаторы уже находятся в snapshot комнаты; при изменении списка участников `MeshTransport` заранее перестраивает таблицу `UInt16 -> PeerId`, поэтому полный список и CRC не перебираются на каждом audio frame-е. При приеме короткий id обязан разрешиться однозначно. Если обнаружена коллизия или неизвестный id, frame отбрасывается с диагностическим логом, чтобы не приписать голос чужому участнику.

`pttSessionId` назначается один раз на нажатие PTT и меняется при следующем нажатии. Составной ключ `originNodeId + pttSessionId + sequence` заменяет отдельный UUID каждого frame-а и хранится в ограниченном dedup-cache на `4096` ключей. Voice flood стартует с TTL `8`; ошибки отдельных realtime frames логируются, но не роняют комнату. Отправки помечаются `isRealtime=true`, поэтому успешные Nearby-отправки логируются агрегированно, а не по строке на каждый audio frame.

`roomId` вынесен из каждого voice frame-а в состояние физического link-а. Link привязывается к комнате при отправке или приеме `ROOM_SNAPSHOT`/`ROOM_EVENT`; voice до такой привязки не принимается. Текущий компактный формат предполагает одну активную mesh-комнату на физический link.

`MeshRoomSnapshot` и каждое durable room event несут `voiceAudioProfile`. Создатель выбирает `10/20/40 мс`, а все вошедшие участники настраивают capture, Opus и playback по профилю из snapshot-а; старые snapshot-ы без поля означают `40 мс`.

## Advertising / discovery

`MeshRoomAdvertisement` кодируется в endpointName с префиксом `BTM4`.

Реклама означает не "я настоящий host", а "я gateway в mesh-комнату". В ней есть:

- короткий `roomToken` для группировки дублей одной комнаты от разных endpoint-ов;
- имя комнаты;
- короткий след и имя формального `knownHost`;
- короткий след и имя текущего `gateway`;
- количество участников из snapshot-а;
- время последнего обновления snapshot-а.

`MeshTransport.startAdvertising(snapshot, gateway)` публикует gateway-визитку через `NeighborTransport.startAdvertising(...)`. Повторные вызовы в рамках той же активной сессии пропускаются, потому что Nearby Connections не принимает второй `startAdvertising()` поверх уже запущенного advertising и возвращает `STATUS_ALREADY_ADVERTISING`.
`CandidateFound` нижнего транспорта превращается в `MeshTransportEvent.GatewayFound`, если endpointName распознан как `BTM4`.
Входящий connection request сейчас принимает общий room/neighbor lifecycle, а `MeshTransport` после `LinkConnected` отправляет новому соседу все известные snapshot-ы.
MESHRA advertising, connect и healing discovery явно выбирают `Strategy.P2P_CLUSTER`, чтобы gateway мог оставаться связанным со своим upstream-соседом и принимать нового downstream-соседа. `P2P_STAR` для такого сценария вел себя как физическая звезда и в тестах ронял C->B connect с `STATUS_ENDPOINT_IO_ERROR`. Общий lobby discovery чередует Star/Cluster, но после выбора MESHRA-комнаты фиксируется на Cluster; Star-комнаты отдельно используют физический `P2P_STAR`.

Временный `RoomId` mesh-рекламы включает и `roomToken`, и `gatewayShortId`. Это нужно для ignore-сценариев: если телефон C игнорит gateway A, реклама A скрывается, но реклама той же комнаты через gateway B остается отдельным кандидатом для входа.
Для UI-дедупликации после фильтрации ignored gateway-ев `RoomInfo.discoveryGroupId` хранит только `roomToken`. Проверка соответствия временного и реального `RoomId` матчится по форме `mesh_room_<roomToken>_gw_...`, чтобы разные gateway одной комнаты считались одной логической комнатой, но похожие token-ы не склеивались случайно.
Для подписи карточки комнаты `RoomInfo.discoveryEndpointId` хранит текущий Nearby `endpointId`, а `RoomInfo.memberCount` для MESHRA берется из `MeshRoomAdvertisement.memberCount`.

На нижнем room/transport уровне найденный endpoint считается прямым gateway (`RoomInfo.gateway ?: RoomInfo.host`). Это важно для ignore-list-а: он запрещает физический соседский link, но не банит логическую комнату и не выкидывает события того же known host-а, пришедшие через другой gateway.

## Runtime-интеграция

`RoomRuntime` использует MESHRA так:

1. Host создает `RoomInfo(roomTransportMode=MESHRA)`.
2. Runtime публикует локальный `MEMBER_JOINED`, получает локальный snapshot и запускает `MeshTransport.startAdvertising(...)`.
3. Гость в лобби видит `GatewayFound` как обычную комнату с временным `mesh_room_<token>_gw_<gateway>` `RoomId`.
4. При входе гость подключается к выбранному gateway и ждет `ROOM_SNAPSHOT`.
5. После snapshot-а runtime заменяет временную комнату настоящим `RoomInfo`, публикует свой `MEMBER_JOINED` и тоже начинает advertising как gateway.
6. Текстовые сообщения идут как `CHAT_MESSAGE` event и flood-ятся соседям с TTL/dedup.
7. Голос в MESHRA идет как отдельный компактный бинарный DATA-пакет: `VoiceRuntime` кодирует Opus, `RoomRuntime` публикует frame в `MeshTransport`, а `MeshTransport` дедупит/flood-ит его без JSON и сохранения в snapshot.

Если выбранный gateway успел протухнуть и Nearby вернул `STATUS_ENDPOINT_UNKNOWN`, `RoomRuntime` запускает clean discovery и повторяет connection при новом `GatewayFound` только для того же временного `mesh_room_<token>_gw_<gateway>` `RoomId`. Это не дает recovery перескочить на другой gateway той же комнаты, например на ignored host.

После создания или входа в MESHRA-комнату mesh discovery включается только как healing-механизм. Runtime не делит link-и на inbound/outbound: flooding использует все пригодные link-и, а healing добирает link-и той же комнаты до `min(участники - 1, 3)` при hard cap `64`. Link со статусом heartbeat `LOST` не считается пригодным и не учитывается как active для healing-счетчика; heartbeat при этом продолжает отправляться, чтобы можно было заметить восстановление. Если участников меньше четырех, узел старается соединиться со всеми известными gateway этой комнаты; если больше — держит минимум три active/pending link-а. Ошибки отдельных healing connection request-ов логируются, но не роняют активную комнату. Если сам Nearby discovery не стартовал, активная MESHRA-комната планирует повторный healing retry через 15 секунд.

Для UI-индикатора прямого соседства `MeshTransport` публикует `directPeerIds: StateFlow<Set<PeerId>>`. Outgoing link привязывается к `PeerId` сразу по `gatewayPeerId` из рекламы, incoming link — после `PEER_HELLO` или первого другого mesh envelope от соседа. UI не получает `endpointId`/`linkId`, только доменный `PeerId`.

Для MESHRA-комнат voice считается готовым, когда есть хотя бы один прямой mesh link. PTT стартует только при наличии прямого mesh-соседа; дальше voice frame flood-ится по активной mesh-связности.

## Flooding

Алгоритм для `ROOM_EVENT`:

1. Если payload не начинается с `BTME1\n`, `MeshTransport` его не трогает.
2. Если `eventId` уже видели, событие игнорируется и дальше не пересылается.
3. Если событие минимально валидно, оно сохраняется в локальный log.
4. Snapshot комнаты обновляется инкрементально.
5. Если `ttl` еще живой, событие пересылается всем активным соседям без heartbeat-статуса `LOST`, кроме link-а, с которого оно пришло.

`ROOM_SNAPSHOT` принимается локально и не flood-ится дальше. Он нужен для сценария, где новый peer входит через mesh gateway и должен быстро получить состояние комнаты.

`PEER_HELLO` принимается локально, не flood-ится и не меняет snapshot комнаты.

Бинарный voice DATA принимается локально, не меняет snapshot комнаты, дедупится по `originNodeId + pttSessionId + sequence`, пересылается пригодным соседям этой комнаты кроме предыдущего hop-а и отдается в `RoomRuntime` для playback через `VoiceRuntime`. Link со статусом `LOST` для voice/data flood-а пропускается, чтобы не складывать свежие пакеты в зависшую очередь нижнего Nearby.

Heartbeat ping/pong — realtime bytes payload размером 4 байта. Если при выходе из комнаты или закрытии endpoint-а Nearby возвращает `STATUS_ENDPOINT_UNKNOWN`/`SOCKET_CLOSED`, `MeshTransport` и `NearbyPayloadTransport` пишут короткий shutdown-log без stack trace.

## Ограничения

- Нет криптографических подписей событий.
- Голос идет через flooding без jitter buffer и без отдельной политики приоритетов маршрута.
- UInt16 sequence циклически переносится после `65535`; отдельный `pttSessionId` не дает смешать соседние нажатия PTT.
- UInt16 `originNodeId` допускает коллизии; сейчас они обнаруживаются по snapshot и блокируют неоднозначный голос, но отдельного согласования коротких id через hello еще нет.
- Нет политик доступа, rename, ban и других фич вне текущего текстового MVP.
