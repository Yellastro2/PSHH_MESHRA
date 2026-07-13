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
- `onCreateRoomClicked(name, voiceTransportPreference)`;
- `onJoinRoomClicked(room)`;
- `onIgnoreRoomClicked(room)`;
- `onClearIgnoredHostsConfirmed()`.

Диалог создания комнаты передает в `LobbyViewModel` имя комнаты и выбранный voice transport; ViewModel сохраняет transport в prefs до вызова `RoomRepository.createRoom(...)`, чтобы host runtime положил этот режим в meta комнаты.

Для тестирования nearby/mesh-сценариев лобби умеет локально игнорировать host-а найденной комнаты. `LobbyViewModel` берет `RoomInfo.host.peerId`, сохраняет его через `IgnoredNearbyRepository` и фильтрует найденные комнаты до маппинга в `RoomItemUi`. `endpointId` не сохраняется, потому что это временный идентификатор нижнего транспорта.

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
