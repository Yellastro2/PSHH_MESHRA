# Google Nearby Connections

## Что используем

- Android API: Google Play services Nearby Connections.
- Зависимость MVP: `com.google.android.gms:play-services-nearby:19.3.0`.
- Физическая strategy выбирается по типу комнаты: `NEARBY_STAR -> Strategy.P2P_STAR`, `MESHRA -> Strategy.P2P_CLUSTER`.
- `serviceId` должен быть стабильной строкой приложения. Сейчас публичный фасад `NearbyTransport` передаёт в `NearbyConnectionLayer`: `com.yellastro.btration.nearby.ROOM_V1`.
- Для MESHRA нижняя Nearby-стратегия должна позволять устройству быть одновременно участником уже установленного link-а и gateway для нового peer-а. В тесте 2026-07-13 `P2P_STAR` давал `STATUS_ENDPOINT_IO_ERROR` при входе C через gateway B, когда B уже состоял в комнате A, поэтому MESHRA advertising/connect/healing используют `P2P_CLUSTER`.
- Для обычной Star-комнаты advertising и connect используют физический `P2P_STAR`. Это подтверждено рабочим Direct voice commit `25683d76446f9189b0957475f42851221aed0713`, где `NearbyTransport` имел default `Strategy.P2P_STAR` одновременно с `WifiDirectVoiceTransport`.
- Одновременный discovery с двумя Strategy не используется. Общее лобби запускает четырехсекундные фазы `P2P_STAR -> P2P_CLUSTER -> ...` с коротким cooldown между ними. Искусственные `EndpointLost` при смене фаз подавляются; список комнат сверяется общим десятисекундным discovery-циклом runtime.
- Найденный endpoint запоминается вместе с topology текущей фазы. При выборе комнаты `RoomTransportMode` из рекламы определяет требуемую topology: при совпадении connect идет сразу, при несовпадении transport фиксирует нужную фазу, повторно обнаруживает тот же endpoint и только затем вызывает `requestConnection`. Ожидание ограничено timeout-ом 12 секунд.

## Практический контракт проекта

- Advertising публикует комнату через `endpointName`.
- В `endpointName` кладётся компактная визитка `NearbyRoomAdvertisement` формата `BTR5`, чтобы discovery мог показать комнату без подключения.
- Визитка `BTR5` содержит `sessionId`, `createdAtMillis`, короткий след host-а, token room transport, token voice transport, `roomName` и `hostName`. Полные `roomId` и `hostPeerId` не кладутся в `endpointName`, потому что лимит Nearby маленький и название комнаты важнее.
- Старый формат `BTR4` продолжает декодироваться как legacy-визитка без token room transport; для него используется default `NEARBY_STAR`, а token voice transport читается как раньше.
- Старый формат `BTR3` продолжает декодироваться как legacy-визитка без token voice transport; для него используется default `WIFI_DIRECT_UDP`.
- До `JOIN_ACCEPTED` listener использует временные `RoomId`/`PeerId` из визитки. После подключения host присылает настоящий `RoomInfo`, и runtime заменяет временные идентификаторы реальными.
- Nearby endpointId не считается доменным идентификатором участника. Связь `endpointId/linkId -> PeerId/RoomId` ведет `RoomTransport`, потому что это уже room-level знание, а не обязанность нижнего Nearby wrapper-а.
- Для физических nearby-линков введён общий интерфейс `NeighborTransport`: discovery, topology advertising/connect, accept/reject/disconnect, BYTES-сообщения и STREAM-потоки описаны без привязки к Nearby SDK и без знания формата контента.
- Код Nearby разделён на фасад `NearbyTransport`, lifecycle-слой `NearbyConnectionLayer` и payload-слой `NearbyPayloadTransport`. `NearbyConnectionLayer` отвечает за discovery, advertising, request/accept/reject/disconnect callbacks и transient permission retry. `NearbyPayloadTransport` отвечает только за отправку и прием непрозрачных BYTES/STREAM по endpointId. `NearbyTransport` реализует `NeighborTransport` и не знает о `WirePacket`, `RoomInfo`, `PeerId` или voice frame.
- `RoomTransport` сидит поверх `NeighborTransport`: кодирует/декодирует `WirePacket`, готовит/читает `NearbyRoomAdvertisement`, автоматически принимает нижнее connection request и публикует `RoomTransportEvent` для `RoomRuntime`.
- `MeshTransport` тоже сидит поверх того же `NeighborTransport`: управляющие JSON payload-ы имеют сигнатуру `BTME1\n`, компактные бинарные MESHRA voice DATA-пакеты — двухбайтовую сигнатуру `MV`, а gateway-реклама — `BTM4`. В `AppContainer` прямой accept у `MeshTransport` выключен, чтобы не принимать один Nearby request двумя слоями; общий accept выполняет `RoomTransport`.
- Ignore-list для MESHRA применяется к gateway из `BTM4`, а не к known host комнаты. `RoomTransport` получает predicate `shouldAcceptConnection(...)` и отклоняет входящий request, если endpointName распознается как реклама ignored gateway.
- `NearbyVoiceTransport` сидит рядом с `RoomTransport` поверх того же `NeighborTransport`: он читает только BYTES с сигнатурой `BTVO`, а все room/control сообщения игнорирует.
- Ошибка отправки BYTES/STREAM возвращается callback-ом конкретному вызывающему слою, а не широковещательным event-ом: иначе voice send failure мог бы случайно превратиться в room packet failure.
- Частые voice BYTES отправляются через `NeighborTransport.sendMessage(..., isRealtime=true)`: `NearbyPayloadTransport` не пишет `Log.i` на каждый успешный frame, а агрегирует успешные realtime-отправки примерно раз в секунду. Ошибки отправки realtime payload-а логируются сразу.
- Nearby lifecycle callbacks считаются только нижним статусом физического endpoint-а. Для MESHRA этого мало: endpoint может числиться connected, пока Google Play services держит внутреннюю очередь payload-ов. Поэтому `MeshTransport` отдельно шлет четырехбайтовые heartbeat `PING/PONG` поверх BYTES и считает RTT/потери на app-level. Heartbeat запускается только для подтвержденных mesh link-ов: исходящих gateway-connect-ов или входящих link-ов, где уже пришел валидный mesh envelope.
- `endpointId -> PeerId` описывает только прямого физического Nearby-соседа. Автор `CHAT_MESSAGE` или другого relayed-пакета не должен перезаписывать эту связь.
- `RoomTransport` автоматически принимает Nearby connection, а бизнес-решение входа в комнату остаётся выше, в `RoomRuntime`, через `JOIN_ACCEPTED` / `JOIN_REJECTED`.
- Для будущего mesh/relay в `WirePacket` уже есть поля `packetId` и `ttl`, но Nearby-слой пока не делает dedup и relay.
- При старте приложения, перед новой рекламой и при сбросе runtime вызывается полный cleanup Nearby: `stopDiscovery()`, `stopAdvertising()`, `stopAllEndpoints()` и очистка локального registry.
- Повторный запрос уже активного discovery-режима отсекается идемпотентно в `NearbyTransport`. Если `STATUS_ALREADY_DISCOVERING` приходит после намеренного переключения Strategy, `NearbyConnectionLayer` считает это stop/start race, повторяет остановку и до двух раз перезапускает требуемую Strategy; молча считать старую фазу новой нельзя.

## BYTES payload для MVP-голоса

- Актуальный PTT-голос отправляется короткими `Payload.fromBytes(...)` фреймами формата `BTVO`.
- Nearby-реализация голоса изолирована в `NearbyVoiceTransport`, а `VoiceRuntime` работает через общий `VoiceTransport`.
- В режиме `WIFI_DIRECT_UDP` Nearby больше не несет аудио, но продолжает передавать `JOIN_ACCEPTED` с полным `RoomInfo.host.peerId` и `VOICE_TRANSPORT_INFO` для запуска Wi-Fi Direct handshake.
- Реальный Wi-Fi Direct `deviceAddress` не передается через Nearby: client находит host-а через DNS-SD TXT `hostPeerId=<RoomInfo.host.peerId>` и берет адрес из `srcDevice.deviceAddress`.
- Один фрейм несет `originPeerId`, `sequence`, признак `isFinal` и Opus packet примерно на 40 ms речи.
- На говорящем устройстве `AudioRecord` дает PCM16 mono 16 kHz, затем `OpusVoiceEncoder` кодирует его в Opus.
- На слушающем устройстве `OpusVoiceDecoder` декодирует Opus обратно в PCM16 перед `PcmVoicePlayer` / `AudioTrack`.
- Host при получении frame проверяет, что `originPeerId == directPeerId`, ретранслирует сжатый Opus-frame остальным участникам без decode/re-encode, а для собственного динамика декодирует отдельной локальной веткой.
- Client принимает voice frame только от host endpoint-а и только если `originPeerId` есть в списке участников.
- Ошибка отправки отдельного voice frame логируется, но не переводит комнату в Error: голос может потерять фрейм, а чат и сессия должны жить дальше.
- Если Nearby API возвращает `ApiException` при discovery/advertising/connect/accept, ошибка проходит через `NearbyTransport -> RoomTransport`, а `RoomRuntime` переводит состояние в Error и отправляет snackbar `Nearby Connections не поддерживается или недоступен на этом устройстве`.
- Исключение: transient `8034: MISSING_PERMISSION...` сразу после выдачи runtime permissions. Если локальный precheck уже видит нужные permissions, `NearbyConnectionLayer` делает несколько тихих retry discovery/advertising и не отправляет ошибку в UI до исчерпания попыток.

## STREAM payload для старого MVP-голоса

- Голос MVP отправляется через `Payload.fromStream(...)` как PCM16 mono 16 kHz.
- На Nothing Phone 2a в тесте `Payload.fromStream(...)` впервые прочитал исходящий stream примерно через 1 секунду после `sendPayload` и запросил сразу `25600` байт, то есть около 800 ms PCM-аудио.
- Чтобы проверить и уменьшить стартовую задержку, `NearbyTransport` оборачивает исходящий stream и ограничивает один `read(...)` до 320 байт, то есть около 10 ms PCM-аудио.
- Этот режим оставлен в коде как запасной путь, но PTT переключен на `BYTES` frames после падений `WIFI_DIRECT SEND_PAYLOAD_FAILED SOCKET_CLOSED` на длинных stream payload.

## Permissions

В `app/src/main/AndroidManifest.xml` добавлены permissions, которые нужны Nearby Connections на разных версиях Android:

- Wi-Fi state/change для старых SDK.
- Bluetooth/Bluetooth admin до Android 11 включительно.
- Location permissions для старых discovery-сценариев.
- `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` для Android 12+.
- `NEARBY_WIFI_DEVICES` для новых Android.

Runtime-запрос permissions реализован в `MainActivity`; транспортный слой дополнительно делает precheck перед Nearby discovery/advertising.

## Источники

- Official get started: https://developers.google.com/nearby/connections/android/get-started
- Google Maven metadata: https://dl.google.com/android/maven2/com/google/android/gms/play-services-nearby/maven-metadata.xml
