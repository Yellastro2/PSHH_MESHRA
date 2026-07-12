# Wi-Fi Direct Voice Transport

## Назначение

Экспериментальный media-plane для PTT-голоса поверх Wi-Fi Direct group и UDP.

Nearby пока остается signaling-транспортом комнаты:

- discovery комнат;
- join / member list / chat;
- служебный `VOICE_TRANSPORT_INFO`.

Wi-Fi Direct transport занимается только голосом:

- публикует или ищет Wi-Fi Direct DNS-SD service с `hostPeerId`;
- создает или подключается к Wi-Fi Direct group;
- слушает UDP-порт `48982`;
- отправляет `HELLO`, чтобы host связал `PeerId` с UDP endpoint клиента;
- гоняет Opus `VoiceFrame` в UDP datagrams.

## Файлы

- `app/src/main/java/com/yellastro/btration/voice/VoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/WifiDirectVoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/WifiDirectVoiceDatagramCodec.kt`
- `app/src/main/java/com/yellastro/btration/domain/model/VoiceTransportControlInfo.kt`
- `app/src/main/java/com/yellastro/btration/domain/model/WirePacket.kt`

## Handshake

1. Host создает комнату и вызывает `VoiceTransport.startSession(HOST)`.
2. `WifiDirectVoiceTransport` стартует UDP socket и запрашивает `createGroup()`.
3. После успешного `createGroup()` transport отправляет `VoiceTransportEvent.DirectAudioReady`, а `RoomRuntime` помечает `RoomInfo.isDirectAudioReady=true`, показывает snackbar host-у и только после этого рассылает гостям обновленный `RoomInfo` и host `VOICE_TRANSPORT_INFO`.
4. Host публикует Wi-Fi Direct DNS-SD service `btratio_voice` с TXT record:
   - `app=btratio`;
   - `v=1`;
   - `hostPeerId=<RoomInfo.host.peerId>`;
   - `udpPort=48982`.
5. `RoomRuntime` отправляет `JOIN_ACCEPTED` через Nearby, где лежит полный `RoomInfo`.
6. Client после `JOIN_ACCEPTED` берет `room.host.peerId` из `RoomInfo` и вызывает `VoiceTransport.startSession(CLIENT)`.
7. Client получает host `VOICE_TRANSPORT_INFO` только после `RoomInfo.isDirectAudioReady=true`, запоминает ожидаемый `hostPeerId` и запускает Wi-Fi Direct DNS-SD discovery.
8. Client принимает DNS-SD TXT record, сравнивает `hostPeerId` с `RoomInfo.host.peerId` и сохраняет `srcDevice.deviceAddress`.
9. Client вызывает Wi-Fi Direct `connect(deviceAddress)` напрямую по `srcDevice.deviceAddress` из DNS-SD callback.
10. После `groupFormed` client отправляет несколько UDP `HELLO` на group owner address.
11. Host запоминает `PeerId -> InetSocketAddress` и может слать voice frames клиентам.

Локальный `WifiP2pDevice.deviceAddress` host-а не является источником истины. Android может вернуть `02:00:00:00:00:00`,
поэтому поле `wifiDirectDeviceAddress` в `VOICE_TRANSPORT_INFO` используется только как диагностическое.

## UDP datagrams

Формат описан в `WifiDirectVoiceDatagramCodec`:

- magic `BTVU`;
- type `HELLO` или `FRAME`;
- `senderPeerId`;
- для `FRAME` внутри лежит бинарный `BTVO` из `VoiceFrameCodec`.

`VoiceFrame.originPeerId` остается исходным говорящим, а `senderPeerId` в UDP datagram — прямой сосед transport-а. Это важно для host relay:

- client говорит: `senderPeerId=client`, `originPeerId=client`;
- host ретранслирует: `senderPeerId=host`, `originPeerId=client`;
- другой client принимает frame от прямого host-а, но UI подсвечивает исходного client-а.

## Ограничения

- Wi-Fi Direct поведение сильно зависит от прошивки и Google/Android Wi-Fi stack.
- Для первого теста режим включен в `AppContainer` как `VoiceTransportMode.WIFI_DIRECT_UDP`.
- Если устройство не заявляет `PackageManager.FEATURE_WIFI_DIRECT` или не отдает `WifiP2pManager`, `AppContainer` включает `UnavailableVoiceTransport`, а UI показывает snackbar `Wi-Fi Direct не поддерживается на этом устройстве`.
- Если client не видит DNS-SD service host-а, он не получит `srcDevice.deviceAddress` и не сможет подключиться.
- Флаг `RoomInfo.isDirectAudioReady` ставится только после host `createGroup()` success; до этого host не отправляет гостям `VOICE_TRANSPORT_INFO`, а гости не начинают Wi-Fi Direct connect.
- Если первый `connect()` попал в системный pairing/accept, client держит его как pending до длинного таймаута и не запускает новые `connect()` поверх старого.
- Watchdog подключения намеренно около минуты: на реальных прошивках системный Wi-Fi Direct dialog может появляться через десятки секунд после принятого `connect()`.
- Если group не сформировалась за watchdog-окно, client делает hard cleanup: убирает service request, останавливает discovery, вызывает `cancelConnect()`, `removeGroup()`, ждет паузу и возвращается к DNS-SD discovery/connect без выхода из комнаты.
- Если client после `groupFormed` сам стал Wi-Fi Direct group owner, это некорректная для комнаты топология; transport удаляет group и пробует подключиться заново как client.
- В `WifiP2pConfig` client ставит `groupOwnerIntent=0`, чтобы Android не выбирал client-а group owner-ом.
- После `WifiP2pManager.ERROR` / `reason=0` client делает такой же hard cleanup, потому что Android P2P стек мог оставить pending invitation/group после предыдущей неудачной попытки.
- Если client не отправит UDP `HELLO`, host не будет знать IP/порт клиента и не сможет отправить ему голос.
- После `groupFormed` client дольше шлет UDP `HELLO`, чтобы host увидел endpoint даже после медленного первого pairing-а.
- UDP final-frame может потеряться; `RoomRuntime` дополнительно гасит talking-индикатор по таймауту тишины и закрывает входящую frame-сессию.
- Финальный voice frame может отправляться из UI callback отпускания PTT, поэтому Wi-Fi Direct transport переносит UDP send с main thread на IO.
- Пока нет настройки UI для выбора `NEARBY_BYTES` / `WIFI_DIRECT_UDP`; переключение сделано в composition root.

## Диагностика

Смотреть теги:

- `WifiDirectVoiceTransport`
- `RoomRuntime`
- `VoiceRuntime`

Ключевые логи:

- `[addHostService]` — host опубликовал DNS-SD service с `hostPeerId`;
- `[createGroupAfterCleanup] Wi-Fi Direct group создана` — host media-plane создан и `RoomRuntime` получит `DirectAudioReady`;
- `[handleDirectAudioReady]` — host пометил комнату готовой к direct-аудио и уведомил UI/гостей;
- `[handleDnsSdTxtRecord] Найден host Wi-Fi Direct service` — client сопоставил service с `RoomInfo.host.peerId`;
- `[connectToHost]` — client запросил Wi-Fi Direct connect к host;
- `[connectToHost] ... reason=0` — Android вернул `WifiP2pManager.ERROR`; после этого client запускает hard cleanup;
- `[cleanupClientConnectionState]` — client запросил очистку service request, discovery, pending connect и group;
- `[restartClientDiscoveryAfterHardReset]` — client снова запускает DNS-SD после cleanup-паузы;
- `[connectToHost] ... reason=2` — Android вернул `WifiP2pManager.BUSY`; новый `connect()` нельзя запускать поверх pending попытки;
- `[handleConnectionInfo]` — group сформирована, известен group owner IP;
- `[handleConnectionInfo] Client стал Wi-Fi Direct group owner` — client попал в неверную P2P-топологию, group будет пересоздана;
- `[startHelloLoop]` и `[handleUdpPacket] Получен Wi-Fi Direct HELLO` — UDP endpoint клиента появился у host-а;
- `[sendFrameToPeers] Нет UDP endpoint` — host еще не знает, куда отправлять голос.
