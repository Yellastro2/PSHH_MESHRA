# PSHH MESHRA

PSHH MESHRA — Android-приложение для локальной голосовой связи и сообщений между nearby-устройствами без обязательного внешнего сервера.

Главная идея проекта: комната приложения должна стать логической сетью поверх доступных nearby-транспортов. Wi-Fi Aware, Nearby Connections, Wi-Fi Direct и Bluetooth рассматриваются как способы найти соседей и передать байты, но не как готовая mesh-сеть.

Приложение уже позволяет оценить качество голосовой связи при использовании разных технологий: Wifi-Direct, Nearby Connect.

## Текущее состояние

- Комнаты и участники управляются через `RoomRuntime`.
- Signaling сейчас идет через Nearby Connections.
- Голосовой media-plane вынесен в отдельный `VoiceTransport`.
- В проекте уже есть задел под packet identity: `packetId`, TTL и отбрасывание дублей.
- Полноценный app-level mesh routing пока не реализован.

## Архитектурная позиция

Mesh-маршрутизацию приложение должно делать само:

```text
RoomRuntime / direct sessions
  -> MeshRouter
  -> NeighborTransport
  -> Wi-Fi Aware / Nearby Connections / Wi-Fi Direct / Bluetooth
```

Физические nearby-технологии дают только соседские каналы. Маршруты, relay, TTL, дедупликация, политика voice-передачи и устойчивые identity устройств должны быть частью нашего протокола.

## Документация

- [RoomRuntime](docs/room-runtime.md) — текущая модель комнаты, host/client MVP, voice flow, packetId/TTL и ограничения.
- [Mesh network README](docs/mesh-network-readme.md) — фиксация позиции по Wi-Fi Aware, Nearby, Wi-Fi Direct и собственной mesh-маршрутизации.
- [AppContainer](docs/app-container.md) — composition root приложения и выбор реализаций.
- [Repository layer](docs/repository-layer.md) — слой репозиториев.
- [ViewModel layer](docs/viewmodel-layer.md) — слой ViewModel.
- [Roadmap](roadmap.md) — ближайшие продуктовые и сетевые направления.

```text
Под блокировками без общения грустишь?
Попробуй PSHH
```
```
По лесу в поисках шабуршишь?
Используй PSHH
(Товарищ майор, я имел ввиду поиск грибов или пропавших людей, а не того что у вас в пакетике при себе было)
```