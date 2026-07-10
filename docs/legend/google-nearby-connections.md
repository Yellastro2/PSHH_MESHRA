# Google Nearby Connections

## Что используем

- Android API: Google Play services Nearby Connections.
- Зависимость MVP: `com.google.android.gms:play-services-nearby:19.3.0`.
- Текущая стратегия MVP: `Strategy.P2P_STAR`.
- `serviceId` должен быть стабильной строкой приложения. Сейчас в `NearbyTransport`: `com.yellastro.btration.nearby.ROOM_V1`.

## Практический контракт проекта

- Advertising публикует комнату через `endpointName`.
- В `endpointName` кладётся компактная визитка `NearbyRoomAdvertisement`, чтобы discovery мог показать комнату без подключения.
- Nearby endpointId не считается доменным идентификатором участника. Для этого есть `NearbyEndpointRegistry`, который связывает `endpointId` с `PeerId` и `RoomId`.
- Транспорт автоматически принимает Nearby connection, а бизнес-решение входа в комнату остаётся выше, в `RoomRuntime`, через `JOIN_ACCEPTED` / `JOIN_REJECTED`.
- Для будущего mesh/relay в `WirePacket` уже есть поля `packetId` и `ttl`, но Nearby-слой пока не делает dedup и relay.

## STREAM payload для MVP-голоса

- Голос MVP отправляется через `Payload.fromStream(...)` как PCM16 mono 16 kHz.
- На Nothing Phone 2a в тесте `Payload.fromStream(...)` впервые прочитал исходящий stream примерно через 1 секунду после `sendPayload` и запросил сразу `25600` байт, то есть около 800 ms PCM-аудио.
- Чтобы проверить и уменьшить стартовую задержку, `NearbyTransport` оборачивает исходящий stream и ограничивает один `read(...)` до 320 байт, то есть около 10 ms PCM-аудио.
- Если после этого receiver всё равно получает звук с большой стартовой задержкой, следующий практичный путь для MVP — уйти от `STREAM` к маленьким `Payload.fromBytes(...)` PCM-фреймам.

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
