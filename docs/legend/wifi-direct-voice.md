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
3. Host публикует Wi-Fi Direct DNS-SD service `btratio_voice` с TXT record:
   - `app=btratio`;
   - `v=1`;
   - `hostPeerId=<RoomInfo.host.peerId>`;
   - `udpPort=48982`.
4. `RoomRuntime` отправляет `JOIN_ACCEPTED` через Nearby, где лежит полный `RoomInfo`.
5. Client после `JOIN_ACCEPTED` берет `room.host.peerId` из `RoomInfo` и вызывает `VoiceTransport.startSession(CLIENT)`.
6. Client получает host `VOICE_TRANSPORT_INFO`, запоминает ожидаемый `hostPeerId` и запускает Wi-Fi Direct DNS-SD discovery.
7. Client принимает DNS-SD TXT record, сравнивает `hostPeerId` с `RoomInfo.host.peerId`, берет `srcDevice.deviceAddress` из Android callback и вызывает Wi-Fi Direct `connect(deviceAddress)`.
8. После `groupFormed` client отправляет несколько UDP `HELLO` на group owner address.
9. Host запоминает `PeerId -> InetSocketAddress` и может слать voice frames клиентам.

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
- Если первый `connect()` попал в системный pairing/accept, client держит его как pending до таймаута и не запускает новые `connect()` поверх старого.
- Если group не сформировалась за watchdog-окно, client вызывает `cancelConnect()`, затем возвращается к DNS-SD discovery/connect без выхода из комнаты.
- Если client не отправит UDP `HELLO`, host не будет знать IP/порт клиента и не сможет отправить ему голос.
- После `groupFormed` client дольше шлет UDP `HELLO`, чтобы host увидел endpoint даже после медленного первого pairing-а.
- UDP final-frame может потеряться; `RoomRuntime` дополнительно гасит talking-индикатор по таймауту тишины и закрывает входящую frame-сессию.
- Пока нет настройки UI для выбора `NEARBY_BYTES` / `WIFI_DIRECT_UDP`; переключение сделано в composition root.

## Диагностика

Смотреть теги:

- `WifiDirectVoiceTransport`
- `RoomRuntime`
- `VoiceRuntime`

Ключевые логи:

- `[addHostService]` — host опубликовал DNS-SD service с `hostPeerId`;
- `[handleDnsSdTxtRecord] Найден host Wi-Fi Direct service` — client сопоставил service с `RoomInfo.host.peerId`;
- `[connectToHost]` — client запросил Wi-Fi Direct connect к host;
- `[connectToHost] ... reason=2` — Android вернул `WifiP2pManager.BUSY`; новый `connect()` нельзя запускать поверх pending попытки;
- `[handleConnectionInfo]` — group сформирована, известен group owner IP;
- `[startHelloLoop]` и `[handleUdpPacket] Получен Wi-Fi Direct HELLO` — UDP endpoint клиента появился у host-а;
- `[sendFrameToPeers] Нет UDP endpoint` — host еще не знает, куда отправлять голос.
