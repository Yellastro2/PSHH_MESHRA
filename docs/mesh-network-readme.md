# Mesh network README

## Главная фиксация

Мы знаем, что Wi-Fi Aware, Nearby Connections, Wi-Fi Direct, Bluetooth и другие nearby-технологии не дают приложению готовую mesh-маршрутизацию.

Они могут помочь найти соседние устройства, поднять прямой канал до конкретного соседа и передать байты между двумя или несколькими nearby-участниками. Но они не дают нам полноценный app-level mesh:

- не строят за нас устойчивую таблицу маршрутов между всеми участниками комнаты;
- не гарантируют multi-hop доставку через промежуточные устройства;
- не решают дедупликацию пакетов, TTL, flooding, backoff и выбор следующего hop;
- не дают стабильную identity-модель друзей и устройств;
- не превращают комнату приложения в автономную mesh-сеть.

Именно поэтому mesh-маршрутизацию мы собираемся делать сами внутри приложения.

## Что значит "делать сами"

В приложении нужен отдельный слой маршрутизации между логикой комнаты и физическими nearby-транспортами.

```text
RoomRuntime / RoomTransport / voice transports
  -> MeshRouter
  -> NeighborTransport
  -> Wi-Fi Aware / Nearby Connections / Wi-Fi Direct / Bluetooth
```

`MeshRouter` должен отвечать за app-level сеть:

- стабильные `peerId` участников;
- соседей, которых прямо видно сейчас;
- `packetId`, TTL и кэш уже виденных пакетов;
- relay сообщений через промежуточных участников;
- выбор маршрута для control/chat/signaling;
- отдельную стратегию для voice, где нельзя бездумно flood-ить каждый audio frame;
- graceful degrade, если остался только один физический канал или часть соседей пропала.

## Роль Wi-Fi Aware

Wi-Fi Aware для нас не "готовый mesh". Это хороший кандидат на основной физический neighbor transport:

- publish/subscribe для нахождения соседей;
- прямые data path соединения между обнаруженными peer-ами;
- работа без общей Wi-Fi сети;
- потенциально несколько соседских линков, если железо и система позволяют.

Но Android управляет NAN-кластерами сам, а приложение не получает готовый mesh-граф с маршрутизацией. Поэтому Aware может быть нижним каналом, но не заменой `MeshRouter`.

## Роль Nearby Connections

Nearby Connections полезен как fallback и signaling-канал, особенно когда Wi-Fi Aware недоступен на устройстве.

`P2P_CLUSTER` ближе к нашей цели, чем `P2P_STAR`, но это все равно не отменяет нашего app-level routing слоя. Даже если Nearby сам умеет держать несколько nearby-соединений, правила доставки, identity, TTL, relay и voice-политики остаются ответственностью приложения.

## Роль Wi-Fi Direct

Wi-Fi Direct годится как временный или опциональный direct media transport, но плохо совпадает с целью настоящей mesh-сети:

- group owner модель тянет архитектуру к star-топологии;
- не дает удобную multi-hop маршрутизацию;
- может конфликтовать с Wi-Fi Aware, SoftAP или tethering на части устройств.

Поэтому Wi-Fi Direct не должен быть главным ответом на mesh-задачу. Его можно оставить как совместимый one-hop транспорт, но не строить вокруг него всю сетевую модель.

## Практическое решение

Комната приложения должна быть логической сетью, а не синонимом одного host-а или одного физического канала.

Ближайший правильный вектор:

1. Вынести общий `NeighborTransport` для физических nearby-линков. Базовый интерфейс уже введён: он описывает discovery, advertising, connect/accept/reject/disconnect, bytes messages и streams без знания room/voice payload-ов.
2. Держать room protocol в `RoomTransport`: `WirePacket`, реклама комнаты и связь `PeerId <-> linkId` уже вынесены из нижнего `NearbyTransport`.
3. Держать voice media-plane отдельно: `NearbyVoiceTransport` использует тот же `NeighborTransport`, но видит только `BTVO` voice frames.
4. Для текстового MVP добавлен и подключен `MeshTransport`: он рекламирует peer-а как gateway (`BTM4`), находит gateway-и, принимает `MEMBER_JOINED` / `MEMBER_LEFT` / `PEER_DISCONNECTED` / `CHAT_MESSAGE`, ведет snapshot комнаты и flood-ит события по соседям через `NeighborTransport`.
5. Диалог создания комнаты сохраняет выбор `Nearby Star` / `MESHRA`; `RoomInfo.roomTransportMode` определяет, пойдет runtime через обычный `RoomTransport` или через `MeshTransport`.
6. Использовать Wi-Fi Aware как основной neighbor transport там, где он доступен.
7. Держать Nearby Connections как fallback и bootstrap/signaling.
8. Для voice сделать отдельную realtime-политику: bounded relay, короткий dedup-cache и без записи аудио в event log. В MESHRA-комнатах voice сейчас намеренно недоступен.

## Название протокола

Пока приложение называется PSHH MESHRA.

Если mesh-протокол будет выделен в отдельный модуль, MESHRA становится названием этого протокола/модуля. Приложение уже называется PSHH MESHRA, то есть имя продукта заранее оставляет MESHRA как сетевое ядро.

Коротко: Aware, Nearby и Wi-Fi Direct - это радиоканалы и neighbor discovery. Mesh - это наш протокол поверх них.
