# Google Nearby Connections

## Что используем

- Android API: Google Play services Nearby Connections.
- Зависимость MVP: `com.google.android.gms:play-services-nearby:19.3.0`.
- Текущая стратегия MVP: `Strategy.P2P_STAR`.
- `serviceId` должен быть стабильной строкой приложения. Сейчас в `NearbyTransport`: `com.yellastro.btration.nearby.ROOM_V1`.

## Практический контракт проекта

- Advertising публикует комнату через `endpointName`.
- В `endpointName` кладётся компактная визитка `NearbyRoomAdvertisement` формата `BTR3`, чтобы discovery мог показать комнату без подключения.
- Визитка `BTR3` содержит только `sessionId`, `createdAtMillis`, короткий след host-а, `roomName` и `hostName`. Полные `roomId` и `hostPeerId` не кладутся в `endpointName`, потому что лимит Nearby маленький и название комнаты важнее.
- До `JOIN_ACCEPTED` listener использует временные `RoomId`/`PeerId` из визитки. После подключения host присылает настоящий `RoomInfo`, и runtime заменяет временные идентификаторы реальными.
- Nearby endpointId не считается доменным идентификатором участника. Для этого есть `NearbyEndpointRegistry`, который связывает `endpointId` с `PeerId` и `RoomId`.
- `endpointId -> PeerId` описывает только прямого физического Nearby-соседа. Автор `CHAT_MESSAGE` или другого relayed-пакета не должен перезаписывать эту связь.
- Транспорт автоматически принимает Nearby connection, а бизнес-решение входа в комнату остаётся выше, в `RoomRuntime`, через `JOIN_ACCEPTED` / `JOIN_REJECTED`.
- Для будущего mesh/relay в `WirePacket` уже есть поля `packetId` и `ttl`, но Nearby-слой пока не делает dedup и relay.
- При старте приложения, перед новой рекламой и при сбросе runtime вызывается полный cleanup Nearby: `stopDiscovery()`, `stopAdvertising()`, `stopAllEndpoints()` и очистка локального registry.

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
- Если Nearby API возвращает `ApiException` при discovery/advertising/connect/accept, `RoomRuntime` переводит состояние в Error и отправляет snackbar `Nearby Connections не поддерживается или недоступен на этом устройстве`.

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

Runtime-запрос permissions ещё не реализован: это задача UI/ViewModel слоя.

## Источники

- Official get started: https://developers.google.com/nearby/connections/android/get-started
- Google Maven metadata: https://dl.google.com/android/maven2/com/google/android/gms/play-services-nearby/maven-metadata.xml
