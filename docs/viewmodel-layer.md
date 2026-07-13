# ViewModel Layer

## Назначение

ViewModel-слой готовит состояние для XML-фрагментов и прокидывает пользовательские действия в repository-слой.

Он не работает напрямую с `NearbyTransport`, `WireCodec` или `RoomRuntime`.

Фрагменты подключены к ViewModel через factory из `AppContainer`; старые локальные моки комнат, участников и сообщений удалены.

## Файлы

- `app/src/main/java/com/yellastro/btration/ui/profile/ProfileUiState.kt`
- `app/src/main/java/com/yellastro/btration/ui/profile/ProfileViewModel.kt`
- `app/src/main/java/com/yellastro/btration/ui/lobby/LobbyUiState.kt`
- `app/src/main/java/com/yellastro/btration/ui/lobby/LobbyViewModel.kt`
- `app/src/main/java/com/yellastro/btration/ui/room/RoomUiState.kt`
- `app/src/main/java/com/yellastro/btration/ui/room/RoomViewModel.kt`

## ProfileViewModel

Отвечает только за локальное имя пользователя:

- `onNameChanged(value)`;
- `onContinueClicked()`.

После успешного сохранения выставляет `ProfileUiState.isCompleted = true`.

## LobbyViewModel

Показывает имя пользователя, поиск и найденные комнаты.

Команды:

- `onStartSearchClicked()`;
- `onStopSearchClicked()`;
- `onCreateRoomClicked(name, roomTransportMode, voiceTransportPreference)`;
- `onJoinRoomClicked(room)`;
- `onIgnoreRoomClicked(room)`;
- `onClearIgnoredHostsConfirmed()`.

Диалог создания комнаты передает в `LobbyViewModel` имя комнаты, выбранный тип комнаты (`Nearby Star` / `MESHRA`) и выбранный voice transport. ViewModel сохраняет оба transport-выбора в prefs до вызова `RoomRepository.createRoom(...)`, чтобы host runtime положил эти режимы в meta комнаты.

Если выбран `MESHRA`, ViewModel не меняется: тот же `RoomRepository.createRoom(...)` приводит runtime в активную комнату, но внутри `RoomRuntime` выбирает `MeshTransport` вместо Nearby Star. Для UI это остается обычная комната с участниками и текстовым чатом.

Для тестирования nearby/mesh-сценариев лобби умеет локально игнорировать прямой gateway найденной комнаты. `LobbyViewModel` берет `RoomInfo.gateway?.peerId ?: RoomInfo.host.peerId`, сохраняет его через `IgnoredNearbyRepository` и фильтрует найденные комнаты до маппинга в `RoomItemUi`. `endpointId` не сохраняется, потому что это временный идентификатор нижнего транспорта.

Для MESHRA ignore применяется к прямому `RoomInfo.gateway`, а не к логическому `RoomInfo.host`. Поэтому если C игнорит gateway A, реклама A скрывается и вход через A блокируется, но та же mesh-комната через gateway B остается видимой. Для Nearby Star `gateway == host`, так что старое поведение сохраняется.

## RoomViewModel

Показывает состояние текущей комнаты:

- имя комнаты;
- роль host/client;
- участников;
- чат;
- текст ввода.

Команды:

- `onMessageChanged(value)`;
- `onSendClicked()`;
- `onLeaveClicked()`;
- `onCloseRoomClicked()`.

`RoomViewModel` получает локальный `PeerId` через `RoomRepository.getSelfPeerId()`, чтобы вычислять `MemberUi.isSelf` и `ChatMessageUi.isOwn`.
Выбранный transport активной комнаты отображается на экране комнаты как read-only пункт меню; после старта комнаты он не меняется из этого меню.
