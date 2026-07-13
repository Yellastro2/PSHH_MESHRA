# App Container

## Назначение

`BtRationApplication` и `AppContainer` — ручной composition root приложения без DI-фреймворков и кодогенерации.

Он создает долгоживущие объекты один раз на процесс и отдает XML-фрагментам только `ViewModelProvider.Factory`.

## Файлы

- `app/src/main/java/com/yellastro/btration/BtRationApplication.kt`
- `app/src/main/java/com/yellastro/btration/AppContainer.kt`
- `app/src/main/java/com/yellastro/btration/MainActivity.kt`

## Цепочка объектов

`BtRationApplication.onCreate()` создает `AppContainer`.

Перед контейнером приложение регистрирует `AppVisibilityTracker`, чтобы сервис мог понять, находится ли PSHH MESHRA сейчас на экране пользователя.

`AppContainer` собирает:

- application `CoroutineScope`;
- `Json`;
- `WireCodec`;
- `IdGenerator`;
- `NearbyTransport` как реализацию `NeighborTransport`;
- `RoomTransport` как room-level signaling поверх `NeighborTransport`;
- `SwitchableVoiceTransport`, который лениво создает нужный media-plane (`NearbyVoiceTransport` или `WifiDirectVoiceTransport`);
- `ProfileRepository`;
- `IgnoredNearbyRepository` для локального списка ignored host `PeerId` в Nearby-лобби;
- `RoomRuntime`;
- `RoomServiceController`;
- `RoomRepository`;
- factory для `ProfileViewModel`, `LobbyViewModel`, `RoomViewModel`.

## UI-доступ

Фрагменты берут factory так:

`(requireActivity().application as BtRationApplication).appContainer.*ViewModelFactory()`

Фрагменты не создают `RoomRuntime`, `RoomTransport`, `NearbyTransport` и repository напрямую.

## Permissions

`MainActivity` сразу после старта запрашивает runtime permissions для Nearby и уведомлений:

- Android 13+ — `NEARBY_WIFI_DEVICES`;
- Android 13+ — `POST_NOTIFICATIONS`;
- Android 12+ — `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`;
- все версии — `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, потому что Nearby Connections может явно требовать location permission для discovery.

В манифесте также всегда объявлены `ACCESS_WIFI_STATE` и `CHANGE_WIFI_STATE`: Google Nearby Connections проверяет их на новых Android тоже, хотя runtime dialog для них не нужен.

`BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` и `NEARBY_WIFI_DEVICES` объявлены в manifest без `minSdkVersion`, чтобы Nearby/Google Play Services точно видели permissions в установленном APK. На старых Android неизвестные permissions просто игнорируются системой. Для `NEARBY_WIFI_DEVICES` стоит `usesPermissionFlags="neverForLocation"`, потому что Nearby нужен для локальной связи, а не для вывода геолокации пользователя.

Если обязательные Nearby permissions не выданы, `MainActivity` показывает список недостающих permissions тостом, но все равно открывает UI. `NearbyTransport` перед вызовом Google Nearby дополнительно проверяет discovery/advertising permissions и через `RoomTransport` отдает понятную ошибку в `RoomRuntimeState.Error`, чтобы лобби или комната показали причину на экране.

Если permissions выданы, но системная геолокация устройства выключена, `NearbyTransport` не вызывает Google Nearby API, а через `RoomTransport` отдает ошибку с действием `OPEN_LOCATION_SETTINGS`. Лобби и комната показывают диалог с кнопкой перехода в системные настройки геолокации.

## Service

`RoomServiceController` остается тонкой боковой обвязкой.

Он не стоит между ViewModel и RoomRuntime. Сейчас он запускает `RoomConnectionService` как foreground service, чтобы пользователь видел постоянное уведомление о поиске или активной комнате, но чат, участники и логика комнаты остаются в `RoomRuntime`.

`RoomConnectionService` также показывает отдельные уведомления о новых чужих сообщениях только когда приложение не находится на экране. При старте сервиса старая история сообщений не превращается в уведомления.
