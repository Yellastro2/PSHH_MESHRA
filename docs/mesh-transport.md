# Mesh transport

## Назначение

`MeshTransport` — экспериментальный слой текстового mesh-протокола поверх `NeighborTransport`.

Он подключен к основному `AppContainer` и включается `RoomRuntime`, когда комната создана или найдена с типом `MESHRA`.
Обычный `Nearby Star` продолжает работать через `RoomTransport`.

## Файлы

- `app/src/main/java/com/yellastro/btration/domain/mesh/MeshModels.kt`
- `app/src/main/java/com/yellastro/btration/domain/mesh/MeshCodec.kt`
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

События дедупятся по `MeshRoomEventId`. Отдельного `envelopeId` сейчас нет: mesh envelope несет событие, snapshot или служебный `PEER_HELLO` через соседский transport.

## Payload

`MeshCodec` добавляет сигнатуру `BTME1\n` перед JSON `MeshEnvelope`.

Это позволяет mesh-слою игнорировать обычные room/voice bytes, а будущим соседним слоям — аналогично игнорировать mesh payload.

`PEER_HELLO` — ephemeral payload вне event-log комнаты. Он отправляется сразу после `LinkConnected`, содержит `previousHopPeerId=selfPeerId` и нужен только для live-мапы `NeighborLinkId -> PeerId`. Если hello не отправился, комната не падает: связь может определиться позже по любому входящему mesh envelope от этого же link-а.

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
Нижний Nearby transport для mesh-тестов запускается с `Strategy.P2P_CLUSTER`, чтобы gateway мог оставаться связанным со своим upstream-соседом и принимать нового downstream-соседа. `P2P_STAR` для такого сценария вел себя как физическая звезда и в тестах ронял C->B connect с `STATUS_ENDPOINT_IO_ERROR`.

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

Если выбранный gateway успел протухнуть и Nearby вернул `STATUS_ENDPOINT_UNKNOWN`, `RoomRuntime` запускает clean discovery и повторяет connection при новом `GatewayFound` только для того же временного `mesh_room_<token>_gw_<gateway>` `RoomId`. Это не дает recovery перескочить на другой gateway той же комнаты, например на ignored host.

После создания или входа в MESHRA-комнату mesh discovery остается активным как простой healing-механизм. Runtime не делит link-и на inbound/outbound: flooding использует все активные link-и, а healing добирает любые link-и той же комнаты до `min(участники - 1, 3)` при hard cap `64`. Если участников меньше четырех, узел старается соединиться со всеми известными gateway этой комнаты; если больше — держит минимум три активных/pending link-а. Ошибки отдельных healing connection request-ов логируются, но не роняют активную комнату.

Для UI-индикатора прямого соседства `MeshTransport` публикует `directPeerIds: StateFlow<Set<PeerId>>`. Outgoing link привязывается к `PeerId` сразу по `gatewayPeerId` из рекламы, incoming link — после `PEER_HELLO` или первого другого mesh envelope от соседа. UI не получает `endpointId`/`linkId`, только доменный `PeerId`.

Для MESHRA-комнат voice сейчас явно помечается как недоступный: текстовый mesh уже включен, но отдельная realtime voice-политика еще не реализована.

## Flooding

Алгоритм для `ROOM_EVENT`:

1. Если payload не начинается с `BTME1\n`, `MeshTransport` его не трогает.
2. Если `eventId` уже видели, событие игнорируется и дальше не пересылается.
3. Если событие минимально валидно, оно сохраняется в локальный log.
4. Snapshot комнаты обновляется инкрементально.
5. Если `ttl` еще живой, событие пересылается всем активным соседям, кроме link-а, с которого оно пришло.

`ROOM_SNAPSHOT` принимается локально и не flood-ится дальше. Он нужен для сценария, где новый peer входит через mesh gateway и должен быстро получить состояние комнаты.

`PEER_HELLO` принимается локально, не flood-ится и не меняет snapshot комнаты.

## Ограничения

- Нет криптографических подписей событий.
- Нет отдельного audio payload: голос должен идти отдельным realtime payload и не попадать в event log.
- Нет политик доступа, rename, ban и других фич вне текущего текстового MVP.
