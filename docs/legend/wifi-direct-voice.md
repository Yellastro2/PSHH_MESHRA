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
- выполняет двусторонний `HELLO/HELLO_ACK`, чтобы оба устройства подтвердили UDP endpoint;
- гоняет Opus `VoiceFrame` в UDP datagrams.

## Файлы

- `app/src/main/java/com/yellastro/btration/voice/VoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/WifiDirectVoiceTransport.kt`
- `app/src/main/java/com/yellastro/btration/voice/WifiDirectVoiceDatagramCodec.kt`
- `app/src/main/java/com/yellastro/btration/domain/model/VoiceTransportControlInfo.kt`
- `app/src/main/java/com/yellastro/btration/domain/model/WirePacket.kt`

## Handshake

1. Host создает комнату и вызывает `VoiceTransport.startSession(HOST)`.
2. `WifiDirectVoiceTransport` запрашивает `createGroup()`, но еще не считает direct-аудио готовым.
3. Host отправляет `VOICE_TRANSPORT_INFO` через Nearby, чтобы клиент мог начать Wi-Fi Direct handshake.
4. Host публикует Wi-Fi Direct DNS-SD service `btratio_voice` с TXT record:
   - `app=btratio`;
   - `v=1`;
   - `hostPeerId=<RoomInfo.host.peerId>`;
   - `udpPort=48982`.
5. `RoomRuntime` отправляет `JOIN_ACCEPTED` через Nearby, где лежит полный `RoomInfo`.
6. Client после `JOIN_ACCEPTED` берет `room.host.peerId` из `RoomInfo` и вызывает `VoiceTransport.startSession(CLIENT)`.
7. Client получает host `VOICE_TRANSPORT_INFO`, запоминает ожидаемый `hostPeerId` и запускает Wi-Fi Direct DNS-SD discovery.
8. Client принимает DNS-SD TXT record, сравнивает `hostPeerId` с `RoomInfo.host.peerId` и сохраняет `srcDevice.deviceAddress`.
9. Client вызывает Wi-Fi Direct `connect(deviceAddress)` напрямую по `srcDevice.deviceAddress` из DNS-SD callback.
10. После `groupFormed` обе стороны пересоздают UDP socket. Предпочтительно используется P2P `Network.bindSocket()`; если прошивка не публикует такую `Network`, socket bind-ится к локальному адресу P2P-интерфейса из подсети group owner.
11. Client отправляет UDP `HELLO`; host сохраняет endpoint и отвечает `HELLO_ACK`.
12. После привязки socket-а обе стороны дополнительно передают через Nearby фактические `p2pAddress + udpPort`. Если обычный handshake не успел завершиться, через 3 секунды обе стороны начинают резервный UDP-punch к этим endpoint-ам.
13. Host считает peer готовым по реально принятому `HELLO`/`HELLO_ACK`, а client — только по `HELLO_ACK` на собственный `HELLO`; адрес из Nearby сам по себе готовностью не считается.
14. Host разрешает PTT для готовых участников, даже если handshake отдельного клиента не состоялся. Если готовых адресатов нет, UI получает `Прямой аудиоканал не установлен`.

Локальный `WifiP2pDevice.deviceAddress` host-а не является источником истины. Android может вернуть `02:00:00:00:00:00`,
поэтому поле `wifiDirectDeviceAddress` в `VOICE_TRANSPORT_INFO` используется только как диагностическое.

## UDP datagrams

Формат описан в `WifiDirectVoiceDatagramCodec`:

- magic `BTVU`;
- type `HELLO`, `HELLO_ACK` или `FRAME`;
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
- Флаг `RoomInfo.isDirectAudioReady` ставится только после входящего UDP `HELLO`; создание group само по себе готовностью не считается.
- UDP socket создается заново после `groupFormed` и привязывается к P2P `Network` либо напрямую к локальному P2P IPv4. Прямой bind нужен Xiaomi, Nothing и другим прошивкам, которые не показывают Wi-Fi Direct в `ConnectivityManager.allNetworks`.
- Если первый `connect()` попал в системный pairing/accept, client держит его как pending до длинного таймаута и не запускает новые `connect()` поверх старого.
- Watchdog подключения намеренно около минуты: на реальных прошивках системный Wi-Fi Direct dialog может появляться через десятки секунд после принятого `connect()`.
- Если group не сформировалась за watchdog-окно, client делает hard cleanup: убирает service request, останавливает discovery, вызывает `cancelConnect()`, `removeGroup()`, ждет паузу и возвращается к DNS-SD discovery/connect без выхода из комнаты.
- Если client после `groupFormed` сам стал Wi-Fi Direct group owner, это некорректная для комнаты топология; transport удаляет group и пробует подключиться заново как client.
- В `WifiP2pConfig` client ставит `groupOwnerIntent=0`, чтобы Android не выбирал client-а group owner-ом.
- После `WifiP2pManager.ERROR` / `reason=0` client делает такой же hard cleanup, потому что Android P2P стек мог оставить pending invitation/group после предыдущей неудачной попытки.
- Если `HELLO/HELLO_ACK` не завершится, обе стороны не считают peer готовым и не запускают запись PTT.
- Резервный endpoint из Nearby принимается только как numeric IPv4 из подсети текущего group owner; затем обе стороны отправляют туда `HELLO`, чтобы обойти прошивки с односторонней UDP-доступностью.
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
- `[createGroupAfterCleanup] Wi-Fi Direct group создана, ожидаем UDP handshake` — group есть, но media-plane еще не готов;
- `[restartUdpSocketForGroup]` — UDP socket найден и привязан к системной P2P `Network`;
- `[handleDirectAudioReady]` — реальный UDP handshake завершен и UI/гости получили готовность;
- `[handleDnsSdTxtRecord] Найден host Wi-Fi Direct service` — client сопоставил service с `RoomInfo.host.peerId`;
- `[connectToHost]` — client запросил Wi-Fi Direct connect к host;
- `[connectToHost] ... reason=0` — Android вернул `WifiP2pManager.ERROR`; после этого client запускает hard cleanup;
- `[cleanupClientConnectionState]` — client запросил очистку service request, discovery, pending connect и group;
- `[restartClientDiscoveryAfterHardReset]` — client снова запускает DNS-SD после cleanup-паузы;
- `[connectToHost] ... reason=2` — Android вернул `WifiP2pManager.BUSY`; новый `connect()` нельзя запускать поверх pending попытки;
- `[handleConnectionInfo]` — group сформирована, известен group owner IP;
- `[handleConnectionInfo] Client стал Wi-Fi Direct group owner` — client попал в неверную P2P-топологию, group будет пересоздана;
- `[startHelloLoop]`, `Получен Wi-Fi Direct HELLO` и `HELLO_ACK` — двусторонний UDP handshake;
- `[sendFrameToPeers] Нет UDP endpoint` — host еще не знает, куда отправлять голос.
